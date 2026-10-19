package com.dubstudio.khpro

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var web: WebView
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val prefs by lazy { getSharedPreferences("dubstudio-mobile", MODE_PRIVATE) }
    private var videoUri: Uri? = null
    private var lastAudioFile: File? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pickVideoCode = 201
    private val pickSrtCode = 202

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(11, 17, 35)
        window.navigationBarColor = android.graphics.Color.rgb(11, 17, 35)
        web = WebView(this)
        web.setBackgroundColor(android.graphics.Color.rgb(11, 17, 35))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.settings.allowFileAccessFromFileURLs = false
        web.settings.allowUniversalAccessFromFileURLs = false
        web.settings.setSupportMultipleWindows(false)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith("file:///android_asset/")) return false
                if (request.url.scheme == "https") {
                    try { startActivity(Intent(Intent.ACTION_VIEW, request.url)) } catch (_: Exception) {}
                }
                return true
            }
        }
        web.addJavascriptInterface(MobileBridge(), "DubNative")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
        tts = TextToSpeech(this, this)
    }

    inner class MobileBridge {
        @JavascriptInterface
        fun call(action: String, data: String) {
            when (action) {
                "pickVideo" -> runOnUiThread { pick("video/*", pickVideoCode) }
                "pickSrt" -> runOnUiThread { pick("*/*", pickSrtCode) }
                "getSettings" -> send(action, true, JSONObject().put("url", prefs.getString("server", "") ?: ""))
                "setServer" -> {
                    val raw = JSONObject(data).optString("url").trim().trimEnd('/')
                    if (!isAllowedServer(raw)) send(action, false, error = "Enter HTTPS URL, or a private Wi-Fi HTTP address (192.168.x.x / 10.x.x.x / 172.16–31.x.x).")
                    else { prefs.edit().putString("server", raw).apply(); send(action, true, JSONObject().put("url", raw)) }
                }
                "preview" -> runOnUiThread {
                    val input = JSONObject(data)
                    if (!ttsReady) send(action, false, error = "Khmer Text-to-Speech engine is not ready on this phone")
                    else {
                        val text = input.optString("text")
                        if (text.isBlank()) send(action, false, error = "Subtitle is empty")
                        else {
                            val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "preview")
                            send(action, result == TextToSpeech.SUCCESS, error = "Unable to preview; install Khmer TTS voice on this phone")
                        }
                    }
                }
                "openVideo" -> runOnUiThread {
                    val u = videoUri
                    if (u == null) send(action, false, error = "Select a video first")
                    else try {
                        startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(u, "video/*"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) })
                        send(action, true)
                    } catch (_: ActivityNotFoundException) { send(action, false, error = "No video player installed") }
                }
                "saveSrt" -> background(action) {
                    val source = JSONObject(data).optString("srt")
                    if (source.isBlank()) throw IllegalArgumentException("SRT is empty")
                    val saved = saveText("DubStudio-KH-${System.currentTimeMillis()}.srt", source)
                    JSONObject().put("saved", saved)
                }
                "ping" -> background(action) { val conn = connect("GET", "/api/status"); JSONObject(readJson(conn)) }
                "transcribe" -> background(action) {
                    val uri = videoUri ?: throw IllegalArgumentException("Please import a video first")
                    require(length(uri) in 0..(200L*1024*1024)) { "Video size must be known and within 200 MB" }
                    val conn = connect("POST", "/api/transcribe", "multipart/form-data; boundary=$BOUNDARY")
                    multipart(conn, listOf(Part("video", displayName(uri), "video/mp4", { contentResolver.openInputStream(uri) ?: error("Cannot read video") }, length(uri))), action)
                    JSONObject(readJson(conn))
                }
                "translate" -> background(action) { postJson("/api/translate", data) }
                "recap" -> background(action) { postJson("/api/recap", data) }
                "audio" -> background(action) {
                    val conn = postJsonConnection("/api/audio", data)
                    val file = receiveFile(conn, "dubstudio-voice.wav", action)
                    lastAudioFile?.delete()
                    lastAudioFile = file
                    val saved = saveFromFile("DubStudio-Voice-${System.currentTimeMillis()}.wav", "audio/wav", file)
                    JSONObject().put("saved", saved).put("fileSize", file.length())
                }
                "export" -> background(action) {
                    val uri = videoUri ?: throw IllegalArgumentException("Please import a video first")
                    require(length(uri) in 0..(200L*1024*1024)) { "Video size must be known and within 200 MB" }
                    val speech = lastAudioFile ?: throw IllegalArgumentException("Generate audio before exporting video")
                    if (!speech.exists()) throw IllegalArgumentException("Audio is missing. Generate again")
                    val conn = connect("POST", "/api/export-video", "multipart/form-data; boundary=$BOUNDARY")
                    multipart(conn, listOf(
                        Part("video", displayName(uri), "video/mp4", { contentResolver.openInputStream(uri) ?: error("Cannot read video") }, length(uri)),
                        Part("audio", "dubbed-voice.wav", "audio/wav", { FileInputStream(speech) }, speech.length())
                    ), action)
                    val file = receiveFile(conn, "dubstudio-dubbed.mp4", action)
                    val saved = saveFromFile("DubStudio-KH-Pro-${System.currentTimeMillis()}.mp4", "video/mp4", file)
                    file.delete()
                    JSONObject().put("saved", saved)
                }
                else -> send(action, false, error = "Unknown action")
            }
        }
    }

    private fun pick(mime: String, requestCode: Int) {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = mime
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, requestCode)
        } catch (e: Exception) { send("pick", false, error = "Cannot open the file chooser: ${e.message}") }
    }

    @Deprecated("Deprecated in Android API, supported for minSdk 26")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == pickVideoCode) {
            videoUri = uri
            lastAudioFile?.delete(); lastAudioFile = null
            send("pickVideo", true, JSONObject().put("name", displayName(uri)).put("size", length(uri)))
        } else if (requestCode == pickSrtCode) {
            worker.execute {
                try {
                    val bytes = contentResolver.openInputStream(uri)?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (output.size() <= 2_000_000) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: error("Cannot open SRT")
                    require(bytes.size <= 2_000_000) { "SRT exceeds 2 MB" }
                    send("pickSrt", true, JSONObject().put("name", displayName(uri)).put("srt", String(bytes, StandardCharsets.UTF_8).removePrefix("\uFEFF")))
                } catch (e: Exception) { send("pickSrt", false, error = e.message ?: "Import failed") }
            }
        }
    }

    private fun length(uri: Uri): Long {
        return contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
        } ?: -1L
    }
    private fun displayName(uri: Uri): String {
        return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "video.mp4"
    }
    private fun isAllowedServer(raw: String): Boolean {
        val u = try { URL(raw) } catch (_: Exception) { return false }
        if (u.userInfo != null || u.query != null || u.ref != null || u.path !in listOf("", "/")) return false
        if (u.protocol == "https") return u.host.isNotBlank()
        if (u.protocol != "http") return false
        val host = u.host.lowercase(Locale.ROOT)
        return host == "localhost" || host == "127.0.0.1" || host.startsWith("192.168.") || host.startsWith("10.") ||
          Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(host)
    }
    private fun connect(method: String, endpoint: String, contentType: String? = null): HttpURLConnection {
        val base = prefs.getString("server", "") ?: ""
        require(isAllowedServer(base)) { "Set a backend server URL in Settings first" }
        val conn = (URL(base + endpoint).openConnection() as HttpURLConnection)
        conn.requestMethod = method
        conn.connectTimeout = 12000
        conn.readTimeout = 600000
        conn.useCaches = false
        conn.setRequestProperty("Accept", "application/json, */*")
        if (contentType != null) { conn.doOutput = true; conn.setRequestProperty("Content-Type", contentType) }
        return conn
    }
    private fun postJsonConnection(endpoint: String, body: String): HttpURLConnection {
        val conn = connect("POST", endpoint, "application/json; charset=utf-8")
        conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        return conn
    }
    private fun postJson(endpoint: String, body: String) = JSONObject(readJson(postJsonConnection(endpoint, body)))
    private fun readJson(conn: HttpURLConnection): String {
        try {
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("HTTP $code: ${friendlyError(raw)}")
            return raw
        } finally { conn.disconnect() }
    }
    private fun friendlyError(raw: String): String = try {
        val d = JSONObject(raw).opt("detail")
        if (d is String) d else d?.toString() ?: raw.take(240)
    } catch (_: Exception) { raw.take(240) }

    private data class Part(val field: String, val name: String, val type: String, val stream: () -> InputStream, val bytes: Long)
    private val BOUNDARY get() = "KHPro${UUID.randomUUID().toString().replace("-", "")}"
    private fun multipart(conn: HttpURLConnection, parts: List<Part>, action: String) {
        // Read the boundary from the Connection header to guarantee a matching delimiter.
        val boundary = conn.getRequestProperty("Content-Type")!!.substringAfter("boundary=")
        conn.setChunkedStreamingMode(64 * 1024)
        val total = parts.sumOf { maxOf(it.bytes, 0) }
        var uploaded = 0L
        conn.outputStream.buffered().use { out ->
            for (part in parts) {
                val safeName = part.name.replace('"', '_').replace('\r','_').replace('\n','_')
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"${part.field}\"; filename=\"$safeName\"\r\nContent-Type: ${part.type}\r\n\r\n".toByteArray())
                part.stream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        uploaded += n
                        if (total > 0) progress(action, "upload", (uploaded * 100 / total).toInt().coerceIn(0, 100), "Uploading media")
                    }
                }
                out.write("\r\n".toByteArray())
            }
            out.write("--$boundary--\r\n".toByteArray())
        }
        progress(action, "processing", -1, "Server is processing…")
    }
    private fun receiveFile(conn: HttpURLConnection, name: String, action: String): File {
        val file = File(cacheDir, "${System.currentTimeMillis()}-$name")
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val detail = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw IllegalStateException("HTTP $code: ${friendlyError(detail)}")
            }
            val length = conn.contentLengthLong
            var done = 0L
            conn.inputStream.use { input -> FileOutputStream(file).use { output ->
                val b = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(b)
                    if (n < 0) break
                    output.write(b, 0, n)
                    done += n
                    progress(action, "download", if (length > 0) (done * 100 / length).toInt().coerceIn(0, 100) else -1,
                        "Downloading result: ${done / 1048576} MB")
                }
            } }
            require(done > 0) { "The server returned an empty result" }
            return file
        } catch (e: Exception) { file.delete(); throw e }
        finally { conn.disconnect() }
    }
    private fun saveText(name: String, text: String): String {
        val file = File(cacheDir, "export-${System.currentTimeMillis()}.srt")
        file.writeText(text, Charsets.UTF_8)
        return try { saveFromFile(name, "application/x-subrip", file) } finally { file.delete() }
    }
    private fun saveFromFile(name: String, type: String, source: File): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, type)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DubStudioKHPro")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Cannot create Downloads file")
            try {
                resolver.openOutputStream(target)?.use { out -> source.inputStream().use { it.copyTo(out) } } ?: error("Cannot write Downloads file")
                resolver.update(target, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                return "Downloads/DubStudioKHPro/$name"
            } catch (e: Exception) { resolver.delete(target, null, null); throw e }
        }
        // Android 8/9: app-accessible external files, avoiding legacy storage permission.
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "DubStudioKHPro")
        require(dir.exists() || dir.mkdirs()) { "Cannot create output directory" }
        FileInputStream(source).use { input -> FileOutputStream(File(dir, name)).use { input.copyTo(it) } }
        return File(dir, name).absolutePath
    }
    private fun background(action: String, execute: () -> JSONObject) {
        if (!busy.compareAndSet(false, true)) { send(action, false, error = "Another operation is running"); return }
        progress(action, "processing", -1, "Working…")
        worker.execute {
            try { send(action, true, execute()) }
            catch (e: Exception) { send(action, false, error = e.message ?: "Operation failed") }
            finally { busy.set(false) }
        }
    }
    private fun progress(action: String, stage: String, percent: Int, label: String) {
        val payload = JSONObject().put("action", action).put("stage", stage).put("percent", percent).put("label", label)
        emit("__nativeProgress", payload)
    }
    private fun send(action: String, success: Boolean, data: JSONObject? = null, error: String? = null) {
        val payload = JSONObject().put("action", action).put("ok", success).put("data", data ?: JSONObject())
        if (!success) payload.put("error", error ?: "Unknown error")
        emit("__nativeResult", payload)
    }
    private fun emit(fn: String, payload: JSONObject) {
        runOnUiThread { web.evaluateJavascript("window.$fn && window.$fn(${payload});", null) }
    }
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.forLanguageTag("km-KH"))
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        }
        emit("__ttsReady", JSONObject().put("available", ttsReady))
    }
    override fun onDestroy() {
        tts?.stop(); tts?.shutdown(); worker.shutdownNow(); web.destroy()
        lastAudioFile?.delete()
        super.onDestroy()
    }
}
