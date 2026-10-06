package ir.hermes.mobile.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

private val DarkScheme = darkColorScheme(
    primary = Gold,
    onPrimary = Ink0,
    primaryContainer = GoldDim,
    onPrimaryContainer = Ink0,
    secondary = Cyan,
    onSecondary = Ink0,
    tertiary = Violet,
    onTertiary = Ink0,
    background = Ink0,
    onBackground = TextHi,
    surface = Ink1,
    onSurface = TextHi,
    surfaceVariant = Ink3,
    onSurfaceVariant = TextMid,
    outline = Ink4,
    outlineVariant = Ink2,
    error = Rose,
    onError = Ink0,
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF8A6D00),
    onPrimary = Color.White,
    secondary = Color(0xFF00857E),
    tertiary = Color(0xFF6244C4),
    background = Color(0xFFF7F8FA),
    onBackground = Ink1,
    surface = Color.White,
    onSurface = Ink1,
    surfaceVariant = Color(0xFFEDEEF2),
    onSurfaceVariant = Color(0xFF4A5160),
)

@Composable
fun HermesTheme(
    // این اپ عمداً دارک طراحی شده و همه‌ی پس‌زمینه‌ها با رنگ‌های تیرهٔ Ink
    // ساخته شده‌اند (گرادیان‌ها، کارت‌های شیشه‌ای و ترمینال). اگر از حالت
    // سیستم تبعیت کنیم، وقتی گوشی در حالت روشن باشد اسکیمای روشن انتخاب
    // می‌شود؛ آن‌وقت رنگ متن‌ها تیره (Ink1) می‌شود و روی پس‌زمینهٔ تیره
    // کاملاً ناپدید می‌شوند. پس تم تیره را همیشه اعمال می‌کنیم.
    dark: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scheme = if (dark) DarkScheme else LightScheme
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val w = view.context as? android.app.Activity ?: return@SideEffect
            // Color.value یک ULong است؛ تبدیل مستقیم با toInt() رنگ را خراب می‌کند.
            // استفاده از toArgb() تنها راه امن برای ست کردن رنگ سیستم‌بار است.
            runCatching {
                w.window.statusBarColor = scheme.background.toArgb()
                w.window.navigationBarColor = scheme.background.toArgb()
            }
        }
    }
    // در Material3 مقدار پیش‌فرض LocalContentColor مشکی است و خودِ MaterialTheme
    // آن را تنظیم نمی‌کند. چون Scaffoldها شفاف هستند، رنگ محتوا مشکی می‌ماند و
    // متن‌هایی که رنگ صریح ندارند (مثل متن پیام‌ها در چت و عنوان کارت‌ها)
    // روی پس‌زمینهٔ تیره ناپدید می‌شوند. اینجا رنگ محتوای پیش‌فرض را
    // روی onBackground تنظیم می‌کنیم تا همه‌چیز خوانا باشد.
    CompositionLocalProvider(LocalContentColor provides scheme.onBackground) {
        MaterialTheme(
            colorScheme = scheme,
            typography = HermesTypography,
            content = content,
        )
    }
}
