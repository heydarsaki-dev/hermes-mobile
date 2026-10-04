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

    /** نرمال‌سازی آدرس: حذف فاصله‌های اضافی و افزودن اسکیما در صورت نبود */
    private val safeBase: String
        get() {
            var b = baseUrl.trim().trimEnd('/')
            if (b.isBlank()) b = "http://127.0.0.1:8080"
            if (!b.startsWith("http://", true) && !b.startsWith("https://", true)) b = "http://$b"
            return b
        }

    /** ساخت URL با پاکسازی نویسه انتهایی و افزودن مسیر */
    private fun url(path: String): String {
        val p = if (path.startsWith("/")) path else "/$path"
        return safeBase + p
    }

    /** حذف نویسه‌های کنترلی/فاصله که هدر HTTP را خراب می‌کنند (علت کرش) */
    fun cleanToken(raw: String): String = raw.trim().filter { it.code in 33..126 }

    fun request(path: String, method: String = "GET", body: JsonObject? = null): Request {
        val builder = Request.Builder().url(url(path))
        val t = cleanToken(token)
        if (t.isNotBlank()) {
            builder.header("X-Hermes-Session-Token", t)
            builder.header("Authorization", "Bearer $t")
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
            }.recoverCatching { e ->
                // خطاهای ساخت URL/هدر (مثل توکن دارای نویسه کنترلی) نباید اپ را بترکانند
                throw friendly(e)
            }
        }

    /** تبدیل استثنای خام به پیام فارسی قابل فهم */
    private fun friendly(e: Throwable): Throwable = when (e) {
        is ApiException -> e
        is java.net.UnknownHostException -> IllegalArgumentException("دامنه پیدا نشد — آدرس را بررسی کنید")
        is java.net.ConnectException -> IllegalArgumentException("اتصال برقرار نشد — سرور در حال اجرا نیست یا آدرس اشتباه است")
        is java.net.SocketTimeoutException -> IllegalArgumentException("زمان پاسخ سرور به پایان رسید")
        is java.net.UnknownServiceException -> IllegalArgumentException("سرویس شبکه در دسترس نیست")
        is IllegalArgumentException -> IllegalArgumentException("آدرس یا توکن نامعتبر است")
        else -> e
    }

    /** بررسی اتصال و اعتبار توکن */
    suspend fun ping(): Result<JsonElement> = call("/api/status")

    /**
     * گرفتن تیکت تک‌بارمصرف برای ارتقای WebSocket.
     * در حالت loopback هرمس تیکت لازم ندارد و ?token= کافی است؛
     * این متد برای حالت احراز هویت داشبورد است.
     */
    suspend fun wsTicket(): Result<String> =
        call("/api/auth/ws-ticket", "POST", JsonObject(emptyMap())).mapCatching { el ->
            val o = el as? JsonObject ?: error("پاسخ نامعتبر")
            (o["ticket"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: error("تیکت در پاسخ یافت نشد")
        }

    fun humanError(code: Int): String = when (code) {
        401 -> "توکن نامعتبر است یا منقضی شده"
        403 -> "دسترسی مجاز نیست"
        404 -> "مسیر یافت نشد — آدرس سرور را بررسی کنید"
        408, 504 -> "زمان سرور به پایان رسید"
        426 -> "این آدرس وب‌سوکت را پشتیبانی نمی‌کند"
        in 500..599 -> "خطای سرور ($code)"
        else -> "خطای شبکه ($code)"
    }

    fun wsUrl(path: String): String = url(path)
        .replaceFirst("https://", "wss://")
        .replaceFirst("http://", "ws://")
}

class ApiException(val code: Int, override val message: String) : IOException(message)
