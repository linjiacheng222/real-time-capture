package com.captureapi.net;

import com.captureapi.core.config.CaptureParams;
import com.captureapi.net.control.ControlCommandDispatcher;
import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.core.constant.ProtocolConstants;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebSocket 服务（T4.1 宿主 + T4.2/T4.3 消息入口）。
 *
 * <p>实现说明（与原计划的偏离记录）：计划选用 Java-WebSocket；Forge 模组类加载器不暴露普通
 * Gradle implementation 依赖（dev 运行实测 `ClassNotFoundException`），而 jarJar/classloader
 * 方案在 M4 阶段引入额外构建风险。本类改为**纯 JDK 的最小 RFC6455 服务端实现**（仅服务端、
 * 文本/二进制/握手/关闭/并发上限/token），零外部依赖，行为满足 T4.1~T4.3 所需。</p>
 */
public final class CaptureWebSocketServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_PAYLOAD_BYTES = 1 << 20;
    private static final int HANDSHAKE_LIMIT = 16 * 1024;

    private final InetSocketAddress address;
    private final NetworkServiceManager manager;
    private final Map<Client, ConnectionInfo> connections = new ConcurrentHashMap<>();

    private static final int SEND_QUEUE_CAPACITY = 32;

    private final AtomicLong slowDisconnects = new AtomicLong();

    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    public CaptureWebSocketServer(InetSocketAddress address, NetworkServiceManager manager) {
        this.address = address;
        this.manager = manager;
    }

    /** 启动监听（异步线程；端口占用抛出 IOException）。 */
    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(address);
        serverSocket = socket;
        running = true;
        Thread thread = new Thread(this::acceptLoop, "captureapi-ws-accept");
        thread.setDaemon(true);
        acceptThread = thread;
        thread.start();
    }

    /** 停止服务并关闭全部连接（幂等）。 */
    public synchronized void stop(long timeoutMillis) {
        running = false;
        ServerSocket socket = serverSocket;
        serverSocket = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 关闭失败无碍
            }
        }
        for (Client client : connections.keySet()) {
            client.close(ProtocolConstants.WS_CLOSE_GOING_AWAY, "server shutting down");
        }
        connections.clear();
        Thread thread = acceptThread;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(Math.max(0L, timeoutMillis));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        acceptThread = null;
    }

    private void acceptLoop() {
        LOGGER.info("{} [NET/WS] 监听 ws://{}:{}/", CaptureConstants.LOG_MARKER,
                address.getAddress() == null ? "0.0.0.0" : address.getAddress().getHostAddress(),
                address.getPort());
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                Thread handler = new Thread(() -> handleConnection(socket), "captureapi-ws-client");
                handler.setDaemon(true);
                handler.start();
            } catch (IOException e) {
                if (running) {
                    manager.recordError();
                    LOGGER.warn("{} [NET/WS] accept 异常", CaptureConstants.LOG_MARKER, e);
                }
                break;
            }
        }
    }

    private void handleConnection(Socket socket) {
        try {
            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            OutputStream output = socket.getOutputStream();
            Handshake handshake = readHandshake(input);
            if (handshake == null) {
                reject(output, ProtocolConstants.HTTP_BAD_REQUEST, "Bad Request");
                return;
            }
            if (!manager.authorizeWs(handshake.pathWithQuery())) {
                manager.recordRejectedConnection();
                reject(output, ProtocolConstants.HTTP_UNAUTHORIZED, "Unauthorized");
                return;
            }
            if (connections.size() >= CaptureParams.get().maxConnections()) {
                manager.recordRejectedConnection();
                reject(output, ProtocolConstants.HTTP_SERVICE_UNAVAILABLE, "Service Unavailable");
                return;
            }
            String accept = websocketAccept(handshake.key());
            String response = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
            output.write(response.getBytes(StandardCharsets.ISO_8859_1));
            output.flush();
            Client client = new Client(socket, input, output);
            connections.put(client, new ConnectionInfo(socket.getRemoteSocketAddress().toString(),
                    com.captureapi.core.time.CaptureClock.get().nowNanos()));
            LOGGER.info("{} [NET/WS] 连接打开：{}，当前连接数={}", CaptureConstants.LOG_MARKER,
                    socket.getRemoteSocketAddress(), connections.size());
            try {
                client.readLoop();
            } finally {
                connections.remove(client);
                LOGGER.info("{} [NET/WS] 连接关闭：{}，当前连接数={}", CaptureConstants.LOG_MARKER,
                        socket.getRemoteSocketAddress(), connections.size());
            }
        } catch (Throwable t) {
            manager.recordError();
            LOGGER.warn("{} [NET/WS] 连接处理异常", CaptureConstants.LOG_MARKER, t);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
    }

    private static void reject(OutputStream output, int code, String reason) throws IOException {
        String body = reason;
        String response = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n"
                + "Connection: close\r\n\r\n" + body;
        output.write(response.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static Handshake readHandshake(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int matched = 0;
        int[] terminator = {'\r', '\n', '\r', '\n'};
        while (buffer.size() < HANDSHAKE_LIMIT) {
            int b = input.read();
            if (b < 0 || (b < 32 && b != '\r' && b != '\n')) {
                return null;
            }
            buffer.write(b);
            if (b == terminator[matched]) {
                matched++;
                if (matched == terminator.length) {
                    break;
                }
            } else {
                matched = b == '\r' ? 1 : 0;
            }
        }
        String request = buffer.toString(StandardCharsets.ISO_8859_1);
        String[] lines = request.split("\\r\\n");
        if (lines.length == 0 || !lines[0].startsWith("GET ")) {
            return null;
        }
        String pathWithQuery = lines[0].split(" ")[1];
        String key = null;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = lines[i].substring(colon + 1).trim();
            if (name.equals("sec-websocket-key")) {
                key = value;
            }
        }
        return key == null ? null : new Handshake(pathWithQuery, key);
    }

    private static String websocketAccept(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest((key + WEBSOCKET_GUID).getBytes(StandardCharsets.ISO_8859_1));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-1 不可用", e);
        }
    }

    /** @return 当前连接数 */
    public int connectionCount() {
        return connections.size();
    }

    /** @return 只读连接元数据快照 */
    public Map<Client, ConnectionInfo> connections() {
        return Map.copyOf(connections);
    }

    /** 广播文本（控制响应/T4.2 入口；逐连接入队，慢连接断开）。 */
    public void broadcastText(String message) {
        for (Client client : connections.keySet()) {
            client.sendText(message);
        }
    }

    /** 广播二进制（视频/音频帧 T4.2 入口；逐连接入队，慢连接断开）。 */
    public void broadcastBinary(byte[] payload) {
        for (Client client : connections.keySet()) {
            client.sendBinary(payload);
        }
    }

    /** @return 因发送队列满被断开的慢连接数 */
    public long slowDisconnectCount() {
        return slowDisconnects.get();
    }

    /** 生成随机 token（32 hex chars）。 */
    public static String randomToken() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder builder = new StringBuilder(32);
        for (byte b : bytes) {
            builder.append(String.format(Locale.ROOT, "%02x", b));
        }
        return builder.toString();
    }

    private record Handshake(String pathWithQuery, String key) {
    }

    /** 连接元数据。 */
    public record ConnectionInfo(String remoteAddress, long openedAtNanos) {
    }

    private record Outbound(int opcode, byte[] payload) {
    }

    /** 单个 WS 连接（服务端帧不加掩码；客户端帧按 RFC 要求去掩码）。 */
    private final class Client {
        private final Socket socket;
        private final BufferedInputStream input;
        private final OutputStream output;
        private final ArrayBlockingQueue<Outbound> outbound = new ArrayBlockingQueue<>(SEND_QUEUE_CAPACITY);
        private final Thread writer;
        private volatile boolean open = true;

        private Client(Socket socket, BufferedInputStream input, OutputStream output) {
            this.socket = socket;
            this.input = input;
            this.output = output;
            this.writer = new Thread(this::writeLoop, "captureapi-ws-writer");
            this.writer.setDaemon(true);
            this.writer.start();
        }

        private void writeLoop() {
            try {
                while (open) {
                    Outbound message = outbound.take();
                    sendFrame(message.opcode(), message.payload());
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                close(ProtocolConstants.WS_CLOSE_INTERNAL_ERROR, "write error");
            }
        }

        private void readLoop() throws IOException {
            while (open) {
                int first = input.read();
                if (first < 0) {
                    return;
                }
                int second = input.read();
                if (second < 0) {
                    return;
                }
                boolean masked = (second & 0x80) != 0;
                long length = second & 0x7F;
                if (length == 126L) {
                    length = ((long) input.read() << 8) | (input.read() & 0xFF);
                } else if (length == 127L) {
                    length = 0L;
                    for (int i = 0; i < 8; i++) {
                        length = (length << 8) | (input.read() & 0xFF);
                    }
                }
                if (length < 0 || length > MAX_PAYLOAD_BYTES) {
                    close(ProtocolConstants.WS_CLOSE_TOO_BIG, "payload too large");
                    return;
                }
                byte[] mask = new byte[4];
                if (masked && readFully(input, mask, 4) != 4) {
                    return;
                }
                byte[] payload = new byte[(int) length];
                if (readFully(input, payload, payload.length) != payload.length) {
                    return;
                }
                if (masked) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= mask[i & 3];
                    }
                }
                int opcode = first & 0x0F;
                switch (opcode) {
                    case 0x1 -> handleText(new String(payload, StandardCharsets.UTF_8));
                    case 0x2 -> {
                        // T4.2：客户端二进制当前无语义；忽略
                    }
                    case 0x8 -> {
                        close(1000, "normal");
                        return;
                    }
                    case 0x9 -> sendFrame(0xA, payload);
                    case 0xA -> {
                        // pong
                    }
                    default -> close(ProtocolConstants.WS_CLOSE_UNSUPPORTED, "unsupported opcode");
                }
            }
        }

        private void handleText(String message) {
            ControlCommandDispatcher.get().handle(message, this::sendText);
        }

        private void sendText(String message) {
            enqueue(0x1, message.getBytes(StandardCharsets.UTF_8));
        }

        private void sendBinary(byte[] payload) {
            enqueue(0x2, payload);
        }

        private boolean enqueue(int opcode, byte[] payload) {
            if (!open) {
                return true;
            }
            if (!outbound.offer(new Outbound(opcode, payload))) {
                slowDisconnects.incrementAndGet();
                LOGGER.warn("{} [NET/WS] 慢连接发送队列满（{}），断开：{}", CaptureConstants.LOG_MARKER,
                        SEND_QUEUE_CAPACITY, socket.getRemoteSocketAddress());
                close(ProtocolConstants.WS_CLOSE_TRY_AGAIN, "slow consumer");
                return false;
            }
            return true;
        }

        private synchronized void sendFrame(int opcode, byte[] payload) {
            if (!open) {
                return;
            }
            try {
                output.write(0x80 | (opcode & 0x0F));
                int length = payload.length;
                if (length <= ProtocolConstants.WS_PAYLOAD_SMALL_MAX) {
                    output.write(length);
                } else if (length <= ProtocolConstants.WS_PAYLOAD_MEDIUM_MAX) {
                    output.write(ProtocolConstants.WS_LENGTH_MARKER_16);
                    output.write((length >> 8) & 0xFF);
                    output.write(length & 0xFF);
                } else {
                    output.write(ProtocolConstants.WS_LENGTH_MARKER_64);
                    long longLength = length;
                    for (int i = ProtocolConstants.WS_LENGTH_64_BYTES - 1; i >= 0; i--) {
                        output.write((int) ((longLength >> (8 * i)) & 0xFF));
                    }
                }
                output.write(payload);
                output.flush();
            } catch (IOException e) {
                open = false;
            }
        }

        private void close(int code, String reason) {
            if (!open) {
                return;
            }
            byte[] reasonBytes = reason.getBytes(StandardCharsets.UTF_8);
            byte[] payload = new byte[2 + Math.min(reasonBytes.length, ProtocolConstants.WS_CLOSE_REASON_MAX)];
            payload[0] = (byte) ((code >> 8) & 0xFF);
            payload[1] = (byte) (code & 0xFF);
            System.arraycopy(reasonBytes, 0, payload, 2, payload.length - 2);
            sendFrame(0x8, payload);
            open = false;
            writer.interrupt();
            try {
                socket.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }

        private static int readFully(InputStream input, byte[] buffer, int length) throws IOException {
            int total = 0;
            while (total < length) {
                int read = input.read(buffer, total, length - total);
                if (read < 0) {
                    return total;
                }
                total += read;
            }
            return total;
        }
    }
}
