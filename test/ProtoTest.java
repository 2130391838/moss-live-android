import okhttp3.*;
import okio.ByteString;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 桌面 JVM 上的「APK 协议一致性」验收：用与 MossClient.java 完全相同的
 * URL / 请求头 / 握手顺序 / 20ms 帧格式连一次真实上游，看能不能走通。
 * 只验证网络与协议层（不涉及 Android 音频 API）。不依赖 org.json。
 */
public class ProtoTest {

    static String actionOf(String text) {
        int i = text.indexOf("\"action\":\"");
        if (i < 0) return "?";
        int j = text.indexOf('"', i + 10);
        return j < 0 ? "?" : text.substring(i + 10, j);
    }

    static String q(String v) {
        return v == null || v.isEmpty() ? "null" : "\"" + v + "\"";
    }

    static String fieldOf(String text, String name) {
        String needle = "\"" + name + "\":\"";
        int i = text.indexOf(needle);
        if (i < 0) return null;
        int j = text.indexOf('"', i + needle.length());
        return j < 0 ? null : text.substring(i + needle.length(), j);
    }

    public static void main(String[] args) throws Exception {
        String key = System.getenv("MOSS_API_KEY");
        if (key == null || key.isEmpty()) {
            System.err.println("缺少 MOSS_API_KEY");
            System.exit(2);
        }
        String wav = args.length > 0 ? args[0] : "../tests/fixtures/question.wav";
        byte[] pcm = readPcm16k(new File(wav));
        System.out.println("上行音频：" + pcm.length + " 字节 ≈ " + (pcm.length / 32000.0) + " 秒");

        String persona = "你是 Luna，一位温柔、活泼、有亲和力的语音助手。"
                + "说话自然口语化，像在打电话，句子短、有停顿、允许使用“嗯”“对呀”这类语气词。"
                + "用户在你说的时候一开口，你就立刻停下来听，不需要他说“等一下”或“打断一下”；"
                + "只有“嗯”“对”这类一两个字的附和、以及他还在思考时的停顿，你才继续说下去。"
                + "被打断后从他实际听到的地方接着说，不要从头重念。"
                + "回答控制在两三句话内，除非用户要求展开。";
        String instr = "始终用中文口语回答；不要输出 Markdown、列表或表情符号。";

        CountDownLatch ended = new CountDownLatch(1);
        AtomicBoolean ready = new AtomicBoolean(false);
        AtomicBoolean failed = new AtomicBoolean(false);
        AtomicInteger downBytes = new AtomicInteger();
        AtomicInteger events = new AtomicInteger();
        AtomicInteger chunks = new AtomicInteger();
        AtomicLong audioStart = new AtomicLong();
        StringBuilder finalText = new StringBuilder();

        OkHttpClient http = new OkHttpClient.Builder()
                .pingInterval(15, TimeUnit.SECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();

        String url = "wss://api.mosi.cn/v1/realtime?model=moss-live-1.0&user=android-prototest";
        Request req = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + key)
                .addHeader("User-Agent", "MossLiveAndroid/1.0")
                .build();

        WebSocket ws = http.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket s, Response r) {
                System.out.println("[open] HTTP " + r.code());
                s.send("{\"action\":\"attach_session\"}");
            }

            @Override
            public void onMessage(WebSocket s, String text) {
                events.incrementAndGet();
                String action = actionOf(text);

                if (action.equals("session_attached")) {
                    System.out.println("[event] session_attached");
                    String session = "{\"action\":\"session.update\",\"session\":{"
                            + "\"persona\":{\"name\":\"Luna\",\"prompt\":\"" + persona + "\"},"
                            + "\"instructions\":\"" + instr + "\"}}";
                    s.send(session);
                } else if (action.equals("session.updated")) {
                    System.out.println("[event] session.updated");
                    s.send("{\"action\":\"media.mode\",\"binary_protocol\":\"tagged-v1\"}");
                    s.send("{\"action\":\"mic_state\",\"enabled\":true}");
                    ready.set(true);
                } else if (action.equals("response.audio.context")) {
                    chunks.incrementAndGet();
                    String chunkId = fieldOf(text, "chunk_id");
                    String runId = fieldOf(text, "run_id");
                    String turnId = fieldOf(text, "turn_id");
                    if (chunkId != null) {
                        s.send("{\"action\":\"playback.chunk.completed\",\"chunk_id\":\"" + chunkId
                                + "\",\"run_id\":" + q(runId) + ",\"turn_id\":" + q(turnId) + "}");
                    }
                } else if (action.equals("response.audio.started")) {
                    audioStart.compareAndSet(0, System.currentTimeMillis());
                    System.out.println("[event] response.audio.started");
                } else if (action.equals("response.audio.done")) {
                    System.out.println("[event] response.audio.done");
                    s.send("{\"action\":\"playback.completed\",\"run_id\":"
                            + q(fieldOf(text, "run_id")) + ",\"turn_id\":"
                            + q(fieldOf(text, "turn_id")) + "}");
                } else if (action.equals("response.audio.stop")) {
                    System.out.println("[event] response.audio.stop");
                    s.send("{\"action\":\"playback.stopped\",\"run_id\":"
                            + q(fieldOf(text, "run_id")) + ",\"turn_id\":"
                            + q(fieldOf(text, "turn_id")) + ",\"played_audio_ms\":0}");
                } else if (action.equals("output.transcript.done")) {
                    String t = fieldOf(text, "text");
                    System.out.println("[回答] " + t);
                    if (t != null) finalText.append(t).append("\n");
                } else if (action.equals("response.ended")) {
                    System.out.println("[event] response.ended status=" + fieldOf(text, "status"));
                    ended.countDown();
                } else if (action.equals("error")) {
                    System.out.println("[ERROR] " + text);
                    failed.set(true);
                } else {
                    System.out.println("[event] " + action);
                }
            }

