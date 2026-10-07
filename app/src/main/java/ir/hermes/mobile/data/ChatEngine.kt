package ir.hermes.mobile.data

import ir.hermes.mobile.core.net.RpcEvent
import ir.hermes.mobile.core.util.Jalali
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

enum class StepStatus { RUNNING, DONE, FAILED }

/**
 * یک مرحله از اجرای نوبت.
 *
 * برای نمایش زندهٔ «هرمس دارد چه‌کار می‌کند» نگه داشته می‌شود: فکر کردن،
 * نوشتن پاسخ، هر ابزاری که اجرا می‌شود، انتظار تأیید، پایان یا خطا.
 */
data class TurnStep(
    val id: Long,
    val kind: String,
    val title: String,
    var detail: String = "",
    var status: StepStatus = StepStatus.RUNNING,
    val at: Long = System.currentTimeMillis(),
    /** تعداد کاراکترهای انباشته (برای دلتاهای متن/بازاندیشی) */
    var chars: Long = 0,
    var rev: Long = 0,
    /** شناسهٔ ابزار در سرور، برای تطبیق tool.start با tool.complete */
    var toolId: String = "",
    /** توضیح کوتاهِ خوانا از سرور (مثل «خواندن فایل …» یا «اجرای دستور …») */
    var context: String = "",
    /** ورودی ابزار (JSON یا متن args) */
    var args: String = "",
    /** خروجی ابزار (نتیجهٔ اجرای دستور یا محتوای فایل) */
    var result: String = "",
    /** مدت اجرای ابزار به میلی‌ثانیه */
    var durationMs: Long = 0,
) {
    /** آیا این مرحله جزئیاتی دارد که ارزش بازکردن داشته باشد؟ */
    val openable: Boolean
        get() = kind == "tool" || context.isNotBlank() || args.isNotBlank() || result.isNotBlank()
}

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

    /** حبابِ مستقلِ ابزارِ در حال اجرا (خارج از حباب توضیح هرمس). */
    private var toolMsg: ChatMessage? = null

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
        if (toolMsg?.id == fresh.id) toolMsg = fresh
    }

    /**
     * بستن حباب پاسخِ در حال استریم.
     *
     * هرمس در یک نوبت ممکن است چند پیام پشتسرهم بفرستد (توضیح قبل از
     * اجرای ابزار، خودِ ابزار، و پاسخ پایانی). سرور برای هر پیام `message.start`
     * و برای متنهای میان‌نوبت `message.interim` می‌فرستد. با بستن حباب فعلی،
     * متن بعدی در حباب تازهٔ خودش می‌آید و همه‌چیز به‌هم نمی‌چسبد.
     */
    private fun sealAssistant() {
        assistantMsg?.let { if (it.pending) { it.pending = false; touch(it) } }
        assistantMsg = null
    }

    // ---------- مراحل اجرا (نمایش زندهٔ کار هرمس) ----------

    private val _steps = MutableStateFlow<List<TurnStep>>(emptyList())
    val steps: StateFlow<List<TurnStep>> = _steps.asStateFlow()
    private var stepSeq = 0L

    /** اعلام تغییر یک مرحله؛ مثل [touch] برای اینکه Compose تغییر را ببیند. */
    private fun touchStep(s: TurnStep) {
        val list = _steps.value.toMutableList()
        val i = list.indexOfFirst { it.id == s.id }
        if (i < 0) return
        list[i] = s.copy(rev = s.rev + 1)
        _steps.value = list
    }

    private fun addStep(
        kind: String,
        title: String,
        detail: String = "",
        status: StepStatus = StepStatus.RUNNING,
    ): TurnStep {
        val s = TurnStep(++stepSeq, kind, title, detail, status)
        _steps.value = _steps.value + s
        return s
    }

    /** آخرین مرحلهٔ در حال اجرا از این نوع، یا ساخت مرحلهٔ تازه. */
    private fun step(kind: String, title: String): TurnStep =
        _steps.value.lastOrNull { it.kind == kind && it.status == StepStatus.RUNNING }
            ?: addStep(kind, title)

    private fun finishSteps(status: StepStatus = StepStatus.DONE) {
        _steps.value = _steps.value.map {
            if (it.status == StepStatus.RUNNING) it.copy(status = status, rev = it.rev + 1) else it
        }
    }

    fun loadHistory(items: List<ChatMessage>) {
        if (messages.value.isEmpty()) messages.value = items
    }

    /**
     * بازکردن یک نشست ذخیره‌شده در چت: تاریخچهٔ آن از سرور خوانده و جای
     * گفت‌وگوی فعلی نشان داده می‌شود.
     *
     * بدون این کار، کلیک روی هر نشست فقط شناسهٔ فعال را عوض می‌کرد و چت همچنان
     * پیام‌های نشست قبلی را نشان می‌داد — یعنی به‌نظر می‌رسید هر نشست همان نشست
     * فعال است.
     */
    suspend fun openSession(sid: String) {
        messages.value = emptyList()
        _steps.value = emptyList()
        state.value = TurnState.IDLE
        streaming.value = ""
        notice.value = null
        assistantMsg = null
        toolMsg = null
        if (sid.isBlank()) return
        HermesRepo.history(sid).onSuccess { r ->
            val list = J.listOf(J.obj(r), "messages", "items")
                .mapNotNull { toMessage(J.obj(it)) }
            messages.value = list
        }
    }

    /** تبدیل یک پیام تاریخچهٔ سرور به [ChatMessage]. */
    private fun toMessage(o: JsonObject): ChatMessage? {
        val text = J.str(o, "text")
        return when (J.str(o, "role")) {
            "user" -> ChatMessage(newId(), MsgRole.USER, text)
            "assistant" -> ChatMessage(
                newId(), MsgRole.ASSISTANT, text,
                reasoning = J.str(
                    o, "reasoning",
                    J.str(o, "reasoning_content", J.str(o, "thinking")),
                ),
            )
            "tool" -> ChatMessage(newId(), MsgRole.TOOL).also {
                it.tools.add(ToolRun(J.str(o, "name", "ابزار"), "پایان", J.str(o, "context")))
            }
            "system" -> ChatMessage(newId(), MsgRole.SYSTEM, text)
            else -> null
        }
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
        toolMsg = null
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

    /** کوتاه‌کردن متن‌های بلند (خروجی ابزار می‌تواند بسیار بزرگ باشد). */
    private fun clamp(s: String, max: Int = 8000): String =
        if (s.length <= max) s else s.take(max) + "\n… (ادامه بریده شد)"

    /** آیا خطای سرور یعنی «این نشست را نمی‌شناسم»؟ */
    private fun isStaleSession(msg: String?): Boolean {
        val m = (msg ?: "").lowercase()
        return m.contains("session not found") || m.contains("4001") || m.contains("4007")
    }

    /** نمایش خطای قطعی برای نوبت جاری (حبهٔ راهنما + پیام خطا). */
    private fun fail(msg: String) {
        state.value = TurnState.ERROR
        streaming.value = ""
        finishSteps(StepStatus.FAILED)
        addStep("error", "خطا", msg, StepStatus.FAILED)
        val m = ChatMessage(newId(), MsgRole.ASSISTANT)
        m.error = msg
        messages.value = messages.value + m
        assistantMsg = null
        toolMsg = null
    }

    /** پاک‌سازی برای شروع یک نشست جدید. */
    fun resetForNewSession() {
        messages.value = emptyList()
        state.value = TurnState.IDLE
        streaming.value = ""
        notice.value = null
        assistantMsg = null
        toolMsg = null
        _steps.value = emptyList()
    }

    fun interrupt() {
        HermesRepo.socket.interrupt()
        finishSteps(StepStatus.DONE)
        addStep("turn", "متوقف شد", "", StepStatus.DONE)
        state.value = TurnState.DONE
        streaming.value = ""
    }

    fun onEvent(ev: RpcEvent.Event) {
        val p = ev.payload
        when (ev.type) {
            "gateway.ready" -> notice.value = "دروازه آماده است"
            "turn.start" -> {
                state.value = TurnState.THINKING
                _steps.value = emptyList()
                assistantMsg = null
                toolMsg = null
                addStep("think", "آماده‌سازی نوبت")
                streaming.value = ""
            }
            // هرمس برای هر پیام پاسخِ تازه یک message.start می‌فرستد؛ حباب قبلی
            // بسته می‌شود تا هر پیام در حباب جداگانهٔ خودش بیاید.
            "message.start" -> {
                sealAssistant()
                toolMsg = null
            }
            // متن میان‌نوبت (مثلاً توضیح قبل از اجرای ابزار): حباب فعلی بسته
            // می‌شود تا به پاسخ بعدی نچسبد.
            "message.interim" -> {
                val t = J.str(p, "text")
                if (!J.bool(p, "already_streamed") && t.isNotBlank()) {
                    val m = assistantMsg ?: ChatMessage(newId(), MsgRole.ASSISTANT, pending = true)
                        .also { assistantMsg = it; messages.value = messages.value + it }
                    m.text += t
                    touch(m)
                }
                sealAssistant()
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
                    // مرحلهٔ «نوشتن پاسخ» با شمار کاراکترهای دریافتی
                    val s = step("write", "نوشتن پاسخ")
                    s.chars += t.length
                    s.detail = Jalali.fa(s.chars) + " کاراکتر"
                    touchStep(s)
                }
            }
            "reasoning.delta", "thinking.delta" -> {
                val t = J.str(p, "text")
                if (t.isNotEmpty()) {
                    val m = assistantMsg ?: ChatMessage(newId(), MsgRole.ASSISTANT, pending = true)
                        .also { assistantMsg = it; messages.value = messages.value + it }
                    m.reasoning += t
                    touch(m)
                    // مرحلهٔ «فکر کردن» با شمار کاراکترهای بازاندیشی
                    val s = step("think", "فکر کردن")
                    s.chars += t.length
                    s.detail = Jalali.fa(s.chars) + " کاراکتر"
                    touchStep(s)
                }
            }
            "tool.start" -> {
                val toolName = J.str(p, "name", J.str(p, "tool", "ابزار"))
                val toolId = J.str(p, "tool_id")
                val ctx = J.str(p, "context", J.str(p, "detail"))
                // توضیح هرمس قبل از اجرای ابزار در حباب خودش بسته می‌شود و خودِ
                // ابزار حباب جداگانه می‌گیرد؛ این‌طور در حین اجرا پاسخ‌ها به‌هم
                // نمی‌چسبند و هر بخش جدا دیده می‌شود.
                sealAssistant()
                val m = ChatMessage(newId(), MsgRole.TOOL)
                m.tools.add(ToolRun(toolName, "در حال اجرا", ctx))
                messages.value = messages.value + m
                toolMsg = m
                state.value = TurnState.TOOL
                // مرحلهٔ ابزار با جزئیات کامل نگه داشته می‌شود تا کاربر با کلیک
                // روی آن ببیند هرمس دقیقاً چه دستوری اجرا می‌کند یا چه فایلی
                // می‌خواند.
                val s = addStep("tool", "ابزار: " + toolName, ctx)
                s.toolId = toolId
                s.context = ctx
                s.args = clamp(J.str(p, "args_text"))
                touchStep(s)
            }
            "tool.complete", "tool.end" -> {
                val name = J.str(p, "name", J.str(p, "tool"))
                val toolId = J.str(p, "tool_id")
                val summary = J.str(p, "summary")
                val m = toolMsg
                m?.tools?.lastOrNull { it.name == name }?.let { it.status = "پایان"; it.detail = summary }
                    ?: m?.tools?.lastOrNull()?.let { it.status = "پایان"; it.detail = summary }
                touch(m)
                toolMsg = null
                // مرحلهٔ متناظر (اول با tool_id، وگرنه آخرین ابزار در حال اجرا)
                val s = _steps.value.lastOrNull { it.kind == "tool" && it.toolId.isNotBlank() && it.toolId == toolId }
                    ?: _steps.value.lastOrNull { it.kind == "tool" && it.status == StepStatus.RUNNING }
                s?.let { st ->
                    st.status = StepStatus.DONE
                    if (summary.isNotBlank()) st.detail = summary
                    st.durationMs = (J.num(p, "duration_s") * 1000).toLong()
                    p["args"]?.let { st.args = clamp(J.pretty(it)) }
                    val rt = J.str(p, "result_text")
                    val out = if (rt.isNotBlank()) rt
                    else p["result"]?.let { if (it is JsonPrimitive) it.content else J.pretty(it) } ?: ""
                    if (out.isNotBlank()) st.result = clamp(out)
                    if (st.context.isBlank()) st.context = J.str(p, "context")
                    touchStep(st)
                }
            }
            "tool.output_risk" -> {
                toolMsg?.tools?.lastOrNull()?.let {
                    it.status = "نیازمند تأیید"; it.detail = J.str(p, "reason")
                }
                state.value = TurnState.WAITING_APPROVAL
                touch(toolMsg)
                addStep("approve", "نیازمند تأیید", J.str(p, "reason"), StepStatus.RUNNING)
            }
            "approval.request" -> {
                notice.value = "تأیید عملیات توسط سرور درخواست شد"
                state.value = TurnState.WAITING_APPROVAL
                addStep("approve", "درخواست تأیید از سرور", J.str(p, "reason"))
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
                finishSteps(if (J.str(p, "status") == "error") StepStatus.FAILED else StepStatus.DONE)
                addStep(
                    "turn",
                    if (J.str(p, "status") == "error") "پایان با خطا" else "پایان نوبت",
                    "",
                    if (J.str(p, "status") == "error") StepStatus.FAILED else StepStatus.DONE,
                )
                state.value = TurnState.DONE
                streaming.value = ""
                assistantMsg = null
                toolMsg = null
            }
            "turn.error", "error" -> {
                val msg = J.str(p, "message", J.str(p, "error", "خطای نامشخص"))
                val low = msg.lowercase()
                // رد درخواست به‌دلیل فیلتر محتوای پرووایدر: قطعی است و با تغییر
                // متن یا مدل حل می‌شود؛ پس به‌جای خطای خام انگلیسی راهنمای
                // فارسی نشان می‌دهیم.
                val policyBlocked = low.contains("sensitive content") ||
                    low.contains("safety filter") || low.contains("content_filter") ||
                    low.contains("content policy") || low.contains("usage policies")
                val friendly = if (policyBlocked) {
                    "فیلتر محتوای پرووایدر این درخواست را رد کرد (خطای هرمس نیست). " +
                        "متن را ساده‌تر بازنویسی کنید یا از صفحهٔ مدل یک مدل/پرووایدر دیگر انتخاب کنید."
                } else null
                val cur = assistantMsg
                if (cur != null) { cur.pending = false; cur.error = friendly ?: msg }
                notice.value = when {
                    friendly != null -> friendly
                    low.contains("timed out") || low.contains("initializ") ->
                        "راهنما: اتصال پرووایدر سفارشی را با دکمهٔ «تست» بررسی کنید و از «تنظیمات → لاگ سرور هرمس» دلیل دقیق را ببینید."
                    cur == null -> msg
                    else -> null
                }
                finishSteps(StepStatus.FAILED)
                addStep("error", "خطا", msg, StepStatus.FAILED)
                state.value = TurnState.ERROR
                streaming.value = ""
                assistantMsg = null
                toolMsg = null
            }
            "model.changed" -> { model.value = J.str(p, "model"); notice.value = "مدل تغییر کرد" }
            else -> Unit
        }
    }
}
