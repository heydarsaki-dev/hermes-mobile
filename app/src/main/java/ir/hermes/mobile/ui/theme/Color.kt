package ir.hermes.mobile.ui.theme

import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────────────────────
// پالت «شب قطبی» — تم تیرهٔ مدرن
//
// پس‌زمینهٔ سرمهٔ عمیق با لهجهٔ نیلیِ روشن و فیروزه‌ای/نعنایی. سطوح عمداً
// کم‌اشباع‌اند تا روی نمایشگرهای OLED آرام و خوانا بمانند و لهجه‌ها فقط برای
// دکمه‌ها، وضعیت‌ها و نمودارها استفاده شوند.
//
// نام‌های قدیمی (Ink*/Gold/Cyan/…) به‌عنوان نام مستعار پایین نگه داشته شده‌اند
// تا صفحه‌هایی که از آن‌ها استفاده می‌کنند بدون تغییر ظاهریِ درست کار کنند.
// کد تازه باید از نام‌های معنادار (Night*/Accent/Mint/…) استفاده کند.
// ─────────────────────────────────────────────────────────────────────────────

// سطوح، از عمیق‌ترین به روشن‌ترین
val Night0 = Color(0xFF070A12) // بوم اصلی
val Night1 = Color(0xFF0C111C) // کارت‌ها و نوارها
val Night2 = Color(0xFF131A28) // کارت‌های تودرتو
val Night3 = Color(0xFF1B2436) // سطح فیلدهای ورودی
val Night4 = Color(0xFF26334A) // حاشیه‌ها

// لهجه‌ها
val Accent = Color(0xFF7C8CFF) // نیلیِ روشن — لهجهٔ اصلی
val AccentDim = Color(0xFF4E5BD6)
val Mint = Color(0xFF4FD1C5) // فیروزه‌ای
val Lilac = Color(0xFFB388FF) // بنفش ملایم
val Blush = Color(0xFFFF6B8A) // صورتیِ هشدار
val Spring = Color(0xFF5BE49B) // سبز نعنایی موفقیت
val Sand = Color(0xFFFFC46B) // کهربایی ملایم

// متن
val TextHi = Color(0xFFEEF1F8)
val TextMid = Color(0xFFA7B0C6)
// متن کم‌اهمیت/راهنما — روی سطوح تیره و کارت‌های نیمه‌شفاف هم خوانا بماند
val TextLow = Color(0xFF7B87A1)

// ── نام‌های مستعار سازگار با کد موجود ──
val Ink0 = Night0
val Ink1 = Night1
val Ink2 = Night2
val Ink3 = Night3
val Ink4 = Night4
val Gold = Accent
val GoldDim = AccentDim
val Cyan = Mint
val Violet = Lilac
val Rose = Blush
val Lime = Spring
val Amber = Sand
