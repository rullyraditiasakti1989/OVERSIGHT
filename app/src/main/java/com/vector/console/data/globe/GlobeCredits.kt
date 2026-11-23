package com.vector.console.data.globe

/** One attribution line: [source] is the name, [note] the licence and what it feeds. */
data class Credit(val source: String, val note: String, val url: String? = null)

/**
 * Attribution for every source the globe draws.
 *
 * This is the whole legal surface of the layer: the Esri line must appear on the
 * canvas itself (the tile terms require attribution adjacent to the imagery), and
 * everything else is satisfied by the CREDITS block in Settings.
 */
object GlobeCredits {

    /** Required on the canvas, not just in Settings. */
    const val IMAGERY = "Imagery © Esri, Maxar, Earthstar Geographics"

    /** Printed under the scale bar when the sphere is live. */
    const val CANVAS = "$IMAGERY · MapLibre"

    val ALL: List<Credit> = listOf(
        Credit(
            "Esri World Imagery",
            "Basemap raster. Attribution required on the canvas.",
            "https://www.arcgis.com/home/item.html?id=10df2279f9684e4a9f6a7f08febac2a9"
        ),
        Credit(
            "MapLibre GL JS",
            "BSD-3-Clause. Globe renderer, vendored at assets/globe/.",
            "https://maplibre.org"
        ),
        Credit(
            "adsb.lol",
            "ODbL 1.0. Aircraft positions.",
            "https://adsb.lol"
        ),
        Credit(
            "CelesTrak",
            "Free, credit required. Two-line element sets.",
            "https://celestrak.org"
        ),
        Credit(
            "satellite.js",
            "MIT, © Shashwat Kandadai / Dinamicka. SGP4 port basis.",
            "https://github.com/shashwatak/satellite-js"
        ),
        Credit(
            "USGS Earthquake Hazards",
            "Public domain. Seismic events.",
            "https://earthquake.usgs.gov"
        ),
        Credit(
            "NASA FIRMS",
            "Open. Thermal anomalies. A MAP_KEY narrows the query; the keyless MODIS " +
                "global export is the fallback.",
            "https://firms.modaps.eosdis.nasa.gov"
        ),
        Credit(
            "Digitraffic AIS",
            "CC BY 4.0, Finnish Transport Agency. Vessel positions, Baltic coverage.",
            "https://www.digitraffic.fi/en/marine-traffic/"
        ),
        Credit(
            "AISStream",
            "Vessel positions over websocket. Optional key stored; not used in this build.",
            "https://aisstream.io"
        ),
        Credit(
            "KAIStream",
            "MIT, © 2026 Kaivy and contributors. Java-wide rail timetable, station index and service alerts.",
            "https://github.com/kaivyy/KAIStream"
        ),
        Credit(
            "Indonesia CCTV catalogue",
            "Public camera list bundled with the app.",
            null
        ),
        Credit(
            "God's Eye View",
            "MIT, © 2026 Bilawal Sidhu. Feed selection and SGP4 provenance.",
            "https://github.com/bilawalsidhu/gods-eye-view"
        )
    )

    /** Key-gated sources, listed in Settings with their requirement. */
    val REQUIRES_KEY: List<Credit> = listOf(
        Credit("NASA FIRMS", "MAP_KEY from the FIRMS profile page.", "https://firms.modaps.eosdis.nasa.gov/api/"),
        Credit("AISStream", "API key plus a websocket client.", "https://aisstream.io")
    )
}
