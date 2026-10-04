package ir.hermes.mobile.core.net

import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
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

    @Volatile var sessionId: String = ""

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
                _events.tryEmit(RpcEvent.Event(type, payload, sid))
            } else {
                val id = root["id"]?.jsonPrimitive?.contentOrNull ?: return
                val err = root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                val res = root["result"]
                _events.tryEmit(RpcEvent.Response(id, res, err))
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

    fun interrupt() {
        val msg = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", ids.incrementAndGet().toString())
            put("method", "session.interrupt")
            put("params", buildJsonObject { put("session_id", sessionId) })
        }
        socket?.send(msg.toString())
    }

    fun disconnect() { socket?.close(1000, "bye"); socket = null; _status.value = RpcEvent.State.CLOSED }
}
