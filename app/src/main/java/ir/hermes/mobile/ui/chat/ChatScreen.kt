package ir.hermes.mobile.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import ir.hermes.mobile.data.ChatEngine
import ir.hermes.mobile.data.MsgRole
import ir.hermes.mobile.data.TurnState
import ir.hermes.mobile.ui.components.*
import ir.hermes.mobile.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    engine: ChatEngine,
    title: String,
    statusLive: Boolean,
    onNewSession: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenModel: () -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    val msgs by engine.messages.collectAsState()
    val st by engine.state.collectAsState()
    val notice by engine.notice.collectAsState()
    val busy = st == TurnState.THINKING || st == TurnState.STREAMING || st == TurnState.TOOL

    LaunchedEffect(msgs.size, msgs.lastOrNull()?.text?.length) {
        if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1)
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
                                    engine.send(input); input = ""
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
            if (st == TurnState.THINKING) item { ThinkingDots() }
        }
    }
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
