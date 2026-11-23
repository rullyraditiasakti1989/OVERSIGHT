package com.vector.console.data

/** Pure taxonomy logic shared by the app and JVM tests. No Android APIs here. */
object CctvTaxonomy {

    val rows: List<TaxRow> = taxonomyRows
    private val bySource: Map<String, TaxRow> = rows.associateBy { it.source }

    val provinces: Set<String> =
        setOf(
            "Jawa Barat", "Jawa Tengah", "DI Yogyakarta", "DKI Jakarta", "Jawa Timur",
            "Banten", "Sumatera", "Bali & Nusa", "Kalimantan", "Sulawesi", "Papua",
            "Nasional", "Singapore", "California", "Ireland", "London", "Maluku",
            "Unmapped", "International"
        )

    data class Validation(
        val cameraCount: Int,
        val uniqueCameraIds: Int,
        val missingLeaf: Int,
        val leafCountSum: Int,
        val unmappedCount: Int
    )

    fun operatorIdFor(source: String): String =
        bySource[source]?.operatorId ?: "unmapped"

    fun breadcrumb(source: String): String {
        val row = bySource[source] ?: return "Unmapped / Unmapped / $source"
        return "${row.region} / ${row.province} / ${row.displayLabel}"
    }

    /** Asset fields are written by the generator; regex keeps this JVM-testable. */
    fun validateAsset(json: String): Validation {
        val ids = Regex("\"id\":\"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.toList()
        val leaves = Regex("\"operatorId\":\"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.toList()
        val knownLeaves = rows.map { it.operatorId }.toSet()
        val counts = leaves.groupingBy { it }.eachCount()
        return Validation(
            cameraCount = ids.size,
            uniqueCameraIds = ids.toSet().size,
            missingLeaf = leaves.count { it.isBlank() || it !in knownLeaves },
            leafCountSum = counts.values.sum(),
            unmappedCount = counts["unmapped"] ?: 0
        )
    }

    /** Reduced-motion is a multiplier, not a boolean. Clamp to 0..1. */
    fun scaledDuration(baseMillis: Long, scale: Float): Long {
        val clamped = scale.coerceIn(0f, 1f)
        return (baseMillis * clamped).toLong()
    }
}
