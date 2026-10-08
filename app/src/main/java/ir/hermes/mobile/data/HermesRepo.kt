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

    /**
     * مهلتِ بستن نشست. `session.close` ممکن است هیچ پاسخی ندهد؛ اگر از
     * [RPC_SLOW] استفاده کنیم، حذف یا ساختِ نشست جدید تا ۱۲۰ ثانیه معطل
     * می‌شود و کاربر فکر می‌کند دکمه کار نمی‌کند.
     */
    private const val RPC_CLOSE = 8_000L

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

    /**
     * ثبت نشست جاری.
     *
     * @param id شناسهٔ زندهٔ ۸ حرفی (برای prompt.submit / interrupt / close)
     * @param key کلیدِ ذخیره‌شدهٔ UUID (برای session.delete و مقایسه با session.list)
     */
    fun startSession(id: String, key: String = "") {
        socket.sessionId = id
        socket.sessionKey = key
    }

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
        // هرمس در هر لحظه یک نشست فعال نگه می‌دارد و ساختِ نشستِ جدید تا
        // وقتی نشست قبلی «فعال» است رد می‌شود. اول آن را رها می‌کنیم.
        releaseCurrentSession()
        val sel = selection()
        val p = provider.ifBlank { sel.provider }
        val m = model.ifBlank { sel.model }
        val params = buildJsonObject {
            title?.let { put("title", it) }
            if (m.isNotBlank()) put("model", m)
            if (p.isNotBlank()) put("provider", p)
        }
        val resp = rpc("session.create", params, timeoutMs = RPC_SLOW)
            .getOrNull()?.let { J.obj(it) } ?: JsonObject(emptyMap())
        // سرور دو شناسه برمی‌گرداند: `session_id` زندهٔ ۸ حرفی و
        // `stored_session_id` (UUID) که در `session.list` به‌عنوان `id` می‌آید.
        val id = J.str(resp, "session_id")
        val key = J.str(resp, "stored_session_id")
        if (id.isNotBlank()) startSession(id, key)
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
        // ۴) نشست فعلی کامل رها می‌شود (هم محلی، هم سمت سرور) تا نشست بعدی
        //    با مدل تازه ساخته شود.
        releaseCurrentSession()
        return err
    }

    /** بستن نشست سمت سرور (حذف نشستِ فعال در هرمس رد می‌شود). */
    suspend fun closeSession(id: String): Result<JsonElement> =
        rpc("session.close", str("session_id", id), timeoutMs = RPC_CLOSE)

    /**
     * پاک‌کردن نشست فعال. نشست بعدی با تنظیمات/مدل جاری ساخته می‌شود؛
     * لازم است چون هرمس مدل هر نشست را در زمان ساخت آن ثابت می‌کند.
     */
    fun clearSession() { socket.sessionId = ""; socket.sessionKey = "" }

    /**
     * رها کردنِ کامل نشست فعالِ کنونی.
     *
     * هرمس در هر لحظه فقط یک نشست را «فعال» نگه می‌دارد. تا وقتی این نشست
     * متوقف نشود، `session.create` و `session.delete` برای همان نشست رد
     * می‌شوند — همین باعث می‌شد «نشست جدید» و «حذف بالاترین نشست» کار
     * نکنند.
     *
     * برای خروج از حالت فعال، اول `session.interrupt` فرستاده می‌شود (این
     * متدِ درستِ «توقف نشست» در هرمس است؛ `session.close` ممکن است جواب
     * ندهد). رد شدنش مشکلی ندارد؛ مهم این است که دیگر نشست فعلی محلیِ
     * نامعتبر در دست نباشد.
     */
    suspend fun releaseCurrentSession() {
        val id = socket.sessionId
        val key = socket.sessionKey
        clearSession()
        if (id.isNotBlank()) stopSession(key.ifBlank { id }, knownLiveSid = id)
    }

    /**
     * متوقف کردن یک نشست روی سرور.
     *
     * هرمس نشستِ *فعال* را فقط بعد از توقف می‌پذیرد (حذف یا ساخت نشست جدید
     * وقتی نشستی فعال است رد می‌شود).
     *
     * **نکتهٔ مهم دربارهٔ شناسه‌ها:** رجیستریِ نشست‌های زندهٔ سرور با
     * شناسهٔ ۸ حرفی (`session_id`) کلید می‌خورد، ولی `session.list` و
     * `session.delete` با کلیدِ UUID ذخیره‌شده کار می‌کنند. قبلاً UUID را به
     * `session.interrupt` می‌فرستادیم و سرور با «session not found» جواب می‌داد
     * — نشست زنده باقی می‌ماند و حذف با «cannot delete an active session» رد
     * می‌شد. حالا شناسهٔ زنده را یا از حالتِ محلیِ نشستِ جاری می‌گیریم، یا
     * از طریق `session.active_list` از سرور حل می‌کنیم.
     *
     * @param key کلید ذخیره‌شده (UUID) — برای پیدا کردن نشست زنده در سرور
     * @param knownLiveSid شناسهٔ زندهٔ ۸ حرفی، اگر از قبل معلوم است (می‌تواند خالی باشد)
     */
    suspend fun stopSession(key: String, knownLiveSid: String = "") {
        if (key.isBlank() && knownLiveSid.isBlank()) return
        val sid = if (knownLiveSid.isNotBlank()) knownLiveSid else (liveSidFor(key) ?: "")
        if (sid.isBlank()) return // نشست زنده‌ای روی سرور نیست؛ نیازی به توقف نیست
        runCatching { rpc("session.interrupt", str("session_id", sid), timeoutMs = RPC_CLOSE) }
        runCatching { closeSession(sid) }
    }

    /**
     * پیدا کردن شناسهٔ زندهٔ ۸ حرفی برای یک کلیدِ ذخیره‌شده.
     *
     * `session.active_list` نشست‌های زنده را با هر دو `id` (زنده) و
     * `session_key` (UUID) برمی‌گرداند؛ این تابع آن‌ها را تطبیق می‌دهد.
     * اگر نشست زنده‌ای پیدا نشد، null برمی‌گرداند (یعنی نشست از قبل بسته شده).
     */
    private suspend fun liveSidFor(key: String): String? {
        if (key.isBlank()) return null
        return runCatching {
            rpc("session.active_list", JsonObject(emptyMap()), timeoutMs = RPC_FAST)
                .getOrNull()?.let { J.listOf(J.obj(it), "sessions") }
                ?.firstOrNull { J.str(J.obj(it), "session_key") == key }
                ?.let { J.str(J.obj(it), "id").ifBlank { J.str(J.obj(it), "session_id") } }
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /**
     * بازگرداندن یک نشست ذخیره‌شده.
     *
     * هرمس هنگام `session.resume` یک نشست *زندهٔ* تازه با شناسهٔ خودش می‌سازد
     * (`_live_session_payload`) که می‌تواند با شناسهٔ ذخیره‌شده‌ای که فرستادیم
     * متفاوت باشد. اگر همان شناسهٔ قدیمی را نگه داریم، `prompt.submit` با خطای
     * «session not found» رد می‌شود — همان چیزی که با خروج از نشست و برگشت
     * رخ می‌داد.
     *
     * @return شناسهٔ زندهٔ نشست که باید برای گفت‌وگو استفاده شود.
     */
    suspend fun resume(id: String): String {
        val resp = rpc("session.resume", str("session_id", id), timeoutMs = RPC_SLOW)
            .getOrNull()?.let { J.obj(it) } ?: JsonObject(emptyMap())
        // `session_id` زنده می‌آید و `session_key` همان UUID ذخیره‌شده است.
        val live = J.str(resp, "session_id")
        val key = J.str(resp, "session_key").ifBlank { id }
        val use = live.ifBlank { id }
        if (use.isNotBlank()) startSession(use, key)
        return use
    }

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

    // ---------- کرون ----------
    // پروتکل سرور (cron.manage) فقط این action‌ها را می‌پذیرد:
    //   list / add / remove / pause / resume
    // شناسهٔ کار با کلید `name` فرستاده می‌شود (سرور آن را به job_id تفسیر می‌کند).
    suspend fun cronList(): Result<JsonElement> = rpc("cron.manage", buildJsonObject { put("action", "list") })

    suspend fun cronCreate(name: String, schedule: String, prompt: String): Result<JsonElement> =
        rpc("cron.manage", buildJsonObject {
            put("action", "add"); put("name", name); put("schedule", schedule); put("prompt", prompt)
        })

    suspend fun cronRemove(jobId: String): Result<JsonElement> =
        rpc("cron.manage", buildJsonObject { put("action", "remove"); put("name", jobId) })

    suspend fun cronPause(jobId: String): Result<JsonElement> =
        rpc("cron.manage", buildJsonObject { put("action", "pause"); put("name", jobId) })

    suspend fun cronResume(jobId: String): Result<JsonElement> =
        rpc("cron.manage", buildJsonObject { put("action", "resume"); put("name", jobId) })

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

    /**
     * نامِ خوانای یک ابزار/مهارت/افزونه.
     *
     * سرور ممکن است نام را زیر کلید `name`، `id` یا `slug` بفرستد. این
     * کمک‌کار همه را امتحان می‌کند تا سوییچ‌ها و نمایش‌ها به‌درستی کار
     * کنند — قبلاً اگر `name` وجود نداشت، نام خالی فرض می‌شد.
     */
    fun toolName(o: JsonObject): String {
        J.str(o, "name").takeIf { it.isNotBlank() }?.let { return it }
        J.str(o, "id").takeIf { it.isNotBlank() }?.let { return it }
        J.str(o, "slug").takeIf { it.isNotBlank() }?.let { return it }
        J.str(o, "title").takeIf { it.isNotBlank() }?.let { return it }
        return ""
    }
}
