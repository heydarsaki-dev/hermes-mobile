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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.hermes.mobile.core.datastore.ModelSelection
import ir.hermes.mobile.data.ChatEngine
import ir.hermes.mobile.data.HermesRepo
import ir.hermes.mobile.data.J
import ir.hermes.mobile.data.MsgRole
import ir.hermes.mobile.data.TurnState
import ir.hermes.mobile.ui.components.*
import ir.hermes.mobile.ui.theme.*
import kotlinx.coroutines.launch
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
    val st by engine.state.collectAsState()
    val notice by engine.notice.collectAsState()
    // «فعال» یعنی هرمس مشغول است. قبلاً فقط THINKING نشانگر داشت، پس وقتی هدر
    // «در حال نوشتن» می‌شد هیچ لودری پایین دیده نمی‌شد — اینجا همهٔ حالت‌ها پوشش
    // داده می‌شوند.
    val busy = st == TurnState.THINKING || st == TurnState.STREAMING ||
        st == TurnState.TOOL || st == TurnState.WAITING_APPROVAL

    LaunchedEffect(msgs.size, msgs.lastOrNull()?.text?.length, msgs.lastOrNull()?.reasoning?.length) {
        if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1)
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
            // در حال کار است ولی هنوز هیچ خروجی‌ای نیامده: فقط نقاط متحرک
            if (busy && msgs.none { it.role == MsgRole.ASSISTANT && (it.text.isNotBlank() || it.reasoning.isNotBlank()) }) {
                item("dots") { ThinkingDots() }
            }
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
                    "با انتخاب مدل، نوبت جاری متوقف می‌شود و نشست تازه‌ای با همان مدل ساخته می‌شود (هرمس مدل هر نشست را هنگام ساخت ثابت می‌کند).",
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
                    if (m.text.isNotBlank()) {
                        Text(m.text, style = MaterialTheme.typography.bodyMedium)
                    } else if (m.pending && m.reasoning.isBlank() && m.tools.isEmpty()) {
                        Text("…", color = TextMid)
                    }
                    m.error?.let {
                        if (m.text.isNotBlank()) Spacer(Modifier.height(6.dp))
                        Text(it, color = Rose, style = MaterialTheme.typography.bodySmall)
                    }
                    // فعالیت‌های این پیام در پایین آن فهرست می‌شوند: بازاندیشی و
                    // سپس هر ابزار یا دستوری که صدا زده شده. ردیف‌ها کوچک و هم‌شکل
                    // هستند، هر کدام آیکون خودش را دارد و با کلیک، متن کاملش با
                    // فونت ریز باز می‌شود.
                    activityItems(m).forEach { ActivityRow(it) }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            when (m.role) {
                MsgRole.USER -> "شما"
                MsgRole.TOOL -> "ابزار"
                else -> "هرمس"
            },
            style = MaterialTheme.typography.labelSmall,
            color = TextLow,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
}

/**
 * یک فعالیتِ پایینِ پیام: صدا زدن یک ابزار، اجرای یک دستور، یا متن بازاندیشی.
 *
 * همهٔ این‌ها به‌صورت ردیف‌های کوچک و یکدست نشان داده می‌شوند (نه کادر بزرگ
 * مراحل اجرا): یک آیکون کوچک، یک خط خلاصه، و با کلیک، متن کامل با فونت ریز.
 */
private data class ActivityItem(
    val icon: ImageVector,
    val tint: Color,
    val title: String,
    val status: String,
    val detail: String,
)

/** آیکون مناسب برای هر ابزار، بر اساس نام آن (نام ابزارها از سرور می‌آید). */
private fun toolIcon(name: String): ImageVector {
    val n = name.lowercase()
    return when {
        n.contains("shell") || n.contains("bash") || n.contains("terminal") ||
            n.contains("command") || n.contains("cmd") || n.contains("exec") -> Icons.Default.Terminal
        n.contains("file") || n.contains("read") || n.contains("cat") -> Icons.Default.Description
        n.contains("write") || n.contains("edit") || n.contains("save") || n.contains("patch") -> Icons.Default.Edit
        n.contains("search") || n.contains("grep") || n.contains("find") || n.contains("glob") -> Icons.Default.Search
        n.contains("web") || n.contains("http") || n.contains("url") || n.contains("fetch") -> Icons.Default.Public
        n.contains("todo") || n.contains("task") || n.contains("plan") -> Icons.Default.Checklist
        n.contains("code") || n.contains("script") || n.contains("python") || n.contains("eval") -> Icons.Default.Code
        n.contains("folder") || n.contains("dir") || n.contains("tree") -> Icons.Default.Folder
        n.contains("memory") || n.contains("remember") || n.contains("know") -> Icons.Default.Memory
        n.contains("skill") || n.contains("tool") -> Icons.Default.Build
        else -> Icons.Default.Build
    }
}

/**
 * فعالیتهای یک پیام را می‌سازد: ابتدا بازاندیشی (اگر هست)، سپس ابزارها.
 *
 * نکته: متن بازاندیشی از بالای پیام به اینجا منتقل شد تا دقیقاً مثل صدا زدن
 * ابزار یا اجرای دستور، یک ردیف کوچک در پایین پیام باشد.
 */
private fun activityItems(m: ir.hermes.mobile.data.ChatMessage): List<ActivityItem> {
    val out = ArrayList<ActivityItem>()
    if (m.reasoning.isNotBlank()) {
        val thinking = m.pending && m.text.isBlank()
        out += ActivityItem(
            icon = Icons.Default.Psychology,
            tint = Violet,
            title = "بازاندیشی",
            status = if (thinking) "در حال اجرا" else "پایان",
            detail = m.reasoning,
        )
    }
    m.tools.forEach { t ->
        out += ActivityItem(
            icon = toolIcon(t.name),
            tint = when (t.status) {
                "پایان" -> Lime
                "نیازمند تأیید" -> Gold
                else -> Cyan
            },
            title = t.name,
            status = t.status,
            detail = t.detail,
        )
    }
    return out
}

@Composable
private fun ActivityRow(a: ActivityItem) {
    var open by remember { mutableStateOf(false) }
    val canOpen = a.detail.isNotBlank()
    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(a.tint.copy(alpha = 0.09f))
            .then(if (canOpen) Modifier.clickable { open = !open } else Modifier)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(a.icon, null, Modifier.size(13.dp), tint = a.tint)
            Spacer(Modifier.width(6.dp))
            Text(
                a.title,
                style = MaterialTheme.typography.labelSmall,
                color = TextHi,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (a.status == "در حال اجرا") {
                CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp, color = a.tint)
            } else {
                Text(a.status, style = MaterialTheme.typography.labelSmall, color = a.tint, maxLines = 1)
            }
            if (canOpen) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null, Modifier.size(12.dp), tint = TextLow,
                )
            }
        }
        AnimatedVisibility(open) {
            Text(
                a.detail,
                Modifier.fillMaxWidth().padding(top = 5.dp),
                style = MaterialTheme.typography.bodySmall,
                color = TextMid,
            )
        }
    }
}
