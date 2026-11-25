package com.lttbdd.socialagent.feature.channel

import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.ChannelAccount
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ConnectionStatus
import com.lttbdd.socialagent.data.model.FacebookPageAccessIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelCapabilityEvaluatorTest {

    @Test
    fun `Mention ban giao thu cong tren hai kenh khi token het han va thieu scope`() {
        for (type in listOf(ChannelType.FACEBOOK, ChannelType.LINKEDIN)) {
            val account = ChannelAccount(type, "id", "Tên", ConnectionStatus.TOKEN_EXPIRED, emptySet())
            val mention = ChannelCapabilityEvaluator.evaluateCapabilities(account, null)
                .single { it.capabilityNameRes == R.string.cap_mention }
            assertEquals(CapabilityStatusType.HANDED_OFF, mention.statusType)
            assertEquals(R.string.cap_status_handed_off, mention.statusTextRes)
        }
    }

    @Test
    fun `token het han yeu cau ket noi lai va giu ban giao thu cong`() {
        val scopesByType = mapOf(
            ChannelType.FACEBOOK to "pages_manage_posts,pages_read_engagement",
            ChannelType.LINKEDIN to "openid profile w_member_social",
        )

        scopesByType.forEach { (type, scopes) ->
            val account = ChannelAccount(
                type = type,
                accountId = "test",
                displayName = "Test",
                status = ConnectionStatus.CONNECTED,
                capabilities = emptySet(),
            )
            val tasks = listOf("CREATE_CONTENT", "MODERATE")
            val connected = ChannelCapabilityEvaluator.evaluateCapabilities(account, scopes, tasks)
            val expired = ChannelCapabilityEvaluator.evaluateCapabilities(
                account.copy(status = ConnectionStatus.TOKEN_EXPIRED),
                scopes,
                tasks,
            )

            assertEquals(CapabilityStatusType.SUPPORTED, connected.first().statusType)
            assertEquals(CapabilityStatusType.REQUIRES_RECONNECT, expired.first().statusType)
            assertEquals(R.string.cap_status_requires_reconnect, expired.first().statusTextRes)
            assertEquals(R.string.cap_token_expired, expired.first().captionRes)
            assertEquals(connected[3], expired[3])
            assertEquals(CapabilityStatusType.HANDED_OFF, expired[3].statusType)
            assertEquals(emptyList<CapabilityItemUiState>(), expired.filter { it.statusType == CapabilityStatusType.SUPPORTED })
        }
    }

    @Test
    fun `Page token bi thu hoi yeu cau ket noi lai du scope va task da luu day du`() {
        assertPageAccessIssue(
            FacebookPageAccessIssue.RECONNECT_REQUIRED,
            CapabilityStatusType.REQUIRES_RECONNECT,
            R.string.cap_status_requires_reconnect,
            R.string.cap_fb_page_token_invalid,
        )
    }

    @Test
    fun `Page mat quyen hien yeu cau quyen du scope va task da luu day du`() {
        assertPageAccessIssue(
            FacebookPageAccessIssue.PERMISSION_REQUIRED,
            CapabilityStatusType.REQUIRES_PERMISSION,
            R.string.cap_status_requires_perm,
            R.string.channel_status_page_permission_required,
        )
    }

    private fun assertPageAccessIssue(
        issue: FacebookPageAccessIssue,
        expectedType: CapabilityStatusType,
        expectedStatus: Int,
        expectedCaption: Int,
    ) {
        val account = ChannelAccount(ChannelType.FACEBOOK, "page", "Page", ConnectionStatus.CONNECTED, emptySet())
        val scopes = "pages_manage_posts,pages_read_engagement,pages_manage_engagement,read_insights"
        val tasks = listOf("CREATE_CONTENT", "MODERATE", "ANALYZE")
        val healthy = ChannelCapabilityEvaluator.evaluateCapabilities(account, scopes, tasks, isFacebookProfile = false)
        val affected = ChannelCapabilityEvaluator.evaluateCapabilities(
            account, scopes, tasks, isFacebookProfile = false, pageAccessIssue = issue,
        )
        val mention = affected.single { it.capabilityNameRes == R.string.cap_mention }
        assertEquals(healthy.single { it.capabilityNameRes == R.string.cap_mention }, mention)
        val apiCapabilities = affected.filter { it.capabilityNameRes != R.string.cap_mention }
        assertEquals(4, apiCapabilities.size)
        apiCapabilities.forEach {
            assertEquals(expectedType, it.statusType)
            assertEquals(expectedStatus, it.statusTextRes)
            assertEquals(expectedCaption, it.captionRes)
        }
    }

    @Test
    fun `facebook capabilities with full scopes return supported for publish, read, reply, metrics`() {
        val account = ChannelAccount(
            type = ChannelType.FACEBOOK,
            accountId = "fb_101",
            displayName = "Facebook Test Page",
            status = ConnectionStatus.CONNECTED,
            capabilities = emptySet(),
        )
        val scopes = "pages_manage_posts,pages_read_engagement,pages_manage_engagement,read_insights"

        val items = ChannelCapabilityEvaluator.evaluateCapabilities(account, scopes)

        assertEquals(5, items.size)
        // 1. Publish
        assertEquals(CapabilityStatusType.SUPPORTED, items[0].statusType)
        // 2. Read Comments
        assertEquals(CapabilityStatusType.SUPPORTED, items[1].statusType)
        // 3. Reply
        assertEquals(CapabilityStatusType.SUPPORTED, items[2].statusType)
        // 4. Mention: chưa xác minh dù đã có quyền đăng/tương tác nên bàn giao thủ công
        assertEquals(CapabilityStatusType.HANDED_OFF, items[3].statusType)
        // 5. Metrics
        assertEquals(CapabilityStatusType.SUPPORTED, items[4].statusType)
    }

    @Test
    fun `facebook capabilities with missing scopes return requires_permission`() {
        val account = ChannelAccount(
            type = ChannelType.FACEBOOK,
            accountId = "fb_101",
            displayName = "Facebook Test Page",
            status = ConnectionStatus.CONNECTED,
            capabilities = emptySet(),
        )
        val scopes = ""

        val items = ChannelCapabilityEvaluator.evaluateCapabilities(account, scopes)

        assertEquals(5, items.size)
        assertEquals(CapabilityStatusType.REQUIRES_PERMISSION, items[0].statusType)
        assertEquals(CapabilityStatusType.REQUIRES_PERMISSION, items[1].statusType)
        assertEquals(CapabilityStatusType.REQUIRES_PERMISSION, items[2].statusType)
        assertEquals(CapabilityStatusType.HANDED_OFF, items[3].statusType)
        assertEquals(CapabilityStatusType.REQUIRES_PERMISSION, items[4].statusType)
    }

    @Test
    fun `linkedin capabilities with full scopes return supported for all 5 items`() {
        val account = ChannelAccount(
            type = ChannelType.LINKEDIN,
            accountId = "li_202",
            displayName = "LinkedIn Org Test",
            status = ConnectionStatus.CONNECTED,
            capabilities = emptySet(),
        )
        val scopes = "w_organization_social,r_organization_social_feed,w_organization_social_feed,rw_organization_admin"

        val items = ChannelCapabilityEvaluator.evaluateCapabilities(account, scopes)

        assertEquals(5, items.size)
        assertEquals(CapabilityStatusType.SUPPORTED, items[0].statusType)
        assertEquals(CapabilityStatusType.SUPPORTED, items[1].statusType)
        assertEquals(CapabilityStatusType.SUPPORTED, items[2].statusType)
        assertEquals(CapabilityStatusType.HANDED_OFF, items[3].statusType)
        assertEquals(CapabilityStatusType.SUPPORTED, items[4].statusType)
    }

    @Test
    fun `chi ho so Facebook da xac nhan moi hien huong dan can Page`() {
        val account = ChannelAccount(
            type = ChannelType.FACEBOOK,
            accountId = "fb_101",
            displayName = "Facebook",
            status = ConnectionStatus.CONNECTED,
            capabilities = emptySet(),
        )

        val items = ChannelCapabilityEvaluator.evaluateCapabilities(
            account, "public_profile", isFacebookProfile = true,
        )

        val profileFeatures = items.filter { it.capabilityNameRes != R.string.cap_mention }
        assertTrue(profileFeatures.all { it.statusType == CapabilityStatusType.REQUIRES_PERMISSION })
        assertTrue(profileFeatures.all { it.captionRes == R.string.cap_fb_needs_page })
        assertEquals(CapabilityStatusType.HANDED_OFF, items.single { it.capabilityNameRes == R.string.cap_mention }.statusType)

        val pageItems = ChannelCapabilityEvaluator.evaluateCapabilities(
            account, "public_profile", isFacebookProfile = false,
        )
        assertEquals(R.string.cap_fb_publish_no_perm, pageItems.first().captionRes)
        assertTrue(pageItems.none { it.captionRes == R.string.cap_fb_needs_page })

        val legacyItems = ChannelCapabilityEvaluator.evaluateCapabilities(account, "public_profile")
        assertTrue(legacyItems.filter { it.capabilityNameRes != R.string.cap_mention }.all { it.captionRes == R.string.cap_fb_account_type_unknown })

        val expiredLegacyItems = ChannelCapabilityEvaluator.evaluateCapabilities(
            account.copy(status = ConnectionStatus.TOKEN_EXPIRED), "public_profile",
        )
        val expiredFeatures = expiredLegacyItems.filter { it.capabilityNameRes != R.string.cap_mention }
        assertTrue(expiredFeatures.all { it.captionRes == R.string.cap_fb_account_type_unknown })
        assertTrue(expiredFeatures.all { it.statusType == CapabilityStatusType.REQUIRES_RECONNECT })
        assertEquals(CapabilityStatusType.HANDED_OFF, expiredLegacyItems.single { it.capabilityNameRes == R.string.cap_mention }.statusType)

        val legacyWithTasks = ChannelCapabilityEvaluator.evaluateCapabilities(
            account, "public_profile", pageTasks = listOf("CREATE_CONTENT"),
        )
        assertEquals(R.string.cap_fb_publish_no_perm, legacyWithTasks.first().captionRes)
    }

    @Test
    fun `linkedin member scope cho phep dang bai nhung mention van chua xac dinh`() {
        val account = ChannelAccount(
            type = ChannelType.LINKEDIN,
            accountId = "li_member",
            displayName = "LinkedIn Member Test",
            status = ConnectionStatus.CONNECTED,
            capabilities = emptySet(),
        )

        val items = ChannelCapabilityEvaluator.evaluateCapabilities(account, "openid profile w_member_social")

        assertEquals(CapabilityStatusType.SUPPORTED, items[0].statusType)
        assertEquals(CapabilityStatusType.HANDED_OFF, items[1].statusType)
        assertEquals(CapabilityStatusType.HANDED_OFF, items[2].statusType)
        assertEquals(CapabilityStatusType.HANDED_OFF, items[3].statusType)
        assertEquals(CapabilityStatusType.HANDED_OFF, items[4].statusType)
    }

    @Test
    fun `linkedin khong co quyen dang bai thi van la requires_permission, chi cac muc khong xin duoc moi ban giao`() {
        val account = ChannelAccount(
            type = ChannelType.LINKEDIN,
            accountId = "li_member",
            displayName = "LinkedIn Member Test",
            status = ConnectionStatus.CONNECTED,
            capabilities = emptySet(),
        )

        val items = ChannelCapabilityEvaluator.evaluateCapabilities(account, "openid profile")

        assertEquals(CapabilityStatusType.REQUIRES_PERMISSION, items[0].statusType)
        assertEquals(CapabilityStatusType.HANDED_OFF, items[1].statusType)
        assertEquals(CapabilityStatusType.HANDED_OFF, items[2].statusType)
        assertEquals(CapabilityStatusType.HANDED_OFF, items[3].statusType)
        assertEquals(CapabilityStatusType.HANDED_OFF, items[4].statusType)
    }

    @Test
    fun `facebook thieu quyen van la requires_permission vi nguoi dung cap lai duoc`() {
        val account = ChannelAccount(
            type = ChannelType.FACEBOOK,
            accountId = "fb_101",
            displayName = "Facebook Test Page",
            status = ConnectionStatus.CONNECTED,
            capabilities = emptySet(),
        )

        val items = ChannelCapabilityEvaluator.evaluateCapabilities(account, "pages_manage_posts")

        assertEquals(CapabilityStatusType.SUPPORTED, items[0].statusType)
        assertEquals(CapabilityStatusType.REQUIRES_PERMISSION, items[1].statusType)
        assertEquals(CapabilityStatusType.REQUIRES_PERMISSION, items[2].statusType)
        assertEquals(CapabilityStatusType.HANDED_OFF, items[3].statusType)
        assertEquals(CapabilityStatusType.REQUIRES_PERMISSION, items[4].statusType)
    }
}
