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
    /** نسخهٔ پیام؛ با هر تغییر یک می‌شود تا StateFlow/Compose تغییر را ببینند */
    var rev: Long = 0,
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

    /**
     * اعلام تغییر یک پیام.
     *
     * `ChatMessage` در جا (in-place) تغییر می‌کرد، اما StateFlow تغییر را با
     * `equals` تشخیص می‌دهد؛ چون همان نمونهٔ قبلی بود هیچ بازتابی (recomposition)
     * رخ نمی‌داد و متن استریم تا خروج و ورود دوباره به صفحه دیده نمی‌شد.
     * اینجا عنصر با یک نمونهٔ تازه (`rev + 1`) جایگزین می‌شود تا هم StateFlow
     * تغیر را منتشر کند و هم Compose کارت پیام را از نو بچیند.
     */
    private fun touch(m: ChatMessage?) {
        m ?: return
        val list = messages.value.toMutableList()
        val i = list.indexOfFirst { it.id == m.id }
        if (i < 0) return
        val fresh = m.copy(rev = m.rev + 1)
        list[i] = fresh
        messages.value = list
        if (assistantMsg?.id == fresh.id) assistantMsg = fresh
    }

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
        var sid = HermesRepo.ensureSession()
        if (sid.isBlank()) {
            fail("نشست هرمس ساخته نشد — اتصال به سرور را بررسی کنید")
            return
        }
        var res = HermesRepo.rpc("prompt.submit", submitParams(sid, clean))
        // اگر سرور نشست را نشناسد (مثلاً بعد از ری‌استارت runtime یا resume)،
        // یک‌بار نشست تازه ساخته و همان پیام دوباره فرستاده می‌شود.
        if (res.isFailure && isStaleSession(res.exceptionOrNull()?.message)) {
            HermesRepo.addLog("نشست نامعتبر بود؛ ساخت نشست تازه و ارسال دوباره")
            HermesRepo.clearSession()
            val fresh = HermesRepo.ensureSession()
            if (fresh.isNotBlank() && fresh != sid) {
                sid = fresh
                res = HermesRepo.rpc("prompt.submit", submitParams(sid, clean))
            }
        }
        res.onFailure { e ->
            // اگر سرور هنوز رویدادی نفرستاده، خطای ارسال را نشان بده.
            if (state.value == TurnState.THINKING) fail(e.message ?: "ارسال پیام ناموفق بود")
        }
        HermesRepo.addLog("ارسال پیام")
    }

    private fun submitParams(sid: String, text: String) = buildJsonObject {
        put("session_id", sid)
        put("text", text)
    }

    /** آیا خطای سرور یعنی «این نشست را نمی‌شناسم»؟ */
    private fun isStaleSession(msg: String?): Boolean {
        val m = (msg ?: "").lowercase()
        return m.contains("session not found") || m.contains("4001") || m.contains("4007")
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
                    touch(m)
                }
            }
            "reasoning.delta", "thinking.delta" -> {
                val t = J.str(p, "text")
                if (t.isNotEmpty()) {
                    val m = assistantMsg ?: ChatMessage(newId(), MsgRole.ASSISTANT, pending = true)
                        .also { assistantMsg = it; messages.value = messages.value + it }
                    m.reasoning += t
                    touch(m)
                }
            }
            "tool.start" -> {
                val m = assistantMsg ?: ChatMessage(newId(), MsgRole.ASSISTANT, pending = true)
                    .also { assistantMsg = it; messages.value = messages.value + it }
                m.tools.add(ToolRun(J.str(p, "name", J.str(p, "tool", "ابزار")), "در حال اجرا"))
                state.value = TurnState.TOOL
                touch(m)
            }
            "tool.complete", "tool.end" -> {
                val name = J.str(p, "name", J.str(p, "tool"))
                val m = assistantMsg
                m?.tools?.lastOrNull { it.name == name }?.let { it.status = "پایان"; it.detail = J.str(p, "summary") }
                    ?: m?.tools?.lastOrNull()?.let { it.status = "پایان"; it.detail = J.str(p, "summary") }
                touch(m)
            }
            "tool.output_risk" -> {
                assistantMsg?.tools?.lastOrNull()?.let {
                    it.status = "نیازمند تأیید"; it.detail = J.str(p, "reason")
                }
                state.value = TurnState.WAITING_APPROVAL
                touch(assistantMsg)
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
                    touch(m)
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
                if (cur != null) { cur.pending = false; cur.error = msg }
                val low = msg.lowercase()
                notice.value = if (low.contains("timed out") || low.contains("initializ")) {
                    "راهنما: اتصال پرووایدر سفارشی را با دکمهٔ «تست» بررسی کنید و از «تنظیمات → لاگ سرور هرمس» دلیل دقیق را ببینید."
                } else if (cur == null) msg else null
                state.value = TurnState.ERROR
                streaming.value = ""
                assistantMsg = null
            }
            "model.changed" -> { model.value = J.str(p, "model"); notice.value = "مدل تغییر کرد" }
            else -> Unit
        }
    }
}
