package app.tapwell

import android.content.Context
import android.content.MutableContextWrapper
import androidx.activity.ComponentActivity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class GoogleUser(val subject: String, val email: String, val name: String?, val project: String)

internal object GoogleSignIn {
    suspend fun signIn(activity: ComponentActivity): GoogleUser {
        val audience = activity.getString(R.string.google_web_client_id)
        val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(
            ByteArray(32).also { SecureRandom().nextBytes(it) }
        )
        val option = GetSignInWithGoogleOption.Builder(audience).setNonce(nonce).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val response = CredentialManager.create(activity).getCredential(MutableContextWrapper(activity), request)
        val credential = response.credential
        require(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            "Google returned an unsupported credential."
        }
        val google = GoogleIdTokenCredential.createFrom(credential.data)
        val claims = withContext(Dispatchers.IO) {
            GoogleTokenVerifier.verify(google.idToken, audience, nonce)
        }
        require(claims.email.equals(google.id, ignoreCase = true)) { "Google account details do not match." }
        return GoogleUser(claims.subject, claims.email, claims.name, activity.getString(R.string.google_cloud_project))
    }

    suspend fun clear(context: Context) {
        CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
    }
}
