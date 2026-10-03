package ir.hermes.mobile.data

import android.content.Context
import ir.hermes.mobile.core.datastore.ServerConfig
import ir.hermes.mobile.core.datastore.SettingsStore
import ir.hermes.mobile.core.net.ApiClient
import ir.hermes.mobile.core.net.HermesSocket
import ir.hermes.mobile.core.net.RpcEvent
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/**
 * مخزن مرکزی: تنظیمات، اتصال WebSocket و درخواست‌های REST.
 */
object HermesRepo {

    lateinit var settings: SettingsStore
        private set
    lateinit var api: ApiClient
        private set
    lateinit var socket: HermesSocket
        private set

    val logLines = MutableStateFlow<List<String>>(emptyList())

    fun init(ctx: Context) {
        if (::api.isInitialized) return
        settings = SettingsStore(ctx.applicationContext)
        api = ApiClient("http://127.0.0.1:8080", "")
        socket = HermesSocket(api) { line -> addLog(line) }
    }

    fun addLog(line: String) {
        logLines.value = (logLines.value + "[${ir.hermes.mobile.core.util.Jalali.fa(System.currentTimeMillis())}] $line")
            .takeLast(400)
    }

    suspend fun applyConfig(cfg: ServerConfig) {
        api.baseUrl = cfg.url
        api.token = cfg.token
    }

    fun startSession(id: String) { socket.sessionId = id }

    /** ارسال متد JSON-RPC با انتظار پاسخ */
    suspend fun rpc(method: String, params: JsonObject = JsonObject(emptyMap())): Result<JsonElement> =
        kotlinx.coroutines.withTimeoutOrNull(30_000) {
            val id = socket.call(method, params)
            val r = socket.events.first { it is RpcEvent.Response && it.id == id }
            (r as RpcEvent.Response).let { resp ->
                if (resp.error != null) Result.failure(RuntimeException(resp.error))
                else Result.success(resp.result ?: JsonObject(emptyMap()))
            }
        } ?: Result.failure(RuntimeException("پاسخی از سرور دریافت نشد"))

    // ---------- نشست‌ها ----------
    suspend fun sessions(): Result<JsonElement> = rpc("session.list")

    suspend fun newSession(title: String? = null): Result<JsonElement> =
        rpc("session.create", buildJsonObject { title?.let { put("title", it) } })

    suspend fun resume(id: String): Result<JsonElement> = rpc("session.resume", str("session_id", id))

    suspend fun deleteSession(id: String): Result<JsonElement> = rpc("session.delete", str("session_id", id))

    suspend fun history(id: String, limit: Int = 100): Result<JsonElement> =
        rpc("session.history", buildJsonObject { put("session_id", id); put("limit", limit) })

    suspend fun setTitle(id: String, t: String): Result<JsonElement> =
        rpc("session.title", buildJsonObject { put("session_id", id); put("title", t) })

    suspend fun branch(id: String): Result<JsonElement> = rpc("session.branch", str("session_id", id))

    suspend fun usage(): Result<JsonElement> = rpc("session.usage")

    // ---------- مدل و پرووایدر ----------
    suspend fun modelOptions(): Result<JsonElement> = rpc("model.options")

    suspend fun configGet(): Result<JsonElement> = rpc("config.get")

    suspend fun configSet(path: String, value: JsonElement): Result<JsonElement> =
        rpc("config.set", buildJsonObject { put("path", path); put("value", value) })

    suspend fun saveKey(provider: String, key: String): Result<JsonElement> =
        rpc("model.save_key", buildJsonObject { put("provider", provider); put("key", key) })

    suspend fun disconnectProvider(provider: String): Result<JsonElement> =
        rpc("model.disconnect", str("provider", provider))

    // ---------- ابزارها ----------
    suspend fun toolsList(): Result<JsonElement> = rpc("tools.list")

    suspend fun toolSets(): Result<JsonElement> = rpc("toolsets.list")

    suspend fun toolsConfigure(name: String, enabled: Boolean, args: JsonObject = JsonObject(emptyMap())): Result<JsonElement> =
        rpc("tools.configure", buildJsonObject {
            put("name", name); put("enabled", enabled); put("args", args)
        })

    // ---------- مهارت و پلاگین ----------
    suspend fun skills(): Result<JsonElement> = rpc("skills.manage", buildJsonObject { put("action", "list") })

    suspend fun plugins(): Result<JsonElement> = rpc("plugins.list")

    // ---------- کرون ----------
    suspend fun cronList(): Result<JsonElement> = rpc("cron.manage", buildJsonObject { put("action", "list") })

    suspend fun cronCreate(name: String, schedule: String, prompt: String): Result<JsonElement> =
        rpc("cron.manage", buildJsonObject {
            put("action", "create"); put("name", name); put("schedule", schedule); put("prompt", prompt)
        })

    suspend fun cronDelete(name: String): Result<JsonElement> =
        rpc("cron.manage", buildJsonObject { put("action", "delete"); put("name", name) })

    // ---------- اجرای فرمان ----------
    suspend fun shell(cmd: String): Result<JsonElement> =
        rpc("shell.exec", buildJsonObject { put("command", cmd) })

    }

/** ساخت آبجکت JSON تک‌کلیدی */
fun str(k: String, v: String): JsonObject = buildJsonObject { put(k, v) }

/** کمک‌کار خواندن JSON پویا */
object J {
    fun obj(e: JsonElement?): JsonObject =
        (e as? JsonObject) ?: JsonObject(emptyMap())

    fun arr(e: JsonElement?): JsonArray =
        (e as? JsonArray) ?: JsonArray(emptyList())

    fun str(o: JsonObject, k: String, d: String = ""): String =
        o[k]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: d

    fun bool(o: JsonObject, k: String, d: Boolean = false): Boolean =
        o[k]?.let { runCatching { it.jsonPrimitive.boolean }.getOrNull() } ?: d

    fun int(o: JsonObject, k: String, d: Int = 0): Int =
        o[k]?.let { runCatching { it.jsonPrimitive.int }.getOrNull() } ?: d

    fun num(o: JsonObject, k: String, d: Double = 0.0): Double =
        o[k]?.let { runCatching { it.jsonPrimitive.double }.getOrNull() } ?: d

    /** فهرست‌هایی که ممکن است زیر کلید خاص یا خود ریشه باشند */
    fun listOf(root: JsonObject, vararg keys: String): JsonArray {
        for (k in keys) {
            root[k]?.let { return arr(it) }
        }
        // اگر ریشه خودش آرایه‌ی اشیا باشد
        val any = root.values.firstOrNull()
        if (any is JsonArray && any.all { it is JsonObject }) return any
        return JsonArray(emptyList())
    }

    fun pretty(e: JsonElement): String = try {
        kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), e)
    } catch (_: Exception) { e.toString() }
}
