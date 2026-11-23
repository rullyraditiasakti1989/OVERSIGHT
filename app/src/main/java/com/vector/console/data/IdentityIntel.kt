package com.vector.console.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Parsed NIK. Every field is derived locally — nothing leaves the device. */
data class NikProfile(
    val nik: String,
    val provinceCode: String,
    val regencyCode: String,
    val districtCode: String,
    val province: String?,
    val regency: String?,
    val birthDate: String,
    val age: Int,
    val gender: String,
    val sequence: String,
    val postalArea: String,
    val issues: List<String>
) {
    val valid: Boolean get() = issues.isEmpty()
}

data class PhoneProfile(
    val input: String,
    val e164: String,
    val national: String,
    val countryCode: String,
    val operator: String?,
    val lineType: String,
    val valid: Boolean,
    val note: String?
)

/** A keyless probe can say "yes", "no" — or honestly "cannot tell". */
enum class PresenceVerdict { PRESENT, ABSENT, UNKNOWN }

data class PresenceResult(
    val channel: String,
    val identifier: String,
    val registered: Boolean,
    val detail: String,
    val verdict: PresenceVerdict = if (registered) PresenceVerdict.PRESENT else PresenceVerdict.ABSENT
)

data class IntakeLink(val label: String, val url: String)

/** One upstream dataset named by a keyless leak index. */
data class LeakSource(val name: String, val date: String)

/**
 * Result of a keyless leak lookup. Nothing here needs an API key, so the
 * Identity screen works on a clean install with no Settings configuration.
 */
data class LeakReport(
    val provider: String,
    val identifier: String,
    val found: Long,
    val fields: List<String>,
    val sources: List<LeakSource>,
    val note: String? = null
) {
    val exposed: Boolean get() = found > 0
}

/** One `account:secret` line from the public COMBO corpus. */
data class CredentialHit(val account: String, val secret: String)

/** One machine from the infostealer index. */
data class StealerHit(val machine: String, val malware: String, val date: String, val antivirus: String)

/**
 * Result of a Cavalier lookup. [infected] is the headline: the identifier was on
 * a compromised machine at some point.
 */
data class StealerReport(
    val identifier: String,
    val infected: Boolean,
    val machines: List<StealerHit>,
    val credentials: List<CredentialHit>,
    val count: Int,
    val note: String?
)

data class BreachHit(
    val name: String,
    val title: String,
    val domain: String,
    val breachDate: String,
    val pwnCount: Long,
    val dataClasses: List<String>,
    val verified: Boolean,
    val sensitive: Boolean
)

object IdentityIntel {

    // BPS province codes. Two-digit prefix of every NIK.
    private val provinces = mapOf(
        "11" to "Aceh",
        "12" to "Sumatera Utara",
        "13" to "Sumatera Barat",
        "14" to "Riau",
        "15" to "Jambi",
        "16" to "Sumatera Selatan",
        "17" to "Bengkulu",
        "18" to "Lampung",
        "19" to "Kepulauan Bangka Belitung",
        "21" to "Kepulauan Riau",
        "31" to "DKI Jakarta",
        "32" to "Jawa Barat",
        "33" to "Jawa Tengah",
        "34" to "DI Yogyakarta",
        "35" to "Jawa Timur",
        "36" to "Banten",
        "51" to "Bali",
        "52" to "Nusa Tenggara Barat",
        "53" to "Nusa Tenggara Timur",
        "61" to "Kalimantan Barat",
        "62" to "Kalimantan Tengah",
        "63" to "Kalimantan Selatan",
        "64" to "Kalimantan Timur",
        "65" to "Kalimantan Utara",
        "71" to "Sulawesi Utara",
        "72" to "Sulawesi Tengah",
        "73" to "Sulawesi Selatan",
        "74" to "Sulawesi Tenggara",
        "75" to "Gorontalo",
        "76" to "Sulawesi Barat",
        "81" to "Maluku",
        "82" to "Maluku Utara",
        "91" to "Papua",
        "92" to "Papua Barat",
        "93" to "Papua Selatan",
        "94" to "Papua Tengah",
        "95" to "Papua Pegunungan",
        "96" to "Papua Barat Daya"
    )

