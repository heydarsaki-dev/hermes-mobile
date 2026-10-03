package ir.hermes.mobile.core.util

import java.util.Calendar

/** تبدیل تاریخ میلادی به شمسی + قالب‌بندی اعداد فارسی */
object Jalali {

    private val gDays = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)

    private val months = arrayOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
    )

    /** میلادی → شمسی */
    fun gregorianToJalali(gy: Int, gm: Int, gd: Int): Triple<Int, Int, Int> {
        var g = gy - 1600
        val gmIdx = gm - 1
        if (gm <= 2) g -= 1
        var days = 365 * g + (g + 3) / 4 - (g + 99) / 100 + (g + 399) / 400 - 80 + gd + gDays[gmIdx]
        var jy = -1595 + 33 * (days / 12053)
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) { jy += (days - 1) / 365; days = (days - 1) % 365 }
        return if (days < 186) Triple(jy + days / 31, days % 31 + 1, 0)
        else Triple(jy + 186 + (days - 186) / 30, (days - 186) % 30 + 1, 0)
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
