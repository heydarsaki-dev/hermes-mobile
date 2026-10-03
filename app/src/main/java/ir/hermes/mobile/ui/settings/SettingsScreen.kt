package ir.hermes.mobile.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ir.hermes.mobile.core.util.Jalali
import ir.hermes.mobile.data.HermesRepo
import ir.hermes.mobile.data.J
import ir.hermes.mobile.ui.components.*
import ir.hermes.mobile.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun SettingsScreen(onBack: () -> Unit, onLoggedOut: () -> Unit) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(HermesRepo.api.baseUrl) }
    var token by remember { mutableStateOf(HermesRepo.api.token) }
    var usage by remember { mutableStateOf<JsonObject?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { HermesRepo.usage().onSuccess { usage = J.obj(it) } }

    Scaffold(containerColor = Color.Transparent, topBar = { TopBar("تنظیمات", onBack) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(14.dp).verticalScroll(rememberScrollState())
        ) {
            SectionTitle("اتصال")
            GlassCard(Modifier.fillMaxWidth()) {
                LabeledField("آدرس سرور", url, { url = it })
                Spacer(Modifier.height(12.dp))
                LabeledField("توکن", token, { token = it }, visualTransformation = PasswordVisualTransformation())
                Spacer(Modifier.height(16.dp))
                PrimaryButton("ذخیره و اتصال مجدد", {
                    scope.launch {
                        HermesRepo.settings.save(url, token)
                        HermesRepo.applyConfig(ir.hermes.mobile.core.datastore.ServerConfig(url, token))
                        HermesRepo.socket.connect()
                        msg = "تنظیمات ذخیره و اتصال برقرار شد"
                    }
                }, Modifier.fillMaxWidth(), icon = Icons.Default.Save)
            }

            Spacer(Modifier.height(18.dp))
            SectionTitle("مصرف منابع")
            GlassCard(Modifier.fillMaxWidth()) {
                val u = usage
                if (u == null) {
                    Text("در حال خواندن آمار…", style = MaterialTheme.typography.bodySmall, color = TextMid)
                } else {
                    if (u.isEmpty()) Text("آماری در دسترس نیست", style = MaterialTheme.typography.bodySmall, color = TextMid)
                    u.forEach { (k, v) ->
                        val sv = runCatching { v.jsonPrimitive.content }.getOrNull() ?: v.toString()
                        LabeledValue(k, Jalali.fa(sv))
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            SectionTitle("درباره")
            GlassCard(Modifier.fillMaxWidth()) {
                LabeledValue("نام", "هرمس — کلاینت اندروید")
                LabeledValue("نسخه", "1.0.0")
                LabeledValue("پروتکل", "JSON-RPC 2.0 روی /api/ws")
                LabeledValue("تاریخ", Jalali.format())
            }

            Spacer(Modifier.height(18.dp))
            OutlinedButton(
                onClick = {
                    scope.launch {
                        HermesRepo.settings.clear()
                        HermesRepo.socket.disconnect()
                        onLoggedOut()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Rose),
                border = androidx.compose.foundation.BorderStroke(1.dp, Rose.copy(alpha = 0.5f)),
            ) { Text("خروج از حساب") }

            msg?.let { Spacer(Modifier.height(12.dp)); Pill(it, Lime) }
            Spacer(Modifier.height(24.dp))
        }
    }
}
