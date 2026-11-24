package com.feiyu.readerbridge.client.gui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 面板的落盘 / 线程工具。
 *
 * <p>硬约束来自任务书：<b>渲染线程不得阻塞</b>。所以本类只有两件事——
 * <ol>
 *   <li>一个守护单线程池 {@code rb-panel-io}，所有 {@code config/readerbridge/*.json} 的
 *       读写、以及搜索这类会走网络的调用，全部丢进去；</li>
 *   <li>{@link #onMain(Runnable)} 把结果经 {@code Minecraft.execute} 送回客户端主线程，
 *       界面状态只在主线程被改。</li>
 * </ol>
 *
 * <p>落盘目录与 {@code LibraryStore} 保持一致：相对游戏工作目录的
 * {@code config/readerbridge}（{@code ClientEvents} 里 {@code prewarm} 传的就是这个路径）。
 * 写文件走「先写 .tmp 再 move」，游戏崩溃不会留下半截 JSON。
 */
final class PanelIo {

    /** 与 LibraryStore 一致的 Gson 配置：缩进 2 空格，中文不转义（Gson 默认保留非 ASCII）。 */
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static ExecutorService io;

    private PanelIo() {
    }

    /** 面板专用 IO 线程池（守护线程，进程退出不会被它拖住）。 */
    private static synchronized ExecutorService pool() {
        if (io == null) {
            io = Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "rb-panel-io");
                t.setDaemon(true);
                return t;
            });
        }
        return io;
    }

    /** 丢一个后台任务；池满/已关闭时静默丢弃（面板不该因为 IO 异常而崩）。 */
    static void background(Runnable task) {
        if (task == null) {
            return;
        }
        try {
            pool().execute(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    System.err.println("[readerbridge] 面板后台任务失败：" + t);
                }
            });
        } catch (Throwable t) {
            System.err.println("[readerbridge] 面板后台任务提交失败：" + t);
        }
    }

    /** 回主线程执行；取不到客户端实例时（测试/极早期）直接同步跑。 */
    static void onMain(Runnable task) {
        if (task == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            task.run();
            return;
        }
        try {
            mc.execute(task);
        } catch (Throwable t) {
            task.run();
        }
    }

    /** {@code config/readerbridge}（不存在时由 {@link #writeText} 负责创建）。 */
    static Path configDir() {
        return new File("config", "readerbridge").toPath();
    }

    /** 读取 UTF-8 文本；文件不存在或读失败返回 {@code null}（调用方按「无存档」处理）。 */
    static String readText(Path file) {
        if (file == null) {
            return null;
        }
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            System.err.println("[readerbridge] 面板存档读取失败：" + file + " -> " + t);
            return null;
        }
    }

    /** 同步写盘（只在后台线程调用）。 */
    static void writeText(Path file, String text) {
        if (file == null || text == null) {
            return;
        }
        try {
            Path dir = file.getParent();
            if (dir != null && !Files.isDirectory(dir)) {
                Files.createDirectories(dir);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (Throwable atomicUnsupported) {
                // 某些文件系统不支持原子移动：退回普通替换
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable t) {
            System.err.println("[readerbridge] 面板存档写入失败：" + file + " -> " + t);
        }
    }

    /** 异步写盘。 */
    static void writeAsync(Path file, String text) {
        background(() -> writeText(file, text));
    }
}
