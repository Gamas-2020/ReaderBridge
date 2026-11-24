package com.feiyu.readerbridge.client.fq;

import com.feiyu.readerbridge.client.audio.OpenAlTtsPlayer;
import com.feiyu.readerbridge.client.source.BookSources;
import com.feiyu.readerbridge.client.tts.EdgeTtsClient;
import com.feiyu.readerbridge.config.ReaderBridgeConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模组内的阅读引擎：把「什锦铺取书 → 切句 → Edge 合成 mp3 → MCI 播放 → 句子级状态」
 * 全部收进客户端进程，不依赖任何外部 App。
 *
 * <p>为什么这么设计（而不是继续走 WebSocket 外部核心）：
 * 用户要的是"开游戏就有"，不是"先开另一个程序"。数据本身在什锦铺里（用户自己的），
 * 而朗读只需要网络 + 系统解码器，没有一样必须放在外部进程里。
 *
 * <p>状态语义：
 * <ul>
 *   <li>{@link #sentence()} 是<b>正在朗读的那一句</b>，世界文字和 HUD 都读它</li>
 *   <li>句子推进由真实播放时钟（{@link System#nanoTime()}）驱动，不是估算字数</li>
 *   <li>块（约 {@value #BLOCK_CHARS} 字）之间靠预取衔接，避免每次换块停顿</li>
 * </ul>
 *
 * <p>线程模型：所有公开方法可从任意线程调用；内部合成在 {@code rb-synth} 单线程，
 * 计时在 {@code rb-tick} 单线程；监听器回调在<b>调用线程</b>触发，
 * 由 {@link #addListener(Runnable)} 的调用方自行决定是否切主线程。
 */
public final class ReaderEngine {

    /** 一块的目标字数。约 40~60 秒音频；太小请求多，太大首句延迟高。 */
    static final int BLOCK_CHARS = 1000;
    /** 预取的前瞻块数（1 = 播当前块时合成下一块）。 */
    static final int PREFETCH = 1;
    /** 句子推进的轮询间隔。80ms 足够跟手且不浪费 CPU。 */
    static final long TICK_MS = 80;

    // 语速档位：UI 上循环切换，映射到 Edge 的 -10..10
    private static final float[] SPEEDS = {0.8f, 1.0f, 1.25f, 1.5f, 2.0f};
    private static final int SPEED_DEFAULT_INDEX = 1;
    /** Edge 语速档位的取值范围（{@code EdgeTtsClient} 内部还会按 ±10% 一档换算成 prosody rate）。 */
    private static final int EDGE_RATE_MIN = -10;
    private static final int EDGE_RATE_MAX = 10;

    private static final ReaderEngine INSTANCE = new ReaderEngine();

    public static ReaderEngine get() {
        return INSTANCE;
    }

    // ---------------------------------------------------------------- 可观察状态

    private volatile boolean active;
    private volatile boolean paused;
    private volatile String bookTitle = "";
    private volatile String chapterTitle = "";
    private volatile String sentence = "";
    private volatile double progress;
    private volatile long lastUpdateMs = System.currentTimeMillis();
    private volatile String statusLine = "";
    private volatile String errorLine = "";

    /**
     * 朗读音量（0.0 ~ 1.0），独立于原版主音量/音乐/语音。
     *
     * <p>{@code -1} = 还没从配置读过（构造阶段读配置不安全，留到 {@link #prewarm(Path)}
     * 或第一次 {@link #volume()} 时惰性初始化）。真正下发在
     * {@link OpenAlTtsPlayer#setGain(float)}，走的是自己那条 source 的 {@code AL_GAIN}。
     */
    private volatile double volume = -1.0D;

    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final List<String> voices = new CopyOnWriteArrayList<>();

    private volatile String voiceShortName = "zh-CN-XiaoxiaoNeural";
    private volatile String voiceLabel = "Xiaoxiao";
    private volatile int speedIndex = SPEED_DEFAULT_INDEX;

    // ---------------------------------------------------------------- 内部

    /**
     * 多书源门面（什锦铺 fqapi + 配置里的规则书源）。
     *
     * <p>搜索是「所有源并发 + 单源超时/失败只丢自己」，所以什锦铺没开时仍能搜到规则源的书；
     * 目录/正文按 {@link FqApiClient.BookInfo#sourceId} 分发回对应的源。
     */
    private final BookSources sources = BookSources.get();
    // OpenAL 播放器是进程内单例（AL context 由 MC 建立，不能自己造），所以拿引用而不是 new
    private final OpenAlTtsPlayer player = OpenAlTtsPlayer.get();

    private final ExecutorService synth = Executors.newSingleThreadExecutor(daemon("rb-synth"));
    private final ScheduledExecutorService ticker =
            Executors.newSingleThreadScheduledExecutor(daemon("rb-tick"));

    private Future<?> pendingSynth;
    private ScheduledFuture<?> tickFuture;
    private final AtomicLong session = new AtomicLong();

    /** 当前书的目录缓存。 */
    private volatile List<FqApiClient.ChapterInfo> chapters = Collections.emptyList();
    private volatile String bookId = "";
    private volatile int chapterIndex = -1;
    /** 书架 + 续听进度（config/readerbridge/library.json）。 */
    private final LibraryStore library = LibraryStore.get();
    /**
     * 当前块的「第 0 句」是全书第几句。
     *
     * <p>正常从块头开始时它等于 {@code blocks.get(blockIdx)[0]}，但书架续听会把落点所在的块
     * 从中间裁开合成，那时 spans 的下标从裁剪点算起 —— 用块头换算会让高亮和续听进度整体偏移。
     */
    private volatile int blockStartSentence;
    /**
     * 下一次起播的句下标（-1 = 从块头开始）；被 synthesizeBlock 消费一次后清零。
     *
     * <p>不再「裁剪块」：合成永远是完整块（磁盘缓存才能与落点无关地复用），
     * 落点只决定 {@code player.play(path, offsetSeconds)} 的起始偏移。</p>
     */
    private volatile int startAtSentence = -1;
    /** 当前书的完整信息，供书架 touch 使用（openChapter 只有 bookId 不够写存档）。 */
    private volatile FqApiClient.BookInfo currentBook;
    /** 待续读的进度：目录是异步拉的，等它回来才可能开章。 */
    private volatile LibraryStore.Entry pendingResume;

    /** 当前章的句子全集（已过滤空白）。 */
    private volatile List<String> sentences = Collections.emptyList();
    /**
     * 当前章的原始正文段落（只读镜像）。
     *
     * <p>只服务于阅读面板的「正文」视图：面板要按段落排版、并知道当前朗读句落在哪一段的
     * 哪个字符区间。这里刻意只做<b>保存</b>，不参与切句/分块/合成，任何逻辑读它都不会
     * 改变播放行为。
     */
    private volatile List<String> paragraphs = Collections.emptyList();
    /** 块边界：blocks.get(i) = [start, end)。 */
    private final List<int[]> blocks = new ArrayList<>();
    private int blockIdx = -1;
    private int sentenceCursor;

    /** 正在播的块的落盘位置与时间轴。 */
    private EdgeTtsClient.Result playing;
    private String playingPath;
    private long playStartNanos;
    private long pausedElapsedMs;

    /** 预取结果（块下标 -> mp3 文件 + 时间轴）。 */
    private final java.util.Map<Integer, Prefetched> prefetched = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 预取结果（块下标 -> mp3 文件 + 时间轴 + 代次）。
     *
     * <p><b>为什么必须带 {@code session}</b>：预取任务在合成线程里跑，检查完
     * {@code s != session.get()} 之后还要把 mp3 落盘（{@code writeBlock}，磁盘 I/O，
     * 几十毫秒量级），落完盘才 {@code prefetched.put}。而切章走
     * {@link #openChapter(int, int)} 会 {@code session.incrementAndGet()} 并
     * {@code prefetched.clear()} —— 如果 clear 恰好落在「检查通过」与「put」之间，
     * 旧章的块就会被塞进新章的预取表；新章同一块下标一旦命中，播出来的是**上一章的
     * 声音**（用户报的「生成时切章会串音」）。只按块下标索引是这个 bug 的根。
     *
     * <p>加代次后命中要验一次：代次不符当没命中，正常走合成，代价只是一次网络往返。
     */
    private static final class Prefetched {
        final long session;
        final String path;
        final EdgeTtsClient.Result result;

        Prefetched(long session, String path, EdgeTtsClient.Result result) {
            this.session = session;
            this.path = path;
            this.result = result;
        }
    }

    private ReaderEngine() {
        voices.add("zh-CN-XiaoxiaoNeural");
        voices.add("zh-CN-YunxiNeural");
        voices.add("zh-CN-YunyangNeural");
        voices.add("zh-CN-XiaoyiNeural");
        voices.add("zh-CN-liaoning-XiaobeiNeural");
        voices.add("zh-CN-shaanxi-XiaoniNeural");
    }

    // ---------------------------------------------------------------- 只读访问器

    public boolean isActive() {
        return active;
    }

    public boolean isPaused() {
        return paused;
    }

    public String bookTitle() {
        return bookTitle;
    }

    public String chapterTitle() {
        return chapterTitle;
    }

    public String sentence() {
        return sentence;
    }

    public double progress() {
        return progress;
    }

    public long lastUpdateMs() {
        return lastUpdateMs;
    }

    public String statusLine() {
        return statusLine;
    }

    public String errorLine() {
        return errorLine;
    }

    public String voiceShortName() {
        return voiceShortName;
    }

    public String voiceLabel() {
        return voiceLabel;
    }

    public List<String> voices() {
        return Collections.unmodifiableList(voices);
    }

    public float speed() {
        return SPEEDS[Math.min(speedIndex, SPEEDS.length - 1)];
    }

    public String speedLabel() {
        float s = speed();
        return (s == Math.round(s) ? String.valueOf((int) s) : String.valueOf(s)) + "x";
    }

    // ---- 独立音量（只影响模组自己播放的 TTS，与 MC 主音量/音乐/语音无关）----

    /**
     * 朗读音量（0.0 ~ 4.0，4.0 = 400%）。
     *
     * <p>第一次调用时从配置惰性初始化（{@code [audio] ttsVolume}）并立刻下发给播放器，
     * 这样面板一打开显示的就是磁盘上的值，而不是先显示 100% 再跳。
     */
    public double volume() {
        double v = volume;
        if (v < 0.0D) {
            v = ReaderBridgeConfig.ttsVolume();
            volume = v;
            player.setGain((float) v);
            // 惰性初始化也要落日志：日志里看到这条 = "值来自磁盘配置、还没被面板点过"
            com.feiyu.readerbridge.ReaderBridgeMod.LOGGER.info(
                    "[readerbridge] TTS 音量 设定={}%（增益={}） 来源=惰性初始化（配置 ttsVolume）",
                    Math.round(v * 100.0D), gain3(v));
        }
        return Math.max(0.0D, Math.min(4.0D, v));
    }

    /**
     * 设置朗读音量（夹到 0.0 ~ 4.0，即 0% ~ 400%）。
     *
     * <p>上界 4.0 不是 bug：它就是 {@code PCM 数字增益}的上界，必须与
     * {@code ReaderBridgeConfig.TTS_VOLUME} 的 {@code defineInRange(…, 4.0D)}
     * 以及 {@link ReaderBridgeConfig#applyTtsVolume(double)} 的 clamp 保持同步。
     * 4.0 时由 {@code PcmGain} 做 tanh 软限幅（不是硬削波），所以 200%~400% 是
     * "更响且波形连续"，不是"更糊"。</p>
     *
     * <p>三个副作用都是刻意的：
     * <ol>
     *   <li><b>立刻</b>生效：{@link OpenAlTtsPlayer#setGain(float)} 会用新增益重传当前 buffer
     *       并 seek 回原位置，不等下一句/下一块 —— 点一次就能听出变化；</li>
     *   <li>写回配置并落盘（{@link ReaderBridgeConfig#applyTtsVolume(double)}），重启后保留；</li>
     *   <li>{@link #fire()} 通知面板/HUD 刷新百分比。</li>
     * </ol>
     *
     * <p><b>日志约束</b>：每次调用都<b>无条件</b>打一条 INFO，没有"值没变就跳过"的短路。
     * 上一轮排查「按了很多次 {@code +} 却一条日志都没有」时，正是因为这条日志不存在，
     * 才无法区分"点击没到"和"到了但没生效"。</p>
     */
    public void setVolume(double v) {
        double clamped = Double.isNaN(v) ? 1.0D : Math.max(0.0D, Math.min(4.0D, v));
        volume = clamped;
        com.feiyu.readerbridge.ReaderBridgeMod.LOGGER.info(
                "[readerbridge] TTS 音量 设定={}%（增益={}）",
                Math.round(clamped * 100.0D), gain3(clamped));
        player.setGain((float) clamped);
        ReaderBridgeConfig.applyTtsVolume(clamped);
        fire();
    }

    /** 三位小数（日志用，避免 2.3999999 这种浮点噪音）。 */
    private static String gain3(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    public List<FqApiClient.ChapterInfo> chapters() {
        return chapters;
    }

    public int chapterIndex() {
        return chapterIndex;
    }

    public String bookId() {
        return bookId;
    }

    // ---- 以下为阅读面板需要的只读镜像（不改变任何播放/切句行为）----

    /** 当前章的原始正文段落；还没取到正文时是空列表。 */
    public List<String> paragraphs() {
        return paragraphs;
    }

    /** 当前章的切句结果（只读视图）。 */
    public List<String> sentences() {
        return Collections.unmodifiableList(sentences);
    }

    /** 当前朗读句在 {@link #sentences()} 里的下标；-1 表示还没有开读。 */
    public int sentenceIndex() {
        return sentenceCursor;
    }

    /** 当前章的句子总数。 */
    public int sentenceCount() {
        return sentences.size();
    }

    /** 语速档位下标（0 起，配合 {@link #speedCount()} 画档位点）。 */
    public int speedIndex() {
        return Math.max(0, Math.min(speedIndex, SPEEDS.length - 1));
    }

    /** 语速档位总数。 */
    public int speedCount() {
        return SPEEDS.length;
    }

    public void addListener(Runnable r) {
        if (r != null) {
            listeners.add(r);
        }
    }

    public void removeListener(Runnable r) {
        listeners.remove(r);
    }

    private void fire() {
        lastUpdateMs = System.currentTimeMillis();
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (Throwable t) {
                // 单个监听器异常不影响引擎
            }
        }
    }

    /** 预热播放通道（进游戏时调用，避免第一句卡 1~2 秒）。 */
    public void prewarm(Path configDir) {
        // 朗读先走贴身声道（AL_SOURCE_RELATIVE，声源钉在听者身上）。
        // 播放器的默认值是「世界定位」，但朗读目前不喂锚点坐标，留着默认会让声源停在世界原点，
        // 与玩家相距几百格、超过 AL_MAX_DISTANCE，实际表现就是完全没声音。
        // 想改成「声音从世界文字处传来」，把这里换成 true 并在每帧喂 setWorldPosition 即可。
        player.setUseWorldPosition(false);
        // 音量：prewarm 时配置一定已加载（ForgeConfigSpec 在 mod 构造期完成 load），
        // 在这里读一次并下发，避免"用户上次调成 40%，重启后第一句用 100% 播"。
        volume = ReaderBridgeConfig.ttsVolume();
        player.setGain((float) volume);
        library.load(configDir);
        // 额外书源：configDir 就是 config/readerbridge，相对文件名会在它下面的 sources/ 里找。
        // 走异步是因为 extraSources 里可能写 http(s) 远程规则，主线程不能等网络。
        sources.reloadAsync(configDir);
        // 不再需要预热外部播放进程：OpenAL 播放器用的是 MC 自己的 AL context
    }

    // ---------------------------------------------------------------- 搜书 / 选章

    /**
     * 搜索。阻塞调用，必须在后台线程。
     *
     * <p>契约（GUI 侧直接依赖）：返回值恒非 null；什锦铺拒连或某个规则源挂了都不抛，
     * 只是那个源没结果。声明 {@code throws IOException} 是给旧调用方保持签名兼容。
     */
    public List<FqApiClient.BookInfo> search(String keyword) throws IOException {
        return sources.searchAll(keyword);
    }

    /** 打开一本书（只取目录，不朗读）。 */
    public void openBook(FqApiClient.BookInfo book) {
        openBook(book, false);
    }

    /**
     * 打开一本书，可选「目录到手后自动开第 0 章」。
     *
     * <p>游戏内点搜索结果是「想马上读」，但目录是异步拉的：调用方拿不到
     * 「目录就绪」回调，立刻调 openChapter 会被越界检查静默挡掉，表现为
     * 「点了没正文、也没有任何报错」。所以自动开章只能由引擎自己在目录
     * 到手之后接上。有续读请求（pendingResume）时优先续读，不覆盖进度。
     */
    public void openBook(FqApiClient.BookInfo book, boolean autoOpenFirst) {
        final long s = session.incrementAndGet();
        stopPlayback();
        active = false;
        paused = true;
        sentence = "";
        chapterTitle = "";
        blockIdx = -1;
        blocks.clear();
        sentences = Collections.emptyList();
        paragraphs = Collections.emptyList();
        prefetched.clear();
        // 换书必须立刻清掉上一本的目录：否则目录页会把旧书章节当成新书的显示出来，
        // 玩家点下去就是「新书 bookId + 旧书 itemId」，正文接口按 itemId 查必然失败，
        // 表现成「点了没正文」。目录到手后才重新赋值。
        chapters = Collections.emptyList();
        setStatus("正在取目录…");
        fire();

        synth.execute(() -> {
            try {
                List<FqApiClient.ChapterInfo> list = sources.chapters(book);
                if (s != session.get()) {
                    return;
                }
                chapters = list;
                bookId = book.bookId;
                bookTitle = book.name;
                currentBook = book;
                chapterIndex = -1;
                setStatus(list.isEmpty() ? "这本书没有目录" : ("目录 " + list.size() + " 章"));
                // 打开即上书架：即使这一章还没开始读，也算「最近在读」里的一本
                library.touch(book, -1, "", 0, list.size());
                fire();
                // 续读请求要等目录到手才可能开章，否则 openChapter 的越界检查会直接 return
                LibraryStore.Entry want = pendingResume;
                if (want != null && book.bookId.equals(want.bookId)) {
                    pendingResume = null;
                    if (!list.isEmpty()) {
                        int ch = Math.max(0, Math.min(want.chapterIndex, list.size() - 1));
                        openChapter(ch, want.sentenceIndex);
                    }
                } else if (autoOpenFirst && !list.isEmpty()) {
                    // 没有续读请求时，从第 0 章开始读
                    openChapter(0, 0);
                }
            } catch (Throwable t) {
                // 不能只 catch IOException：书源层抛 RuntimeException/Error 时会直接漏出
                // synth 线程，玩家看到的是「点了完全没反应、也没有任何报错」。
                // 这里必须全兜住，并且把原因同时写进 errorLine 和 stderr。
                if (s == session.get()) {
                    setError("目录读取失败：" + t);
                    System.err.println("[readerbridge] openBook 失败: " + t);
                    t.printStackTrace();
                }
            }
        });
    }

    /** 打开某一章并开始朗读（从章头开始）。 */
    public void openChapter(int index) {
        openChapter(index, 0);
    }

    /**
     * 打开某一章并从第 {@code startSentence} 句开始朗读。
     *
     * <p>书架「继续朗读」和正文点击定位都用它：从句续听而不是从章头重读。
     * 合成的是<b>完整</b>块（缓存才能复用），落点只作为起播偏移交给 OpenAL，
     * 因此后续块的预取与自动翻章逻辑都无需改动。
     */
    public void openChapter(int index, int startSentence) {
        if (index < 0 || index >= chapters.size()) {
            // 别静默 return：目录还没到手（或换书后被清空）时点章会走到这里，
            // 用户之前的体验是「点了完全没反应」。
            setError("这一章打不开：目录里没有第 " + (index + 1) + " 章（目录共 " + chapters.size() + " 章）");
            return;
        }
        startAtSentence = Math.max(0, startSentence);
        final long s = session.incrementAndGet();
        stopPlayback();
        blockIdx = -1;
        blocks.clear();
        prefetched.clear();
        sentences = Collections.emptyList();
        paragraphs = Collections.emptyList();
        chapterIndex = index;
        sentence = "";
        paused = false;
        active = true;
        setStatus("正在取正文…");
        fire();

        final FqApiClient.ChapterInfo ch = chapters.get(index);
        // 正文请求也要按来源分发，所以把当前书一起快照进 lambda：
        // 合成是异步的，等它跑起来时 currentBook 可能已经被「打开另一本」改掉了。
        final FqApiClient.BookInfo bookForContent = currentBook;
        chapterTitle = ch.title;

        synth.execute(() -> {
            try {
                List<String> paras = sources.paragraphs(bookForContent, ch);
                if (s != session.get()) {
                    return;
                }
                List<String> sents = splitSentences(paras);
                if (sents.isEmpty()) {
                    // 正文为空必须让用户看见原因：之前这里是静默 return，
                    // 面板上就是「正文一片空白、也没有任何提示」，只能靠猜。
                    // 两种情况分开说：正文 0 段（解析/接口问题）vs 段落全都切不出句子。
                    String why = paras.isEmpty()
                            ? ("这一章取不到正文（接口返回 0 段）：可能该书正文格式未识别，"
                                    + "详情见游戏日志里的 [readerbridge] 行")
                            : "这一章的正文里没有可朗读的句子（全是空白或标点）";
                    setError(why + "；" + ch.title);
                    System.err.println("[readerbridge] 正文为空：book=" + bookId
                            + " chapter=" + (index + 1) + " title=" + ch.title
                            + " itemId=" + ch.itemId
                            + " paragraphs=" + paras.size() + " sentences=" + sents.size());
                    return;
                }
                paragraphs = Collections.unmodifiableList(new ArrayList<>(paras));
                sentences = sents;
                buildBlocks(sents);
                // 续读点越界（章节被作者改过、存档来自旧版本）就退回章头，别让朗读卡死
                if (startAtSentence >= sents.size()) {
                    startAtSentence = 0;
                }
                setStatus("");
                if (currentBook != null) {
                    library.touch(currentBook, index, ch.title, Math.max(0, startAtSentence), chapters.size());
                }
                fire();
                synthesizeBlock(s, currentBlockOf(Math.max(0, startAtSentence)));
            } catch (IOException e) {
                if (s == session.get()) {
                    setError("正文读取失败：" + e.getMessage());
                }
            }
        });
    }

    // ---------------------------------------------------------------- 书架续听

    /**
     * 从书架记录处继续朗读。
     *
     * <p>不能简单调 {@code openBook + openChapter}：目录是异步拉的，此刻 {@code chapters}
     * 可能还是上一本书的（甚至空的），openChapter 会被越界检查直接挡掉。所以先记
     * {@code pendingResume}，等目录回到主流程里再开章；同一本书已经载入时就地开章，省一次请求。
     */
    public void resumeEntry(LibraryStore.Entry entry) {
        if (entry == null || entry.bookId == null || entry.bookId.isEmpty()) {
            return;
        }
        currentBook = entry.toBookInfo();
        if (entry.bookId.equals(bookId) && !chapters.isEmpty()) {
            if (entry.title != null && !entry.title.isEmpty()) {
                bookTitle = entry.title;
            }
            pendingResume = null;
            int ch = Math.max(0, Math.min(entry.chapterIndex, chapters.size() - 1));
            openChapter(ch, Math.max(0, entry.sentenceIndex));
            return;
        }
        pendingResume = entry;
        openBook(entry.toBookInfo());
    }

    // ---------------------------------------------------------------- 语速模式（direct / bake）

    /**
     * 当前语速调节方式，每次用到都直接读配置（运行期改配置文件立即生效，不需要重启）。
     *
     * <p>这两种模式<b>不是</b>同一套东西的两种参数，而是两条完全不同的链路：</p>
     * <ul>
     *   <li>{@code direct}（默认）：一份 0 档音频 + OpenAL 的 {@code AL_PITCH} 变速。
     *       零延迟、零网络、缓存跨语速复用，代价是音高跟着变。</li>
     *   <li>{@code bake}：语速交给 Edge 合成，音高正确，代价是每换一次档要重合成当前块。</li>
     * </ul>
     */
    private static boolean bakeMode() {
        return "bake".equals(ReaderBridgeConfig.speedMode());
    }

    /** 档位下标夹进合法范围：配置/状态异常时不让数组越界。 */
    private static int clampSpeedIndex(int idx) {
        return Math.max(0, Math.min(idx, SPEEDS.length - 1));
    }

    /**
     * 档位 → Edge 的 -10..10 语速档：{@code round((倍率 - 1) * 10)}。
     *
     * <p>0.8x→-2、1.0x→0、1.5x→+5、2.0x→+10。1.25x 因为 {@code 0.25 * 10 = 2.5}，
     * {@link Math#round(float)} 是「floor(x + 0.5)」，结果是 <b>+3</b>（不是 +2）。
     * 这里刻意不改写成「四舍六入」或截断：截断会让 0.8x 变成 -1（丢掉 0.2 档），
     * 反而与其它档位的比例不一致。</p>
     */
    private static int edgeRateOfSpeed(int idx) {
        int r = Math.round((SPEEDS[clampSpeedIndex(idx)] - 1.0f) * 10.0f);
        if (r < EDGE_RATE_MIN) {
            r = EDGE_RATE_MIN;
        }
        if (r > EDGE_RATE_MAX) {
            r = EDGE_RATE_MAX;
        }
        return r;
    }

    /**
     * 本次合成该用哪个 Edge 语速档，<b>全文件唯一的换算入口</b>
     * （{@code synthesizeBlock} 与它派生出的预取都用这里的结果）。
     *
     * <p>direct 恒 0：音频与语速无关，磁盘缓存才能在任意档位之间复用。
     * bake 用当前档位换算值：不同档位落到不同的缓存 key，不会互相顶替。</p>
     */
    private int synthRate() {
        return bakeMode() ? edgeRateOfSpeed(speedIndex) : 0;
    }

    /**
     * 本次播放该下发多大的 pitch。
     *
     * <p>bake 恒 1.0：语速已经烘焙进 mp3，再叠一次 {@code AL_PITCH} 就是双重变速
     * （1.5x 档会听到接近 2.25x 且明显更尖的声音）。direct 用档位倍率。</p>
     */
    private float playbackRateForMode() {
        return bakeMode() ? 1.0f : SPEEDS[clampSpeedIndex(speedIndex)];
    }

    // ---------------------------------------------------------------- 播放控制

    public void play() {
        if (!active) {
            return;
        }
        if (paused) {
            paused = false;
            if (playing != null) {
                // 从暂停恢复：用剩余时长继续计
                playStartNanos = System.nanoTime() - pausedElapsedMs * 1_000_000L;
                resumePlayback();
                startTicker();
            } else if (sentenceCursor >= sentences.size()) {
                synthesizeBlock(session.get(), 0);
            } else {
                synthesizeBlock(session.get(), currentBlockOf(sentenceCursor));
            }
            fire();
        }
    }

    public void pause() {
        paused = true;
        pausedElapsedMs = elapsedMs();
        pausePlayback();
        stopTicker();
        fire();
    }

    public void togglePlay() {
        if (!active) {
            return;
        }
        if (paused) {
            play();
        } else {
            pause();
        }
    }

    public void stop() {
        session.incrementAndGet();
        stopPlayback();
        active = false;
        paused = true;
        sentence = "";
        fire();
    }

    public void nextChapter() {
        if (chapterIndex + 1 < chapters.size()) {
            openChapter(chapterIndex + 1);
        }
    }

    public void prevChapter() {
        if (chapterIndex > 0) {
            openChapter(chapterIndex - 1);
        }
    }

    /**
     * 切到下一档语速。两种模式的语义完全不同，所以这里分两支。
     *
     * <p><b>direct</b>：只改 {@code AL_PITCH}。不重新合成、不清预取、不打断当前音频，
     * 切档零等待零网络；代价是音高随之变化（1.5x 偏尖、0.8x 偏低）—— 这是「不重新生成」
     * 唯一要付的代价，没有别的做法能同时满足两者。</p>
     *
     * <p><b>bake</b>：语速是合成参数，已落盘的旧音频没法"变成"新语速，只能按新档重合成。
     * 所以丢掉预取（旧 rate 的产物，留着会让下一块悄悄换回旧语速）、换 session
     * （在飞的旧 rate 合成回调按 session 语义作废）、停播、把 pitch 复位成 1.0，
     * 再从当前句所在块重新合成。</p>
     */
    public void cycleSpeed() {
        speedIndex = (speedIndex + 1) % SPEEDS.length;

        if (!bakeMode()) {
            player.setPlaybackRate(SPEEDS[clampSpeedIndex(speedIndex)]);
            setStatus("语速 " + speedLabel() + "（音高变速，已生效）");
            fire();
            return;
        }

        // 旧 rate 的预取块一律作废：否则下一块会命中旧语速音频，表现为"切了档却没变"
        prefetched.clear();
        final int target = Math.max(0, sentenceCursor);

        // 正文还没切好块（openChapter 的异步任务还没回来）：只记档位和提示。
        // 这里绝对不能 session.incrementAndGet()：那会让 openChapter 的取正文回调判定过期，
        // 整章再也不会合成。
        if (blocks.isEmpty()) {
            setStatus("语速 " + speedLabel() + "（正文载入后按新语速合成）");
            fire();
            return;
        }

        if (!active || paused) {
            // 没在播 / 暂停中：只换档位与提示，绝不主动开播（暂停被"合成完自动起播"打破很难理解）。
            // 但旧音频必须丢掉：它是旧 rate 的产物，留着会让恢复播放时听到的语速与 UI 档位不符。
            // stopPlayback() 只停不播；后续 play() 的 "playing == null" 分支会按新档重新合成，
            // startAtSentence 保证重合成后仍从当前句起播，而不是退回块头。
            startAtSentence = target;
            stopPlayback();
            player.setPlaybackRate(1.0f);
            setStatus("语速 " + speedLabel() + "（恢复播放时按新语速合成）");
            fire();
            return;
        }

        startAtSentence = target;
        final long s = session.incrementAndGet();
        stopPlayback();
        // 语速已经烘在音频里，pitch 再留在旧档位就是双重变速 —— 复位成原速
        player.setPlaybackRate(1.0f);
        setStatus("正在按 " + speedLabel() + " 重新合成…");
        fire();
        synthesizeBlock(s, currentBlockOf(target));
    }

    public void cycleVoice() {
        if (voices.isEmpty()) {
            return;
        }
        int i = voices.indexOf(voiceShortName);
        i = (i + 1) % voices.size();
        voiceShortName = voices.get(i);
        voiceLabel = shortLabel(voiceShortName);
        prefetched.clear();
        setStatus("音色 " + voiceLabel + "（正在换音色…）");
        fire();
        if (active && !paused) {
            // 不再先 stopPlayback：让旧音色把当前这句播完，新音色的块合成完直接接手，
            // 用户听不到中间那段纯静音。
            final long s = session.incrementAndGet();
            startAtSentence = Math.max(0, sentenceCursor);
            synthesizeBlock(s, currentBlockOf(sentenceCursor));
        }
    }

    /**
     * 跳到本章第 {@code idx} 句并立刻从那里朗读（正文点击定位用）。
     *
     * <p>两条路径都<b>不重新合成</b>：
     * <ul>
     *   <li>落点仍在当前块的时间轴里 → 直接把播放头挪到那句的 startMs，
     *       零延迟、零网络、不打断解码；</li>
     *   <li>落点在别的块 → 起播句记进 {@code startAtSentence}，合成的是<b>完整</b>块
     *       （磁盘缓存多半已经存在），起来后再按句偏移起播。</li>
     * </ul>
     */
    public void seekSentence(int idx) {
        if (!active || sentences.isEmpty()) {
            return;
        }
        int target = Math.max(0, Math.min(idx, sentences.size() - 1));
        EdgeTtsClient.Result r = playing;
        String path = playingPath;
        int local = target - blockStartSentence;
        if (r != null && path != null && local >= 0 && local < r.spans.size()) {
            double offsetSec = r.spans.get(local).startMs / 1000.0;
            sentenceCursor = target;
            sentence = sentences.get(target);
            paused = false;
            pausedElapsedMs = Math.round(offsetSec * 1000.0);
            playStartNanos = System.nanoTime() - pausedElapsedMs * 1_000_000L;
            player.play(java.nio.file.Path.of(path), offsetSec);
            // 与 startPlaying 同一套规则：direct 用档位倍率，bake 恒 1.0（语速已在音频里）。
            // 必须放在 play() 之后 —— play 入口会把 pitch 复位成 1.0，放在它前面会被吃掉。
            player.setPlaybackRate(playbackRateForMode());
            startTicker();
            updateProgress();
            library.touchProgress(bookId, chapterIndex, target);
            setStatus("");
            fire();
            return;
        }
        startAtSentence = target;
        paused = false;
        final long s = session.incrementAndGet();
        stopPlayback();
        synthesizeBlock(s, currentBlockOf(target));
        fire();
    }

    public void setVoice(String shortName) {
        if (shortName == null || shortName.isEmpty()) {
            return;
        }
        voiceShortName = shortName;
        voiceLabel = shortLabel(shortName);
        prefetched.clear();
        fire();
    }

    // ---------------------------------------------------------------- 合成与播放

    private void buildBlocks(List<String> sents) {
        blocks.clear();
        int start = 0;
        int chars = 0;
        for (int i = 0; i < sents.size(); i++) {
            chars += sents.get(i).length();
            if (chars >= BLOCK_CHARS) {
                blocks.add(new int[]{start, i + 1});
                start = i + 1;
                chars = 0;
            }
        }
        if (start < sents.size()) {
            blocks.add(new int[]{start, sents.size()});
        }
    }

    private int currentBlockOf(int sentenceIdx) {
        for (int i = 0; i < blocks.size(); i++) {
            int[] b = blocks.get(i);
            if (sentenceIdx >= b[0] && sentenceIdx < b[1]) {
                return i;
            }
        }
        return blocks.isEmpty() ? 0 : blocks.size() - 1;
    }

    /**
     * 重新生成当前这一块的语音（播放条「重新生成」按钮）。
     *
     * <p>典型用途：这一段合成出来串了音、断句怪、或服务端返回了残缺音频，想重来一次。
     * 只重做当前块，不切章、<b>不动 {@code session}</b> —— 播放位置、书架进度都不受影响。
     *
     * <p><b>必须先删磁盘缓存</b>：{@link #synthesizeBlock} 入口第一件事就是
     * {@code loadCachedBlock}，文件还在就直接命中旧音频，「重新生成」会等于什么都没做。
     * 而且 {@code writeBlock} 只在<b>文件大小不同</b>时才覆盖，同长度会原样保留 ——
     * 光靠重合成也救不回来。内存预取同样要丢掉。
     *
     * <p>起播落点保留在当前句（不是块头）：用户点重新生成是因为这一段不对，
     * 不是想从头再听一遍这几十句。
     */
    public void regenerateCurrentBlock() {
        if (!active || blocks.isEmpty() || sentences.isEmpty()) {
            setError("还没有正在朗读的内容，先选一章开始");
            fire();
            return;
        }
        final List<String> sent = sentences;
        int idx = blockIdx >= 0 ? blockIdx : currentBlockOf(Math.max(0, sentenceCursor));
        if (idx < 0 || idx >= blocks.size()) {
            setError("找不到当前所在的段落，无法重新生成");
            fire();
            return;
        }
        final int[] range = blocks.get(idx);
        final String voice = voiceShortName;
        final int rate = synthRate();
        // 落点夹进本块：sentenceCursor 可能已被推进到块外（块播完后的间隙）
        final int from = Math.max(range[0], Math.min(Math.max(0, sentenceCursor), range[1] - 1));

        prefetched.remove(idx);
        try {
            Path f = blockFile(sent, range[0], voice, rate);
            java.nio.file.Files.deleteIfExists(f);
            java.nio.file.Files.deleteIfExists(blockMeta(f));
        } catch (Throwable t) {
            // 删不掉也继续：合成结果大小不同时 writeBlock 会覆盖
            System.err.println("[readerbridge] 删除旧块缓存失败（已忽略）: " + t);
        }

        startAtSentence = from;
        if (pendingSynth != null) {
            pendingSynth.cancel(true);
            pendingSynth = null;
        }
        synthesizeBlock(session.get(), idx);
    }

    /** 提交合成任务：合成第 idx 块并（若仍是当前块）立即播放。 */
    private void synthesizeBlock(long s, int idx) {
        if (idx < 0 || idx >= blocks.size()) {
            return;
        }
        final int[] range = blocks.get(idx);
        final List<String> sent = sentences;
        final String voice = voiceShortName;
        // 语速在这里一次定下（调用线程算好，合成线程只用捕获值，中途切档也不会串）：
        //   direct → 恒 0，一份音频可被任意语速复用，语速由 OpenAL pitch 承担；
        //   bake   → 当前档位换算出的 Edge 档位，不同语速落在不同的缓存 key 上。
        final int rate = synthRate();

        // 起播落点只影响播放偏移，不影响合成范围
        int want = startAtSentence;
        if (want < range[0] || want >= range[1]) {
            want = range[0];
        }
        startAtSentence = -1;
        final int base = range[0];
        final int span = want;

        Prefetched hit = prefetched.remove(idx);
        // 代次不符 = 切章/换书之前那一代残留的预取，音频属于上一章的内容。
        // 丢掉当没命中（**不能 return**），继续往下走正常合成。
        if (hit != null && hit.session == s) {
            startPlaying(s, idx, hit.path, hit.result, base, span);
            return;
        }

        blockIdx = idx;

        // 磁盘缓存命中就直接播：块永远是完整合成的，key 与落点无关，
        // 所以「点到同一块里的第二句」不会再有第二次网络往返。
        EdgeTtsClient.Result cached = loadCachedBlock(sent, base, voice, rate);
        if (cached != null) {
            String cachedPath = blockFile(sent, base, voice, rate).toAbsolutePath().toString();
            startPlaying(s, idx, cachedPath, cached, base, span);
            prefetch(s, idx + 1, sent, voice, rate);
            return;
        }

        // bake 模式每次切档都是真的在重合成，文案带上档位用户才知道在等什么；
        // direct 模式 rate 恒 0，文案与旧版完全一致。
        setStatus(rate != 0
                ? ("正在按 " + speedLabel() + " 重新合成…")
                : (idx == 0 ? "正在合成语音…" : "正在合成下一段…"));
        fire();

        if (pendingSynth != null) {
            pendingSynth.cancel(true);
        }
        pendingSynth = synth.submit(() -> {
            try {
                List<String> slice = new ArrayList<>(sent.subList(base, range[1]));
                EdgeTtsClient.Result r = EdgeTtsClient.synthesize(slice, voice, rate);
                if (s != session.get()) {
                    return;
                }
                String path = writeBlock(r, sent, base, voice, rate);
                startPlaying(s, idx, path, r, base, span);
                prefetch(s, idx + 1, sent, voice, rate);
            } catch (Throwable t) {
                if (s == session.get()) {
                    setError("语音合成失败：" + t.getMessage());
                    paused = true;
                    active = false;
                    fire();
                }
            }
        });
    }

    private void prefetch(long s, int idx, List<String> sent, String voice, int rate) {
        if (idx >= blocks.size()) {
            return;
        }
        final int[] range = blocks.get(idx);
        synth.submit(() -> {
            try {
                if (s != session.get()) {
                    return;
                }
                String path = blockFile(sent, range[0], voice, rate).toAbsolutePath().toString();
                // 磁盘已有这块就直接登记，别再为下一块花一次网络往返
                EdgeTtsClient.Result cached = loadCachedBlock(sent, range[0], voice, rate);
                if (cached != null) {
                    prefetched.put(idx, new Prefetched(s, path, cached));
                    return;
                }
                List<String> slice = new ArrayList<>(sent.subList(range[0], range[1]));
                EdgeTtsClient.Result r = EdgeTtsClient.synthesize(slice, voice, rate);
                if (s != session.get()) {
                    return;
                }
                writeBlock(r, sent, range[0], voice, rate);
                // 落盘是慢操作，这期间可能已经切章并 prefetched.clear()：写完再验一次
                // 代次，否则旧章结果会污染新章的预取表（串音根因，见 Prefetched 注释）。
                if (s != session.get()) {
                    return;
                }
                prefetched.put(idx, new Prefetched(s, path, r));
            } catch (Throwable ignored) {
                // 预取失败无所谓，播到那一块时会重新合成
            }
        });
    }

    /**
     * 块文件的稳定命名：key = 音色 + 块首句下标 + 块首句文本（+ 语速档位），与落点无关。
     *
     * <p><b>为什么 rate 必须进 key</b>：bake 模式把语速烘进了 mp3，同一个块的 1.0x 和 1.5x
     * 是两份完全不同的音频。rate 不进 key 就会「换了语速却命中旧 mp3」，听起来压根没变，
     * 而且永远不会自愈（缓存一直命中，再也不重新合成）。</p>
     *
     * <p><b>为什么 rate 拼在末尾、且 0 档不拼</b>：direct 模式（以及 bake 的 1.0x 档）rate 恒为 0，
     * 不带后缀时拼出来的字符串与旧版本逐字节相同 → 用户已经下好的缓存继续命中，
     * 不会因为这次改动一次性全部作废。只有 bake 的非 0 档才多一段 {@code |r-2} / {@code |r+5}，
     * 与 0 档、与彼此都不串音。附带好处：{@code .meta} 是从 mp3 文件名派生的，
     * 命名规则一处生效，旁文件自动跟着走。</p>
     */
    private static Path blockFile(List<String> sent, int startIdx, String voice, int rate) {
        StringBuilder keySrc = new StringBuilder();
        keySrc.append(voice).append('|').append(startIdx).append('|');
        keySrc.append(sent.get(startIdx));
        if (rate != 0) {
            keySrc.append("|r").append(rate);
        }
        long h = fnv1a64(keySrc.toString());
        return ttsCacheDir().resolve(String.format("blk_%016x.mp3", h));
    }

    private static Path blockMeta(Path mp3) {
        String n = mp3.getFileName().toString();
        return mp3.resolveSibling(n.substring(0, n.length() - 4) + ".meta");
    }

    /**
     * 落盘 mp3 + 时间轴旁文件。
     *
     * <p>先写音频后写旁文件：「有 mp3 没 meta」= 未命中，绝不会出现
     * 「命中了一个不知道句子边界的块」。旁文件是纯文本（{@code 时长|音色} 一行，
     * 之后每行 {@code 句下标|起|止}），不引 Gson、不引 JSON 库。
     */
    private String writeBlock(EdgeTtsClient.Result r, List<String> sent, int startIdx,
                              String voice, int rate) throws IOException {
        Path dir = ttsCacheDir();
        Files.createDirectories(dir);
        Path file = blockFile(sent, startIdx, voice, rate);
        if (!Files.isRegularFile(file) || Files.size(file) != r.mp3.length) {
            Files.write(file, r.mp3);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(r.durationMs).append('|').append(r.voiceName == null ? "" : r.voiceName).append('\n');
        for (EdgeTtsClient.Span sp : r.spans) {
            sb.append(sp.index).append('|').append(sp.startMs).append('|').append(sp.endMs).append('\n');
        }
        Files.write(blockMeta(file), sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return file.toAbsolutePath().toString();
    }

    /**
     * 从磁盘缓存重建时间轴。mp3 与旁文件都齐才算命中。
     *
     * <p>返回的 {@code Result.mp3} 是空数组：命中路径只用来播已有文件，
     * 只有真正合成出来（或内存预取）的 Result 才带音频字节。
     */
    private static EdgeTtsClient.Result loadCachedBlock(List<String> sent, int startIdx,
                                                        String voice, int rate) {
        try {
            Path file = blockFile(sent, startIdx, voice, rate);
            Path meta = blockMeta(file);
            if (!Files.isRegularFile(file) || !Files.isRegularFile(meta)) {
                return null;
            }
            List<String> lines = Files.readAllLines(meta, java.nio.charset.StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                return null;
            }
            String[] head = lines.get(0).split("\\|");
            if (head.length == 0 || head[0].trim().isEmpty()) {
                return null;
            }
            long durationMs = Long.parseLong(head[0].trim());
            String voiceName = head.length > 1 ? head[1] : "";
            List<EdgeTtsClient.Span> spans = new ArrayList<>(lines.size());
            for (int i = 1; i < lines.size(); i++) {
                String ln = lines.get(i).trim();
                if (ln.isEmpty()) {
                    continue;
                }
                String[] p = ln.split("\\|");
                if (p.length < 3) {
                    continue;
                }
                spans.add(new EdgeTtsClient.Span(
                        Integer.parseInt(p[0].trim()),
                        Long.parseLong(p[1].trim()),
                        Long.parseLong(p[2].trim())));
            }
            if (spans.isEmpty()) {
                return null;
            }
            return new EdgeTtsClient.Result(new byte[0], durationMs, spans, voiceName);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Path ttsCacheDir() {
        return new java.io.File("config", "readerbridge").toPath().resolve("tts");
    }

    private void startPlaying(long s, int idx, String path, EdgeTtsClient.Result r, int base, int startSpan) {
        if (s != session.get()) {
            return;
        }
        startAtSentence = -1;
        playing = r;
        playingPath = path;
        blockIdx = idx;
        blockStartSentence = base;
        int span = startSpan;
        if (r.spans.isEmpty()) {
            span = base;
        } else {
            span = Math.max(base, Math.min(startSpan, base + r.spans.size() - 1));
        }
        sentenceCursor = span;
        if (span >= 0 && span < sentences.size()) {
            sentence = sentences.get(span);
        }
        // 起播偏移：spans 的下标相对块头，块内第 n 句在文件中的绝对起点就是它的 startMs
        double offsetSec = 0;
        int local = span - base;
        if (local > 0 && local < r.spans.size()) {
            offsetSec = r.spans.get(local).startMs / 1000.0;
        }
        pausedElapsedMs = Math.round(offsetSec * 1000.0);
        // nanoTime 兜底路径也要从落点算起，否则 OpenAL 尚未起播的那几帧会读到 0
        playStartNanos = System.nanoTime() - pausedElapsedMs * 1_000_000L;
        setStatus("");
        player.play(java.nio.file.Path.of(path), offsetSec);
        // pitch 必须放在 play() <b>之后</b>：OpenAlTtsPlayer.play 入口会把 pitch 复位成 1.0
        // （新块自带语速，避免上一块的临时变速跨块延续）。写在 play 前面会被这次复位吃掉，
        // direct 模式"换了块仍然 1.5x"就悄悄丢了 —— 所以顺序就是语义，别调换。
        player.setPlaybackRate(playbackRateForMode());
        startTicker();
        if (currentBook != null) {
            library.touchProgress(bookId, chapterIndex, span);
        }
        updateProgress();
        fire();
    }

    private void startTicker() {
        stopTicker();
        tickFuture = ticker.scheduleAtFixedRate(this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    private void stopTicker() {
        if (tickFuture != null) {
            tickFuture.cancel(false);
            tickFuture = null;
        }
    }

    private void tick() {
        try {
            EdgeTtsClient.Result r = playing;
            if (r == null || paused || !active) {
                return;
            }
            long ms = elapsedMs();
            if (ms >= r.durationMs) {
                // 本块播完 → 取下一块
                int next = blockIdx + 1;
                stopTicker();
                if (next >= blocks.size()) {
                    // 本章读完 → 自动下一章
                    if (chapterIndex + 1 < chapters.size()) {
                        openChapter(chapterIndex + 1);
                    } else {
                        active = false;
                        paused = true;
                        setStatus("全书读完");
                        fire();
                    }
                    return;
                }
                synthesizeBlock(session.get(), next);
                return;
            }
            int local = indexAt(r, ms);
            // 用 blockStartSentence 而不是 blocks.get(blockIdx)[0]：书架续听的那一块是从中间
            // 裁开合成的，spans 下标从裁剪点算起，套块头会让高亮和续听进度整体偏移。
            int global = blockStartSentence + local;
            if (global >= 0 && global < sentences.size()) {
                sentenceCursor = global;
                String s2 = sentences.get(global);
                if (!s2.equals(sentence)) {
                    sentence = s2;
                    updateProgress();
                    library.touchProgress(bookId, chapterIndex, global);
                    fire();
                }
            }
        } catch (Throwable ignored) {
            // 计时异常不打断朗读
        }
    }

    private void updateProgress() {
        int total = sentences.size();
        if (total <= 0 || chapters.isEmpty()) {
            progress = 0;
            return;
        }
        double inChapter = (double) sentenceCursor / total;
        progress = Math.min(1.0, (chapterIndex + inChapter) / Math.max(1, chapters.size()));
    }

    private long elapsedMs() {
        // 优先读 OpenAL 的真实播放头：估算值在解码/缓冲延迟下会偏快，句子高亮会跑到人声前面。
        // 只在「确认在播」时采信真实值：刚起播 offset 还是 0，播完后 source 停止、offset 不可靠，
        // 这两种情况回退到 nanoTime 估算，tick 的「播完切下一块」判断才不会被卡住。
        if (player.isPlaying()) {
            long real = Math.round(player.positionSeconds() * 1000.0);
            if (real > 0) {
                return real;
            }
        }
        if (playStartNanos == 0) {
            return pausedElapsedMs;
        }
        return (System.nanoTime() - playStartNanos) / 1_000_000L;
    }

    /** 按 spans 的 startMs 单调扫描，找当前句（与 Rust 侧的单调化假设一致）。 */
    private static int indexAt(EdgeTtsClient.Result r, long ms) {
        if (r.spans.isEmpty()) {
            return 0;
        }
        int best = r.spans.get(0).index;
        for (EdgeTtsClient.Span sp : r.spans) {
            if (sp.startMs > ms) {
                break;
            }
            best = sp.index;
        }
        return best;
    }

    private void stopPlayback() {
        stopTicker();
        player.stop();
        playing = null;
        playingPath = null;
        playStartNanos = 0;
        pausedElapsedMs = 0;
    }

    private void pausePlayback() {
        player.pause();
    }

    private void resumePlayback() {
        player.resume();
    }

    public void shutdown() {
        session.incrementAndGet();
        stopTicker();
        library.flush();
        player.shutdown();
        synth.shutdownNow();
        ticker.shutdownNow();
    }

    private void setStatus(String s) {
        statusLine = s == null ? "" : s;
    }

    private void setError(String s) {
        errorLine = s == null ? "" : s;
        setStatus("");
        fire();
    }

    // ---------------------------------------------------------------- 切句

    private static final char[] ENDERS = {'。', '！', '？', '!', '?', '…'};
    private static final char[] CLOSERS = {'”', '’', '」', '』', '）', ')', ']', '】'};
    static final int MAX_SENTENCE_CHARS = 120;

    /**
     * 段落 → 句子。
     *
     * <p>刻意<b>不按逗号断句</b>：一个自然句一次合成，逗号处的停顿交给语音模型，
     * 自己切会把语气切碎（这正是之前"断句碎、语气机械"的原因）。
     * 超过 {@value #MAX_SENTENCE_CHARS} 字的句子才在次级标点处再切。
     */
    static List<String> splitSentences(List<String> paragraphs) {
        List<String> out = new ArrayList<>();
        for (String p : paragraphs) {
            String text = p == null ? "" : p.trim();
            if (text.isEmpty()) {
                continue;
            }
            StringBuilder cur = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                cur.append(c);
                boolean end = false;
                for (char e : ENDERS) {
                    if (c == e) {
                        end = true;
                        break;
                    }
                }
                if (!end) {
                    continue;
                }
                // 把紧跟的收尾引号/括号带进本句，避免它们自成一句
                while (i + 1 < text.length() && isCloser(text.charAt(i + 1))) {
                    cur.append(text.charAt(++i));
                }
                // 连续标点（…… 或 ？！）一并吃掉
                while (i + 1 < text.length() && isEnder(text.charAt(i + 1))) {
                    cur.append(text.charAt(++i));
                }
                pushSentence(out, cur);
            }
            pushSentence(out, cur);
        }
        return out;
    }

    private static boolean isEnder(char c) {
        for (char e : ENDERS) {
            if (c == e) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCloser(char c) {
        for (char e : CLOSERS) {
            if (c == e) {
                return true;
            }
        }
        return false;
    }

    private static void pushSentence(List<String> out, StringBuilder cur) {
        String s = cur.toString().trim();
        cur.setLength(0);
        if (s.isEmpty()) {
            return;
        }
        if (s.length() <= MAX_SENTENCE_CHARS) {
            out.add(s);
            return;
        }
        // 超长句：优先在次级标点处切，仍超长就硬切（不拆代理对）
        int from = 0;
        while (from < s.length()) {
            int remain = s.length() - from;
            if (remain <= MAX_SENTENCE_CHARS) {
                String tail = s.substring(from).trim();
                if (!tail.isEmpty()) {
                    out.add(tail);
                }
                return;
            }
            int cut = -1;
            for (int i = from + MAX_SENTENCE_CHARS; i > from + MAX_SENTENCE_CHARS / 2; i--) {
                char c = s.charAt(i - 1);
                if (c == '，' || c == '、' || c == '；' || c == '：' || c == ',' || c == ';' || c == ':') {
                    cut = i;
                    break;
                }
            }
            if (cut < 0) {
                cut = from + MAX_SENTENCE_CHARS;
                if (Character.isLowSurrogate(s.charAt(cut))) {
                    cut--;
                }
            }
            String piece = s.substring(from, cut).trim();
            if (!piece.isEmpty()) {
                out.add(piece);
            }
            from = cut;
        }
    }

    // ---------------------------------------------------------------- 杂项

    private static String shortLabel(String id) {
        String s = id;
        int dash = s.indexOf('-');
        if (dash > 0 && dash + 1 < s.length()) {
            s = s.substring(dash + 1); // zh-CN-XiaoxiaoNeural -> CN-XiaoxiaoNeural
            int d2 = s.indexOf('-');
            if (d2 > 0 && d2 + 1 < s.length()) {
                s = s.substring(d2 + 1);
            }
        }
        if (s.endsWith("Neural")) {
            s = s.substring(0, s.length() - 6);
        }
        return s.isEmpty() ? id : s;
    }

    private static long fnv1a64(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    private static ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
}
