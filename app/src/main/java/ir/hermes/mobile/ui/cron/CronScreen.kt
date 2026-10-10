package ir.hermes.mobile.ui.cron

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.hermes.mobile.core.util.Jalali
import ir.hermes.mobile.data.HermesRepo
import ir.hermes.mobile.data.J
import ir.hermes.mobile.ui.components.*
import ir.hermes.mobile.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/* ───────────────────────── نوع زمان‌بندی ───────────────────────── */

private enum class CronKind(val label: String) {
    EVERY("هر چند وقت"), DAILY("روزانه"), WEEKDAYS("روزهای هفته"), ONCE("یک‌بار"), CUSTOM("دستی")
}

/** تبدیل ورودی‌های فرم به رشتهٔ زمان‌بندیِ قابل فهم برای سرور */
private fun buildSchedule(kind: CronKind, n: Int, hour: Boolean, hh: String, mm: String, expr: String): String = when (kind) {
    CronKind.EVERY -> "every $n${if (hour) "h" else "m"}"
    CronKind.ONCE -> "$n${if (hour) "h" else "m"}"
    CronKind.DAILY -> "${mm.padStart(2, '0')} ${hh.padStart(2, '0')} * * *"
    CronKind.WEEKDAYS -> "${mm.padStart(2, '0')} ${hh.padStart(2, '0')} * * 1-5"
    CronKind.CUSTOM -> expr.trim()
}

/** آیا فرمِ زمان‌بندی برای ارسال آماده است؟ */
private fun scheduleValid(kind: CronKind, n: Int, hh: String, mm: String, expr: String): Boolean = when (kind) {
    CronKind.EVERY, CronKind.ONCE -> n > 0
    CronKind.DAILY, CronKind.WEEKDAYS -> hh.toIntOrNull() in 0..23 && mm.toIntOrNull() in 0..59
    CronKind.CUSTOM -> expr.trim().split(Regex("\\s+")).size >= 5
}

/* ───────────────────────── کمک‌کارهای تاریخ ───────────────────────── */

private fun isoToMillis(s: String): Long? {
    if (s.isBlank()) return null
    return runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        .recoverCatching { LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
        .getOrNull()
}

private fun fmtIso(s: String): String {
    val m = isoToMillis(s) ?: return "—"
    return Jalali.format(m)
}

/** نمایش فارسی و خوانا برای زمان‌بندی خام سرور */
private fun scheduleToPersian(raw: String): String {
    val s = raw.trim()
    if (s.isBlank()) return "—"
    // every 30m / every 2h
    Regex("^every\\s+(\\d+)\\s*([mh])$", RegexOption.IGNORE_CASE).find(s)?.let {
        val n = it.groupValues[1]
        return when (it.groupValues[2].lowercase()) {
            "h" -> "هر $n ساعت"
            else -> "هر $n دقیقه"
        }
    }
    // 0 9 * * *  →  هر روز ساعت ۰۹:۰۰
    val parts = s.split(Regex("\\s+"))
    if (parts.size == 5 && parts[2] == "*" && parts[3] == "*" && parts[4] == "*") {
        return "هر روز ساعت ${parts[1].padStart(2, '0')}:${parts[0].padStart(2, '0')}"
    }
    // 0 9 * * 1-5  →  روزهای هفته ساعت ۰۹:۰۰
    if (parts.size == 5 && parts[2] == "*" && parts[3] == "*" && parts[4] != "*") {
        return "روزهای هفته ساعت ${parts[1].padStart(2, '0')}:${parts[0].padStart(2, '0')}"
    }
    return s
}

/* ───────────────────────── صفحه ───────────────────────── */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CronScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }

    suspend fun load() {
        loading = true; error = null
        HermesRepo.cronList()
            .onSuccess { r -> items = J.listOf(J.obj(r), "jobs", "items", "cron").map { J.obj(it) } }
            .onFailure { error = it.message }
        loading = false
    }
    LaunchedEffect(Unit) { load() }

    Scaffold(containerColor = Color.Transparent, topBar = {
        TopBar("کارهای زمان‌بندی‌شده", onBack) {
            IconButton(onClick = { adding = true }) { Icon(Icons.Default.Add, "افزودن کار", tint = Gold) }
        }
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 14.dp)) {
            notice?.let { SuccessBanner(it) { notice = null } }
            error?.let { ErrorBanner(it) { scope.launch { load() } } }
            if (loading) { LoadingRow(); return@Column }
            if (items.isEmpty()) {
                EmptyState("هنوز کاری زمان‌بندی نشده.\nبا دکمهٔ «+» یک کار جدید بسازید.", Icons.Default.Schedule)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(
                        items,
                        key = { i, c -> J.str(c, "job_id", J.str(c, "name", "$i")) },
                    ) { _, c -> JobCard(c) {
                        scope.launch { load() }
                    } }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }

    if (adding) AddJobSheet(
        onDismiss = { adding = false },
        onCreated = { msg ->
            adding = false
            notice = msg
            scope.launch { load() }
        },
    )
}

