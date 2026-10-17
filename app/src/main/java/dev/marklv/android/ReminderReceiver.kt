package dev.marklv.android

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            restore(context)
            return
        }
        val text = intent.getStringExtra("text") ?: "Jarvis reminder"
        val id = intent.getIntExtra("id", (System.currentTimeMillis() and 0x7fffffff).toInt())
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Jarvis reminders", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Jarvis reminder")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
        removeSaved(context, id)
    }

    private fun restore(context: Context) {
        val prefs = context.getSharedPreferences("mark", Context.MODE_PRIVATE)
        val array = runCatching { JSONArray(prefs.getString("reminders", "[]")) }.getOrDefault(JSONArray())
        val now = System.currentTimeMillis()
        val future = JSONArray()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optInt("id")
            val text = item.optString("text", "Jarvis reminder")
            val at = item.optLong("trigger_at")
            if (at > now) {
                future.put(item)
                schedule(context, id, text, at)
            } else {
                showMissed(context, id, text)
            }
        }
        prefs.edit().putString("reminders", future.toString()).apply()
    }

    private fun showMissed(context: Context, id: Int, text: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Jarvis reminders", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Jarvis reminder")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    private fun removeSaved(context: Context, id: Int) {
        val prefs = context.getSharedPreferences("mark", Context.MODE_PRIVATE)
        val array = runCatching { JSONArray(prefs.getString("reminders", "[]")) }.getOrDefault(JSONArray())
        val updated = JSONArray()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (item.optInt("id") != id) updated.put(item)
        }
        prefs.edit().putString("reminders", updated.toString()).apply()
    }

    companion object {
        private const val CHANNEL_ID = "jarvis_reminders"

        fun schedule(context: Context, id: Int, text: String, triggerAt: Long) {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ReminderReceiver::class.java).putExtra("text", text).putExtra("id", id)
            val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }
}
