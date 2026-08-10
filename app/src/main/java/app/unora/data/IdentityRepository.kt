package app.unora.data

import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Context.unoraDataStore: DataStore<Preferences> by preferencesDataStore(name = "unora_preferences")

data class LocalIdentity(
    val clientId: String,
    val nickname: String,
    val avatarPath: String? = null,
)

enum class ThemePreference { SYSTEM, LIGHT, DARK }

class IdentityRepository(context: Context) {
    private val context = context.applicationContext
    private val dataStore = this.context.unoraDataStore

    val identity: Flow<LocalIdentity?> = dataStore.data.map { preferences ->
        val id = preferences[CLIENT_ID]
        val nickname = preferences[NICKNAME]
        if (id == null || nickname == null) null else LocalIdentity(id, nickname, preferences[AVATAR_PATH])
    }

    val themePreference: Flow<ThemePreference> = dataStore.data.map { preferences ->
        preferences[THEME_MODE]
            ?.let { stored -> ThemePreference.entries.firstOrNull { it.name == stored } }
            ?: ThemePreference.SYSTEM
    }

    suspend fun ensureIdentity(): LocalIdentity {
        var created: LocalIdentity? = null
        dataStore.edit { preferences ->
            val id = preferences[CLIENT_ID] ?: UUID.randomUUID().toString().replace("-", "").also {
                preferences[CLIENT_ID] = it
            }
            val nickname = preferences[NICKNAME] ?: defaultNickname().also {
                preferences[NICKNAME] = it
            }
            created = LocalIdentity(id, nickname, preferences[AVATAR_PATH])
        }
        return requireNotNull(created)
    }

    suspend fun updateNickname(rawNickname: String): Result<Unit> {
        val nickname = rawNickname.trim()
        if (nickname.length !in 1..32) return Result.failure(IllegalArgumentException("O nome deve ter entre 1 e 32 caracteres."))
        dataStore.edit { it[NICKNAME] = nickname }
        return Result.success(Unit)
    }

    suspend fun updateAvatar(uri: Uri): Result<String> = withContext(Dispatchers.IO) { runCatching {
        val profileDirectory = File(context.filesDir, "profile").apply { mkdirs() }
        val target = File(profileDirectory, "avatar")
        val temporary = File(profileDirectory, "avatar.tmp")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Não foi possível abrir a imagem selecionada." }
            temporary.outputStream().use { output -> input.copyTo(output, bufferSize = 64 * 1024) }
        }
        require(temporary.length() in 1..MAX_AVATAR_BYTES) {
            "Escolha uma imagem de até 12 MB."
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(temporary.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "O arquivo selecionado não é uma imagem válida." }
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > AVATAR_MAX_EDGE || bounds.outHeight / sampleSize > AVATAR_MAX_EDGE) {
            sampleSize *= 2
        }
        val bitmap = requireNotNull(BitmapFactory.decodeFile(
            temporary.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        )) { "Não foi possível processar a imagem." }
        target.outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) { "Não foi possível salvar a imagem." }
        }
        bitmap.recycle()
        temporary.delete()
        dataStore.edit { it[AVATAR_PATH] = target.absolutePath }
        target.absolutePath
    }.onFailure {
        File(context.filesDir, "profile/avatar.tmp").delete()
    } }

    suspend fun removeAvatar() {
        dataStore.edit { it.remove(AVATAR_PATH) }
        File(context.filesDir, "profile/avatar").delete()
    }

    suspend fun updateThemePreference(preference: ThemePreference) {
        dataStore.edit { it[THEME_MODE] = preference.name }
    }

    suspend fun saveHostToken(partyId: String, hostToken: String) {
        dataStore.edit { it[hostTokenKey(partyId)] = hostToken }
    }

    suspend fun hostTokenFor(partyId: String): String? = dataStore.data.map { it[hostTokenKey(partyId)] }.first()

    private fun defaultNickname(): String = NICKNAMES.random() + " " + (100..999).random()

    private fun hostTokenKey(partyId: String) = stringPreferencesKey("host_token_${partyId.uppercase()}")

    private companion object {
        val CLIENT_ID = stringPreferencesKey("client_id")
        val NICKNAME = stringPreferencesKey("nickname")
        val AVATAR_PATH = stringPreferencesKey("avatar_path")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val NICKNAMES = listOf("Luna", "Rafa", "Alex", "Nina", "Theo", "Maya")
        const val MAX_AVATAR_BYTES = 12L * 1024 * 1024
        const val AVATAR_MAX_EDGE = 1_024
    }
}
