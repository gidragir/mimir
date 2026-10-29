package dev.mimir.android

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.ui.platform.UriHandler

class CustomTabsUriHandler(private val context: Context) : UriHandler {
    override fun openUri(uri: String) {
        val parsed = Uri.parse(uri)
        try {
            val customTabsIntent = CustomTabsIntent.Builder().build()
            customTabsIntent.launchUrl(context, parsed)
        } catch (_: ActivityNotFoundException) {
            launchViewIntent(parsed)
        } catch (_: Exception) {
            launchViewIntent(parsed)
        }
    }

    private fun launchViewIntent(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(intent)
    }
}
