package ir.hermes.mobile.ui.skills

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.text.style.TextOverflow
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
    var busy by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        // ابتدا دادهٔ کش‌شده را فوری نمایش می‌دهیم تا کاربر منتظر سرور
        // نماند؛ سپس در پس‌زمینه تازه‌سازی می‌کنیم.
        val cachedS = HermesRepo.cachedSkills()
        val cachedP = HermesRepo.cachedPlugins()
        if (cachedS != null) skills = cachedS.map { J.obj(it) }
        if (cachedP != null) plugins = cachedP.map { J.obj(it) }
        loading = cachedS == null && cachedP == null
        error = null
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
                items(skills, key = { J.toolName(it) + it.hashCode() }) { s ->
                    val name = J.toolName(s)
                    ToggleRow(
                        title = name.ifBlank { "بدون نام" },
                        sub = J.str(s, "description"),
                        checked = J.bool(s, "enabled", true),
                        accent = Lime,
                        busy = busy == "skill:$name",
                    ) { en ->
                        scope.launch {
                            busy = "skill:$name"
                            HermesRepo.toggleSkill(name, en)
                                .onFailure { error = it.message }
                            busy = null
                            load()
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionTitle("افزونه‌ها (${ir.hermes.mobile.core.util.Jalali.fa(plugins.size)})")
            if (plugins.isEmpty()) EmptyState("افزونه‌ای نصب نیست", Icons.Default.Extension) else
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(plugins, key = { J.toolName(it) + it.hashCode() }) { p ->
                    val name = J.toolName(p)
                    ToggleRow(
                        title = name.ifBlank { "بدون نام" },
                        sub = J.str(p, "description", J.str(p, "version")),
                        checked = J.bool(p, "enabled", true),
                        accent = Cyan,
                        busy = busy == "plugin:$name",
                    ) { en ->
                        scope.launch {
                            busy = "plugin:$name"
                            HermesRepo.togglePlugin(name, en)
                                .onFailure { error = it.message }
                            busy = null
                            load()
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

/**
 * ردیفِ مدرنِ مهارت/افزونه با سوییچِ فعال‌سازی.
 *
 * برخلاف [InfoRow]ی قبلی که فقط یک برچسبِ غیرقابلِ تعامل می‌گذاشت، اینجا یک
 * [Switch] واقعی قرار می‌دهیم که `skills.manage`/`plugins.manage` را صدا می‌زند.
 * هنگام درخواست، یک نشانگرِ «در حال انجام» روی سوییچ ظاهر می‌شود تا کاربر
 * بداند عملیات در حال اجراست و دوباره کلیک نکند.
 */
@Composable
private fun ToggleRow(
    title: String,
    sub: String,
    checked: Boolean,
    accent: Color,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    var on by remember(title) { mutableStateOf(checked) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Extension, null, Modifier.size(18.dp), tint = if (on) accent else TextLow)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            AnimatedVisibility(sub.isNotBlank(), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = TextMid, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = accent)
        } else {
            Switch(checked = on, onCheckedChange = { on = it; onToggle(it) })
        }
    }
}
