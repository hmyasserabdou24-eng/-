package org.darelihsan.parents

import android.content.Context
import java.util.UUID

/** Non-secret app state: install id (stable per installation) and the latest FCM token. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("app", Context.MODE_PRIVATE)

    val installId: String
        get() = p.getString("install_id", null) ?: UUID.randomUUID().toString().replace("-", "").also {
            p.edit().putString("install_id", it).apply()
        }

    var fcmToken: String?
        get() = p.getString("fcm_token", null)
        set(v) { p.edit().putString("fcm_token", v).apply() }

    var askedNotifPermission: Boolean
        get() = p.getBoolean("asked_notif", false)
        set(v) { p.edit().putBoolean("asked_notif", v).apply() }
}
