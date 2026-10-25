package ai.dubstudio.khpro;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.MediaController;
import android.media.MediaPlayer;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Android UI for real Whisper → speaker scan → Khmer translation backend. */
public class MainActivity extends Activity {
    private static final int PICK_VIDEO = 1001;
    private static final int SAVE_ORIGINAL = 1002;
    private static final int SAVE_KHMER = 1003;
    private static final int PICK_VOICE_REFERENCE = 1004;
    private static final int SAVE_VOICE_WAV = 1005;
    private static final long POLL_DELAY_MS = 1700L;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Uri selectedVideo;
    private String selectedName = "video.mp4";
    private String jobId;
    private String server;
    private String authToken;
    private String lastStage = "";
    private JSONObject currentResult;
    private boolean busy = false;
    private boolean originalReady = false;
    private boolean khmerReady = false;
    private boolean voiceReady = false;
    private boolean voiceBusy = false;
    private String selectedCloneSpeaker = "";
    private MediaPlayer voicePlayer;
    private File cachedVoice;


    private VideoView videoPreview;
    private EditText backendUrl, apiToken;
    private CheckBox scanSpeakers, isolateVocals, voiceConsent;
    private Button chooseVideo, startButton, saveOriginal, saveKhmer, renameSpeakers;
    private Button selectVoiceRef, generateVoice, listenVoice, saveVoice, voiceStylesButton;
    private final Map<String, String> voiceStyles = new LinkedHashMap<>();
    private TextView fileName, progressText, scanStatus, originalStatus, khmerStatus, subtitlePreview, voiceStatus;
    private ProgressBar progress;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        videoPreview = findViewById(R.id.videoPreview);
        backendUrl = findViewById(R.id.backendUrl);
        apiToken = findViewById(R.id.apiToken);
        scanSpeakers = findViewById(R.id.scanSpeakers);
        isolateVocals = findViewById(R.id.isolateVocals);
        voiceConsent = findViewById(R.id.voiceConsent);
        selectVoiceRef = findViewById(R.id.selectVoiceRef);
        generateVoice = findViewById(R.id.generateVoice);
        voiceStylesButton = findViewById(R.id.voiceStyles);
        listenVoice = findViewById(R.id.listenVoice);
        saveVoice = findViewById(R.id.saveVoice);
        voiceStatus = findViewById(R.id.voiceStatus);
        chooseVideo = findViewById(R.id.chooseVideo);
        startButton = findViewById(R.id.startButton);
        saveOriginal = findViewById(R.id.saveOriginal);
        saveKhmer = findViewById(R.id.saveKhmer);
        renameSpeakers = findViewById(R.id.renameSpeakers);
        fileName = findViewById(R.id.fileName);
        progressText = findViewById(R.id.progressText);
        scanStatus = findViewById(R.id.scanStatus);
        originalStatus = findViewById(R.id.originalStatus);
        khmerStatus = findViewById(R.id.khmerStatus);
        subtitlePreview = findViewById(R.id.subtitlePreview);
        progress = findViewById(R.id.progress);

