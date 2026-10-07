package ir.hermes.mobile.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.hermes.mobile.core.datastore.ModelSelection
import ir.hermes.mobile.data.ChatEngine
import ir.hermes.mobile.data.HermesRepo
import ir.hermes.mobile.data.J
import ir.hermes.mobile.data.MsgRole
import ir.hermes.mobile.data.StepStatus
import ir.hermes.mobile.data.TurnState
import ir.hermes.mobile.data.TurnStep
import ir.hermes.mobile.ui.components.*
import ir.hermes.mobile.ui.theme.*
import kotlinx.coroutines.launch
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Composable
fun ChatScreen(
    engine: ChatEngine,
    title: String,
    statusLive: Boolean,
    modelLabel: String,
    onNewSession: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenModel: () -> Unit,
    onApplyModel: (provider: String, model: String, baseUrl: String) -> Unit,
) {
    val listState = rememberLazyListState()
    var showModelPicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    val msgs by engine.messages.collectAsState()
    val steps by engine.steps.collectAsState()
    val st by engine.state.collectAsState()
    val notice by engine.notice.collectAsState()
    // «فعال» یعنی هرمس مشغول است. قبلاً فقط THINKING نشانگر داشت، پس وقتی هدر
    // «در حال نوشتن» می‌شد هیچ لودری پایین دیده نمی‌شد — اینجا همهٔ حالت‌ها پوشش
    // داده می‌شوند.
    val busy = st == TurnState.THINKING || st == TurnState.STREAMING ||
        st == TurnState.TOOL || st == TurnState.WAITING_APPROVAL

    LaunchedEffect(msgs.size, msgs.lastOrNull()?.text?.length, steps.size, steps.lastOrNull()?.rev) {
        if (msgs.isNotEmpty()) {
            // آخرین آیتم پنل مراحل است، پس در حال اجرا یکی بعد از پیام‌ها می‌رویم
            val last = if (busy || steps.isNotEmpty()) msgs.size else msgs.size - 1
            listState.animateScrollToItem(last.coerceAtLeast(0))
        }
    }

    // اگر نوبتی بیش از حد طول بکشد، اپ نباید بی‌صدا در «در حال فکر کردن» بماند.
    LaunchedEffect(st) {
        if (st == TurnState.THINKING || st == TurnState.STREAMING || st == TurnState.TOOL) {
            kotlinx.coroutines.delay(240_000)
            if (engine.state.value == TurnState.THINKING) {
                engine.notice.value = "هنوز پاسخی از سرور نیامده؛ می‌توانید با دکمهٔ توقف متوقف کنید."
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Gold.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) { Text("هـ", color = Gold, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title.ifBlank { "گفت‌وگوی جدید" },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(statusLive)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when (st) {
                                TurnState.THINKING -> "در حال فکر کردن…"
                                TurnState.STREAMING -> "در حال نوشتن…"
                                TurnState.TOOL -> "اجرای ابزار…"
                                TurnState.WAITING_APPROVAL -> "منتظر تأیید"
                                TurnState.ERROR -> "خطا"
                                TurnState.DONE -> "آماده"
                                TurnState.IDLE -> if (statusLive) "متصل" else "قطع"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMid,
                        )
                    }
                    // انتخاب مدل همون‌جا در چت: لمس کن، مدل تازه را بزن؛
                    // نشست فعلی کنار گذاشته می‌شود و نشست بعدی با همان مدل
                    // ساخته می‌شود (مدل هر نشست در هرمس هنگام ساخت ثابت می‌شود).
                    Row(
                        Modifier
                            .padding(top = 3.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Accent.copy(alpha = 0.12f))
                            .clickable { showModelPicker = true }
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.AutoAwesome, null, Modifier.size(11.dp), tint = Accent)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            modelLabel.ifBlank { "انتخاب مدل" },
                            style = MaterialTheme.typography.labelSmall,
                            color = Accent,
                            maxLines = 1,
                        )
                        Icon(Icons.Default.ExpandMore, null, Modifier.size(12.dp), tint = Accent)
                    }
                }
                IconButton(onClick = onOpenModel) { Icon(Icons.Default.Tune, "مدل", tint = TextMid) }
                IconButton(onClick = onOpenSessions) { Icon(Icons.Default.History, "نشست‌ها", tint = TextMid) }
                IconButton(onClick = onNewSession) { Icon(Icons.Default.AddComment, "نشست جدید", tint = Gold) }
            }
        },
        bottomBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.background)) {
                notice?.let {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(it, Modifier.weight(1f), color = Cyan, style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = { engine.notice.value = null }, Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, "بستن", Modifier.size(15.dp), tint = TextMid)
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Box(Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("پیام خود را بنویسید…", color = TextLow) },
                            shape = RoundedCornerShape(22.dp),
                            maxLines = 5,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Gold,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            ),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    if (busy) {
                        Box(
                            Modifier.size(52.dp).clip(CircleShape).background(Rose.copy(alpha = 0.18f))
                                .clickable { engine.interrupt() },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Default.Stop, "توقف", tint = Rose) }
                    } else {
                        Box(
                            Modifier.size(52.dp).clip(CircleShape).background(Gold)
                                .clickable(enabled = input.isNotBlank()) {
                                    val outgoing = input
                                    input = ""
                                    scope.launch { engine.send(outgoing) }
                                },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.AutoMirrored.Filled.Send, "ارسال", tint = Ink0) }
                    }
                }
            }
        },
    ) { pad ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (msgs.isEmpty()) {
                item { WelcomeCard(onSuggestion = { input = it }) }
            }
            items(msgs, key = { it.id }) { MessageCard(it) }
            // اگر مرحله‌ای ثبت نشده بود، حداقل نشانگر متحرک را نشان بده
            if (busy && steps.isEmpty()) item("dots") { ThinkingDots() }
            if (steps.isNotEmpty()) item("steps") { ActivityPanel(steps, busy) }
        }
    }

    if (showModelPicker) {
        ModelPickerDialog(
            onDismiss = { showModelPicker = false },
            onPick = { p, m, b ->
                showModelPicker = false
                onApplyModel(p, m, b)
            },
        )
    }
}

