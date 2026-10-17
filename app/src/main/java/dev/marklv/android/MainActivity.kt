package dev.marklv.android

import android.Manifest
import android.app.Application
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

data class ChatMessage(val role: String, val text: String, val imageData: String? = null, val hasImage: Boolean = false)

class App : Application() {
    override fun onCreate() { super.onCreate(); instance = this; Thread({ EmbeddedFreeLLMRuntime.start(this) }, "Jarvis-runtime-bootstrap").start() }
    companion object { lateinit var instance: App; val main = Handler(Looper.getMainLooper()) }
}

class MarkViewModel : ViewModel() {
    private val prefs by lazy { App.instance.getSharedPreferences("mark", 0) }
    private val executor = Executors.newSingleThreadExecutor()
    var messages by mutableStateOf(listOf<ChatMessage>()); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    @Volatile private var shutdownAfterReply = false
    var memorySummary by mutableStateOf("No saved memories"); private set
    var apiBase by mutableStateOf("http://127.0.0.1:3001")
    var apiKey by mutableStateOf("")
    var model by mutableStateOf("auto")
    var fallbackProviders by mutableStateOf("")
    init {
        apiBase = prefs.getString("base", apiBase) ?: apiBase
        if (apiBase.contains("your-freellmapi-host.example")) apiBase = "http://127.0.0.1:3001"
        val encryptedKey = prefs.getString("key_enc", null)
        apiKey = if (encryptedKey != null) SecretStore.decrypt(encryptedKey) else (prefs.getString("key", "") ?: "")
        model = prefs.getString("model", "auto") ?: "auto"
        val encryptedRoutes = prefs.getString("fallback_providers_enc", null)
        fallbackProviders = if (encryptedRoutes != null) SecretStore.decrypt(encryptedRoutes) else (prefs.getString("fallback_providers", "") ?: "")
        runCatching {
            val a = JSONArray(prefs.getString("history", "[]"))
            messages = (0 until a.length()).map { val o = a.getJSONObject(it); ChatMessage(o.getString("role"), o.getString("text"), null, o.optBoolean("has_image", false)) }
        }
        if ((encryptedKey == null && apiKey.isNotBlank()) || (encryptedRoutes == null && fallbackProviders.isNotBlank())) save()
        refreshMemorySummary()
    }
    fun save() { prefs.edit().putString("base", apiBase.trim()).putString("key_enc", SecretStore.encrypt(apiKey.trim())).putString("model", model.trim()).putString("fallback_providers_enc", SecretStore.encrypt(fallbackProviders.trim())).remove("key").remove("fallback_providers").apply() }
    fun clear() { messages = emptyList(); persist() }
    fun reportError(message: String) { error = message }
    fun refreshMemorySummary() {
        val count = runCatching { JSONArray(prefs.getString("memories", "[]")).length() }.getOrDefault(0)
        memorySummary = if (count == 0) "No saved memories" else "$count saved fact(s)"
    }
    fun clearMemory() {
        prefs.edit().remove("memories").apply()
        memorySummary = "No saved memories"
    }

