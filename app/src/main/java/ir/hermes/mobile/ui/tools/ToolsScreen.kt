package ir.hermes.mobile.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ir.hermes.mobile.data.HermesRepo
import ir.hermes.mobile.data.J
import ir.hermes.mobile.ui.components.*
import ir.hermes.mobile.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

@Composable
fun ToolsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var tools by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var sets by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        loading = true; error = null
        HermesRepo.toolsList()
            .onSuccess { r -> tools = J.listOf(J.obj(r), "tools", "items").map { J.obj(it) } }
            .onFailure { error = it.message }
        HermesRepo.toolSets()
            .onSuccess { r -> sets = J.listOf(J.obj(r), "toolsets", "items").map { J.obj(it) } }
            .onFailure { error = it.message }
        loading = false
    }
    LaunchedEffect(Unit) { load() }

    Scaffold(containerColor = Color.Transparent, topBar = {
        TopBar("ابزارها", onBack) {
            IconButton(onClick = { scope.launch { load() } }) { Icon(Icons.Default.Refresh, "تازه‌سازی", tint = TextMid) }
        }
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 14.dp)) {
            error?.let { ErrorBanner(it) { scope.launch { load() } } }
            if (loading) { LoadingRow(); return@Column }

            if (sets.isNotEmpty()) {
                SectionTitle("مجموعه‌های ابزار")
                LazyColumn(Modifier.heightIn(max = 160.dp)) {
                    items(sets) { s ->
                        val name = J.str(s, "id", J.str(s, "name"))
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Workspaces, null, Modifier.size(16.dp), tint = Cyan)
                            Spacer(Modifier.width(8.dp))
                            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Pill("${Jalali_count(J.listOf(s, "tools").size)} ابزار", Cyan)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            SectionTitle("ابزارهای در دسترس")
            if (tools.isEmpty()) {
                EmptyState("ابزاری برگردانده نشد", Icons.Default.Build)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(
                        tools,
                        // کلید باید یکتا باشد؛ اگر نام/شناسه ابزار خالی یا تکراری بود،
                        // LazyColumn با خطای «Key was already used» کرش می‌کرد.
                        key = { i, t -> "$i:${J.str(t, "name", J.str(t, "id"))}" },
                    ) { _, t ->
                        ToolRow(t) { enabled ->
                            scope.launch {
                                HermesRepo.toolsConfigure(
                                    J.str(t, "name", J.str(t, "id")), enabled
                                ).onFailure { error = it.message }
                                load()
                            }
                        }
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

private fun Jalali_count(n: Int) = ir.hermes.mobile.core.util.Jalali.fa(n)

@Composable
private fun ToolRow(t: JsonObject, onToggle: (Boolean) -> Unit) {
    var on by remember(t) { mutableStateOf(J.bool(t, "enabled", !J.bool(t, "disabled", false))) }
    val name = J.str(t, "name", J.str(t, "id"))
    val desc = J.str(t, "description", J.str(t, "summary"))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            if (desc.isNotBlank()) {
                Text(desc, style = MaterialTheme.typography.bodySmall, color = TextMid, maxLines = 2)
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = on, onCheckedChange = { on = it; onToggle(it) })
    }
}
