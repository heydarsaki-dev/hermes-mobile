package ir.hermes.mobile.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ir.hermes.mobile.core.util.Jalali
import ir.hermes.mobile.data.HermesRepo
import ir.hermes.mobile.data.J
import ir.hermes.mobile.ui.components.*
import ir.hermes.mobile.ui.theme.*
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Forum
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

@Composable
fun SessionsScreen(onBack: () -> Unit, onPicked: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<JsonObject?>(null) }

    suspend fun load() {
        loading = true; error = null
        HermesRepo.sessions()
            .onSuccess { r ->
                items = J.listOf(J.obj(r), "sessions", "items").map { J.obj(it) }
            }
            .onFailure { error = it.message }
        loading = false
    }
    LaunchedEffect(Unit) { load() }

    Scaffold(containerColor = Color.Transparent,
        topBar = { TopBar("نشست‌ها", onBack) {
            IconButton(onClick = { scope.launch { load() } }) { Icon(Icons.Default.Refresh, "تازه‌سازی", tint = TextMid) }
        } }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 14.dp)) {
            Spacer(Modifier.height(4.dp))
            PrimaryButton("نشست جدید", {
                scope.launch {
                    HermesRepo.newSession("گفت‌وگوی جدید")
                        .onSuccess { load() }
                        .onFailure { error = it.message }
                }
            }, Modifier.fillMaxWidth(), icon = Icons.Default.Add)
            Spacer(Modifier.height(12.dp))
            error?.let { ErrorBanner(it) { scope.launch { load() } } }
            when {
                loading -> LoadingRow()
                items.isEmpty() -> EmptyState("نشستی وجود ندارد", Icons.Default.Forum)
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // کلید یکتا: بعضی پاسخ‌ها فقط session_id دارند و id خالی می‌ماند
                    itemsIndexed(
                        items,
                        key = { i, s -> "$i:${J.str(s, "id", J.str(s, "session_id"))}" },
                    ) { _, s ->
                        SessionRow(
                            s,
                            onOpen = {
                                val id = J.str(s, "id", J.str(s, "session_id"))
                                scope.launch { HermesRepo.resume(id); onPicked(id) }
                            },
                            onRename = { renaming = s },
                            onDelete = {
                                scope.launch { HermesRepo.deleteSession(J.str(s, "id")); load() }
                            },
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
                    scope.launch { HermesRepo.setTitle(J.str(s, "id"), t); renaming = null; load() }
                }) { Text("ذخیره") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("انصراف") } },
        )
    }
}

@Composable
private fun SessionRow(s: JsonObject, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val id = J.str(s, "id", J.str(s, "session_id"))
    val title = J.str(s, "title").ifBlank { "بدون عنوان" }
    val ts = J.num(s, "updated_at", J.num(s, "created_at", 0.0)).toLong()
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onOpen() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            Text(
                listOfNotNull(
                    id.take(8),
                    if (ts > 0) Jalali.ago(ts * 1000) else null,
                    J.int(s, "message_count", -1).takeIf { it >= 0 }?.let { "${Jalali.fa(it)} پیام" }
                ).joinToString(" • "),
                style = MaterialTheme.typography.bodySmall, color = TextLow,
            )
        }
        IconButton(onClick = onRename, Modifier.size(34.dp)) { Icon(Icons.Default.Edit, "تغییر نام", Modifier.size(15.dp), tint = TextMid) }
        IconButton(onClick = onDelete, Modifier.size(34.dp)) { Icon(Icons.Default.Delete, "حذف", Modifier.size(15.dp), tint = Rose) }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(16.dp), tint = TextLow)
    }
}
