package com.daidubber.khmer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.*
import java.io.File
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private lateinit var status: TextView
    private lateinit var entries: LinearLayout
    private lateinit var voicePicker: Spinner
    private val subtitles = mutableListOf<EditText>()
    private var videoUri: Uri? = null
    private var khmerVoices = emptyList<android.speech.tts.Voice>()
    private var currentIndex = 0
    private var exportDir: File? = null
    private var exporting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 30, 24, 24)
            setBackgroundColor(Color.rgb(17, 19, 30))
        }
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
        fun heading(s: String, size: Float): TextView {
            val v = TextView(this).apply {
                text = s; textSize = size; setTextColor(Color.WHITE)
                setPadding(4, 12, 4, 12)
            }
            root.addView(v)
            return v
        }
        fun action(s: String, fn: () -> Unit) {
            root.addView(Button(this).apply { text = s; isAllCaps = false; setOnClickListener { fn() } })
        }
        heading("DAI Dubber Khmer", 26f)
        heading("SRT • Khmer TTS • Audio export (beta)", 15f)
        status = heading("Import an SRT subtitle file", 14f)
        action("Import Video") { choose(10, "video/*") }
        action("Import SRT") { choose(11, "*/*") }
        heading("Available Khmer voices", 16f)
        voicePicker = Spinner(this)
        root.addView(voicePicker)
        action("Preview First Subtitle") {
            val text = subtitles.firstOrNull()?.text?.toString().orEmpty()
            if (text.isNotBlank()) speak(text) else status.text = "Import SRT first"
        }
        action("Generate Audio Files (WAV)") { exportAudio() }
        action("Export MP4 — not implemented") {
            status.text = "MP4 muxing is not implemented yet. WAV files can be generated separately."
        }
        entries = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(entries)
        tts = TextToSpeech(this, this)
        updateVoicePicker()
    }

    private fun choose(code: Int, mime: String) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
        }, code)
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == 10) {
            videoUri = uri
            status.text = "Video selected (video export is not available yet)"
            return
        }
        if (requestCode != 11) return
        try {
            val raw = contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            val stamp = Regex("""\d{2}:\d{2}:\d{2}[,.]\d{3}\s*-->\s*\d{2}:\d{2}:\d{2}[,.]\d{3}""")
            val texts = raw.replace("\r", "").split(Regex("""\n\s*\n""")).mapNotNull { block ->
                val lines = block.lines()
                val index = lines.indexOfFirst { stamp.containsMatchIn(it) }
                if (index < 0) null else lines.drop(index + 1).joinToString(" ").trim().takeIf { it.isNotEmpty() }
            }
            subtitles.clear()
            entries.removeAllViews()
            texts.forEachIndexed { i, line ->
                val edit = EditText(this).apply {
                    setText(line)
                    setTextColor(Color.WHITE)
                    textSize = 15f
                }
                subtitles.add(edit)
                entries.addView(TextView(this).apply {
                    text = "Subtitle ${i + 1}"
                    setTextColor(Color.LTGRAY)
                })
                entries.addView(edit)
            }
            status.text = "Imported ${texts.size} subtitles"
        } catch (e: Exception) {
            status.text = "SRT import failed: ${e.message}"
        }
    }

    private fun updateVoicePicker() {
        val names = if (khmerVoices.isEmpty()) listOf("No Khmer voice installed")
            else khmerVoices.map { it.name + " (" + it.locale.toLanguageTag() + ")" }
        voicePicker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
    }

    private fun applyVoice(): Boolean {
        val engine = tts ?: return false
        if (khmerVoices.isEmpty()) {
            status.text = "Install a TTS engine with Khmer (km-KH) voices"
            return false
        }
        engine.voice = khmerVoices[voicePicker.selectedItemPosition.coerceIn(khmerVoices.indices)]
        return true
    }

    private fun speak(text: String) {
        if (!applyVoice()) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "preview")
    }

    private fun exportAudio() {
        if (exporting) return
        if (subtitles.isEmpty()) { status.text = "Import SRT first"; return }
        if (!applyVoice()) return
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "DAI-Dubber-${System.currentTimeMillis()}")
        if (!dir.mkdirs()) { status.text = "Cannot create audio folder"; return }
        exportDir = dir
        exporting = true
        currentIndex = 0
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                runOnUiThread { currentIndex++; synthesizeNext() }
            }
            @Deprecated("Deprecated in Android API")
            override fun onError(utteranceId: String?) {
                runOnUiThread { exporting = false; status.text = "TTS failed at subtitle ${currentIndex + 1}" }
            }
        })
        synthesizeNext()
    }

    private fun synthesizeNext() {
        if (!exporting) return
        if (currentIndex >= subtitles.size) {
            exporting = false
            status.text = "Audio files saved in app Music folder: ${exportDir?.absolutePath}. No MP4 created."
            return
        }
        val line = subtitles[currentIndex].text.toString()
        status.text = "Generating WAV ${currentIndex + 1} / ${subtitles.size}"
        val out = File(exportDir, "line_%04d.wav".format(Locale.US, currentIndex + 1))
        val code = tts?.synthesizeToFile(line, null, out, "line-$currentIndex")
        if (code != TextToSpeech.SUCCESS) {
            exporting = false
            status.text = "Could not generate audio for line ${currentIndex + 1}"
        }
    }

    override fun onInit(code: Int) {
        if (code != TextToSpeech.SUCCESS) {
            runOnUiThread { status.text = "Android TTS initialization failed" }
            return
        }
        val engine = tts ?: return
        engine.setLanguage(Locale.forLanguageTag("km-KH"))
        khmerVoices = engine.voices?.filter { it.locale.language == "km" }?.sortedBy { it.name } ?: emptyList()
        runOnUiThread { updateVoicePicker() }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
