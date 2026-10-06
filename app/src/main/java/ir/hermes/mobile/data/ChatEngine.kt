package ir.hermes.mobile.data

import ir.hermes.mobile.core.net.RpcEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicLong

enum class MsgRole { USER, ASSISTANT, SYSTEM, TOOL }

data class ToolRun(val name: String, var status: String, var detail: String = "")

data class ChatMessage(
    val id: String,
    val role: MsgRole,
    var text: String = "",
    var reasoning: String = "",
    val tools: MutableList<ToolRun> = mutableListOf(),
    val time: Long = System.currentTimeMillis(),
    var pending: Boolean = false,
    var error: String? = null,
)

enum class TurnState { IDLE, THINKING, STREAMING, TOOL, WAITING_APPROVAL, DONE, ERROR }

/**
 * موتور چت: رویدادهای WebSocket را به وضعیت قابل نمایش تبدیل می‌کند.
 */
class ChatEngine {

    private val ids = AtomicLong(0)
    private fun newId() = "m${ids.incrementAndGet()}"

    val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val state = MutableStateFlow(TurnState.IDLE)
    val notice = MutableStateFlow<String?>(null)
    val model = MutableStateFlow("")
    val streaming = MutableStateFlow("")

    private var assistantMsg: ChatMessage? = null

    fun loadHistory(items: List<ChatMessage>) {
        if (messages.value.isEmpty()) messages.value = items
    }

    /**
     * ارسال پیام. تا وقتی پاسخ JSON-RPC سرور خوانده نشود خبری از وضعیت نهایی نیست.
     * اگر سرور درخواست را رد کند (مثلاً نشست نامعتبر) بلافاصله خطا نمایش داده می‌شود
     * تا UI در «در حال فکر کردن» گیر نکند.
     */
    suspend fun send(text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || state.value == TurnState.THINKING ||
            state.value == TurnState.STREAMING || state.value == TurnState.TOOL
        ) return
        messages.value = messages.value + ChatMessage(newId(), MsgRole.USER, clean)
        notice.value = null
        assistantMsg = null
        state.value = TurnState.THINKING
        streaming.value = ""

        // بدون نشست معتبر، هرمس درخواست را با خطا رد می‌کند.
        val sid = HermesRepo.ensureSession()
        if (sid.isBlank()) {
            fail("نشست هرمس ساخته نشد — اتصال به سرور را بررسی کنید")
            return
        }
        val params = buildJsonObject {
            put("session_id", sid)
            put("text", clean)
        }
        HermesRepo.rpc("prompt.submit", params).onFailure { e ->
            // اگر سرور هنوز رویدادی نفرستاده، خطای ارسال را نشان بده.
            if (state.value == TurnState.THINKING) fail(e.message ?: "ارسال پیام ناموفق بود")
        }
        HermesRepo.addLog("ارسال پیام")
    }

    /** نمایش خطای قطعی برای نوبت جاری (حبهٔ راهنما + پیام خطا). */
    private fun fail(msg: String) {
        state.value = TurnState.ERROR
        streaming.value = ""
        val m = ChatMessage(newId(), MsgRole.ASSISTANT)
        m.error = msg
        messages.value = messages.value + m
        assistantMsg = null
    }

    /** پاک‌سازی برای شروع یک نشست جدید. */
    fun resetForNewSession() {
        messages.value = emptyList()
        state.value = TurnState.IDLE
        streaming.value = ""
        notice.value = null
        assistantMsg = null
    }

    fun interrupt() {
        HermesRepo.socket.interrupt()
        state.value = TurnState.DONE
        streaming.value = ""
    }

    fun onEvent(ev: RpcEvent.Event) {
        val p = ev.payload
        when (ev.type) {
            "gateway.ready" -> notice.value = "دروازه آماده است"
            "turn.start" -> {
                state.value = TurnState.THINKING
                assistantMsg = ChatMessage(newId(), MsgRole.ASSISTANT, pending = true).also {
                    messages.value = messages.value + it
                }
                streaming.value = ""
            }
            "message.delta", "assistant.delta", "text.delta" -> {
                val t = J.str(p, "text")
                if (t.isNotEmpty()) {
                    val m = assistantMsg ?: ChatMessage(newId(), MsgRole.ASSISTANT, pending = true)
                        .also { assistantMsg = it; messages.value = messages.value + it }
                    m.text += t
                    streaming.value = m.text
                    state.value = TurnState.STREAMING
                }
            }
            "reasoning.delta", "thinking.delta" -> {
                val t = J.str(p, "text")
                if (t.isNotEmpty()) {
                    val m = assistantMsg ?: ChatMessage(newId(), MsgRole.ASSISTANT, pending = true)
                        .also { assistantMsg = it; messages.value = messages.value + it }
                    m.reasoning += t
                }
            }
            "tool.start" -> {
                val m = assistantMsg ?: ChatMessage(newId(), MsgRole.ASSISTANT, pending = true)
                    .also { assistantMsg = it; messages.value = messages.value + it }
                m.tools.add(ToolRun(J.str(p, "name", J.str(p, "tool", "ابزار")), "در حال اجرا"))
                state.value = TurnState.TOOL
            }
            "tool.complete", "tool.end" -> {
                val name = J.str(p, "name", J.str(p, "tool"))
                val m = assistantMsg
                m?.tools?.lastOrNull { it.name == name }?.let { it.status = "پایان"; it.detail = J.str(p, "summary") }
                    ?: m?.tools?.lastOrNull()?.let { it.status = "پایان"; it.detail = J.str(p, "summary") }
            }
            "tool.output_risk" -> {
                assistantMsg?.tools?.lastOrNull()?.let {
                    it.status = "نیازمند تأیید"; it.detail = J.str(p, "reason")
                }
                state.value = TurnState.WAITING_APPROVAL
            }
            "approval.request" -> {
                notice.value = "تأیید عملیات توسط سرور درخواست شد"
                state.value = TurnState.WAITING_APPROVAL
            }
            "message.complete", "turn.complete" -> {
                val pt = J.str(p, "text")
                val m = assistantMsg
                if (m != null) {
                    if (m.text.isEmpty()) m.text = if (pt.isNotEmpty()) pt else streaming.value
                    m.pending = false
                } else if (pt.isNotEmpty() || streaming.value.isNotEmpty()) {
                    messages.value = messages.value +
                        ChatMessage(newId(), MsgRole.ASSISTANT, if (pt.isNotEmpty()) pt else streaming.value)
                }
                if (J.str(p, "status") == "error") notice.value = pt.ifBlank { "خطا در اجرای نوبت" }
                state.value = TurnState.DONE
                streaming.value = ""
                assistantMsg = null
            }
            "turn.error", "error" -> {
                val msg = J.str(p, "message", J.str(p, "error", "خطای نامشخص"))
                val cur = assistantMsg
                if (cur != null) { cur.pending = false; cur.error = msg } else { notice.value = msg }
                state.value = TurnState.ERROR
                streaming.value = ""
                assistantMsg = null
            }
            "model.changed" -> { model.value = J.str(p, "model"); notice.value = "مدل تغییر کرد" }
            else -> Unit
        }
    }
}
