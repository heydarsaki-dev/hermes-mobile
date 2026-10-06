package ir.hermes.mobile.core.util

import java.util.Calendar

/** تبدیل تاریخ میلادی به شمسی + قالب‌بندی اعداد فارسی */
object Jalali {

    private val gDays = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)

    private val months = arrayOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
    )

    /**
     * میلادی → شمسی (الگوریتم استانداردِ تبدیل بر پایهٔ شمارهٔ روز).
     *
     * نسخهٔ قبلی چند اشکال داشت و برای مثلاً ۲۰۲۶-۱۰-۰۶ سال ‎-۹۸۳‎ و روز ۰
     * می‌داد (در گزارش کرش دیده شد: «۰ اسفند ‎-۹۸۳»).
     */
    fun gregorianToJalali(gy: Int, gm: Int, gd: Int): Triple<Int, Int, Int> {
        // بر اساس الگوریتم شناختهشدهٔ jalaali.
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 355666 + 365 * gy +
            (gy2 + 3) / 4 - (gy2 + 99) / 100 + (gy2 + 399) / 400 +
            gd + gDays[gm - 1]
        var jy = -1595 + 33 * (days / 12053)
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        // «days» اکنون شمارهٔ روز از ابتدای سال شمسی است (صفر‑مبنا).
        return if (days < 186) {
            Triple(jy, 1 + days / 31, 1 + days % 31)
        } else {
            Triple(jy, 7 + (days - 186) / 30, 1 + (days - 186) % 30)
        }
    }

    fun format(millis: Long = System.currentTimeMillis()): String {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        val (jy, jm, jd) = gregorianToJalali(
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)
        )
        val h = c.get(Calendar.HOUR_OF_DAY)
        val m = c.get(Calendar.MINUTE)
        return "${fa(jd)} ${months[(jm - 1).coerceIn(0, 11)]} ${fa(jy)} — ${fa(h)}:${fa(m)}"
    }

    fun dateOnly(millis: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        val (jy, jm, jd) = gregorianToJalali(
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)
        )
        return "${fa(jd)} ${months[(jm - 1).coerceIn(0, 11)]} ${fa(jy)}"
    }

    fun ago(millis: Long): String {
        val d = (System.currentTimeMillis() - millis) / 1000
        return when {
            d < 60 -> "همین حالا"
            d < 3600 -> "${fa(d / 60)} دقیقه پیش"
            d < 86400 -> "${fa(d / 3600)} ساعت پیش"
            d < 604800 -> "${fa(d / 86400)} روز پیش"
            else -> dateOnly(millis)
        }
    }

    /** ارقام لاتین → فارسی */
    fun fa(n: Any?): String {
        val s = n?.toString() ?: "۰"
        return s.map { c -> if (c in '0'..'9') '۰' + (c - '0') else c }.joinToString("")
    }

    /** ارقام فارسی/عربی → لاتین (برای پارس اعداد از سرور) */
    fun toLatin(s: String): String = s.map { c ->
        when (c) {
            in '۰'..'۹' -> '0' + (c - '۰')
            in '٠'..'٩' -> '0' + (c - '٠')
            else -> c
        }
    }.joinToString("")
}
