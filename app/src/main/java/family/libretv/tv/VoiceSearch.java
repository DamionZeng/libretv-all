package family.libretv.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import org.json.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 语音搜索：电视麦克风录音 → 语音识别模型转写成文字 → 大模型整理成影视搜索关键词。
 * 都走硅基流动（国内可直连），只需要在设置里填写一个 API Key。
 */
final class VoiceSearch {
    static final String URL_STT = "https://api.siliconflow.cn/v1/audio/transcriptions";
    static final String URL_CHAT = "https://api.siliconflow.cn/v1/chat/completions";
    private static final String PREFS = "voice", KEY = "siliconflow";
    private static final int MAX_MS = 9000, MIN_VOICE_MS = 600, TAIL_SILENCE_MS = 800;
    /** 转写模型：Ultra 为主，Diarize 作为兜底。 */
    private static final String[] STT_MODELS = {"XingChenAGI/XingChenASR-V3.2-Ultra", "XingChenAGI/XingChenASR-Diarize-V3.0"};
    /** 对话模型候选（Qwen3.5-4B 实测 0.6-0.75 秒；GLM-5.3-Flash 要 9 秒以上，不用）。 */
    private static final String[] CHAT_MODELS = {"Qwen/Qwen3.5-4B", "Qwen/Qwen3-8B"};

    private VoiceSearch() {}

    static String key(Context context) {
        String value = prefs(context).getString(KEY, "");
        return value == null ? "" : value.trim();
    }

    static void saveKey(Context context, String value) {
        prefs(context).edit().putString(KEY, value == null ? "" : value.trim()).apply();
    }

