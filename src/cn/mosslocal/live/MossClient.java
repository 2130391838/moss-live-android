package cn.mosslocal.live;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * MOSS-Live 实时通话客户端：直连 wss://api.mosi.cn/v1/realtime。
 *
 * 协议要点（与桌面版一致）：
 *  - attach_session -> session_attached -> session.update -> session.updated -> media.mode(tagged-v1)
 *  - 上行：16 kHz 单声道 PCM16，每 20 ms 一帧（320 采样 = 640 字节），帧首加 1 字节 tag 0x00
 *  - 下行：48 kHz 单声道 PCM16 原始字节（无前缀），播放完按块回 playback.chunk.completed
 *  - 打断：本地判定人声 / 服务端 response.audio.stop，都要回 playback.stopped
 */
public class MossClient {

    private static final String TAG = "MossClient";
    private static final String WS_URL =
            "wss://api.mosi.cn/v1/realtime?model=moss-live-1.0&user=";
    private static final String BINARY_PROTOCOL = "tagged-v1";

    private static final int MIC_RATE = 16000;
    private static final int MIC_FRAME_MS = 20;
    private static final int MIC_FRAME_SAMPLES = MIC_RATE * MIC_FRAME_MS / 1000; // 320
    private static final int PLAY_RATE = 48000;

    // 打断判定
    private static final float BARGE_MIN_THRESHOLD = 0.13f;
    private static final int BARGE_SUSTAIN_FRAMES = 12;   // 240 ms
    private static final long AUDIO_START_GRACE_MS = 400;

    public interface Listener {
        void onState(String state, String detail);
        void onTranscript(String who, String text, boolean done);
        void onError(String message);
        void onLevel(float mic, boolean playing);
        void onBargeIn(String source);
        void onClosed();
    }

    private final String apiKey;
    private final String personaName;
    private final String personaPrompt;
    private final String instructions;
    private final Listener listener;

    private OkHttpClient http;
    private WebSocket ws;
    private volatile boolean closed;
    private volatile boolean ready;         // 可以上行音频
    private volatile boolean muted;

    // 音频
    private AudioRecord record;
    private AudioTrack track;
    private AcousticEchoCanceler aec;
    private NoiseSuppressor ns;
    private Thread captureThread;
    private Thread playbackThread;

    // 播放队列
    private static final class Chunk {
        final byte[] pcm;
        final String chunkId;
        final String runId;
        final String turnId;
        Chunk(byte[] pcm, String chunkId, String runId, String turnId) {
            this.pcm = pcm; this.chunkId = chunkId; this.runId = runId; this.turnId = turnId;
        }
    }
    private final LinkedBlockingQueue<Chunk> playQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean playingNow = new AtomicBoolean(false);

    // 轮次跟踪
    private static final class Run {
        String turnId;
        boolean done;
        boolean completed;
        final Set<String> pending = new HashSet<>();
        long playedMs;
    }
    private final Map<String, Run> runs = new HashMap<>();
    private final Set<String> localStoppedRuns = new HashSet<>();
    private volatile String activeRunId;
    private volatile long audioStartedAt;
    private PendingInfo pendingInfo;

    private static final class PendingInfo {
        String chunkId, runId, turnId;
        PendingInfo(String c, String r, String t) { chunkId = c; runId = r; turnId = t; }
    }

    // 声学电平与打断
    private int playedChunks = 0;

    /**
     * 软件音量增益。上游下行的语音本身偏小（实测 RMS 约 -31 dBFS、峰值仅 -13 dBFS），
     * 手机用「通话音频」通道播放又比其他通道更收敛，所以默认给 3 倍增益，
     * 配合软限幅防止削波。用户可在通话界面用滑块在 1x ~ 6x 之间调整。
     */
    private volatile float gain = 3.0f;
    private short[] gainBuf = null;
    private float noiseFloor = 0.02f;
    private int bargeFrames;
    private volatile float lastMicLevel;
    private int bargeCount;
    private int gatedFrames;

    public MossClient(String apiKey, String personaName, String personaPrompt,
                      String instructions, Listener listener) {
        this.apiKey = apiKey;
        this.personaName = personaName;
        this.personaPrompt = personaPrompt;
        this.instructions = instructions;
        this.listener = listener;
    }

    public int getBargeCount() { return bargeCount; }
    public int getGatedFrames() { return gatedFrames; }

    // ------------------------------------------------------------ 连接

