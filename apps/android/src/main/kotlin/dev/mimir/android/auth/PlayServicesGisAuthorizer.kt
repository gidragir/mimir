package dev.mimir.android.auth

import android.accounts.Account
import android.content.Context
import android.content.SharedPreferences
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.common.api.Scope
import dev.mimir.drive.GoogleAuthConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class PlayServicesGisAuthorizer(
    private val context: Context,
    private val accountPrefs: SharedPreferences,
) : GisAuthorizer {
    companion object {
        const val PREF_GIS_ACCOUNT = "gis_account"
    }

    override suspend fun authorizeSilently(): String? = withContext(Dispatchers.IO) {
        val builder = AuthorizationRequest.builder()
            .setRequestedScopes(GoogleAuthConfig.scopes.map { Scope(it) })
        val accountName = accountPrefs.getString(PREF_GIS_ACCOUNT, null)
        if (!accountName.isNullOrEmpty()) {
            builder.setAccount(Account(accountName, "com.google"))
        }
        val request = builder.build()
        try {
            val result = Identity.getAuthorizationClient(context).authorize(request).await()
            if (result.hasResolution()) {
                null
            } else {
                result.accessToken
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResolvableApiException) {
            null
        } catch (e: ApiException) {
            if (e.status.hasResolution() || e.statusCode == CommonStatusCodes.SIGN_IN_REQUIRED) {
                null
            } else {
                throw e
            }
        }
    }

    override suspend fun clearToken(token: String) {
        withContext(Dispatchers.IO) {
            try {
                GoogleAuthUtil.clearToken(context, token)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    // GoogleSignIn устарел; отзыв доступа переводится на Identity API отдельной задачей.
    @Suppress("DEPRECATION")
    override suspend fun revoke() {
        withContext(Dispatchers.IO) {
            try {
                GoogleSignIn.getClient(context, GoogleSignInOptions.DEFAULT_SIGN_IN).revokeAccess().await()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            accountPrefs.edit().remove(PREF_GIS_ACCOUNT).apply()
        }
    }

    override fun isAvailable(): Boolean {
        return GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }
}
