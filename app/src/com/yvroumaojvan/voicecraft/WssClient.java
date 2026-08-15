package com.yvroumaojvan.voicecraft;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/**
 * 零依赖最小 WebSocket 客户端（RFC 6455 客户端侧）。
 * JDK 与 Android 通用（javax.net.ssl + java.io，无 java.net.http）。
 */
public class WssClient {

    public interface Listener {
        /** 收到文本消息（消息可能分片，last 为 true 表示最后一片） */
        void onText(String text, boolean last);
        /** 收到二进制消息 */
        void onBinary(byte[] data, boolean last);
        void onClose(int code, String reason);
        void onError(Throwable t);
    }

    private final Socket socket;
    private final OutputStream out;
    private final SecureRandom random = new SecureRandom();

    public WssClient(String url, Map<String, String> headers, int timeoutMs) throws Exception {
        URI uri = URI.create(url);
        if (!"wss".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("仅支持 wss://");
        }
        int port = uri.getPort() > 0 ? uri.getPort() : 443;
        String host = uri.getHost();

        // TLS 连接
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, null, null);
        SSLSocketFactory factory = ctx.getSocketFactory();
        SSLSocket ssl = (SSLSocket) factory.createSocket();
        SSLParameters params = ssl.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS"); // 主机名校验
        ssl.setSSLParameters(params);
        ssl.connect(new InetSocketAddress(host, port), timeoutMs);
        ssl.startHandshake();
        socket = ssl;

        // 发起 WebSocket 升级握手
        String path = uri.getRawPath() + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "");
        byte[] keyBytes = new byte[16];
        random.nextBytes(keyBytes);
        String secKey = Base64.getEncoder().encodeToString(keyBytes);

        StringBuilder req = new StringBuilder();
        req.append("GET ").append(path).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(host).append("\r\n");
        req.append("Upgrade: websocket\r\n");
        req.append("Connection: Upgrade\r\n");
        req.append("Sec-WebSocket-Key: ").append(secKey).append("\r\n");
        req.append("Sec-WebSocket-Version: 13\r\n");
        for (Map.Entry<String, String> e : headers.entrySet()) {
            req.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        req.append("\r\n");

        out = ssl.getOutputStream();
        out.write(req.toString().getBytes("US-ASCII"));
        out.flush();

        // 读响应头，校验 101
        InputStream in = ssl.getInputStream();
        String statusLine = readLine(in);
        if (!statusLine.contains(" 101 ")) {
            throw new IOException("握手失败: " + statusLine);
        }
        boolean sawUpgrade = false;
        String line;
        String accept = null;
        while (!(line = readLine(in)).isEmpty()) {
            if (line.toLowerCase().startsWith("upgrade:")) sawUpgrade = true;
            if (line.toLowerCase().startsWith("sec-websocket-accept:")) {
                accept = line.substring(line.indexOf(':') + 1).trim();
            }
        }
        // 校验 Sec-WebSocket-Accept
        String expect = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest(
                (secKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("US-ASCII")));
        if (accept == null || !accept.equals(expect)) {
            throw new IOException("Sec-WebSocket-Accept 校验失败");
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') buf.write(b);
        }
        if (b == -1 && buf.size() == 0) throw new EOFException("连接已断开");
        return new String(buf.toByteArray(), "US-ASCII");
    }

    /** 发送文本消息 */
    public synchronized void sendText(String text) throws IOException {
        sendFrame(0x1, text.getBytes("UTF-8"));
    }

    /** 发送关闭帧 */
    public synchronized void sendClose(int code, String reason) throws IOException {
        byte[] reasonBytes = reason == null ? new byte[0] : reason.getBytes("UTF-8");
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(code >> 8 & 0xFF);
        payload.write(code & 0xFF);
        payload.write(reasonBytes);
        sendFrame(0x8, payload.toByteArray());
    }

    private void sendFrame(int opcode, byte[] payload) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x80 | opcode); // FIN + opcode
        int len = payload.length;
        if (len < 126) {
            frame.write(0x80 | len); // MASK bit + len
        } else if (len < 65536) {
            frame.write(0x80 | 126);
            frame.write(len >> 8 & 0xFF);
            frame.write(len & 0xFF);
        } else {
            frame.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) {
                frame.write((int) (len >>> (i * 8)) & 0xFF);
            }
        }
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        frame.write(mask);
        for (int i = 0; i < payload.length; i++) {
            frame.write(payload[i] ^ mask[i % 4]);
        }
        out.write(frame.toByteArray());
        out.flush();
    }

    /** 阻塞读取消息直到关闭（监听线程调用）。返回 false 表示连接已关闭。 */
    public boolean readLoop(Listener listener) {
        try {
            InputStream in = socket.getInputStream();
            while (true) {
                int b1 = in.read();
                if (b1 == -1) { listener.onClose(1006, "连接断开"); return false; }
                boolean fin = (b1 & 0x80) != 0;
                int opcode = b1 & 0x0F;

                int b2 = in.read();
                if (b2 == -1) { listener.onClose(1006, "连接断开"); return false; }
                boolean masked = (b2 & 0x80) != 0;
                long len = b2 & 0x7F;
                if (len == 126) {
                    len = ((long) in.read() << 8) | in.read();
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
                }

                byte[] mask = new byte[4];
                if (masked) {
                    if (readFully(in, mask, 4) != 4) return false;
                }

                byte[] payload = new byte[(int) len];
                if (readFully(in, payload, (int) len) != (int) len) {
                    listener.onClose(1006, "连接断开");
                    return false;
                }
                if (masked) {
                    for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
                }

                switch (opcode) {
                    case 0x1: // text
                        listener.onText(new String(payload, "UTF-8"), fin);
                        break;
                    case 0x2: // binary
                        listener.onBinary(payload, fin);
                        break;
                    case 0x8: { // close
                        int code = 1000;
                        String reason = "";
                        if (payload.length >= 2) {
                            code = (payload[0] & 0xFF) << 8 | (payload[1] & 0xFF);
                            if (payload.length > 2) reason = new String(payload, 2, payload.length - 2, "UTF-8");
                        }
                        try { sendClose(1000, ""); } catch (IOException ignored) {}
                        listener.onClose(code, reason);
                        return false;
                    }
                    case 0x9: { // ping → pong
                        try { sendFrame(0xA, payload); } catch (IOException ignored) {}
                        break;
                    }
                    case 0xA: // pong
                        break;
                    default:
                        break;
                }
            }
        } catch (Exception e) {
            listener.onError(e);
            return false;
        }
    }

    private static int readFully(InputStream in, byte[] buf, int n) throws IOException {
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r == -1) break;
            off += r;
        }
        return off;
    }

    public void closeQuietly() {
        try { socket.close(); } catch (Exception ignored) {}
    }
}
