package com.lttbdd.socialagent.data.repository

import com.lttbdd.socialagent.BuildConfig
import com.lttbdd.socialagent.core.model.ChannelAccount
import com.lttbdd.socialagent.core.model.ChannelCapability
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ConnectionStatus
import com.lttbdd.socialagent.data.model.ChannelError
import com.lttbdd.socialagent.data.model.ChannelException
import com.lttbdd.socialagent.data.model.FacebookPage
import com.lttbdd.socialagent.data.model.FacebookUserToken
import com.lttbdd.socialagent.data.model.OAuthToken
import com.lttbdd.socialagent.data.model.toChannelError
import com.lttbdd.socialagent.data.network.LinkedInBffAuth
import com.lttbdd.socialagent.data.security.PendingChannelWrite
import com.lttbdd.socialagent.data.security.TokenStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation quản lý kết nối kênh mạng xã hội, lưu token bảo mật và metadata tài khoản.
 * Nguồn tham chiếu:
 * - RFC 6749 Section 5.1: hạn token tính từ `expires_in` thật của nhà cung cấp (không viết cứng 60 ngày).
 * - RFC 7636: LinkedIn đi qua BFF; app đổi exchange_code kèm code_verifier (client_secret chỉ nằm ở BFF).
 * - Android Keystore: token mã hóa qua TokenStore; [ChannelAccountStore] chỉ giữ metadata không bí mật.
 */