/* ───────────────────────── کارِ یک‌سریِ زمان‌بندی ───────────────────────── */

@Composable
private fun JobCard(c: JsonObject, reload: () -> Unit) {
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    val name = J.str(c, "name", "بدون نام")
    val schedule = J.str(c, "schedule", "—")
    val prompt = J.str(c, "prompt_preview")
    val paused = J.str(c, "state") == "paused" || !J.bool(c, "enabled", true)
    val nextRun = J.str(c, "next_run_at")
    val lastRun = J.str(c, "last_run_at")
    val lastStatus = J.str(c, "last_status")
    val jobId = J.str(c, "job_id", J.str(c, "name"))

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Pill(
                if (paused) "مکث‌شده" else "فعال",
                color = if (paused) Amber else Lime,
                icon = if (paused) Icons.Default.Pause else Icons.Default.PlayArrow,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Schedule, null, Modifier.size(14.dp), tint = Cyan)
            Spacer(Modifier.width(6.dp))
            Text(schedule, style = MaterialTheme.typography.bodySmall, color = Cyan, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
        if (prompt.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(prompt, style = MaterialTheme.typography.bodySmall, color = TextMid, maxLines = 2)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            InfoText("اجرای بعدی", if (paused) "—" else fmtIso(nextRun))
            InfoText("اجرای آخر", if (lastRun.isBlank()) "—" else fmtIso(lastRun))
        }
        if (lastStatus.isNotBlank() && lastStatus != "ok") {
            Spacer(Modifier.height(4.dp))
            Text("آخرین وضعیت: $lastStatus", style = MaterialTheme.typography.labelSmall, color = Rose)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { scope.launch { if (paused) HermesRepo.cronResume(jobId) else HermesRepo.cronPause(jobId); reload() } },
            ) {
                Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, null, Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (paused) "از سرگیری" else "مکث", fontSize = 12.sp)
            }
            Spacer(Modifier.width(4.dp))
            TextButton(
                onClick = { confirmDelete = true },
                colors = ButtonDefaults.textButtonColors(contentColor = Rose),
            ) {
                Icon(Icons.Default.Delete, null, Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("حذف", fontSize = 12.sp)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("حذف کار") },
            text = { Text("آیا از حذف کار «$name» مطمئن هستید؟") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        scope.launch { HermesRepo.cronRemove(jobId); reload() }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Rose),
                ) { Text("حذف") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("انصراف") } },
        )
    }
}

@Composable
private fun InfoText(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextLow, fontSize = 10.sp)
        Text(value, style = MaterialTheme.typography.labelMedium, color = TextMid, fontSize = 11.sp)
    }
}

