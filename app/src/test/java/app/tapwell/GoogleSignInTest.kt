package app.tapwell

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class GoogleSignInTest {
    private val audience = "tapwell.apps.googleusercontent.com"
    private val nonce = "current-sign-in-nonce"
    private val now = 100_000L

    private fun claims() = JSONObject().put("iss", "https://accounts.google.com")
        .put("aud", audience).put("nonce", nonce).put("iat", now - 30).put("exp", now + 300)
        .put("sub", "google-user").put("email", "person@example.com").put("email_verified", true)
        .put("name", "Test User")

    private fun token(claims: JSONObject): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"RS256","kid":"test"}""".toByteArray())
        val body = encoder.encodeToString(claims.toString().toByteArray())
        val input = "$header.$body"
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(keys.private)
        signer.update(input.toByteArray())
        return "$input.${encoder.encodeToString(signer.sign())}"
    }

    @Test
    fun verifiesSignatureAndReadsGoogleIdentity() {
        val user = verifyGoogleToken(token(claims()), keys.public, audience, nonce, now)
        assertEquals(GoogleTokenClaims("google-user", "person@example.com", "Test User"), user)
    }

    @Test
    fun rejectsWrongAudienceReplayAndExpiredCredentials() {
        for (invalid in listOf(
            claims().put("aud", "another-client"),
            claims().put("nonce", "previous-sign-in"),
            claims().put("exp", now - 300),
            claims().put("iat", now + 500),
            claims().put("email_verified", false),
            claims().put("iss", "https://example.com")
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                verifyGoogleToken(token(invalid), keys.public, audience, nonce, now)
            }
        }
    }

    @Test
    fun rejectsAnAlteredIdentityEvenWithValidClaims() {
        val parts = token(claims()).split('.').toMutableList()
        parts[1] = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(claims().put("email", "attacker@example.com").toString().toByteArray())
        assertThrows(IllegalArgumentException::class.java) {
            verifyGoogleToken(parts.joinToString("."), keys.public, audience, nonce, now)
        }
    }

    @Test
    fun oldProjectAndOtherAccountsCannotReuseSyncState() {
        val user = GoogleUser("google-user", "person@example.com", null, "new-project")
        val old = SavedSync(SyncAccount(user.email, "drive-user"), mapOf("locks" to "local setup"), emptyMap(), 1L)
        assertNull(syncForGoogleUser(old, user, "new-project"))
        val current = old.copy(project = "new-project")
        assertEquals(current, syncForGoogleUser(current, user, "new-project"))
        assertNull(syncForGoogleUser(current, null, "new-project"))
        assertNull(syncForGoogleUser(current, user.copy(email = "another@example.com"), "new-project"))
        assertNull(syncForGoogleUser(current, user.copy(project = "other-project"), "new-project"))
    }

    companion object {
        private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }
}