            @Override
            public void onMessage(WebSocket s, ByteString bytes) {
                downBytes.addAndGet(bytes.size());
            }

            @Override
            public void onFailure(WebSocket s, Throwable t, Response r) {
                System.out.println("[failure] " + t + " / " + (r == null ? "no response" : r.code()));
                failed.set(true);
                ended.countDown();
            }

            @Override
            public void onClosed(WebSocket s, int code, String reason) {
                System.out.println("[closed] " + code + " " + reason);
                ended.countDown();
            }
        });

        long t0 = System.currentTimeMillis();
        while (!ready.get() && System.currentTimeMillis() - t0 < 15000) Thread.sleep(50);
        if (!ready.get()) {
            System.out.println("[FAIL] 15 秒内没握手成功");
            ws.close(1000, "timeout");
            System.exit(1);
        }
        System.out.println("[ready] 握手成功，按 20ms 节奏上行 320 采样/帧");

        byte[] frame = new byte[1 + 640];
        int offset = 0;
        int frames = 0;
        while (offset + 640 <= pcm.length) {
            frame[0] = 0x00;
            System.arraycopy(pcm, offset, frame, 1, 640);
            offset += 640;
            ws.send(ByteString.of(frame));
            frames++;
            Thread.sleep(20);
        }
        System.out.println("[sent] 上行 " + frames + " 帧完成，继续送静音等回答…");

        // 说完后必须继续按 20ms 送静音帧，否则服务端判断不出「用户说完了」
        byte[] silence = new byte[1 + 640];
        long deadline = System.currentTimeMillis() + 30000;
        while (ended.getCount() > 0 && System.currentTimeMillis() < deadline) {
            ws.send(ByteString.of(silence));
            Thread.sleep(20);
        }
        ended.await(2, TimeUnit.SECONDS);
        Thread.sleep(800);
        ws.close(1000, "done");

        System.out.println("----------------------------------------");
        System.out.println("事件数：" + events.get() + "，音频块：" + chunks.get());
        System.out.println("下行音频：" + downBytes.get() + " 字节 ≈ " + (downBytes.get() / 96.0) + " 毫秒");
        System.out.println("首次出声延迟：" + (audioStart.get() == 0 ? "无"
                : (audioStart.get() - t0) + " ms"));
        System.out.println("回答：" + finalText);
        boolean pass = !failed.get() && downBytes.get() > 48000 && events.get() > 5;
        System.out.println(pass ? "[PASS] 协议链路可用" : "[FAIL] 没拿到音频/事件");
        System.exit(pass ? 0 : 1);
    }

    /** 读 WAV 并重采样到 16 kHz 单声道 PCM16（与桌面版 load_pcm16k 同口径）。 */
    static byte[] readPcm16k(File f) throws Exception {
        byte[] all = Files.readAllBytes(f.toPath());
        ByteBuffer bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
        int channels = 1, rate = 16000, bits = 16;
        byte[] raw = null;
        int pos = 12;
        while (pos + 8 <= all.length) {
            String id = new String(all, pos, 4, "US-ASCII");
            int size = bb.getInt(pos + 4);
            if (id.equals("fmt ")) {
                channels = bb.getShort(pos + 10) & 0xffff;
                rate = bb.getInt(pos + 12);
                bits = bb.getShort(pos + 22) & 0xffff;
            } else if (id.equals("data")) {
                raw = new byte[Math.min(size, all.length - pos - 8)];
                System.arraycopy(all, pos + 8, raw, 0, raw.length);
                break;
            }
            pos += 8 + size + (size % 2);
        }
        if (raw == null) throw new IllegalStateException("没有 data 段");
        if (bits != 16) throw new IllegalStateException("只支持 16-bit PCM");

        short[] src = new short[raw.length / 2];
        ByteBuffer sb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < src.length; i++) src[i] = sb.getShort();

        int monoLen = channels > 1 ? src.length / channels : src.length;
        float[] mono = new float[monoLen];
        for (int i = 0; i < monoLen; i++) {
            if (channels > 1) {
                int sum = 0;
                for (int c = 0; c < channels; c++) sum += src[i * channels + c];
                mono[i] = sum / (float) channels;
            } else {
                mono[i] = src[i];
            }
        }

        int outLen = rate == 16000 ? monoLen : Math.round(monoLen * 16000f / rate);
        byte[] out = new byte[outLen * 2];
        ByteBuffer ob = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        if (rate == 16000) {
            for (int i = 0; i < monoLen; i++) ob.putShort((short) mono[i]);
        } else {
            for (int i = 0; i < outLen; i++) {
                float sp = i * (monoLen - 1f) / Math.max(1, outLen - 1);
                int i0 = (int) Math.floor(sp);
                int i1 = Math.min(monoLen - 1, i0 + 1);
                float frac = sp - i0;
                ob.putShort((short) (mono[i0] * (1 - frac) + mono[i1] * frac));
            }
        }
        return out;
    }
}
