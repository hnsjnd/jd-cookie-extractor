package com.example.jdcookie

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Cookie 失效提醒：顶部横幅文案 + 系统通知（渠道「京东CK提醒」）。
 * 不伪造解黑能力；仅根据本地 CK 检测 / 青龙禁用状态提醒。
 */
object CkInvalidAlert {

    const val CHANNEL_ID = "jd_ck_alert"
    private const val NOTIF_ID = 17001
    const val REQUEST_POST_NOTIFICATIONS = 1701

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.ck_invalid_notif_channel),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.ck_invalid_notif_channel_desc)
            enableVibration(true)
        }
        nm.createNotificationChannel(ch)
    }

    fun requestPermissionIfNeeded(activity: Activity) {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_POST_NOTIFICATIONS
        )
    }

    fun formatBanner(context: Context, invalidNames: List<String>): String {
        val list = invalidNames.joinToString("、")
        return context.getString(R.string.ck_invalid_banner_fmt, list)
    }

    /**
     * Show system notification if setting enabled and there is at least one invalid account.
     * Tap opens AccountListActivity.
     */
    fun notifyIfNeeded(context: Context, invalidAccounts: List<Account>) {
        if (invalidAccounts.isEmpty()) return
        val cfg = QingLongConfig(context).load()
        if (!cfg.notifyOnInvalid) return
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= 33) {
            val ok = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!ok) return
        }
        val names = invalidAccounts.joinToString("、") { it.displayName }
        val title = context.getString(R.string.ck_invalid_notif_title)
        val body = context.getString(
            R.string.ck_invalid_notif_body,
            invalidAccounts.size,
            names
        )
        val launch = Intent(context, AccountListActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_SHOW_INVALID, true)
        }
        val pi = PendingIntent.getActivity(
            context,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID, notif)
        } catch (_: SecurityException) {
            // permission revoked mid-flight
        }
    }

    /** Collect EXPIRED accounts from store snapshot. */
    fun expiredOf(accounts: List<Account>): List<Account> =
        accounts.filter { it.status == AccountStatus.EXPIRED }

    const val EXTRA_SHOW_INVALID = "show_invalid_banner"
}
