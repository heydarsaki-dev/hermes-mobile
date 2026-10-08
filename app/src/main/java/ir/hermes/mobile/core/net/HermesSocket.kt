package ir.hermes.mobile.core.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicLong
import java.net.URLEncoder

sealed interface RpcEvent {
    data class Event(val type: String, val payload: JsonObject, val sid: String?) : RpcEvent
    data class Response(val id: String, val result: JsonElement?, val error: String?) : RpcEvent
    data class Status(val state: State) : RpcEvent
    enum class State { CONNECTING, CONNECTED, CLOSED, FAILED }
}

/**
 * اتصال WebSocket به /api/ws هرمس با پروتکل JSON-RPC 2.0.
 * رویدادها از طریق Flow به لایه UI می‌رسند.
 */
class HermesSocket(
    private val api: ApiClient,
    private val onLog: (String) -> Unit = {},
) {
    private var socket: WebSocket? = null
    private val ids = AtomicLong(0)
    private val _events = MutableSharedFlow<RpcEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<RpcEvent> = _events.asSharedFlow()

    private val _status = MutableStateFlow(RpcEvent.State.CLOSED)
    val status: StateFlow<RpcEvent.State> = _status

    /**
     * پاسخ‌های در انتظار. پیش از ارسال ثبت می‌شوند تا پاسخ سریعِ سرور از دست نرود
     * (قبلاً با SharedFlow و replay=0 ممکن بود پاسخ پیش از شروع اشتراک برسد و گم شود
     * و UI برای همیشه در «در حال فکر کردن» بماند).
     */
    private val awaiters = ConcurrentHashMap<String, CompletableDeferred<RpcEvent.Response>>()

    /**
     * شناسهٔ نشستِ زنده (۸ حرف) — کلیدِ واقعیِ نشست در رجیستریِ سرور.
     *
     * برای `prompt.submit`، `session.interrupt` و `session.close` باید همین
     * شناسه فرستاده شود.
     */
    @Volatile var sessionId: String = ""

    /**
     * کلیدِ ذخیره‌شدهٔ نشست (UUID) — همان چیزی که `session.list` به‌عنوان
     * `id` برمی‌گرداند و `session.delete` آن را می‌خواهد.
     *
     * توجه: این دو با هم متفاوت‌اند. ارسالِ UUID به `session.interrupt` با
     * خطای «session not found» رد می‌شود و نشست زنده باقی می‌ماند — همان
     * دلیلی که حذفِ نشستِ فعال با «cannot delete an active session» رد می‌شد.
     */
    @Volatile var sessionKey: String = ""

    fun connect() {
        disconnect()
        _status.value = RpcEvent.State.CONNECTING
        // توکن باید در کوئری URL-encoding شود؛ کاراکترهای خاص (فاصله/خط جدید) باعث کرش می‌شدند
        val q = if (api.token.isNotBlank()) "?token=" + URLEncoder.encode(api.token.trim(), "UTF-8") else ""
        val target = runCatching { api.wsUrl("/api/ws") + q }.getOrElse {
            _status.value = RpcEvent.State.FAILED
            onLog("آدرس وب‌سوکت نامعتبر: ${it.message}")
            return
        }
        val req = runCatching {
            Request.Builder()
                .url(target)
                .header("X-Hermes-Session-Token", api.cleanToken(api.token))
                .build()
        }.getOrElse {
            _status.value = RpcEvent.State.FAILED
            onLog("ساخت درخواست ناموفق: ${it.message}")
            return
        }
        socket = runCatching { api.clientForSocket().newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, r: Response) {
                _status.value = RpcEvent.State.CONNECTED
                onLog("متصل شد")
            }
            override fun onMessage(ws: WebSocket, text: String) { parse(text) }
            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                onLog("در حال بستن: $code $reason"); ws.close(1000, null)
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                _status.value = RpcEvent.State.CLOSED; onLog("بسته شد ($code)")
            }
            override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
                _status.value = RpcEvent.State.FAILED
                onLog("قطع اتصال: ${t.message}")
            }
        }) }.getOrElse {
            _status.value = RpcEvent.State.FAILED
            onLog("اتصال ناموفق: ${it.message}")
            return
        }
    }

    private fun parse(text: String) {
        runCatching {
            val root = Json.parseToJsonElement(text).jsonObject
            if (root["method"]?.jsonPrimitive?.content == "event") {
                val p = root["params"]?.jsonObject ?: JsonObject(emptyMap())
                val type = p["type"]?.jsonPrimitive?.content ?: "unknown"
                val payload = p["payload"]?.jsonObject ?: JsonObject(emptyMap())
                val sid = p["sid"]?.jsonPrimitive?.contentOrNull
                // رویدادها هر دو شناسه را حمل می‌کنند: `sid` زنده و `session_key`
                // ذخیره‌شده. نگه‌داشتنِ هر دو باعث می‌شود حذف نشستِ فعال کار کند
                // (رجوع کنید به توضیحات [sessionKey]).
                if (!sid.isNullOrBlank()) {
                    sessionId = sid
                    p["session_key"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?.let { sessionKey = it }
                }
                _events.tryEmit(RpcEvent.Event(type, payload, sid))
            } else {
                val id = root["id"]?.jsonPrimitive?.contentOrNull ?: return
                val err = root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                val res = root["result"]
                val response = RpcEvent.Response(id, res, err)
                awaiters.remove(id)?.complete(response)
                _events.tryEmit(response)
            }
        }.onFailure { onLog("خطای تجزیه: ${it.message}") }
    }

    /** ارسال درخواست JSON-RPC و برگرداندن شناسه */
    fun call(method: String, params: JsonObject = JsonObject(emptyMap())): String {
        val id = ids.incrementAndGet().toString()
        val msg = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params)
        }
        socket?.send(msg.toString())
        return id
    }

    /**
     * ارسال درخواست و انتظار برای پاسخ متناظر. ثبت منتظر‌شونده *قبل* از ارسال انجام می‌شود
     * تا هیچ پاسخی گم نشود؛ در پایان هم منتظر‌شونده پاک می‌شود.
     */
    suspend fun callAwait(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        timeoutMs: Long = 30_000,
    ): RpcEvent.Response {
        val id = ids.incrementAndGet().toString()
        val deferred = CompletableDeferred<RpcEvent.Response>()
        awaiters[id] = deferred
        try {
            val msg = buildJsonObject {
                put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params)
            }
            socket?.send(msg.toString())
            return withTimeout(timeoutMs) { deferred.await() }
        } finally {
            awaiters.remove(id)
        }
    }

    /** ارسال دستور slash مانند /model */
    fun sendSlash(cmd: String) {
        val msg = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", ids.incrementAndGet().toString())
            put("method", "slash.exec")
            put("params", buildJsonObject {
                put("session_id", sessionId); put("command", cmd)
            })
        }
        socket?.send(msg.toString())
    }

    /**
     * متوقف کردن یک نشست.
     *
     * `sessionId` پیش‌فرض نشستِ جاری است؛ ولی می‌توان نشستِ دیگری را هم
     * متوقف کرد. این برای حذفِ نشست لازم است: هرمس حذفِ نشستِ *فعال* را
     * رد می‌کند، پس اول باید آن نشست با `session.interrupt` متوقف شود.
     * اگر نشستی فعال نباشد، فرستادن این درخواست بی‌ضرر است.
     */
    fun interrupt(sessionId: String = this.sessionId) {
        if (sessionId.isBlank()) return
        val msg = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", ids.incrementAndGet().toString())
            put("method", "session.interrupt")
            put("params", buildJsonObject { put("session_id", sessionId) })
        }
        socket?.send(msg.toString())
    }

    fun disconnect() { socket?.close(1000, "bye"); socket = null; _status.value = RpcEvent.State.CLOSED }
}
