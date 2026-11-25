package com.lttbdd.socialagent.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lttbdd.socialagent.core.model.ChannelType
import kotlinx.coroutines.flow.first

private val Context.channelAccountDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "social_agent_channel_accounts",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Lưu metadata tài khoản dưới dạng JSON trong DataStore (không mã hóa vì không chứa bí mật). */
class DataStoreChannelAccountStore(
    private val context: Context,
) : ChannelAccountStore {

    private val key = stringPreferencesKey("accounts_v1")

    override suspend fun load(): List<StoredChannelAccount> {
        val raw = context.channelAccountDataStore.data.first()[key]
        return ChannelAccountMetadata.decode(raw)
    }

    override suspend fun upsert(account: StoredChannelAccount) {
        upsertIf(account) { true }
    }

    override suspend fun upsertIf(account: StoredChannelAccount, canCommit: () -> Boolean): Boolean {
        var committed = false
        context.channelAccountDataStore.edit { preferences ->
            if (canCommit()) {
                preferences[key] = ChannelAccountMetadata.upsert(preferences[key], account)
                committed = true
            }
        }
        return committed
    }

    override suspend fun remove(type: ChannelType, accountId: String) {
        context.channelAccountDataStore.edit { preferences ->
            preferences[key] = ChannelAccountMetadata.remove(preferences[key], type, accountId)
        }
    }
}
