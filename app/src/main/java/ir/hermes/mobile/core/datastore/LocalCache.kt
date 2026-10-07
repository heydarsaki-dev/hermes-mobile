package ir.hermes.mobile.core.datastore

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File

/**
 * کش محلی روی حافظهٔ دستگاه.
 *
 * خواندن فهرست‌ها از هرمس روی گوشی کند است (سرور هر بار ایجنت/دیتابیس را
 * می‌خواند). اینجا آخرین پاسخ موفق به‌صورت JSON ذخیره می‌شود تا صفحه‌ها
 * بلافاصله محتوا نشان دهند و بعد در پس‌زمینه تازه‌سازی کنند.
 *
 * عمداً سبک است: یک فایل JSON برای هر کلید، بدون وابستگی تازه. برای داده‌های
 * حجیم (تاریخ کامل چت) استفاده نمی‌شود؛ فقط فهرست‌ها و انتخاب‌ها.
 */
class LocalCache(ctx: Context) {

    private val dir = File(ctx.filesDir, "cache").apply { runCatching { mkdirs() } }
    private val json = Json { ignoreUnknownKeys = true }

    private fun file(key: String) = File(dir, "$key.json")

    /** ذخیرهٔ مقدار با زمان ثبت (برای تشخیص کهنگی). */
    fun put(key: String, value: JsonElement) {
        runCatching {
            file(key).writeText("{\"at\":${System.currentTimeMillis()},\"v\":$value}")
        }
    }

    /** آخرین مقدار ذخیره‌شده یا null. */
    fun get(key: String): JsonElement? = runCatching {
        val f = file(key)
        if (!f.exists()) return null
        json.parseToJsonElement(f.readText())
            .let { if (it is kotlinx.serialization.json.JsonObject) it["v"] else null }
    }.getOrNull()

    /** سن دادهٔ ذخیره‌شده به میلی‌ثانیه، یا null اگر چیزی ذخیره نشده باشد. */
    fun ageMs(key: String): Long? = runCatching {
        val f = file(key)
        if (!f.exists()) return null
        val obj = json.parseToJsonElement(f.readText()) as? kotlinx.serialization.json.JsonObject ?: return null
        val at = obj["at"]?.let { runCatching { it.toString().trim('"').toLong() }.getOrNull() } ?: return null
        System.currentTimeMillis() - at
    }.getOrNull()

    fun clear() {
        runCatching { dir.deleteRecursively() }
    }
}
