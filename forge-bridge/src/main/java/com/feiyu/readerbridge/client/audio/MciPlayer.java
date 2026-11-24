package com.feiyu.readerbridge.client.audio;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模组内的 mp3 播放通道：常驻 PowerShell 子进程 + winmm MCI。
 *
 * <p>为什么不是纯 Java：JDK 的 {@code javax.sound} 在 Windows 上只认 WAV/AIFF/AU，
 * 没有 MP3 解码器；Edge TTS 只能返回 mp3（PCM 变体服务端一律回 0 字节）。
 * 而 MCI 由 Windows 自己解码 mp3，是唯一零安装、零第三方库的路径。
 * MCI 的 alias 生命周期绑定在进程上，所以播放器进程必须常驻 —— 这也正是
 * 参考项目 MuoniumPlayer 拉外部解码器进程的同一思路，只是我们把解码器换成系统自带的。
 *
 * <p>线程模型：{@link #play(String)} / {@link #stop()} 会写管道，调用方
 * <b>不要放在主线程</b>（写管道通常很快，但进程冷启动要 1~2 秒）。{@link #prewarm()}
 * 可以在后台线程提前把进程和 Add-Type 编译做掉，避免第一次朗读卡住。
 */
public final class MciPlayer {

    /** 脚本文件名。放在游戏目录的 config/readerbridge 下，便于排查。 */
    private static final String SCRIPT_NAME = "mci_bridge.ps1";

    /**
     * 常驻脚本：从 stdin 逐行读命令。
     * <ul>
     *   <li>{@code play <abs path>} —— 关掉上一段，open + play</li>
     *   <li>{@code stop} —— 停止但保留 alias</li>
     *   <li>{@code quit} —— 退出</li>
     * </ul>
     * 刻意全为 ASCII：PowerShell 5.1 按 ANSI 读 .ps1，中文会有编码风险。
     */
    private static final String SCRIPT = String.join("\r\n",
            "[Console]::InputEncoding = [System.Text.Encoding]::UTF8",
            "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8",
            "Add-Type -Namespace Rb -Name Mci -MemberDefinition @'",
            "[DllImport(\"winmm.dll\", CharSet = CharSet.Unicode)]",
            "public static extern int mciSendString(string cmd, System.Text.StringBuilder ret, int retLen, System.IntPtr hwnd);",
            "'@",
            "$alias = 'rbmci'",
            "while ($true) {",
            "  $line = [Console]::In.ReadLine()",
            "  if ($null -eq $line) { break }",
            "  $line = $line.Trim()",
            "  if ($line.Length -eq 0) { continue }",
            "  if ($line -eq 'quit') { break }",
            "  if ($line -eq 'prewarm') { [Console]::Out.WriteLine('OK'); [Console]::Out.Flush(); continue }",
            "  if ($line -eq 'stop') {",
            "    [void][Rb.Mci]::mciSendString(\"stop $alias\", $null, 0, [IntPtr]::Zero)",
            "    [Console]::Out.WriteLine('OK'); [Console]::Out.Flush(); continue",
            "  }",
            "  if ($line -eq 'pause') {",
            "    [void][Rb.Mci]::mciSendString(\"pause $alias\", $null, 0, [IntPtr]::Zero)",
            "    [Console]::Out.WriteLine('OK'); [Console]::Out.Flush(); continue",
            "  }",
            "  if ($line -eq 'resume') {",
            "    [void][Rb.Mci]::mciSendString(\"resume $alias\", $null, 0, [IntPtr]::Zero)",
            "    [Console]::Out.WriteLine('OK'); [Console]::Out.Flush(); continue",
            "  }",
            "  if ($line.StartsWith('play ')) {",
            "    $p = $line.Substring(5)",
            "    [void][Rb.Mci]::mciSendString(\"close $alias\", $null, 0, [IntPtr]::Zero)",
            "    $r = [Rb.Mci]::mciSendString(\"open `\"$p`\" type mpegvideo alias $alias\", $null, 0, [IntPtr]::Zero)",
            "    if ($r -ne 0) {",
            "      $r2 = [Rb.Mci]::mciSendString(\"open `\"$p`\" alias $alias\", $null, 0, [IntPtr]::Zero)",
            "      if ($r2 -ne 0) { [Console]::Out.WriteLine(\"ERR open $r/$r2\"); [Console]::Out.Flush(); continue }",
            "    }",
            "    $rp = [Rb.Mci]::mciSendString(\"play $alias\", $null, 0, [IntPtr]::Zero)",
            "    if ($rp -ne 0) { [Console]::Out.WriteLine(\"ERR play $rp\") } else { [Console]::Out.WriteLine('OK') }",
            "    [Console]::Out.Flush()",
            "  }",
            "}",
            "");

    private final Object lock = new Object();
    private final AtomicBoolean broken = new AtomicBoolean(false);

    private Process process;
    private BufferedWriter stdin;
    private Path scriptPath;
    private String lastError = "";

    public String lastError() {
        return lastError;
    }

    public boolean isBroken() {
        return broken.get();
    }

    /**
     * 预热：把脚本落盘、拉起进程、等 Add-Type 编译完成。
     * 失败不抛异常，只置 {@link #isBroken()}，上层据此退回静音而不是崩游戏。
     */
    public boolean prewarm(Path configDir) {
        synchronized (lock) {
            if (process != null && process.isAlive()) {
                return true;
            }
            if (broken.get()) {
                return false;
            }
            try {
                if (!Files.isDirectory(configDir)) {
                    Files.createDirectories(configDir);
                }
                scriptPath = configDir.resolve(SCRIPT_NAME);
                // 内容变了才重写，避免每次启动都动文件
                byte[] bytes = SCRIPT.getBytes(StandardCharsets.US_ASCII);
                boolean needWrite = true;
                if (Files.isRegularFile(scriptPath)) {
                    try {
                        needWrite = !java.util.Arrays.equals(Files.readAllBytes(scriptPath), bytes);
                    } catch (IOException ignored) {
                        needWrite = true;
                    }
                }
                if (needWrite) {
                    Files.write(scriptPath, bytes);
                }

                String ps = locatePowershell();
                if (ps == null) {
                    return fail("找不到 powershell.exe");
                }
                ProcessBuilder pb = new ProcessBuilder(
                        ps, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                        "-File", scriptPath.toAbsolutePath().toString());
                pb.redirectErrorStream(false);
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                process = pb.start();
                stdin = new BufferedWriter(new OutputStreamWriter(
                        process.getOutputStream(), StandardCharsets.UTF_8));

                // 用 prewarm 命令确认脚本已经在跑（此时 Add-Type 已编译完成）
                stdin.write("prewarm\n");
                stdin.flush();
                return true;
            } catch (IOException e) {
                return fail("启动播放通道失败：" + e.getMessage());
            }
        }
    }

    /** 播放一个本地 mp3 文件（绝对路径）。返回是否成功把命令送出去。 */
    public boolean play(String absoluteMp3Path) {
        if (absoluteMp3Path == null || absoluteMp3Path.isEmpty()) {
            lastError = "空音频路径";
            return false;
        }
        synchronized (lock) {
            if (process == null || !process.isAlive()) {
                if (!prewarm(defaultConfigDir())) {
                    return false;
                }
            }
            try {
                stdin.write("play " + absoluteMp3Path + "\n");
                stdin.flush();
                return true;
            } catch (IOException e) {
                return fail("写播放命令失败：" + e.getMessage());
            }
        }
    }

    public void stop() {
        send("stop");
    }

    /** 暂停（MCI 原生 pause/resume，恢复后不会从头播）。 */
    public void pause() {
        send("pause");
    }

    public void resume() {
        send("resume");
    }

    private void send(String command) {
        synchronized (lock) {
            if (stdin == null || process == null || !process.isAlive()) {
                return;
            }
            try {
                stdin.write(command);
                stdin.write('\n');
                stdin.flush();
            } catch (IOException ignored) {
                // 控制命令失败不影响播放状态机：下次 play 会先 close 掉 alias
            }
        }
    }

    /** 关闭通道（客户端退出时调用）。 */
    public void shutdown() {
        synchronized (lock) {
            try {
                if (stdin != null) {
                    stdin.write("quit\n");
                    stdin.flush();
                }
            } catch (IOException ignored) {
                // ignore
            }
            try {
                if (stdin != null) {
                    stdin.close();
                }
            } catch (IOException ignored) {
                // ignore
            }
            if (process != null) {
                try {
                    if (!process.waitFor(800, TimeUnit.MILLISECONDS)) {
                        process.destroy();
                        if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                            process.destroyForcibly();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
            process = null;
            stdin = null;
        }
    }

    // ---------------------------------------------------------------- 内部

    private boolean fail(String msg) {
        lastError = msg;
        broken.set(true);
        return false;
    }

    private static Path defaultConfigDir() {
        // MC 工作目录就是 gameDir，Forge 的 config 就在下面
        return new File("config", "readerbridge").toPath();
    }

    /**
     * 优先 Windows PowerShell 5.1（.NET Framework，Add-Type 稳定），
     * 找不到再退回 PATH 上的 pwsh。pwsh 7 也可跑同样脚本，但启动更慢。
     */
    private static String locatePowershell() {
        String sysRoot = System.getenv("SystemRoot");
        if (sysRoot == null || sysRoot.isEmpty()) {
            sysRoot = "C:\\Windows";
        }
        String[] candidates = {
                sysRoot + "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
                "powershell.exe",
        };
        for (String c : candidates) {
            if (c.startsWith("powershell")) {
                return c;
            }
            if (new File(c).isFile()) {
                return c;
            }
        }
        return null;
    }
}
