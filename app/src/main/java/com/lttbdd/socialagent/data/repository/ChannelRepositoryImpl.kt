package com.lttbdd.socialagent.data.repository

import android.os.SystemClock
import com.lttbdd.socialagent.BuildConfig
import com.lttbdd.socialagent.core.model.ChannelAccount
import com.lttbdd.socialagent.core.model.ChannelCapability
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ConnectionStatus
import com.lttbdd.socialagent.data.model.ChannelError
import com.lttbdd.socialagent.data.model.ChannelException
import com.lttbdd.socialagent.data.model.FACEBOOK_REFRESH_RETRY
import com.lttbdd.socialagent.data.model.FacebookPage
import com.lttbdd.socialagent.data.model.FacebookPageAccessIssue
import com.lttbdd.socialagent.data.model.FacebookRefreshCheck
import com.lttbdd.socialagent.data.model.FacebookRefreshException
import com.lttbdd.socialagent.data.model.FacebookRefreshIssue
import com.lttbdd.socialagent.data.model.FacebookRefreshOutcome
import com.lttbdd.socialagent.data.model.FacebookRefreshSkip
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.UUID
import java.util.logging.Logger
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
    private val facebookPageAccessMutex = Mutex()
    private val facebookPageAccessIssues = MutableStateFlow<Map<String, FacebookPageAccessIssue>>(emptyMap())
    private var lastFacebookPageAccessCheck: Long? = null
    internal var elapsedRealtime: () -> Long = SystemClock::elapsedRealtime
    private val facebookRefreshCoordinator = FacebookRefreshCoordinator { elapsedRealtime() }
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
                if (user.userId != null && profile.first != user.userId) {
                    return@withContext Result.failure(ChannelException(ChannelError.RECONNECT_ACCOUNT_MISMATCH))
                }
                // Hồ sơ cá nhân không có Page token nên không có khả năng ở mức Page: lưu public_profile
                // để ma trận không báo "Hỗ trợ" cho thứ không dùng được. Hạn token là hạn thật từ SDK.
                val token = OAuthToken(
                    accessToken = user.accessToken,
                    expiresAt = user.expiresAtMillis,
                    scope = "public_profile",
                    isFacebookProfile = true,
                    facebookUserId = user.userId,
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
        fetchFacebookPages(userAccessToken, targetPageId = null)

    private suspend fun fetchFacebookPages(
        userAccessToken: String,
        targetPageId: String?,
    ): Result<List<FacebookPage>> = withContext(Dispatchers.IO) {
        try {
            val accountsUrl = "$GRAPH_API_BASE_URL/me/accounts?fields=id,name,access_token,tasks&limit=100".toHttpUrl()
            val pages = linkedMapOf<String, FacebookPage>()
            val seenCursors = mutableSetOf<String>()
            var after: String? = null
            do {
                currentCoroutineContext().ensureActive()
                // Dùng cursor với endpoint cố định vì URL paging.next có thể chứa access token.
                val url = after?.let { accountsUrl.newBuilder().addQueryParameter("after", it).build() } ?: accountsUrl
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $userAccessToken")
                    .build()
                val batch = httpClient.newCall(request).execute().use { response ->
                    val body = response.body.string()
                    if (!response.isSuccessful) throw facebookPagesHttpFailure(response.code, body)
                    FacebookPagesParser.parseBatch(body)
                        ?: throw facebookPagesHttpFailure(response.code, body)
                }
                batch.pages.forEach { page -> pages.getOrPut(page.id) { page } }
                if (targetPageId != null && targetPageId in pages) break
                after = batch.nextCursor
                if (after != null && !seenCursors.add(after)) {
                    throw facebookPagesFailure(FacebookPagesFailure.PAGINATION_LOOP)
                }
            } while (after != null)
            Result.success(pages.values.toList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChannelException) {
            Result.failure(e)
        } catch (e: Exception) {
            val reason = when {
                e is SocketTimeoutException -> "TIMEOUT"
                e.toChannelError() == ChannelError.NETWORK -> "NETWORK"
                else -> "UNEXPECTED"
            }
            refreshLogger.warning("event=facebook_token_refresh stage=fetch_pages result=failed reason=$reason")
            Result.failure(ChannelException(e.toChannelError(), e))
        }
    }

    override suspend fun connectFacebookSelectedPage(
        user: FacebookUserToken,
        page: FacebookPage,
    ): Result<ChannelAccount> = withContext(Dispatchers.IO) {
        try {
            // Quyền do Meta SDK báo là quyền thật đã cấp cho người dùng và áp dụng cho Page token.
            val token = OAuthToken(
                accessToken = page.accessToken,
                expiresAt = null,
                scope = user.grantedScope ?: "public_profile",
                pageTasks = page.tasks,
                isFacebookProfile = false,
                facebookUserId = user.userId,
                facebookUserSessionExpiresAt = user.expiresAtMillis,
            )
            saveAndRegister(ChannelType.FACEBOOK, page.id, page.name, token)
        } catch (e: Exception) {
            Result.failure(ChannelException(e.toChannelError(), e))
        }
    }

    override suspend fun getGrantedScope(type: ChannelType, accountId: String): String? =
        readCommittedToken(type, accountId)?.scope

    override suspend fun getTokenExpiresAt(type: ChannelType, accountId: String): Long? =
        readCommittedToken(type, accountId)?.let { token ->
            // Bản cũ từng ghi hạn phiên người dùng vào trường hạn của Page.
            if (type == ChannelType.FACEBOOK && token.isFacebookProfile != true) null else token.expiresAt
        }

    override suspend fun needsFacebookReconnect(accountId: String): Boolean =
        readCommittedToken(ChannelType.FACEBOOK, accountId)?.let { token ->
            token.isFacebookProfile != true && token.facebookUserId == null
        } ?: false

    override suspend fun getFacebookPageSessionExpiresAt(accountId: String): Long? =
        readCommittedToken(ChannelType.FACEBOOK, accountId)
            ?.takeIf { it.isFacebookProfile == false }
            ?.facebookUserSessionExpiresAt

    override fun observeFacebookPageAccessIssues(): Flow<Map<String, FacebookPageAccessIssue>> =
        facebookPageAccessIssues.asStateFlow()

    override suspend fun checkFacebookPageAccess() {
        facebookPageAccessMutex.withLock {
            val now = elapsedRealtime()
            if (lastFacebookPageAccessCheck?.let { now - it < FACEBOOK_REFRESH_RETRY } == true) return
            val candidates = try {
                facebookCandidates(pageId = null) { _, token -> token.isFacebookProfile == false }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return
            }
            if (candidates.isEmpty()) return
            for ((account, metadata, token) in candidates) {
                currentCoroutineContext().ensureActive()
                val result = checkFacebookPageToken(account.accountId, token.accessToken)
                if (result == PageTokenCheck.RATE_LIMITED) {
                    // Dùng chung thời gian chờ khi Meta yêu cầu giảm tốc độ toàn lượt.
                    lastFacebookPageAccessCheck = elapsedRealtime()
                    return
                }
                if (result == PageTokenCheck.UNKNOWN) continue
                accountsMutex.withLock {
                    val key = account.type to account.accountId
                    // Bỏ phản hồi đến muộn nếu Page đã ngắt hoặc token đã được lưu lại.
                    try {
                        if (metadata !in accountStore.load() ||
                            readToken(account.type, account.accountId, pendingWrites[key]) != token
                        ) {
                            return@withLock
                        }
                        val issues = facebookPageAccessIssues.value
                        val issue = result.issue
                        facebookPageAccessIssues.value =
                            if (issue == null) issues - account.accountId else issues + (account.accountId to issue)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Kho chưa đọc được thì giữ trạng thái trước đó.
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            lastFacebookPageAccessCheck = elapsedRealtime()
        }
    }

    private enum class PageTokenCheck(val issue: FacebookPageAccessIssue? = null) {
        VALID,
        UNKNOWN,
        RATE_LIMITED,
        RECONNECT(FacebookPageAccessIssue.RECONNECT_REQUIRED),
        PERMISSION(FacebookPageAccessIssue.PERMISSION_REQUIRED),
    }

    private suspend fun checkFacebookPageToken(pageId: String, accessToken: String): PageTokenCheck =
        withContext(Dispatchers.IO) {
            val request = okhttp3.Request.Builder()
                .url("$GRAPH_API_BASE_URL/me?fields=id")
                .header("Authorization", "Bearer $accessToken")
                .build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    if (response.code == 429) return@use PageTokenCheck.RATE_LIMITED
                    val body = graphResponseObject(response.body.string())
                    val error = body?.get("error") as? JsonObject
                    val code = error?.stringField("code")?.toIntOrNull()
                    val subcode = error?.stringField("error_subcode")?.toIntOrNull()
                    when {
                        code in facebookRateLimitCodes -> PageTokenCheck.RATE_LIMITED
                        subcode == 492 -> PageTokenCheck.PERMISSION
                        code == 190 -> PageTokenCheck.RECONNECT
                        response.isSuccessful && body?.stringField("id") == pageId -> PageTokenCheck.VALID
                        else -> PageTokenCheck.UNKNOWN
                    }
                }
            } catch (_: IOException) {
                PageTokenCheck.UNKNOWN
            }
        }

    private fun graphResponseObject(body: String): JsonObject? =
        runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    override suspend fun getPageTasks(type: ChannelType, accountId: String): List<String>? =
        readCommittedToken(type, accountId)?.pageTasks

    override suspend fun isFacebookProfile(accountId: String): Boolean? =
        readCommittedToken(ChannelType.FACEBOOK, accountId)?.isFacebookProfile

    private data class FacebookRefreshCandidate(
        val account: ChannelAccount,
        val metadata: StoredChannelAccount,
        val token: OAuthToken,
    )

    override suspend fun refreshFacebookChannels(
        userId: String?,
        expiresBefore: Long,
        sessionVersion: () -> Long?,
        refresh: suspend () -> FacebookUserToken?,
    ): FacebookRefreshCheck =
        facebookRefreshCoordinator.check(userId, refresh, sessionVersion) { coordinatedRefresh ->
            val owner = checkNotNull(userId)
            refreshEligibleFacebookChannels(
                owner, pageId = null, sessionVersion, userToken = coordinatedRefresh,
            ) { account, token ->
                token.isEligibleForFacebookRefresh(account, owner, expiresBefore)
            }
        }.also(::logFacebookRefresh)

    override suspend fun retryFacebookPage(
        pageId: String,
        user: FacebookUserToken,
        version: Long,
        sessionVersion: () -> Long?,
    ): FacebookRefreshCheck =
        facebookRefreshCoordinator.checkPage(user.userId, pageId, version, sessionVersion) { begin ->
            val owner = checkNotNull(user.userId)
            refreshEligibleFacebookChannels(
                owner, pageId, sessionVersion, refreshSession = version,
                userToken = { user.takeIf { begin() } },
            ) { _, token ->
                token.isFacebookProfile == false && token.facebookUserId == owner
            }
        }.also(::logFacebookRefresh)

    private fun logFacebookRefresh(check: FacebookRefreshCheck) {
        val outcome = check.outcome
        if (outcome.issues.isNotEmpty()) {
            val result = if (outcome.updated.isEmpty()) "failed" else "partial"
            refreshLogger.warning(
                "event=facebook_token_refresh stage=batch result=$result " +
                    "updated=${outcome.updated.size} failed=${outcome.issuesByAccountId.size} " +
                    "issues=${outcome.issues} retry_after_ms=${check.nextCheckMillis}",
            )
            outcome.issuesByAccountId.forEach { (id, issue) ->
                refreshLogger.warning(
                    "event=facebook_token_refresh stage=account result=failed " +
                        "account_ref=${refreshAccountRef(id)} issue=$issue retry_after_ms=${check.nextCheckMillis}",
                )
            }
        } else if (BuildConfig.DEBUG) {
            val result = if (outcome.skipped == null) "updated" else "skipped"
            refreshLogger.info(
                "event=facebook_token_refresh stage=batch result=$result skip=${outcome.skipped} " +
                    "updated=${outcome.updated.size} retry_after_ms=${check.nextCheckMillis}",
            )
        }
    }

    private suspend fun facebookCandidates(
        pageId: String?,
        isEligible: (ChannelAccount, OAuthToken) -> Boolean,
    ): List<FacebookRefreshCandidate> =
        accountsMutex.withLock {
            ensureRestored()
            val storedAccounts = accountStore.load().associateBy { it.type to it.accountId }
            channelsFlow.value.filter {
                it.type == ChannelType.FACEBOOK && (pageId == null || it.accountId == pageId)
            }.mapNotNull { account ->
                val key = account.type to account.accountId
                val token = readToken(account.type, account.accountId, pendingWrites[key])
                val metadata = storedAccounts[key]
                if (token == null || metadata == null || !isEligible(account, token)) return@mapNotNull null
                FacebookRefreshCandidate(account, metadata, token)
            }
        }

    private suspend fun refreshEligibleFacebookChannels(
        userId: String,
        pageId: String?,
        sessionVersion: () -> Long?,
        refreshSession: Long? = sessionVersion(),
        userToken: suspend () -> FacebookUserToken?,
        isEligible: (ChannelAccount, OAuthToken) -> Boolean,
    ): FacebookRefreshOutcome {
        val sessionChanged = FacebookRefreshOutcome(skipped = FacebookRefreshSkip.SDK_SESSION_CHANGED)
        fun isCurrent() = sessionVersion() == refreshSession
        val candidates = try {
            facebookCandidates(pageId, isEligible)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (!isCurrent()) return sessionChanged
            return FacebookRefreshOutcome(issues = setOf(FacebookRefreshIssue.STORAGE))
        }
        if (!isCurrent()) return sessionChanged
        if (candidates.isEmpty()) return FacebookRefreshOutcome(skipped = FacebookRefreshSkip.NO_ELIGIBLE_CHANNELS)
        val user = try {
            userToken() ?: return sessionChanged
        } catch (e: CancellationException) {
            throw e
        } catch (e: FacebookRefreshException) {
            return finishFacebookRefresh(candidates, sessionIssue = e.issue, sessionIsCurrent = ::isCurrent)
        } catch (_: Exception) {
            if (!isCurrent()) return sessionChanged
            return FacebookRefreshOutcome(issues = setOf(FacebookRefreshIssue.SDK_UNAVAILABLE))
        }
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return sessionChanged
        if (user.userId != userId) {
            return finishFacebookRefresh(candidates, sessionIssue = FacebookRefreshIssue.ACCOUNT_MISMATCH, sessionIsCurrent = ::isCurrent)
        }
        if (user.expiresAtMillis <= clock()) {
            return finishFacebookRefresh(candidates, sessionIssue = FacebookRefreshIssue.USER_SESSION_EXPIRED, sessionIsCurrent = ::isCurrent)
        }
        val pages = if (candidates.any { it.token.isFacebookProfile == false }) {
            fetchFacebookPages(user.accessToken, targetPageId = pageId)
        } else {
            Result.success(emptyList())
        }
        return finishFacebookRefresh(candidates, user, pages, sessionIsCurrent = ::isCurrent)
    }

    private suspend fun finishFacebookRefresh(
        candidates: List<FacebookRefreshCandidate>,
        user: FacebookUserToken? = null,
        pages: Result<List<FacebookPage>> = Result.success(emptyList()),
        sessionIssue: FacebookRefreshIssue? = null,
        sessionIsCurrent: () -> Boolean,
    ): FacebookRefreshOutcome {
        if (!sessionIsCurrent()) return FacebookRefreshOutcome(skipped = FacebookRefreshSkip.SDK_SESSION_CHANGED)
        val updated = mutableListOf<ChannelAccount>()
        val batchIssues = mutableSetOf<FacebookRefreshIssue>()
        val reconnect = mutableSetOf<String>()
        val issuesByAccount = mutableMapOf<String, FacebookRefreshIssue>()
        val confirmedPages = mutableSetOf<String>()
        var sessionChanged = false
        val pagesById = pages.getOrNull().orEmpty().associateBy { it.id }
        val pagesIssue = pages.exceptionOrNull()?.let { failure ->
            when (failure.cause) {
                is SocketTimeoutException -> FacebookRefreshIssue.TIMEOUT
                is FacebookPagesFailureException -> (failure.cause as FacebookPagesFailureException).kind.issue
                else -> when (failure.toChannelError()) {
                    ChannelError.NETWORK -> FacebookRefreshIssue.NETWORK
                    ChannelError.FACEBOOK_SESSION_INVALID -> FacebookRefreshIssue.SESSION_INVALID
                    else -> FacebookRefreshIssue.PAGES_UNAVAILABLE
                }
            }
        }
        try {
            accountsMutex.withLock {
                val stored = accountStore.load().associateBy { it.type to it.accountId }
                for ((account, metadata, previous) in candidates) {
                    currentCoroutineContext().ensureActive()
                    val key = account.type to account.accountId
                    // Lần đăng nhập hoặc ngắt kết nối mới hơn quyết định cả token lẫn cảnh báo.
                    if (stored[key] != metadata) continue
                    val currentToken = try {
                        readToken(account.type, account.accountId, pendingWrites[key])
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        issuesByAccount[account.accountId] = FacebookRefreshIssue.STORAGE
                        continue
                    }
                    if (currentToken != previous) continue
                    if (!sessionIsCurrent()) {
                        sessionChanged = true
                        break
                    }
                    val issue = sessionIssue ?: when {
                        user == null || user.expiresAtMillis <= clock() -> FacebookRefreshIssue.USER_SESSION_EXPIRED
                        pagesIssue == FacebookRefreshIssue.SESSION_INVALID -> pagesIssue
                        previous.isFacebookProfile == false -> pagesIssue
                        else -> null
                    }
                    if (issue != null) {
                        batchIssues.add(issue)
                        issuesByAccount[account.accountId] = issue
                        if (issue == FacebookRefreshIssue.SESSION_INVALID ||
                            issue == FacebookRefreshIssue.ACCOUNT_MISMATCH ||
                            issue == FacebookRefreshIssue.USER_SESSION_EXPIRED
                        ) {
                            reconnect.add(account.accountId)
                        }
                        continue
                    }
                    checkNotNull(user)
                    val next = if (previous.isFacebookProfile == true) {
                        previous.copy(accessToken = user.accessToken, expiresAt = user.expiresAtMillis, facebookUserId = user.userId)
                    } else {
                        val page = pagesById[account.accountId]
                        if (page == null) {
                            issuesByAccount[account.accountId] = FacebookRefreshIssue.PAGE_NOT_RETURNED
                            continue
                        }
                        confirmedPages.add(account.accountId)
                        previous.copy(
                            accessToken = page.accessToken,
                            // /me/accounts không cung cấp hạn đã xác minh cho Page token.
                            expiresAt = null,
                            scope = user.grantedScope ?: "public_profile",
                            pageTasks = page.tasks,
                            facebookUserId = user.userId,
                            facebookUserSessionExpiresAt = user.expiresAtMillis,
                        )
                    }
                    if (next != previous) {
                        try {
                            val saved = saveAndRegisterLocked(
                                account.type, account.accountId, account.displayName, next, sessionIsCurrent,
                            ).getOrThrow()
                            updated.add(saved)
                        } catch (_: FacebookSessionChanged) {
                            sessionChanged = true
                            break
                        } catch (_: ChannelException) {
                            issuesByAccount[account.accountId] = FacebookRefreshIssue.STORAGE
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            batchIssues.add(FacebookRefreshIssue.STORAGE)
        }
        if (sessionChanged || !sessionIsCurrent()) {
            // Các token đã xác nhận thuộc về thời điểm trước khi phiên thay đổi.
            return FacebookRefreshOutcome(updated = updated, skipped = FacebookRefreshSkip.SDK_SESSION_CHANGED)
        }
        val issues = batchIssues + issuesByAccount.values
        val skipped = FacebookRefreshSkip.NO_CHANGES.takeIf { updated.isEmpty() && issues.isEmpty() }
        return FacebookRefreshOutcome(
            updated = updated,
            issues = issues,
            reconnectAccountIds = reconnect,
            skipped = skipped,
            issuesByAccountId = issuesByAccount,
            batchIssues = batchIssues,
            confirmedPageIds = confirmedPages,
            retryablePageIds = candidates.asSequence().filter { candidate ->
                candidate.token.isFacebookProfile == false &&
                    issuesByAccount[candidate.account.accountId] in pageRetryIssues
            }.map { it.account.accountId }.toSet(),
        )
    }

    private fun OAuthToken.isEligibleForFacebookRefresh(
        account: ChannelAccount,
        userId: String,
        expiresBefore: Long,
    ): Boolean = when (isFacebookProfile) {
        true -> account.accountId == userId && expiresAt?.let { it <= expiresBefore } == true
        false -> facebookUserId == userId && facebookUserSessionExpiresAt?.let { it <= expiresBefore } != false
        null -> false
    }

    private enum class FacebookPagesFailure(val issue: FacebookRefreshIssue) {
        INVALID_SESSION(FacebookRefreshIssue.SESSION_INVALID),
        RATE_LIMIT(FacebookRefreshIssue.RATE_LIMITED),
        SERVER_ERROR(FacebookRefreshIssue.PROVIDER_UNAVAILABLE),
        PERMISSION(FacebookRefreshIssue.PAGES_UNAVAILABLE),
        HTTP_ERROR(FacebookRefreshIssue.PAGES_UNAVAILABLE),
        INVALID_RESPONSE(FacebookRefreshIssue.PAGES_UNAVAILABLE),
        PAGINATION_LOOP(FacebookRefreshIssue.PAGES_UNAVAILABLE),
    }

    private class FacebookPagesFailureException(val kind: FacebookPagesFailure) : Exception(kind.name)

    private fun facebookPagesHttpFailure(status: Int, body: String): ChannelException {
        val error = graphResponseObject(body)?.get("error") as? JsonObject
        val code = error?.stringField("code")?.toIntOrNull()
        val subcode = error?.stringField("error_subcode")?.toIntOrNull()
        val kind = when {
            status in 200..299 -> FacebookPagesFailure.INVALID_RESPONSE
            subcode == 492 -> FacebookPagesFailure.PERMISSION
            code == 190 -> FacebookPagesFailure.INVALID_SESSION
            status == 429 || code in facebookRateLimitCodes -> FacebookPagesFailure.RATE_LIMIT
            status in 500..599 -> FacebookPagesFailure.SERVER_ERROR
            status == 403 || code == 10 || code == 200 -> FacebookPagesFailure.PERMISSION
            else -> FacebookPagesFailure.HTTP_ERROR
        }
        return facebookPagesFailure(kind, status, code, subcode)
    }

    private fun facebookPagesFailure(
        kind: FacebookPagesFailure,
        status: Int? = null,
        code: Int? = null,
        subcode: Int? = null,
    ): ChannelException {
        refreshLogger.warning(
            "event=facebook_token_refresh stage=fetch_pages result=failed " +
                "reason=$kind http_status=$status graph_code=$code graph_subcode=$subcode",
        )
        val channelError = if (kind == FacebookPagesFailure.INVALID_SESSION) {
            ChannelError.FACEBOOK_SESSION_INVALID
        } else {
            ChannelError.FACEBOOK_PAGES_LOAD_FAILED
        }
        return ChannelException(channelError, FacebookPagesFailureException(kind))
    }

    private fun refreshAccountRef(accountId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest((refreshLogSalt + accountId).toByteArray())
            .take(6).joinToString("") { "%02x".format(it) }

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
    internal var clock: () -> Long = System::currentTimeMillis

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
        expectedAccountId: String?,
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
            // Tính hạn ngay khi nhận token từ BFF để thời gian tải hồ sơ không kéo dài hạn đã lưu.
            val expiresIn = bffToken.expiresInSeconds ?: FALLBACK_EXPIRES_IN_SECONDS
            val receivedAt = clock()
            if (expiresIn > (Long.MAX_VALUE - receivedAt.coerceAtLeast(0)) / 1000) {
                return@withContext Result.failure(ChannelException(ChannelError.LINKEDIN_TOKEN_INVALID))
            }
            val token = OAuthToken.createFromExpiresIn(
                accessToken = bffToken.accessToken,
                expiresInSeconds = expiresIn,
                scope = bffToken.scope,
                currentTimeMillis = receivedAt,
            )

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
            if (expectedAccountId != null && accountId != expectedAccountId) {
                return@withContext Result.failure(ChannelException(ChannelError.RECONNECT_ACCOUNT_MISMATCH))
            }
            val displayName = userObj?.stringField("name")?.takeIf { it.isNotEmpty() } ?: accountId

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
                    tokenStore.removeToken(type, accountId)
                    pendingWrites.remove(type to accountId)
                    if (type == ChannelType.FACEBOOK) {
                        facebookPageAccessIssues.value -= accountId
                    }
                    channelsFlow.value = channelsFlow.value.filterNot { it.type == type && it.accountId == accountId }
                    accountStore.remove(type, accountId)
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
        if (type == ChannelType.LINKEDIN && token.isExpired(clock())) {
            return@withLock Result.failure(ChannelException(ChannelError.LINKEDIN_TOKEN_INVALID))
        }
        saveAndRegisterLocked(type, accountId, displayName, token)
    }

    private class FacebookSessionChanged : Exception()

    private suspend fun saveAndRegisterLocked(
        type: ChannelType,
        accountId: String,
        displayName: String,
        token: OAuthToken,
        canCommit: () -> Boolean = { true },
    ): Result<ChannelAccount> {
        val account = toChannelAccount(type, accountId, displayName)
        val current = channelsFlow.value.toMutableList()
        val index = current.indexOfFirst { it.type == type && it.accountId == accountId }
        if (index >= 0) current[index] = account else current.add(account)
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            val write = PendingChannelWrite(UUID.randomUUID().toString(), type, accountId)
            try {
                // Hoàn tất dọn dữ liệu của mạng trước khi thêm kết nối mới cùng loại.
                pendingWrites.values.filter { it.write.type == type && it.write.clearChannelData }
                    .forEach { finishPendingWrite(it) }
                finishPendingWrite(pendingWrites[type to accountId])
                if (!canCommit()) throw FacebookSessionChanged()
                tokenStore.stageToken(write, token)
                pendingWrites[type to accountId] = PendingWrite(write, committed = false)
                // Metadata xác nhận lần ghi. Token đang dùng vẫn còn nguyên trước bước này.
                val metadata = StoredChannelAccount(type, accountId, displayName, write.id)
                if (!accountStore.upsertIf(metadata, canCommit)) throw FacebookSessionChanged()
                pendingWrites[type to accountId] = PendingWrite(write, committed = true)
                if (type == ChannelType.FACEBOOK) {
                    facebookPageAccessIssues.value -= accountId
                }
                channelsFlow.value = current
                Result.success(account)
            } catch (e: FacebookSessionChanged) {
                throw e
            } catch (e: Exception) {
                throw ChannelException(ChannelError.CHANNELS_SAVE_FAILED, e)
            } finally {
                tryFinishPendingWrite(pendingWrites[type to accountId])
            }
        }
    }

    companion object {
        private val refreshLogger = Logger.getLogger("FacebookTokenRefresh")
        private val refreshLogSalt = UUID.randomUUID().toString()
        private val facebookRateLimitCodes = setOf(4, 17, 32, 613)
        private val pageRetryIssues = setOf(
            FacebookRefreshIssue.PAGE_NOT_RETURNED,
            FacebookRefreshIssue.PAGES_UNAVAILABLE,
            FacebookRefreshIssue.STORAGE,
            FacebookRefreshIssue.NETWORK,
            FacebookRefreshIssue.TIMEOUT,
            FacebookRefreshIssue.RATE_LIMITED,
            FacebookRefreshIssue.PROVIDER_UNAVAILABLE,
        )
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
                val rawExpiry = obj["expires_in"]
                val expiresIn = when (rawExpiry) {
                    null, JsonNull -> null
                    else -> (rawExpiry as? JsonPrimitive)?.content?.toLongOrNull()?.takeIf { it > 0 } ?: return null
                }
                BffToken(
                    accessToken = accessToken,
                    expiresInSeconds = expiresIn,
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