        MediaController controller = new MediaController(this);
        controller.setAnchorView(videoPreview);
        videoPreview.setMediaController(controller);
        chooseVideo.setOnClickListener(v -> selectVideo());
        startButton.setOnClickListener(v -> startProcessing());
        saveOriginal.setOnClickListener(v -> createSrtFile(false));
        saveKhmer.setOnClickListener(v -> createSrtFile(true));
        renameSpeakers.setOnClickListener(v -> showRenameDialog());
        selectVoiceRef.setOnClickListener(v -> chooseCloneSpeaker());
        generateVoice.setOnClickListener(v -> requestAIVoice());
        voiceStylesButton.setOnClickListener(v -> chooseVoiceStyle());
        listenVoice.setOnClickListener(v -> playAIVoice());
        saveVoice.setOnClickListener(v -> saveAIVoice());
        refreshButtons();
    }

    private void selectVideo() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("video/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_VIDEO);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == PICK_VIDEO) {
            selectedVideo = uri;
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Exception ignored) { }
            selectedName = displayName(uri);
            fileName.setText("✓ " + selectedName);
            videoPreview.setVideoURI(uri);
            resetJob();
        } else if (requestCode == SAVE_ORIGINAL || requestCode == SAVE_KHMER) {
            saveSrtToUri(uri, requestCode == SAVE_KHMER);
        } else if (requestCode == PICK_VOICE_REFERENCE) {
            uploadCloneReference(uri);
        } else if (requestCode == SAVE_VOICE_WAV) {
            writeVoiceToUri(uri);
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (col >= 0) return cursor.getString(col);
            }
        } catch (Exception ignored) { }
        return "video.mp4";
    }

    private void resetJob() {
        jobId = null;
        currentResult = null;
        originalReady = false;
        khmerReady = false;
        voiceReady = false;
        voiceStyles.clear();
        cachedVoice = null;
        voiceStatus.setText("VoxCPM2 not started");
        originalStatus.setText("Original SUB — Waiting");
        khmerStatus.setText("Translation + Khmer SRT — Waiting");
        scanStatus.setText("Scan Character — Waiting");
        subtitlePreview.setText("Original និង Khmer Subtitle នឹងបង្ហាញនៅទីនេះ។");
        progress.setProgress(0);
        progressText.setText("0% · Ready");
        refreshButtons();
    }

    private void startProcessing() {
        if (selectedVideo == null) { message("សូមជ្រើសរើសវីដេអូជាមុន។"); return; }
        if (busy) return;
        String endpoint = backendUrl.getText().toString().trim().replaceAll("/+$", "");
        String secret = apiToken.getText().toString().trim();
        if (!(endpoint.startsWith("https://") || endpoint.matches("http://10\\.0\\.2\\.2(:[0-9]+)?"))) {
            message("សូមបញ្ចូល HTTPS Backend URL (ឬ 10.0.2.2 សម្រាប់ Android Emulator)។");
            return;
        }
        if (secret.isEmpty()) { message("សូមបញ្ចូល API Token របស់អ្នក។"); return; }
        server = endpoint;
        authToken = secret;  // never persisted; only held in memory while the Activity exists
        resetJob();
        setBusy(true);
        progressText.setText("1% · Uploading video...");
        progress.setProgress(1);
        executor.execute(() -> {
            try {
                JSONObject response = uploadVideo(selectedVideo, selectedName, scanSpeakers.isChecked(), isolateVocals.isChecked());
                jobId = response.getString("job_id");
                ui.post(() -> {
                    progressText.setText("5% · Video uploaded. Starting AI...");
                    progress.setProgress(5);
                });
                ui.post(this::pollJob);
            } catch (Exception err) { onFailed("Upload failed: " + readableError(err)); }
        });
    }

    private void setBusy(boolean value) {
        busy = value;
        refreshButtons();
    }

    private void refreshButtons() {
        startButton.setEnabled(!busy && selectedVideo != null);
        chooseVideo.setEnabled(!busy);
        saveOriginal.setEnabled(originalReady);
        saveKhmer.setEnabled(khmerReady);
        renameSpeakers.setEnabled(currentResult != null);
        selectVoiceRef.setEnabled(!busy && !voiceBusy && currentResult != null);
        voiceStylesButton.setEnabled(!busy && !voiceBusy && currentResult != null);
        generateVoice.setEnabled(!busy && !voiceBusy && khmerReady);
        listenVoice.setEnabled(voiceReady && !voiceBusy);
        saveVoice.setEnabled(voiceReady && !voiceBusy);
    }

    private JSONObject uploadVideo(Uri video, String name, boolean scan, boolean demucs) throws Exception {
        String boundary = "----DubStudio" + UUID.randomUUID().toString().replace("-", "");
        HttpURLConnection c = open("/api/jobs", "POST");
        c.setConnectTimeout(25000);
        c.setReadTimeout(600000);
        c.setDoOutput(true);
        c.setChunkedStreamingMode(256 * 1024);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try {
            try (OutputStream out = c.getOutputStream()) {
                writeTextPart(out, boundary, "source_language", "auto");
                writeTextPart(out, boundary, "scan_speakers", scan ? "true" : "false");
                writeTextPart(out, boundary, "isolate_vocals", demucs ? "true" : "false");
                String safeName = name.replace("\"", "_").replace("\r", "_").replace("\n", "_");
                send(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"video\"; filename=\"" + safeName + "\"\r\nContent-Type: video/mp4\r\n\r\n");
                try (InputStream in = getContentResolver().openInputStream(video)) {
                    if (in == null) throw new IOException("Cannot open video");
                    byte[] buf = new byte[128 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
                send(out, "\r\n--" + boundary + "--\r\n");
            }
            return new JSONObject(readConnection(c));
        } finally { c.disconnect(); }
    }

    private void writeTextPart(OutputStream out, String boundary, String name, String value) throws IOException {
        send(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
    }

    private void send(OutputStream stream, String value) throws IOException {
        stream.write(value.getBytes(StandardCharsets.UTF_8));
    }

    private void pollJob() {
        if (jobId == null || !busy) return;
        String job = jobId;
        executor.execute(() -> {
            try {
                JSONObject data = getJson("/api/jobs/" + job);
                ui.post(() -> showJobStatus(data));
            } catch (Exception err) { onFailed("Status check failed: " + readableError(err)); }
        });
    }

    private void showJobStatus(JSONObject data) {
        if (!busy) return;
        String stage = data.optString("stage", "queued");
        lastStage = stage;
        int pc = data.optInt("percent", 0);
        String msg = data.optString("message", "Working...");
        progress.setProgress(pc);
        progressText.setText(pc + "% · " + msg);
        String warn = data.optString("warning", "");
        if (!warn.isEmpty()) scanStatus.setText("Scan Speakers: " + warn);
        else if (stage.equals("scan")) scanStatus.setText("Scan Speakers — Processing");
        else if (stage.equals("translate") || stage.equals("done"))
            scanStatus.setText(scanSpeakers.isChecked() ? "Scan Speakers — Check results" : "Scan Speakers — Skipped");
        if (pc >= 45 || data.optBoolean("original_ready")) originalStatus.setText("Original SUB — Ready ✓");
        if (stage.equals("translate")) khmerStatus.setText("Khmer Translation — " + pc + "% total");
        if (stage.equals("done")) khmerStatus.setText("Khmer SRT — Ready ✓");
        if (stage.equals("voice")) voiceStatus.setText("VoxCPM2: " + pc + "% · " + msg);
        if (stage.equals("voice_done") || stage.equals("voice_failed")) {
            voiceBusy = false;
            voiceReady = stage.equals("voice_done") && data.optBoolean("audio_ready", false);
            voiceStatus.setText(voiceReady ? "✓ AI voice WAV ready" : "AI Voice error: " + msg);
            setBusy(false);
            return;
        }
        if (stage.equals("done") || stage.equals("failed")) {
            originalReady = data.optBoolean("original_ready", stage.equals("done"));
            khmerReady = data.optBoolean("khmer_ready", stage.equals("done"));
            setBusy(false);
            if (stage.equals("failed")) khmerStatus.setText("Khmer Translation — Error (Original SRT may still be saved)");
            fetchResult();
        } else ui.postDelayed(this::pollJob, POLL_DELAY_MS);
    }

    private void fetchResult() {
        if (jobId == null) return;
        executor.execute(() -> {
            try {
                JSONObject result = getJson("/api/jobs/" + jobId + "/result");
                ui.post(() -> showResult(result));
            } catch (Exception err) {
                ui.post(() -> message("Subtitles not available yet: " + readableError(err)));
            }
        });
    }

    private void showResult(JSONObject result) {
        currentResult = result;
        JSONArray lines = result.optJSONArray("subtitles");
        StringBuilder preview = new StringBuilder();
        if (lines != null) {
            int count = Math.min(80, lines.length());
            JSONObject names = result.optJSONObject("speaker_names");
            for (int i = 0; i < count; i++) {
                JSONObject line = lines.optJSONObject(i);
                if (line == null) continue;
                String speaker = line.optString("speaker", "SPEAKER_01");
                if (names != null) speaker = names.optString(speaker, speaker);
                preview.append(String.format(Locale.US, "%02d  %s  [%.1fs – %.1fs]\n", i + 1, speaker,
                        line.optDouble("start"), line.optDouble("end")));
                preview.append(line.optString("original")).append("\n");
                String kh = line.optString("khmer", "").trim();
                preview.append(kh.isEmpty() ? "— មិនទាន់បកប្រែ —" : "ខ្មែរ: " + kh).append("\n\n");
            }
            if (lines.length() > count) preview.append("… More lines are included in the saved SRT.\n");
        }
        subtitlePreview.setText(preview.length() == 0 ? "No subtitles detected." : preview.toString());
        refreshButtons();
    }

    private void showRenameDialog() {
        JSONObject result = currentResult;
        if (result == null) return;
        JSONArray arr = result.optJSONArray("subtitles");
        if (arr == null) return;
        Map<String, String> mapping = new LinkedHashMap<>();
        JSONObject currentNames = result.optJSONObject("speaker_names");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject line = arr.optJSONObject(i);
            if (line == null) continue;
            String speaker = line.optString("speaker", "SPEAKER_01");
            mapping.put(speaker, currentNames == null ? speaker : currentNames.optString(speaker, speaker));
        }
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> row : mapping.entrySet())
            text.append(row.getKey()).append("=").append(row.getValue()).append("\n");
        final EditText input = new EditText(this);
        input.setText(text.toString());
        input.setTextSize(14);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        new AlertDialog.Builder(this)
                .setTitle("កែឈ្មោះ Speaker")
                .setMessage("ចូរប្តូរតែអក្សរនៅខាងស្តាំ '='។ ឧទាហរណ៍ SPEAKER_01=ព្រះឯក")
                .setView(input)
                .setPositiveButton("Save", (dialog, which) -> saveSpeakerMapping(input.getText().toString(), mapping))
                .setNegativeButton("Cancel", null).show();
    }

    private void saveSpeakerMapping(String edited, Map<String, String> originals) {
        JSONObject map = new JSONObject();
        try {
            for (String row : edited.split("\\n")) {
                if (row.trim().isEmpty()) continue;
                String[] kv = row.split("=", 2);
                if (kv.length != 2 || !originals.containsKey(kv[0].trim()) || kv[1].trim().isEmpty()) {
                    message("Invalid speaker line: " + row); return;
                }
                map.put(kv[0].trim(), kv[1].trim());
            }
        } catch (Exception err) { message(readableError(err)); return; }
        if (jobId == null) return;
        executor.execute(() -> {
            try {
                String response = request("/api/jobs/" + jobId + "/speakers", "POST", map.toString());
                ui.post(() -> { try { showResult(new JSONObject(response)); message("Speaker names saved"); }
                                catch (Exception err) { message(readableError(err)); } });
            } catch (Exception err) { ui.post(() -> message("Cannot save speaker names: " + readableError(err))); }
        });
    }

    private void chooseCloneSpeaker() {
        if (!voiceConsent.isChecked()) { message("Please confirm permission from the voice owner first"); return; }
        if (currentResult == null) { message("Generate subtitles first"); return; }
        JSONArray arr = currentResult.optJSONArray("subtitles");
        if (arr == null) return;
        ArrayList<String> speakers = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject row = arr.optJSONObject(i);
            if (row == null) continue;
            String speaker = row.optString("speaker", "SPEAKER_01");
            if (!speakers.contains(speaker)) speakers.add(speaker);
        }
        if (speakers.isEmpty()) { message("No speakers found"); return; }
        String[] names = speakers.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle("Select speaker for permitted voice sample")
                .setItems(names, (dialog, which) -> {
                    selectedCloneSpeaker = names[which];
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.setType("audio/*");
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(intent, PICK_VOICE_REFERENCE);
                }).show();
    }

    private void uploadCloneReference(Uri audio) {
        if (jobId == null || selectedCloneSpeaker.isEmpty() || !voiceConsent.isChecked()) {
            message("Permission and speaker are required"); return;
        }
        voiceStatus.setText("Uploading authorized voice sample for " + selectedCloneSpeaker + "...");
        executor.execute(() -> {
            HttpURLConnection c = null;
            try {
                String boundary = "----DubVoice" + UUID.randomUUID().toString().replace("-", "");
                c = open("/api/jobs/" + jobId + "/voice-reference", "POST");
                c.setDoOutput(true);
                c.setChunkedStreamingMode(65536);
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                try (OutputStream out = c.getOutputStream()) {
                    writeTextPart(out, boundary, "speaker", selectedCloneSpeaker);
                    writeTextPart(out, boundary, "consent", "true");
                    send(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"voice_sample.wav\"\r\nContent-Type: audio/wav\r\n\r\n");
                    try (InputStream in = getContentResolver().openInputStream(audio)) {
                        if (in == null) throw new IOException("Cannot read audio");
                        byte[] buffer = new byte[65536];
                        int count;
                        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                    }
                    send(out, "\r\n--" + boundary + "--\r\n");
                }
                readConnection(c);
                ui.post(() -> voiceStatus.setText("✓ Authorized reference saved for " + selectedCloneSpeaker));
            } catch (Exception err) {
                ui.post(() -> voiceStatus.setText("Reference upload error: " + readableError(err)));
            } finally { if (c != null) c.disconnect(); }
        });
    }

    private void chooseVoiceStyle() {
        if (currentResult == null) { message("Generate subtitles first"); return; }
        JSONArray arr = currentResult.optJSONArray("subtitles");
        if (arr == null) return;
        ArrayList<String> speakers = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject row = arr.optJSONObject(i);
            if (row == null) continue;
            String speaker = row.optString("speaker", "SPEAKER_01");
            if (!speakers.contains(speaker)) speakers.add(speaker);
        }
        String[] ids = speakers.toArray(new String[0]);
        if (ids.length == 0) return;
        new AlertDialog.Builder(this).setTitle("Choose speaker")
                .setItems(ids, (dialog, which) -> {
                    final String speaker = ids[which];
                    String[] styles = {"neutral", "male", "female"};
                    new AlertDialog.Builder(this).setTitle(speaker + " AI voice style")
                            .setSingleChoiceItems(styles, Math.max(0, java.util.Arrays.asList(styles).indexOf(voiceStyles.getOrDefault(speaker, "neutral"))),
                                    (d, position) -> {
                                        voiceStyles.put(speaker, styles[position]);
                                        voiceStatus.setText(speaker + " → " + styles[position] + " AI voice");
                                        d.dismiss();
                                    }).setNegativeButton("Cancel", null).show();
                }).show();
    }

    private void requestAIVoice() {
        if (jobId == null || !khmerReady || busy || voiceBusy) {
            message("Khmer subtitles must be complete first"); return;
        }
        voiceBusy = true;
        voiceReady = false;
        voiceStatus.setText("Preparing VoxCPM2 voice...");
        refreshButtons();
        executor.execute(() -> {
            try {
                request("/api/jobs/" + jobId + "/generate-voice", "POST", new JSONObject(voiceStyles).toString());
                ui.post(() -> { setBusy(true); pollJob(); });
            } catch (Exception err) {
                ui.post(() -> {
                    voiceBusy = false;
                    voiceStatus.setText("AI Voice: " + readableError(err));
                    refreshButtons();
                });
            }
        });
    }

    private void playAIVoice() {
        if (!voiceReady || jobId == null) return;
        voiceStatus.setText("Downloading AI Voice for preview...");
        executor.execute(() -> {
            try {
                byte[] wav = getBytes("/api/jobs/" + jobId + "/voice.wav");
                File file = new File(getCacheDir(), "preview_ai_voice.wav");
                try (FileOutputStream out = new FileOutputStream(file)) { out.write(wav); }
                cachedVoice = file;
                ui.post(() -> {
                    try {
                        if (voicePlayer != null) { voicePlayer.release(); voicePlayer = null; }
                        voicePlayer = new MediaPlayer();
                        voicePlayer.setDataSource(file.getAbsolutePath());
                        voicePlayer.prepare();
                        voicePlayer.start();
                        voiceStatus.setText("▶ Playing generated AI Voice");
                    } catch (Exception e) { voiceStatus.setText("Playback error: " + readableError(e)); }
                });
            } catch (Exception e) { ui.post(() -> voiceStatus.setText("Download error: " + readableError(e))); }
        });
    }

    private void saveAIVoice() {
        if (!voiceReady) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType("audio/wav");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_TITLE, "DubStudio_Khmer_AI_Voice.wav");
        startActivityForResult(intent, SAVE_VOICE_WAV);
    }

    private void writeVoiceToUri(Uri destination) {
        executor.execute(() -> {
            try {
                byte[] audio = getBytes("/api/jobs/" + jobId + "/voice.wav");
                try (OutputStream out = getContentResolver().openOutputStream(destination)) {
                    if (out == null) throw new IOException("Cannot open destination");
                    out.write(audio);
                }
                ui.post(() -> message("Saved AI Voice WAV ✓"));
            } catch (Exception e) { ui.post(() -> message("Saving voice failed: " + readableError(e))); }
        });
    }

    private void createSrtFile(boolean khmer) {
        if (jobId == null || (khmer && !khmerReady) || (!khmer && !originalReady)) {
            message("The subtitles are not ready yet"); return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType("text/plain");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_TITLE, khmer ? "DubStudio_Khmer.srt" : "DubStudio_Original.srt");
        startActivityForResult(intent, khmer ? SAVE_KHMER : SAVE_ORIGINAL);
    }

    private void saveSrtToUri(Uri destination, boolean khmer) {
        final String kind = khmer ? "km" : "original";
        executor.execute(() -> {
            try {
                byte[] content = getBytes("/api/jobs/" + jobId + "/srt?kind=" + kind);
                try (OutputStream out = getContentResolver().openOutputStream(destination)) {
                    if (out == null) throw new IOException("Cannot open destination");
                    out.write(content);
                }
                ui.post(() -> message(khmer ? "បានរក្សាទុក Khmer SRT ✓" : "បានរក្សាទុក Original SRT ✓"));
            } catch (Exception err) { ui.post(() -> message("Saving SRT failed: " + readableError(err))); }
        });
    }

    private JSONObject getJson(String path) throws Exception { return new JSONObject(new String(getBytes(path), StandardCharsets.UTF_8)); }

    private byte[] getBytes(String path) throws Exception {
        HttpURLConnection c = open(path, "GET");
        try {
            int status = c.getResponseCode();
            byte[] value = readFully(status >= 400 ? c.getErrorStream() : c.getInputStream());
            if (status >= 400) throw new IOException("HTTP " + status + ": " + new String(value, StandardCharsets.UTF_8));
            return value;
        } finally { c.disconnect(); }
    }

    private String request(String path, String method, String body) throws Exception {
        HttpURLConnection c = open(path, method);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try {
            try (OutputStream out = c.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
            return readConnection(c);
        } finally { c.disconnect(); }
    }

    private HttpURLConnection open(String path, String method) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(server + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Authorization", "Bearer " + authToken);
        c.setRequestProperty("Accept", "application/json, text/plain");
        return c;
    }

    private String readConnection(HttpURLConnection c) throws IOException {
        int status = c.getResponseCode();
        String text = new String(readFully(status >= 400 ? c.getErrorStream() : c.getInputStream()), StandardCharsets.UTF_8);
        if (status >= 400) throw new IOException("HTTP " + status + ": " + text);
        return text;
    }

    private byte[] readFully(InputStream stream) throws IOException {
        if (stream == null) return new byte[0];
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) >= 0) if (n > 0) out.write(b, 0, n);
            return out.toByteArray();
        }
    }

    private void onFailed(String msg) {
        ui.post(() -> {
            setBusy(false);
            progressText.setText("Error · " + msg);
            message(msg);
        });
    }

    private void message(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }

    private String readableError(Exception e) { return e.getMessage() == null ? e.toString() : e.getMessage(); }

    @Override protected void onDestroy() {
        busy = false;
        executor.shutdownNow();
        if (voicePlayer != null) { voicePlayer.release(); voicePlayer = null; }
        super.onDestroy();
    }
}
