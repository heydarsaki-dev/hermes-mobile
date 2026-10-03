package ir.hermes.mobile.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * کلاینت REST برای داشبورد Hermes.
 * احراز هویت با هدر X-Hermes-Session-Token انجام می‌شود.
 */
class ApiClient(
    @Volatile var baseUrl: String,
    @Volatile var token: String,
) {
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** کلاینت با تایم‌اوت طولانی برای WebSocket (بدون readTimeout) */
    fun clientForSocket(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** ساخت URL با پاکسازی نویسه انتهایی و افزودن مسیر */
    private fun url(path: String): String {
        val b = baseUrl.trimEnd('/')
        val p = if (path.startsWith("/")) path else "/$path"
        return "$b$p"
    }

    fun request(path: String, method: String = "GET", body: JsonObject? = null): Request {
        val builder = Request.Builder().url(url(path))
        if (token.isNotBlank()) {
            builder.header("X-Hermes-Session-Token", token)
            builder.header("Authorization", "Bearer $token")
        }
        when (method.uppercase()) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            else -> builder.method(method.uppercase(), (body?.toString() ?: "{}").toRequestBody(JSON))
        }
        return builder.build()
    }

    /** اجرای درخواست و برگرداندن JSON یا خطای قابل نمایش */
    suspend fun call(path: String, method: String = "GET", body: JsonObject? = null): Result<JsonElement> =
        withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(request(path, method, body)).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw ApiException(resp.code, humanError(resp.code))
                    if (text.isBlank()) return@use JsonObject(emptyMap())
                    Json.parseToJsonElement(text)
                }
            }
        }

    /** بررسی اتصال و اعتبار توکن */
    suspend fun ping(): Result<JsonElement> = call("/api/status")

    fun humanError(code: Int): String = when (code) {
        401 -> "توکن نامعتبر است یا منقضی شده"
        403 -> "دسترسی مجاز نیست"
        404 -> "مسیر یافت نشد — آدرس سرور را بررسی کنید"
        408, 504 -> "زمان سرور به پایان رسید"
        in 500..599 -> "خطای سرور ($code)"
        else -> "خطای شبکه ($code)"
    }

    fun wsUrl(path: String): String = url(path)
        .replaceFirst("https://", "wss://")
        .replaceFirst("http://", "ws://")
}

class ApiException(val code: Int, override val message: String) : IOException(message)
