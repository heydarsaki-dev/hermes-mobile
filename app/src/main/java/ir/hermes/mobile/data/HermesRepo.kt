package ir.hermes.mobile.data

import android.content.Context
import ir.hermes.mobile.core.datastore.LocalCache
import ir.hermes.mobile.core.datastore.ModelSelection
import ir.hermes.mobile.core.datastore.ServerConfig
import ir.hermes.mobile.core.datastore.SettingsStore
import ir.hermes.mobile.core.net.ApiClient
import ir.hermes.mobile.core.net.HermesSocket
import ir.hermes.mobile.core.net.RpcEvent
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

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

    /** کش محلی فهرست‌ها تا هر بار از هرمس خوانده نشود */
    lateinit var cache: LocalCache
        private set

    /** مهلت کوتاه برای فراخوانی‌های سبک */
    private const val RPC_FAST = 30_000L

    /** مهلت بلند برای فراخوانی‌هایی که سرور برایشان ایجنت می‌سازد یا دیتابیس می‌خواند */
    private const val RPC_SLOW = 120_000L

    private const val CACHE_SESSIONS = "sessions"
    private const val CACHE_MODEL_OPTIONS = "model_options"
    private const val CACHE_ENDPOINTS = "endpoints"

    val logLines = MutableStateFlow<List<String>>(emptyList())

    fun init(ctx: Context) {
        if (::api.isInitialized) return
        settings = SettingsStore(ctx.applicationContext)
        api = ApiClient("http://127.0.0.1:8080", "")
        socket = HermesSocket(api) { line -> addLog(line) }
        cache = LocalCache(ctx.applicationContext)
    }

    fun addLog(line: String) {
        logLines.value = (logLines.value + "[${ir.hermes.mobile.core.util.Jalali.fa(System.currentTimeMillis())}] $line")
            .takeLast(400)
        // همین خط‌ها در گزارش کرش هم می‌آیند تا وضعیت شبکه/سوکت معلوم باشد.
        ir.hermes.mobile.core.util.CrashLogger.breadcrumb(line)
    }

    suspend fun applyConfig(cfg: ServerConfig) {
        api.baseUrl = cfg.url
        api.token = cfg.token
    }

    fun startSession(id: String) { socket.sessionId = id }

    /**
     * ارسال متد JSON-RPC با انتظار پاسخ.
     *
     * توجه: پاسخ‌ها ممکن است *خطا* باشند (مثل «session not found»). قبلاً این خطاها
     * خوانده نمی‌شدند و اگر سرور یک درخواست را رد می‌کرد، UI بی‌نهایت منتظر می‌ماند.
     */
    suspend fun rpc(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        timeoutMs: Long = RPC_FAST,
    ): Result<JsonElement> = try {
        val resp = socket.callAwait(method, params, timeoutMs)
        if (resp.error != null) Result.failure(RuntimeException(resp.error))
        else Result.success(resp.result ?: JsonObject(emptyMap()))
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        Result.failure(RuntimeException("پاسخی از سرور دریافت نشد"))
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(RuntimeException(e.message ?: "خطای نامشخص"))
    }

    // ---------- نشست‌ها ----------

    /**
     * فهرست نشست‌ها. پاسخ موفق در کش محلی ذخیره می‌شود تا دفعهٔ بعد صفحه
     * بلافاصله محتوا داشته باشد و همیشه منتظر هرمس نمانیم.
     */
    suspend fun sessions(): Result<JsonElement> =
        rpc("session.list", JsonObject(emptyMap()), timeoutMs = RPC_SLOW).onSuccess {
            cache.put(CACHE_SESSIONS, it)
        }

    /** فهرست نشست‌های کش‌شده (بدون تماس با سرور) یا null. */
    fun cachedSessions(): JsonArray? =
        (cache.get(CACHE_SESSIONS) as? JsonObject)?.let { J.arr(it["sessions"]) }

    /** ساخت نشست خام (بدون override مدل). */
    suspend fun newSession(title: String? = null): Result<JsonElement> =
        rpc("session.create", buildJsonObject { title?.let { put("title", it) } }, timeoutMs = RPC_SLOW)

    /**
     * ایجاد نشست جدید با مدل/پرووایدر انتخاب‌شدهٔ کاربر.
     *
     * پارامترهای `model` و `provider` در `session.create` به‌صورت override همان
     * نشست ساخته می‌شوند — همان راه درستِ «ست کردن مدل روی نشست» در هرمس؛ چون
     * مدل هر نشست هنگام ساخت ثابت می‌شود و `config.set` فقط پیش‌فرض سرور را
     * عوض می‌کند (همین باعث می‌شد مدل انتخابی در صفحهٔ مدل روی نشست‌ها ست نشود).
     */
    suspend fun createSession(
        title: String? = null,
        provider: String = "",
        model: String = "",
    ): String? {
        val sel = selection()
        val p = provider.ifBlank { sel.provider }
        val m = model.ifBlank { sel.model }
        val params = buildJsonObject {
            title?.let { put("title", it) }
            if (m.isNotBlank()) put("model", m)
            if (p.isNotBlank()) put("provider", p)
        }
        val id = rpc("session.create", params, timeoutMs = RPC_SLOW)
            .getOrNull()?.let { J.str(J.obj(it), "session_id") } ?: ""
        if (id.isNotBlank()) startSession(id)
        return id.ifBlank { null }
    }

    /** شناسهٔ نشست فعال؛ اگر نبود یکی می‌سازد. بدون نشست معتبر، prompt.submit رد می‌شود. */
    suspend fun ensureSession(): String {
        socket.sessionId.takeIf { it.isNotBlank() }?.let { return it }
        return createSession(null) ?: ""
    }

    /** انتخاب فعلی مدل/پرووایدر کاربر (از حافظهٔ دستگاه). */
    suspend fun selection(): ModelSelection =
        runCatching { settings.selection.first() }.getOrElse { ModelSelection() }

    /**
     * اعمال مدل روی نشست.
     *
     * هرمس مدل هر نشست را هنگام ساخت آن ثابت می‌کند؛ پس ترتیب درست این است:
     *  ۱. توقف نوبت جاری (معادل /stop روی همان نشست)
     *  ۲. به‌روزرسانی پیش‌فرض سرور (برای نشست‌هایی که override ندارند)
     *  ۳. ثبت انتخاب کاربر روی دستگاه
     *  ۴. کنار گذاشتن نشست فعلی تا نشست بعدی با مدل تازه ساخته شود (نقش /start)
     *
     * @return پیام خطای سمت سرور (مثل «نیاز به تأیید») یا null اگر مشکلی نبود.
     */
    suspend fun applyModel(provider: String, model: String, baseUrl: String = ""): String? {
        // ۱) توقف نوبت جاری
        runCatching { socket.interrupt() }
        // ۲) پیش‌فرض سرور
        val err = if (model.isNotBlank()) {
            setMainModel(provider, model, baseUrl).fold(
                onSuccess = { r ->
                    val o = J.obj(r)
                    if (J.bool(o, "confirm_required")) {
                        J.str(o, "confirm_message", "انتخاب این مدل نیاز به تأیید دارد")
                    } else null
                },
                onFailure = { it.message },
            )
        } else null
        // ۳) ثبت انتخاب کاربر — حتی اگر REST خطا داد، انتخاب کاربر معتبر است
        settings.saveSelection(ModelSelection(provider, model, baseUrl))
        // ۴) نشست فعلی کنار گذاشته می‌شود
        clearSession()
        return err
    }

    /** بستن نشست سمت سرور (حذف نشستِ فعال در هرمس رد می‌شود). */
    suspend fun closeSession(id: String): Result<JsonElement> =
        rpc("session.close", str("session_id", id), timeoutMs = RPC_SLOW)

    /**
     * پاک‌کردن نشست فعال. نشست بعدی با تنظیمات/مدل جاری ساخته می‌شود؛
     * لازم است چون هرمس مدل هر نشست را در زمان ساخت آن ثابت می‌کند.
     */
    fun clearSession() { socket.sessionId = "" }

    suspend fun resume(id: String): Result<JsonElement> = rpc("session.resume", str("session_id", id))

    suspend fun deleteSession(id: String): Result<JsonElement> = rpc("session.delete", str("session_id", id))

    suspend fun history(id: String, limit: Int = 100): Result<JsonElement> =
        rpc("session.history", buildJsonObject { put("session_id", id); put("limit", limit) })

    suspend fun setTitle(id: String, t: String): Result<JsonElement> =
        rpc("session.title", buildJsonObject { put("session_id", id); put("title", t) })

    suspend fun branch(id: String): Result<JsonElement> = rpc("session.branch", str("session_id", id))

    suspend fun usage(): Result<JsonElement> = rpc("session.usage")

    // ---------- مدل و پرووایدر ----------
    /** فهرست پرووایدرها/مدل‌ها؛ با refresh=true کاتالوگ پرووایدرهای سفارشی هم دوباره خوانده می‌شود. */
    suspend fun modelOptions(refresh: Boolean = false): Result<JsonElement> =
        rpc(
            "model.options",
            buildJsonObject { if (refresh) put("refresh", true) },
            timeoutMs = RPC_SLOW,
        ).onSuccess { cache.put(CACHE_MODEL_OPTIONS, it) }

    /** گزینه‌های مدل کش‌شده (نمایش فوری پیش از پاسخ سرور). */
    fun cachedModelOptions(): JsonObject? = cache.get(CACHE_MODEL_OPTIONS) as? JsonObject

    suspend fun configGet(): Result<JsonElement> = rpc("config.get")

    suspend fun configSet(key: String, value: JsonElement): Result<JsonElement> =
        rpc("config.set", buildJsonObject { put("key", key); put("value", value) })

    /**
     * ذخیرهٔ کلید API یک پرووایدر شناخته‌شدهٔ هرمس.
     * قرارداد JSON-RPC هرمس: {slug, api_key} — نه {provider, key}.
     */
    suspend fun saveKey(slug: String, key: String): Result<JsonElement> =
        rpc("model.save_key", buildJsonObject { put("slug", slug); put("api_key", key) })

    suspend fun disconnectProvider(slug: String): Result<JsonElement> =
        rpc("model.disconnect", str("slug", slug))

    // ---------- پرووایدرهای سفارشی (REST داشبورد هرمس) ----------
    // افزودن «پرووایدر سفارشی» از طریق همان RESTی انجام می‌شود که صفحهٔ Providers
    // داشبورد استفاده می‌کند؛ config.set فقط کلیدهای ثابت را می‌پذیرد و پرووایدر
    // جدید نمی‌سازد. روت‌های داشبورد روی همان سرور /api/ws سوار شده‌اند.

    private fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)

    suspend fun customEndpoints(): Result<JsonElement> =
        api.call("/api/providers/custom-endpoints").onSuccess { cache.put(CACHE_ENDPOINTS, it) }

    /** endpointهای سفارشی کش‌شده. */
    fun cachedEndpoints(): JsonArray? =
        (cache.get(CACHE_ENDPOINTS) as? JsonObject)?.let { J.arr(it["endpoints"]) }

    /**
     * ساخت یا ویرایش یک پرووایدر سفارشی سازگار با OpenAI.
     * [apiMode] یکی از "" (تشخیص خودکار)، "chat_completions"، "anthropic_messages"،
     * "codex_responses". [apiKey] خالی یعنی دست‌نزدن/کلید لازم نیست.
     */
    suspend fun addCustomEndpoint(
        name: String,
        baseUrl: String,
        model: String,
        apiKey: String = "",
        apiMode: String = "",
        models: List<String> = emptyList(),
        makeDefault: Boolean = false,
        discoverModels: Boolean = true,
        id: String = "",
    ): Result<JsonElement> = api.call("/api/providers/custom-endpoints", "POST", buildJsonObject {
        if (id.isNotBlank()) put("id", id)
        put("name", name)
        put("base_url", baseUrl)
        put("model", model)
        if (apiKey.isNotBlank()) put("api_key", apiKey)
        put("api_mode", apiMode)
        put("discover_models", discoverModels)
        put("make_default", makeDefault)
        put("models", buildJsonArray { models.forEach { add(it) } })
    })

    /** بررسی دسترسی endpoint و کشف فهرست مدل‌های آن. */
    suspend fun validateCustomEndpoint(
        name: String,
        baseUrl: String,
        model: String,
        apiKey: String = "",
        apiMode: String = "",
    ): Result<JsonElement> = api.call("/api/providers/custom-endpoints/validate", "POST", buildJsonObject {
        put("name", name); put("base_url", baseUrl); put("model", model)
        if (apiKey.isNotBlank()) put("api_key", apiKey)
        put("api_mode", apiMode)
    })

    /** فعال‌کردن یک پرووایدر سفارشی به‌عنوان مدل اصلی هرمس. */
    suspend fun activateCustomEndpoint(id: String): Result<JsonElement> =
        api.call("/api/providers/custom-endpoints/${enc(id)}/activate", "POST", JsonObject(emptyMap()))

    suspend fun deleteCustomEndpoint(id: String): Result<JsonElement> =
        api.call("/api/providers/custom-endpoints/${enc(id)}", "DELETE")

    /**
     * انتخاب مدل برای اسلات اصلی (REST داشبورد). برای پرووایدرهای سفارشی
     * base_url هم فرستاده می‌شود تا مسیر درست حل شود.
     */
    suspend fun setMainModel(
        provider: String,
        model: String,
        baseUrl: String = "",
    ): Result<JsonElement> = api.call("/api/model/set", "POST", buildJsonObject {
        put("scope", "main"); put("provider", provider); put("model", model)
        if (baseUrl.isNotBlank()) put("base_url", baseUrl)
    })

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
