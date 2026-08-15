package com.yvroumaojvan.voicecraft;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Fish Audio TTS 客户端（免费模型 s2.1-pro-free）。
 * HTTP POST 一次返回 mp3 音频流，零依赖（HttpURLConnection，JDK/Android 通用）。
 */
public class FishTTS {

    public static final String API_URL = "https://api.fish.audio/v1/tts";
    public static final String FREE_MODEL = "s2.1-pro-free";

    public static final class FishException extends Exception {
        public FishException(String msg) { super(msg); }
    }

    /**
     * 用平台音色（reference_id）合成。
     * @param apiKey  fish.audio API Key
     * @param voiceId 音色模型 ID（如莫提斯）
     * @param text    要合成的文本
     * @param speed   语速倍率（0.5~2.0）
     */
    public static byte[] synthesize(String apiKey, String voiceId, String text, double speed)
            throws FishException {
        StringBuilder body = new StringBuilder();
        body.append("{\"text\":").append(json(text));
        body.append(",\"reference_id\":").append(json(voiceId));
        body.append(",\"format\":\"mp3\"");
        body.append(",\"prosody\":{\"speed\":").append(speed).append("}");
        body.append(",\"normalize\":true}");
        return post(apiKey, body.toString());
    }

    private static byte[] post(String apiKey, String body) throws FishException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(API_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(120000);
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("model", FREE_MODEL);
            conn.setDoOutput(true);
            conn.getOutputStream().write(body.getBytes("UTF-8"));

            int code = conn.getResponseCode();
            if (code == 200) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                InputStream in = conn.getInputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
                in.close();
                return baos.toByteArray();
            }
            // 错误：读 JSON 错误体
            String errBody = "";
            InputStream err = conn.getErrorStream();
            if (err != null) {
                ByteArrayOutputStream b2 = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = err.read(buf)) > 0) b2.write(buf, 0, n);
                errBody = new String(b2.toByteArray(), "UTF-8");
            }
            throw new FishException("HTTP " + code + (errBody.isEmpty() ? "" : " " + errBody));
        } catch (FishException e) {
            throw e;
        } catch (Exception e) {
            throw new FishException("请求失败: " + e.getMessage() + "（请确认已挂 VPN）");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String json(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append("\"").toString();
    }

    /** Edge 风格语速字符串（+30%）转 Fish 倍率（1.3） */
    public static double speedFromRate(String rate) {
        try {
            String trimmed = rate.replace("%", "").trim();
            boolean minus = trimmed.startsWith("-");
            String digits = trimmed.replace("+", "").replace("-", "").trim();
            double pct = Double.parseDouble(digits);
            double factor = 1.0 + pct / 100.0;
            if (factor < 0.5) factor = 0.5;
            if (factor > 2.0) factor = 2.0;
            return Math.round(factor * 100.0) / 100.0;
        } catch (Exception e) {
            return 1.0;
        }
    }
}
