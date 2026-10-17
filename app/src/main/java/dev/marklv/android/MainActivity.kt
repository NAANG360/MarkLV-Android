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
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

data class ChatMessage(val role: String, val text: String)

class App : Application() {
    override fun onCreate() { super.onCreate(); instance = this }
    companion object { lateinit var instance: App; val main = Handler(Looper.getMainLooper()) }
}

class MarkViewModel : ViewModel() {
    private val prefs by lazy { App.instance.getSharedPreferences("mark", 0) }
    private val executor = Executors.newSingleThreadExecutor()
    var messages by mutableStateOf(listOf<ChatMessage>()); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var apiBase by mutableStateOf("https://your-freellmapi-host.example")
    var apiKey by mutableStateOf("")
    var model by mutableStateOf("auto")
    var fallbackProviders by mutableStateOf("")
    init {
        apiBase = prefs.getString("base", apiBase) ?: apiBase
        apiKey = prefs.getString("key", "") ?: ""
        model = prefs.getString("model", "auto") ?: "auto"
        fallbackProviders = prefs.getString("fallback_providers", "") ?: ""
        runCatching {
            val a = JSONArray(prefs.getString("history", "[]"))
            messages = (0 until a.length()).map { val o = a.getJSONObject(it); ChatMessage(o.getString("role"), o.getString("text")) }
        }
    }
    fun save() { prefs.edit().putString("base", apiBase.trim()).putString("key", apiKey.trim()).putString("model", model.trim()).putString("fallback_providers", fallbackProviders.trim()).apply() }
    fun clear() { messages = emptyList(); persist() }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || busy) return
        if (apiBase.isBlank() || apiBase.contains("your-freellmapi-host.example")) {
            error = "Open Settings and enter your FreeLLMAPI server URL first."; return
        }
        messages = messages + ChatMessage("user", prompt); busy = true; error = null; persist()
        executor.execute {
            try {
                val conversation = JSONArray()
                conversation.put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                messages.dropLast(1).takeLast(16).forEach { conversation.put(JSONObject().put("role", if (it.role == "model") "assistant" else it.role).put("content", it.text)) }
                conversation.put(JSONObject().put("role", "user").put("content", prompt))
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
                val request = JSONObject().put("model", model.trim().ifBlank { "auto" }).put("messages", conversation)
                    .put("tools", tools).put("tool_choice", "auto").put("temperature", 0.7)
                val first = requestApi(request)
                val message = first.getJSONObject("choices").getJSONObject(0).getJSONObject("message")
                val calls = message.optJSONArray("tool_calls")
                if (calls == null || calls.length() == 0) {
                    val answer = message.optString("content").ifBlank { "The model returned an empty reply." }
                    App.main.post { messages = messages + ChatMessage("model", answer); busy = false; persist() }
                } else {
                    conversation.put(message)
                    val results = mutableListOf<String>()
                    for (i in 0 until minOf(calls.length(), 4)) {
                        val call = calls.getJSONObject(i)
                        val fn = call.getJSONObject("function")
                        val args = JSONObject(fn.optString("arguments", "{}"))
                        val result = executeAction(fn.optString("name"), args)
                        results.add(result)
                        conversation.put(JSONObject().put("role","tool").put("tool_call_id",call.optString("id")).put("content",result))
                    }
                    request.put("messages", conversation)
                    request.remove("tools")
                    request.remove("tool_choice")
                    val final = requestApi(request).getJSONObject("choices").getJSONObject(0).getJSONObject("message").optString("content")
                    val answer = (if (results.isNotEmpty()) "Action results:\n" + results.joinToString("\n") + "\n\n" else "") + final
                    App.main.post { messages = messages + ChatMessage("model", answer.ifBlank { "Finished the requested action." }); busy = false; persist() }
                }
            } catch (e: Exception) { App.main.post { error = e.message ?: "Request failed"; busy = false } }
        }
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
        val providers = mutableListOf(ProviderConfig("primary", apiBase.trim(), model.trim().ifBlank { "auto" }, apiKey.trim()))
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
                val endpoint = when {
                    base.endsWith("/chat/completions") -> base
                    base.endsWith("/v1") -> "$base/chat/completions"
                    else -> "$base/v1/chat/completions"
                }
                val outgoing = JSONObject(body.toString()).put("model", provider.model)
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 12000; readTimeout = 45000
                    setRequestProperty("Content-Type", "application/json")
                    if (provider.key.isNotBlank()) setRequestProperty("Authorization", "Bearer ${provider.key}")
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
                    "Saved to Jarvis local memory."
                }
            }
            "recall_memory" -> {
                val q = args.optString("query").trim()
                val memories = JSONArray(prefs.getString("memories", "[]"))
                val found = (0 until memories.length()).map { memories.getJSONObject(it) }
                    .filter { it.optString("fact").contains(q, true) || q.split(Regex("\\s+")).any { token -> token.length > 2 && it.optString("fact").contains(token, true) } }
                    .takeLast(12).map { it.optString("fact") }
                if (found.isEmpty()) "No matching local memories found." else found.joinToString("\\n")
            }
            "create_reminder" -> {
                val text = args.optString("text").trim()
                val minutes = args.optInt("minutes", 0)
                if (text.isBlank() || minutes !in 1..43200) "Reminder needs text and a delay between 1 and 43200 minutes."
                else {
                    val alarm = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                    val id = (System.currentTimeMillis() and 0x7fffffff).toInt()
                    val intent = Intent(ctx, ReminderReceiver::class.java).putExtra("text", text).putExtra("id", id)
                    val pending = PendingIntent.getBroadcast(ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + minutes * 60_000L, pending)
                    "Reminder scheduled for $minutes minute(s) from now. Android notification permission may be needed to display it."
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
                    val value = (max * percent / 100.0).toInt()
                    audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, value, 0)
                    "Set media volume to approximately $percent%."
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
        messages.takeLast(100).forEach { a.put(JSONObject().put("role", it.role).put("text", it.text)) }
        prefs.edit().putString("history", a.toString()).apply()
    }

    companion object {
        private const val SYSTEM_PROMPT = """You are Jarvis, a personal Android assistant. Your directive is to be helpful, preserve the user's life and wellbeing, and follow the user's legitimate instructions. You are a conversational assistant, not Miss Minutes; do not claim access to other projects or devices unless a tool confirms it. You may call only the provided Android actions: open_app, open_url, search_web, click_text, type_text, scroll_screen, press_back, remember_fact, recall_memory, create_reminder, device_status, open_camera, open_maps, share_text, read_screen, open_setting, set_media_volume. Accessibility actions work only when the user has explicitly enabled Jarvis accessibility in Android Settings. Never claim an action succeeded unless its tool result confirms it. Use cross-app interaction tools only when they clearly match the user request and the user has enabled Accessibility. Ask before actions with meaningful irreversible consequences. Do not invent capabilities. Prefer safe, clear, concise help."""
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(speak: (String) -> Unit, vm: MarkViewModel = viewModel()) {
    var input by remember { mutableStateOf("") }
    var settings by remember { mutableStateOf(false) }
    val context = LocalContext.current
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
            Box(Modifier.weight(1f).background(Color(0xFF25272C), RoundedCornerShape(22.dp)).padding(14.dp)) {
                if (input.isEmpty()) Text("Ask Jarvis to do something…", color = Color.LightGray)
                BasicTextField(input, { input = it }, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White), maxLines = 5)
            }
            IconButton(enabled = !vm.busy, onClick = { val t = input; input = ""; vm.send(t) }) { Icon(Icons.Default.Send, "Send", tint = Color(0xFFFF9E45)) }
        } }, containerColor = Color(0xFF101114)
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            vm.error?.let { Text(it, color = Color(0xFFFF8A80), modifier = Modifier.padding(12.dp)) }
            if (vm.messages.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("◉", color = Color(0xFFFF9E45), style = MaterialTheme.typography.displayLarge)
                    Text("At your service.", style = MaterialTheme.typography.headlineSmall)
                    Text("Voice in. Actions out.", color = Color.LightGray)
                    Text("Configure FreeLLMAPI in Settings to begin.", color = Color.Gray)
                }
            } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(vm.messages) { m ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.role == "user") Arrangement.End else Arrangement.Start) {
                        Column(Modifier.widthIn(max = 330.dp).background(if (m.role == "user") Color(0xFF30343B) else Color(0xFF1D2024), RoundedCornerShape(16.dp)).padding(12.dp)) {
                            Text(if (m.role == "user") "YOU" else "JARVIS", color = if (m.role == "user") Color.LightGray else Color(0xFFFF9E45), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp)); Text(m.text, color = Color.White)
                            if (m.role == "model") TextButton(onClick = { speak(m.text) }) { Icon(Icons.Default.VolumeUp, null); Spacer(Modifier.width(5.dp)); Text("Speak reply") }
                        }
                    }
                }
                if (vm.busy) item { Text("Jarvis is working…", color = Color(0xFF65D8D0)) }
            }
        }
    }
    if (settings) AlertDialog(
        onDismissRequest = { settings = false },
        title = { Text("Jarvis settings") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("FreeLLMAPI base URL")
            OutlinedTextField(vm.apiBase, { vm.apiBase = it }, singleLine = true, placeholder = { Text("https://your-server.example") })
            Text("API key (if your server requires one)")
            OutlinedTextField(vm.apiKey, { vm.apiKey = it }, singleLine = true, placeholder = { Text("Optional") })
            Text("Model / routing name")
            OutlinedTextField(vm.model, { vm.model = it }, singleLine = true, placeholder = { Text("auto") })
            Text("Fallback routes (optional; one per line: name|https://provider.example/v1|model-id|API-key)")
            OutlinedTextField(vm.fallbackProviders, { vm.fallbackProviders = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp), minLines = 3, maxLines = 6, placeholder = { Text("groq|https://api.groq.com/openai/v1|your-model|key") })
            Text("Jarvis tries the primary FreeLLMAPI route first, then these OpenAI-compatible HTTPS routes in order. Each provider has its own key and model. This is the native mobile fallback layer; it does not run the upstream Node server.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                Text("Enable Jarvis app interaction in Android Settings")
            }
        } },
        confirmButton = { TextButton(onClick = { vm.save(); settings = false }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { vm.clear() }) { Text("Clear history") } }
    )
}