    public void connect() {
        closed = false;
        listener.onState("connecting", "");
        http = new OkHttpClient.Builder()
                .pingInterval(15, TimeUnit.SECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
        String user = "android-" + Long.toHexString(new Random().nextLong() & 0xffffffffL);
        Request req = new Request.Builder()
                .url(WS_URL + user)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("User-Agent", "MossLiveAndroid/1.0")
                .build();
        ws = http.newWebSocket(req, new SocketListener());
    }

    public void close() {
        closed = true;
        stopAudio();
        if (ws != null) {
            try { ws.close(1000, "client_end"); } catch (Exception ignored) { }
            ws = null;
        }
        if (http != null) {
            try { http.dispatcher().executorService().shutdown(); } catch (Exception ignored) { }
            try { http.connectionPool().evictAll(); } catch (Exception ignored) { }
            http = null;
        }
        listener.onClosed();
    }

    /** 设置软件增益（1.0 ~ 6.0 倍），立即生效。 */
    public void setGain(float value) {
        this.gain = Math.min(6f, Math.max(1f, value));
    }

    public float getGain() {
        return gain;
    }

    public void setMuted(boolean value) {
        muted = value;
        sendJson("{\"action\":\"mic_state\",\"enabled\":" + (!value) + "}");
    }

    /** 手动打断（按钮）：立刻停播并告诉上游听到哪儿了。 */
    public void interruptNow() {
        if (playQueue.isEmpty() && !playingNow.get()) return;
        interrupt("manual");
    }

    private final class SocketListener extends WebSocketListener {
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            Log.i(TAG, "ws open");
            sendRaw("{\"action\":\"attach_session\"}");
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            try {
                handleEvent(text);
            } catch (Exception e) {
                Log.w(TAG, "bad event: " + text, e);
            }
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            handleAudioChunk(bytes.toByteArray());
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            if (closed) return;
            Log.w(TAG, "ws failure", t);
            String msg;
            if (response != null && response.code() == 401) {
                msg = "API Key 无效（401），请检查密钥";
            } else if (response != null && response.code() > 0) {
                msg = "连接被拒绝（HTTP " + response.code() + "）";
            } else {
                msg = "网络连接失败：" + (t == null ? "unknown" : t.getMessage());
            }
            listener.onError(msg);
            listener.onState("failed", msg);
            close();
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            if (closed) return;
            closed = true;
            stopAudio();
            listener.onState("closed", "连接已关闭（" + code + "）");
            listener.onClosed();
        }
    }

    // ------------------------------------------------------------ 下行事件

