package ir.hermes.mobile.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ir.hermes.mobile.core.util.CrashLogger
import ir.hermes.mobile.core.util.Jalali
import ir.hermes.mobile.data.HermesRepo
import ir.hermes.mobile.data.J
import ir.hermes.mobile.ui.components.*
import ir.hermes.mobile.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun SettingsScreen(onBack: () -> Unit, onLoggedOut: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var url by remember { mutableStateOf(HermesRepo.api.baseUrl) }
    var token by remember { mutableStateOf(HermesRepo.api.token) }
    var usage by remember { mutableStateOf<JsonObject?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var serverLog by remember { mutableStateOf<String?>(null) }
    var logBusy by remember { mutableStateOf(false) }

    // گزارش‌های کرش از حافظهٔ اپ خوانده می‌شوند؛ با هر تغییر نسخهٔ آن دوباره
    // خوانده می‌شوند تا بعد از «پاک کردن» فهرست به‌روز شود.
    var crashRev by remember { mutableStateOf(0) }
    val crashCount = remember(crashRev) { CrashLogger.count() }
    val crashText = remember(crashRev) { if (crashCount > 0) CrashLogger.all() else "" }

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

            // ------------------------------------------------------------------
            // گزارش خطا: اگر اپ کرش کرد، استک‌تریس همین‌جا ذخیره شده است.
            // ------------------------------------------------------------------
            Spacer(Modifier.height(18.dp))
            SectionTitle("گزارش خطا")
            GlassCard(Modifier.fillMaxWidth(), borderColor = Amber.copy(alpha = 0.3f)) {
                if (crashCount == 0) {
                    Text(
                        "هیچ کرشی ثبت نشده است.",
                        style = MaterialTheme.typography.bodySmall, color = Lime
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "اگر اپ بسته شد، دوباره بازش کنید و به همین بخش برگردید تا گزارش اینجا باشد.",
                        style = MaterialTheme.typography.bodySmall, color = TextLow
                    )
                } else {
                    Text(
                        "${Jalali.fa(crashCount)} گزارش ثبت شده — برای ارسال به توسعه‌دهنده «کپی» یا «اشتراک‌گذاری» کنید.",
                        style = MaterialTheme.typography.bodySmall, color = Amber
                    )
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier.fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Ink0)
                            .padding(10.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            crashText,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = TextMid,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton(
                            "کپی گزارش",
                            {
                                clipboard.setText(AnnotatedString(crashText))
                                msg = "گزارش کرش کپی شد"
                            },
                            Modifier.weight(1f),
                            icon = Icons.Default.ContentCopy,
                        )
                        GhostButton(
                            "اشتراک‌گذاری",
                            { shareCrash(ctx, crashText) },
                            Modifier.weight(1f),
                            color = Cyan,
                            icon = Icons.Default.Share,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    GhostButton(
                        "پاک کردن گزارش‌ها",
                        { CrashLogger.clear(); crashRev++ },
                        Modifier.fillMaxWidth(),
                        color = Rose,
                        icon = Icons.Default.Delete,
                    )
                }
            }

            // ------------------------------------------------------------------
            // لاگ سرور: برای دیدن دلیل خطاهای سمت هرمس (مثل agent initialization
            // timed out). از shell.exec استفاده می‌کند و آخرین خطوط لاگ را می‌خواند.
            // ------------------------------------------------------------------
            Spacer(Modifier.height(18.dp))
            SectionTitle("لاگ سرور هرمس")
            GlassCard(Modifier.fillMaxWidth(), borderColor = Cyan.copy(alpha = 0.25f)) {
                Text(
                    "اگر خطایی مثل «Agent initialization timed out» دیدید، اینجا آخرین خطوط لاگ هرمس را ببینید (دلیل واقعی همان‌جاست).",
                    style = MaterialTheme.typography.bodySmall, color = TextLow,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(
                        if (logBusy) "در حال خواندن…" else "خواندن لاگ",
                        {
                            scope.launch {
                                logBusy = true
                                val cmd = "for f in ~/.hermes/logs/errors.log ~/.hermes/logs/agent.log " +
                                    "~/.hermes/logs/gateway.log; do echo \"=== \$f ===\"; tail -n 150 \"\$f\" 2>/dev/null; done"
                                HermesRepo.shell(cmd)
                                    .onSuccess {
                                        val o = J.obj(it)
                                        val out = J.str(o, "stdout")
                                        val err = J.str(o, "stderr")
                                        serverLog = (out + if (err.isNotBlank()) "\n--- stderr ---\n" + err else "").trim()
                                    }
                                    .onFailure { serverLog = it.message }
                                logBusy = false
                            }
                        },
                        Modifier.weight(1f),
                        enabled = !logBusy,
                        icon = Icons.Default.Terminal,
                    )
                    val logText = serverLog
                    if (!logText.isNullOrBlank()) {
                        GhostButton(
                            "کپی",
                            { clipboard.setText(AnnotatedString(logText)); msg = "لاگ سرور کپی شد" },
                            Modifier.weight(1f),
                            color = Cyan,
                            icon = Icons.Default.ContentCopy,
                        )
                    }
                }
                serverLog?.let { log ->
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier.fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Ink0)
                            .padding(10.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            log.ifBlank { "(لاگ خالی است — ممکن است فایلی موجود نباشد)" },
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = TextMid,
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            SectionTitle("درباره")
            GlassCard(Modifier.fillMaxWidth()) {
                LabeledValue("نام", "هرمس — کلاینت اندروید")
                LabeledValue("نسخه", CrashLogger.appVersion(ctx))
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
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Rose),
                border = androidx.compose.foundation.BorderStroke(1.dp, Rose.copy(alpha = 0.5f)),
            ) { Text("خروج از حساب") }

            msg?.let { Spacer(Modifier.height(12.dp)); Pill(it, Lime) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** اشتراک‌گذاری متن گزارش با هر اپی که کاربر انتخاب کند (تلگرام، ایمیل، …) */
private fun shareCrash(ctx: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Hermes Mobile — crash report")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching { ctx.startActivity(Intent.createChooser(intent, "اشتراک‌گذاری گزارش")) }
}
