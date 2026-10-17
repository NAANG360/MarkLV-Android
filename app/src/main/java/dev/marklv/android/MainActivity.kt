package dev.marklv.android

import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.RecognitionListener
import android.speech.tts.TextToSpeech
import android.os.Handler
import android.os.Looper
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
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
    var apiKey by mutableStateOf("")
    var model by mutableStateOf("gemini-2.5-flash")
    init {
        apiKey = prefs.getString("key", "") ?: ""
        model = prefs.getString("model", "gemini-2.5-flash") ?: "gemini-2.5-flash"
        runCatching {
            val a = JSONArray(prefs.getString("history", "[]"))
            messages = (0 until a.length()).map { val o = a.getJSONObject(it); ChatMessage(o.getString("role"), o.getString("text")) }
        }
    }
    fun save() { prefs.edit().putString("key", apiKey.trim()).putString("model", model.trim()).apply() }
    fun clear() { messages = emptyList(); persist() }
    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || busy) return
        if (apiKey.isBlank()) { error = "Add your Gemini API key in Settings."; return }
        messages = messages + ChatMessage("user", prompt); busy = true; error = null; persist()
        executor.execute {
            try {
                val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/${model.trim()}:generateContent?key=" + java.net.URLEncoder.encode(apiKey.trim(), "UTF-8")
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 20000; readTimeout = 60000
                    setRequestProperty("Content-Type", "application/json"); doOutput = true
                }
                val history = JSONArray()
                messages.dropLast(1).takeLast(15).forEach { m -> history.put(JSONObject().put("role", m.role).put("parts", JSONArray().put(JSONObject().put("text", m.text)))) }
                history.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt))))
                val body = JSONObject().put("contents", history).put("generationConfig", JSONObject().put("temperature", 0.7).put("maxOutputTokens", 2048))
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val raw = stream.bufferedReader().use { it.readText() }; conn.disconnect()
                if (code !in 200..299) throw IllegalStateException(runCatching { JSONObject(raw).getJSONObject("error").getString("message") }.getOrDefault("HTTP $code"))
                val reply = JSONObject(raw).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                App.main.post { messages = messages + ChatMessage("model", reply); busy = false; persist() }
            } catch (e: Exception) { App.main.post { error = e.message ?: "Request failed"; busy = false } }
        }
    }
    private fun persist() {
        val a = JSONArray()
        messages.takeLast(100).forEach { a.put(JSONObject().put("role", it.role).put("text", it.text)) }
        prefs.edit().putString("history", a.toString()).apply()
    }
}

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault() }
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFFF9E45), secondary = Color(0xFF65D8D0), background = Color(0xFF101114), surface = Color(0xFF1A1C20))) { ChatScreen({ tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "mark") }) } }
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
                override fun onResults(results: Bundle?) {
                    input = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: input
                    recognizer.destroy()
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Column { Text("MARK / LV", fontWeight = FontWeight.Black); Text(if (vm.busy) "THINKING…" else "PERSONAL AI • ANDROID", style = MaterialTheme.typography.labelSmall, color = Color(0xFF65D8D0)) } }, actions = { IconButton(onClick = { settings = true }) { Icon(Icons.Default.Settings, "Settings") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF151619))) },
        bottomBar = { Row(Modifier.fillMaxWidth().background(Color(0xFF151619)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { speechPermission.launch(Manifest.permission.RECORD_AUDIO) }) { Icon(Icons.Default.Mic, "Speak", tint = Color(0xFF65D8D0)) }
            Box(Modifier.weight(1f).background(Color(0xFF25272C), RoundedCornerShape(22.dp)).padding(14.dp)) {
                if (input.isEmpty()) Text("Ask Mark anything…", color = Color.LightGray)
                BasicTextField(input, { input = it }, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White), maxLines = 5)
            }
            IconButton(enabled = !vm.busy, onClick = { val t = input; input = ""; vm.send(t) }) { Icon(Icons.Default.Send, "Send", tint = Color(0xFFFF9E45)) }
        } }, containerColor = Color(0xFF101114)) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            vm.error?.let { Text(it, color = Color(0xFFFF8A80), modifier = Modifier.padding(12.dp)) }
            if (vm.messages.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("◉", color = Color(0xFFFF9E45), style = MaterialTheme.typography.displayLarge); Text("Ready when you are.", style = MaterialTheme.typography.headlineSmall); Text("Voice in. Ideas out.", color = Color.LightGray); Text("Set a Gemini key in Settings.", color = Color.Gray) } }
            else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(vm.messages) { m ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.role == "user") Arrangement.End else Arrangement.Start) {
                        Column(Modifier.widthIn(max = 330.dp).background(if (m.role == "user") Color(0xFF30343B) else Color(0xFF1D2024), RoundedCornerShape(16.dp)).padding(12.dp)) {
                            Text(if (m.role == "user") "YOU" else "MARK", color = if (m.role == "user") Color.LightGray else Color(0xFFFF9E45), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp)); Text(m.text, color = Color.White)
                            if (m.role == "model") TextButton(onClick = { speak(m.text) }) { Icon(Icons.Default.VolumeUp, null); Spacer(Modifier.width(5.dp)); Text("Speak reply") }
                        }
                    }
                }
                if (vm.busy) item { Text("Mark is thinking…", color = Color(0xFF65D8D0)) }
            }
        }
    }
    if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text("Mark settings") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Gemini API key"); OutlinedTextField(vm.apiKey, { vm.apiKey = it }, singleLine = true, placeholder = { Text("Paste API key") })
            Text("Model ID"); OutlinedTextField(vm.model, { vm.model = it }, singleLine = true)
            Text("Key is stored in app-private preferences and sent to Google for chat.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { vm.save(); settings = false }) { Text("Save") } }, dismissButton = { TextButton(onClick = vm::clear) { Text("Clear history") } })
}
