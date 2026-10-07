package ir.hermes.mobile.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

private val DarkScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Night0,
    primaryContainer = AccentDim,
    onPrimaryContainer = TextHi,
    secondary = Mint,
    onSecondary = Night0,
    tertiary = Lilac,
    onTertiary = Night0,
    background = Night0,
    onBackground = TextHi,
    surface = Night1,
    onSurface = TextHi,
    surfaceVariant = Night3,
    onSurfaceVariant = TextMid,
    surfaceDim = Night0,
    surfaceBright = Night4,
    surfaceContainerLowest = Night0,
    surfaceContainerLow = Night1,
    surfaceContainer = Night1,
    surfaceContainerHigh = Night2,
    surfaceContainerHighest = Night3,
    outline = Night4,
    outlineVariant = Night2,
    scrim = Night0,
    error = Blush,
    onError = Night0,
)

private val LightScheme = lightColorScheme(
    primary = AccentDim,
    onPrimary = Color.White,
    secondary = Color(0xFF1F8E86),
    tertiary = Color(0xFF6B4FD1),
    background = Color(0xFFF6F7FB),
    onBackground = Night1,
    surface = Color.White,
    onSurface = Night1,
    surfaceVariant = Color(0xFFE9ECF5),
    onSurfaceVariant = Color(0xFF4A5265),
    outline = Color(0xFFC7CCDB),
)

@Composable
fun HermesTheme(
    // این اپ عمداً دارک طراحی شده و همه‌ی پس‌زمینه‌ها با سطوح تیرهٔ Night
    // ساخته شده‌اند (گرادیان‌ها، کارت‌های شیشه‌ای و ترمینال). اگر از حالت
    // سیستم تبعیت کنیم، وقتی گوشی در حالت روشن باشد اسکیمای روشن انتخاب
    // می‌شود؛ آن‌وقت رنگ متن‌ها تیره می‌شود و روی پس‌زمینهٔ تیره کاملاً
    // ناپدید می‌شوند. پس تم تیره را همیشه اعمال می‌کنیم.
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