    private void handleEvent(String raw) throws Exception {
        JSONObject msg = new JSONObject(raw);
        String action = msg.optString("action");
        JSONObject data = msg.optJSONObject("data");
        if (data == null) data = new JSONObject();

        switch (action) {
            case "session_attached": {
                JSONObject session = new JSONObject();
                JSONObject persona = new JSONObject();
                persona.put("name", personaName);
                persona.put("prompt", personaPrompt);
                session.put("persona", persona);
                session.put("instructions", instructions);
                JSONObject out = new JSONObject();
                out.put("action", "session.update");
                out.put("session", session);
                sendRaw(out.toString());
                listener.onState("attached", "");
                break;
            }
            case "session.updated": {
                String err = data.optString("error");
                if (!err.isEmpty()) {
                    listener.onError("会话配置失败：" + err);
                    listener.onState("failed", err);
                    return;
                }
                sendRaw("{\"action\":\"media.mode\",\"binary_protocol\":\"" + BINARY_PROTOCOL + "\"}");
                // 与网页版一致：会话就绪后要显式告诉后端麦克风已开启，否则上游不会开始听
                sendRaw("{\"action\":\"mic_state\",\"enabled\":" + (!muted) + "}");
                startAudio();
                ready = true;
                listener.onState("ready", "");
                break;
            }
            case "input.speech.started": {
                // 服务端 VAD 听到人声。必须复核：若是回声引起就不能掐断。
                long since = SystemClock.elapsedRealtime() - audioStartedAt;
                boolean loud = lastMicLevel > BARGE_MIN_THRESHOLD * 0.8f;
                if (playingNow.get() && since > AUDIO_START_GRACE_MS && loud) {
                    interrupt("server-vad");
                }
                listener.onState("listening", "");
                break;
            }
            case "input.speech.stopped":
                listener.onState("thinking", "");
                break;
            case "input.transcript.done": {
                String text = data.optString("text");
                if (!text.isEmpty()) listener.onTranscript("user", text, true);
                break;
            }
            case "output.transcript.updated": {
                String text = data.optString("text");
                if (!text.isEmpty()) listener.onTranscript("assistant", text, false);
                break;
            }
            case "output.transcript.done": {
                String text = data.optString("text");
                if (!text.isEmpty()) listener.onTranscript("assistant", text, true);
                break;
            }
            case "response.audio.started": {
                activeRunId = data.optString("run_id", null);
                audioStartedAt = SystemClock.elapsedRealtime();
                if (activeRunId != null) localStoppedRuns.remove(activeRunId);
                listener.onState("speaking", "");
                break;
            }
            case "response.audio.context": {
                pendingInfo = new PendingInfo(
                        data.optString("chunk_id", null),
                        data.optString("run_id", null),
                        data.optString("turn_id", null));
                break;
            }
            case "response.audio.done": {
                String runId = data.optString("run_id", null);
                Run run = runs.get(runId);
                if (run != null) {
                    run.done = true;
                    if (run.pending.isEmpty()) completeRun(runId, run);
                }
                break;
            }
            case "response.audio.stop": {
                String runId = data.optString("run_id", null);
                if (runId != null && localStoppedRuns.contains(runId)) {
                    runs.remove(runId);
                    listener.onState("listening", "");
                    break;
                }
                stopPlaybackNow();
                Run run = runs.get(runId);
                sendRaw("{\"action\":\"playback.stopped\",\"run_id\":" + q(runId)
                        + ",\"turn_id\":" + q(data.optString("turn_id", null))
                        + ",\"played_audio_ms\":" + (run == null ? 0 : run.playedMs) + "}");
                if (runId != null) runs.remove(runId);
                listener.onState("listening", "");
                break;
            }
            case "response.interrupted": {
                String runId = data.optString("run_id", null);
                if (runId != null) {
                    runs.remove(runId);
                    localStoppedRuns.remove(runId);
                }
                break;
            }
            case "response.ended": {
                String runId = data.optString("run_id", null);
                if (runId != null) localStoppedRuns.remove(runId);
                if (runId != null && runId.equals(activeRunId)) activeRunId = null;
                listener.onState("ready", "");
                break;
            }
            case "error": {
                JSONObject err = data.optJSONObject("error");
                String code = err == null ? "upstream_error" : err.optString("code", "upstream_error");
                String message = err == null ? "上游返回错误" : err.optString("message", "上游返回错误");
                boolean local = data.optBoolean("local", false);
                listener.onError(message + " (" + code + ")");
                if (!local) listener.onState("failed", message);
                break;
            }
            default:
                break;
        }
    }

    private void handleAudioChunk(byte[] pcm) {
        PendingInfo info = pendingInfo;
        pendingInfo = null;
        if (info != null && info.runId != null && localStoppedRuns.contains(info.runId)) {
            return; // 本轮已被本地打断，剩余音频直接丢弃
        }
        if (info != null && info.runId != null) {
            Run run = runs.get(info.runId);
            if (run == null) {
                run = new Run();
                run.turnId = info.turnId;
                runs.put(info.runId, run);
            }
            if (info.chunkId != null) run.pending.add(info.chunkId);
        }
        playQueue.offer(new Chunk(pcm, info == null ? null : info.chunkId,
                info == null ? null : info.runId, info == null ? null : info.turnId));
    }

    // ------------------------------------------------------------ 上行音频

    private void startAudio() {
        if (captureThread != null) return;
        try {
            setupPlayback();
            setupCapture();
        } catch (Exception e) {
            Log.e(TAG, "audio init failed", e);
            listener.onError("音频设备初始化失败：" + e.getMessage());
            listener.onState("failed", "音频设备初始化失败");
            return;
        }
        playbackThread = new Thread(this::playbackLoop, "moss-playback");
        playbackThread.start();
        captureThread = new Thread(this::captureLoop, "moss-capture");
        captureThread.start();
    }

