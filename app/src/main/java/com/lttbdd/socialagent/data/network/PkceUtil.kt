package com.lttbdd.socialagent.data.network

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Tiện ích tạo tham số PKCE (Proof Key for Code Exchange) theo chuẩn RFC 7636.
 * Nguồn tham chiếu:
 * - RFC 7636 Section 4.1: Code Verifier (43 - 128 ký tự unreserved)
 * - RFC 7636 Section 4.2: Code Challenge (S256 = Base64URL(SHA-256(verifier)))
 */
object PkceUtil {

    private const val UNRESERVED_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._"
    private const val DEFAULT_VERIFIER_LENGTH = 64

    /**
     * Sinh chuỗi `state` ngẫu nhiên bảo mật cao theo RFC 6749 Section 10.12 (chống tấn công CSRF).
     */
    fun generateState(length: Int = 32): String {
        val random = SecureRandom()
        val sb = StringBuilder(length)
        for (i in 0 until length) {
            val randomIndex = random.nextInt(UNRESERVED_CHARS.length)
            sb.append(UNRESERVED_CHARS[randomIndex])
        }
        return sb.toString()
    }

    /**
     * Sinh chuỗi `code_verifier` ngẫu nhiên bảo mật cao theo RFC 7636 Section 4.1.
     */
    fun generateCodeVerifier(length: Int = DEFAULT_VERIFIER_LENGTH): String {
        require(length in 43..128) { "code_verifier length must be between 43 and 128 chars (RFC 7636 Section 4.1)" }
        val random = SecureRandom()
        val sb = StringBuilder(length)
        for (i in 0 until length) {
            val randomIndex = random.nextInt(UNRESERVED_CHARS.length)
            sb.append(UNRESERVED_CHARS[randomIndex])
        }
        return sb.toString()
    }

    /**
     * Sinh `code_challenge` từ `code_verifier` bằng phương thức S256 theo RFC 7636 Section 4.2.
     * S256 = BASE64URL-ENCODE(SHA256(ASCII(code_verifier))) không chứa padding.
     */
    fun generateCodeChallengeS256(codeVerifier: String): String {
        val bytes = codeVerifier.toByteArray(StandardCharsets.US_ASCII)
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
    }
}
