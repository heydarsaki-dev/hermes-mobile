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

/** ذخیره آدرس سرور و توکن روی دستگاه */
class SettingsStore(private val ctx: Context) {

    private object Keys {
        val URL = stringPreferencesKey("server_url")
        val TOKEN = stringPreferencesKey("session_token")
        val SAVED = booleanPreferencesKey("has_config")
    }

    val config: Flow<ServerConfig> = ctx.dataStore.data.map {
        ServerConfig(
            url = it[Keys.URL] ?: "http://127.0.0.1:8080",
            token = it[Keys.TOKEN] ?: "",
            connected = it[Keys.SAVED] ?: false,
        )
    }

    suspend fun save(url: String, token: String) {
        ctx.dataStore.edit {
            it[Keys.URL] = url.trim().trimEnd('/')
            it[Keys.TOKEN] = token.trim()
            it[Keys.SAVED] = true
        }
    }

    suspend fun clear() {
        ctx.dataStore.edit { it[Keys.SAVED] = false }
    }
}