/** یک گزینهٔ قابل انتخاب در انتخابگر مدل */
private data class ModelChoice(
    val provider: String,
    val providerLabel: String,
    val model: String,
    val baseUrl: String,
)

/** استخراج فهرست شناسه‌های مدل از آرایهٔ JSON (رشته یا آبجکت با id/name). */
private fun jsonStrings(arr: JsonArray): List<String> =
    arr.mapNotNull { el ->
        (el as? JsonPrimitive)?.content
            ?: (el as? JsonObject)?.let { J.str(it, "id", J.str(it, "name")) }
    }.filter { it.isNotBlank() }

/**
 * انتخابگر مدل که همان‌جا در صفحهٔ چت باز می‌شود.
 *
 * اول از کش محلی پر می‌شود (فوری) و بعد در پس‌زمینه از هرمس تازه می‌شود؛
 * چون خواندن مدل‌ها از سرور روی گوشی کند است.
 */
@Composable
private fun ModelPickerDialog(
    onDismiss: () -> Unit,
    onPick: (String, String, String) -> Unit,
) {
    var choices by remember { mutableStateOf<List<ModelChoice>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val selection by HermesRepo.settings.selection.collectAsState(initial = ModelSelection())

    fun fromCache(): List<ModelChoice> {
        val out = ArrayList<ModelChoice>()
        HermesRepo.cachedModelOptions()?.let { opts ->
            for (el in J.listOf(opts, "providers")) {
                val p = J.obj(el)
                val slug = J.str(p, "slug", J.str(p, "name"))
                if (slug.isBlank()) continue
                val label = J.str(p, "name", slug)
                for (m in jsonStrings(J.listOf(p, "models"))) {
                    out += ModelChoice(slug, label, m, "")
                }
            }
        }
        HermesRepo.cachedEndpoints()?.let { eps ->
            for (el in eps) {
                val e = J.obj(el)
                val id = J.str(e, "id", J.str(e, "name"))
                if (id.isBlank()) continue
                val label = J.str(e, "name", id)
                val base = J.str(e, "base_url")
                for (m in jsonStrings(J.listOf(e, "models"))) {
                    out += ModelChoice(id, label, m, base)
                }
            }
        }
        return out
    }

    LaunchedEffect(Unit) {
        choices = fromCache()
        loading = choices.isEmpty()
        HermesRepo.modelOptions().onFailure { if (choices.isEmpty()) error = it.message }
        HermesRepo.customEndpoints()
        choices = fromCache()
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تغییر مدل") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "با انتخاب مدل، نوبت جاری متوقف می‌شود و نشست تازه‌ای با همان مدل ساخته می‌شود (هرمز مدل هر نشست را هنگام ساخت ثابت می‌کند).",
                    style = MaterialTheme.typography.bodySmall, color = TextLow,
                )
                Spacer(Modifier.height(10.dp))
                when {
                    loading -> LoadingRow()
                    choices.isEmpty() -> Text(
                        error ?: "مدلی از سرور خوانده نشد",
                        style = MaterialTheme.typography.bodySmall, color = TextMid,
                    )
                    else -> {
                        var lastProvider = ""
                        choices.take(120).forEach { c ->
                            if (c.providerLabel != lastProvider) {
                                lastProvider = c.providerLabel
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    c.providerLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Mint,
                                )
                            }
                            val isSel = c.model == selection.model &&
                                (selection.provider.isBlank() || c.provider == selection.provider)
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .then(
                                        if (isSel) Modifier.background(Accent.copy(alpha = 0.14f))
                                        else Modifier
                                    )
                                    .clickable { onPick(c.provider, c.model, c.baseUrl) }
                                    .padding(horizontal = 8.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    if (isSel) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                    null, Modifier.size(15.dp),
                                    tint = if (isSel) Accent else TextLow,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(c.model, style = MaterialTheme.typography.bodyMedium, color = TextHi, maxLines = 1)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("بستن") } },
    )
}

@Composable
private fun WelcomeCard(onSuggestion: (String) -> Unit) {
    val tips = listOf("سلام، چطور می‌تونی کمکم کنی؟", "وضعیت سیستم رو بررسی کن", "یه خلاصه از فایل‌های پروژه بده")
    GlassCard(Modifier.fillMaxWidth()) {
        Text("گفت‌وگو با هرمس", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "پیام‌تان را بنویسید یا یکی از نمونه‌ها را انتخاب کنید.",
            style = MaterialTheme.typography.bodySmall, color = TextMid
        )
        Spacer(Modifier.height(12.dp))
        tips.forEach {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .clickable { onSuggestion(it) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.AutoAwesome, null, Modifier.size(15.dp), tint = Gold)
                Spacer(Modifier.width(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * نمایش زندهٔ مرحله‌به‌مرحلهٔ کار هرمس: آماده‌سازی، فکر کردن، هر ابزار،
 * انتظار تأیید، نوشتن پاسخ و پایان/خطا.
 */
@Composable
private fun ActivityPanel(steps: List<TurnStep>, busy: Boolean) {
    var openStep by remember { mutableStateOf<TurnStep?>(null) }
    GlassCard(
        Modifier.fillMaxWidth(),
        borderColor = Accent.copy(alpha = if (busy) 0.45f else 0.18f),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp, color = Accent)
            } else {
                Icon(Icons.Default.CheckCircle, null, Modifier.size(14.dp), tint = Lime)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (busy) "هرمس در حال کار است…" else "مراحل اجرای نوبت اخیر",
                style = MaterialTheme.typography.labelLarge,
                color = if (busy) Accent else TextMid,
            )
        }
        Spacer(Modifier.height(8.dp))
        steps.forEach { s -> StepRow(s) { openStep = s } }
    }
    openStep?.let { s -> StepDetailDialog(s) { openStep = null } }
}

@Composable
private fun StepRow(s: TurnStep, onOpen: () -> Unit) {
    val c = when (s.status) {
        StepStatus.RUNNING -> Accent
        StepStatus.DONE -> Lime
        StepStatus.FAILED -> Rose
    }
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(if (s.openable) Modifier.clickable { onOpen() } else Modifier)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        when (s.status) {
            StepStatus.RUNNING ->
                CircularProgressIndicator(Modifier.padding(top = 3.dp).size(11.dp), strokeWidth = 2.dp, color = c)
            StepStatus.DONE -> Icon(Icons.Default.Check, null, Modifier.size(13.dp), tint = c)
            StepStatus.FAILED -> Icon(Icons.Default.Close, null, Modifier.size(13.dp), tint = c)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title, style = MaterialTheme.typography.bodySmall, color = TextHi, maxLines = 2)
            if (s.detail.isNotBlank()) {
                Text(s.detail, style = MaterialTheme.typography.labelSmall, color = TextLow, maxLines = 3)
            }
        }
        // نشانهٔ «قابل بازکردن»: کاربر می‌تواند روی مرحله بزند و جزئیات را ببیند.
        if (s.openable) {
            Spacer(Modifier.width(6.dp))
            Text("جزئیات", style = MaterialTheme.typography.labelSmall, color = Accent, maxLines = 1)
            Icon(Icons.Default.ChevronLeft, null, Modifier.size(14.dp), tint = Accent)
        }
    }
}

/** نمایش جزئیات کامل یک مرحله (اجرای دستور/خواندن فایل و…) با کلیک روی آن. */
@Composable
private fun StepDetailDialog(s: TurnStep, onDismiss: () -> Unit) {
    val statusText = when (s.status) {
        StepStatus.RUNNING -> "در حال اجرا"
        StepStatus.DONE -> "پایان‌یافته"
        StepStatus.FAILED -> "ناموفق"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.title) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                SelectionContainer {
                    Column {
                        StepDetailLine("وضعیت", statusText)
                        if (s.durationMs > 0) StepDetailLine("مدت", formatDuration(s.durationMs))
                        if (s.context.isNotBlank()) StepDetailBlock("توضیح", s.context)
                        if (s.args.isNotBlank()) StepDetailBlock("ورودی / دستور", s.args)
                        if (s.result.isNotBlank()) StepDetailBlock("خروجی / نتیجه", s.result)
                        if (s.args.isBlank() && s.result.isBlank() && s.context.isBlank()) {
                            StepDetailBlock("جزئیات", s.detail.ifBlank { "جزئیات بیشتری ثبت نشده است." })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("بستن") } },
    )
}

@Composable
private fun StepDetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextMid)
        Spacer(Modifier.width(8.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = TextHi)
    }
}

