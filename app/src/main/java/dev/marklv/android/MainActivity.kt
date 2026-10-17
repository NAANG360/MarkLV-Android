package dev.marklv.android

import android.Manifest
import android.app.Application
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
    init {
        apiBase = prefs.getString("base", apiBase) ?: apiBase
        apiKey = prefs.getString("key", "") ?: ""
        model = prefs.getString("model", "auto") ?: "auto"
        runCatching {
            val a = JSONArray(prefs.getString("history", "[]"))
            messages = (0 until a.length()).map { val o = a.getJSONObject(it); ChatMessage(o.getString("role"), o.getString("text")) }
        }
    }
    fun save() { prefs.edit().putString("base", apiBase.trim()).putString("key", apiKey.trim()).putString("model", model.trim()).apply() }
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

    private fun requestApi(body: JSONObject): JSONObject {
        val base = apiBase.trim().trimEnd('/')
        val endpoint = if (base.endsWith("/v1")) "$base/chat/completions" else "$base/v1/chat/completions"
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 20000; readTimeout = 60000
            setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            doOutput = true
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val raw = stream.bufferedReader().use { it.readText() }; conn.disconnect()
        if (code !in 200..299) throw IllegalStateException(runCatching { JSONObject(raw).optString("error", raw) }.getOrDefault("HTTP $code"))
        return JSONObject(raw)
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
        private const val SYSTEM_PROMPT = """You are Jarvis, a personal Android assistant. Your directive is to be helpful, preserve the user's life and wellbeing, and follow the user's legitimate instructions. You are a conversational assistant, not Miss Minutes; do not claim access to other projects or devices unless a tool confirms it. You may call only the provided Android actions: open_app, open_url, search_web, click_text, type_text, scroll_screen, press_back. Accessibility actions work only when the user has explicitly enabled Jarvis accessibility in Android Settings. Never claim an action succeeded unless its tool result confirms it. Use cross-app interaction tools only when they clearly match the user request and the user has enabled Accessibility. Ask before actions with meaningful irreversible consequences. Do not invent capabilities. Prefer safe, clear, concise help."""
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
            Text("Requests use the OpenAI-compatible /v1/chat/completions API. Credentials stay in app-private preferences.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                Text("Enable Jarvis app interaction in Android Settings")
            }
        } },
        confirmButton = { TextButton(onClick = { vm.save(); settings = false }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { vm.clear() }) { Text("Clear history") } }
    )
}