    /** 已保存 Key 的掩码文案，只用于界面回显，不提供查看明文的入口。 */
    static String masked(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.length() <= 4 ? "••••" : "••••••" + value.substring(value.length() - 4);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, 0);
    }

    /** 单声道录音，说完停顿约 1 秒自动停止，最长 9 秒。 */
    static final class Recorder {
        /** 电视音频 HAL 常把输入锁在 44.1k/48k，16k 打不开时依次回退。 */
        private static final int[] RATES = {16000, 44100, 48000};
        private final AudioRecord record;
        private final int rate;
        private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        private final AtomicBoolean running = new AtomicBoolean();
        private Thread worker;
        private volatile int peak;

        Recorder() throws Exception {
            AudioRecord opened = null;
            int openedRate = RATES[0];
            for (int candidate : RATES) {
                int min = AudioRecord.getMinBufferSize(candidate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                AudioRecord attempt = new AudioRecord(MediaRecorder.AudioSource.MIC, candidate, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, min > 0 ? Math.max(min, candidate) : candidate * 2);
                if (attempt.getState() == AudioRecord.STATE_INITIALIZED) { opened = attempt; openedRate = candidate; break; }
                attempt.release();
            }
            if (opened == null) throw new Exception("无法打开麦克风");
            record = opened;
            rate = openedRate;
        }

        void start() {
            record.startRecording();
            running.set(true);
            worker = new Thread(this::loop, "voice-record");
            worker.start();
        }

        boolean running() { return running.get(); }

        /** 录音峰值，用于判断麦克风是否真的收到了声音。 */
        int peak() { return peak; }

        private void loop() {
            int chunk = rate / 10;
            byte[] buffer = new byte[chunk];
            int silence = 0, voiced = 0, elapsed = 0;
            while (running.get() && elapsed < MAX_MS) {
                int read = record.read(buffer, 0, buffer.length);
                if (read <= 0) break;
                pcm.write(buffer, 0, read);
                int rms = rms(buffer, read);
                if (rms > peak) peak = rms;
                elapsed += 100;
                if (rms > 600) { voiced += 100; silence = 0; }
                else if (voiced > 0) silence += 100;
                if (voiced >= MIN_VOICE_MS && silence >= TAIL_SILENCE_MS) break;
            }
            running.set(false);
        }

        byte[] stop() {
            running.set(false);
            join();
            try { record.stop(); } catch (Exception ignored) { }
            record.release();
            return wav(pcm.toByteArray(), rate);
        }

        void cancel() {
            running.set(false);
            join();
            try { record.stop(); } catch (Exception ignored) { }
            record.release();
        }

        private void join() {
            try { if (worker != null) worker.join(500); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            worker = null;
        }

        private static int rms(byte[] data, int length) {
            long sum = 0;
            int count = length / 2;
            for (int i = 0; i + 1 < length; i += 2) {
                int sample = (short) ((data[i] & 0xff) | (data[i + 1] << 8));
                sum += (long) sample * sample;
            }
            return count == 0 ? 0 : (int) Math.sqrt(sum / count);
        }

        private static byte[] wav(byte[] pcm, int rate) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(pcm.length + 44);
            DataOutputStream data = new DataOutputStream(out);
            try {
                data.writeBytes("RIFF"); data.writeInt(Integer.reverseBytes(pcm.length + 36)); data.writeBytes("WAVEfmt ");
                data.writeInt(Integer.reverseBytes(16)); data.writeShort(Short.reverseBytes((short) 1));
                data.writeShort(Short.reverseBytes((short) 1)); data.writeInt(Integer.reverseBytes(rate));
                data.writeInt(Integer.reverseBytes(rate * 2)); data.writeShort(Short.reverseBytes((short) 2));
                data.writeShort(Short.reverseBytes((short) 16)); data.writeBytes("data");
                data.writeInt(Integer.reverseBytes(pcm.length)); data.write(pcm); data.flush();
            } catch (IOException ignored) { }
            return out.toByteArray();
        }
    }

    /** 语音转写，返回识别出的文字。 */
    static String transcribe(byte[] wav, String key) throws Exception {
        long start = System.currentTimeMillis();
        Exception failure = null;
        for (String model : STT_MODELS) {
            try {
                String text = transcribe(wav, key, model);
                android.util.Log.d("VoiceSearch", "stt " + model + " " + (System.currentTimeMillis() - start) + "ms");
                return text;
            } catch (Exception e) {
                failure = e;
                android.util.Log.d("VoiceSearch", "stt " + model + " failed: " + e.getMessage());
            }
        }
        throw failure == null ? new Exception("没有听清，请再说一次") : failure;
    }

    private static String transcribe(byte[] wav, String key, String model) throws Exception {
        String boundary = "----starlight" + System.currentTimeMillis();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream body = new DataOutputStream(bytes);
        field(body, boundary, "model", model);
        body.writeBytes("--" + boundary + "\r\n");
        body.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"voice.wav\"\r\n");
        body.writeBytes("Content-Type: audio/wav\r\n\r\n");
        body.write(wav);
        body.writeBytes("\r\n--" + boundary + "--\r\n");
        body.flush();
        JSONObject reply = post(URL_STT, key, "multipart/form-data; boundary=" + boundary, bytes.toByteArray());
        // 部分转写模型会带上 <|zh|><|NEUTRAL|> 之类的标记，结果统一去掉。
        String text = reply.optString("text", "").replaceAll("<\\|[^|]*\\|>", "").trim();
        if (text.isEmpty()) throw new Exception("没有听清，请再说一次");
        return text;
    }

    /** 把口语整理成适合搜索框的关键词；大模型不可用时退回识别原文。 */
    static String keyword(String transcript, String key) {
        JSONArray messages = new JSONArray();
        try {
            messages.put(new JSONObject().put("role", "system")
                    .put("content", "你只输出一个影视搜索关键词，20 字以内，不要解释、不要引号、不要标点。"));
            messages.put(new JSONObject().put("role", "user").put("content",
                    "用户在电视上语音搜索影视，识别文字是：「" + transcript + "」。请提取最适合拿去影视搜索框的关键词："
                            + "如果是具体作品名就只给作品名；如果是题材、演员、导演、地区等描述就给最贴近的短语。"));
            for (String model : CHAT_MODELS) {
                try {
                    JSONObject payload = new JSONObject().put("model", model).put("temperature", 0)
                            .put("max_tokens", 16).put("enable_thinking", false).put("messages", messages);
                    JSONObject reply = post(URL_CHAT, key, "application/json",
                            payload.toString().getBytes(StandardCharsets.UTF_8));
                    JSONArray choices = reply.optJSONArray("choices");
                    if (choices == null || choices.length() == 0) continue;
                    String answer = choices.optJSONObject(0).optJSONObject("message").optString("content", "").trim();
                    answer = answer.replaceAll("[\"'“”‘’《》\\[\\]]", "").replaceAll("\\s+", " ").trim();
                    if (!answer.isEmpty() && answer.length() <= 40) return answer;
                } catch (Exception ignored) { }
            }
        } catch (JSONException ignored) { }
        return transcript;
    }

    private static void field(DataOutputStream body, String boundary, String name, String value) throws IOException {
        body.writeBytes("--" + boundary + "\r\n");
        body.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        body.write(value.getBytes(StandardCharsets.UTF_8));
        body.writeBytes("\r\n");
    }

    private static JSONObject post(String endpoint, String key, String contentType, byte[] body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "Bearer " + key);
        conn.setRequestProperty("Content-Type", contentType);
        conn.setFixedLengthStreamingMode(body.length);
        OutputStream out = conn.getOutputStream();
        out.write(body);
        out.flush();
        out.close();
        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (stream != null) {
            byte[] chunk = new byte[4096];
            int read;
            while ((read = stream.read(chunk)) > 0) buffer.write(chunk, 0, read);
            stream.close();
        }
        conn.disconnect();
        String text = buffer.toString("UTF-8");
        if (code < 200 || code >= 300) throw new Exception(reason(code, text));
        return new JSONObject(text);
    }

    private static String reason(int code, String body) {
        if (code == 401) return "API Key 无效，请在设置里重新填写";
        if (code == 403) return "服务拒绝访问（当前网络被限制），请稍后重试";
        if (code == 429) return "调用太频繁或额度已用尽，请稍后再试";
        if (code == 503 || code == 504) return "语音服务繁忙，请稍后再试";
        try {
            JSONObject error = new JSONObject(body);
            JSONObject detail = error.optJSONObject("error");
            String message = detail != null ? detail.optString("message") : error.optString("message");
            if (message != null && !message.isEmpty()) return message.length() > 60 ? message.substring(0, 60) : message;
        } catch (JSONException ignored) { }
        return "语音服务请求失败（" + code + "）";
    }
}
