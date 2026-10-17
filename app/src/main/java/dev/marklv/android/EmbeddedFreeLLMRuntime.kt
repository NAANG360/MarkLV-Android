package dev.marklv.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Starts the bundled FreeLLMAPI Node server in-process. No Termux, shell, Docker,
 * separate server app, or user-managed background process is required.
 */
object EmbeddedFreeLLMRuntime {
    private const val BASE_URL = "http://127.0.0.1:3001"
    private const val ASSET_ROOT = "freellmapi"
    @Volatile private var started = false
    @Volatile private var librariesLoaded = false
    @Volatile var startupError: String? = null
        private set
    private val bootstrapExecutor = Executors.newSingleThreadExecutor()

    private external fun startNodeWithArguments(arguments: Array<String>, environment: Array<String>): Int

    fun hasBundledRuntime(context: Context): Boolean = runCatching { context.assets.list(ASSET_ROOT)?.contains("server.mjs") == true }.getOrDefault(false)

    fun start(context: Context) {
        synchronized(this) {
            if (started) return
            val prefs = context.getSharedPreferences("mark", Context.MODE_PRIVATE)
            val root = File(context.filesDir, ASSET_ROOT)
            val server = File(root, "server.mjs")
            val appVersion = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionCode }.getOrDefault(1)
            val copiedVersion = prefs.getInt("freellmapi_assets_version", -1)
            if (!server.exists() || copiedVersion != appVersion) {
                runCatching {
                    copyAssets(context, ASSET_ROOT, root)
                    prefs.edit().putInt("freellmapi_assets_version", appVersion).apply()
                }.onFailure { startupError = "Bundled FreeLLMAPI assets could not be prepared: ${it.message}" }
            }
            if (!server.exists()) {
                startupError = startupError ?: "This build does not contain the bundled FreeLLMAPI runtime."
                return
            }
            try {
                if (!librariesLoaded) {
                    System.loadLibrary("node")
                    System.loadLibrary("jarvisnode")
                    librariesLoaded = true
                }
            } catch (e: Throwable) {
                startupError = "Embedded Node.js runtime could not load: ${e.message}"
                return
            }

            val dataDir = File(root, "data")
            val clientDir = File(root, "client/dist")
            val homeDir = File(root, "home")
            dataDir.mkdirs()
            clientDir.mkdirs()
            homeDir.mkdirs()
            val prefs = context.getSharedPreferences("mark", Context.MODE_PRIVATE)
            var encryptionKey = SecretStore.decrypt(prefs.getString("freellmapi_encryption_key_enc", "") ?: "")
            if (!encryptionKey.matches(Regex("[0-9a-fA-F]{64}"))) {
                val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
                encryptionKey = bytes.joinToString("") { "%02x".format(it) }
                prefs.edit().putString("freellmapi_encryption_key_enc", SecretStore.encrypt(encryptionKey)).apply()
            }

            var setupSecret = SecretStore.decrypt(prefs.getString("freellmapi_setup_secret_enc", "") ?: "")
            if (!setupSecret.matches(Regex("[0-9a-fA-F]{64}"))) {
                val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
                setupSecret = bytes.joinToString("") { "%02x".format(it) }
                prefs.edit().putString("freellmapi_setup_secret_enc", SecretStore.encrypt(setupSecret)).apply()
            }

            val environment = arrayOf(
                "JARVIS_EMBEDDED_SETUP_TOKEN=$setupSecret",
                "HOME=${homeDir.absolutePath}",
                "TMPDIR=${context.cacheDir.absolutePath}",
                "NODE_OPTIONS=--max-old-space-size=256",
                "NODE_ENV=production",
                "HOST=127.0.0.1",
                "PORT=3001",
                "ENCRYPTION_KEY=$encryptionKey",
                "FREEAPI_DB_PATH=${File(dataDir, "freeapi.db").absolutePath}",
                "CLIENT_DIST=${clientDir.absolutePath}",
                "FREEAPI_INSTALL_METHOD=jarvis-android"
            )
            started = true
            Thread({
                try {
                    val result = startNodeWithArguments(arrayOf("node", server.absolutePath), environment)
                    startupError = "Embedded FreeLLMAPI stopped unexpectedly (exit $result)."
                } catch (e: Throwable) {
                    startupError = "Embedded FreeLLMAPI failed: ${e.message}"
                }
            }, "Jarvis-FreeLLMAPI").apply { isDaemon = true; start() }
        }
    }

    private fun copyAssets(context: Context, assetPath: String, destination: File) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        if (children.isNotEmpty()) {
            destination.mkdirs()
            children.forEach { child -> copyAssets(context, "$assetPath/$child", File(destination, child)) }
        } else {
            destination.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    fun waitUntilReady(context: Context, timeoutMs: Long = 30000): Boolean {
        start(context)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (startupError != null) return false
            if (request("GET", "/livez", null, null, 1200) != null) return true
            SystemClock.sleep(350)
        }
        startupError = startupError ?: "Bundled FreeLLMAPI did not become ready within ${timeoutMs / 1000} seconds."
        return false
    }

    fun unifiedApiKey(context: Context): String {
        if (!waitUntilReady(context)) throw IllegalStateException(startupError ?: "FreeLLMAPI is unavailable.")
        val database = File(File(File(context.filesDir, ASSET_ROOT), "data"), "freeapi.db")
        var lastError: Throwable? = null
        repeat(12) {
            try {
                SQLiteDatabase.openDatabase(database.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("SELECT value FROM settings WHERE key = 'unified_api_key' LIMIT 1", null).use { cursor ->
                        if (cursor.moveToFirst()) return cursor.getString(0)
                    }
                }
            } catch (e: Throwable) {
                lastError = e
            }
            SystemClock.sleep(250)
        }
        throw IllegalStateException("Couldn't read the local FreeLLMAPI access key from its private database.", lastError)
    }

    fun ensureSession(context: Context): String {
        if (!waitUntilReady(context)) throw IllegalStateException(startupError ?: "FreeLLMAPI is unavailable.")
        val prefs = context.getSharedPreferences("mark", Context.MODE_PRIVATE)
        val existing = SecretStore.decrypt(prefs.getString("freellmapi_session_enc", "") ?: "")
        if (existing.isNotBlank() && request("GET", "/api/auth/me", existing, null) != null) return existing

        var email = SecretStore.decrypt(prefs.getString("freellmapi_email_enc", "") ?: "")
        var password = SecretStore.decrypt(prefs.getString("freellmapi_password_enc", "") ?: "")
        if (email.isNotBlank() && password.isNotBlank()) {
            val login = request("POST", "/api/auth/login", null, JSONObject().put("email", email).put("password", password))
            val token = login?.optString("token").orEmpty()
            if (token.isNotBlank()) {
                prefs.edit().putString("freellmapi_session_enc", SecretStore.encrypt(token)).apply()
                return token
            }
        }

        email = "jarvis-${UUID.randomUUID().toString().take(12)}@local.invalid"
        password = UUID.randomUUID().toString() + UUID.randomUUID().toString()
        val setupSecret = SecretStore.decrypt(prefs.getString("freellmapi_setup_secret_enc", "") ?: "")
        val setup = request("POST", "/api/auth/setup", null, JSONObject().put("email", email).put("password", password), setupToken = setupSecret)
        val token = setup?.optString("token").orEmpty()
        if (token.isBlank()) {
            throw IllegalStateException(setup?.optJSONObject("error")?.optString("message") ?: "Could not initialize the local provider dashboard.")
        }
        prefs.edit()
            .putString("freellmapi_email_enc", SecretStore.encrypt(email))
            .putString("freellmapi_password_enc", SecretStore.encrypt(password))
            .putString("freellmapi_session_enc", SecretStore.encrypt(token))
            .apply()
        return token
    }

    private fun request(method: String, path: String, token: String?, body: JSONObject?, timeoutMs: Int = 5000, setupToken: String? = null): JSONObject? {
        return runCatching {
            val connection = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }
                if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
                if (!setupToken.isNullOrBlank()) setRequestProperty("X-Jarvis-Embedded-Setup", setupToken)
            }
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            if (code !in 200..299) {
                if (raw.isNotBlank()) throw IllegalStateException(runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") ?: raw }.getOrDefault(raw))
                throw IllegalStateException("HTTP $code")
            }
            if (raw.isBlank()) JSONObject() else JSONObject(raw)
        }.getOrNull()
    }
}
