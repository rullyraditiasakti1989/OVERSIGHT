package com.vector.console.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * One phone-x.net account in the rotation pool.
 *
 * Upstream bills every investigation in tokens and a fresh account starts with
 * a two-token welcome grant, so a single account runs dry after two lookups.
 * [blockedAt] parks an account the moment the upstream answers 402/401 so the
 * next call rotates instead of retrying a wallet that is already empty.
 */
data class PhoneXAccount(
    val email: String,
    val password: String,
    val token: String = "",
    val balance: Long = -1,
    val blockedAt: Long = 0L,
    val note: String = ""
) {
    val blocked: Boolean get() = blockedAt > 0L
}

/**
 * Bridge to the phone-x.net provider backend plus the account farm that feeds it.
 *
 * Routes are `POST /api/phone/<module>` with `{"phoneNumber": "+62..."}` and a
 * bearer token. When an account is empty the pool rotates to the next one; when
 * the whole pool is empty the caller gets 402 and can register more accounts
 * through [requestOtp] / [completeRegistration].
 *
 * Registration is the upstream's own email OTP flow, in three steps:
 * request OTP, verify OTP for a verify token, then set the password. The code
 * arrives in the mailbox, so it is a user-supplied value, not something the app
 * can invent.
 */
internal object PhoneX {

    const val BASE = "https://phone-x.net"

    const val MODULE_HLR = "hlr"
    const val MODULE_WHATSAPP = "whatsapp"
    const val MODULE_TELEGRAM = "telegram"
    const val MODULE_GETCONTACT = "getcontact"
    const val MODULE_EWALLET = "ewallet"
    const val MODULE_BREACH = "breach"
    const val MODULE_HUMINT = "humint"
    const val MODULE_EVIDENCE = "public-evidence"

    /** Display order and score weight, ported from the upstream signal table. */
    val MODULES = listOf(
        ModuleSpec(MODULE_HLR, "HLR", 10),
        ModuleSpec(MODULE_WHATSAPP, "WhatsApp", 15),
        ModuleSpec(MODULE_TELEGRAM, "Telegram", 15),
        ModuleSpec(MODULE_GETCONTACT, "GetContact", 20),
        ModuleSpec(MODULE_EWALLET, "E-Wallet", 15),
        ModuleSpec(MODULE_BREACH, "Breach", 0),
        ModuleSpec(MODULE_HUMINT, "HUMINT", 10),
        ModuleSpec(MODULE_EVIDENCE, "Public evidence", 0)
    )

    /** Upstream rejects with 429 when this IP has burned the auth/lookup window. */
    class RateLimited(message: String) : Exception(message)

    /** No usable account: empty pool, or the upstream refused the session. */
    class AuthRequired(message: String) : Exception(message)

    /** Whole pool out of tokens. */
    class EmptyWallet(message: String) : Exception(message)

    private val loginLock = Mutex()

    @Volatile
    private var storeRef: Store? = null

    private val memory = mutableListOf<PhoneXAccount>()

    /** Called once from the app root; [Store] is not a singleton. */
    fun attach(store: Store) {
        storeRef = store
        synchronized(memory) {
            if (memory.isEmpty()) {
                memory += load(store)
                // Legacy single-account keys, folded into the pool once.
                val legacyEmail = store.key("phonex_email").trim()
                val legacyPassword = store.key("phonex_password")
                if (legacyEmail.isNotBlank() && legacyPassword.isNotBlank() && memory.none { it.email == legacyEmail }) {
                    memory += PhoneXAccount(legacyEmail, legacyPassword, store.key("phonex_token"))
                    persist()
                }
            }
        }
    }

    val configured: Boolean
        get() = accounts().any { !it.blocked }

    val poolSize: Int
        get() = accounts().count { !it.blocked }

    fun accounts(): List<PhoneXAccount> = synchronized(memory) { memory.toList() }