    // Kota (all 98) plus the administrative kabupaten the code prefix resolves to.
    // Kabupaten detail below the regency level needs the full BPS wilayah dataset —
    // drop wilayah.json into assets to extend, see WilayahDataset.
    private val regencies = mapOf(
        "3101" to "Kab. Kepulauan Seribu",
        "3171" to "Kota Jakarta Selatan",
        "3172" to "Kota Jakarta Timur",
        "3173" to "Kota Jakarta Pusat",
        "3174" to "Kota Jakarta Barat",
        "3175" to "Kota Jakarta Utara",
        "1171" to "Kota Banda Aceh",
        "1172" to "Kota Sabang",
        "1173" to "Kota Langsa",
        "1174" to "Kota Lhokseumawe",
        "1175" to "Kota Subulussalam",
        "1271" to "Kota Medan",
        "1272" to "Kota Pematang Siantar",
        "1273" to "Kota Sibolga",
        "1274" to "Kota Tanjungbalai",
        "1275" to "Kota Binjai",
        "1276" to "Kota Tebing Tinggi",
        "1277" to "Kota Padang Sidempuan",
        "1278" to "Kota Gunungsitoli",
        "1371" to "Kota Padang",
        "1372" to "Kota Solok",
        "1373" to "Kota Sawahlunto",
        "1374" to "Kota Padang Panjang",
        "1375" to "Kota Bukittinggi",
        "1376" to "Kota Payakumbuh",
        "1377" to "Kota Pariaman",
        "1471" to "Kota Pekanbaru",
        "1472" to "Kota Dumai",
        "1571" to "Kota Jambi",
        "1572" to "Kota Sungai Penuh",
        "1671" to "Kota Palembang",
        "1672" to "Kota Pagar Alam",
        "1673" to "Kota Lubuk Linggau",
        "1674" to "Kota Prabumulih",
        "1771" to "Kota Bengkulu",
        "1871" to "Kota Bandar Lampung",
        "1872" to "Kota Metro",
        "1971" to "Kota Pangkal Pinang",
        "2171" to "Kota Batam",
        "2172" to "Kota Tanjung Pinang",
        "3271" to "Kota Bogor",
        "3272" to "Kota Sukabumi",
        "3273" to "Kota Bandung",
        "3274" to "Kota Cimahi",
        "3275" to "Kota Bekasi",
        "3276" to "Kota Depok",
        "3277" to "Kota Cirebon",
        "3278" to "Kota Tasikmalaya",
        "3279" to "Kota Banjar",
        "3371" to "Kota Magelang",
        "3372" to "Kota Surakarta",
        "3373" to "Kota Pekalongan",
        "3374" to "Kota Semarang",
        "3375" to "Kota Salatiga",
        "3376" to "Kota Tegal",
        "3471" to "Kota Yogyakarta",
        "3571" to "Kota Kediri",
        "3572" to "Kota Blitar",
        "3573" to "Kota Malang",
        "3574" to "Kota Probolinggo",
        "3575" to "Kota Pasuruan",
        "3576" to "Kota Mojokerto",
        "3577" to "Kota Madiun",
        "3578" to "Kota Surabaya",
        "3579" to "Kota Batu",
        "3671" to "Kota Tangerang",
        "3672" to "Kota Cilegon",
        "3673" to "Kota Serang",
        "3674" to "Kota Tangerang Selatan",
        "5171" to "Kota Denpasar",
        "5271" to "Kota Mataram",
        "5272" to "Kota Bima",
        "5371" to "Kota Kupang",
        "6171" to "Kota Pontianak",
        "6172" to "Kota Singkawang",
        "6271" to "Kota Palangka Raya",
        "6371" to "Kota Banjarmasin",
        "6372" to "Kota Banjarbaru",
        "6471" to "Kota Samarinda",
        "6472" to "Kota Bontang",
        "6474" to "Kota Balikpapan",
        "6571" to "Kota Tarakan",
        "7171" to "Kota Manado",
        "7172" to "Kota Bitung",
        "7173" to "Kota Tomohon",
        "7174" to "Kota Kotamobagu",
        "7271" to "Kota Palu",
        "7371" to "Kota Makassar",
        "7372" to "Kota Parepare",
        "7373" to "Kota Palopo",
        "7471" to "Kota Kendari",
        "7472" to "Kota Baubau",
        "7571" to "Kota Gorontalo",
        "8171" to "Kota Ambon",
        "8172" to "Kota Tual",
        "8271" to "Kota Ternate",
        "8272" to "Kota Tidore Kepulauan",
        "9171" to "Kota Jayapura"
    )

