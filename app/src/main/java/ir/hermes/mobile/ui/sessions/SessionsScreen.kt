package ir.hermes.mobile.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ir.hermes.mobile.core.datastore.ModelSelection
import ir.hermes.mobile.core.util.Jalali
import ir.hermes.mobile.data.HermesRepo
import ir.hermes.mobile.data.J
import ir.hermes.mobile.ui.components.*
import ir.hermes.mobile.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * صفحهٔ نشست‌ها.
 *
 * نکات مهم این نسخه:
 *  - فهرست از کش محلی بلافاصله نشان داده می‌شود و بعد تازه‌سازی می‌شود؛
 *    قبلاً هر بار باید منتظر پاسخ کندِ هرمس می‌ماندیم.
 *  - «نشست جدید» با مدل/پرووایدری که کاربر انتخاب کرده ساخته می‌شود
 *    (override همان نشست)، پس مدل انتخابی واقعاً روی نشست ست می‌شود.
 *  - حذف با دیالوگ تأیید انجام می‌شود. اگر نشست همان نشست فعال باشد، اول
 *    بسته می‌شود چون هرمس حذف نشست زنده را رد می‌کند (cannot delete an active session).
 *  - حذف آخرین نشستِ باقی‌مانده هم مجاز است؛ فهرست خالی می‌شود و کاربر می‌تواند
 *    نشست جدید بسازد (یا در صفحهٔ چت به‌صورت خودکار یکی ساخته می‌شود).
 */