    private fun load(store: Store): List<PhoneXAccount> {
        val raw = store.key("phonex_accounts")
        if (raw.isBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val email = item.optString("email")
                if (email.isBlank()) continue
                add(
                    PhoneXAccount(
                        email = email,
                        password = item.optString("password"),
                        token = item.optString("token"),
                        balance = item.optLong("balance", -1),
                        blockedAt = item.optLong("blockedAt", 0),
                        note = item.optString("note")
                    )
                )
            }
        }
    }

    private fun persist() {
        val store = storeRef ?: return
        val array = JSONArray()
        synchronized(memory) {
            memory.forEach { account ->
                array.put(
                    JSONObject()
                        .put("email", account.email)
                        .put("password", account.password)
                        .put("token", account.token)
                        .put("balance", account.balance)
                        .put("blockedAt", account.blockedAt)
                        .put("note", account.note)
                )
            }
        }
        store.setKey("phonex_accounts", array.toString())
    }

    fun addAccount(email: String, password: String) {
        val clean = email.trim().lowercase()
        synchronized(memory) {
            if (memory.none { it.email == clean }) memory += PhoneXAccount(clean, password)
        }
        persist()
    }

    fun removeAccount(email: String) {
        synchronized(memory) { memory.removeAll { it.email == email } }
        persist()
    }

    /** Clears the parked flag after a wallet is topped up out of band. */
    fun unblock(email: String) {
        synchronized(memory) { memory.indexOfFirst { it.email == email }.takeIf { it >= 0 } }
        synchronized(memory) {
            val index = memory.indexOfFirst { it.email == email }
            if (index >= 0) memory[index] = memory[index].copy(blockedAt = 0, note = "")
        }
        persist()
    }

    private fun update(email: String, transform: (PhoneXAccount) -> PhoneXAccount) {
        synchronized(memory) {
            val index = memory.indexOfFirst { it.email == email }
            if (index >= 0) memory[index] = transform(memory[index])
        }
        persist()
    }

    /** First account that is not parked; the next call rotates after a 402. */
    private fun pick(): PhoneXAccount {
        val list = accounts()
        return list.firstOrNull { !it.blocked }
            ?: throw EmptyWallet("Account pool exhausted. Add an account or top up.")
    }

    private fun device(): String {
        val store = storeRef ?: throw AuthRequired("Store is not ready.")
        return store.key("phonex_device").ifBlank {
            UUID.randomUUID().toString().also { store.setKey("phonex_device", it) }
        }
    }

    private fun headers(device: String, token: String?): Map<String, String> = buildMap {
        put("Accept", "application/json")
        put("x-device-id", device)
        put("x-device-type", "mobile")
        put("Origin", BASE)
        put("Referer", "$BASE/phone")
        if (!token.isNullOrBlank()) put("Authorization", "Bearer $token")
    }

    suspend fun login(email: String): String = loginLock.withLock {
        val account = accounts().firstOrNull { it.email == email }
            ?: throw AuthRequired("Account $email is not in the pool.")
        val device = device()
        val payload = JSONObject()
            .put("identifier", account.email)
            .put("identity", account.email)
            .put("password", account.password)
            .toString()
        val response = Net.request(
            url = "$BASE/api/auth/web/login",
            method = "POST",
            headers = headers(device, null),
            body = payload.toByteArray(),
            contentType = "application/json",
            timeoutMs = 30_000
        )
        when {
            response.status == 429 -> throw RateLimited("Upstream refused the login (429). Wait a few minutes.")
            response.status == 401 || response.status == 400 ->
                throw AuthRequired("Password akun $email ditolak (HTTP ${response.status}).")
            response.status !in 200..299 ->
                throw IllegalStateException("Login failed (HTTP ${response.status}).")
        }
        val token = extractToken(response.body) ?: throw IllegalStateException("Login returned no token.")
        update(email) { it.copy(token = token) }
        token
    }

    private fun extractToken(body: String): String? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        listOf("sessionToken", "token", "accessToken", "session_token").forEach { key ->
            root.optString(key).takeIf { it.isNotBlank() }?.let { return it }
        }
        listOf("data", "session", "user").forEach { wrapper ->
            root.optJSONObject(wrapper)?.let { inner ->
                listOf("sessionToken", "token").forEach { key ->
                    inner.optString(key).takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
        return null
    }

    /**
     * Runs one module, rotating the pool on a dead wallet.
     *
     * 402 and 403 park the account and immediately retry on the next one, because
     * an empty wallet is a fact about that account, not about the request. 429 is
     * IP-wide, so it is surfaced and the caller paces itself.
     */
    suspend fun probe(module: String, phoneNumber: String): JSONObject = withContext(Dispatchers.IO) {
        val device = device()
        var lastError: Exception? = null

        repeat(accounts().count { !it.blocked }.coerceAtLeast(1)) {
            val account = pick()
            val token = account.token.ifBlank { login(account.email) }
            var response = call(module, device, token, phoneNumber)
            if (response.status == 401) {
                response = call(module, device, login(account.email), phoneNumber)
            }
            when {
                response.status == 402 -> {
                    update(account.email) {
                        it.copy(blockedAt = System.currentTimeMillis(), balance = 0, note = "poin habis")
                    }
                    lastError = EmptyWallet("Poin akun Phone-X habis.")
                }
                response.status == 429 -> throw RateLimited("Modul $module kena batas upstream (429).")
                response.status == 403 -> {
                    update(account.email) { it.copy(blockedAt = System.currentTimeMillis(), note = "ditolak") }
                    lastError = AuthRequired("Akun ${account.email} ditolak (403).")
                }
                response.status !in 200..299 ->
                    throw IllegalStateException("$module failed (HTTP ${response.status}).")
                else -> {
                    runCatching { JSONObject(response.body) }.getOrNull()?.let { return@withContext it }
                    throw IllegalStateException("$module: bukan JSON.")
                }
            }
        }
        throw lastError ?: EmptyWallet("Account pool exhausted. Add an account or top up.")
    }

    private suspend fun call(
        module: String,
        device: String,
        token: String,
        phoneNumber: String
    ): Net.Response {
        val payload = JSONObject().put("phoneNumber", phoneNumber).toString()
        return Net.request(
            url = "$BASE/api/phone/$module",
            method = "POST",
            headers = headers(device, token),
            body = payload.toByteArray(),
            contentType = "application/json",
            timeoutMs = 40_000
        )
    }

    /** Token balance of the first live account; null when unreachable. */
    suspend fun balance(): Long? = runCatching {
        withContext(Dispatchers.IO) {
            val account = accounts().firstOrNull { !it.blocked } ?: return@withContext null
            val token = account.token.ifBlank { login(account.email) }
            val response = Net.getOrNull("$BASE/api/tokens/balance", headers(device(), token))
            if (response.status !in 200..299) return@withContext null
            val root = JSONObject(response.body)
            listOf("tokensBalance", "balance", "tokens", "points")
                .firstNotNullOfOrNull { key -> root.optLong(key, -1L).takeIf { it >= 0 } }
                ?.also { value -> update(account.email) { it.copy(balance = value) } }
        }
    }.getOrNull()

    // ------------------------------------------------------------- registration

    /** Step 1 of the farm: asks upstream to mail a code to the alias. */
    suspend fun requestOtp(email: String, reset: Boolean = false): String = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("email", email.trim().lowercase())
            .put("purpose", if (reset) "reset" else "register")
            .toString()
        val response = Net.request(
            url = "$BASE/api/auth/web/email/request-otp",
            method = "POST",
            headers = headers(device(), null),
            body = payload.toByteArray(),
            contentType = "application/json",
            timeoutMs = 30_000
        )
        when {
            response.status == 409 -> throw IllegalStateException("That email is already registered.")
            response.status == 429 -> throw RateLimited("OTP ditolak upstream (429). Tunggu sebentar.")
            response.status !in 200..299 ->
                throw IllegalStateException("OTP send failed (HTTP ${response.status}).")
        }
        "Code sent to $email."
    }

    /**
     * Steps 2 and 3: exchange the code for a verify token, then set the password.
     * The new account is added to the pool and left unblocked.
     */
    suspend fun completeRegistration(email: String, code: String, password: String): String =
        withContext(Dispatchers.IO) {
            val clean = email.trim().lowercase()
            if (!Regex("^\\d{6}$").matches(code.trim())) throw IllegalStateException("Kode harus 6 digit.")
            if (password.length < 8) throw IllegalStateException("Password minimal 8 karakter.")
            val device = device()

            val verify = Net.request(
                url = "$BASE/api/auth/web/email/verify-otp",
                method = "POST",
                headers = headers(device, null),
                body = JSONObject()
                    .put("email", clean)
                    .put("purpose", "register")
                    .put("code", code.trim())
                    .toString().toByteArray(),
                contentType = "application/json",
                timeoutMs = 30_000
            )
            if (verify.status !in 200..299) {
                throw IllegalStateException("Kode ditolak (HTTP ${verify.status}).")
            }
            val verifyToken = runCatching { JSONObject(verify.body).optString("verifyToken") }
                .getOrNull().orEmpty()
            if (verifyToken.isBlank()) throw IllegalStateException("No verifyToken. Request a new OTP.")

            val setPassword = Net.request(
                url = "$BASE/api/auth/web/email/set-password",
                method = "POST",
                headers = headers(device, null),
                body = JSONObject()
                    .put("email", clean)
                    .put("purpose", "register")
                    .put("verifyToken", verifyToken)
                    .put("password", password)
                    .toString().toByteArray(),
                contentType = "application/json",
                timeoutMs = 30_000
            )
            if (setPassword.status !in 200..299) {
                throw IllegalStateException("Password save failed (HTTP ${setPassword.status}).")
            }
            addAccount(clean, password)
            login(clean)
            "Akun $clean masuk pool."
        }

    // ------------------------------------------------------------------ parsing

    /** True when a module actually produced a signal, per upstream semantics. */
    fun isActive(module: String, body: JSONObject?): Boolean {
        if (body == null) return false
        return when (module) {
            MODULE_HLR -> body.optJSONObject("hlr")?.let {
                it.optString("status") == "active" || it.optString("carrier").isNotBlank()
            } ?: body.optString("status") == "active"
            MODULE_WHATSAPP -> (body.optJSONObject("wa") ?: body).optBoolean("connected")
            MODULE_TELEGRAM -> (body.optJSONObject("tg") ?: body).optBoolean("registered")
            MODULE_GETCONTACT -> (body.optJSONObject("gc") ?: body).optString("name").isNotBlank()
            MODULE_EWALLET -> walletNames(body).isNotEmpty()
            MODULE_BREACH -> (body.optJSONObject("breach") ?: body).optBoolean("found")
            MODULE_HUMINT -> (body.optJSONObject("humint") ?: body).optBoolean("found")
            MODULE_EVIDENCE -> (body.optJSONArray("records")?.length() ?: 0) > 0
            else -> false
        }
    }

    fun score(results: Map<String, JSONObject?>): Int {
        val earned = MODULES.sumOf { spec -> if (isActive(spec.id, results[spec.id])) spec.weight else 0 }
        val ceiling = MODULES.sumOf { it.weight }.coerceAtLeast(1)
        return earned * 100 / ceiling
    }

    fun walletNames(body: JSONObject?): List<String> {
        body ?: return emptyList()
        val array = body.optJSONArray("ewallet") ?: body.optJSONArray("wallets") ?: body.optJSONArray("ew")
        if (array != null) {
            return buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i)
                    val name = item?.optString("name") ?: item?.optString("provider") ?: array.optString(i)
                    if (!name.isNullOrBlank()) add(name)
                }
            }
        }
        return emptyList()
    }
}

/** One upstream lookup module and the weight it contributes to the score. */
data class ModuleSpec(val id: String, val label: String, val weight: Int)
