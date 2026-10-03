package ir.hermes.mobile.ui.cron

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Schedule
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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

@Composable
fun CronScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }

    suspend fun load() {
        loading = true; error = null
        HermesRepo.cronList()
            .onSuccess { items = J.listOf(J.obj(it), "jobs", "items", "cron").map { o -> J.obj(it) } }
            .onFailure { error = it.message }
        loading = false
    }
    LaunchedEffect(Unit) { load() }

    Scaffold(containerColor = Color.Transparent, topBar = {
        TopBar("کارهای زمان‌بندی‌شده", onBack) {
            IconButton(onClick = { adding = true }) { Icon(Icons.Default.Schedule, "افزودن", tint = Gold) }
        }
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 14.dp)) {
            error?.let { ErrorBanner(it) { scope.launch { load() } } }
            if (loading) { LoadingRow(); return@Column }
            if (items.isEmpty()) {
                EmptyState("کار زمان‌بندی‌شده‌ای وجود ندارد", Icons.Default.Schedule)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(items, key = { J.str(it, "name", J.str(it, "id")) }) { c ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surface).padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(J.str(c, "name", "بدون نام"), style = MaterialTheme.typography.titleSmall)
                                Text(J.str(c, "schedule"), style = MaterialTheme.typography.bodySmall, color = Gold)
                                J.str(c, "prompt").let {
                                    if (it.isNotBlank()) Text(it, style = MaterialTheme.typography.bodySmall, color = TextMid, maxLines = 2)
                                }
                            }
                            IconButton(onClick = {
                                scope.launch { HermesRepo.cronDelete(J.str(c, "name")); load() }
                            }) { Icon(Icons.Default.Delete, "حذف", tint = Rose) }
                        }
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }

    if (adding) {
        var name by remember { mutableStateOf("") }
        var sched by remember { mutableStateOf("0 * * * *") }
        var prompt by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("کار جدید") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LabeledField("نام", name, { name = it })
                    LabeledField("زمان‌بندی (cron)", sched, { sched = it }, placeholder = "0 * * * *")
                    Text("قالب: دقیقه ساعت روز ماه هفته", style = MaterialTheme.typography.labelSmall, color = TextLow)
                    LabeledField("دستور به هرمس", prompt, { prompt = it }, singleLine = false)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        HermesRepo.cronCreate(name.ifBlank { "کار" }, sched, prompt)
                            .onFailure { error = it.message }
                        adding = false; load()
                    }
                }) { Text("ساخت") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("انصراف") } },
        )
    }
}
