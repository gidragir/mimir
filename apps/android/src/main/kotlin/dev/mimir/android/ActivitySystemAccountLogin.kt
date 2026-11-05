package dev.mimir.android

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.content.IntentSender
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.common.api.Scope
import dev.mimir.android.auth.PlayServicesGisAuthorizer
import dev.mimir.drive.GoogleAuthConfig
import dev.mimir.ui.platform.SystemAccountLogin
import dev.mimir.ui.platform.SystemLoginResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.tasks.await

class ActivitySystemAccountLogin(
    private val context: Context,
    private val accountPrefs: SharedPreferences,
    private val launchIntentSender: (suspend (IntentSenderRequest) -> ActivityResult)? = null,
    registry: ActivityResultRegistry? = null,
) : SystemAccountLogin {

    private var pendingDeferred: CompletableDeferred<ActivityResult>? = null

    private val launcher: ActivityResultLauncher<IntentSenderRequest>? = registry?.register(
        "mimir_system_account_login",
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        pendingDeferred?.complete(result)
    }

    override suspend fun authorize(): SystemLoginResult {
        val availability = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        if (availability != ConnectionResult.SUCCESS) {
            return SystemLoginResult.Unavailable("Play Services unavailable (code $availability)")
        }

        val builder = AuthorizationRequest.builder()
            .setRequestedScopes(GoogleAuthConfig.scopes.map { Scope(it) })
        val accountName = accountPrefs.getString(PlayServicesGisAuthorizer.PREF_GIS_ACCOUNT, null)
        if (!accountName.isNullOrEmpty()) {
            builder.setAccount(Account(accountName, "com.google"))
        }
        val request = builder.build()

        val authClient = Identity.getAuthorizationClient(context)
        val result = try {
            authClient.authorize(request).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResolvableApiException) {
            return launchResolution(e.resolution.intentSender)
        } catch (e: ApiException) {
            val resolution = e.status.resolution
            if (resolution != null) {
                return launchResolution(resolution.intentSender)
            } else {
                return SystemLoginResult.Unavailable(e.message ?: "Play Services API exception")
            }
        } catch (e: Exception) {
            return SystemLoginResult.Unavailable(e.message ?: "Failed to authorize")
        }

        if (result.hasResolution()) {
            val pendingIntent = result.pendingIntent
                ?: return SystemLoginResult.Unavailable("Resolution required but no pending intent")
            return launchResolution(pendingIntent.intentSender)
        } else {
            val email = result.toGoogleSignInAccount()?.email
            if (!email.isNullOrEmpty()) {
                accountPrefs.edit().putString(PlayServicesGisAuthorizer.PREF_GIS_ACCOUNT, email).apply()
            }
            return SystemLoginResult.Granted
        }
    }

    private suspend fun launchResolution(intentSender: IntentSender): SystemLoginResult {
        val senderRequest = IntentSenderRequest.Builder(intentSender).build()
        val activityResult = if (launchIntentSender != null) {
            launchIntentSender.invoke(senderRequest)
        } else if (launcher != null) {
            val deferred = CompletableDeferred<ActivityResult>()
            pendingDeferred = deferred
            launcher.launch(senderRequest)
            deferred.await()
        } else {
            return SystemLoginResult.Unavailable("No intent sender launcher available")
        }

        return when (activityResult.resultCode) {
            Activity.RESULT_OK -> {
                val intent = activityResult.data
                val authResult = try {
                    intent?.let { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(it) }
                } catch (_: Exception) {
                    null
                }
                val email = authResult?.toGoogleSignInAccount()?.email
                    ?: runCatching { GoogleSignIn.getSignedInAccountFromIntent(intent).result?.email }.getOrNull()

                if (!email.isNullOrEmpty()) {
                    accountPrefs.edit().putString(PlayServicesGisAuthorizer.PREF_GIS_ACCOUNT, email).apply()
                }
                SystemLoginResult.Granted
            }
            Activity.RESULT_CANCELED -> {
                val intent = activityResult.data
                val authError = try {
                    intent?.let { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(it) }
                    null
                } catch (e: ApiException) {
                    e
                } catch (_: Exception) {
                    null
                }
                if (authError != null && authError.statusCode != CommonStatusCodes.CANCELED) {
                    SystemLoginResult.Unavailable("Play Services: ${authError.status.statusMessage ?: authError.message ?: "код ${authError.statusCode}"}")
                } else {
                    SystemLoginResult.Cancelled
                }
            }
            else -> SystemLoginResult.Cancelled
        }
    }
}

@Composable
fun rememberActivitySystemAccountLogin(
    activity: ComponentActivity,
    accountPrefs: SharedPreferences,
): ActivitySystemAccountLogin {
    val pendingRef = remember { arrayOf<CompletableDeferred<ActivityResult>?>(null) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        pendingRef[0]?.complete(result)
    }

    return remember(activity, accountPrefs) {
        ActivitySystemAccountLogin(
            context = activity,
            accountPrefs = accountPrefs,
            launchIntentSender = { request ->
                val deferred = CompletableDeferred<ActivityResult>()
                pendingRef[0] = deferred
                launcher.launch(request)
                deferred.await()
            },
        )
    }
}