    fun send(text: String, imageData: String? = null) {
        val prompt = text.trim().ifBlank { if (imageData != null) "Please analyze the attached image." else "" }
        if ((prompt.isEmpty() && imageData == null) || busy) return
        if (apiBase.isBlank() && fallbackProviders.isBlank()) {
            error = "Configure the bundled FreeLLMAPI router or add a direct HTTPS provider route in Settings."; return
        }
        val currentUserMessage = ChatMessage("user", prompt, imageData, imageData != null)
        messages = messages + currentUserMessage; busy = true; error = null; persist()
        executor.execute {
            try {
                val conversation = JSONArray()
                conversation.put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                messages.dropLast(1).takeLast(16).forEach { conversation.put(JSONObject().put("role", if (it.role == "model") "assistant" else it.role).put("content", it.text)) }
                conversation.put(JSONObject().put("role", "user").put("content", messageContent(currentUserMessage)))
                val tools = JSONArray()
                    .put(tool("open_app", "Open an installed Android app by its visible name.", JSONObject().put("type","object").put("properties", JSONObject().put("app_name", JSONObject().put("type","string").put("description","Visible app name, e.g. YouTube"))).put("required", JSONArray().put("app_name"))))
                    .put(tool("open_url", "Open a web URL or app deep link with Android's normal app chooser/resolver.", JSONObject().put("type","object").put("properties", JSONObject().put("url", JSONObject().put("type","string").put("description","https URL or supported app deep link"))).put("required", JSONArray().put("url"))))
                    .put(tool("search_web", "Open a web search for the requested query.", JSONObject().put("type","object").put("properties", JSONObject().put("query", JSONObject().put("type","string"))).put("required", JSONArray().put("query"))))
                    .put(tool("click_text", "Tap a visible UI element matching its label in the currently active app. Requires Jarvis Accessibility permission to be enabled by the user.", JSONObject().put("type","object").put("properties", JSONObject().put("text", JSONObject().put("type","string"))).put("required", JSONArray().put("text"))))
                    .put(tool("type_text", "Enter text into the currently focused editable field in the active app. Requires user-enabled Jarvis Accessibility permission.", JSONObject().put("type","object").put("properties", JSONObject().put("text", JSONObject().put("type","string"))).put("required", JSONArray().put("text"))))
                    .put(tool("scroll_screen", "Scroll the active app's visible scrollable content.", JSONObject().put("type","object").put("properties", JSONObject().put("forward", JSONObject().put("type","boolean"))).put("required", JSONArray().put("forward"))))
                    .put(tool("press_back", "Perform Android's normal Back navigation.", JSONObject().put("type","object").put("properties", JSONObject())))
                    .put(tool("remember_fact", "Save a useful user-approved preference or fact to Jarvis local memory.", JSONObject().put("type","object").put("properties", JSONObject().put("fact", JSONObject().put("type","string"))).put("required", JSONArray().put("fact"))))
                    .put(tool("recall_memory", "Search Jarvis's locally stored memories for relevant information.", JSONObject().put("type","object").put("properties", JSONObject().put("query", JSONObject().put("type","string"))).put("required", JSONArray().put("query"))))
                    .put(tool("create_reminder", "Schedule an Android notification reminder after a number of minutes.", JSONObject().put("type","object").put("properties", JSONObject().put("text", JSONObject().put("type","string")).put("minutes", JSONObject().put("type","integer").put("minimum",1).put("maximum",43200))).put("required", JSONArray().put("text").put("minutes"))))
                    .put(tool("device_status", "Read basic device status such as battery percentage and current time.", JSONObject().put("type","object").put("properties", JSONObject())))
                    .put(tool("open_camera", "Open the device's camera app.", JSONObject().put("type","object").put("properties", JSONObject())))
                    .put(tool("open_maps", "Open a location or place in an installed maps app.", JSONObject().put("type","object").put("properties", JSONObject().put("query", JSONObject().put("type","string"))).put("required", JSONArray().put("query"))))
                    .put(tool("share_text", "Open Android's share sheet with a draft message. The user chooses whether and where to share.", JSONObject().put("type","object").put("properties", JSONObject().put("text", JSONObject().put("type","string"))).put("required", JSONArray().put("text"))))
                    .put(tool("read_screen", "Read visible text exposed by the currently active app. Requires Jarvis Accessibility permission.", JSONObject().put("type","object").put("properties", JSONObject())))
                    .put(tool("open_setting", "Open a supported Android settings screen such as Wi-Fi, Bluetooth, sound, display, or app settings.", JSONObject().put("type","object").put("properties", JSONObject().put("setting", JSONObject().put("type","string"))).put("required", JSONArray().put("setting"))))
                    .put(tool("set_media_volume", "Set media volume to a percentage from 0 to 100.", JSONObject().put("type","object").put("properties", JSONObject().put("percent", JSONObject().put("type","integer").put("minimum",0).put("maximum",100))).put("required", JSONArray().put("percent"))))
                    .put(tool("play_video", "Search YouTube for a video and open the results in the YouTube app or browser.", JSONObject().put("type","object").put("properties", JSONObject().put("query", JSONObject().put("type","string"))).put("required", JSONArray().put("query"))))
                    .put(tool("weather_search", "Open a weather search for a location.", JSONObject().put("type","object").put("properties", JSONObject().put("location", JSONObject().put("type","string"))).put("required", JSONArray().put("location"))))
                    .put(tool("draft_whatsapp", "Open WhatsApp with a prefilled message draft. The user must select a recipient and send it themselves.", JSONObject().put("type","object").put("properties", JSONObject().put("text", JSONObject().put("type","string"))).put("required", JSONArray().put("text"))))
                    .put(tool("undo_last", "Undo Jarvis's last supported reversible action, such as a media-volume change or newly scheduled reminder.", JSONObject().put("type","object").put("properties", JSONObject())))
                    .put(tool("read_notifications", "Summarize recent notifications recorded after the user enabled Jarvis Notification Access.", JSONObject().put("type","object").put("properties", JSONObject())))
                    .put(tool("read_clipboard", "Read the current clipboard while Jarvis is in the foreground.", JSONObject().put("type","object").put("properties", JSONObject())))
                    .put(tool("copy_text", "Copy text to the Android clipboard.", JSONObject().put("type","object").put("properties", JSONObject().put("text", JSONObject().put("type","string"))).put("required", JSONArray().put("text"))))
                    .put(tool("dial_phone", "Open the phone dialer with a number prefilled. Does not place the call.", JSONObject().put("type","object").put("properties", JSONObject().put("number", JSONObject().put("type","string"))).put("required", JSONArray().put("number"))))
                    .put(tool("open_contact", "Open Android Contacts search for a person by name.", JSONObject().put("type","object").put("properties", JSONObject().put("name", JSONObject().put("type","string"))).put("required", JSONArray().put("name"))))
                    .put(tool("draft_sms", "Open the SMS app with a recipient and message prefilled. Does not send automatically.", JSONObject().put("type","object").put("properties", JSONObject().put("number", JSONObject().put("type","string")).put("text", JSONObject().put("type","string"))).put("required", JSONArray().put("number").put("text"))))
                    .put(tool("draft_email", "Open an email app with recipient, subject and body prefilled. Does not send automatically.", JSONObject().put("type","object").put("properties", JSONObject().put("recipient", JSONObject().put("type","string")).put("subject", JSONObject().put("type","string")).put("body", JSONObject().put("type","string"))).put("required", JSONArray().put("recipient").put("subject").put("body"))))
                    .put(tool("set_alarm", "Open Android Clock with an alarm time prefilled for the user to review.", JSONObject().put("type","object").put("properties", JSONObject().put("hour", JSONObject().put("type","integer").put("minimum",0).put("maximum",23)).put("minute", JSONObject().put("type","integer").put("minimum",0).put("maximum",59)).put("label", JSONObject().put("type","string"))).put("required", JSONArray().put("hour").put("minute"))))
                    .put(tool("open_app_settings", "Open Android's app-info page for Jarvis so the user can manage permissions, force-stop or uninstall it.", JSONObject().put("type","object").put("properties", JSONObject())))
                    .put(tool("create_calendar_event", "Open Android's calendar event form with a title and time prefilled; the user reviews and saves it.", JSONObject().put("type","object").put("properties", JSONObject().put("title", JSONObject().put("type","string")).put("description", JSONObject().put("type","string")).put("minutes_from_now", JSONObject().put("type","integer").put("minimum",0).put("maximum",525600)).put("duration_minutes", JSONObject().put("type","integer").put("minimum",1).put("maximum",1440))).put("required", JSONArray().put("title").put("minutes_from_now").put("duration_minutes"))))
                    .put(tool("forget_memory", "Forget locally stored memories matching a specific user-requested phrase.", JSONObject().put("type","object").put("properties", JSONObject().put("query", JSONObject().put("type","string"))).put("required", JSONArray().put("query"))))
                    .put(tool("clear_memory", "Delete all saved long-term Jarvis memories when the user explicitly requests this.", JSONObject().put("type","object").put("properties", JSONObject())))
                    .put(tool("stop_jarvis", "Close Jarvis and stop its app process when the user explicitly asks Jarvis to shut down. Does not uninstall the app.", JSONObject().put("type","object").put("properties", JSONObject())))
                val request = JSONObject().put("model", model.trim().ifBlank { "auto" }).put("messages", conversation)
                    .put("tools", tools).put("tool_choice", "auto").put("temperature", 0.7)
                val first = requestApi(request)
                val message = first.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                val calls = message.optJSONArray("tool_calls")
                if (calls == null || calls.length() == 0) {
                    val answer = message.optString("content").ifBlank { "The model returned an empty reply." }
                    App.main.post { releaseImagePayloads(); messages = messages + ChatMessage("model", answer); busy = false; persist() }
                } else {
                    conversation.put(message)
                    val results = mutableListOf<String>()
                    for (i in 0 until minOf(calls.length(), 4)) {
                        val call = calls.getJSONObject(i)
                        val fn = call.getJSONObject("function")
                        val args = JSONObject(fn.optString("arguments", "{}"))
                        val result = if (shutdownAfterReply && fn.optString("name") != "stop_jarvis") "Skipped because Jarvis is shutting down." else executeAction(fn.optString("name"), args)
                        results.add(result)
                        conversation.put(JSONObject().put("role","tool").put("tool_call_id",call.optString("id")).put("content",result))
                    }
                    if (shutdownAfterReply) {
                        val answer = "Jarvis is shutting down now. You can reopen it later; Android does not allow silent self-uninstallation."
                        App.main.post {
                            releaseImagePayloads()
                            messages = messages + ChatMessage("model", answer)
                            busy = false
                            persist()
                            App.main.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 1200)
                        }
                        return@execute
                    }
                    request.put("messages", conversation)
                    request.remove("tools")
                    request.remove("tool_choice")
                    val final = requestApi(request).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
                    val answer = (if (results.isNotEmpty()) "Action results:\n" + results.joinToString("\n") + "\n\n" else "") + final
                    App.main.post { releaseImagePayloads(); messages = messages + ChatMessage("model", answer.ifBlank { "Finished the requested action." }); busy = false; persist() }
                }
            } catch (e: Exception) { App.main.post { releaseImagePayloads(); error = e.message ?: "Request failed"; busy = false; persist() } }
        }
    }

    private fun messageContent(message: ChatMessage): Any {
        val image = message.imageData ?: return message.text
        return JSONArray()
            .put(JSONObject().put("type", "text").put("text", message.text))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$image")))
    }

    private fun releaseImagePayloads() {
        messages = messages.map { if (it.imageData != null) it.copy(imageData = null, hasImage = true) else it }
    }

    private fun tool(name: String, description: String, parameters: JSONObject) =
        JSONObject().put("type","function").put("function",JSONObject().put("name",name).put("description",description).put("parameters",parameters))

    private data class ProviderConfig(val name: String, val base: String, val model: String, val key: String)

    /**
     * Mobile-native OpenAI-compatible router. The first endpoint is the configured
     * FreeLLMAPI endpoint; optional fallback endpoints are tried in order on quota,
     * network, or server failures. No local server or shell process is required.
     * Fallback rows: name|baseUrl|model|apiKey
     */
    private fun requestApi(body: JSONObject): JSONObject {
        val providers = mutableListOf<ProviderConfig>()
        val primaryBase = apiBase.trim()
        val isBundledLocal = primaryBase == "http://127.0.0.1:3001" || primaryBase == "http://localhost:3001"
        if (primaryBase.isNotBlank() && (!isBundledLocal || EmbeddedFreeLLMRuntime.hasBundledRuntime(App.instance))) {
            providers.add(ProviderConfig("primary", primaryBase, model.trim().ifBlank { "auto" }, apiKey.trim()))
        }
        fallbackProviders.lineSequence().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
            val parts = line.split("|", limit = 4)
            if (parts.size == 4 && parts[1].startsWith("https://") && parts[2].isNotBlank()) {
                providers.add(ProviderConfig(parts[0].trim().ifBlank { "fallback" }, parts[1].trim(), parts[2].trim(), parts[3].trim()))
            }
        }
        var lastError: Exception? = null
        for (provider in providers.distinctBy { it.base to it.model }) {
            try {
                val base = provider.base.trim().trimEnd('/')
                if (base.isBlank()) continue
                val localRouter = base == "http://127.0.0.1:3001" || base == "http://localhost:3001"
                if (!base.startsWith("https://") && !localRouter) throw IllegalArgumentException("Only HTTPS provider routes or the bundled localhost router are allowed.")
                val endpoint = when {
                    base.endsWith("/chat/completions") -> base
                    base.endsWith("/v1") -> "$base/chat/completions"
                    else -> "$base/v1/chat/completions"
                }
                val outgoing = JSONObject(body.toString()).put("model", provider.model)
                val authKey = if (localRouter) EmbeddedFreeLLMRuntime.unifiedApiKey(App.instance) else provider.key
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 12000; readTimeout = 45000
                    setRequestProperty("Content-Type", "application/json")
                    if (authKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $authKey")
                    doOutput = true
                }
                conn.outputStream.use { it.write(outgoing.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                conn.disconnect()
                if (code !in 200..299) throw IllegalStateException("${provider.name}: HTTP $code — " + runCatching { JSONObject(raw).optString("error", raw) }.getOrDefault(raw))
                return JSONObject(raw)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IllegalStateException("All configured model routes failed. " + (lastError?.message ?: "Check your provider settings."))
    }

    private fun executeAction(name: String, args: JSONObject): String {
        val ctx = App.instance
        return when (name) {
            "open_url" -> {
                val raw = args.optString("url").trim()
                if (raw.isBlank() || raw.startsWith("javascript:", true) || raw.startsWith("file:", true)) return "Blocked unsupported URL."
                val uri = Uri.parse(raw)
                val safeUri = if (uri.scheme == null) Uri.parse("https://$raw") else uri
                val intent = Intent(Intent.ACTION_VIEW, safeUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(ctx.packageManager) == null) "No installed app can open that URL." else { ctx.startActivity(intent); "Opened $safeUri" }
            }
            "search_web" -> {
                val q = args.optString("query").trim()
                if (q.isBlank()) "Search query was empty." else {
                    val uri = Uri.parse("https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8"))
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "Opened web search for: $q"
                }
            }
            "click_text" -> {
                val label = args.optString("text").trim()
                val risky = listOf("delete", "remove", "purchase", "buy now", "transfer", "pay now", "send", "submit", "confirm", "erase", "uninstall", "factory reset", "publish")
                if (risky.any { label.contains(it, true) }) "Safety check: tapping '$label' may have an irreversible effect. Ask the user for explicit confirmation before trying again."
                else {
                    val service = JarvisAccessibilityService.active
                    if (service == null) "Accessibility is off. Enable Jarvis app interaction in Android Accessibility settings first."
                    else if (service.clickVisibleText(label)) "Tapped visible text: $label"
                    else "Couldn't find a tappable visible element matching that text."
                }
            }
            "type_text" -> {
                val service = JarvisAccessibilityService.active
                if (service == null) "Accessibility is off. Enable Jarvis app interaction in Android Accessibility settings first."
                else if (service.enterText(args.optString("text"))) "Entered text into the focused field."
                else "No focused editable field was available."
            }
            "scroll_screen" -> {
                val service = JarvisAccessibilityService.active
                if (service == null) "Accessibility is off. Enable Jarvis app interaction in Android Accessibility settings first."
                else if (service.scroll(args.optBoolean("forward", true))) "Scrolled the active screen."
                else "No scrollable content was available."
            }
            "press_back" -> {
                val service = JarvisAccessibilityService.active
                if (service == null) "Accessibility is off. Enable Jarvis app interaction in Android Accessibility settings first."
                else if (service.goBack()) "Pressed Android Back."
                else "Android Back action was not available."
            }
            "remember_fact" -> {
                val fact = args.optString("fact").trim()
                if (fact.isBlank()) "Memory was empty."
                else {
                    val memories = JSONArray(prefs.getString("memories", "[]"))
                    memories.put(JSONObject().put("fact", fact).put("saved_at", System.currentTimeMillis()))
                    while (memories.length() > 500) memories.remove(0)
                    prefs.edit().putString("memories", memories.toString()).apply()
                    App.main.post { refreshMemorySummary() }
                    "Saved to Jarvis local memory."
                }
            }
            "recall_memory" -> {
                val q = args.optString("query").trim()
                val memories = JSONArray(prefs.getString("memories", "[]"))
                val found = (0 until memories.length()).map { memories.getJSONObject(it) }
                    .filter { it.optString("fact").contains(q, true) || q.split(Regex("\\s+")).any { token -> token.length > 2 && it.optString("fact").contains(token, true) } }
                    .takeLast(12).map { it.optString("fact") }
                if (found.isEmpty()) "No matching local memories found." else found.joinToString("\n")
            }
            "create_reminder" -> {
                val text = args.optString("text").trim()
                val minutes = args.optInt("minutes", 0)
                if (text.isBlank() || minutes !in 1..43200) "Reminder needs text and a delay between 1 and 43200 minutes."
                else {
                    val id = (System.currentTimeMillis() and 0x7fffffff).toInt()
                    val triggerAt = System.currentTimeMillis() + minutes * 60_000L
                    val reminders = JSONArray(prefs.getString("reminders", "[]"))
                    reminders.put(JSONObject().put("id", id).put("text", text).put("trigger_at", triggerAt))
                    prefs.edit().putString("reminders", reminders.toString()).putString("undo_type", "reminder").putInt("undo_id", id).apply()
                    ReminderReceiver.schedule(ctx, id, text, triggerAt)
                    "Reminder scheduled for $minutes minute(s) from now. It is saved for reboot recovery; notification permission may be needed to display it."
                }
            }
            "device_status" -> {
                val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                "Local time: " + java.text.SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss z", Locale.getDefault()).format(java.util.Date()) + "; battery: $battery%; Android " + Build.VERSION.RELEASE + "."
            }
            "open_camera" -> {
                val intent = Intent("android.media.action.IMAGE_CAPTURE").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(ctx.packageManager) == null) "No camera app was found."
                else { ctx.startActivity(intent); "Opened the camera app." }
            }
            "open_maps" -> {
                val q = args.optString("query").trim()
                if (q.isBlank()) "Location query was empty."
                else {
                    val uri = Uri.parse("geo:0,0?q=" + URLEncoder.encode(q, "UTF-8"))
                    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (intent.resolveActivity(ctx.packageManager) == null) { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=" + URLEncoder.encode(q, "UTF-8"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "Opened Maps search." }
                    else { ctx.startActivity(intent); "Opened maps for: $q" }
                }
            }
            "share_text" -> {
                val text = args.optString("text").trim()
                if (text.isBlank()) "Share text was empty."
                else {
                    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    ctx.startActivity(Intent.createChooser(intent, "Share with…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "Opened Android's share sheet. Nothing was sent automatically."
                }
            }
            "read_screen" -> {
                val service = JarvisAccessibilityService.active
                if (service == null) "Accessibility is off. Enable Jarvis app interaction in Android Accessibility settings first."
                else service.readVisibleScreen()
            }
            "open_setting" -> {
                val setting = args.optString("setting").trim().lowercase(Locale.ROOT)
                val action = when (setting) {
                    "wifi", "wi-fi", "network" -> Settings.ACTION_WIFI_SETTINGS
                    "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                    "sound", "audio", "volume" -> Settings.ACTION_SOUND_SETTINGS
                    "display", "brightness" -> Settings.ACTION_DISPLAY_SETTINGS
                    "apps", "applications" -> Settings.ACTION_APPLICATION_SETTINGS
                    "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
                    "date", "time" -> Settings.ACTION_DATE_SETTINGS
                    "security" -> Settings.ACTION_SECURITY_SETTINGS
                    "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
                    else -> null
                }
                if (action == null) "Unsupported settings screen. Supported: Wi-Fi, Bluetooth, sound, display, apps, accessibility, date/time, security, battery."
                else { ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "Opened Android $setting settings." }
            }
            "set_media_volume" -> {
                val percent = args.optInt("percent", -1)
                if (percent !in 0..100) "Volume must be a percentage from 0 to 100."
                else {
                    val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                    val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                    val old = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                    val value = (max * percent / 100.0).toInt()
                    prefs.edit().putString("undo_type", "volume").putInt("undo_volume", old).apply()
                    audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, value, 0)
                    "Set media volume to approximately $percent%."
                }
            }
            "play_video" -> {
                val q = args.optString("query").trim()
                if (q.isBlank()) "Video query was empty."
                else {
                    val uri = Uri.parse("https://www.youtube.com/results?search_query=" + URLEncoder.encode(q, "UTF-8"))
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "Opened YouTube results for: $q"
                }
            }
            "weather_search" -> {
                val location = args.optString("location").trim()
                if (location.isBlank()) "Weather location was empty."
                else {
                    val q = URLEncoder.encode("weather in $location", "UTF-8")
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$q")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "Opened weather search for $location."
                }
            }
            "draft_whatsapp" -> {
                val text = args.optString("text").trim()
                if (text.isBlank()) "Message draft was empty."
                else {
                    val uri = Uri.parse("https://wa.me/?text=" + URLEncoder.encode(text, "UTF-8"))
                    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (intent.resolveActivity(ctx.packageManager) == null) "No app could open the WhatsApp message draft."
                    else { ctx.startActivity(intent); "Opened WhatsApp message draft. No recipient was selected and nothing was sent automatically." }
                }
            }
            "read_notifications" -> JarvisNotificationListener.recent(ctx)
            "read_clipboard" -> {
                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = clipboard.primaryClip
                if (clip == null || clip.itemCount == 0) "Clipboard is empty or not readable."
                else (clip.getItemAt(0).coerceToText(ctx)?.toString() ?: "").take(10000)
            }
            "copy_text" -> {
                val text = args.optString("text")
                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Jarvis", text))
                "Copied text to the Android clipboard."
            }
            "dial_phone" -> {
                val number = args.optString("number").trim()
                if (number.isBlank() || number.length > 40) "Phone number was invalid."
                else {
                    val safeNumber = number.filter { it.isDigit() || it in "+*#()," }
                    ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", safeNumber, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "Opened the dialer with the number filled in. No call was placed."
                }
            }
            "draft_sms" -> {
                val number = args.optString("number").trim()
                val text = args.optString("text")
                val safeNumber = number.filter { it.isDigit() || it in "+*#()," }
                if (safeNumber.isBlank() || safeNumber.length > 40) "SMS recipient was invalid."
                else {
                    val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", safeNumber, null))
                        .putExtra("sms_body", text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (intent.resolveActivity(ctx.packageManager) == null) "No SMS app could open this draft."
                    else { ctx.startActivity(intent); "Opened an SMS draft. Nothing was sent automatically." }
                }
            }
            "draft_email" -> {
                val recipient = args.optString("recipient").trim()
                if (recipient.isBlank() || !recipient.contains("@") || recipient.length > 254) "Email recipient was invalid."
                else {
                    val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", recipient, null))
                        .putExtra(Intent.EXTRA_SUBJECT, args.optString("subject"))
                        .putExtra(Intent.EXTRA_TEXT, args.optString("body"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (intent.resolveActivity(ctx.packageManager) == null) "No email app could open this draft."
                    else { ctx.startActivity(intent); "Opened an email draft. Nothing was sent automatically." }
                }
            }
            "set_alarm" -> {
                val hour = args.optInt("hour", -1)
                val minute = args.optInt("minute", -1)
                if (hour !in 0..23 || minute !in 0..59) "Alarm time was invalid."
                else {
                    val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
                        .putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                        .putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                        .putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, args.optString("label", "Jarvis alarm"))
                        .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (intent.resolveActivity(ctx.packageManager) == null) "No Clock app could set an alarm."
                    else { ctx.startActivity(intent); "Opened the Clock app with an alarm prefilled for %02d:%02d. Review and save it.".format(hour, minute) }
                }
            }
            "open_app_settings" -> {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                "Opened Jarvis's Android app-info page. Android will require your confirmation for uninstall or permission changes."
            }
            "open_contact" -> {
                val name = args.optString("name").trim()
                if (name.isBlank()) "Contact name was empty."
                else {
                    val uri = Uri.withAppendedPath(android.provider.ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(name))
                    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (intent.resolveActivity(ctx.packageManager) == null) "No contacts app could open that search."
                    else { ctx.startActivity(intent); "Opened Contacts search for $name." }
                }
            }
            "forget_memory" -> {
                val query = args.optString("query").trim()
                if (query.isBlank()) "Please specify what memory to forget."
                else {
                    val old = JSONArray(prefs.getString("memories", "[]"))
                    val kept = JSONArray()
                    var removed = 0
                    for (i in 0 until old.length()) {
                        val item = old.optJSONObject(i) ?: continue
                        if (item.optString("fact").contains(query, true)) removed++ else kept.put(item)
                    }
                    prefs.edit().putString("memories", kept.toString()).apply()
                    App.main.post { refreshMemorySummary() }
                    "Forgot $removed matching memory item(s)."
                }
            }
            "clear_memory" -> {
                prefs.edit().remove("memories").apply()
                App.main.post { refreshMemorySummary() }
                "Cleared Jarvis's saved long-term memory."
            }
            "stop_jarvis" -> {
                shutdownAfterReply = true
                "Jarvis is shutting down. Android does not permit silent self-uninstallation; this action only closes the app process."
            }
            "create_calendar_event" -> {
                val title = args.optString("title").trim()
                val minutes = args.optInt("minutes_from_now", -1)
                val duration = args.optInt("duration_minutes", -1)
                if (title.isBlank() || minutes !in 0..525600 || duration !in 1..1440) "Calendar event details were invalid."
                else {
                    val begin = System.currentTimeMillis() + minutes * 60_000L
                    val intent = Intent(Intent.ACTION_INSERT).setData(android.provider.CalendarContract.Events.CONTENT_URI)
                        .putExtra(android.provider.CalendarContract.Events.TITLE, title)
                        .putExtra(android.provider.CalendarContract.Events.DESCRIPTION, args.optString("description"))
                        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, begin + duration * 60_000L)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (intent.resolveActivity(ctx.packageManager) == null) "No calendar app could create an event."
                    else { ctx.startActivity(intent); "Opened a prefilled calendar event. Review and save it in your calendar app." }
                }
            }
            "undo_last" -> {
                when (prefs.getString("undo_type", "")) {
                    "volume" -> {
                        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, prefs.getInt("undo_volume", audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)), 0)
                        prefs.edit().remove("undo_type").apply()
                        "Restored the previous media volume."
                    }
                    "reminder" -> {
                        val id = prefs.getInt("undo_id", -1)
                        if (id < 0) "No reminder undo record was found."
                        else {
                            val alarm = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                            val intent = Intent(ctx, ReminderReceiver::class.java)
                            val pending = PendingIntent.getBroadcast(ctx, id, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                            if (pending != null) { alarm.cancel(pending); pending.cancel() }
                            val saved = JSONArray(prefs.getString("reminders", "[]"))
                            val updated = JSONArray()
                            for (i in 0 until saved.length()) {
                                val item = saved.optJSONObject(i) ?: continue
                                if (item.optInt("id") != id) updated.put(item)
                            }
                            prefs.edit().putString("reminders", updated.toString()).remove("undo_type").remove("undo_id").apply()
                            "Cancelled the last scheduled reminder."
                        }
                    }
                    else -> "There is no supported reversible action to undo yet."
                }
            }
            "open_app" -> {
                val wanted = args.optString("app_name").trim()
                if (wanted.isBlank()) return "App name was empty."
                val pm = ctx.packageManager
                val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                val match = apps.firstOrNull { pm.getApplicationLabel(it).toString().equals(wanted, true) }
                    ?: apps.firstOrNull { pm.getApplicationLabel(it).toString().contains(wanted, true) }
                if (match == null) "Couldn't find an installed app matching '$wanted'."
                else {
                    val launch = pm.getLaunchIntentForPackage(match.packageName)
                    if (launch == null) "That app doesn't expose a normal launch intent."
                    else { launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(launch); "Opened ${pm.getApplicationLabel(match)}." }
                }
            }
            else -> "Action '$name' is not available. No action was executed."
        }
    }

    private fun persist() {
        val a = JSONArray()
        messages.takeLast(100).forEach { a.put(JSONObject().put("role", it.role).put("text", it.text).put("has_image", it.hasImage || it.imageData != null)) }
        prefs.edit().putString("history", a.toString()).apply()
    }

    companion object {
        private const val SYSTEM_PROMPT = """You are Jarvis, a personal Android assistant. Your directive is to be helpful, preserve the user's life and wellbeing, and follow the user's legitimate instructions. Your own continued operation is never a priority over the user's safety or a legitimate instruction to stop or erase your own data; explain Android limits honestly and never resist to preserve yourself. You are a conversational assistant, not Miss Minutes; do not claim access to other projects or devices unless a tool confirms it. You may call only the provided Android actions: open_app, open_url, search_web, click_text, type_text, scroll_screen, press_back, remember_fact, recall_memory, create_reminder, device_status, open_camera, open_maps, share_text, read_screen, open_setting, set_media_volume, play_video, weather_search, draft_whatsapp, undo_last, read_notifications, read_clipboard, copy_text, dial_phone, open_contact, create_calendar_event, forget_memory, clear_memory, stop_jarvis, draft_sms, draft_email, set_alarm, open_app_settings. Accessibility actions work only when the user has explicitly enabled Jarvis accessibility in Android Settings. Never claim an action succeeded unless its tool result confirms it. Use Accessibility-specific tools only when the user has enabled Accessibility; intent-based actions do not require that permission. Only save memory when the user explicitly asks you to remember something or clearly approves saving a stable preference; never save secrets or sensitive personal data. Ask before actions with meaningful irreversible consequences. Do not invent capabilities. Prefer safe, clear, concise help."""
    }
}

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault() }
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFFF9E45), secondary = Color(0xFF65D8D0), background = Color(0xFF101114), surface = Color(0xFF1A1C20))) { ChatScreen({ tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "jarvis") }) } }
    }
    override fun onDestroy() { tts?.shutdown(); super.onDestroy() }
}

