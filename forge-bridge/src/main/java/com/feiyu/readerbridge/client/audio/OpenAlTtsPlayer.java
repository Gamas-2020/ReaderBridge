package com.feiyu.readerbridge.client.audio;

import com.feiyu.readerbridge.ReaderBridgeMod;

import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.Vec3;

import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;

/**
 * 朗读音频通道：JLayer 解 mp3 → OpenAL 3D 声源播放。
 *
 * <p>相对旧的 {@link MciPlayer}（常驻 PowerShell + winmm MCI）多出三件事：</p>
 * <ol>
 *   <li><b>水下闷声（PCM 软滤波）</b>：玩家眼睛在水下时，把交给 {@code alBufferData} 的 PCM 换成
 *       {@link WaterLowpass} 处理过的版本（420 Hz 低通 + ×0.82），出水换回原始版本。
 *       不用 OpenAL EFX——{@code EXTEfx.AL_FILTER_LOWPASS} 是否生效取决于驱动，实测存在"挂了但听不出来"
 *       的情况；软件滤波只依赖 JDK，必然生效。原始 PCM 常驻，低通版本按块缓存，切换不重算。</li>
 *   <li><b>世界定位</b>：声源放在当前句子的世界锚点（{@link #setWorldPosition}），
 *       listener 每帧跟随 {@code gameRenderer.getMainCamera()}，于是玩家走远会变小声、
 *       转头会左右声道变化。{@link #setUseWorldPosition} 为 {@code false} 时回到贴身播放
 *       （{@code AL_SOURCE_RELATIVE=1} + 原点），不做距离衰减。</li>
 *   <li><b>语速变速</b>：{@link #setPlaybackRate} 用 {@code AL_PITCH} 对当前 source 立即生效（不变调以外的行为），
 *       不重新合成。该倍率<b>不跨 {@link #play}</b>：{@code play} 入口一律复位到 1.0，
 *       因为新合成的音频本身已经带上了目标语速，再叠加 pitch 会双重加速。</li>
 * </ol>
 *
 * <p><b>线程模型</b>：解码在工作线程（单线程池，daemon，低优先级），<b>所有 OpenAL 调用都在
 * 调 {@link #tick()} 的线程（= 渲染主线程）</b>。{@link #play} 只在命中解码缓存时才在调用线程
 * 做一次 {@code alBufferData} 上传（纯 memcpy，毫秒级）；未命中时立刻返回，起播推迟到解码完成后的
 * 下一个 {@link #tick()}。水下 PCM 低通同样只在主线程的切换路径里跑一次，结果缓存复用。</p>
 *
 * <p><b>播放位置</b>：{@link #positionSeconds()} 读 {@code alGetSourcef(source, AL_SEC_OFFSET)}，
 * 这是 OpenAL 自己的播放头（不受游戏掉帧影响），正好供 ReaderEngine 推进句子。水陆切换换 buffer 时
 * 会把原位置搬过去，保证不跳变、不归零。</p>
 *
 * <p>本类任何失败都不会抛异常给游戏：{@link #lastError()} 里留最后一条错误，日志只刷一次。</p>
 */
public final class OpenAlTtsPlayer {

    // ------------------------------------------------------------------ 单例

    private static final OpenAlTtsPlayer INSTANCE = new OpenAlTtsPlayer();

    /** 全局唯一实例。 */
    public static OpenAlTtsPlayer get() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------ 常量

    /** 距离衰减系数；1.0 = 物理反比衰减（每翻倍距离 -6dB）。 */
    public static final float ROLLOFF_FACTOR = 1.0f;

    /** 参考距离：玩家与声源重合到 4 格之内不衰减。 */
    public static final float REFERENCE_DISTANCE = 4.0f;

    /** 最大可闻距离（格），超出后按 OpenAL 规则取衰减钳制的下限。 */
    public static final float MAX_DISTANCE = 64.0f;

    /** {@link #setPlaybackRate} 允许的最低倍率（再低会明显像慢放）。 */
    public static final float MIN_PLAYBACK_RATE = 0.5f;

    /** {@link #setPlaybackRate} 允许的最高倍率。 */
    public static final float MAX_PLAYBACK_RATE = 2.0f;

    /**
     * {@link #setGain} 允许的最高增益（4.0 = 400%）。
     *
     * <p><b>语义已改：这是 PCM 数字增益的上界，不再是 {@code AL_GAIN} 的上界。</b>
     * {@code AL_GAIN} 现在恒定 1.0（见 {@link #pushGain(int)}），真正的放大发生在
     * {@link PcmGain}：解码后、{@code alBufferData} 之前逐样本乘增益，{@code > 1.0} 走 tanh 软限幅。
     * 之所以从 {@code AL_GAIN} 搬走：Edge TTS 的 mp3 解码后接近满幅，{@code AL_GAIN = 4.0}
     * 只会把大部分样本撞上 mixer 的 ±1.0 硬 clamp（听感"没变响，只是变糊"）。</p>
     *
     * <p>上界必须与 {@code ReaderBridgeConfig.TTS_VOLUME} 的 {@code defineInRange(…, 4.0D)} 同步。</p>
     */
    public static final float MAX_GAIN = 4.0f;

    /** 解码结果缓存条数（一个块几 MB，留 2 条够覆盖"重播当前块/回上一块"）。 */
    private static final int MAX_CACHE_ENTRIES = 2;

    /** 单文件大小上限，防止误读巨物把堆吃满。 */
    private static final long MAX_FILE_BYTES = 32L * 1024 * 1024;

    // ------------------------------------------------------------------ 解码侧

