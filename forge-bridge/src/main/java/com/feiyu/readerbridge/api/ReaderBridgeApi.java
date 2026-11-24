package com.feiyu.readerbridge.api;

/**
 * 桥接模组对外暴露的只读 + 最小控制 API。
 *
 * <p>获取方式：{@link ReaderBridgeProvider#get()}（未初始化抛异常）或
 * {@link ReaderBridgeProvider#getIfPresent()}（安全探测）。</p>
 *
 * <p><b>线程安全硬性要求：</b></p>
 * <ul>
 *   <li>{@link #getState()} 可以在<b>任意线程</b>调用，返回一个不可变快照；
 *       未连接（甚至桥接尚未启动）时返回 {@code active=false} 的 inactive 快照，
 *       <b>永不返回 {@code null}</b>。</li>
 *   <li>{@link #addListener(ReaderStateListener)} /
 *       {@link #removeListener(ReaderStateListener)} 可以在任意线程调用。</li>
 *   <li><b>所有 {@link ReaderStateListener} 回调都会被派发到 Minecraft 主线程。</b>
 *       实现方式：客户端使用 {@code DistExecutor} + {@code Minecraft.getInstance().execute(...)}，
 *       专用服务端使用 {@code ServerLifecycleHooks.getCurrentServer().execute(...)}；
 *       若两者都不可用（例如 {@code FMLCommonSetupEvent} 阶段），任务会排队，
 *       等主线程可用时再 flush。绝不在 WebSocket 接收线程上直接回调监听器。</li>
 *   <li>每个监听器回调都被 try/catch 包裹，单个监听器抛异常不会影响其它监听器，
 *       也不会弄断 WebSocket 连接。</li>
 *   <li>{@link #requestPause()} / {@link #requestResume()} / {@link #requestPlay()} /
 *       {@link #requestStop()} / {@link #requestNext()} / {@link #requestPrev()}
 *       可以在任意线程调用；未连接时调用是安全的空操作。</li>
 * </ul>
 */
public interface ReaderBridgeApi {

    /**
     * 获取当前阅读状态快照。
     *
     * <p>可在任意线程调用。未连接时返回 inactive（{@code active=false}）快照，
     * 永不为 {@code null}。</p>
     *
     * @return 不可变的状态快照
     */
    ReadingState getState();

    /**
     * 注册状态变化监听器。
     *
     * <p>回调保证在主线程执行（见类级 Javadoc）。重复注册同一实例会被忽略。</p>
     *
     * @param listener 监听器，不能为 {@code null}
     */
    void addListener(ReaderStateListener listener);

    /**
     * 移除已注册的监听器。未注册的实例会被静默忽略。
     *
     * @param listener 监听器，不能为 {@code null}
     */
    void removeListener(ReaderStateListener listener);

    /**
     * 请求 App 侧暂停朗读。
     *
     * <p>可在任意线程调用；未连接时是安全的空操作。</p>
     */
    void requestPause();

    /**
     * 请求 App 侧恢复朗读。
     *
     * <p>可在任意线程调用；未连接时是安全的空操作。</p>
     */
    void requestResume();

    /**
     * 请求 App 侧开始/继续朗读（对应协议动作 {@code play}）。
     *
     * <p>与 {@link #requestResume()} 的区别：{@code resume} 只对已暂停的会话生效，
     * {@code play} 是「开始播放」语义，具体行为由 App 侧决定。</p>
     *
     * <p>可在任意线程调用；未连接时是安全的空操作。</p>
     */
    void requestPlay();

    /**
     * 请求 App 侧停止朗读（对应协议动作 {@code stop}）。
     *
     * <p>可在任意线程调用；未连接时是安全的空操作。</p>
     */
    void requestStop();

    /**
     * 请求 App 侧跳到下一句（对应协议动作 {@code next}）。
     *
     * <p>可在任意线程调用；未连接时是安全的空操作。</p>
     */
    void requestNext();

    /**
     * 请求 App 侧回到上一句（对应协议动作 {@code prev}）。
     *
     * <p>可在任意线程调用；未连接时是安全的空操作。</p>
     */
    void requestPrev();

    /**
     * 当前是否已与 App 建立 WebSocket 连接。
     *
     * @return 已连接返回 {@code true}
     */
    boolean isConnected();

    /**
     * 当前（或期望）连接的 WebSocket 地址，便于诊断。
     *
     * <p>即 {@code ws://127.0.0.1:<port>/ws}。尚未初始化时返回空字符串。</p>
     *
     * @return 地址字符串
     */
    String wsUrl();

    /**
     * 自上次连接成功后累计的连接尝试次数；从未成功连接过时同样累计。UI 展示用。
     *
     * <p><b>增量 API</b>：Java 8+ 的 default 方法，加入它不构成破坏性变更——
     * 已有的第三方实现无需改动即可继续编译运行（拿到兜底值 0）。</p>
     *
     * @return 连接尝试次数，非负
     */
    default int connectionAttempts() {
        return 0;
    }

    /**
     * 运行时修改断线重连的基础间隔（毫秒）。实现内部会 clamp 到配置允许的范围，
     * 写回配置文件并立即重建连接使其生效。
     *
     * <p><b>增量 API</b>：Java 8+ 的 default 方法，属向后兼容的扩展；
     * 默认实现为空操作，未升级的实现不会被破坏。</p>
     *
     * @param ms 期望的重连间隔（毫秒）
     */
    default void setReconnectIntervalMs(long ms) {
    }
}
