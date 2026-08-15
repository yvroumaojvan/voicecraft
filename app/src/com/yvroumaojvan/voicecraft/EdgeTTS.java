package com.yvroumaojvan.voicecraft;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Edge TTS 协议核心（基于自研零依赖 WssClient，JDK 与 Android 通用）。
 * 协议细节复刻自 edge-tts 7.2.8 (python)。
 * 标点停顿由微软语音模型自然处理（逗号/句号/问号/感叹号均自动停顿）。
 */
public class EdgeTTS {

    private static final String TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    private static final String WSS_BASE =
        "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1";
    private static final String CHROMIUM_FULL_VERSION = "143.0.3650.75";
    private static final String UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0";

    public static final class TtsException extends Exception {
        public TtsException(String msg) { super(msg); }
    }

    /** 生成 Sec-MS-GEC token（edge-tts DRM.generate_sec_ms_gec） */
    static String generateSecMsGec() {
        long unixSec = System.currentTimeMillis() / 1000L;
        double ticks = (double) unixSec + 11644473600.0;   // Windows 纪元
        ticks -= ticks % 300.0;                            // 向下取整到 5 分钟
        ticks *= 1e7;                                      // 100ns 间隔
        String toHash = String.format(Locale.US, "%.0f", ticks) + TRUSTED_CLIENT_TOKEN;
        return sha256Hex(toHash).toUpperCase(Locale.US);
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes("US-ASCII"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String connectId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 类似 JS 的 Date#toString 格式 */
    private static String dateToString() {
        SimpleDateFormat sdf = new SimpleDateFormat(
            "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date());
    }

    private static String makeSpeechConfig() {
        return "X-Timestamp:" + dateToString() + "\r\n"
            + "Content-Type:application/json; charset=utf-8\r\n"
            + "Path:speech.config\r\n\r\n"
            + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{"
            + "\"sentenceBoundaryEnabled\":\"true\",\"wordBoundaryEnabled\":\"false\"},"
            + "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n";
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String makeSsml(String voice, String text, String rate) {
        String body = xmlEscape(text);
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>"
            + "<voice name='" + voice + "'>"
            + "<prosody pitch='+0Hz' rate='" + rate + "' volume='+0%'>"
            + body
            + "</prosody></voice></speak>";
    }

    private static String makeSsmlHeaders(String ssml) {
        return "X-RequestId:" + connectId() + "\r\n"
            + "Content-Type:application/ssml+xml\r\n"
            + "X-Timestamp:" + dateToString() + "Z\r\n"
            + "Path:ssml\r\n\r\n"
            + ssml;
    }

    /**
     * 合成文本 → mp3 字节。
     * @param voice 音色名，如 zh-CN-XiaoxiaoNeural
     * @param text  要合成的文本（标点自动停顿）
     * @param rate  语速，如 +0%、+30%、-20%
     */
    public static byte[] synthesize(String voice, String text, String rate) throws TtsException {
        String url = WSS_BASE
            + "?TrustedClientToken=" + TRUSTED_CLIENT_TOKEN
            + "&ConnectionId=" + connectId()
            + "&Sec-MS-GEC=" + generateSecMsGec()
            + "&Sec-MS-GEC-Version=1-" + CHROMIUM_FULL_VERSION;

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Accept-Encoding", "gzip, deflate, br, zstd");
        headers.put("Accept-Language", "en-US,en;q=0.9");
        headers.put("Pragma", "no-cache");
        headers.put("Cache-Control", "no-cache");
        headers.put("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold");
        headers.put("Cookie", "muid=" + connectId() + ";");

        final ByteArrayOutputStream mp3 = new ByteArrayOutputStream();
        final TtsException[] errorHolder = new TtsException[1];
        final CountDownLatch done = new CountDownLatch(1);
        final StringBuilder textBuf = new StringBuilder();

        try {
            WssClient client = new WssClient(url, headers, 15000);

            WssClient.Listener listener = new WssClient.Listener() {
                @Override
                public void onText(String data, boolean last) {
                    textBuf.append(data);
                    if (last) {
                        String msg = textBuf.toString();
                        textBuf.setLength(0);
                        if ("turn.end".equals(extractPath(msg))) {
                            done.countDown();
                        }
                    }
                }

                @Override
                public void onBinary(byte[] data, boolean last) {
                    if (data.length >= 2) {
                        int headerLen = (data[0] & 0xFF) << 8 | (data[1] & 0xFF);
                        if (headerLen + 2 <= data.length) {
                            mp3.write(data, 2 + headerLen, data.length - 2 - headerLen);
                        }
                    }
                }

                @Override
                public void onClose(int code, String reason) {
                    if (code != 1000 && code != 1006) {
                        errorHolder[0] = new TtsException("服务器关闭连接: " + code + " " + reason);
                    }
                    done.countDown();
                }

                @Override
                public void onError(Throwable t) {
                    errorHolder[0] = new TtsException("网络错误: " + t.getMessage());
                    done.countDown();
                }
            };

            client.sendText(makeSpeechConfig());
            client.sendText(makeSsmlHeaders(makeSsml(voice, text, rate)));
            Thread reader = new Thread(new Runnable() {
                @Override public void run() { client.readLoop(listener); }
            });
            reader.setDaemon(true);
            reader.start();

            if (!done.await(60, TimeUnit.SECONDS)) {
                client.closeQuietly();
                throw new TtsException("合成超时，请检查网络");
            }
            client.closeQuietly();
            if (errorHolder[0] != null) throw errorHolder[0];
            if (mp3.size() == 0) throw new TtsException("未收到音频数据，请稍后重试");
            return mp3.toByteArray();
        } catch (TtsException e) {
            throw e;
        } catch (Exception e) {
            throw new TtsException("连接失败: " + e.getMessage());
        }
    }

    private static String extractPath(String textMessage) {
        int cut = textMessage.indexOf("\r\n\r\n");
        String head = cut >= 0 ? textMessage.substring(0, cut) : textMessage;
        for (String line : head.split("\r\n")) {
            if (line.startsWith("Path:")) return line.substring(5).trim();
        }
        return "";
    }

    /** 便捷入口：text→mp3 写文件 */
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("用法: java EdgeTTS <voice> <text> <out.mp3> [rate]");
            System.exit(1);
        }
        String voice = args[0];
        String text = args[1];
        String out = args[2];
        String rate = args.length >= 4 ? args[3] : "+0%";
        System.out.println("合成中... voice=" + voice + " rate=" + rate);
        byte[] mp3 = synthesize(voice, text, rate);
        java.nio.file.Files.write(java.nio.file.Paths.get(out), mp3);
        System.out.println("OK -> " + out + " (" + mp3.length + " bytes)");
    }
}
