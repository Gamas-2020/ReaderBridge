package com.feiyu.readerbridge.impl;

import com.feiyu.readerbridge.ReaderBridgeMod;
import com.feiyu.readerbridge.api.ReaderBridgeApi;
import com.feiyu.readerbridge.api.ReaderStateListener;
import com.feiyu.readerbridge.api.ReadingState;
import com.feiyu.readerbridge.config.ReaderBridgeConfig;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link ReaderBridgeApi} 的默认实现（进程内单例）。
 *
 * <p>关键职责：</p>
 * <ul>
 *   <li>持有一份「最近一次状态」的不可变快照，{@link #getState()} 任意线程可读。</li>
 *   <li>把 WebSocket 线程上收到的状态变化，通过 {@link MainThreadDispatcher} 派发到主线程后
 *       再回调监听器 —— 这是 API 的硬性线程约定。</li>
 *   <li>监听器回调逐一 try/catch，单个监听器异常不影响其它监听器，也不会断开连接。</li>
 * </ul>
 */
public final class ReaderBridgeApiImpl implements ReaderBridgeApi {

    private static volatile ReaderBridgeApiImpl instance;

    private final CopyOnWriteArrayList<ReaderStateListener> listeners = new CopyOnWriteArrayList<>();
    private final BridgeWebSocketClient client;

    private volatile ReadingStateSnapshot currentState = ReadingStateSnapshot.INACTIVE;

    /**
     * 最近一次实际应用到连接的配置签名（enabled|port|interval）。
     *
     * <p>Forge 在配置首次加载后可能紧接着再派发一次 {@code ModConfigEvent.Reloading}，
     * 若不做去重就会和启动期的 {@code start()} 竞争，导致旧连接池被提前关闭、
     * 出现一次无意义的 {@code RejectedExecutionException} 与多余重连。
     * 这里只在签名真正变化时才重建连接。</p>
     */
    private volatile String appliedSignature;

    private ReaderBridgeApiImpl() {
        this.client = new BridgeWebSocketClient(this::onMessage);
    }

    /**
     * 获取（必要时创建）单例。线程安全：双检锁。
     *
     * @return 单例实例
     */
    public static ReaderBridgeApiImpl install() {
        ReaderBridgeApiImpl local = instance;
        if (local == null) {
            synchronized (ReaderBridgeApiImpl.class) {
                local = instance;
                if (local == null) {
                    local = new ReaderBridgeApiImpl();
                    instance = local;
                }
            }
        }
        return local;
    }

    /** 已安装的实例；未安装返回 {@code null}。 */
    public static ReaderBridgeApiImpl instanceOrNull() {
        return instance;
    }

    // ------------------------------------------------------------------ ReaderBridgeApi

    @Override
    public ReadingState getState() {
        // 快照不可变，volatile 读即可，任意线程安全。
        return currentState;
    }

    @Override
    public void addListener(ReaderStateListener listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.addIfAbsent(listener);
        ReaderBridgeMod.LOGGER.debug("[readerbridge] 已注册监听器 {}（当前共 {} 个）",
                listener.getClass().getName(), listeners.size());
    }

    @Override
    public void removeListener(ReaderStateListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    @Override
    public void requestPause() {
        sendCommand("pause");
    }

    @Override
    public void requestResume() {
        sendCommand("resume");
    }

    @Override
    public void requestPlay() {
        sendCommand("play");
    }

    @Override
    public void requestStop() {
        sendCommand("stop");
    }

    @Override
    public void requestNext() {
        sendCommand("next");
    }

    @Override
    public void requestPrev() {
        sendCommand("prev");
    }

    @Override
    public boolean isConnected() {
        return client.isConnected();
    }

    @Override
    public String wsUrl() {
        return client.getWsUrl();
    }

    // ------------------------------------------------------------------ 生命周期（供模组内部调用）

    /** 启动 WebSocket 客户端（幂等）。 */
    public void start() {
        appliedSignature = configSignature();
        client.start();
    }

    /**
     * 配置变更后重建连接。
     *
     * <p>只有 enabled / wsPort / reconnectIntervalMs / heartbeatIntervalMs 真正变化时才重建，
     * 避免启动期 Forge 在 Loading 之后紧接的 Reloading 把刚建立的连接打断。</p>
     *
     * @param reason 变更来源，仅用于日志
     */
    public void restart(String reason) {
        String signature = configSignature();
        if (signature.equals(appliedSignature)) {
            ReaderBridgeMod.LOGGER.debug(
                    "[readerbridge] 配置事件（{}）但连接参数未变化，保持现有连接", reason);
            return;
        }
        appliedSignature = signature;
        client.restart(reason);
    }

    /**
     * {@inheritDoc}
     *
     * <p>取自 {@code BridgeWebSocketClient} 的只读计数（每次发起连接尝试 +1，成功后清零）。</p>
     */
    @Override
    public int connectionAttempts() {
        return client.attempts();
    }

    /**
     * {@inheritDoc}
     *
     * <p>写回配置（clamp 到 [500, 60000]）后立刻按签名比对重建连接——
     * 复用 {@link #restart(String)} 的 {@code appliedSignature} 去重逻辑，
     * 不绕开既有的「同参数不打断连接」保护。</p>
     */
    @Override
    public void setReconnectIntervalMs(long ms) {
        int applied = ReaderBridgeConfig.applyReconnectInterval(ms);
        restart("setReconnectIntervalMs=" + applied);
    }

    /** 关闭连接与线程池。 */
    public void shutdown() {
        appliedSignature = null;
        client.shutdown();
    }

    /** 影响连接行为的配置签名。 */
    private static String configSignature() {
        return ReaderBridgeConfig.enabled() + "|" + ReaderBridgeConfig.wsPort() + "|"
                + ReaderBridgeConfig.reconnectIntervalMs() + "|"
                + ReaderBridgeConfig.heartbeatIntervalMs();
    }

    // ------------------------------------------------------------------ 消息处理（WebSocket 线程）

    private void onMessage(Protocol.Message message) {
        switch (message.type) {
            case Protocol.TYPE_STATE -> handleState(message);
            case Protocol.TYPE_HELLO -> ReaderBridgeMod.LOGGER.info(
                    "[readerbridge] 收到 hello: service={}, version={}",
                    textOf(message.payload, "service"), textOf(message.payload, "version"));
            case Protocol.TYPE_EVENT -> handleEvent(message);
            case Protocol.TYPE_ERROR -> ReaderBridgeMod.LOGGER.warn(
                    "[readerbridge] App 返回错误: {}", textOf(message.payload, "message"));
            case Protocol.TYPE_PONG -> ReaderBridgeMod.LOGGER.debug("[readerbridge] 收到心跳回执 pong");
            default -> ReaderBridgeMod.LOGGER.debug(
                    "[readerbridge] 忽略未知消息类型: {}", message.type);
        }
    }

    private void handleState(Protocol.Message message) {
        ReadingStateSnapshot snapshot = Protocol.parseState(message.payload);
        currentState = snapshot;
        if (ReaderBridgeConfig.logStateChanges()) {
            ReaderBridgeMod.LOGGER.info("[readerbridge] 状态更新: {}", snapshot);
        }
        // 硬性要求：监听器回调必须在主线程。这里只做派发，回调体在主线程执行。
        final ReadingState stateForListeners = snapshot;
        MainThreadDispatcher.dispatch(() -> notifyListeners(stateForListeners));
    }

    private void handleEvent(Protocol.Message message) {
        String eventName = message.eventName;
        if (eventName == null || eventName.isEmpty()) {
            return;
        }
        switch (eventName) {
            case "server.ready" -> ReaderBridgeMod.LOGGER.info(
                    "[readerbridge] 服务端就绪: wsUrl={}, httpBase={}, port={}",
                    textOf(message.payload, "wsUrl"), textOf(message.payload, "httpBase"),
                    textOf(message.payload, "port"));
            case "tts.audio" -> {
                // 只做文字投射，模组不播放音频；日志受 logStateChanges 控制，避免关掉日志时刷屏。
                if (ReaderBridgeConfig.logStateChanges()) {
                    ReaderBridgeMod.LOGGER.debug(
                            "[readerbridge] tts.audio path={}, durationMs={}, sentenceIndex={}, sampleRate={}, voiceId={}",
                            textOf(message.payload, "path"), textOf(message.payload, "durationMs"),
                            textOf(message.payload, "sentenceIndex"), textOf(message.payload, "sampleRate"),
                            textOf(message.payload, "voiceId"));
                }
                // 音频走游戏内 OpenAL：仅客户端执行，且 payload 需带 wavPath 才动作。
                // 用 DistExecutor 隔离，专用服务端不会链接 net.minecraft.client.* / LWJGL 音频类。
                if (net.minecraftforge.fml.loading.FMLEnvironment.dist
                        == net.minecraftforge.api.distmarker.Dist.CLIENT) {
                    net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                            net.minecraftforge.api.distmarker.Dist.CLIENT,
                            () -> () -> com.feiyu.readerbridge.client.audio.TtsAudioPlayer.INSTANCE
                                    .onTtsAudio(message.payload));
                }
            }
            case "tts.error" -> ReaderBridgeMod.LOGGER.warn(
                    "[readerbridge] tts.error: {}", textOf(message.payload, "message"));
            default -> ReaderBridgeMod.LOGGER.debug("[readerbridge] 忽略事件: {}", eventName);
        }
    }

    /** 只在主线程被调用。 */
    private void notifyListeners(ReadingState state) {
        for (ReaderStateListener listener : listeners) {
            try {
                listener.onReadingStateChanged(state);
            } catch (Throwable t) {
                // 单个监听器异常隔离：其它监听器与 WS 连接都不受影响。
                ReaderBridgeMod.LOGGER.error("[readerbridge] 监听器 {} 回调异常（已隔离）",
                        listener.getClass().getName(), t);
            }
        }
    }

    private void sendCommand(String action) {
        if (!client.isConnected()) {
            ReaderBridgeMod.LOGGER.debug("[readerbridge] 未连接，忽略命令: {}", action);
            return;
        }
        // action 只会是 pause / resume 常量，不存在注入风险。
        boolean sent = client.send("{\"type\":\"command\",\"action\":\"" + action + "\"}");
        if (!sent) {
            ReaderBridgeMod.LOGGER.debug("[readerbridge] 命令发送失败: {}", action);
        }
    }

    private static String textOf(com.google.gson.JsonObject payload, String key) {
        try {
            if (payload == null || !payload.has(key) || payload.get(key).isJsonNull()) {
                return "null";
            }
            return payload.get(key).getAsString();
        } catch (Throwable ignored) {
            return "null";
        }
    }
}
