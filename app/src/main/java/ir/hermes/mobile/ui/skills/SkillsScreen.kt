package ir.hermes.mobile.ui.skills

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
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
fun SkillsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var skills by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var plugins by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        loading = true; error = null
        HermesRepo.skills().onSuccess { r -> skills = J.listOf(J.obj(r), "skills", "items").map { J.obj(it) } }
            .onFailure { error = it.message }
        HermesRepo.plugins().onSuccess { r -> plugins = J.listOf(J.obj(r), "plugins", "items").map { J.obj(it) } }
        loading = false
    }
    LaunchedEffect(Unit) { load() }

    Scaffold(containerColor = Color.Transparent, topBar = {
        TopBar("مهارت‌ها و افزونه‌ها", onBack) {
            IconButton(onClick = { scope.launch { load() } }) { Icon(Icons.Default.Refresh, "تازه‌سازی", tint = TextMid) }
        }
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 14.dp)) {
            error?.let { ErrorBanner(it) { scope.launch { load() } } }
            if (loading) { LoadingRow(); return@Column }

            SectionTitle("مهارت‌ها (${ir.hermes.mobile.core.util.Jalali.fa(skills.size)})")
            if (skills.isEmpty()) EmptyState("مهارتی ثبت نشده", Icons.Default.Extension) else
            LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(skills) { s -> InfoRow(
                    J.str(s, "name", J.str(s, "id")),
                    J.str(s, "description"),
                    if (J.bool(s, "enabled", true)) "فعال" else "غیرفعال",
                    if (J.bool(s, "enabled", true)) Lime else TextLow,
                ) }
            }

            Spacer(Modifier.height(16.dp))
            SectionTitle("افزونه‌ها (${ir.hermes.mobile.core.util.Jalali.fa(plugins.size)})")
            if (plugins.isEmpty()) EmptyState("افزونه‌ای نصب نیست", Icons.Default.Extension) else
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(plugins) { p -> InfoRow(
                    J.str(p, "name", J.str(p, "id")),
                    J.str(p, "description", J.str(p, "version")),
                    if (J.bool(p, "enabled", true)) "فعال" else "غیرفعال",
                    if (J.bool(p, "enabled", true)) Cyan else TextLow,
                ) }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun InfoRow(title: String, sub: String, badge: String, badgeColor: Color) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = TextMid, maxLines = 2)
        }
        Spacer(Modifier.width(8.dp))
        Pill(badge, badgeColor)
    }
}
