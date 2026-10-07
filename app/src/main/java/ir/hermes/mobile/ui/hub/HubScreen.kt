package ir.hermes.mobile.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import ir.hermes.mobile.core.net.RpcEvent
import ir.hermes.mobile.core.util.Jalali
import ir.hermes.mobile.ui.components.*
import ir.hermes.mobile.ui.theme.*

data class HubItem(val route: String, val title: String, val sub: String, val icon: ImageVector, val color: Color)

@Composable
fun HubScreen(
    status: RpcEvent.State,
    sessionId: String,
    chatTitle: String,
    onGo: (String) -> Unit,
) {
    val items = remember {
        listOf(
            HubItem("chat", "گفت‌وگو", "چت با هرمس", Icons.AutoMirrored.Filled.Chat, Gold),
            HubItem("sessions", "نشست‌ها", "تاریخچه و مدیریت", Icons.Default.Forum, Cyan),
            HubItem("model", "مدل و پرووایدر", "انتخاب و کلید API", Icons.Default.Tune, Violet),
            HubItem("tools", "ابزارها", "فعال/غیرفعال کردن", Icons.Default.Build, Lime),
            HubItem("skills", "مهارت و افزونه", "افزونه‌های هرمس", Icons.Default.Extension, Gold),
            HubItem("cron", "زمان‌بندی", "کارهای خودکار", Icons.Default.Schedule, Cyan),
            HubItem("terminal", "ترمینال", "اجرای دستور شل", Icons.Default.Terminal, Rose),
            HubItem("settings", "تنظیمات", "اتصال و آمار", Icons.Default.Settings, TextMid),
        )
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("هرمس", style = MaterialTheme.typography.headlineMedium, color = TextHi)
                Text(
                    if (sessionId.isBlank()) "بدون نشست فعال" else "نشست ${Jalali.fa(sessionId.take(8))}",
                    style = MaterialTheme.typography.bodySmall, color = TextMid,
                )
            }
            Pill(
                when (status) {
                    RpcEvent.State.CONNECTED -> "متصل"
                    RpcEvent.State.CONNECTING -> "در حال اتصال"
                    RpcEvent.State.CLOSED -> "قطع"
                    RpcEvent.State.FAILED -> "خطا"
                },
                if (status == RpcEvent.State.CONNECTED) Lime else TextLow,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (chatTitle.isBlank()) "به هرمس خوش آمدید" else chatTitle,
            style = MaterialTheme.typography.bodySmall, color = TextLow, maxLines = 1,
        )
        Spacer(Modifier.height(18.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items) { it ->
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { onGo(it.route) }
                        .padding(18.dp),
                ) {
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
                            .background(it.color.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(it.icon, null, Modifier.size(22.dp), tint = it.color) }
                    Spacer(Modifier.height(12.dp))
                    Text(it.title, style = MaterialTheme.typography.titleSmall)
                    Text(it.sub, style = MaterialTheme.typography.bodySmall, color = TextLow, maxLines = 1)
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}
