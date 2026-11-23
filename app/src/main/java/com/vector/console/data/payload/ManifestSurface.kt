package com.vector.console.data.payload

/**
 * Reads the component surface out of a **binary** AndroidManifest.
 *
 * The manifest inside a built APK is a chunked binary document, so a test cannot just regex it.
 * This walks the element stream and collects the `activity`, `service`, `receiver` and `provider`
 * children of `<application>` with their `exported` and `permission` attributes.
 *
 * Two distinctions are the whole point and are kept apart on purpose:
 *
 *  - **reachable** — `android:exported="true"`. Required for the launch activity, the boot
 *    receiver and the accessibility binding: without the flag the feature silently never fires.
 *  - **open** — reachable *and* no `android:permission`. Correct for the first two; wrong for the
 *    accessibility service, which Settings binds from another uid and which the platform refuses
 *    to bind without `BIND_ACCESSIBILITY_SERVICE`.
 *
 * A manifest it cannot parse returns `null` rather than an empty list, so a broken build fails
 * loudly instead of reporting a clean surface.
 */
internal object ManifestSurface {

    private const val CHUNK_XML = 0x0003
    private const val CHUNK_START_ELEMENT = 0x0102
    private const val CHUNK_END_ELEMENT = 0x0103

    private const val NODE_HEADER = 16
    private const val ATTR_EXT_NAME = 20
    private const val ATTR_EXT_START = 24
    private const val ATTR_EXT_STRIDE = 26
    private const val ATTR_EXT_COUNT = 28
    private const val ATTR_SIZE_MIN = 20
    private const val ATTR_NAME = 4
    private const val ATTR_RAW = 8
    private const val ATTR_DATA = 16

    private val COMPONENT_KINDS = setOf("activity", "activity-alias", "service", "receiver", "provider")

    data class Component(
        val kind: String,
        val name: String,
        val exported: Boolean,
        val guarded: Boolean
    ) {
        /** Reachable from outside the app and protected by nothing. */
        val open: Boolean get() = exported && !guarded
    }

    fun components(manifest: ByteArray): List<Component>? {
        if (manifest.size < 8 || readU16(manifest, 0) != CHUNK_XML) return null
        val strings = Axml.strings(manifest) ?: return null
        val rootSize = readU32(manifest, 4)
        if (rootSize <= 0 || rootSize > manifest.size) return null

        val found = mutableListOf<Component>()
        val stack = ArrayDeque<String>()
        var cursor = readU16(manifest, 2)

        while (cursor + 8 <= manifest.size && cursor < rootSize) {
            val type = readU16(manifest, cursor)
            val size = readU32(manifest, cursor + 4)
            if (size <= 0 || cursor + size > manifest.size) return null

            when (type) {
                CHUNK_START_ELEMENT -> {
                    val name = strings.getOrNull(readU32(manifest, cursor + ATTR_EXT_NAME)) ?: return null
                    // Components live one level under <application>.
                    if (stack.lastOrNull() == "application" && name in COMPONENT_KINDS) {
                        val attributes = attributes(manifest, cursor, strings)
                        found += Component(
                            kind = name,
                            name = attributes["name"].orEmpty(),
                            exported = attributes["exported"].toBoolean(),
                            guarded = !attributes["permission"].isNullOrEmpty()
                        )
                    }
                    stack.addLast(name)
                }
                CHUNK_END_ELEMENT -> stack.removeLastOrNull()
            }
            cursor += size
        }
        return found
    }

    /** Element attributes keyed by local name. The android namespace is the only one used here. */
    private fun attributes(manifest: ByteArray, node: Int, strings: List<String>): Map<String, String> {
        val count = readU16(manifest, node + ATTR_EXT_COUNT)
        val start = readU16(manifest, node + ATTR_EXT_START)
        val stride = readU16(manifest, node + ATTR_EXT_STRIDE).coerceAtLeast(ATTR_SIZE_MIN)
        if (count <= 0) return emptyMap()

        val out = mutableMapOf<String, String>()
        val base = node + NODE_HEADER + start
        for (index in 0 until count) {
            val at = base + index * stride
            if (at + ATTR_SIZE_MIN > manifest.size) return out
            val key = strings.getOrNull(readU32(manifest, at + ATTR_NAME)) ?: continue
            val raw = readU32(manifest, at + ATTR_RAW)
            val data = readU32(manifest, at + ATTR_DATA)
            out[key] = when {
                raw >= 0 && raw < strings.size -> strings[raw]
                data == -1 -> "true"
                data == 0 -> "false"
                else -> ""
            }
        }
        return out
    }

    private fun readU16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun readU32(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)
}
