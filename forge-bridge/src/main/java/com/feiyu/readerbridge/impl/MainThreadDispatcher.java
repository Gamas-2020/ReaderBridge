package com.feiyu.readerbridge.impl;

import com.feiyu.readerbridge.ReaderBridgeMod;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * 把任务派发到 Minecraft 主线程。
 *
 * <p>这是 {@code ReaderStateListener} 回调「必须在主线程」这一硬性要求的实现核心：</p>
 * <ul>
 *   <li><b>客户端</b>：通过 {@link DistExecutor} 隔离地使用 {@code Minecraft.getInstance()}，
 *       当前已在主线程就直接执行，否则 {@code execute(...)} 排队。</li>
 *   <li><b>专用服务端</b>：使用 {@link ServerLifecycleHooks#getCurrentServer()} 拿到的
 *       {@link MinecraftServer}，同样「同线程直接跑 / 否则 execute」。</li>
 *   <li><b>两者都不可用</b>（例如 {@code FMLCommonSetupEvent} 阶段、或客户端主循环尚未建立）：
 *       任务进入 {@link #PENDING} 队列，由主线程 tick / 生命周期事件调用
 *       {@link #flushPending()} 时按入队顺序补发。</li>
 * </ul>
 *
 * <p>客户端类（{@code net.minecraft.client.Minecraft}）只在
 * {@link ClientThreadAccess} 中被引用，而 {@code ClientThreadAccess} 只会在
 * {@link Dist#CLIENT} 分支里通过 lambda 被加载，因此专用服务端不会因链接客户端类而崩溃。</p>
 */
public final class MainThreadDispatcher {

    /** 暂存队列容量上限：主线程长时间不可用时（例如游戏卡在加载）防止内存无限增长。 */
    private static final int MAX_PENDING = 256;

    /** 主线程尚不可用时暂存的任务。 */
    private static final Queue<Runnable> PENDING = new ConcurrentLinkedQueue<>();

    /** 队列长度计数，配合 {@link #MAX_PENDING} 做有界保护。 */
    private static final AtomicInteger PENDING_COUNT = new AtomicInteger();

    /** 溢出只告警一次，避免刷屏。 */
    private static final AtomicBoolean OVERFLOW_WARNED = new AtomicBoolean(false);

    private MainThreadDispatcher() {
    }

    /**
     * 派发一个任务到主线程。
     *
     * <p>任何线程都可以调用。任务内部的异常会被捕获并记录，不会传播到 WebSocket 线程。</p>
     *
     * @param task 任务
     */
    public static void dispatch(Runnable task) {
        if (task == null) {
            return;
        }
        Runnable guarded = guard(task);
        if (tryRunOnMainThread(guarded)) {
            return;
        }
        PENDING.add(guarded);
        if (PENDING_COUNT.incrementAndGet() > MAX_PENDING) {
            // 队列有界：丢弃最旧的一条，保证内存不会因为主线程长期不可用而无限增长。
            if (PENDING.poll() != null) {
                PENDING_COUNT.decrementAndGet();
            }
            if (OVERFLOW_WARNED.compareAndSet(false, true)) {
                ReaderBridgeMod.LOGGER.warn(
                        "[readerbridge] 主线程长时间不可用，待派发队列超过 {} 条，已开始丢弃最旧任务（本提示只输出一次）",
                        MAX_PENDING);
            }
        }
    }

    /**
     * 在主线程上 flush 暂存任务。必须只在主线程调用。
     */
    public static void flushPending() {
        Runnable task;
        while ((task = PENDING.poll()) != null) {
            PENDING_COUNT.decrementAndGet();
            task.run();
        }
    }

    /**
     * 当前线程是否为主线程。
     *
     * @return 是主线程返回 {@code true}
     */
    public static boolean isOnMainThread() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            // 这里用 unsafe 变体而不是 safe 变体，原因见 tryRunOnMainThread 的注释。
            Boolean result = DistExecutor.unsafeCallWhenOn(Dist.CLIENT, () -> ClientThreadAccess::isOnMainThread);
            if (result != null) {
                return result;
            }
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null && server.isSameThread();
    }

    /** 当前暂存任务数量（诊断用）。 */
    public static int pendingCount() {
        return PENDING_COUNT.get();
    }

    /**
     * 尝试立即把任务交给主线程。
     *
     * @param task 已包裹异常保护的任务
     * @return 已交给主线程（直接执行或已排队）返回 {@code true}；主线程不可用返回 {@code false}
     */
    private static boolean tryRunOnMainThread(Runnable task) {
        // 注意：这里必须用 unsafe 变体，不能用 safe 变体。
        // Forge 的 DistExecutor.validateSafeReferent 会用反射取 lambda 的 SerializedLambda，
        // 并比较 getCapturingClass() 与 getImplClass()；只要两者相等就判定为"unsafe referent"
        // 并直接抛 RuntimeException（"Unsafe Referent usage found in safe referent method"）。
        // 本方法内的嵌套 lambda 的捕获类就是它自己所在的类，因此必然被判 unsafe。
        // 安全性由前面的 FMLEnvironment.dist == Dist.CLIENT 判断保证：
        // 专用服务端不会执行这个 lambda，也就不会解析/加载 net.minecraft.client.* 相关类。
        if (FMLEnvironment.dist == Dist.CLIENT) {
            Boolean handled = DistExecutor.unsafeCallWhenOn(Dist.CLIENT, () -> () -> ClientThreadAccess.tryRun(task));
            if (Boolean.TRUE.equals(handled)) {
                return true;
            }
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            if (server.isSameThread()) {
                task.run();
            } else {
                server.execute(task);
            }
            return true;
        }
        return false;
    }

    /** 包一层 try/catch：单个任务失败不能影响其它任务或调用方线程。 */
    private static Runnable guard(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable t) {
                ReaderBridgeMod.LOGGER.error("[readerbridge] 主线程任务执行异常（已隔离）", t);
            }
        };
    }
}
