package com.feiyu.readerbridge.api;

/**
 * 阅读状态变化监听器。
 *
 * <p><b>线程安全硬性要求（实现方保证，调用方依赖）：</b></p>
 * <ul>
 *   <li>{@link #onReadingStateChanged(ReadingState)} <b>永远</b> 在 Minecraft 主线程上被调用。
 *       客户端主线程即渲染线程（{@code Minecraft} 所在的线程），专用服务端主线程即
 *       Server thread。</li>
 *   <li>绝不允许在 WebSocket 接收线程上直接回调监听器。</li>
 *   <li>如果主线程暂时不可用（例如仍在 {@code FMLCommonSetupEvent} 阶段），回调会被排队，
 *       等主线程可用时再按顺序补发。</li>
 *   <li>每个监听器的回调都被 try/catch 包裹：某个监听器抛出异常不会影响其它监听器，
 *       也不会中断 WebSocket 连接。</li>
 * </ul>
 *
 * <p>因此监听器实现内部可以安全地访问 Minecraft 状态、操作 GUI、修改方块等，
 * 不需要再自行做 {@code Minecraft.getInstance().execute(...)} 调度。</p>
 *
 * <p>回调传入的 {@link ReadingState} 是当次产生的不可变快照，可以在任意线程持有与读取。</p>
 */
@FunctionalInterface
public interface ReaderStateListener {

    /**
     * 阅读状态发生了变化。
     *
     * <p>该回调保证在 Minecraft 主线程执行。实现方不应在其中做长时间阻塞操作。</p>
     *
     * @param newState 新的阅读状态快照，永不为 {@code null}
     */
    void onReadingStateChanged(ReadingState newState);
}
