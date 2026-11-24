package com.feiyu.readerbridge.impl;

import com.feiyu.readerbridge.ReaderBridgeMod;
import com.feiyu.readerbridge.config.ReaderBridgeConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 零外部依赖的 WebSocket 客户端（基于 JDK 11+ 内置的 {@link java.net.http.WebSocket}）。
 *
 * <p>职责与约束：</p>
 * <ul>
 *   <li>连接 {@code ws://127.0.0.1:<port>/ws}，端口来自配置，默认 47210。</li>
 *   <li>只允许回环地址，建立连接前对 host 做一次显式校验。</li>
 *   <li>断线自动重连：以 {@code reconnectIntervalMs} 为基础做指数退避，并有上限保护；
 *       失败日志按次数降级，避免断线刷屏。</li>
 *   <li>所有网络线程都是 daemon 线程，模组卸载 / 游戏退出不会阻塞 JVM。</li>
 *   <li>协议解析全部委托给 {@link Protocol}，坏数据不会抛异常、不会断连。</li>
 * </ul>
 */
public final class BridgeWebSocketClient implements AutoCloseable {

    private static final String LOOPBACK_HOST = "127.0.0.1";
    private static final String WS_PATH = "/ws";
    /** 指数退避的绝对上限。 */
    private static final long MAX_BACKOFF_MS = 30_000L;
    /** 连续失败日志的采样间隔：超过前 3 次后，每 N 次才输出一条。 */
    private static final int FAILURE_LOG_SAMPLE = 10;
    /** 连续多少个心跳周期「没有任何帧到达」就判定连接已死。 */
    private static final int MAX_UNANSWERED_HEARTBEATS = 3;
    /** 心跳统计日志的采样间隔：每 N 次 ping 输出一条 INFO 汇总，便于在日志里 grep 心跳证据。 */
    private static final int HEARTBEAT_LOG_EVERY = 4;

    private final Object lifecycleLock = new Object();
    private final Consumer<Protocol.Message> messageHandler;
    private final StringBuilder partialFrame = new StringBuilder();

    private volatile boolean shutdown;
    private volatile boolean started;
    private volatile boolean connected;
    private volatile String wsUrl = "";
    private volatile WebSocket webSocket;
    private volatile int reconnectAttempt;
    private volatile int consecutiveFailures;

    /** 心跳未响应计数：任意帧到达（含 pong / state / event）都清零。 */
    private final AtomicInteger unansweredHeartbeats = new AtomicInteger();
    /** 心跳统计（仅用于日志证据，不参与逻辑判断）。 */
    private final AtomicInteger pingsSent = new AtomicInteger();
    private final AtomicInteger pongsReceived = new AtomicInteger();

    private ScheduledExecutorService scheduler;
    private ExecutorService worker;
    private HttpClient httpClient;
    private boolean reconnectScheduled;
    private ScheduledFuture<?> heartbeatTask;

    public BridgeWebSocketClient(Consumer<Protocol.Message> messageHandler) {
        this.messageHandler = messageHandler;
    }

    // ------------------------------------------------------------------ 生命周期

    /**
     * 启动客户端。幂等：重复调用只会启动一次。
     */
    public void start() {
        synchronized (lifecycleLock) {
            startInternal();
        }
    }

    /** 启动主体。调用方必须已持有 {@link #lifecycleLock}。 */
    private void startInternal() {
        if (shutdown) {
            ReaderBridgeMod.LOGGER.debug("[readerbridge] 客户端已关闭，忽略 start()");
            return;
        }
        if (started) {
            return;
        }
        if (!ReaderBridgeConfig.enabled()) {
            ReaderBridgeMod.LOGGER.info("[readerbridge] 配置 enabled=false，不建立连接");
            return;
        }
        int port = ReaderBridgeConfig.wsPort();
        String url = "ws://" + LOOPBACK_HOST + ":" + port + WS_PATH;
        if (!isLoopbackUrl(url)) {
            // 理论不可达（host 是常量），保留显式校验以满足「只允许连回环」的硬性约束。
            ReaderBridgeMod.LOGGER.error("[readerbridge] 非回环地址被拒绝: {}", url);
            return;
        }
        wsUrl = url;

        scheduler = Executors.newSingleThreadScheduledExecutor(daemonFactory("readerbridge-ws-sched"));
        worker = Executors.newSingleThreadExecutor(daemonFactory("readerbridge-ws-io"));
        httpClient = HttpClient.newBuilder()
                .executor(worker)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        started = true;
        ReaderBridgeMod.LOGGER.info("[readerbridge] 准备连接 {}", url);
        scheduleHeartbeat();
        connect();
    }

    /**
     * 配置变更后重建连接（端口 / enabled / 重连间隔可能已变化）。
     *
     * @param reason 变更原因，仅用于日志
     */
    public void restart(String reason) {
        ReaderBridgeMod.LOGGER.info("[readerbridge] 配置变更（{}），重建 WebSocket 连接", reason);
        synchronized (lifecycleLock) {
            if (shutdown) {
                return;
            }
            // 「停止 + 重启」必须在同一把锁内完成。
            // 否则并发进来的 start()（例如配置加载线程与 FMLLoadCompleteEvent 竞争）
            // 会在 stopInternal 与 start 之间插入，导致同时存在两套线程池：
            // 旧的被 shutdownNow 后 httpClient 仍持有它，重连时抛
            // RejectedExecutionException: ... Terminated ...，并泄漏线程。
            stopInternal(true);
            startInternal();
        }
    }

    /** 优雅关闭并禁止再次启动（游戏结束 / 服务端停止时调用）。 */
    public void shutdown() {
        synchronized (lifecycleLock) {
            if (shutdown) {
                return;
            }
            shutdown = true;
        }
        stopInternal(true);
        ReaderBridgeMod.LOGGER.info("[readerbridge] WebSocket 客户端已关闭");
    }

    @Override
    public void close() {
        shutdown();
    }

    /** 真正释放资源。调用方需自行持有 lifecycleLock 之外的语义。 */
    private void stopInternal(boolean closeSocket) {
        WebSocket socket;
        ScheduledExecutorService sched;
        ExecutorService io;
        ScheduledFuture<?> heartbeat;

        synchronized (lifecycleLock) {
            socket = webSocket;
            sched = scheduler;
            io = worker;
            heartbeat = heartbeatTask;
            webSocket = null;
            scheduler = null;
            worker = null;
            httpClient = null;
            heartbeatTask = null;
            started = false;
            connected = false;
            reconnectScheduled = false;
            reconnectAttempt = 0;
            consecutiveFailures = 0;
            unansweredHeartbeats.set(0);
        }

        if (heartbeat != null) {
            heartbeat.cancel(false);
        }

        if (closeSocket && socket != null) {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "bridge shutdown");
            } catch (Throwable ignored) {
                // 连接可能已断开，忽略
            }
            try {
                socket.abort();
            } catch (Throwable ignored) {
                // 同上
            }
        }
        if (sched != null) {
            sched.shutdownNow();
        }
        if (io != null) {
            io.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ 对外状态 / 发送

    public boolean isConnected() {
        return connected;
    }

    public String getWsUrl() {
        return wsUrl;
    }

    /**
     * 发送一条文本消息。
     *
     * @param json JSON 文本
     * @return 已提交发送返回 {@code true}；未连接或出错返回 {@code false}
     */
    public boolean send(String json) {
        WebSocket socket = webSocket;
        if (socket == null || !connected) {
            return false;
        }
        try {
            socket.sendText(json, true).exceptionally(error -> {
                ReaderBridgeMod.LOGGER.debug("[readerbridge] 发送失败: {}", error.toString());
                return null;
            });
            return true;
        } catch (Throwable t) {
            ReaderBridgeMod.LOGGER.debug("[readerbridge] 发送异常: {}", t.toString());
            return false;
        }
    }

    // ------------------------------------------------------------------ 应用层心跳

    /**
     * 按配置的 {@code heartbeatIntervalMs} 启动周期心跳。
     *
     * <p>用 {@code scheduleAtFixedRate} 保证固定节奏；重连后会重新注册（由
     * {@link #startInternal()} 触发），旧任务在 {@link #stopInternal(boolean)} 中取消。</p>
     */
    private void scheduleHeartbeat() {
        ScheduledExecutorService sched = scheduler;
        if (sched == null) {
            return;
        }
        long interval = Math.max(1000L, ReaderBridgeConfig.heartbeatIntervalMs());
        try {
            ScheduledFuture<?> previous = heartbeatTask;
            if (previous != null) {
                previous.cancel(false);
            }
            heartbeatTask = sched.scheduleAtFixedRate(this::heartbeatTick, interval, interval,
                    TimeUnit.MILLISECONDS);
            ReaderBridgeMod.LOGGER.debug("[readerbridge] 心跳已启用，间隔 {} ms（连续 {} 次无任何帧即重连）",
                    interval, MAX_UNANSWERED_HEARTBEATS);
        } catch (RejectedExecutionException ignored) {
            // 客户端已关闭
        }
    }

    /**
     * 一个心跳周期。
     *
     * <p>语义：先检查「连续多少个周期没有任何帧到达」；达到上限即判定连接已死，主动 abort
     * 并交给既有的指数退避重连。否则发送一条 {@code {"type":"ping"}}，未响应计数 +1。
     * 任何帧（pong / state / event / error）到达都会把计数清零，因此朗读期间 state 流
     * 不会造成误判，也不会重复计数。</p>
     */
    private void heartbeatTick() {
        if (shutdown || !started || !connected) {
            unansweredHeartbeats.set(0);
            return;
        }
        int unanswered = unansweredHeartbeats.get();
        if (unanswered >= MAX_UNANSWERED_HEARTBEATS) {
            ReaderBridgeMod.LOGGER.warn(
                    "[readerbridge] 连续 {} 个心跳周期未收到任何帧，判定连接已死，主动断开并重连: {}",
                    unanswered, wsUrl);
            unansweredHeartbeats.set(0);
            abandonDeadConnection();
            return;
        }
        unansweredHeartbeats.incrementAndGet();
        boolean sent = send("{\"type\":\"ping\"}");
        int sentCount = pingsSent.incrementAndGet();
        ReaderBridgeMod.LOGGER.debug("[readerbridge] 心跳 ping 发送（累计 {} 次，未响应计数 {}），sent={}",
                sentCount, unanswered + 1, sent);
        if (sentCount % HEARTBEAT_LOG_EVERY == 0) {
            // 每 N 次 ping 给一条 INFO 汇总，避免刷屏又能作为「心跳通道已通」的日志证据。
            ReaderBridgeMod.LOGGER.info(
                    "[readerbridge] 心跳统计：累计发送 ping {} 次，收到 pong {} 次，未响应计数 {}",
                    sentCount, pongsReceived.get(), unansweredHeartbeats.get());
        }
    }

    /** 主动断开一个被判定为已死的连接，并复用既有重连路径。 */
    private void abandonDeadConnection() {
        WebSocket socket = webSocket;
        connected = false;
        webSocket = null;
        if (socket != null) {
            try {
                socket.abort();
            } catch (Throwable ignored) {
                // 已经断开
            }
        }
        scheduleReconnect();
    }

    // ------------------------------------------------------------------ 连接 / 重连

    /**
     * 每次发起连接尝试 +1、连接成功后清零的独立计数。
     *
     * <p>刻意不复用既有的 {@code reconnectAttempt}（退避用）与 {@code consecutiveFailures}
     * （日志降噪用）：那两个字段承载既有语义且不是原子变量，复用会把「UI 展示」
     * 和「退避/日志」耦死。这里是纯新增的只读扩展，不改变任何既有行为。</p>
     */
    private final java.util.concurrent.atomic.AtomicInteger connectionAttempts =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 自上次连接成功以来累计的连接尝试次数（包内只读 getter）。 */
    int attempts() {
        return connectionAttempts.get();
    }

    private void connect() {
        connectionAttempts.incrementAndGet();
        final String url;
        final HttpClient client;
        synchronized (lifecycleLock) {
            if (shutdown || !started) {
                return;
            }
            url = wsUrl;
            client = httpClient;
        }
        if (client == null || url.isEmpty()) {
            return;
        }
        try {
            client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(URI.create(url), new Listener())
                    .whenComplete((socket, error) -> {
                        if (error != null) {
                            onConnectFailure(url, error);
                        }
                    });
        } catch (Throwable t) {
            onConnectFailure(url, t);
        }
    }

    private void onConnectFailure(String url, Throwable error) {
        connected = false;
        consecutiveFailures++;
        int failures = consecutiveFailures;
        if (failures == 1) {
            // 首次失败用 WARN 告知一次「App 未启动 / 连接被拒」，之后静默重试，避免每 3 秒刷屏。
            ReaderBridgeMod.LOGGER.warn("[readerbridge] 连接 {} 失败（App 可能未启动），将按配置间隔持续重试: {}",
                    url, describe(error));
        } else if (failures % FAILURE_LOG_SAMPLE == 0) {
            // 持续失败时降级为 debug，避免刷屏
            ReaderBridgeMod.LOGGER.debug("[readerbridge] 连接 {} 仍失败（累计 {} 次）: {}",
                    url, failures, describe(error));
        }
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        synchronized (lifecycleLock) {
            if (shutdown || !started) {
                return;
            }
            if (reconnectScheduled) {
                return;
            }
            reconnectScheduled = true;
        }
        ScheduledExecutorService sched = scheduler;
        if (sched == null) {
            return;
        }
        int attempt = reconnectAttempt++;
        long base = Math.max(500L, ReaderBridgeConfig.reconnectIntervalMs());
        long cap = Math.max(base, MAX_BACKOFF_MS);
        long delay = base;
        for (int i = 0; i < Math.min(attempt, 6) && delay < cap; i++) {
            delay = Math.min(delay * 2L, cap);
        }
        try {
            sched.schedule(() -> {
                synchronized (lifecycleLock) {
                    reconnectScheduled = false;
                }
                connect();
            }, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // 客户端已关闭
        }
    }

    // ------------------------------------------------------------------ 接收

    private void handleLine(String line) {
        Protocol.Message message = Protocol.parseLine(line);
        if (message == null) {
            return;
        }
        if (Protocol.TYPE_PONG.equals(message.type)) {
            // 心跳回执：清零未响应计数并记录 DEBUG（计数清零在任何帧到达时都会做，
            // 这里只是把 pong 单独统计出来，供日志验证「心跳往返真的通了」）。
            int pongs = pongsReceived.incrementAndGet();
            unansweredHeartbeats.set(0);
            ReaderBridgeMod.LOGGER.debug("[readerbridge] 收到 pong（累计 {} 次），未响应计数已清零", pongs);
        }
        try {
            messageHandler.accept(message);
        } catch (Throwable t) {
            // 上层处理异常不能反向影响 WebSocket 连接
            ReaderBridgeMod.LOGGER.error("[readerbridge] 消息处理异常（已隔离）", t);
        }
    }

    /** 把累积的帧数据按行切分并逐行解析。 */
    private void handleFrame(String frame) {
        int start = 0;
        int length = frame.length();
        for (int i = 0; i < length; i++) {
            if (frame.charAt(i) == '\n') {
                if (i > start) {
                    handleLine(frame.substring(start, i));
                }
                start = i + 1;
            }
        }
        if (start < length) {
            String tail = frame.substring(start);
            if (!tail.trim().isEmpty()) {
                handleLine(tail);
            }
        }
    }

    private static String describe(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null ? "" : (": " + message));
    }

    /** 校验 URL 指向回环地址。 */
    static boolean isLoopbackUrl(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) {
                return false;
            }
            String normalized = host.trim().toLowerCase(java.util.Locale.ROOT);
            return LOOPBACK_HOST.equals(normalized)
                    || "localhost".equals(normalized)
                    || "::1".equals(normalized)
                    || "[::1]".equals(normalized);
        } catch (Throwable t) {
            return false;
        }
    }

    private static ThreadFactory daemonFactory(String name) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, name + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    // ------------------------------------------------------------------ WebSocket.Listener

    private final class Listener implements WebSocket.Listener {

        @Override
        public void onOpen(WebSocket socket) {
            webSocket = socket;
            connected = true;
            reconnectAttempt = 0;
            consecutiveFailures = 0;
            connectionAttempts.set(0);
            unansweredHeartbeats.set(0);
            ReaderBridgeMod.LOGGER.info("[readerbridge] WebSocket 已连接: {}", wsUrl);
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            // 任何帧到达都是「连接仍存活」的证据：无论能否解析成功，先清零未响应计数。
            unansweredHeartbeats.set(0);
            try {
                String chunk = data.toString();
                String frame;
                synchronized (partialFrame) {
                    // JDK 的 data 是「累计」语义；这里同时兼容增量语义，避免依赖具体实现细节。
                    if (chunk.startsWith(partialFrame.toString())) {
                        partialFrame.setLength(0);
                        partialFrame.append(chunk);
                    } else {
                        partialFrame.append(chunk);
                    }
                    if (!last) {
                        socket.request(1);
                        return null;
                    }
                    frame = partialFrame.toString();
                    partialFrame.setLength(0);
                }
                handleFrame(frame);
            } catch (Throwable t) {
                ReaderBridgeMod.LOGGER.error("[readerbridge] WebSocket 文本帧处理异常（已隔离）", t);
            }
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            connected = false;
            webSocket = null;
            ReaderBridgeMod.LOGGER.info("[readerbridge] WebSocket 已断开: code={}, reason={}", statusCode, reason);
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            connected = false;
            webSocket = null;
            ReaderBridgeMod.LOGGER.debug("[readerbridge] WebSocket 错误: {}", describe(error));
            scheduleReconnect();
        }
    }
}