@Singleton
class ChannelRepositoryImpl @Inject constructor(
    private val tokenStore: TokenStore,
    private val accountStore: ChannelAccountStore,
    private val channelDataCleanup: ChannelDataCleanup,
) : ChannelRepository {

    private val channelsFlow = MutableStateFlow<List<ChannelAccount>>(emptyList())
    private val _hasPendingDisconnectCleanup = MutableStateFlow(false)
    override val hasPendingDisconnectCleanup = _hasPendingDisconnectCleanup.asStateFlow()
    /** Khách HTTP; tách ra (internal var) để unit test thay bằng bản giả trả phản hồi có sẵn, không gọi mạng thật. */
    internal var httpClient: okhttp3.OkHttpClient = okhttp3.OkHttpClient()
    private val accountsMutex = Mutex()
    private var restored = false
    private data class PendingWrite(val write: PendingChannelWrite, val committed: Boolean)

    private val pendingWrites = mutableMapOf<Pair<ChannelType, String>, PendingWrite>()

    override fun getConnectedChannels(): Flow<List<ChannelAccount>> = flow {
        accountsMutex.withLock {
            ensureRestored(reload = true)
        }
        emitAll(channelsFlow)
    }

    override suspend fun retryPendingDisconnectCleanup(): Boolean = accountsMutex.withLock {
        try {
            if (!restored) {
                restorePendingDisconnects(tokenStore.loadPendingWrites())
            }
            tryFinishPendingDisconnects()
        } finally {
            publishPendingDisconnectCleanup()
        }
        !_hasPendingDisconnectCleanup.value
    }

    /**
     * Khôi phục danh sách kênh từ metadata đã lưu, chỉ giữ tài khoản còn token đọc được
     * (token mất/hỏng, ví dụ Keystore bị đặt lại, thì tài khoản đó coi như chưa kết nối).
     * Phải giữ [accountsMutex] khi gọi. Nếu kho lỗi, lần sau phải đọc lại trước khi ghi.
     */
    private suspend fun ensureRestored(reload: Boolean = false) {
        if (restored && !reload) return
        restored = false
        val accounts = try {
            val savedWrites = tokenStore.loadPendingWrites().associateBy { it.type to it.accountId }
            // Có thể tiếp tục ngắt kết nối mà chưa cần đọc metadata của các tài khoản khác.
            restorePendingDisconnects(savedWrites.values)
            tryFinishPendingDisconnects()

            val storedAccounts = accountStore.load()
            pendingWrites.entries.removeAll { !it.value.write.disconnect }
            savedWrites.filterValues { !it.disconnect }.forEach { (key, write) ->
                pendingWrites[key] = PendingWrite(
                    write,
                    committed = storedAccounts.any {
                        it.type == write.type && it.accountId == write.accountId && it.tokenWriteId == write.id
                    },
                )
            }
            // Token chính có thể đọc được ngay sau khi hoàn tất lần ghi đang chờ.
            pendingWrites.values.filter { !it.write.disconnect }.forEach { tryFinishPendingWrite(it) }
            storedAccounts
                .filter { readToken(it.type, it.accountId, pendingWrites[it.type to it.accountId]) != null }
                .map { toChannelAccount(it.type, it.accountId, it.displayName) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ChannelException(ChannelError.CHANNELS_LOAD_FAILED, e)
        } finally {
            publishPendingDisconnectCleanup()
        }
        channelsFlow.value = accounts
        restored = true
    }

    private fun restorePendingDisconnects(writes: Collection<PendingChannelWrite>) {
        pendingWrites.entries.removeAll { it.value.write.disconnect }
        writes.filter { it.disconnect }.forEach { write ->
            pendingWrites[write.type to write.accountId] = PendingWrite(write, committed = false)
        }
    }

    private suspend fun tryFinishPendingDisconnects() {
        pendingWrites.values.filter { it.write.disconnect }.forEach { pending ->
            currentCoroutineContext().ensureActive()
            tryFinishPendingWrite(pending)
        }
    }

    private fun toChannelAccount(type: ChannelType, accountId: String, displayName: String) = ChannelAccount(
        type = type,
        accountId = accountId,
        displayName = displayName,
        status = ConnectionStatus.CONNECTED,
        // Khả năng thật tính ở UI từ scope của token; ở đây chỉ liệt kê tập khả năng tối đa của kênh.
        capabilities = ALL_CAPABILITIES,
    )

    override suspend fun connectFacebookProfile(user: FacebookUserToken): Result<ChannelAccount> =
        withContext(Dispatchers.IO) {
            try {
                val profile = fetchFacebookProfile(user.accessToken)
                    ?: return@withContext Result.failure(ChannelException(ChannelError.FACEBOOK_PROFILE_FAILED))
                // Hồ sơ cá nhân không có Page token nên không có khả năng ở mức Page: lưu public_profile
                // để ma trận không báo "Hỗ trợ" cho thứ không dùng được. Hạn token là hạn thật từ SDK.
                val token = OAuthToken(
                    accessToken = user.accessToken,
                    expiresAt = user.expiresAtMillis,
                    scope = "public_profile",
                    isFacebookProfile = true,
                )
                saveAndRegister(ChannelType.FACEBOOK, profile.first, profile.second, token)
            } catch (e: Exception) {
                Result.failure(ChannelException(e.toChannelError(), e))
            }
        }

    /**
     * Gọi Graph API /me bằng access token để lấy (id, name). Trả null nếu lỗi.
     * Token gửi qua header Authorization để không nằm trong URL/log.
     */
    private fun fetchFacebookProfile(accessToken: String): Pair<String, String>? {
        return try {
            val request = okhttp3.Request.Builder()
                .url("$GRAPH_API_BASE_URL/me?fields=id,name")
                .header("Authorization", "Bearer $accessToken")
                .build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (response.isSuccessful && !body.isNullOrEmpty()) parseFacebookProfile(body) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun fetchFacebookPages(userAccessToken: String): Result<List<FacebookPage>> =
        withContext(Dispatchers.IO) {
            try {
                val request = okhttp3.Request.Builder()
                    .url("$GRAPH_API_BASE_URL/me/accounts?fields=id,name,access_token,tasks&limit=100")
                    .header("Authorization", "Bearer $userAccessToken")
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    val pages = if (response.isSuccessful) body?.let(FacebookPagesParser::parse) else null
                    pages?.let { Result.success(it) }
                        ?: Result.failure(ChannelException(ChannelError.FACEBOOK_PAGES_LOAD_FAILED))
                }
            } catch (e: Exception) {
                Result.failure(ChannelException(e.toChannelError(), e))
            }
        }

    override suspend fun connectFacebookSelectedPage(
        user: FacebookUserToken,
        page: FacebookPage,
    ): Result<ChannelAccount> = withContext(Dispatchers.IO) {
        try {
            // Quyền do Meta SDK báo là quyền thật đã cấp cho người dùng và áp dụng cho Page token.
            // GIẢ ĐỊNH (research doc TEST-02 chưa xác minh): hạn Page token được gán bằng hạn user token. Theo tài liệu Meta,
            // Page token lấy từ long-lived user token có thể không hết hạn; nếu đúng thì sau hạn này màn hình sẽ báo
            // "Hết hạn" sớm. Nút "Kết nối lại" xử lý được, nhưng cần kiểm bằng debug_token (expires_at) khi có điều kiện.
            // Nếu Meta rút token sớm hơn, lỗi 190 khi gọi API sẽ cho biết.
            val token = OAuthToken(
                accessToken = page.accessToken,
                expiresAt = user.expiresAtMillis,
                scope = user.grantedScope ?: "public_profile",
                pageTasks = page.tasks,
                isFacebookProfile = false,
            )
            saveAndRegister(ChannelType.FACEBOOK, page.id, page.name, token)
        } catch (e: Exception) {
            Result.failure(ChannelException(e.toChannelError(), e))
        }
    }

    override suspend fun getGrantedScope(type: ChannelType, accountId: String): String? =
        readCommittedToken(type, accountId)?.scope

    override suspend fun getTokenExpiresAt(type: ChannelType, accountId: String): Long? =
        readCommittedToken(type, accountId)?.expiresAt

    override suspend fun getPageTasks(type: ChannelType, accountId: String): List<String>? =
        readCommittedToken(type, accountId)?.pageTasks

    override suspend fun isFacebookProfile(accountId: String): Boolean? =
        readCommittedToken(ChannelType.FACEBOOK, accountId)?.isFacebookProfile

    private suspend fun readCommittedToken(type: ChannelType, accountId: String): OAuthToken? = try {
        accountsMutex.withLock {
            ensureRestored()
            readToken(type, accountId, pendingWrites[type to accountId])
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun readToken(type: ChannelType, accountId: String, pending: PendingWrite?): OAuthToken? {
        if (pending?.write?.disconnect == true) return null
        val write = pending?.takeIf { it.committed }?.write
        return if (write != null && write.type == type && write.accountId == accountId) {
            tokenStore.loadStagedToken(write)
        } else {
            tokenStore.getToken(type, accountId).first()
        }
    }

    /** Gọi trong [accountsMutex]. Chỉ xử lý lần ghi kế tiếp khi đã dọn lần ghi trước. */
    private suspend fun finishPendingWrite(pending: PendingWrite?) {
        if (pending == null) return
        withContext(NonCancellable) {
            var write = pending.write
            if (write.disconnect) {
                var signOutFailure: ChannelException? = null
                val facebookSession = write.facebookSessionToSignOut
                if (facebookSession != null) {
                    try {
                        channelDataCleanup.signOutFacebook(facebookSession)
                        write = tokenStore.completeFacebookSignOut(write)
                        pendingWrites[write.type to write.accountId] = pending.copy(write = write)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        signOutFailure = ChannelException(ChannelError.FACEBOOK_SIGN_OUT_FAILED, e)
                    }
                }
                try {
                    accountStore.remove(write.type, write.accountId)
                } finally {
                    // Phạm vi dọn đã lưu cùng lần xoá token, nên vẫn có thể dọn Room khi kho metadata lỗi.
                    if (write.clearChannelData) channelDataCleanup.clear(write.type, write.id)
                }
                // Khi SDK lỗi, dữ liệu nội bộ vẫn được dọn và journal giữ lại phiên cần đăng xuất.
                signOutFailure?.let { throw it }
            }
            tokenStore.finishPendingWrite(write, pending.committed)
            pendingWrites.remove(write.type to write.accountId)
            if (write.disconnect) publishPendingDisconnectCleanup()
        }
    }

    /** Phải giữ [accountsMutex] khi đọc danh sách lượt ghi đang chờ. */
    private fun publishPendingDisconnectCleanup() {
        _hasPendingDisconnectCleanup.value = pendingWrites.values.any { it.write.disconnect }
    }

    private suspend fun tryFinishPendingWrite(pending: PendingWrite?) {
        try {
            finishPendingWrite(pending)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Trạng thái lần ghi đã nằm trên đĩa. Lần sau sẽ thử dọn lại.
        }
    }

    /** Lượt ngắt kết nối đã được lưu và token đã bị xoá trước khi dọn dữ liệu. */
    private suspend fun finishDisconnectCleanup(pending: PendingWrite) {
        try {
            finishPendingWrite(pending)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is ChannelException && e.error == ChannelError.FACEBOOK_SIGN_OUT_FAILED) throw e
            throw ChannelException(ChannelError.DISCONNECT_CLEANUP_FAILED, e)
        }
    }

    /** Địa chỉ BFF; tách ra để unit test điều khiển được. */
    internal var linkedInBffUrl: String = BuildConfig.LINKEDIN_BFF_URL

    /**
     * Đăng ký phiên với BFF (`POST /auth/linkedin/start`) trước khi mở trình duyệt: BFF nhớ `state`
     * và `code_challenge` để kiểm khi LinkedIn chuyển về và khi app đổi token. Không gửi bí mật nào.
     */
    override suspend fun startLinkedInSession(state: String, codeChallenge: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                if (!LinkedInBffAuth.isValidBaseUrl(linkedInBffUrl)) {
                    return@withContext Result.failure(ChannelException(ChannelError.LINKEDIN_CONFIG_INVALID))
                }
                val body = buildJsonObject {
                    put("state", state)
                    put("code_challenge", codeChallenge)
                }
                postJson<Unit>(LinkedInBffAuth.startUrl(linkedInBffUrl), body.toString()) { response, _ ->
                    if (response.isSuccessful) {
                        Result.success(Unit)
                    } else {
                        Result.failure(ChannelException(ChannelError.LINKEDIN_SESSION_START_FAILED))
                    }
                }
            } catch (e: Exception) {
                Result.failure(ChannelException(e.toChannelError(), e))
            }
        }

    /**
     * Đổi `exchange_code` (từ deep link) kèm `state` và `code_verifier` lấy token LinkedIn từ BFF
     * (`POST /auth/linkedin/token`), rồi gọi `/v2/userinfo` lấy tên và mã thành viên.
     * Hạn và scope là giá trị thật LinkedIn trả (BFF chuyển tiếp nguyên vẹn); thiếu thì không bịa.
     */
    override suspend fun connectLinkedInViaBff(
        exchangeCode: String,
        state: String,
        codeVerifier: String,
    ): Result<ChannelAccount> = withContext(Dispatchers.IO) {
        try {
            if (!LinkedInBffAuth.isValidBaseUrl(linkedInBffUrl)) {
                return@withContext Result.failure(ChannelException(ChannelError.LINKEDIN_CONFIG_INVALID))
            }
            val requestBody = buildJsonObject {
                put("exchange_code", exchangeCode)
                put("state", state)
                put("code_verifier", codeVerifier)
            }
            val tokenResult = postJson<BffToken>(LinkedInBffAuth.tokenUrl(linkedInBffUrl), requestBody.toString()) { response, body ->
                if (!response.isSuccessful || body.isNullOrEmpty()) {
                    Result.failure(ChannelException(ChannelError.LINKEDIN_TOKEN_EXCHANGE_FAILED))
                } else {
                    parseBffToken(body)?.let { Result.success(it) }
                        ?: Result.failure(ChannelException(ChannelError.LINKEDIN_TOKEN_INVALID))
                }
            }
            val bffToken = tokenResult.getOrElse { return@withContext Result.failure(it) }

            val userRequest = okhttp3.Request.Builder()
                .url("https://api.linkedin.com/v2/userinfo")
                .header("Authorization", "Bearer ${bffToken.accessToken}")
                .build()
            val userObj = httpClient.newCall(userRequest).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful || body.isNullOrEmpty()) {
                    return@withContext Result.failure(ChannelException(ChannelError.LINKEDIN_PROFILE_FAILED))
                }
                json.parseToJsonElement(body) as? JsonObject
            }
            val accountId = userObj?.stringField("sub")?.takeIf { it.isNotEmpty() }
                ?: return@withContext Result.failure(ChannelException(ChannelError.LINKEDIN_PROFILE_FAILED))
            val displayName = userObj?.stringField("name")?.takeIf { it.isNotEmpty() } ?: accountId

            val token = OAuthToken.createFromExpiresIn(
                accessToken = bffToken.accessToken,
                expiresInSeconds = bffToken.expiresInSeconds ?: FALLBACK_EXPIRES_IN_SECONDS,
                scope = bffToken.scope,
            )
            saveAndRegister(ChannelType.LINKEDIN, accountId, displayName, token)
        } catch (e: Exception) {
            Result.failure(ChannelException(e.toChannelError(), e))
        }
    }

    /** Khách HTTP riêng cho BFF (timeout dài hơn); giữ nguyên interceptor của [httpClient] để test thay được. */
    private fun bffClient(): okhttp3.OkHttpClient = httpClient.newBuilder()
        .connectTimeout(BFF_CONNECT_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(BFF_READ_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /** POST JSON tới BFF; [handle] chạy khi response còn mở. Token trong body không bao giờ được log. */
    private fun <T> postJson(
        url: String,
        jsonBody: String,
        handle: (okhttp3.Response, String?) -> Result<T>,
    ): Result<T> {
        val request = okhttp3.Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return bffClient().newCall(request).execute().use { response -> handle(response, response.body?.string()) }
    }

    override suspend fun disconnectChannel(type: ChannelType, accountId: String): Result<Unit> {
        return try {
            accountsMutex.withLock {
                ensureRestored()
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    val key = type to accountId
                    val unfinished = pendingWrites[key]
                    if (unfinished?.write?.disconnect == true) {
                        finishDisconnectCleanup(unfinished)
                        return@withContext
                    }
                    val remaining = channelsFlow.value.filterNot { it.type == type && it.accountId == accountId }
                    val clearChannelData = remaining.size != channelsFlow.value.size && remaining.none { it.type == type }
                    val facebookSession = if (clearChannelData && type == ChannelType.FACEBOOK) {
                        channelDataCleanup.currentFacebookSession()
                    } else null
                    // Lưu phạm vi cleanup và phiên SDK cùng giao dịch xoá token của kênh.
                    val write = PendingChannelWrite(
                        id = UUID.randomUUID().toString(),
                        type = type,
                        accountId = accountId,
                        disconnect = true,
                        clearChannelData = clearChannelData,
                        facebookSessionToSignOut = facebookSession,
                    )
                    tokenStore.stageDisconnect(write)
                    val pending = PendingWrite(write, committed = false)
                    pendingWrites[key] = pending
                    _hasPendingDisconnectCleanup.value = true
                    channelsFlow.value = remaining
                    finishDisconnectCleanup(pending)
                }
                Result.success(Unit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChannelException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(ChannelException(ChannelError.CHANNELS_SAVE_FAILED, e))
        }
    }

    private suspend fun saveAndRegister(
        type: ChannelType,
        accountId: String,
        displayName: String,
        token: OAuthToken,
    ): Result<ChannelAccount> = accountsMutex.withLock {
        ensureRestored()
        val account = toChannelAccount(type, accountId, displayName)
        val current = channelsFlow.value.toMutableList()
        val index = current.indexOfFirst { it.type == type && it.accountId == accountId }
        if (index >= 0) current[index] = account else current.add(account)
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            val write = PendingChannelWrite(UUID.randomUUID().toString(), type, accountId)
            try {
                // Hoàn tất dọn dữ liệu của mạng trước khi thêm kết nối mới cùng loại.
                pendingWrites.values.filter { it.write.type == type && it.write.clearChannelData }
                    .forEach { finishPendingWrite(it) }
                finishPendingWrite(pendingWrites[type to accountId])
                tokenStore.stageToken(write, token)
                pendingWrites[type to accountId] = PendingWrite(write, committed = false)
                // Metadata xác nhận lần ghi. Token đang dùng vẫn còn nguyên trước bước này.
                accountStore.upsert(StoredChannelAccount(type, accountId, displayName, write.id))
                pendingWrites[type to accountId] = PendingWrite(write, committed = true)
                channelsFlow.value = current
                Result.success(account)
            } catch (e: Exception) {
                throw ChannelException(ChannelError.CHANNELS_SAVE_FAILED, e)
            } finally {
                tryFinishPendingWrite(pendingWrites[type to accountId])
            }
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        private val ALL_CAPABILITIES = setOf(
            ChannelCapability.PUBLISH,
            ChannelCapability.READ_COMMENTS,
            ChannelCapability.REPLY,
            ChannelCapability.MENTION,
            ChannelCapability.METRICS,
        )

        /** Phiên bản Graph API dùng chung (docs/research-social-api.md chuẩn hóa `v26.0`); nâng cấp ở đây một chỗ. */
        internal const val FACEBOOK_GRAPH_API_VERSION = "v26.0"
        private const val GRAPH_API_BASE_URL = "https://graph.facebook.com/$FACEBOOK_GRAPH_API_VERSION"

        /**
         * BFF chạy trên gói free của Render nên ngủ khi không ai dùng; lần gọi đầu có thể mất khoảng một phút
         * để khởi động. Timeout mặc định của OkHttp (10 giây) sẽ làm lần đăng nhập LinkedIn đầu tiên thất bại.
         */
        private const val BFF_CONNECT_TIMEOUT_SECONDS = 30L
        private const val BFF_READ_TIMEOUT_SECONDS = 60L

        /** LinkedIn không trả expires_in: dùng hạn ngắn để buộc đăng nhập lại thay vì giả định 60 ngày. */
        private const val FALLBACK_EXPIRES_IN_SECONDS = 3_600L

        /** Token LinkedIn do BFF chuyển tiếp: hạn và scope là giá trị thật, thiếu thì null (không bịa). */
        internal data class BffToken(val accessToken: String, val expiresInSeconds: Long?, val scope: String?)

        /** Phân tích phản hồi `POST /auth/linkedin/token`; trả null nếu không có `access_token`. */
        internal fun parseBffToken(body: String): BffToken? {
            return try {
                val obj = json.parseToJsonElement(body) as? JsonObject ?: return null
                val accessToken = obj.stringField("access_token")?.takeIf { it.isNotEmpty() } ?: return null
                BffToken(
                    accessToken = accessToken,
                    expiresInSeconds = obj.stringField("expires_in")?.toLongOrNull()?.takeIf { it > 0 },
                    scope = obj.stringField("scope")?.takeIf { it.isNotBlank() },
                )
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Parse JSON của Graph API /me thành (id, name). Trả null nếu thiếu trường.
         */
        internal fun parseFacebookProfile(body: String): Pair<String, String>? {
            return try {
                val obj = json.parseToJsonElement(body) as? JsonObject ?: return null
                val id = (obj["id"] as? JsonPrimitive)?.content
                val name = (obj["name"] as? JsonPrimitive)?.content
                if (id.isNullOrBlank() || name.isNullOrBlank()) null else id to name
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** Giá trị chuỗi của trường JSON; trường thiếu hoặc `null` trả null. */
private fun JsonObject.stringField(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
