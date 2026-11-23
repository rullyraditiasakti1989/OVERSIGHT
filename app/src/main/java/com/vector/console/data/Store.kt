package com.vector.console.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vector.console.data.globe.LayerId
import com.vector.console.data.globe.LayerSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class CasePriority(val label: String) {
    LOW("LOW"),
    AMBER("MEDIUM"),
    RED("HIGH")
}

enum class CaseStatus(val label: String) {
    OPEN("OPEN"),
    ACTIVE("ACTIVE"),
    CLOSED("CLOSED")
}

data class CaseFile(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val subject: String,
    val priority: CasePriority = CasePriority.LOW,
    val status: CaseStatus = CaseStatus.OPEN,
    val notes: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

data class AuditItem(
    val id: String,
    val label: String,
    val detail: String,
    val weight: Int
)

/** One row in Settings → SOURCES. In-memory only: a restart means no fetch has happened. */
data class SourceStat(
    val atMillis: Long,
    val bytes: Long,
    val count: Int,
    val stale: Boolean
)

val auditItems = listOf(
    AuditItem(
        "two_factor",
        "Two-factor authentication",
        "Enabled on email, banking, and primary accounts.",
        20
    ),
    AuditItem(
        "unique_passwords",
        "Unique passwords",
        "No password reuse across services.",
        15
    ),
    AuditItem(
        "password_manager",
        "Password manager",
        "Uses a password manager, not plain notes.",
        10
    ),
    AuditItem(
        "private_phone",
        "Private number is not public",
        "Not posted openly on social profiles.",
        10
    ),
    AuditItem(
        "private_email",
        "Primary email is not public",
        "Uses an alias or dedicated email for sensitive services.",
        10
    ),
    AuditItem(
        "social_privacy",
        "Strict social privacy",
        "Review who can see contacts, location, and photos.",
        10
    ),
    AuditItem(
        "app_permissions",
        "Minimum app permissions",
        "Remove unnecessary location, contacts, and camera access.",
        10
    ),
    AuditItem(
        "broker_optout",
        "Data-broker opt-out",
        "Requested removal from people-search sites.",
        10
    ),
    AuditItem(
        "phishing_habit",
        "Anti-phishing habit",
        "Verify sender and links before opening.",
        5
    )
)

class Store(context: Context) {

    private val prefs = context.getSharedPreferences("vector_store", Context.MODE_PRIVATE)

    var auditChecked by mutableStateOf(loadAuditChecked())
        private set

    var auditCompleted by mutableStateOf(prefs.getBoolean("audit_completed", false))
        private set

    var cases by mutableStateOf(loadCases())
        private set

    var apiKeys by mutableStateOf(loadKeys())
        private set

    /** GLOBE settings. The layer mask and cadence survive process death; stats do not. */
    var globeLayers by mutableStateOf(loadGlobeLayers())
        private set

    var globeCadenceMs by mutableStateOf(
        prefs.getLong("globe_cadence", 60_000L).coerceIn(15_000L, 60_000L)
    )
        private set

    var globeStats by mutableStateOf<Map<LayerId, SourceStat>>(emptyMap())
        private set

    fun setGlobeLayer(layer: LayerId, on: Boolean) {
        globeLayers = if (on) globeLayers + layer else globeLayers - layer
        prefs.edit().putString("globe_layers", globeLayers.joinToString(",") { it.name }).apply()
    }

    fun setGlobeCadence(ms: Long) {
        globeCadenceMs = ms.coerceIn(15_000L, 60_000L)
        prefs.edit().putLong("globe_cadence", globeCadenceMs).apply()
    }

    /** Called once per layer refresh, including failures that served cached contacts. */
    fun recordGlobeFetch(snapshot: LayerSnapshot) {
        globeStats = globeStats + (
            snapshot.layer to SourceStat(
                atMillis = snapshot.fetchedAtMillis,
                bytes = snapshot.bytes,
                count = snapshot.count,
                stale = snapshot.stale
            )
        )
    }

    private fun loadGlobeLayers(): Set<LayerId> {
        val raw = prefs.getString("globe_layers", null) ?: return GLOBE_DEFAULT_LAYERS
        val parsed = raw.split(",").mapNotNull { name ->
            runCatching { LayerId.valueOf(name.trim()) }.getOrNull()
        }.toSet()
        // An empty stored set is a real choice ("I turned everything off"), not a
        // corrupt value — only an unreadable one falls back to the default.
        return parsed
    }

    fun key(name: String): String = apiKeys[name].orEmpty()

    fun setKey(name: String, value: String) {
        apiKeys = apiKeys + (name to value)
        prefs.edit().putString("key_$name", value).apply()
    }

    private fun loadKeys(): Map<String, String> =
        listOf(
            "hibp", "face_endpoint", "face_key", "osiris_base", "osiris_token",
            "firms_key", "ais_key", "tg_token", "tg_chat", "tg_webhook"
        ).associateWith { name ->
            prefs.getString("key_$name", "").orEmpty()
        }

    val auditScore: Int
        get() = auditItems
            .filter { it.id in auditChecked }
            .sumOf { it.weight }

    val auditControlCount: Int
        get() = auditChecked.size

    fun toggleAudit(id: String) {
        auditChecked = if (id in auditChecked) auditChecked - id else auditChecked + id
        prefs.edit().putString("audit_checked", auditChecked.joinToString(",")).apply()
    }

    fun saveAudit() {
        auditCompleted = true
        prefs.edit().putBoolean("audit_completed", true).apply()
    }

    fun saveCase(caseFile: CaseFile) {
        val next = cases.toMutableList()
        val index = next.indexOfFirst { it.id == caseFile.id }
        if (index >= 0) next[index] = caseFile.copy(updatedAt = System.currentTimeMillis())
        else next.add(0, caseFile.copy(updatedAt = System.currentTimeMillis()))
        cases = next
        persistCases()
    }

    fun deleteCase(id: String) {
        cases = cases.filterNot { it.id == id }
        persistCases()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
        auditChecked = emptySet()
        auditCompleted = false
        cases = emptyList()
        apiKeys = emptyMap()
        globeLayers = GLOBE_DEFAULT_LAYERS
        globeCadenceMs = 60_000L
        globeStats = emptyMap()
    }

    private fun loadAuditChecked(): Set<String> {
        val raw = prefs.getString("audit_checked", "") ?: ""
        return raw.split(",").filter { it.isNotBlank() }.toSet()
    }

    private fun loadCases(): List<CaseFile> {
        val raw = prefs.getString("cases", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(
                        CaseFile(
                            id = item.optString("id", UUID.randomUUID().toString()),
                            title = item.optString("title"),
                            subject = item.optString("subject"),
                            priority = runCatching {
                                CasePriority.valueOf(item.optString("priority"))
                            }.getOrDefault(CasePriority.LOW),
                            status = runCatching {
                                CaseStatus.valueOf(item.optString("status"))
                            }.getOrDefault(CaseStatus.OPEN),
                            notes = item.optString("notes"),
                            updatedAt = item.optLong("updatedAt", System.currentTimeMillis())
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun persistCases() {
        val array = JSONArray()
        cases.forEach { caseFile ->
            array.put(
                JSONObject().apply {
                    put("id", caseFile.id)
                    put("title", caseFile.title)
                    put("subject", caseFile.subject)
                    put("priority", caseFile.priority.name)
                    put("status", caseFile.status.name)
                    put("notes", caseFile.notes)
                    put("updatedAt", caseFile.updatedAt)
                }
            )
        }
        prefs.edit().putString("cases", array.toString()).apply()
    }

    companion object {
        val GLOBE_DEFAULT_LAYERS: Set<LayerId> =
            setOf(LayerId.AIRCRAFT, LayerId.SATELLITES, LayerId.QUAKES)
    }
}
