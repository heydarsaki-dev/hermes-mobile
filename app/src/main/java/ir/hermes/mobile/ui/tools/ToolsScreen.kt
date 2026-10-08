package ir.hermes.mobile.ui.tools

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Workspaces
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
fun ToolsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var tools by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var sets by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        // ابتدا دادهٔ کش‌شده را فوری نمایش می‌دهیم تا کاربر منتظر سرور
        // نماند؛ سپس در پس‌زمینه تازه‌سازی می‌کنیم.
        val fromCache = HermesRepo.cachedTools()
        if (fromCache != null) tools = fromCache.map { J.obj(it) }
        HermesRepo.cachedToolSets()?.let { sets = it.map { J.obj(it) } }
        loading = fromCache == null
        error = null
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
                    items(sets, key = { J.toolName(it) + it.hashCode() }) { s ->
                        val name = J.toolName(s)
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Workspaces, null, Modifier.size(16.dp), tint = Cyan)
                            Spacer(Modifier.width(8.dp))
                            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Pill("${faNum(J.listOf(s, "tools").size)} ابزار", Cyan)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            val enabled = tools.count { J.bool(it, "enabled", !J.bool(it, "disabled", false)) }
            SectionTitle("ابزارهای در دسترس", action = {
                Pill("${faNum(enabled)} از ${faNum(tools.size)} فعال", if (enabled > 0) Lime else TextLow)
            })
            if (tools.isEmpty()) {
                EmptyState("ابزاری یافت نشد", Icons.Default.Build)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(
                        tools,
                        // کلید باید یکتا باشد؛ اگر نام/شناسه ابزار خالی یا تکراری بود،
                        // LazyColumn با خطای «Key was already used» کرش می‌کرد.
                        key = { i, t -> "$i:${J.toolName(t)}" },
                    ) { _, t ->
                        val name = J.toolName(t)
                        ToolRow(t, busy == name) { en ->
                            scope.launch {
                                busy = name
                                HermesRepo.toolsConfigure(name, en)
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
}

/**
 * کارتِ ابزار با سوییچِ فعال‌سازی.
 *
 * طراحیِ جدید: یک دایرهٔ کوچک با آیکونِ وضعیت (سبزِ فعال/خاکستریِ غیرفعال)،
 * نام و توضیح، و یک [Switch] در سمتِ چپ. هنگام ثبتِ تغییر، سوییچ با یک
 * [CircularProgressIndicator] جایگزین می‌شود تا کاربر ببیند عملیات در حال
 * اجراست و دوباره لمس نکند.
 */
@Composable
private fun ToolRow(t: JsonObject, busy: Boolean, onToggle: (Boolean) -> Unit) {
    var on by remember(t) { mutableStateOf(J.bool(t, "enabled", !J.bool(t, "disabled", false))) }
    val name = J.toolName(t).ifBlank { "ابزار بدون نام" }
    val desc = J.str(t, "description", J.str(t, "summary"))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, if (on) Lime.copy(alpha = .35f) else TextLow.copy(alpha = .25f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape)
                .background(if (on) Lime.copy(alpha = .15f) else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (on) Icons.Default.CheckCircle else Icons.Default.Build,
                null, Modifier.size(17.dp),
                tint = if (on) Lime else TextLow,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            AnimatedVisibility(desc.isNotBlank(), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Text(desc, style = MaterialTheme.typography.bodySmall, color = TextMid, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Lime)
        } else {
            Switch(checked = on, onCheckedChange = { on = it; onToggle(it) })
        }
    }
}