    private final ExecutorService decoderExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread t = new Thread(runnable, "readerbridge-mp3-decode");
        t.setDaemon(true);
        t.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        return t;
    });

    private final Map<String, Mp3Decoder.Pcm> decodeCache =
            new LinkedHashMap<>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Mp3Decoder.Pcm> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            };

    /** 播放请求代数：新请求/停止都会 +1，用来丢弃过期的解码结果。 */
    private volatile long generation;

    /** 解码完成、等主线程上传的请求。 */
    private volatile Request pending;

    private volatile String lastError = "";

    // ------------------------------------------------------------------ OpenAL 资源（只碰主线程）

    private int sourceId = -1;
    private int bufferId = -1;

    private boolean alWarned;
    private boolean startedOnce;

    // ------------------------------------------------------------------ 播放状态

    private volatile boolean playing;
    private volatile boolean paused;
    private volatile double durationSeconds;

    /** 当前块的<b>原始</b> PCM（空气版本）；水下版本由 {@link #waterVersion} 派生并缓存。 */
    private volatile Mp3Decoder.Pcm current;

    /**
     * 水下低通结果所对应的原始 PCM；与 {@link #current} 引用比较即可判断缓存是否过期。
     */
    private Mp3Decoder.Pcm waterSource;

    /** 原始 PCM 的低通版本（换块后按需重建，不跨块复用）。<b>不含</b>数字增益。 */
    private Mp3Decoder.Pcm waterPcm;

    /**
     * 「低通 + 数字增益」成品缓存：{@link #processedSource} 引用同一块 air PCM、
     * 水下状态与 {@link #processedGain} 都一致时，{@link #processedPcm} 可直接复用。
     *
     * <p>缓存键必须是三元组，缺一不可：换块（source 引用变）、进出水面（underwater 变）、
     * 用户调音量（gain 变）都会让上一条成品作废。gain == 1.0 时
     * {@link #processedVersion} 直接返回基础版本（空气版或低通版），<b>不做整片拷贝</b>。</p>
     */
    private Mp3Decoder.Pcm processedSource;
    private boolean processedUnderwater;
    private float processedGain = Float.NaN;
    private Mp3Decoder.Pcm processedPcm;

    /**
     * 当前 source 的 buffer 里装的到底是哪一份 PCM（{@link #processedVersion} 的返回值）。
     *
     * <p>用来判断「要不要重传」：与本次要上传的版本引用相同 → 已经是这个版本，不重传
     * （重传会有一个可闻的断点）。比原来只比较 {@code playingUnderwater} 更准 ——
     * 音量变化同样会换版本。</p>
     */
    private Mp3Decoder.Pcm bufferedPcm;

    /** 期望播放速率（pitch）；{@link #play} 会复位成 1.0。 */
    private volatile float playbackRate = 1.0f;

    /** 已经下发到 source 的 pitch；{@code NaN} = 未知（新句柄 / 刚重建），下帧强制重发。 */
    private volatile float appliedPitch = Float.NaN;

    /**
     * 期望的 <b>PCM 数字增益</b>（朗读音量 0.0 ~ {@link #MAX_GAIN}，1.0 = 原样）。
     *
     * <p>与 {@link #playbackRate} 相反，它<b>跨 {@link #play} 保留</b>：音量是用户偏好，
     * 不该在换句/换块时被复位。和原版音量完全无关 —— 只改自己这条 source 的 buffer 内容，
     * 不读也不写 {@code options.getSoundSourceVolume(...)}。</p>
     *
     * <p>这个值<b>不</b>进 {@code AL_GAIN}；{@code AL_GAIN} 恒 1.0，
     * 放大由 {@link PcmGain} 在 PCM 层完成（见 {@link #pushGain(int)} 的注释）。</p>
     */
    private volatile float gain = 1.0f;

    /**
     * 已经写进 source 的 {@code AL_GAIN} 值（恒 {@link #UNITY_GAIN}，仅用于幂等下发）；
     * {@code NaN} = 未知（新句柄 / 刚重建），下帧强制重发一次。
     */
    private volatile float appliedGain = Float.NaN;

    /** {@code AL_GAIN} 的固定值：音量由 PCM 数字增益承担，OpenAL 侧不再放大。 */
    private static final float UNITY_GAIN = 1.0f;

    /** 非渲染线程调用 {@link #setGain} 时置位：buffer 里还是旧增益，由 {@link #tick()} 在主线程补一次重传。 */
    private volatile boolean gainReuploadPending;

    /** 最近一次判定的水下状态（主线程写，README 日志即基于它的变化）。 */
    private boolean underwaterState;

    private long playStartNanos;
    private long pausedAtNanos;
    private long pausedTotalNanos;

    // ------------------------------------------------------------------ 空间化

    private volatile boolean useWorldPosition = true;
    private volatile double worldX;
    private volatile double worldY;
    private volatile double worldZ;

    private final float[] orientation = new float[6];

    private OpenAlTtsPlayer() {
    }

    // ================================================================== 公开 API

    /**
     * 播放一个本地 mp3，从 {@code startSeconds} 秒处起播。
     *
     * <p>主线程调用。未命中解码缓存时本方法立即返回（解码在后台线程），实际起播发生在下一次
     * {@link #tick()}。</p>
     *
     * <p><b>入口会把 pitch 复位成 1.0</b>（当前 source 立即回到原速）：调用方若已用
     * {@link #setPlaybackRate} 变速、随后换成"已经按新语速合成好的音频"，这里不能再叠加一次
     * pitch，否则会双重加速。复位后 {@link #playbackRate()} 读到的就是 1.0。</p>
     *
     * @param mp3          mp3 文件
     * @param startSeconds 块内起播偏移（秒），&lt;= 0 表示从头
     */
    public void play(Path mp3, double startSeconds) {
        // 新块自带语速，先把旧块的临时变速清掉，避免 pitch 跨 play 延续
        applyPlaybackRate(1.0f);
        if (mp3 == null) {
            lastError = "音频路径为空";
            return;
        }
        final String key = cacheKey(mp3);
        final double startAt = Math.max(0d, startSeconds);
        final long gen = ++generation;
        lastError = "";

        Mp3Decoder.Pcm hit = cached(key);
        if (hit != null) {
            pending = Request.plain(hit, startAt, gen, 0L);
            return;
        }

        try {
            long size = Files.size(mp3);
            if (size <= 0L || size > MAX_FILE_BYTES) {
                lastError = "mp3 大小异常：" + size + " 字节";
                return;
            }
        } catch (Throwable t) {
            lastError = "读取 mp3 失败：" + t;
            return;
        }

        decoderExecutor.execute(() -> {
            long t0 = System.nanoTime();
            try {
                Mp3Decoder.Pcm pcm = Mp3Decoder.decode(mp3);
                long costMs = (System.nanoTime() - t0) / 1_000_000L;
                if (gen != generation) {
                    return; // 已被新的播放请求或 stop() 取代
                }
                store(key, pcm);
                pending = Request.plain(pcm, startAt, gen, costMs);
            } catch (Throwable t) {
                if (gen == generation) {
                    lastError = "mp3 解码失败：" + t;
                    ReaderBridgeMod.LOGGER.warn("[readerbridge] mp3 解码失败：{}", t.toString());
                }
            }
        });
    }

    /** 停止播放（保留 OpenAL 句柄，下次 play 复用）。主线程调用。 */
    public void stop() {
        generation++;
        pending = null;
        if (sourceId >= 0 && isSourceValid()) {
            try {
                AL10.alSourceStop(sourceId);
            } catch (Throwable ignored) {
                // 上下文已销毁时忽略
            }
        }
        playing = false;
        paused = false;
        pausedAtNanos = 0L;
        pausedTotalNanos = 0L;
        playStartNanos = 0L;
    }

    /** 暂停（OpenAL 原生 pause，resume 后从原位置继续）。主线程调用。 */
    public void pause() {
        if (!playing || paused) {
            return;
        }
        if (sourceId < 0 || !isSourceValid()) {
            return;
        }
        try {
            AL10.alSourcePause(sourceId);
            paused = true;
            pausedAtNanos = System.nanoTime();
        } catch (Throwable t) {
            lastError = "暂停失败：" + t;
        }
    }

    /** 从暂停处继续。主线程调用。 */
    public void resume() {
        if (!playing || !paused) {
            return;
        }
        if (sourceId < 0 || !isSourceValid()) {
            return;
        }
        try {
            AL10.alSourcePlay(sourceId);
            if (pausedAtNanos != 0L) {
                pausedTotalNanos += System.nanoTime() - pausedAtNanos;
                pausedAtNanos = 0L;
            }
            paused = false;
        } catch (Throwable t) {
            lastError = "继续播放失败：" + t;
        }
    }

    /**
     * 每帧调用（渲染线程）。
     *
     * <p>做五件事：同步 listener 到主相机 → 更新水下状态（在消费解码结果之前，这样"在水下起播"
     * 第一声就是低通版本）→ 消费解码结果并起播 → 补做跨线程的音量重传 → 刷新声源位置/语速/水下 buffer。
     * 水陆状态变化且正在播放时，会在这一步换 buffer 并保住播放位置。</p>
     */
    public void tick() {
        Minecraft mc;
        try {
            mc = Minecraft.getInstance();
        } catch (Throwable t) {
            return;
        }
        if (mc == null) {
            return;
        }
        try {
            if (!ensureOpenAl()) {
                return;
            }
            updateListener(mc);
            final boolean underwaterChanged = updateUnderwaterState(mc);
            consumePending();
            if (underwaterChanged) {
                // 故意放在 source 判断之外：还没起播 / 句柄未建时也要落一条 INFO，
                // 用户在任何时刻进出水面都能在日志里确认状态确实切了。
                applyUnderwaterToBuffer();
            }
            // 非渲染线程调过 setGain：buffer 里还是旧增益，这一步在主线程补做重传。
            consumeGainPending();
            if (sourceId >= 0 && isSourceValid()) {
                applySpatial();
                syncPitch();
                syncGain();
                updateFinished();
            }
        } catch (Throwable t) {
            lastError = "tick 失败：" + t;
        }
    }

    /** 设置声源世界坐标（格）。只写字段，真正下发在 {@link #tick()}。 */
    public void setWorldPosition(double x, double y, double z) {
        this.worldX = x;
        this.worldY = y;
        this.worldZ = z;
    }

    /**
     * 是否用世界坐标做 3D 声源。
     *
     * @param use {@code true} = 世界锚点 + 距离衰减 + 左右声道；{@code false} = 贴身播放（不衰减）
     */
    public void setUseWorldPosition(boolean use) {
        this.useWorldPosition = use;
    }

    /**
     * 设置播放速率（只改 pitch，不重新合成）。
     *
     * <p>任何线程可调用（字段 volatile）；若当前没有 source，只记下倍率，下一次下发时套用。
     * 倍率会被夹到 {@code [0.5, 2.0]}。取值范围外的入参、{@code NaN} 都不会报错。</p>
     *
     * <p><b>不跨 {@link #play}</b>：{@code play} 入口会把它复位为 1.0。</p>
     *
     * @param rate 目标倍率，1.0 = 原速
     */
    public void setPlaybackRate(float rate) {
        float clamped = Float.isNaN(rate) ? 1.0f : rate;
        clamped = Math.max(MIN_PLAYBACK_RATE, Math.min(MAX_PLAYBACK_RATE, clamped));
        applyPlaybackRate(clamped);
    }

    /** 当前 pitch 倍率；{@link #play} 之后为 1.0，{@link #stop} 后保留最后一次的值。 */
    public float playbackRate() {
        return playbackRate;
    }

    /**
     * 设置朗读音量（真正的音量，作用在 PCM 上，不是 {@code AL_GAIN}）。
     *
     * <p>独立音量：<b>不碰</b>原版的主音量/音乐/语音，也不受它们影响 ——
     * 这是「模组自己播放的 TTS」专属的音量。</p>
     *
     * <p>实现分两层：</p>
     * <ol>
     *   <li>值本身存进 {@link #gain}（volatile，任何线程可写），跨换块/换句保留；</li>
     *   <li><b>立刻生效</b>：在渲染线程上直接重传当前 buffer（{@link #reuploadCurrent(String)}，
     *       内部记住 {@code positionSeconds()} → 重新上传 → seek 回原位置），所以点一下
     *       {@code +} 当前这一句就会变响，不用等下一句。若调用方不在渲染线程
     *       （配置重载等），只置 {@link #gainReuploadPending}，由 {@link #tick()} 在主线程补做
     *       —— OpenAL 的调用不能跨线程。</li>
     * </ol>
     *
     * <p>{@code AL_GAIN} 恒为 1.0：放大由 {@link PcmGain} 在 PCM 层完成，避免双重放大。</p>
     *
     * @param g 目标增益，夹到 {@code [0, MAX_GAIN]}（4.0 = 400%）；{@code NaN} 视为 1.0
     */
    public void setGain(float g) {
        final float clamped = clampGain(g);
        gain = clamped;
        if (isRenderThread()) {
            applyGain(clamped);
        } else {
            gainReuploadPending = true;
        }
    }

    /** 当前朗读增益（0.0 ~ 4.0）。 */
    public float gain() {
        return gain;
    }

    /** 当前是否处于"正在出声"状态（暂停也算 true，停止/播完为 false）。 */
    public boolean isPlaying() {
        if (!playing) {
            return false;
        }
        if (sourceId < 0 || !isSourceValid()) {
            return playing;
        }
        final int state = sourceState();
        if (state < 0) {
            return playing;
        }
        return state == AL10.AL_PLAYING || state == AL10.AL_PAUSED;
    }

    /**
     * 当前播放位置（秒，块内绝对偏移，含 {@link #play} 传入的起点）。
     *
     * <p>优先读 OpenAL 播放头 {@code AL_SEC_OFFSET}；读不到时退回 Java 计时（仅作兜底）。
     * 水陆切换换 buffer 后这里立刻反映切换前的位置，不会跳变或归零。</p>
     */
    public double positionSeconds() {
        if (!playing) {
            return 0d;
        }
        if (sourceId >= 0 && isSourceValid()) {
            try {
                float sec = AL10.alGetSourcef(sourceId, AL11.AL_SEC_OFFSET);
                int err = AL10.alGetError();
                if (err == AL10.AL_NO_ERROR && !Float.isNaN(sec) && sec >= 0f) {
                    return sec;
                }
            } catch (Throwable ignored) {
                // 退回 Java 计时
            }
        }
        return fallbackPositionSeconds();
    }

    /** 当前音频总时长（秒）；还没有解码结果时返回 0。 */
    public double durationSeconds() {
        return durationSeconds;
    }

    /** 最后一条错误（无错误为空串）。 */
    public String lastError() {
        return lastError;
    }

    /** 释放 OpenAL 资源（客户端退出 / 重载声音时调用）。主线程调用。 */
    public void release() {
        stop();
        try {
            if (sourceId >= 0 && isSourceValid()) {
                AL10.alSourceStop(sourceId);
                AL10.alSourcei(sourceId, AL10.AL_BUFFER, 0);
            }
        } catch (Throwable ignored) {
            // 上下文可能已销毁
        }
        try {
            if (sourceId >= 0) {
                AL10.alDeleteSources(sourceId);
            }
        } catch (Throwable ignored) {
            // ignore
        }
        sourceId = -1;
        try {
            if (bufferId >= 0) {
                AL10.alDeleteBuffers(bufferId);
            }
        } catch (Throwable ignored) {
            // ignore
        }
        bufferId = -1;
        appliedPitch = Float.NaN;
        appliedGain = Float.NaN;
        waterSource = null;
        waterPcm = null;
        processedSource = null;
        processedPcm = null;
        processedGain = Float.NaN;
        bufferedPcm = null;
        current = null;
        durationSeconds = 0d;
    }

    /** 释放 OpenAL 资源并停掉解码线程（只在客户端关闭时调用，之后实例不可再用）。 */
    public void shutdown() {
        release();
        decoderExecutor.shutdownNow();
        synchronized (decodeCache) {
            decodeCache.clear();
        }
    }

    // ================================================================== 内部：OpenAL 可用性

    /** 每帧一次的能力检查；上下文未建好时返回 false，句柄失效时自动重建。 */
    private boolean ensureOpenAl() {
        org.lwjgl.openal.ALCapabilities caps;
        try {
            caps = AL.getCapabilities();
        } catch (Throwable t) {
            caps = null;
        }
        if (caps == null) {
            if (!alWarned) {
                alWarned = true;
                ReaderBridgeMod.LOGGER.warn("[readerbridge] OpenAL 上下文尚未创建，朗读音频待命");
            }
            lastError = "OpenAL 上下文尚未创建";
            return false;
        }
        if (sourceId >= 0 && !AL10.alIsSource(sourceId)) {
            // 声音设备切换 / 资源重载会让上下文重建，旧句柄全部失效
            ReaderBridgeMod.LOGGER.debug("[readerbridge] OpenAL source 已失效，重建句柄");
            sourceId = -1;
            bufferId = -1;
            appliedPitch = Float.NaN;
            appliedGain = Float.NaN;
            // buffer 随旧句柄一起没了：清掉"已装版本"的记录，下次重传会重新派生
            bufferedPcm = null;
        }
        return true;
    }

    private boolean isSourceValid() {
        try {
            return sourceId >= 0 && AL10.alIsSource(sourceId);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 读 {@code AL_SOURCE_STATE}；句柄不可用或读失败返回 -1。 */
    private int sourceState() {
        try {
            return AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE);
        } catch (Throwable t) {
            return -1;
        }
    }

    // ================================================================== 内部：listener / 声源

    /** 把 listener 放到主相机位置，朝向取相机 forward/up。 */
    private void updateListener(Minecraft mc) {
        Camera camera;
        try {
            camera = mc.gameRenderer.getMainCamera();
        } catch (Throwable t) {
            return;
        }
        if (camera == null) {
            return;
        }
        Vec3 pos = camera.getPosition();
        Vector3f look = camera.getLookVector();
        Vector3f up = camera.getUpVector();
        if (pos == null || look == null || up == null) {
            return;
        }
        AL10.alListener3f(AL10.AL_POSITION, (float) pos.x, (float) pos.y, (float) pos.z);
        orientation[0] = look.x;
        orientation[1] = look.y;
        orientation[2] = look.z;
        orientation[3] = up.x;
        orientation[4] = up.y;
        orientation[5] = up.z;
        AL10.alListenerfv(AL10.AL_ORIENTATION, orientation);
    }

    /** 消费解码结果：上传 PCM 并起播。 */
    private void consumePending() {
        Request req = pending;
        if (req == null) {
            return;
        }
        pending = null;
        if (req.generation != generation) {
            return;
        }
        uploadAndStart(req);
    }

    /**
     * 上传 PCM 并起播 / 换 buffer 后重播。
     *
     * <p>调用方负责在需要时把 {@code req.startSeconds} 设成"要续播的位置"；本方法用
     * {@code AL_BYTE_OFFSET} 定位，并把 {@link #playStartNanos} 按起点回填，使兜底计时与
     * OpenAL 播放头对齐（水下换 buffer 后位置不跳变）。</p>
     */
    private void uploadAndStart(Request req) {
        try {
            if (sourceId < 0 || !isSourceValid()) {
                AL10.alGetError();
                int id = AL10.alGenSources();
                if (id <= 0 || AL10.alGetError() != AL10.AL_NO_ERROR) {
                    if (!alWarned) {
                        alWarned = true;
                        ReaderBridgeMod.LOGGER.warn("[readerbridge] alGenSources 失败，朗读音频不可用");
                    }
                    lastError = "alGenSources 失败";
                    return;
                }
                sourceId = id;
                AL10.alSourcei(sourceId, AL10.AL_LOOPING, 0);
                // 新句柄的 AL_PITCH 是默认 1.0，标记为未知让 syncPitch() 重新下发；
                // AL_GAIN 同理（默认 1.0），不标未知的话用户调小的音量会被句柄重建吞掉。
                appliedPitch = Float.NaN;
                appliedGain = Float.NaN;
            }
            if (bufferId < 0 || !AL10.alIsBuffer(bufferId)) {
                int id = AL10.alGenBuffers();
                if (id <= 0 || AL10.alGetError() != AL10.AL_NO_ERROR) {
                    bufferId = -1;
                    lastError = "alGenBuffers 失败";
                    return;
                }
                bufferId = id;
            }

            final Mp3Decoder.Pcm air = req.air;
            // 上传版本统一在这里派生：先低通（若在水下）→ 再乘 PCM 数字增益（若 != 1.0）。
            // 顺序理由见 processedVersion()：增益必须是最后一步，否则水下的低通衰减会把
            // 刚放大上去的幅度重新压回去，"水下时调音量听不出变化"。
            // 调用方不再自己算版本（原来的 Request.pcm 字段已删除），从根上消除"算错版本"。
            final Mp3Decoder.Pcm pcm = processedVersion(air, underwaterState, gain);
            final int channels = pcm.channels >= 2 ? 2 : 1;
            final int format = channels == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;

            // 先解绑再重填，避免改正在播放的 buffer
            AL10.alSourceStop(sourceId);
            AL10.alSourcei(sourceId, AL10.AL_BUFFER, 0);

            ShortBuffer data = BufferUtils.createShortBuffer(pcm.samples.length);
            data.put(pcm.samples).flip();
            AL10.alBufferData(bufferId, format, data, pcm.sampleRate);
            int err = AL10.alGetError();
            if (err != AL10.AL_NO_ERROR) {
                playing = false;
                lastError = "alBufferData 失败 err=" + err;
                return;
            }
            AL10.alSourcei(sourceId, AL10.AL_BUFFER, bufferId);

            current = air;
            // 记下 buffer 里到底装的是哪一份（低通/增益的组合），供"要不要重传"的判断与
            // 音量热更新共用；引用相同即内容相同（processedVersion 内部按引用缓存）。
            bufferedPcm = pcm;
            durationSeconds = air.durationSeconds();
            applySpatial();
            syncPitch();
            // 每次（重建句柄 / 换块 / 水下换 buffer / 音量变化后重传）填完 buffer、起播前
            // 都把 AL_GAIN 钉回 1.0（新 source 默认也是 1.0，这里只是防止被别处改动）。
            // 真正的音量已在 pcm 里逐样本乘过，见 PcmGain。
            syncGain();

            // 起播偏移：用字节偏移，OpenAL 会按帧对齐；越界时退回秒偏移
            int byteOffset = (int) Math.round(req.startSeconds * pcm.sampleRate) * channels * 2;
            if (byteOffset > 0 && byteOffset < pcm.samples.length * 2 - 2) {
                AL10.alSourcei(sourceId, AL11.AL_BYTE_OFFSET, byteOffset);
            } else if (req.startSeconds > 0.0d) {
                AL10.alSourcef(sourceId, AL11.AL_SEC_OFFSET, (float) req.startSeconds);
            }
            AL10.alGetError();

            AL10.alSourcePlay(sourceId);
            playing = true;
            paused = false;
            pausedAtNanos = 0L;
            pausedTotalNanos = 0L;
            // 起点已经过去的部分要回填，兜底计时才和 OpenAL 播放头一致（换 buffer 不归零）
            playStartNanos = System.nanoTime()
                    - (long) (Math.max(0d, req.startSeconds) * 1_000_000_000d);

            if (!startedOnce) {
                startedOnce = true;
                ReaderBridgeMod.LOGGER.info(
                        "[readerbridge] OpenAL 通道起播：{} 帧 / {} Hz / {} 声道 / {} s，解码 {} ms",
                        pcm.frames(), pcm.sampleRate, pcm.channels,
                        String.format("%.2f", durationSeconds), req.decodeMs);
            }
        } catch (Throwable t) {
            playing = false;
            lastError = "OpenAL 播放失败：" + t;
        }
    }

    /** 下发声源位置/衰减参数。注意：这里<b>不碰</b> {@code AL_PITCH}，语速由 {@link #syncPitch()} 独占。 */
    private void applySpatial() {
        if (sourceId < 0) {
            return;
        }
        if (useWorldPosition) {
            AL10.alSourcei(sourceId, AL10.AL_SOURCE_RELATIVE, 0);
            AL10.alSource3f(sourceId, AL10.AL_POSITION,
                    (float) worldX, (float) worldY, (float) worldZ);
            AL10.alSourcef(sourceId, AL10.AL_ROLLOFF_FACTOR, ROLLOFF_FACTOR);
            AL10.alSourcef(sourceId, AL10.AL_REFERENCE_DISTANCE, REFERENCE_DISTANCE);
            AL10.alSourcef(sourceId, AL10.AL_MAX_DISTANCE, MAX_DISTANCE);
        } else {
            // 贴身：与 listener 重合，不做距离衰减（仍保留声道/HRTF 处理）
            AL10.alSourcei(sourceId, AL10.AL_SOURCE_RELATIVE, 1);
            AL10.alSource3f(sourceId, AL10.AL_POSITION, 0f, 0f, 0f);
            AL10.alSourcef(sourceId, AL10.AL_ROLLOFF_FACTOR, 0f);
        }
    }

    // ================================================================== 内部：语速

    /**
     * 把 {@link #playbackRate} 真正下发到 source。
     *
     * <p>只在不一致时调 OpenAL，避免每帧无谓调用；{@link #appliedPitch} 用 {@code NaN}
     * 表示"句柄刚建/刚重建，值未知"，下一帧强制重发。</p>
     */
    private void syncPitch() {
        if (sourceId < 0) {
            return;
        }
        final float want = playbackRate;
        if (!Float.isNaN(appliedPitch) && Math.abs(appliedPitch - want) < 0.0005f) {
            return;
        }
        try {
            AL10.alSourcef(sourceId, AL10.AL_PITCH, want);
            appliedPitch = want;
        } catch (Throwable t) {
            appliedPitch = Float.NaN;
            lastError = "下发语速失败：" + t;
        }
    }

    /** 写字段 + 立即下发（source 不存在时只写字段，等下次 play/tick 套用）。 */
    private void applyPlaybackRate(float rate) {
        playbackRate = rate;
        if (sourceId >= 0 && isSourceValid()) {
            try {
                AL10.alSourcef(sourceId, AL10.AL_PITCH, rate);
                appliedPitch = rate;
            } catch (Throwable t) {
                appliedPitch = Float.NaN;
                lastError = "下发语速失败：" + t;
            }
        } else {
            appliedPitch = Float.NaN;
        }
    }

    // ================================================================== 内部：音量

    /**
     * 保证 source 的 {@code AL_GAIN} 恒为 {@link #UNITY_GAIN}（1.0）。
     *
     * <p><b>增益改由 PCM 数字增益承担，AL_GAIN 恒 1.0 以避免双重放大</b>：真正的放大发生在
     * {@link PcmGain}（解码后、{@code alBufferData} 之前逐样本乘），这里再把 {@code AL_GAIN}
     * 放开就变成同一份增益乘两遍，只剩硬削波。本方法只在句柄新建/重建
     * （{@link #appliedGain} 为 {@code NaN}）后补一次，与 {@link #syncPitch()} 同一套幂等模式。
     */
    private void syncGain() {
        if (sourceId < 0) {
            return;
        }
        if (!Float.isNaN(appliedGain) && Math.abs(appliedGain - UNITY_GAIN) < 0.0005f) {
            return;
        }
        final float readBack = pushGain(sourceId);
        if (Float.isNaN(readBack)) {
            appliedGain = Float.NaN;   // 下发失败：下一帧继续重试
            return;
        }
        appliedGain = UNITY_GAIN;      // 读回值只用于核对，不回写缓存（否则每帧重发）
        if (Math.abs(readBack - UNITY_GAIN) > 0.0005f) {
            // 正常情况这里恒等于 1.0；被别处（驱动/其它 mod）改掉才值得记一条
            ReaderBridgeMod.LOGGER.warn(
                    "[readerbridge] AL_GAIN 读回 {} != 1.000（source={}），已钉回 1.0；音量由 PCM 数字增益承担",
                    gainText(readBack), sourceId);
        }
    }

    /**
     * 音量变化的落地：先钉住 {@code AL_GAIN = 1.0}，再用新增益重传当前 buffer（就地续播）。
     *
     * <p>「设置后立刻生效、不重头」就靠 {@link #reuploadCurrent(String)} ——
     * 记下 {@code positionSeconds()} → 重新上传 → {@code AL_BYTE_OFFSET} 回到原位置，
     * 与水下切换换 buffer 完全同一段逻辑。当前没有播放中的 buffer 时只记值，
     * 下次 {@code play} 或换块时 {@code processedVersion} 会自动带上新增益。</p>
     */
    private void applyGain(float g) {
        // 先保证 OpenAL 侧不再放大，音量由 PCM 层独占
        syncGain();
        final boolean reuploaded = reuploadCurrent("音量变化 PCM增益=" + gainText(g));
        if (!reuploaded) {
            ReaderBridgeMod.LOGGER.info(
                    "[readerbridge] TTS 音量 PCM增益={}（{}%） 当前没有播放中的 buffer，下次起播时生效",
                    gainText(g), Math.round(g * 100f));
        }
    }

    /**
     * 把 {@code AL_GAIN} 写成固定的 {@link #UNITY_GAIN}（1.0），并读回核对。
     *
     * <p><b>增益改由 PCM 数字增益承担，AL_GAIN 恒 1.0 以避免双重放大</b>：解码后、
     * {@code alBufferData} 之前已经对每个样本乘过 gain（{@link PcmGain}，{@code > 1.0} 走 tanh 软限幅）。
     * 若这里再写 {@code AL_GAIN = 4.0}，等于同一份增益乘两遍（4×4 = 16 倍），
     * 除了一小段峰值全是方波 —— 听感只会"更糊"，不会更响。±1.0 的 mixer 硬 clamp
     * 就是上一轮把 {@code MAX_GAIN} 放到 4.0 之后依然听不出差别的原因。</p>
     *
     * <p>返回读回值；{@code NaN} 表示调用根本没成功 —— {@code alGetError()} 非 0
     * （source 无效、上下文未初始化、驱动拒绝等），错误码写进 {@link #lastError}。</p>
     *
     * @param src 目标 source 句柄
     */
    private float pushGain(int src) {
        try {
            AL10.alGetError();   // 清掉历史错误，否则会把上一处错误算到这次头上
            AL10.alSourcef(src, AL10.AL_GAIN, UNITY_GAIN);
            final int setErr = AL10.alGetError();
            if (setErr != AL10.AL_NO_ERROR) {
                lastError = "alSourcef(AL_GAIN) alGetError=" + setErr;
                return Float.NaN;
            }
            final float readBack = AL10.alGetSourcef(src, AL10.AL_GAIN);
            final int getErr = AL10.alGetError();
            if (getErr != AL10.AL_NO_ERROR) {
                lastError = "alGetSourcef(AL_GAIN) alGetError=" + getErr;
                return Float.NaN;
            }
            return readBack;
        } catch (Throwable t) {
            lastError = "下发音量异常：" + t;
            return Float.NaN;
        }
    }

    /** 增益夹紧到 {@code [0, MAX_GAIN]}；{@code NaN} 视为 1.0。PCM 层（{@link PcmGain}）还会再夹一次。 */
    private static float clampGain(float g) {
        if (Float.isNaN(g)) {
            return 1.0f;
        }
        return Math.max(0.0f, Math.min(MAX_GAIN, g));
    }

    /**
     * 当前线程是不是渲染主线程。
     *
     * <p>OpenAL 的调用（{@code alSourcef}/{@code alBufferData}/…）只能在渲染线程做，
     * 所以 {@link #setGain} 要按线程分派：渲染线程直接重传（GUI 点 {@code +} 就是这条），
     * 其它线程只置标记，由 {@link #tick()} 补做。</p>
     */
    private static boolean isRenderThread() {
        try {
            final Minecraft mc = Minecraft.getInstance();
            return mc != null && mc.isSameThread();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 统一的增益文本：三位小数，避免日志里出现 2.3999999 这种浮点噪音。 */
    private static String gainText(float v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    // ================================================================== 内部：水下 PCM

    /**
     * 更新水下状态；返回是否发生了变化。
     *
     * <p>玩家还没加载（进世界/换维度瞬间 {@code mc.player == null}）时保持现状，不判成出水，
     * 否则会在进世界第一帧把水下低通错误地关掉。</p>
     */
    private boolean updateUnderwaterState(Minecraft mc) {
        if (mc.player == null) {
            return false;
        }
        final boolean underwater = isUnderwater(mc);
        if (underwater == underwaterState) {
            return false;
        }
        underwaterState = underwater;
        return true;
    }

    /**
     * 水下判定。
     *
     * <p>主判据是 1.20.1 的 {@code Entity.isUnderWater()}（语义就是"眼睛在水下"）。
     * 兜底再判一次 {@code isEyeInFluid(FluidTags.WATER)}：实测/排查里发现两种情况它更稳——
     * 玩家的眼位正好压在水线临界高度上，或者自定义流体/传送落地同一帧里状态还没刷新，
     * 此时 {@code isUnderWater()} 仍为 false 但眼睛确实泡在水里。任一为真即按水下处理，
     * 宁可多闷一声也不要漏闷。</p>
     *
     * <p>未确认项：{@code isEyeInFluid(TagKey<Fluid>)} 与 {@code FluidTags.WATER} 是 1.20.1 的
     * 官方名，但本环境禁止跑 gradle，未能用编译器核实（详见报告"无法验证"一节）。</p>
     */
    private boolean isUnderwater(Minecraft mc) {
        try {
            if (mc.player.isUnderWater()) {
                return true;
            }
        } catch (Throwable ignored) {
            // 落到下面的兜底
        }
        try {
            return mc.player.isEyeInFluid(FluidTags.WATER);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 水下状态变了：记录日志，并在"正在播放"时换 buffer 续播。
     *
     * <p>不播放（没起播/已停止/播完）时只记录状态，下次 {@link #play} 起播会直接选对版本，
     * 不用在这里为空 buffer 折腾。</p>
     */
    private void applyUnderwaterToBuffer() {
        final Mp3Decoder.Pcm air = current;
        if (air == null) {
            logUnderwaterChange("当前无音频，仅记录状态");
            return;
        }
        if (!playing || sourceId < 0 || !isSourceValid()) {
            logUnderwaterChange("当前未播放，仅记录状态，下次起播直接套用");
            return;
        }
        final int state = sourceState();
        if (state != AL10.AL_PLAYING && state != AL10.AL_PAUSED) {
            logUnderwaterChange("当前未播放，仅记录状态，下次起播直接套用");
            return;
        }
        // 当前水下状态 + 当前增益下「本该装进 buffer」的那一份；缓存命中时零成本
        if (processedVersion(air, underwaterState, gain) == bufferedPcm) {
            // 例如"在水下起播"：uploadAndStart 已经选了低通版本，不必再重传一次
            logUnderwaterChange("buffer 已是该版本，未重传");
            return;
        }
        // 换 buffer + 保住播放位置：与音量热更新共用 reuploadCurrent
        reuploadCurrent(underwaterState ? "进入水下，换低通+增益版本" : "离开水下，换空气+增益版本");
    }

    /**
     * 取（必要时生成）「低通 + 数字增益」的成品 PCM —— 上传 OpenAL buffer 前的最后一道处理。
     *
     * <p><b>顺序固定：先低通，后增益。</b> {@link WaterLowpass} 本身带 ×0.82 的整体衰减；
     * 若先增益后低通，低通会把刚放大上去的幅度重新压回 0.82 倍，"水下时音量变化被吞掉"。
     * 增益放在最后一步，任何音量设定都完整作用在水下版本上，水上与水下听感一致。</p>
     *
     * <p>缓存键是三元组（air 引用, underwater, gain）：换块 / 进出水面 / 调音量都会失效。
     * gain 等价于 1.0 时<b>不拷贝</b>，直接复用基础版本（空气版或低通版）——
     * 默认音量下每次换块都不会白拷一整片 PCM。</p>
     */
    private Mp3Decoder.Pcm processedVersion(Mp3Decoder.Pcm air, boolean underwater, float wantGain) {
        final float g = clampGain(wantGain);
        if (processedPcm != null && processedSource == air
                && processedUnderwater == underwater
                && Math.abs(processedGain - g) < 0.0005f) {
            return processedPcm;
        }
        final long t0 = System.nanoTime();
        final Mp3Decoder.Pcm base = underwater ? waterVersion(air) : air;
        final Mp3Decoder.Pcm built;
        if (PcmGain.isIdentity(g)) {
            built = base;   // 恒等：零拷贝
        } else {
            built = new Mp3Decoder.Pcm(PcmGain.apply(base.samples, g), base.sampleRate, base.channels);
        }
        final long costMs = Math.max(0L, (System.nanoTime() - t0) / 1_000_000L);
        processedSource = air;
        processedUnderwater = underwater;
        processedGain = g;
        processedPcm = built;
        if (PcmGain.isIdentity(g)) {
            ReaderBridgeMod.LOGGER.debug(
                    "[readerbridge] TTS 数字增益 跳过 gain=1.00 samples={} 耗时={}ms（恒等，未做逐样本运算）",
                    built.samples.length, costMs);
        } else {
            // 这条就是「设定 240% → PCM 真的乘了 2.40」的直接证据
            ReaderBridgeMod.LOGGER.info(
                    "[readerbridge] TTS 数字增益 应用 gain={} samples={} 软限幅={} 耗时={}ms 低通={}",
                    String.format(java.util.Locale.ROOT, "%.2f", g),
                    built.samples.length,
                    g > 1.0005f ? "是" : "否",
                    costMs,
                    underwater ? "on" : "off");
        }
        return built;
    }

    /**
     * 就地重传当前块的 buffer 并保住播放位置（水下切换与音量热更新共用这一段）。
     *
     * <p>套路沿用原来的 {@code applyUnderwaterToBuffer}：记下 {@code positionSeconds()}
     * → 重新上传（{@link #uploadAndStart} 内部按当前水下状态 + 当前增益选版本）
     * → {@code AL_BYTE_OFFSET} 定位回原位置 → {@link #verifySeek(double)} 兜底核对
     * → 原本暂停的话立刻停回去。所以"改音量"和"进出水面"对播放头是完全一样的处理：
     * <b>立即生效、不重头、位置不跳</b>。</p>
     *
     * @return {@code true} = 真的重传了；{@code false} = 当前没有可续播的 buffer
     */
    private boolean reuploadCurrent(String reason) {
        final Mp3Decoder.Pcm air = current;
        if (air == null || !playing || sourceId < 0 || !isSourceValid()) {
            return false;
        }
        final int state = sourceState();
        if (state != AL10.AL_PLAYING && state != AL10.AL_PAUSED) {
            return false;
        }
        final double resumeAt = positionSeconds();
        final boolean wasPaused = paused;

        final long t0 = System.nanoTime();
        uploadAndStart(Request.plain(air, resumeAt, generation, 0L));
        final long costMs = Math.max(0L, (System.nanoTime() - t0) / 1_000_000L);

        if (wasPaused) {
            // 原本是暂停状态：换完 buffer 立刻停回去，保持"暂停中切换也已完成"的语义
            try {
                AL10.alSourcePause(sourceId);
                paused = true;
                pausedAtNanos = System.nanoTime();
                pausedTotalNanos = 0L;
            } catch (Throwable t) {
                lastError = "切换后恢复暂停失败：" + t;
            }
        }
        verifySeek(resumeAt);
        ReaderBridgeMod.LOGGER.info(
                "[readerbridge] TTS 重传 buffer（{}）耗时={}ms 位置={}s 低通={} PCM增益={}",
                reason, costMs,
                String.format(java.util.Locale.ROOT, "%.2f", resumeAt),
                underwaterState ? "on" : "off",
                gainText(gain));
        return true;
    }

    /** 非渲染线程调过 {@link #setGain}：buffer 里还是旧增益，在主线程补一次重传。 */
    private void consumeGainPending() {
        if (!gainReuploadPending) {
            return;
        }
        gainReuploadPending = false;
        reuploadCurrent("音量变化（跨线程补做）");
    }

    /**
     * 取（必要时生成）低通版本。
     *
     * <p>缓存键就是原始 PCM 的引用：换块后 {@code waterSource != current}，第一次需要时重建，
     * 之后同一块的反复水陆切换都直接命中，不做二次计算。</p>
     */
    private Mp3Decoder.Pcm waterVersion(Mp3Decoder.Pcm air) {
        final Mp3Decoder.Pcm cachedWater = waterPcm;
        if (cachedWater != null && waterSource == air) {
            return cachedWater;
        }
        final int channels = air.channels >= 2 ? 2 : 1;
        final short[] filtered = WaterLowpass.apply(air.samples, channels, air.sampleRate);
        final Mp3Decoder.Pcm built = new Mp3Decoder.Pcm(filtered, air.sampleRate, channels);
        waterSource = air;
        waterPcm = built;
        return built;
    }

    /** 状态变化必打 INFO，方便用户自己确认水下低通到底有没有切。 */
    private void logUnderwaterChange(String detail) {
        ReaderBridgeMod.LOGGER.info("[readerbridge] 水下低通 -> {}（{}Hz, 增益 {}, {}）",
                underwaterState ? "on" : "off", WaterLowpass.CUTOFF_HZ, WaterLowpass.GAIN, detail);
    }

    /**
     * 换 buffer 后核对播放头。
     *
     * <p>个别实现的 {@code AL_BYTE_OFFSET} 只在 {@code AL_BUFFER} 之后立刻设置才生效；
     * 这里读一次 {@code AL_SEC_OFFSET} 交叉验证，偏差超过 0.3s 时用秒偏移补一次 seek，
     * 保证 {@link #positionSeconds()} 不出现归零或跳变。</p>
     */
    private void verifySeek(double wantSeconds) {
        if (sourceId < 0 || !isSourceValid() || wantSeconds <= 0.05d) {
            return;
        }
        try {
            final float got = AL10.alGetSourcef(sourceId, AL11.AL_SEC_OFFSET);
            if (AL10.alGetError() != AL10.AL_NO_ERROR) {
                return;
            }
            if (Math.abs(got - (float) wantSeconds) > 0.30f) {
                AL10.alSourcef(sourceId, AL11.AL_SEC_OFFSET, (float) wantSeconds);
                AL10.alGetError();
                ReaderBridgeMod.LOGGER.debug("[readerbridge] 水下切换位置兜底 seek：{}s -> {}s",
                        got, wantSeconds);
            }
        } catch (Throwable ignored) {
            // 位置兜底失败不影响播放
        }
    }

    /** 播完（AL_STOPPED）后把状态落回"不在播放"。 */
    private void updateFinished() {
        if (!playing || paused) {
            return;
        }
        if (sourceState() == AL10.AL_STOPPED) {
            playing = false;
        }
    }

    /** OpenAL 播放头读不到时的兜底计时。 */
    private double fallbackPositionSeconds() {
        if (playStartNanos == 0L) {
            return 0d;
        }
        long now = (paused && pausedAtNanos != 0L) ? pausedAtNanos : System.nanoTime();
        long elapsed = now - playStartNanos - pausedTotalNanos;
        return Math.max(0d, elapsed / 1_000_000_000d);
    }

    // ================================================================== 内部：解码缓存

    private static String cacheKey(Path file) {
        String abs = file.toAbsolutePath().toString();
        long size = -1L;
        long mtime = -1L;
        try {
            size = Files.size(file);
            mtime = Files.getLastModifiedTime(file).toMillis();
        } catch (Throwable ignored) {
            // 用路径本身当 key
        }
        return abs + '|' + size + '|' + mtime;
    }

    private Mp3Decoder.Pcm cached(String key) {
        synchronized (decodeCache) {
            return decodeCache.get(key);
        }
    }

    private void store(String key, Mp3Decoder.Pcm pcm) {
        synchronized (decodeCache) {
            decodeCache.put(key, pcm);
        }
    }

    /**
     * 一次播放请求。
     *
     * <p>只带<b>原始</b> PCM（{@code air}）：真正上传的版本由 {@link #uploadAndStart} 内部
     * 通过 {@link #processedVersion}（低通 → 数字增益）统一派生。原来还有一个 {@code pcm}
     * 字段让调用方自己算版本，容易在"水下 + 音量"两个维度叠加时算错，已删除。</p>
     */
    private static final class Request {

        final Mp3Decoder.Pcm air;
        final double startSeconds;
        final long generation;
        final long decodeMs;

        Request(Mp3Decoder.Pcm air, double startSeconds, long generation, long decodeMs) {
            this.air = air;
            this.startSeconds = startSeconds;
            this.generation = generation;
            this.decodeMs = decodeMs;
        }

        /** 普通起播 / 重传：上传版本由 uploadAndStart 内部派生。 */
        static Request plain(Mp3Decoder.Pcm air, double startSeconds, long generation, long decodeMs) {
            return new Request(air, startSeconds, generation, decodeMs);
        }
    }
}
