package app.tapwell

import java.math.BigInteger
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

internal data class GoogleTokenClaims(val subject: String, val email: String, val name: String?)

/** Verify Google's signed identity locally. Drive independently authorizes access with its access token. */
internal object GoogleTokenVerifier {
    private var keys = emptyMap<String, PublicKey>()
    private var validUntil = 0L

    fun verify(token: String, audience: String, nonce: String): GoogleTokenClaims {
        require(token.length <= 65_536) { "Google credential is too large." }
        val header = JSONObject(String(Base64.getUrlDecoder().decode(token.substringBefore('.')), StandardCharsets.UTF_8))
        require(header.getString("alg") == "RS256") { "Unsupported Google signature." }
        val key = signingKey(header.getString("kid"))
        return verifyGoogleToken(token, key, audience, nonce, System.currentTimeMillis() / 1_000)
    }

    @Synchronized
    private fun signingKey(id: String): PublicKey {
        val now = System.nanoTime()
        if (now < validUntil) keys[id]?.let { return it }
        val connection = URL("https://www.googleapis.com/oauth2/v3/certs").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 12_000
            connection.readTimeout = 12_000
            connection.instanceFollowRedirects = false
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "Google signing keys are unavailable." }
            val document = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4_096)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    require(output.size() + count <= 65_536) { "Google signing key response is too large." }
                    output.write(buffer, 0, count)
                }
                JSONObject(String(output.toByteArray(), StandardCharsets.UTF_8))
            }
            val items = document.getJSONArray("keys")
            val factory = KeyFactory.getInstance("RSA")
            val parsed = mutableMapOf<String, PublicKey>()
            for (index in 0 until items.length()) {
                val key = items.getJSONObject(index)
                if (key.optString("kty") != "RSA" || key.optString("alg") != "RS256" || key.optString("use") != "sig") continue
                val modulus = BigInteger(1, Base64.getUrlDecoder().decode(key.getString("n")))
                val exponent = BigInteger(1, Base64.getUrlDecoder().decode(key.getString("e")))
                parsed[key.getString("kid")] = factory.generatePublic(RSAPublicKeySpec(modulus, exponent))
            }
            keys = parsed
            val maxAge = Regex("max-age=(\\d+)").find(connection.getHeaderField("Cache-Control").orEmpty())
                ?.groupValues?.get(1)?.toLongOrNull()?.coerceIn(0, 86_400) ?: 300
            validUntil = now + maxAge * 1_000_000_000
            return keys[id] ?: throw IllegalArgumentException("Google signing key was not found.")
        } finally {
            connection.disconnect()
        }
    }
}

internal fun verifyGoogleToken(
    token: String, key: PublicKey, audience: String, nonce: String, nowSeconds: Long
): GoogleTokenClaims {
    require(token.length <= 65_536) { "Google credential is too large." }
    val parts = token.split('.')
    require(parts.size == 3) { "Invalid Google credential." }
    val decoder = Base64.getUrlDecoder()
    val header = JSONObject(String(decoder.decode(parts[0]), StandardCharsets.UTF_8))
    require(header.getString("alg") == "RS256") { "Unsupported Google signature." }
    val signature = Signature.getInstance("SHA256withRSA")
    signature.initVerify(key)
    signature.update("${parts[0]}.${parts[1]}".toByteArray(StandardCharsets.US_ASCII))
    require(signature.verify(decoder.decode(parts[2]))) { "Invalid Google signature." }
    val claims = JSONObject(String(decoder.decode(parts[1]), StandardCharsets.UTF_8))
    require(claims.getString("iss") in setOf("accounts.google.com", "https://accounts.google.com")) { "Invalid Google issuer." }
    val audiences = when (val value = claims.get("aud")) {
        is String -> listOf(value)
        is JSONArray -> (0 until value.length()).map(value::getString)
        else -> emptyList()
    }
    require(audience in audiences) { "Google credential belongs to another app." }
    require(claims.getString("nonce") == nonce) { "Google credential does not match this sign-in." }
    require(claims.getLong("exp") > nowSeconds - 120 && claims.getLong("iat") <= nowSeconds + 120) {
        "Google credential expired. Check the phone’s date and time."
    }
    require(claims.optBoolean("email_verified")) { "Google email is not verified." }
    val subject = claims.getString("sub").takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("Missing Google account ID.")
    val email = claims.getString("email").takeIf { it.contains('@') }
        ?: throw IllegalArgumentException("Missing Google account email.")
    return GoogleTokenClaims(subject, email, claims.optString("name").takeIf(String::isNotBlank))
}
