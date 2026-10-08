package ir.hermes.mobile.ui.skills

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
    var skills by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var plugins by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        // ابتدا دادهٔ کش‌شده را فوری نمایش می‌دهیم تا کاربر منتظر سرور
        // نماند؛ سپس در پس‌زمینه تازه‌سازی می‌کنیم.
        val cachedS = HermesRepo.cachedSkills()
        if (cachedS != null) skills = J.flattenSkills(cachedS)
        val cachedP = HermesRepo.cachedPlugins()
        if (cachedP != null) plugins = cachedP.map { J.obj(it) }
        loading = cachedS == null && cachedP == null
        error = null
        // `skills.manage` یک دیکشنری «دسته → فهرستِ نام‌ها» برمی‌گرداند.
        HermesRepo.skills()
            .onSuccess { r -> skills = J.flattenSkills(J.obj(r)["skills"]) }
            .onFailure { error = it.message }
        HermesRepo.plugins()
            .onSuccess { r -> plugins = J.listOf(J.obj(r), "plugins", "items").map { J.obj(it) } }
            .onFailure { error = it.message }
        loading = false
    }
    LaunchedEffect(Unit) { load() }

    Scaffold(containerColor = Color.Transparent, topBar = {
        TopBar("مهارت‌ها و افزونه‌ها", onBack) {
            IconButton(onClick = { scope.launch { load() } }) { Icon(Icons.Default.Refresh, "تازه‌سازی", tint = TextMid) }
        }
    }) { pad ->
        // همهٔ محتوا در یک LazyColumn واحد قرار می‌گیرد تا مهارت‌ها و
        // افزونه‌ها در یک نوارِ اسکرولِ پیوسته باشند.
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            error?.let { item { ErrorBanner(it) { scope.launch { load() } } } }
            if (loading) { item { LoadingRow() }; return@LazyColumn }

            // ── مهارت‌ها ─────────────────────────────────────────────────
            // توجه: سرور امکانِ فعال/غیرفعال کردنِ تک‌تک مهارت‌ها را از طریق
            // RPC نمی‌دهد؛ `skills.manage` فقط list/search/install/browse/inspect
            // دارد. به همین دلیل مهارت‌ها صرفاً نمایش داده می‌شوند.
            item {
                SectionTitle("مهارت‌ها", action = {
                    Pill("${faNum(skills.size)} فعال", Accent)
                })
            }
            if (skills.isEmpty()) {
                item { EmptyState("مهارتی ثبت نشده", Icons.Default.Extension) }
            } else {
                val byCategory = skills.groupBy({ it.first }, { it.second })
                byCategory.forEach { (cat, names) ->
                    item(key = "cat:$cat") {
                        Text(
                            "$cat · ${faNum(names.size)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextMid,
                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                        )
                    }
                    items(names.sorted(), key = { "skill:$cat:$it" }) { name ->
                        SkillChip(name)
                    }
                }
            }

            item { Spacer(Modifier.height(18.dp)) }

            // ── افزونه‌ها (با سوییچِ فعال‌سازی واقعی) ─────────────────────
            item {
                val enabledCount = plugins.count { J.str(it, "status") == "enabled" }
                SectionTitle("افزونه‌ها", action = {
                    Pill("${faNum(enabledCount)} از ${faNum(plugins.size)} فعال", if (enabledCount > 0) Mint else TextLow)
                })
            }
            if (plugins.isEmpty()) {
                item { EmptyState("افزونه‌ای نصب نیست", Icons.Default.Extension) }
            } else {
                itemsIndexed(
                    plugins,
                    key = { i, p -> "$i:${J.toolName(p)}" },
                ) { _, p ->
                    val name = J.toolName(p)
                    // `plugins.manage` وضعیت را در فیلد `status` می‌فرستد:
                    // "enabled" / "disabled" / "not enabled".
                    PluginRow(
                        name = name.ifBlank { "افزونهٔ بدون نام" },
                        desc = J.str(p, "description"),
                        version = J.str(p, "version"),
                        source = J.str(p, "source"),
                        checked = J.str(p, "status") == "enabled",
                        busy = busy == name,
                    ) { enable ->
                        scope.launch {
                            busy = name
                            // پروتکل سرور: {"action":"toggle","name":"...","enable":bool}
                            HermesRepo.togglePlugin(name, enable)
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
 * تراشهٔ یک مهارت. فقط نمایشی است (toggle ممکن نیست).
 */
@Composable
private fun SkillChip(name: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .border(1.dp, Night4.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Check, null, Modifier.size(13.dp), tint = Accent)
        Spacer(Modifier.width(8.dp))
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * کارتِ یک افزونه با سوییچِ فعال‌سازی.
 *
 * وضعیتِ سوییچ از پاسخِ سرور (فیلد `status`) می‌آید و پس از هر تغییر لیست
 * از نو بارگذاری می‌شود؛ اگر سرور تغییری را نپذیرفت، سوییچ برمی‌گردد.
 */
@Composable
private fun PluginRow(
    name: String,
    desc: String,
    version: String,
    source: String,
    checked: Boolean,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val borderColor by animateColorAsState(
        if (checked) Mint.copy(alpha = 0.35f) else Night4.copy(alpha = 0.6f),
        label = "pluginBorder",
    )
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                .background(if (checked) Mint.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Extension, null, Modifier.size(18.dp), tint = if (checked) Mint else TextLow)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // badge منبع: «داخلی» برای افزونه‌های همراه، وگرنه «کاربر»
                if (source.isNotBlank()) {
                    Spacer(Modifier.width(7.dp))
                    Pill(
                        if (source == "bundled") "داخلی" else "کاربر",
                        if (source == "bundled") Accent else Lilac,
                    )
                }
            }
            AnimatedVisibility(desc.isNotBlank(), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Text(
                    desc.ifBlank { version }.ifBlank { "—" },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMid,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp, color = Mint)
        } else {
            Switch(
                checked = checked,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Night0,
                    checkedTrackColor = Mint,
                    uncheckedThumbColor = TextLow,
                    uncheckedTrackColor = Night4,
                ),
            )
        }
    }
}
