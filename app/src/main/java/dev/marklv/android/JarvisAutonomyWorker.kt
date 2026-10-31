package dev.marklv.android

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Background personal-assistant pass. It uses the user's locally stored profile and recent
 * conversation context to identify one genuinely useful next step. It may create a reminder
 * only when the user already stated a concrete time-bound commitment. It never sends messages,
 * purchases, calls people, or launches apps from the background.
 */
class JarvisAutonomyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("mark", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("autonomy_enabled", true)) return Result.success()

        val base = prefs.getString("base", "http://127.0.0.1:3001")?.trim().orEmpty()
        val model = prefs.getString("model", "auto")?.trim().takeUnless { it.isNullOrBlank() } ?: "auto"
        val encryptedKey = prefs.getString("key_enc", null)
        val key = if (encryptedKey != null) SecretStore.decrypt(encryptedKey) else prefs.getString("key", "").orEmpty()
        val encryptedFallback = prefs.getString("fallback_providers_enc", null)
        val fallbacks = if (encryptedFallback != null) SecretStore.decrypt(encryptedFallback) else prefs.getString("fallback_providers", "").orEmpty()
        if (base.isBlank() && fallbacks.isBlank()) return Result.success()

        val profile = runCatching {
            val memories = JSONArray(prefs.getString("memories", "[]"))
            (0 until memories.length()).mapNotNull { memories.optJSONObject(it)?.optString("fact")?.takeIf(String::isNotBlank) }.takeLast(40)
        }.getOrDefault(emptyList())
        val history = runCatching {
            val messages = JSONArray(prefs.getString("history", "[]"))
            (maxOf(0, messages.length() - 16) until messages.length()).mapNotNull { i ->
                val item = messages.optJSONObject(i) ?: return@mapNotNull null
                val role = if (item.optString("role") == "model") "Jarvis" else "User"
                "$role: ${item.optString("text").take(900)}"
            }
        }.getOrDefault(emptyList())

        val prompt = """You are Jarvis, the user's proactive personal assistant. Review the user's learned preferences and recent conversation. Find at most ONE useful, timely, low-risk thing that would genuinely benefit the user now. Do not invent facts, deadlines, obligations, or user intentions. Do not nag, create generic motivation, or repeat prior notifications. Only create a reminder if the user explicitly stated a concrete time-bound commitment in the supplied context and the reminder is still in the future. Never send communications, spend money, place calls, change security/privacy settings, delete data, or perform irreversible actions. Return ONLY JSON: {"notification":"short useful notification or empty string","reminder_text":"text or empty string","reminder_minutes":0}. reminder_minutes must be 1..43200 and only when a clear commitment and timing are explicit. If nothing useful is warranted, return {"notification":"","reminder_text":"","reminder_minutes":0}.
        
Learned preferences:
${profile.joinToString("\n") { "- $it" }}

Recent conversation:
${history.joinToString("\n")}
"""
        val request = JSONObject()
            .put("model", model)
            .put("temperature", 0.25)
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", "Return valid JSON only.")).put(JSONObject().put("role", "user").put("content", prompt)))
        val providers = mutableListOf<Provider>()
        val local = base == "http://127.0.0.1:3001" || base == "http://localhost:3001"
        if (base.isNotBlank() && (!local || EmbeddedFreeLLMRuntime.hasBundledRuntime(applicationContext))) {
            providers.add(Provider("primary", base, model, if (local) EmbeddedFreeLLMRuntime.unifiedApiKey(applicationContext) else key))
        }
        fallbacks.lineSequence().map(String::trim).filter { it.isNotBlank() && !it.startsWith("#") }.forEach { row ->
            val parts = row.split("|", limit = 4)
            if (parts.size == 4 && parts[1].startsWith("https://") && parts[2].isNotBlank()) providers.add(Provider(parts[0], parts[1], parts[2], parts[3]))
        }
        var response: String? = null
        for (provider in providers.distinctBy { it.base to it.model }) {
            try {
                val root = provider.base.trimEnd('/')
                val endpoint = when {
                    root.endsWith("/chat/completions") -> root
                    root.endsWith("/v1") -> "$root/chat/completions"
                    else -> "$root/v1/chat/completions"
                }
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 30000
                    setRequestProperty("Content-Type", "application/json")
                    if (provider.key.isNotBlank()) setRequestProperty("Authorization", "Bearer ${provider.key}")
                    doOutput = true
                }
                conn.outputStream.use { it.write(JSONObject(request.toString()).put("model", provider.model).toString().toByteArray()) }
                val code = conn.responseCode
                val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                conn.disconnect()
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                response = JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
                if (!response.isNullOrBlank()) break
            } catch (_: Exception) { }
        }
        if (response.isNullOrBlank()) return Result.retry()

        val result = runCatching { JSONObject(response.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()) }.getOrNull()
            ?: return Result.retry()
        val notification = result.optString("notification").trim().take(220)
        val reminderText = result.optString("reminder_text").trim().take(180)
        val minutes = result.optInt("reminder_minutes", 0)
        if (reminderText.isNotBlank() && minutes in 1..43200) {
            val id = (System.currentTimeMillis() and 0x7fffffff).toInt()
            val at = System.currentTimeMillis() + minutes * 60_000L
            val reminders = JSONArray(prefs.getString("reminders", "[]"))
            reminders.put(JSONObject().put("id", id).put("text", reminderText).put("trigger_at", at))
            prefs.edit().putString("reminders", reminders.toString()).apply()
            ReminderReceiver.schedule(applicationContext, id, reminderText, at)
        }
        if (notification.isNotBlank()) {
            val signature = notification.lowercase().hashCode().toString()
            if (prefs.getString("last_autonomy_notification", "") != signature) {
                showNotification(notification)
                prefs.edit().putString("last_autonomy_notification", signature).putLong("last_autonomy_notification_at", System.currentTimeMillis()).apply()
            }
        }
        return Result.success()
    }

    private fun showNotification(text: String) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Jarvis proactive assistance", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification) }
    }

    private data class Provider(val name: String, val base: String, val model: String, val key: String)

    companion object {
        private const val CHANNEL_ID = "jarvis_proactive"
        private const val NOTIFICATION_ID = 7301

        fun schedule(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val request = PeriodicWorkRequestBuilder<JarvisAutonomyWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInitialDelay(30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("jarvis-proactive-assistant", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

object JarvisAutonomy {
    fun schedule(context: Context) = JarvisAutonomyWorker.schedule(context)
}