    private void setupCapture() {
        int minBuf = AudioRecord.getMinBufferSize(MIC_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf, MIC_FRAME_SAMPLES * 2 * 8);
        record = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MIC_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IllegalStateException("麦克风不可用（可能被其他应用占用）");
        }
        int sessionId = record.getAudioSessionId();
        // 手机是一体的麦克风+喇叭，开启系统自带回声消除，效果比软件算法好得多
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(sessionId);
                if (aec != null) aec.setEnabled(true);
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(sessionId);
                if (ns != null) ns.setEnabled(true);
            }
        } catch (Exception e) {
            Log.w(TAG, "effect init failed", e);
        }
        record.startRecording();
        Log.i(TAG, "capture started aec=" + (aec != null && aec.getEnabled()));
    }

    private void setupPlayback() {
        int minBuf = AudioTrack.getMinBufferSize(PLAY_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf, PLAY_RATE / 50 * 2); // 约 50ms，降低打断延迟
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(PLAY_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        track = new AudioTrack(attrs, fmt, bufSize, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
        track.play();
        Log.i(TAG, "playback started buf=" + bufSize);
    }

    private void captureLoop() {
        // 每次只读一帧长度：AudioRecord 的阻塞读会自然按 20ms 节奏返回，
        // 一次读太多会在同一瞬间突发发送好几帧，打乱上游的 20ms 节奏判断。
        short[] tmp = new short[MIC_FRAME_SAMPLES];
        short[] frame = new short[MIC_FRAME_SAMPLES];
        int filled = 0;
        byte[] out = new byte[1 + MIC_FRAME_SAMPLES * 2];
        out[0] = 0x00;
        while (!closed) {
            int n = record.read(tmp, 0, tmp.length);
            if (n <= 0) {
                if (n < 0) Log.w(TAG, "read error " + n);
                continue;
            }
            for (int i = 0; i < n; i++) {
                frame[filled++] = tmp[i];
                if (filled == MIC_FRAME_SAMPLES) {
                    filled = 0;
                    handleMicFrame(frame, out);
                }
            }
        }
    }

    private void handleMicFrame(short[] frame, byte[] out) {
        float level = frameLevel(frame);
        lastMicLevel = level;
        boolean playing = playingNow.get() || !playQueue.isEmpty();

        if (!playing && level < 0.2f) {
            noiseFloor = noiseFloor * 0.95f + level * 0.05f;
        }
        float threshold = Math.max(BARGE_MIN_THRESHOLD, noiseFloor * 2.4f);

        boolean barge = false;
        if (playing && !muted) {
            if (level > threshold) {
                bargeFrames++;
                if (bargeFrames >= BARGE_SUSTAIN_FRAMES) {
                    barge = true;
                    bargeFrames = 0;
                }
            } else {
                bargeFrames = Math.max(0, bargeFrames - 2);
            }
        } else {
            bargeFrames = 0;
        }
        if (barge) {
            interrupt("local-vad");
            playing = false;
        }

        if (!ready || ws == null) return;

        // 静音 / 回声守护：播放期间麦克风里只有漏回来的声音时，上行送等长静音，
        // 不然服务端会把它自己的声音当成「用户开口」，说着说着就自己停下等你说。
        boolean gate = muted || (playing && level <= threshold);
        int idx = 1;
        if (gate) {
            gatedFrames++;
            Arrays.fill(out, 1, out.length, (byte) 0);
        } else {
            for (int i = 0; i < frame.length; i++) {
                short s = frame[i];
                out[idx++] = (byte) (s & 0xff);
                out[idx++] = (byte) ((s >> 8) & 0xff);
            }
        }
        try {
            ws.send(ByteString.of(out));
        } catch (Exception ignored) { }
        listener.onLevel(level, playing);
    }

    /** 与桌面版同口径：min(1,(rms/0.18)^0.7)。 */
    private static float frameLevel(short[] frame) {
        double sum = 0;
        for (short s : frame) {
            double v = s / 32768.0;
            sum += v * v;
        }
        double rms = Math.sqrt(sum / frame.length);
        double v = Math.pow(Math.min(1.0, rms / 0.18), 0.7);
        return (float) v;
    }

    // ------------------------------------------------------------ 播放

    private void playbackLoop() {
        while (!closed) {
            Chunk chunk;
            try {
                chunk = playQueue.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (chunk == null) {
                playingNow.set(false);
                continue;
            }
            playingNow.set(true);
            try {
                short[] out = applyGain(chunk.pcm);
                int wrote = track.write(out, 0, out.length);
                if (playedChunks == 0) {
                    Log.i(TAG, "first chunk played bytes=" + wrote + " gain=" + gain);
                }
                playedChunks++;
            } catch (Exception e) {
                Log.w(TAG, "write failed", e);
            }
            if (playingNow.get()) {
                onChunkPlayed(chunk);
            }
        }
    }

    /**
     * 逐样本乘增益，再用软限幅把超过 0.8 的部分压回去（tanh 曲线），
     * 这样 3 倍增益也不会出现硬削波的破音。
     * 入参是下行的原始小端 PCM16 字节流。
     */
    private short[] applyGain(byte[] pcmBytes) {
        int n = pcmBytes.length / 2;
        if (gainBuf == null || gainBuf.length != n) gainBuf = new short[n];
        float g = gain;
        for (int i = 0; i < n; i++) {
            int lo = pcmBytes[i * 2] & 0xff;
            int hi = pcmBytes[i * 2 + 1];
            int s = (hi << 8) | lo;          // 小端 s16
            float x = (s / 32768f) * g;
            float a = Math.abs(x);
            if (a > 0.8f) {
                float y = 0.8f + 0.2f * (float) Math.tanh((a - 0.8f) / 0.2f);
                x = x < 0 ? -y : y;
            }
            int v = Math.round(x * 32767f);
            if (v > 32767) v = 32767;
            if (v < -32768) v = -32768;
            gainBuf[i] = (short) v;
        }
        return gainBuf;
    }

    private void onChunkPlayed(Chunk chunk) {
        long ms = chunk.pcm.length / 96L; // 48 kHz * 2 字节 = 96 字节/ms
        if (chunk.runId != null) {
            Run run = runs.get(chunk.runId);
            if (run != null) {
                run.playedMs += ms;
                if (chunk.chunkId != null) run.pending.remove(chunk.chunkId);
                if (run.done && run.pending.isEmpty()) completeRun(chunk.runId, run);
            }
        }
        if (chunk.chunkId != null) {
            sendRaw("{\"action\":\"playback.chunk.completed\",\"chunk_id\":" + q(chunk.chunkId)
                    + ",\"run_id\":" + q(chunk.runId) + ",\"turn_id\":" + q(chunk.turnId) + "}");
        }
    }

    private void completeRun(String runId, Run run) {
        if (run.completed) return;
        run.completed = true;
        sendRaw("{\"action\":\"playback.completed\",\"run_id\":" + q(runId)
                + ",\"turn_id\":" + q(run.turnId) + "}");
        runs.remove(runId);
    }

    private void stopPlaybackNow() {
        playQueue.clear();
        playingNow.set(false);
        if (track != null) {
            try {
                track.pause();
                track.flush();
                track.play();
            } catch (Exception ignored) { }
        }
    }

    /** 本地打断：停播 + 丢弃本轮剩余音频 + 告诉上游听到哪儿了。 */
    private void interrupt(String source) {
        stopPlaybackNow();
        String runId = activeRunId;
        Run run = runId == null ? null : runs.get(runId);
        long playedMs = run == null ? 0 : run.playedMs;
        if (runId != null) localStoppedRuns.add(runId);
        sendRaw("{\"action\":\"playback.stopped\",\"run_id\":" + q(runId)
                + ",\"turn_id\":" + q(run == null ? null : run.turnId)
                + ",\"played_audio_ms\":" + playedMs + "}");
        bargeCount++;
        listener.onBargeIn(source);
        listener.onState("listening", "");
    }

    private void stopAudio() {
        ready = false;
        stopPlaybackNow();
        if (record != null) {
            try { record.stop(); } catch (Exception ignored) { }
            try { record.release(); } catch (Exception ignored) { }
            record = null;
        }
        if (aec != null) { try { aec.release(); } catch (Exception ignored) { } aec = null; }
        if (ns != null) { try { ns.release(); } catch (Exception ignored) { } ns = null; }
        if (track != null) {
            try { track.pause(); } catch (Exception ignored) { }
            try { track.flush(); } catch (Exception ignored) { }
            try { track.release(); } catch (Exception ignored) { }
            track = null;
        }
        Thread c = captureThread; captureThread = null;
        if (c != null) { try { c.join(500); } catch (InterruptedException ignored) { } }
        Thread p = playbackThread; playbackThread = null;
        if (p != null) { try { p.join(500); } catch (InterruptedException ignored) { } }
    }

    // ------------------------------------------------------------ 工具

    private static String q(String value) {
        if (value == null || value.isEmpty()) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private void sendRaw(String json) {
        WebSocket socket = ws;
        if (socket == null) return;
        try { socket.send(json); } catch (Exception ignored) { }
    }

    private void sendJson(String json) { sendRaw(json); }
}
