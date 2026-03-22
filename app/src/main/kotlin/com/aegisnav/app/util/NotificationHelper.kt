package com.aegisnav.app.util

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Safe notification posting that checks POST_NOTIFICATIONS permission on Android 13+.
 * Returns true if the notification was posted, false if permission was missing.
 */
object NotificationHelper {
    fun notify(context: Context, id: Int, notification: Notification): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                AppLog.w("NotificationHelper", "POST_NOTIFICATIONS permission not granted, skipping notification $id")
                return false
            }
        }
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        nm.notify(id, notification)
        return true
    }
}