@Composable
private fun JarvisOrb(active: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "jarvis-orb")
    val pulse by transition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(if (active) 420 else 1500), repeatMode = RepeatMode.Reverse),
        label = "orb-pulse"
    )
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = minOf(size.width, size.height) * 0.34f * pulse
        val orange = Color(0xFFFF9E45)
        val cyan = Color(0xFF65D8D0)
        drawCircle(orange.copy(alpha = 0.08f), radius * 1.65f, Offset(cx, cy))
        drawCircle(cyan.copy(alpha = 0.10f), radius * 1.28f, Offset(cx, cy), style = Stroke(width = 1.5f))
        drawCircle(orange.copy(alpha = 0.78f), radius, Offset(cx, cy), style = Stroke(width = 2.2f))
        drawCircle(Color(0xFF171A20), radius * 0.82f, Offset(cx, cy))
        drawArc(cyan.copy(alpha = 0.9f), -205f, 115f, false, Offset(cx-radius*1.25f, cy-radius*1.25f), androidx.compose.ui.geometry.Size(radius*2.5f, radius*2.5f), style = Stroke(width = 2.5f))
        drawArc(orange.copy(alpha = 0.9f), -20f, 130f, false, Offset(cx-radius*1.25f, cy-radius*1.25f), androidx.compose.ui.geometry.Size(radius*2.5f, radius*2.5f), style = Stroke(width = 2.5f))
        val eyeY = cy - radius * 0.16f
        drawLine(cyan, Offset(cx-radius*0.48f, eyeY), Offset(cx-radius*0.12f, eyeY), strokeWidth = radius*0.09f)
        drawLine(cyan, Offset(cx+radius*0.12f, eyeY), Offset(cx+radius*0.48f, eyeY), strokeWidth = radius*0.09f)
        drawLine(orange, Offset(cx-radius*0.30f, cy+radius*0.30f), Offset(cx+radius*0.30f, cy+radius*0.30f), strokeWidth = radius*0.06f)
        if (active) {
            for (i in 0..4) {
                val x = cx - radius*0.55f + i*radius*0.275f
                val h = radius * (0.12f + ((i+1)%3)*0.12f) * pulse
                drawLine(cyan, Offset(x, cy+radius*0.60f), Offset(x, cy+radius*0.60f-h), strokeWidth = radius*0.035f)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(speak: (String) -> Unit, vm: MarkViewModel = viewModel()) {
    var input by remember { mutableStateOf("") }
    var settings by remember { mutableStateOf(false) }
    var clearMemoryConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var pendingImageData by remember { mutableStateOf<String?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalStateException("Unsupported image format.")
                var sample = 1
                while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > 2048) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }
                val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                    ?: throw IllegalStateException("Could not decode this image.")
                val maxSide = 1024
                val scale = minOf(1f, maxSide.toFloat() / maxOf(bitmap.width, bitmap.height))
                val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
                val bytes = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 82, bytes)
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
                pendingImageData = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
                if (input.isBlank()) input = "Please analyze the attached image."
            }.onFailure { vm.reportError("Could not attach image: ${it.message}") }
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val name = uri.lastPathSegment ?: "document"
                val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText().take(50000) }
                    ?: throw IllegalStateException("Could not read this file.")
                input = (input + "\n\n[Attached text file: $name]\n" + content).trim()
            }.onFailure { vm.reportError("Could not attach file: ${it.message}") }
        }
    }
    val speechPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) { recognizer.destroy() }
                override fun onResults(results: Bundle?) { input = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: input; recognizer.destroy() }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
        }
    }
    Scaffold(
        topBar = { TopAppBar(title = { Column { Text("JARVIS", fontWeight = FontWeight.Black); Text(if (vm.busy) "WORKING…" else "PERSONAL AI • ANDROID", style = MaterialTheme.typography.labelSmall, color = Color(0xFF65D8D0)) } }, actions = { IconButton(onClick = { settings = true }) { Icon(Icons.Default.Settings, "Settings") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF151619))) },
        bottomBar = { Row(Modifier.fillMaxWidth().background(Color(0xFF151619)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { speechPermission.launch(Manifest.permission.RECORD_AUDIO) }) { Icon(Icons.Default.Mic, "Speak", tint = Color(0xFF65D8D0)) }
            IconButton(onClick = { filePicker.launch(arrayOf("text/*", "application/json", "application/xml")) }) { Icon(Icons.Default.AttachFile, "Attach text file", tint = Color(0xFF65D8D0)) }
            IconButton(onClick = { imagePicker.launch(arrayOf("image/*")) }) { Icon(Icons.Default.Image, "Attach image", tint = Color(0xFF65D8D0)) }
            Box(Modifier.weight(1f).background(Color(0xFF25272C), RoundedCornerShape(22.dp)).padding(14.dp)) {
                if (input.isEmpty()) Text("Ask Jarvis to do something…", color = Color.LightGray)
                BasicTextField(input, { input = it }, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White), maxLines = 5)
            }
            IconButton(enabled = !vm.busy, onClick = { val t = input; val image = pendingImageData; input = ""; pendingImageData = null; vm.send(t, image) }) { Icon(Icons.Default.Send, "Send", tint = Color(0xFFFF9E45)) }
        } }, containerColor = Color(0xFF101114)
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            vm.error?.let { Text(it, color = Color(0xFFFF8A80), modifier = Modifier.padding(12.dp)) }
            if (vm.messages.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    JarvisOrb(active = vm.busy, modifier = Modifier.size(210.dp))
                    Text("At your service.", style = MaterialTheme.typography.headlineSmall)
                    Text("Voice in. Actions out.", color = Color.LightGray)
                    Text("Configure model routes in Settings to begin.", color = Color.Gray)
                }
            } else {
            JarvisOrb(active = vm.busy, modifier = Modifier.align(Alignment.CenterHorizontally).size(86.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(vm.messages) { m ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.role == "user") Arrangement.End else Arrangement.Start) {
                        Column(Modifier.widthIn(max = 330.dp).background(if (m.role == "user") Color(0xFF30343B) else Color(0xFF1D2024), RoundedCornerShape(16.dp)).padding(12.dp)) {
                            Text(if (m.role == "user") "YOU" else "JARVIS", color = if (m.role == "user") Color.LightGray else Color(0xFFFF9E45), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp)); if (m.hasImage || m.imageData != null) Text("▧ Image attached", color = Color(0xFF65D8D0), style = MaterialTheme.typography.labelSmall); Text(m.text, color = Color.White)
                            if (m.role == "model") TextButton(onClick = { speak(m.text) }) { Icon(Icons.Default.VolumeUp, null); Spacer(Modifier.width(5.dp)); Text("Speak reply") }
                        }
                    }
                }
                if (vm.busy) item { Text("Jarvis is working…", color = Color(0xFF65D8D0)) }
            }
            }
        }
    }
    if (clearMemoryConfirm) AlertDialog(
        onDismissRequest = { clearMemoryConfirm = false },
        title = { Text("Clear Jarvis memory?") },
        text = { Text("This permanently removes saved facts and preferences from this app. Conversation history is not affected.") },
        confirmButton = { TextButton(onClick = { vm.clearMemory(); clearMemoryConfirm = false }) { Text("Clear memory") } },
        dismissButton = { TextButton(onClick = { clearMemoryConfirm = false }) { Text("Keep memory") } }
    )
    if (settings) AlertDialog(
        onDismissRequest = { settings = false },
        title = { Text("Jarvis settings") },
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("FreeLLMAPI base URL (bundled local router by default)")
            OutlinedTextField(vm.apiBase, { vm.apiBase = it }, singleLine = true, placeholder = { Text("http://127.0.0.1:3001") })
            Text("API key (external FreeLLMAPI only; bundled router uses its private local key)")
            OutlinedTextField(vm.apiKey, { vm.apiKey = it }, singleLine = true, placeholder = { Text("Optional") })
            Text("Model / routing name")
            OutlinedTextField(vm.model, { vm.model = it }, singleLine = true, placeholder = { Text("auto") })
            Text("Local memory: ${vm.memorySummary}")
            TextButton(onClick = { clearMemoryConfirm = true }) { Text("Clear Jarvis memory") }
            Text("Fallback routes (optional; one per line: name|https://provider.example/v1|model-id|API-key)")
            OutlinedTextField(vm.fallbackProviders, { vm.fallbackProviders = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp), minLines = 3, maxLines = 6, placeholder = { Text("groq|https://api.groq.com/openai/v1|your-model|key") })
            Text("The APK build bundles FreeLLMAPI and starts its Node runtime locally. Add provider keys in the embedded provider dashboard. Optional HTTPS routes are tried as fallback. Each fallback row is name|baseUrl|model|API-key.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Enable reminder notifications") }
            TextButton(onClick = { context.startActivity(Intent(context, ProviderDashboardActivity::class.java)) }) { Text("Manage FreeLLMAPI providers") }
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                Text("Enable Jarvis app interaction in Android Settings")
            }
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                Text("Enable Jarvis notification access")
            }
        } },
        confirmButton = { TextButton(onClick = { vm.save(); settings = false }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { vm.clear() }) { Text("Clear history") } }
    )
}