@Composable
private fun StepDetailBlock(label: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Mint)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Ink2.copy(alpha = 0.7f))
                .padding(10.dp)
        ) {
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = TextMid,
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val secs = ms / 1000.0
    return if (secs < 1) "$ms میلی‌ثانیه" else "${String.format(Locale.US, "%.1f", secs)} ثانیه"
}

@Composable
private fun ThinkingDots() {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.Center) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(3) { i ->
                val a = rememberInfiniteTransition(label = "d$i")
                val alpha by a.animateFloat(
                    0.3f, 1f,
                    androidx.compose.animation.core.infiniteRepeatable(
                        androidx.compose.animation.core.tween(600, delayMillis = i * 180),
                        androidx.compose.animation.core.RepeatMode.Reverse,
                    ), label = "a$i"
                )
                Box(Modifier.padding(horizontal = 3.dp).size(6.dp).clip(CircleShape).background(Gold.copy(alpha = alpha)))
            }
        }
    }
}

@Composable
private fun MessageCard(m: ir.hermes.mobile.data.ChatMessage) {
    val isUser = m.role == MsgRole.USER
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        Row(
            Modifier
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp, topEnd = 18.dp,
                        bottomStart = if (isUser) 18.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 18.dp,
                    )
                )
                .background(
                    if (isUser) Gold.copy(alpha = 0.16f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                )
                .border(
                    1.dp,
                    (if (isUser) Gold else MaterialTheme.colorScheme.outline).copy(alpha = 0.22f),
                    RoundedCornerShape(18.dp),
                )
                .padding(14.dp)
        ) {
            SelectionContainer {
                Column {
                    if (m.reasoning.isNotBlank()) {
                        ReasoningBlock(m.reasoning)
                        Spacer(Modifier.height(8.dp))
                    }
                    if (m.text.isNotBlank()) {
                        Text(m.text, style = MaterialTheme.typography.bodyMedium)
                    } else if (m.pending) {
                        Text("…", color = TextMid)
                    }
                    m.tools.forEach { ToolChip(it.name, it.status, it.detail) }
                    m.error?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = Rose, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (isUser) "شما" else "هرمس",
            style = MaterialTheme.typography.labelSmall,
            color = TextLow,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
}

@Composable
private fun ReasoningBlock(text: String) {
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(Violet.copy(alpha = 0.10f)).padding(10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Psychology, null, Modifier.size(15.dp), tint = Violet)
            Spacer(Modifier.width(6.dp))
            Text(
                if (open) "بازاندیشی" else "نمایش بازاندیشی",
                style = MaterialTheme.typography.labelSmall, color = Violet,
            )
        }
        AnimatedVisibility(open) {
            Text(text, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = TextMid)
        }
    }
}

@Composable
private fun ToolChip(name: String, status: String, detail: String) {
    val c = when (status) {
        "پایان" -> Lime
        "نیازمند تأیید" -> Gold
        else -> Cyan
    }
    Row(
        Modifier.padding(top = 8.dp).fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(c.copy(alpha = 0.10f))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Build, null, Modifier.size(14.dp), tint = c)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.labelMedium, color = c)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.labelSmall, color = TextMid)
        }
        Text(status, style = MaterialTheme.typography.labelSmall, color = c)
    }
}
