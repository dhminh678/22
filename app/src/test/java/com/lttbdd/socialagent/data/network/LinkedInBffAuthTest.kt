package com.lttbdd.socialagent.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkedInBffAuthTest {

    private val base = "https://bff.example.com"

    @Test
    fun `URL dang nhap dung endpoint Standard OAuth va redirect ve BFF`() {
        val url = LinkedInBffAuth.authorizationUrl("client_that", base, "state_abc")

        assertTrue(url.startsWith("https://www.linkedin.com/oauth/v2/authorization?"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("client_id=client_that"))
        assertTrue(url.contains("state=state_abc"))
        assertTrue(url.contains("redirect_uri=https%3A%2F%2Fbff.example.com%2Fauth%2Flinkedin%2Fcallback"))
        assertTrue(url.contains("scope=openid+profile+w_member_social"))
    }

    @Test
    fun `URL dang nhap khong chua secret va khong dung native-pkce`() {
        val url = LinkedInBffAuth.authorizationUrl("client_that", base, "s")

        assertFalse(url.contains("secret"))
        assertFalse(url.contains("native-pkce"))
        assertFalse(url.contains("code_challenge"))
    }

    @Test
    fun `dia chi BFF duoc chuan hoa bo dau gach cheo cuoi va khoang trang`() {
        assertEquals("https://bff.example.com", LinkedInBffAuth.normalizeBaseUrl("  https://bff.example.com/// "))
        assertEquals("https://bff.example.com/auth/linkedin/start", LinkedInBffAuth.startUrl("https://bff.example.com/"))
        assertEquals("https://bff.example.com/auth/linkedin/token", LinkedInBffAuth.tokenUrl(" https://bff.example.com "))
        assertEquals(
            "https://bff.example.com/auth/linkedin/callback",
            LinkedInBffAuth.bffRedirectUri("https://bff.example.com/"),
        )
    }

    @Test
    fun `chi chap nhan BFF qua HTTPS`() {
        assertTrue(LinkedInBffAuth.isValidBaseUrl("https://bff.example.com"))
        assertTrue(LinkedInBffAuth.isValidBaseUrl(" https://bff.example.com/ "))
        assertFalse(LinkedInBffAuth.isValidBaseUrl("http://bff.example.com"))
        assertFalse(LinkedInBffAuth.isValidBaseUrl("https://"))
        assertFalse(LinkedInBffAuth.isValidBaseUrl(""))
        assertFalse(LinkedInBffAuth.isValidBaseUrl("SOCIAL_AGENT_LINKEDIN_BFF_URL"))
        assertFalse(LinkedInBffAuth.isValidBaseUrl("https://bff example.com"))
    }

    @Test
    fun `redirect ve app trung scheme trong Manifest`() {
        assertEquals("com.lttbdd.socialagent://oauth/linkedin/callback", LinkedInBffAuth.APP_REDIRECT_URI)
    }
}
