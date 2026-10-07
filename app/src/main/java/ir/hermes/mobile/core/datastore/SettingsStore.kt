package ir.hermes.mobile.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("hermes_settings")

data class ServerConfig(
    val url: String = "http://127.0.0.1:8080",
    val token: String = "",
    val connected: Boolean = false,
)

/**
 * مدل/پرووایدری که کاربر انتخاب کرده است.
 *
 * هرمس مدل هر نشست را هنگام ساخت آن ثابت می‌کند؛ پس انتخاب کاربر باید ذخیره
 * شود تا هنگام ساخت نشست جدید به‌عنوان override فرستاده شود. صرف تغییر پیش‌فرض
 * سرور کافی نیست (همان چیزی که باعث می‌شد مدل انتخابی روی نشست‌ها ست نشود).
 */
data class ModelSelection(
    val provider: String = "",
    val model: String = "",
    val baseUrl: String = "",
) {
    val isSet: Boolean get() = model.isNotBlank()
}

/** ذخیره آدرس سرور، توکن و مدل انتخابی روی دستگاه */
class SettingsStore(private val ctx: Context) {

    private object Keys {
        val URL = stringPreferencesKey("server_url")
        val TOKEN = stringPreferencesKey("session_token")
        val SAVED = booleanPreferencesKey("has_config")
        val SEL_PROVIDER = stringPreferencesKey("sel_provider")
        val SEL_MODEL = stringPreferencesKey("sel_model")
        val SEL_BASE_URL = stringPreferencesKey("sel_base_url")
    }

    val config: Flow<ServerConfig> = ctx.dataStore.data.map {
        ServerConfig(
            url = it[Keys.URL] ?: "http://127.0.0.1:8080",
            token = it[Keys.TOKEN] ?: "",
            connected = it[Keys.SAVED] ?: false,
        )
    }

    val selection: Flow<ModelSelection> = ctx.dataStore.data.map {
        ModelSelection(
            provider = it[Keys.SEL_PROVIDER] ?: "",
            model = it[Keys.SEL_MODEL] ?: "",
            baseUrl = it[Keys.SEL_BASE_URL] ?: "",
        )
    }

    suspend fun save(url: String, token: String) {
        ctx.dataStore.edit {
            it[Keys.URL] = url.trim().trimEnd('/')
            it[Keys.TOKEN] = token.trim()
            it[Keys.SAVED] = true
        }
    }

    suspend fun saveSelection(sel: ModelSelection) {
        ctx.dataStore.edit {
            it[Keys.SEL_PROVIDER] = sel.provider
            it[Keys.SEL_MODEL] = sel.model
            it[Keys.SEL_BASE_URL] = sel.baseUrl
        }
    }

    suspend fun clear() {
        ctx.dataStore.edit { it[Keys.SAVED] = false }
    }
}
