package dev.marklv.android

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

/** Stores a small private rolling summary only after the user enables Notification Access. */
class JarvisNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val prefs = getSharedPreferences("mark", MODE_PRIVATE)
        val previous = SecretStore.decrypt(prefs.getString("notification_history_enc", "") ?: "")
        val items = runCatching { JSONArray(previous.ifBlank { "[]" }) }.getOrDefault(JSONArray())
        items.put(JSONObject()
            .put("package", sbn.packageName)
            .put("title", title.take(300))
            .put("text", text.take(1200))
            .put("time", sbn.postTime))
        val bounded = JSONArray()
        val start = maxOf(0, items.length() - 50)
        for (i in start until items.length()) bounded.put(items.getJSONObject(i))
        prefs.edit().putString("notification_history_enc", SecretStore.encrypt(bounded.toString())).remove("notification_history").apply()
    }

    companion object {
        fun recent(context: android.content.Context): String {
            val prefs = context.getSharedPreferences("mark", MODE_PRIVATE)
            val saved = SecretStore.decrypt(prefs.getString("notification_history_enc", "") ?: "")
            val items = runCatching { JSONArray(saved.ifBlank { "[]" }) }.getOrDefault(JSONArray())
            if (items.length() == 0) return "No notification history is available. The user may need to enable Jarvis Notification Access in Android Settings."
            val start = maxOf(0, items.length() - 15)
            return (start until items.length()).map { i ->
                val item = items.getJSONObject(i)
                val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(item.optLong("time")))
                "[$time] ${item.optString("package")} — ${item.optString("title")}: ${item.optString("text")}"
            }.joinToString("\n")
        }
    }
}