/* ───────────────────────── مودال افزودن ───────────────────────── */

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AddJobSheet(onDismiss: () -> Unit, onCreated: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(CronKind.EVERY) }
    var name by remember { mutableStateOf("") }
    var count by remember { mutableStateOf("30") }
    var hour by remember { mutableStateOf(false) }
    var hh by remember { mutableStateOf("09") }
    var mm by remember { mutableStateOf("00") }
    var expr by remember { mutableStateOf("0 * * * *") }
    var prompt by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    val schedValid = scheduleValid(kind, count.toIntOrNull() ?: 0, hh, mm, expr)
    val promptValid = prompt.trim().isNotEmpty()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // عنوان
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, null, Modifier.size(20.dp), tint = Gold)
                Spacer(Modifier.width(8.dp))
                Text("کار زمان‌بندی‌شدهٔ جدید", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }

            LabeledField("نام کار", name, { name = it }, placeholder = "مثلاً: خلاصهٔ روزانه")

            // انتخاب نوع زمان‌بندی
            Text("نوع تکرار", style = MaterialTheme.typography.labelMedium, color = TextMid)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CronKind.entries.forEach { k ->
                    FilterChip(
                        selected = kind == k,
                        onClick = { kind = k },
                        label = { Text(k.label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Gold.copy(alpha = 0.2f),
                            selectedLabelColor = Gold,
                        ),
                    )
                }
            }

            // فیلدهای متغیر بر اساس نوع
            when (kind) {
                CronKind.EVERY, CronKind.ONCE -> {
                    Text(
                        if (kind == CronKind.EVERY) "هر چه فاصله تکرار شود" else "هر چند بعد اجرا شود",
                        style = MaterialTheme.typography.labelMedium, color = TextMid,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = count,
                            onValueChange = { count = it.filter { ch -> ch.isDigit() }.take(4) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            shape = RoundedCornerShape(14.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        FilterChip(selected = !hour, onClick = { hour = false }, label = { Text("دقیقه") })
                        Spacer(Modifier.width(6.dp))
                        FilterChip(selected = hour, onClick = { hour = true }, label = { Text("ساعت") })
                    }
                }
                CronKind.DAILY, CronKind.WEEKDAYS -> {
                    Text(
                        if (kind == CronKind.DAILY) "هر روز ساعت" else "روزهای هفته (شنبه تا پنجشنبه) ساعت",
                        style = MaterialTheme.typography.labelMedium, color = TextMid,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = hh,
                            onValueChange = { hh = it.filter { ch -> ch.isDigit() }.take(2) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            shape = RoundedCornerShape(14.dp),
                            label = { Text("ساعت", fontSize = 11.sp) },
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = mm,
                            onValueChange = { mm = it.filter { ch -> ch.isDigit() }.take(2) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            shape = RoundedCornerShape(14.dp),
                            label = { Text("دقیقه", fontSize = 11.sp) },
                        )
                    }
                }
                CronKind.CUSTOM -> {
                    LabeledField(
                        "عبارت cron", expr, { expr = it },
                        placeholder = "0 * * * *",
                        singleLine = false,
                    )
                    Text(
                        "قالب: دقیقه ساعت روز ماه هفته — مثال: «0 9 * * *» یعنی هر روز ساعت ۹",
                        style = MaterialTheme.typography.labelSmall, color = TextLow,
                    )
                }
            }

            LabeledField(
                "دستور به هرمس",
                prompt,
                { prompt = it },
                placeholder = "هر چه می‌خواهی هرمس انجام دهد بنویس…",
                singleLine = false,
            )
            Text(
                "این دستور در نشستی جدا و بدون دیدن گفت‌وگوی فعلی اجرا می‌شود، پس باید کامل و خودکفا باشد.",
                style = MaterialTheme.typography.labelSmall, color = TextLow,
            )

            err?.let {
                Text(it, color = Rose, style = MaterialTheme.typography.bodySmall)
            }

            PrimaryButton(
                text = "ساخت کار",
                onClick = {
                    busy = true; err = null
                    scope.launch {
                        val sched = buildSchedule(kind, count.toIntOrNull() ?: 0, hour, hh, mm, expr)
                        HermesRepo.cronCreate(name.ifBlank { prompt.trim().take(30).ifBlank { "کار" } }, sched, prompt.trim())
                            .onSuccess { r ->
                                busy = false
                                val msg = J.str(J.obj(r), "message").ifBlank { "کار ساخته شد." }
                                onCreated(msg)
                            }
                            .onFailure { e ->
                                err = e.message ?: "خطا در ساخت کار"
                                busy = false
                            }
                    }
                },
                enabled = !busy && schedValid && promptValid,
                icon = Icons.Default.Add,
            )
            if (!schedValid || !promptValid) {
                Text(
                    when {
                        !promptValid -> "یک دستور برای هرمس بنویس."
                        kind == CronKind.CUSTOM -> "عبارت cron باید حداقل ۵ بخش داشته باشد."
                        kind in listOf(CronKind.DAILY, CronKind.WEEKDAYS) -> "ساعت باید ۰ تا ۲۳ و دقیقه ۰ تا ۵۹ باشد."
                        else -> "یک بازهٔ زمانی معتبر وارد کن."
                    },
                    style = MaterialTheme.typography.labelSmall, color = TextLow,
                )
            }
        }
    }
}
