package dev.mimir.android.platform

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.mimir.ui.platform.StorageAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidStorageAccess(
    private val activity: Activity,
    private val isExternalManager: () -> Boolean = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }
    },
    private val notificationRequester: (() -> Unit)? = null,
) : StorageAccess, DefaultLifecycleObserver {

    private val _granted = MutableStateFlow(isExternalManager())
    override val granted: StateFlow<Boolean> = _granted.asStateFlow()

    init {
        (activity as? LifecycleOwner)?.lifecycle?.addObserver(this)
    }

    fun refresh() {
        _granted.value = isExternalManager()
    }

    override fun onResume(owner: LifecycleOwner) {
        refresh()
    }

    override fun requestAllFiles() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
            } catch (_: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                activity.startActivity(intent)
            }
        }
    }

    override fun requestNotifications() {
        if (notificationRequester != null) {
            notificationRequester.invoke()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATIONS_CODE,
                )
            }
        }
    }

    companion object {
        const val REQUEST_NOTIFICATIONS_CODE = 1001
    }
}
