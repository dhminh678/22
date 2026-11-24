package com.lttbdd.socialagent.data.security

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.data.model.OAuthToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "social_agent_secure_tokens")

/**
 * Bộ lưu trữ Token an toàn cho ứng dụng Android.
 * Nguồn tham chiếu:
 * - Android Keystore system: https://developer.android.com/privacy-and-security/keystore
 * - Android Cryptography Guide: https://developer.android.com/privacy-and-security/cryptography
 * - DataStore Preferences kết hợp AndroidKeystoreCipher.
 *
 * Nguyên tắc bảo mật:
 * 1. Mọi OAuthToken trước khi lưu vào DataStore đều được mã hóa bằng Android Keystore (AES-256-GCM).
 * 2. Tuyệt đối không lưu mã thông báo dạng rõ (plaintext), không log token ra Logcat hay đưa vào repo.
 */
@Singleton
class SecureTokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keystoreCipher: AndroidKeystoreCipher,
) : TokenStore {

    private val json = Json { ignoreUnknownKeys = true }
    private val legacyPendingKey = stringPreferencesKey("pending_channel_write")
    private val legacyStagedKey = stringPreferencesKey("pending_channel_token")

    /**
     * Lưu trữ mã OAuthToken mã hóa theo kênh và ID tài khoản.
     */
    override suspend fun saveToken(channelType: ChannelType, accountId: String, token: OAuthToken) {
        val prefKey = stringPreferencesKey(buildTokenKey(channelType, accountId))
        val cipherText = encryptToken(token)
        context.dataStore.edit { it[prefKey] = cipherText }
    }

    override suspend fun completeFacebookSignOut(write: PendingChannelWrite): PendingChannelWrite {
        require(write.disconnect && write.facebookSessionToSignOut != null)
        val completed = write.copy(facebookSessionToSignOut = null)
        context.dataStore.edit { preferences ->
            val key = if (isLegacy(preferences, write)) legacyPendingKey else descriptorKey(write.type, write.accountId)
            check(decodePendingWrite(preferences[key]) == write)
            preferences[key] = json.encodeToString(completed)
        }
        return completed
    }

    override suspend fun stageToken(write: PendingChannelWrite, token: OAuthToken) {
        val cipherText = encryptToken(token)
        context.dataStore.edit { preferences ->
            val key = descriptorKey(write.type, write.accountId)
            check(decodePendingWrite(preferences[key]) == null)
            preferences[key] = json.encodeToString(write)
            preferences[stagedKey(write)] = cipherText
        }
    }

    private val descriptorPrefix = "pending_channel_write_"

    private fun descriptorKey(type: ChannelType, accountId: String) =
        stringPreferencesKey(descriptorPrefix + buildTokenKey(type, accountId))

    private fun stagedKey(write: PendingChannelWrite) = stringPreferencesKey("staged_channel_token_" + write.id)

    override suspend fun loadPendingWrites(): List<PendingChannelWrite> {
        val preferences = context.dataStore.data.first()
        return buildList {
            decodePendingWrite(preferences[legacyPendingKey])?.let { add(it) }
            for ((key, value) in preferences.asMap()) {
                if (key.name.startsWith(descriptorPrefix)) decodePendingWrite(value as? String)?.let { add(it) }
            }
        }
    }

    private fun isLegacy(preferences: Preferences, write: PendingChannelWrite): Boolean =
        decodePendingWrite(preferences[legacyPendingKey]) == write

    override suspend fun loadStagedToken(write: PendingChannelWrite): OAuthToken? = withContext(Dispatchers.IO) {
        val preferences = context.dataStore.data.first()
        val key = if (isLegacy(preferences, write)) legacyStagedKey else stagedKey(write)
        decryptToken(preferences[key])
    }

    override suspend fun finishPendingWrite(write: PendingChannelWrite, commit: Boolean) {
        context.dataStore.edit { preferences ->
            val legacy = isLegacy(preferences, write)
            val descriptor = if (legacy) legacyPendingKey else descriptorKey(write.type, write.accountId)
            val staged = if (legacy) legacyStagedKey else stagedKey(write)
            check(decodePendingWrite(preferences[descriptor]) == write)
            if (commit) {
                val tokenKey = stringPreferencesKey(buildTokenKey(write.type, write.accountId))
                val ciphertext = preferences[staged]
                // Metadata đã đổi. Nếu mất token mới, yêu cầu kết nối lại thay vì dùng token cũ.
                if (ciphertext == null) preferences.remove(tokenKey) else preferences[tokenKey] = ciphertext
            }
            preferences.remove(descriptor)
            preferences.remove(staged)
        }
    }

    private fun decodePendingWrite(value: String?): PendingChannelWrite? = try {
        value?.let { json.decodeFromString<PendingChannelWrite>(it) }
    } catch (_: SerializationException) {
        // Một mục hỏng hoặc thuộc phiên bản mới hơn không được chặn các kênh khác.
        null
    }

    private fun encryptToken(token: OAuthToken): String {
        val plainText = json.encodeToString(token).toByteArray(Charsets.UTF_8)
        return Base64.encodeToString(keystoreCipher.encrypt(plainText), Base64.NO_WRAP)
    }

    private fun decryptToken(cipherText: String?): OAuthToken? {
        if (cipherText == null) return null
        return try {
            val bytes = Base64.decode(cipherText, Base64.NO_WRAP)
            val plainText = keystoreCipher.decrypt(bytes).toString(Charsets.UTF_8)
            json.decodeFromString<OAuthToken>(plainText)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Đọc và giải mã OAuthToken của một kênh. Trả về null nếu chưa có hoặc token bị hỏng.
     */
    override fun getToken(channelType: ChannelType, accountId: String): Flow<OAuthToken?> {
        val prefKey = stringPreferencesKey(buildTokenKey(channelType, accountId))
        return context.dataStore.data
            .map { preferences -> decryptToken(preferences[prefKey]) }
            .flowOn(Dispatchers.IO) // Giải mã Keystore chạy ngoài Main thread.
    }

    /**
     * Xóa token khỏi bộ lưu trữ khi người dùng ngắt kết nối kênh.
     */
    override suspend fun removeToken(channelType: ChannelType, accountId: String) {
        removeTokenAndPending(channelType, accountId, disconnect = null)
    }

    override suspend fun stageDisconnect(write: PendingChannelWrite) {
        require(write.disconnect)
        removeTokenAndPending(write.type, write.accountId, disconnect = write)
    }

    private suspend fun removeTokenAndPending(
        channelType: ChannelType,
        accountId: String,
        disconnect: PendingChannelWrite?,
    ) {
        val prefKey = stringPreferencesKey(buildTokenKey(channelType, accountId))
        context.dataStore.edit { preferences ->
            preferences.remove(prefKey)
            val descriptor = descriptorKey(channelType, accountId)
            decodePendingWrite(preferences[descriptor])?.let { preferences.remove(stagedKey(it)) }
            preferences.remove(descriptor)
            val legacy = decodePendingWrite(preferences[legacyPendingKey])
            if (legacy?.type == channelType && legacy.accountId == accountId) {
                preferences.remove(legacyPendingKey)
                preferences.remove(legacyStagedKey)
            }
            if (disconnect != null) preferences[descriptor] = json.encodeToString(disconnect)
        }
    }

    private fun buildTokenKey(channelType: ChannelType, accountId: String): String {
        return "token_${channelType.name}_$accountId"
    }
}
