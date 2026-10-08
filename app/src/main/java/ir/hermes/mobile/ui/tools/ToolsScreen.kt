package ir.hermes.mobile.ui.tools

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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
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
fun ToolsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var sets by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var sections by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        // ابتدا دادهٔ کش‌شده را فوری نمایش می‌دهیم تا کاربر منتظر سرور
        // نماند؛ سپس در پس‌زمینه تازه‌سازی می‌کنیم.
        val cachedSets = HermesRepo.cachedTools()
        if (cachedSets != null) sets = cachedSets.map { J.obj(it) }
        HermesRepo.cachedToolsShow()?.let { sections = it.map { J.obj(it) } }
        loading = cachedSets == null
        error = null
        // `tools.list` مجموعه‌های ابزار را برمی‌گرداند (با وضعیتِ فعال‌بودن)
        HermesRepo.toolsList()
            .onSuccess { r -> sets = J.listOf(J.obj(r), "toolsets", "tools", "items").map { J.obj(it) } }
            .onFailure { error = it.message }
        // `tools.show` فهرستِ ابزارهای تکیِ فعال را برمی‌گرداند
        HermesRepo.toolsShow()
            .onSuccess { r -> sections = J.listOf(J.obj(r), "sections", "items").map { J.obj(it) } }
            .onFailure { error = it.message }
        loading = false
    }
    LaunchedEffect(Unit) { load() }

    Scaffold(containerColor = Color.Transparent, topBar = {
        TopBar("ابزارها", onBack) {
            IconButton(onClick = { scope.launch { load() } }) { Icon(Icons.Default.Refresh, "تازه‌سازی", tint = TextMid) }
        }
    }) { pad ->
        // همهٔ محتوا در یک LazyColumn واحد قرار می‌گیرد تا هر دو بخش
        // (مجموعه‌ها و ابزارهای فعال) در یک نوارِ اسکرولِ پیوسته باشند.
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            error?.let { item { ErrorBanner(it) { scope.launch { load() } } } }
            if (loading) { item { LoadingRow() }; return@LazyColumn }

            // ── مجموعه‌های ابزار (قابل فعال/غیرفعال کردن) ──────────────────
            item {
                val enabledCount = sets.count { J.bool(it, "enabled", true) }
                SectionTitle("مجموعه‌های ابزار", action = {
                    Pill("${faNum(enabledCount)} از ${faNum(sets.size)} فعال", if (enabledCount > 0) Mint else TextLow)
                })
            }
            if (sets.isEmpty()) {
                item { EmptyState("مجموعه‌ای یافت نشد", Icons.Default.Build) }
            } else {
                itemsIndexed(
                    sets,
                    // کلید باید یکتا باشد؛ اگر نام/شناسه خالی یا تکراری بود،
                    // LazyColumn با خطای «Key was already used» کرش می‌کرد.
                    key = { i, t -> "$i:${J.toolName(t)}" },
                ) { _, t ->
                    val name = J.toolName(t)
                    val on = J.bool(t, "enabled", true)
                    ToolsetRow(
                        name = name.ifBlank { "نامشخص" },
                        desc = J.str(t, "description"),
                        count = J.int(t, "tool_count", J.listOf(t, "tools").size),
                        checked = on,
                        busy = busy == name,
                    ) { enable ->
                        scope.launch {
                            busy = name
                            // پروتکل سرور: action + names[] (نه name/enabled تکی)
                            HermesRepo.toolsConfigure(listOf(name), enable)
                                .onFailure { error = it.message }
                            busy = null
                            load()
                        }
                    }
                }
                item { Spacer(Modifier.height(20.dp)) }
            }

            // ── ابزارهای فعالِ زیرمجموعه ──────────────────────────────────
            if (sections.isNotEmpty()) {
                item {
                    val total = sections.sumOf { J.listOf(it, "tools").size }
                    SectionTitle("ابزارهای فعال", action = {
                        Pill("${faNum(total)} ابزار", Accent)
                    })
                }
                sections.forEach { sec ->
                    val secName = J.str(sec, "name", "سایر")
                    val tools = J.listOf(sec, "tools")
                    item(key = "sec:$secName") {
                        Text(
                            "$secName · ${faNum(tools.size)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextMid,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                        )
                    }
                    items(tools, key = { t -> "tool:$secName:${J.str(J.obj(t), "name")}" }) { toolRaw ->
                        val tool = J.obj(toolRaw)
                        ActiveToolChip(
                            name = J.str(tool, "name"),
                            desc = J.str(tool, "description"),
                        )
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

/**
 * کارتِ یک مجموعهٔ ابزار با سوییچِ فعال‌سازی.
 *
 * مهم: وضعیتِ سوییچ از [checked] می‌آید (منبعِ حقیقت، پاسخِ سرور) و پس از
 * هر بار تغییر، لیست از نو بارگذاری می‌شود. از این رو اگر سرور تغییر را
 * نپذیرفت، سوییچ به‌صورت خودکار به حالتِ قبلی برمی‌گردد.
 */
@Composable
private fun ToolsetRow(
    name: String,
    desc: String,
    count: Int,
    checked: Boolean,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val borderColor by animateColorAsState(
        if (checked) Mint.copy(alpha = 0.35f) else Night4.copy(alpha = 0.6f),
        label = "toolsetBorder",
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
            Icon(
                if (checked) Icons.Default.CheckCircle else Icons.Default.Build,
                null, Modifier.size(18.dp),
                tint = if (checked) Mint else TextLow,
            )
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            AnimatedVisibility(desc.isNotBlank(), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Text(desc, style = MaterialTheme.typography.bodySmall, color = TextMid, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(6.dp))
        Pill("${faNum(count)} ابزار", if (checked) Mint else TextLow)
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

/**
 * یک ابزارِ تکیِ فعال — یک تراشهٔ فشرده با نام و توضیح.
 *
 * این موارد فقط نمایشی هستند (سرور امکانِ فعال/غیرفعال کردنِ تک‌تک
 * ابزارها را ندارد — فقط در سطحِ *مجموعه* قابل کنترل‌اند).
 */
@Composable
private fun ActiveToolChip(name: String, desc: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .border(1.dp, Night4.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.ChevronRight, null, Modifier.size(14.dp), tint = Accent)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (desc.isNotBlank()) {
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMid,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