    private val operatorPrefixes = linkedMapOf(
        "0811" to "Telkomsel", "0812" to "Telkomsel", "0813" to "Telkomsel",
        "0821" to "Telkomsel", "0822" to "Telkomsel", "0823" to "Telkomsel",
        "0851" to "Telkomsel", "0852" to "Telkomsel", "0853" to "Telkomsel",
        "0814" to "Indosat Ooredoo", "0815" to "Indosat Ooredoo", "0816" to "Indosat Ooredoo",
        "0855" to "Indosat Ooredoo", "0856" to "Indosat Ooredoo",
        "0857" to "Indosat Ooredoo", "0858" to "Indosat Ooredoo",
        "0817" to "XL Axiata", "0818" to "XL Axiata", "0819" to "XL Axiata",
        "0859" to "XL Axiata", "0877" to "XL Axiata", "0878" to "XL Axiata",
        "0831" to "Axis", "0832" to "Axis", "0833" to "Axis", "0838" to "Axis",
        "0895" to "Tri", "0896" to "Tri", "0897" to "Tri", "0898" to "Tri", "0899" to "Tri",
        "0881" to "Smartfren", "0882" to "Smartfren", "0883" to "Smartfren",
        "0884" to "Smartfren", "0885" to "Smartfren", "0886" to "Smartfren",
        "0887" to "Smartfren", "0888" to "Smartfren", "0889" to "Smartfren"
    )

    // ---------------------------------------------------------------- NIK