@Composable
fun SessionsScreen(onBack: () -> Unit, onPicked: (id: String, title: String) -> Unit) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<JsonObject?>(null) }
    var pendingDelete by remember { mutableStateOf<JsonObject?>(null) }
    var activeSid by remember { mutableStateOf(HermesRepo.socket.sessionKey) }
    val selection by HermesRepo.settings.selection.collectAsState(initial = ModelSelection())

    suspend fun load() {
        loading = true; error = null
        activeSid = HermesRepo.socket.sessionKey
        HermesRepo.sessions()
            .onSuccess { r -> items = J.listOf(J.obj(r), "sessions", "items").map { J.obj(it) } }
            .onFailure { if (items.isEmpty()) error = it.message }
        loading = false
    }

    // کش محلی: همان لحظه محتوا نشان بده، بعد تازه کن
    LaunchedEffect(Unit) {
        HermesRepo.cachedSessions()?.let { cached ->
            val list = cached.map { J.obj(it) }
            if (list.isNotEmpty()) { items = list; loading = false }
        }
        load()
    }

    fun deleteSession(s: JsonObject) {
        val id = J.str(s, "id", J.str(s, "session_id"))
        if (id.isBlank()) { error = "شناسهٔ نشست خالی است"; return }
        scope.launch {
            // هرمس نشستِ فعال را رد می‌کند؛ اول کامل متوقفش می‌کنیم.
            //
            // نکتهٔ مهم دربارهٔ شناسه‌ها: `session.list` کلیدِ UUID ذخیره‌شده
            // را می‌دهد، ولی رجیستریِ نشست‌های زندهٔ سرور با شناسهٔ ۸ حرفی
            // کلید می‌خورد. `stopSession` این دو را از طریق `session.active_list`
            // حل می‌کند. اگر همین نشستِ فعلی است، آن را کامل رها می‌کنیم تا
            // وضعیت محلی هم پاک شود.
            if (id == HermesRepo.socket.sessionKey) HermesRepo.releaseCurrentSession()
            else HermesRepo.stopSession(id)
            HermesRepo.deleteSession(id).onFailure { error = it.message }
            load()
        }
    }

    Scaffold(containerColor = Color.Transparent,
        topBar = {
            TopBar("نشست‌ها", onBack) {
                IconButton(onClick = { scope.launch { load() } }) {
                    Icon(Icons.Default.Refresh, "تازه‌سازی", tint = TextMid)
                }
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 14.dp)) {
            Spacer(Modifier.height(4.dp))

            // مدلی که نشست‌های جدید با آن ساخته می‌شوند
            GlassCard(Modifier.fillMaxWidth(), borderColor = Accent.copy(alpha = 0.25f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AutoAwesome, null, Modifier.size(16.dp), tint = Accent)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("مدل نشست‌های جدید", style = MaterialTheme.typography.labelMedium, color = TextMid)
                        Text(
                            selection.model.ifBlank { "از پیش‌فرض سرور استفاده می‌شود" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selection.model.isBlank()) TextLow else TextHi,
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            PrimaryButton("نشست جدید", {
                scope.launch {
                    error = null
                    val id = HermesRepo.createSession("گفت‌وگوی جدید")
                    if (id == null) {
                        error = "ساخت نشست ناموفق بود — اتصال را بررسی کنید"
                        load()
                    } else {
                        // مستقیماً وارد چتِ نشستِ جدید شو تا کاربر نتیجه را
                        // ببیند؛ رفتن به فهرست و دوباره کلیک کردن اضافی است.
                        onPicked(id, "گفت‌وگوی جدید")
                    }
                }
            }, Modifier.fillMaxWidth(), icon = Icons.Default.Add)
            Spacer(Modifier.height(12.dp))

            error?.let { ErrorBanner(it) { scope.launch { load() } } }

            when {
                loading && items.isEmpty() -> LoadingRow()
                items.isEmpty() -> EmptyState("نشستی وجود ندارد", Icons.Default.Forum)
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(
                        items,
                        key = { i, s -> i.toString() + ":" + J.str(s, "id", J.str(s, "session_id")) },
                    ) { _, s ->
                        val id = J.str(s, "id", J.str(s, "session_id"))
                        SessionRow(
                            s = s,
                            isActive = id == activeSid,
                            onOpen = {
                                scope.launch {
                                    // هرمس هنگام resume یک نشست زندهٔ تازه با شناسهٔ خودش
                                    // می‌سازد؛ باید همان شناسه برای گفت‌وگو استفاده شود.
                                    // اگر روی نشستِ دیگری غیر از نشست فعالِ فعلی کلیک
                                    // شده، نشستِ فعال رها می‌شود تا resume رد نشود.
                                    // مقایسه با `sessionKey` (UUID) درست است، نه با
                                    // شناسهٔ زندهٔ ۸ حرفی.
                                    if (id != HermesRepo.socket.sessionKey) {
                                        HermesRepo.releaseCurrentSession()
                                    }
                                    val live = HermesRepo.resume(id)
                                    onPicked(live.ifBlank { id }, J.str(s, "title"))
                                }
                            },
                            onRename = { renaming = s },
                            onDelete = { pendingDelete = s },
                        )
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }

    renaming?.let { s ->
        var t by remember { mutableStateOf(J.str(s, "title", "")) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("تغییر عنوان") },
            text = { LabeledField("عنوان", t, { t = it }) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        HermesRepo.setTitle(J.str(s, "id", J.str(s, "session_id")), t)
                        renaming = null
                        load()
                    }
                }) { Text("ذخیره") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("انصراف") } },
        )
    }

    pendingDelete?.let { s ->
        val title = J.str(s, "title").ifBlank { J.str(s, "preview").ifBlank { "بدون عنوان" } }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("حذف نشست") },
            text = {
                Text(
                    "آیا از حذف نشست «$title» مطمئن هستید؟\n" +
                        "تمام پیام‌های این نشست برای همیشه پاک می‌شوند.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    deleteSession(s)
                }) { Text("حذف", color = Rose) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("انصراف") } },
        )
    }
}

@Composable
private fun SessionRow(
    s: JsonObject,
    isActive: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val id = J.str(s, "id", J.str(s, "session_id"))
    val title = J.str(s, "title").ifBlank { J.str(s, "preview").ifBlank { "بدون عنوان" } }
    val ts = J.num(s, "updated_at", J.num(s, "started_at", J.num(s, "created_at", 0.0))).toLong()
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onOpen() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, modifier = Modifier.weight(1f, false))
                if (isActive) { Spacer(Modifier.width(6.dp)); Pill("فعال", Accent) }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                listOfNotNull(
                    id.take(8),
                    if (ts > 0) Jalali.ago(ts * 1000) else null,
                    J.int(s, "message_count", -1).takeIf { it >= 0 }?.let { Jalali.fa(it) + " پیام" },
                ).joinToString(" • "),
                style = MaterialTheme.typography.bodySmall, color = TextLow,
            )
        }
        IconButton(onClick = onRename, Modifier.size(34.dp)) {
            Icon(Icons.Default.Edit, "تغییر نام", Modifier.size(15.dp), tint = TextMid)
        }
        IconButton(onClick = onDelete, Modifier.size(34.dp)) {
            Icon(Icons.Default.Delete, "حذف", Modifier.size(15.dp), tint = Rose)
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(16.dp), tint = TextLow)
    }
}
