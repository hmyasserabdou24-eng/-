package org.darelihsan.parents

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Notification helpers. Texts are fixed here: the app never shows server-supplied text, so nothing sensitive can reach the lock screen. */
object Notify {
    const val CHANNEL_ID = "parents_general"
    const val EXTRA_ROUTE = "route"

    /** Allowed in-app destinations (keys understood by assets/inject.js). */
    val ROUTES = setOf("children", "programs", "reports", "appts", "msgs", "docs")

    fun textFor(kind: String?): String = when (kind) {
        "new_message" -> "لديك رسالة جديدة من إدارة دار الإحسان"
        "new_report", "new_evaluation" -> "تم نشر ملف جديد في فضاء الأولياء"
        else -> "لديك إشعار جديد من دار الإحسان"
    }

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val ch = NotificationChannel(CHANNEL_ID, ctx.getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH)
        ch.description = ctx.getString(R.string.channel_desc)
        ch.lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun show(ctx: Context, eventId: String, kind: String?, route: String?) {
        if (!canPost(ctx)) return
        ensureChannel(ctx)
        val safeRoute = if (route != null && route in ROUTES) route else "msgs"
        val open = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_ROUTE, safeRoute)
        val pi = PendingIntent.getActivity(
            ctx, safeRoute.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(textFor(kind))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        // Same tag+id => a re-delivered event replaces the previous notification instead of duplicating it.
        NotificationManagerCompat.from(ctx).notify(eventId.take(60), 1, n)
    }
}