    fun parseNik(raw: String): NikProfile {
        val nik = raw.filter { it.isDigit() }
        val issues = mutableListOf<String>()
        if (nik.length != 16) issues += "Panjang NIK harus 16 digit (ditemukan ${nik.length})."

        val padded = nik.padEnd(16, '0')
        val provinceCode = padded.substring(0, 2)
        val regencyCode = padded.substring(0, 4)
        val districtCode = padded.substring(4, 6)

        var day = padded.substring(6, 8).toIntOrNull() ?: 0
        val month = padded.substring(8, 10).toIntOrNull() ?: 0
        val shortYear = padded.substring(10, 12).toIntOrNull() ?: 0
        val sequence = padded.substring(12, 16)

        val female = day > 40
        if (female) day -= 40
        if (month !in 1..12) issues += "Invalid birth month (${padded.substring(8, 10)})."
        if (day !in 1..31) issues += "Invalid birth date."

        val currentYear = LocalDate.now().year
        val century = if (shortYear > currentYear % 100) 1900 else 2000
        val year = century + shortYear

        var birthDate = "—"
        var age = 0
        val parsed = runCatching {
            val date = LocalDate.of(year, month.coerceIn(1, 12), day.coerceIn(1, 28))
            birthDate = date.format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.forLanguageTag("id-ID")))
            age = Period.between(date, LocalDate.now()).years
        }
        if (parsed.isFailure) issues += "Birth date could not be reconstructed."

        val province = provinces[provinceCode]
        if (province == null) issues += "Unrecognized province code $provinceCode."

        val regency = regencies[regencyCode]
        val postal = "${provinceCode}${padded.substring(2, 4)}-${districtCode}"

        return NikProfile(
            nik = nik,
            provinceCode = provinceCode,
            regencyCode = regencyCode,
            districtCode = districtCode,
            province = province,
            regency = regency,
            birthDate = birthDate,
            age = age,
            gender = if (female) "PEREMPUAN" else "LAKI-LAKI",
            sequence = sequence,
            postalArea = postal,
            issues = issues
        )
    }

    // -------------------------------------------------------------- phone

    fun parsePhone(raw: String): PhoneProfile {
        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) {
            return PhoneProfile(raw, "", "", "", null, "UNKNOWN", false, "No digits.")
        }
        val e164 = when {
            digits.startsWith("62") -> "+$digits"
            digits.startsWith("0") -> "+62${digits.drop(1)}"
            digits.startsWith("8") -> "+62$digits"
            else -> "+$digits"
        }
        val countryCode = if (e164.startsWith("+62")) "+62" else e164.take(4)
        val national = if (e164.startsWith("+62")) "0${e164.removePrefix("+62")}" else e164.removePrefix("+")
        val valid = e164.startsWith("+62") && national.length in 10..13

        val prefix4 = national.take(4)
        val operator = operatorPrefixes[prefix4]
            ?: operatorPrefixes.entries.firstOrNull { prefix4.startsWith(it.key.take(3)) }?.value

        val note = when {
            !e164.startsWith("+62") -> "Outside Indonesia — operator not mapped."
            !valid -> "Number length is unusual for Indonesia."
            operator == null -> "Prefix $prefix4 is not in the operator table."
            else -> null
        }

        return PhoneProfile(
            input = raw,
            e164 = e164,
            national = national,
            countryCode = countryCode,
            operator = operator,
            lineType = "MOBILE",
            valid = valid,
            note = note
        )
    }

    // ---------------------------------------------------- presence probes

    suspend fun whatsapp(phone: PhoneProfile): PresenceResult = withContext(Dispatchers.IO) {
        val target = phone.e164.removePrefix("+")
        val response = Net.getOrNull("https://api.whatsapp.com/send?phone=$target&text=&lang=id")
        val verdict = whatsappVerdict(response.status, response.body)
        PresenceResult(
            channel = "WHATSAPP",
            identifier = phone.e164,
            registered = verdict == PresenceVerdict.PRESENT,
            verdict = verdict,
            detail = when (verdict) {
                PresenceVerdict.ABSENT -> "WhatsApp rejects this number as invalid."
                PresenceVerdict.UNKNOWN -> if (response.status !in 200..299) {
                    "Probe failed (HTTP ${response.status})."
                } else {
                    "Deep link accepted - registration cannot be confirmed without WhatsApp's API."
                }
                PresenceVerdict.PRESENT -> "Number is registered on WhatsApp."
            }
        )
    }

    /**
     * `api.whatsapp.com/send` answers 200 and a full page even for an unregistered
     * number. Only the explicit invalid-number markers mean anything keylessly,
     * so a clean page is UNKNOWN and must never be rendered as PRESENT.
     */
    fun whatsappVerdict(status: Int, body: String): PresenceVerdict = when {
        status !in 200..299 -> PresenceVerdict.UNKNOWN
        body.contains("Phone number shared via url is invalid", ignoreCase = true) ||
            body.contains("invalid_number", ignoreCase = true) -> PresenceVerdict.ABSENT
        else -> PresenceVerdict.UNKNOWN
    }

    suspend fun telegramByUsername(username: String): PresenceResult = withContext(Dispatchers.IO) {
        val handle = username.trim().removePrefix("@").removePrefix("https://t.me/").removePrefix("t.me/")
        if (handle.isBlank()) {
            return@withContext PresenceResult("TELEGRAM", username, false, "Empty username.")
        }
        val response = Net.getOrNull("https://t.me/$handle")
        val exists = response.status == 200 && response.body.contains("tgme_page_title")
        val title = Regex("<div class=\"tgme_page_title\"[^>]*>\\s*<span[^>]*>([^<]+)</span>")
            .find(response.body)?.groupValues?.getOrNull(1)?.trim()
        val verdict = when {
            exists -> PresenceVerdict.PRESENT
            response.status == 404 -> PresenceVerdict.ABSENT
            else -> PresenceVerdict.UNKNOWN
        }
        PresenceResult(
            channel = "TELEGRAM",
            identifier = "@$handle",
            registered = exists,
            verdict = verdict,
            detail = when (verdict) {
                PresenceVerdict.ABSENT -> "Username is not in use."
                PresenceVerdict.PRESENT -> if (!title.isNullOrBlank()) "Akun publik: $title" else "Public account without a display name."
                PresenceVerdict.UNKNOWN -> "Probe failed (HTTP ${response.status})."
            }
        )
    }

    suspend fun telegramByPhone(phone: PhoneProfile): PresenceResult = withContext(Dispatchers.IO) {
        val target = phone.e164.removePrefix("+")
        val response = Net.getOrNull("https://t.me/+$target")
        val title = Regex("og:title\" content=\"([^\"]+)\"")
            .find(response.body)?.groupValues?.getOrNull(1)?.trim()
        val verdict = telegramPhoneVerdict(response.status, title)
        PresenceResult(
            channel = "TELEGRAM",
            identifier = phone.e164,
            registered = verdict == PresenceVerdict.PRESENT,
            verdict = verdict,
            detail = when (verdict) {
                PresenceVerdict.PRESENT -> "Public profile: $title"
                PresenceVerdict.UNKNOWN -> "Generic contact page - registration cannot be confirmed keylessly."
                PresenceVerdict.ABSENT -> "Telegram does not serve a contact page for this number."
            }
        )
    }

    /**
     * Measured against a number with no Telegram: t.me answers 200 with
     * `<title>Telegram: Contact</title>` and `og:title "Join group chat on Telegram"`.
     * The old check only rejected the exact string `Telegram: Contact`, so the
     * generic join page was graded PRESENT. Generic titles now read UNKNOWN; only
     * a title that names something is a positive signal.
     */
    fun telegramPhoneVerdict(status: Int, ogTitle: String?): PresenceVerdict {
        if (status !in 200..299 || ogTitle.isNullOrBlank()) return PresenceVerdict.UNKNOWN
        val generic = listOf("telegram", "join group chat", "join channel", "contact")
            .any { ogTitle.contains(it, ignoreCase = true) }
        return if (generic) PresenceVerdict.UNKNOWN else PresenceVerdict.PRESENT
    }

    // ------------------------------------------------- keyless leak APIs

    private fun enc(value: String): String =
        java.net.URLEncoder.encode(value.trim(), "UTF-8")

    /**
     * LeakCheck public endpoint. No key, no account, accepts an email, a phone
     * number or any query string. Answers `success:false / error:"Not found"`
     * when the identifier is absent, which is a clean zero.
     */
    suspend fun leakCheck(query: String): LeakReport = withContext(Dispatchers.IO) {
        val identifier = query.trim()
        require(identifier.isNotBlank()) { "Identifier is empty." }
        // LeakCheck indexes phone numbers without the leading plus, so an E.164
        // input has to be stripped or every number looks clean.
        val target = identifier.removePrefix("+")
        val response = Net.getOrNull("https://leakcheck.io/api/public?check=${enc(target)}")
        if (response.status !in 200..299) {
            throw IllegalStateException("LeakCheck failed (HTTP ${response.status}).")
        }
        val root = JSONObject(response.body)
        if (!root.optBoolean("success", false)) {
            return@withContext LeakReport(
                provider = "LeakCheck",
                identifier = identifier,
                found = 0,
                fields = emptyList(),
                sources = emptyList()
            )
        }
        val sources = root.optJSONArray("sources")?.let { array ->
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(LeakSource(item.optString("name"), item.optString("date")))
                }
            }
        }.orEmpty()
        LeakReport(
            provider = "LeakCheck",
            identifier = identifier,
            found = root.optLong("found"),
            fields = root.optJSONArray("fields").stringList(),
            sources = sources.sortedByDescending { it.date }
        )
    }

    /**
     * XposedOrNot checker. Email only — it answers `{"Error":"Not found"}` for
     * every other identifier shape, so callers gate on the `@`.
     */
    suspend fun xposedOrNot(email: String): LeakReport = withContext(Dispatchers.IO) {
        val identifier = email.trim().lowercase()
        require(identifier.contains("@")) { "XposedOrNot takes an email address." }
        val response = Net.getOrNull("https://api.xposedornot.com/v1/check-email/${enc(identifier)}")
        if (response.status !in 200..299) {
            throw IllegalStateException("XposedOrNot failed (HTTP ${response.status}).")
        }
        val root = JSONObject(response.body)
        if (root.has("Error")) {
            return@withContext LeakReport(
                provider = "XposedOrNot",
                identifier = identifier,
                found = 0,
                fields = emptyList(),
                sources = emptyList()
            )
        }
        val names = root.optJSONArray("breaches")?.let { outer ->
            outer.optJSONArray(0)?.let { inner -> inner.stringList() }
        }.orEmpty()
        LeakReport(
            provider = "XposedOrNot",
            identifier = identifier,
            found = names.size.toLong(),
            fields = emptyList(),
            sources = names.map { LeakSource(it, "") }
        )
    }

    /**
     * ProxyNova COMBO corpus — publicly served `account:secret` pairs. Returns
     * the raw matches plus the upstream total so the caller can show the size of
     * the corpus hit without dumping everything.
     */
    suspend fun combLookup(query: String, limit: Int = 25): Pair<Long, List<CredentialHit>> =
        withContext(Dispatchers.IO) {
            val identifier = query.trim()
            require(identifier.isNotBlank()) { "Identifier is empty." }
            val response = Net.getOrNull(
                "https://api.proxynova.com/comb?query=${enc(identifier)}&start=0&limit=$limit"
            )
            if (response.status !in 200..299) {
                throw IllegalStateException("COMBO lookup failed (HTTP ${response.status}).")
            }
            val root = JSONObject(response.body)
            val total = root.optLong("count")
            val lines = root.optJSONArray("lines").stringList()
            val hits = lines.mapNotNull { line ->
                val split = line.indexOf(':')
                if (split <= 0) null
                else CredentialHit(line.substring(0, split), line.substring(split + 1))
            }
            total to hits
        }

    /**
     * HudsonRock Cavalier — free, keyless index of infostealer logs. Answers an
     * email or a username (no phone, no telegram). A positive hit means the
     * identifier sat on a machine with a stealer, which is a stronger signal
     * than a plain breach: the credentials were harvested, not just stored.
     */
    suspend fun stealerLog(identifier: String, byEmail: Boolean): StealerReport =
        withContext(Dispatchers.IO) {
            val target = identifier.trim()
            require(target.isNotBlank()) { "Identifier is empty." }
            val query = if (byEmail) "email" else "username"
            val response = Net.getOrNull(
                "https://cavalier.hudsonrock.com/api/json/v2/osint-tools/search-by-$query?$query=${
                    java.net.URLEncoder.encode(target, "UTF-8")
                }"
            )
            if (response.status !in 200..299) {
                return@withContext StealerReport(target, false, emptyList(), emptyList(), 0,
                    "Cavalier did not answer (HTTP ${response.status}).")
            }
            val root = runCatching { JSONObject(response.body) }
                .getOrElse { return@withContext StealerReport(target, false, emptyList(), emptyList(), 0, "Balasan bukan JSON.") }
            val data = root.optJSONObject("data")
            val stealers = data?.optJSONArray("stealers")
            val users = data?.optJSONArray("users")
            val infected = (stealers?.length() ?: 0) > 0 || (users?.length() ?: 0) > 0
            val rows = buildList {
                if (stealers != null) {
                    for (i in 0 until stealers.length()) {
                        val item = stealers.optJSONObject(i) ?: continue
                        add(
                            StealerHit(
                                machine = item.optString("computerName"),
                                malware = item.optString("malwarePath"),
                                date = item.optString("dateCompromised"),
                                antivirus = item.optString("antivirus")
                            )
                        )
                    }
                }
            }
            val credentials = buildList {
                val pairs = data?.optJSONArray("credentials")
                if (pairs != null) {
                    for (i in 0 until pairs.length()) {
                        val item = pairs.optJSONObject(i) ?: continue
                        val link = item.optString("url")
                        val user = item.optString("username")
                        if (link.isNotBlank() || user.isNotBlank()) {
                            add(CredentialHit(link.ifBlank { user }, user))
                        }
                    }
                }
                if (isEmpty() && users != null) {
                    for (i in 0 until users.length()) {
                        val item = users.optJSONObject(i) ?: continue
                        val user = item.optString("username")
                        if (user.isNotBlank()) add(CredentialHit(item.optString("url").ifBlank { "—" }, user))
                    }
                }
            }
            StealerReport(target, infected, rows, credentials, stealers?.length() ?: 0, null)
        }

    // ------------------------------------------------------ public registries

    /** Public pages that accept a NIK. None of these needs an account. */
    fun registryLinks(nik: String): List<IntakeLink> {
        val encoded = java.net.URLEncoder.encode(nik.filter { it.isDigit() }, "UTF-8")
        return listOf(
            IntakeLink("DPT KPU", "https://cekdptonline.kpu.go.id/"),
            IntakeLink("Dukcapil", "https://disdukcapil.kemendagri.go.id/"),
            IntakeLink("AHU Kemenkumham", "https://ahu.go.id/"),
            IntakeLink("LeakCheck", "https://leakcheck.io/?query=$encoded"),
            IntakeLink("HIBP", "https://haveibeenpwned.com/account/$encoded")
        )
    }

    /** Breach aggregators that accept an email, phone, or username in a URL. */
    fun breachLinks(account: String): List<IntakeLink> {
        val encoded = java.net.URLEncoder.encode(account.trim().removePrefix("+"), "UTF-8")
        return listOf(
            IntakeLink("LeakCheck", "https://leakcheck.io/?query=$encoded"),
            IntakeLink("Intelligence X", "https://intelx.io/?s=$encoded"),
            IntakeLink("DeHashed", "https://dehashed.com/search?query=$encoded"),
            IntakeLink("HIBP", "https://haveibeenpwned.com/account/$encoded")
        )
    }

    // ----------------------------------------------------------- breaches

    suspend fun breachesFor(email: String, apiKey: String): List<BreachHit> =
        breachesForAccount(email, apiKey, requireEmail = true)

    /**
     * [account] may be an email, a phone number, or a username. HIBP answers 404
     * when the account is absent, which is a clean empty result.
     */
    suspend fun breachesForAccount(
        account: String,
        apiKey: String,
        requireEmail: Boolean = false
    ): List<BreachHit> = withContext(Dispatchers.IO) {
        val normalized = account.trim().lowercase()
        require(normalized.isNotBlank()) { "Identifier is empty." }
        if (requireEmail) require(normalized.contains("@")) { "Invalid email format." }
        require(apiKey.isNotBlank()) { "Have I Been Pwned API key is not set in Settings." }

        val response = Net.request(
            url = "https://haveibeenpwned.com/api/v3/breachedaccount/" +
                java.net.URLEncoder.encode(normalized, "UTF-8") +
                "?truncateResponse=false",
            headers = mapOf(
                "hibp-api-key" to apiKey,
                "Accept" to "application/json"
            )
        )
        when (response.status) {
            404 -> return@withContext emptyList()
            401 -> throw IllegalStateException("API key rejected. Check Settings.")
            429 -> throw IllegalStateException("HIBP rate limit reached. Wait a minute.")
        }
        if (response.status !in 200..299) throw IllegalStateException("HIBP failed (HTTP ${response.status}).")

        val array = JSONArray(response.body)
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(
                    BreachHit(
                        name = item.optString("Name"),
                        title = item.optString("Title"),
                        domain = item.optString("Domain"),
                        breachDate = item.optString("BreachDate"),
                        pwnCount = item.optLong("PwnCount"),
                        dataClasses = item.optJSONArray("DataClasses").stringList(),
                        verified = item.optBoolean("IsVerified"),
                        sensitive = item.optBoolean("IsSensitive")
                    )
                )
            }
        }.sortedByDescending { it.breachDate }
    }

    private fun JSONArray?.stringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (i in 0 until length()) optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
        }
    }

    // ------------------------------------------------------------ helpers

    /** Masks the DOB segment (digits 7-12) — the part that identifies a person. */
    fun maskNik(nik: String): String {
        if (nik.length != 16) return nik
        return nik.substring(0, 6) + "******" + nik.substring(12)
    }

    fun csv(rows: List<List<String>>): String = buildString {
        rows.forEach { row ->
            append(row.joinToString(",") { cell -> "\"${cell.replace("\"", "\"\"")}\"" })
            append("\r\n")
        }
    }

    fun jsonObject(pairs: Map<String, Any?>): String {
        val obj = JSONObject()
        pairs.forEach { (key, value) -> obj.put(key, value ?: JSONObject.NULL) }
        return obj.toString(2)
    }
}
