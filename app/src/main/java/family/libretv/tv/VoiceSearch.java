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
    private static final int MAX_MS = 9000, MIN_VOICE_MS = 400, TAIL_SILENCE_MS = 1300, VOICE_RMS = 500;
    /** 转写模型：Ultra 为主，Diarize 作为兜底。 */
    private static final String[] STT_MODELS = {"XingChenAGI/XingChenASR-V3.2-Ultra", "XingChenAGI/XingChenASR-Diarize-V3.0"};
    /** 对话模型：固定使用 DeepSeek-V4.1-Flash，只请求一次，不做模型兜底。 */
    private static final String CHAT_MODEL = "deepseek-ai/DeepSeek-V4.1-Flash";

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
            int chunk = Math.max(rate / 10, 320);
            byte[] buffer = new byte[chunk];
            int silence = 0, voiced = 0, total = 0;
            while (running.get() && total * 1000L / (rate * 2) < MAX_MS) {
                int read = record.read(buffer, 0, buffer.length);
                if (read <= 0) break;
                pcm.write(buffer, 0, read);
                total += read;
                // 时长按实际读到的采样数换算，不能按读取次数累加，否则会在真人还没说完时提前结束。
                int chunkMs = Math.max(1, read * 1000 / (rate * 2));
                int rms = rms(buffer, read);
                if (rms > peak) peak = rms;
                if (rms > VOICE_RMS) { voiced += chunkMs; silence = 0; }
                else if (voiced > 0) silence += chunkMs;
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

    /** 把识别原文整体交给文本模型，由它纠正误识别并直接给出作品名称；仅请求一次，失败时退回识别原文。 */
    static String keyword(String transcript, String key) {
        long start = System.currentTimeMillis();
        try {
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system")
                    .put("content", "你是电视影视搜索助手。用户用语音说出想看的内容，识别文字常有同音错别字。请把识别文字纠正为真实存在的影视作品名称，只输出该名称。"
                            + "规则：1.优先输出与读音最接近的已知影视作品全名，例如把仓原图纠正为沧元图；"
                            + "2.用户只给题材、演员、导演、角色、地区、剧情等线索时，输出最匹配的作品名称；"
                            + "3.无法确定具体作品时，输出最贴近的搜索词。"
                            + "只输出结果本身，不要解释、不要引号、不要标点，20 字以内。"));
            messages.put(new JSONObject().put("role", "user").put("content", transcript));
            JSONObject payload = new JSONObject().put("model", CHAT_MODEL).put("temperature", 0)
                    .put("max_tokens", 24).put("enable_thinking", false).put("messages", messages);
            // 语音搜索要求 5 秒内出结果，这里限制连接与读取耗时。
            JSONObject reply = post(URL_CHAT, key, "application/json",
                    payload.toString().getBytes(StandardCharsets.UTF_8), 2000, 3500);
            JSONArray choices = reply.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                String answer = choices.optJSONObject(0).optJSONObject("message").optString("content", "").trim();
                answer = answer.replaceAll("[\"'“”‘’《》\\[\\]]", "").replaceAll("\\s+", " ").trim();
                android.util.Log.d("VoiceSearch", "chat " + CHAT_MODEL + " " + (System.currentTimeMillis() - start) + "ms");
                if (!answer.isEmpty() && answer.length() <= 40) return answer;
            }
        } catch (Exception e) {
            android.util.Log.d("VoiceSearch", "chat failed: " + e.getMessage());
        }
        return transcript;
    }

    private static void field(DataOutputStream body, String boundary, String name, String value) throws IOException {
        body.writeBytes("--" + boundary + "\r\n");
        body.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        body.write(value.getBytes(StandardCharsets.UTF_8));
        body.writeBytes("\r\n");
    }

    private static JSONObject post(String endpoint, String key, String contentType, byte[] body) throws Exception {
        return post(endpoint, key, contentType, body, 10000, 30000);
    }

    private static JSONObject post(String endpoint, String key, String contentType, byte[] body, int connectMs, int readMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setConnectTimeout(connectMs);
        conn.setReadTimeout(readMs);
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
