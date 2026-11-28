package com.lttbdd.socialagent.data.social

import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.data.model.OAuthToken
import com.lttbdd.socialagent.data.repository.ChannelRepositoryImpl
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject

internal fun interface PublishingAccounts {
    suspend fun load(type: ChannelType): List<Pair<String, OAuthToken>>
}

internal class PublishDestinations(private val accounts: PublishingAccounts) {
    @Inject constructor(repository: ChannelRepositoryImpl) : this(PublishingAccounts(repository::readPublishingAccounts))

    /** Gắn đích vào khoá hàng đợi để lần thử lại không thể chuyển sang tài khoản khác. */
    suspend fun newKey(type: ChannelType): String? {
        val account = accounts.load(type).filter { (id, token) ->
            when (type) {
                ChannelType.FACEBOOK -> token.isFacebookProfile == false && pageId.matches(id)
                ChannelType.LINKEDIN -> memberUrn(id) != null
            }
        }.singleOrNull() ?: return null
        val nonce = UUID.randomUUID().toString()
        return "v1:$nonce:${fingerprint(type, nonce, account.first)}"
    }

    suspend fun resolve(type: ChannelType, key: String): Pair<String, OAuthToken>? {
        val parts = key.split(':', limit = 3)
        if (parts.size != 3 || parts[0] != "v1") return null
        return accounts.load(type).singleOrNull { (id, _) ->
            fingerprint(type, parts[1], id) == parts[2]
        }
    }

    private fun fingerprint(type: ChannelType, nonce: String, account: String): String =
        MessageDigest.getInstance("SHA-256").digest("${type.name}:$nonce:$account".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

internal val pageId = Regex("[0-9]+")
private val memberId = Regex("[A-Za-z0-9_-]+")

internal fun memberUrn(accountId: String): String? =
    accountId.removePrefix("urn:li:person:").takeIf(memberId::matches)?.let { "urn:li:person:$it" }
