package com.feiyu.readerbridge.client.source;

import com.feiyu.readerbridge.client.fq.FqApiClient;
import com.feiyu.readerbridge.config.ReaderBridgeConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 多书源门面：把「什锦铺 fqapp」与配置里声明的「规则书源」捏成一个入口。
 *
 * <p>{@code ReaderEngine} 只认这个类：搜索走 {@link #searchAll(String)}（所有源并发），
 * 目录/正文按 {@link FqApiClient.BookInfo#sourceId} 分发回具体的源。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>什锦铺没开不能毒死整个搜索</b>：fqapi 是 HTTP 127.0.0.1:18921，
 *       端口拒连会在几毫秒内失败，这里只记一行日志，其余源的结果照常返回；</li>
 *   <li><b>单个规则源也不会拖垮整体</b>：每个源一个 future + 自己的超时上限，
 *       超时/异常都退化成空结果；</li>
 *   <li><b>配置改了不用重启游戏</b>：每次搜索前比对一次
 *       {@code [sources] extraSources} 的快照，变了就重载全部规则源（带 id 去重）。</li>
 * </ul>
 *
 * <p>线程：内部持有一个守护线程池 {@code rb-sources-N}，所有网络 IO 都在上面跑，
 * 绝不会占用渲染线程；{@link #searchAll} 本身是阻塞方法，必须在 IO 线程调用。
 */
public final class BookSources {

    /** fqapi（什锦铺）的源标识，与 {@link FqApiClient.BookInfo#sourceId} 的默认值一致。 */
    public static final String FQAPI = FqApiClient.SOURCE_FQAPI;

    /**
     * 本地 txt 书源的 id（{@code config/readerbridge/books/*.txt}）。
     *
     * <p>它不像规则源那样进 {@link #rules}：规则源要解析 JSON、要发 HTTP，而本地书源就是扫目录，
     * 没有「加载」这一步。所以这里只在分发处和搜索处各挂一个分支，并在 {@link #reload} 时
     * 建目录。配置里的书源文件不允许占用这个 id，否则目录/正文分发会出现两个候选。
     */
    public static final String LOCAL = LocalBooks.SOURCE_ID;

    /** 搜索结果总数上限：多源合并后可能几百条，面板滚不了那么多。 */
    private static final int MAX_RESULTS = 200;
    /** 等 fqapi 的上限（它自己请求超时是 15s，留 5s 余量）。 */
    private static final long FQAPI_DEADLINE_MS = 20_000L;
    /** 本地书扫描的上限：只是列目录读文件名，正常毫秒级；给 10s 是为了盖住机械盘/网络盘。 */
    private static final long LOCAL_DEADLINE_MS = 10_000L;
    /** 拉远程规则文本的超时。 */
    private static final int RULE_FETCH_TIMEOUT_MS = 15_000;

    private static final BookSources INSTANCE = new BookSources();

    private final FqApiClient fq = new FqApiClient();
    private final ExecutorService pool;
    private final AtomicInteger threadSeq = new AtomicInteger();

    /** 规则源列表（不可变快照，读多写少，volatile 发布）。 */
    private volatile List<RuleSource> rules = Collections.emptyList();
    /** 上次加载时的配置快照，用来判断「用户改了配置」。 */
    private volatile List<String> loadedTokens = Collections.emptyList();
    /** {@code config/readerbridge}，由 {@code ReaderEngine.prewarm} 传入。 */
    private volatile Path baseDir;

    private BookSources() {
        this.pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "rb-sources-" + threadSeq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    public static BookSources get() {
        return INSTANCE;
    }

    // ---------------------------------------------------------------- 加载 / 重载

    /**
     * 按 {@code [sources] extraSources} 重新加载全部规则书源。
     *
     * <p>幂等且安全：任何一条配错（路径不存在、JSON 语法错、选择器缺失、远程 404）
     * 都只跳过那一条并打日志，<b>不会</b>抛出到调用方，也不会清掉已经加载成功的其它源。
     *
     * @param configDir {@code config/readerbridge}；传 null 时相对路径退化为「相对游戏根目录」
     */
    public synchronized void reload(Path configDir) {
        if (configDir != null) {
            this.baseDir = configDir;
        }
        List<String> tokens = ReaderBridgeConfig.extraSources();
        List<RuleSource> loaded = new ArrayList<>();
        LinkedHashSet<String> seenIds = new LinkedHashSet<>();

        // ---------------------------------------------------------------- ① 书源目录（文件驱动）
        // 规则源现在<b>完全由文件驱动</b>：{@code config/readerbridge/sources/*.json} 里有什么就加载什么。
        // jar 里只剩「模板」——首次启动由 BuiltinSources 复制出去，之后用户可以随意编辑/删除/新增，
        // 程序再也不会覆盖已存在的文件（覆盖会把用户的修改静默丢掉）。
        boolean seedTemplates = true;
        try {
            seedTemplates = ReaderBridgeConfig.builtinSources();
        } catch (Throwable t) {
            // 配置未加载：按默认（植入模板）走，别因为读配置失败就一个源都没有
            seedTemplates = true;
        }
        if (seedTemplates) {
            try {
                BuiltinSources.exportMissing(configDir);
            } catch (Throwable t) {
                System.err.println("[readerbridge] 书源模板导出失败（不影响已有文件）: " + reason(t));
            }
        }

        // 本地书目录：首次启动就建出来，用户直接往里面丢 txt 即可。
        // 已存在的目录/文件一个都不动（Files.createDirectories 语义）。
        LocalBooks.ensureDir();

        int fileCount = 0;
        for (Path file : listSourceFiles(configDir)) {
            String name = file.getFileName().toString();
            String text;
            try {
                text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            } catch (Throwable t) {
                System.err.println("[readerbridge] 书源文件 " + name + " 读取失败：" + reason(t));
                continue;
            }
            if (isDisabledByFile(text)) {
                // 显式 enabled:false —— shuquge-novel 就靠这个默认不启用（详见 BuiltinSources javadoc）
                System.err.println("[readerbridge] 书源文件 " + name + " 标记 enabled=false，跳过");
                continue;
            }
            SourceRule rule;
            try {
                rule = SourceRule.parse(text);
            } catch (Throwable t) {
                System.err.println("[readerbridge] 书源文件 " + name + " 解析失败：" + reason(t));
                continue;
            }
            if (rule == null) {
                continue;
            }
            String id = rule.normalizedId();
            if (FQAPI.equalsIgnoreCase(id)) {
                System.err.println("[readerbridge] 书源文件 " + name + " 加载失败：id 不能叫 \"" + FQAPI + "\"（已被什锦铺占用）");
                continue;
            }
            if (LOCAL.equalsIgnoreCase(id)) {
                System.err.println("[readerbridge] 书源文件 " + name + " 加载失败：id 不能叫 \"" + LOCAL + "\"（本地 txt 书源占用）");
                continue;
            }
            if (seenIds.contains(id)) {
                // 目录里两份同 id：后读到的覆盖先读到的（文件名排序稳定，行为可预期）
                final String overrideId = id;
                boolean replaced = loaded.removeIf(r -> overrideId.equalsIgnoreCase(r.id()));
                System.err.println("[readerbridge] 书源文件 " + name
                        + (replaced ? (" 覆盖了同 id 的前一份 \"" + rule.id + "\"") : " 与前一份同 id，已忽略"));
                if (!replaced) {
                    continue;
                }
            } else {
                seenIds.add(id);
            }
            loaded.add(new RuleSource(rule));
            fileCount++;
        }

        // ---------------------------------------------------------------- ② extraSources 覆盖
        for (String token : tokens) {
            SourceRule rule;
            try {
                rule = loadToken(token);
            } catch (Throwable t) {
                System.err.println("[readerbridge] 额外书源 " + brief(token) + " 加载失败：" + reason(t));
                continue;
            }
            if (rule == null) {
                continue;
            }
            String id = rule.normalizedId();
            if (FQAPI.equalsIgnoreCase(id)) {
                System.err.println("[readerbridge] 额外书源 " + brief(token) + " 加载失败：id 不能叫 \"" + FQAPI + "\"（已被什锦铺占用）");
                continue;
            }
            if (LOCAL.equalsIgnoreCase(id)) {
                System.err.println("[readerbridge] 额外书源 " + brief(token) + " 加载失败：id 不能叫 \"" + LOCAL + "\"（本地 txt 书源占用）");
                continue;
            }
            if (seenIds.contains(id)) {
                // 与目录里的文件撞 id：用户配置优先 —— 摘掉文件那份再放配置的。
                final String overrideId = id;
                boolean replaced = loaded.removeIf(r -> overrideId.equalsIgnoreCase(r.id()));
                if (!replaced) {
                    System.err.println("[readerbridge] 额外书源 " + brief(token) + " 加载失败：id \"" + rule.id + "\" 与前面的书源重复");
                    continue;
                }
                System.err.println("[readerbridge] 额外书源 " + brief(token) + " 覆盖了同 id 的配置文件源 \"" + rule.id + "\"");
            } else {
                seenIds.add(id);
            }
            loaded.add(new RuleSource(rule));
        }

        this.rules = Collections.unmodifiableList(loaded);
        this.loadedTokens = tokens;
        if (loaded.isEmpty()) {
            System.err.println("[readerbridge] 规则书源：0 个（" + sourcesDirLabel(configDir)
                    + " 里没有可用文件、extraSources 也为空；只剩什锦铺 fqapi）");
        } else {
            StringBuilder sb = new StringBuilder();
            for (RuleSource r : loaded) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(r.id());
            }
            System.err.println("[readerbridge] 规则书源：已加载 " + loaded.size() + " 个（文件 " + fileCount
                    + " + 配置 " + Math.max(0, loaded.size() - fileCount) + "） -> " + sb);
        }
    }

    /** {@code config/readerbridge/sources/*.json}，按文件名排序（顺序稳定，快源先出结果）。 */
    private List<Path> listSourceFiles(Path configDir) {
        Path dir = sourcesDir(configDir);
        if (!Files.isDirectory(dir)) {
            return Collections.emptyList();
        }
        List<Path> files = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".json"))
                    .sorted(java.util.Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(files::add);
        } catch (Throwable t) {
            System.err.println("[readerbridge] 扫描书源目录失败：" + reason(t));
            return Collections.emptyList();
        }
        return files;
    }

    private Path sourcesDir(Path configDir) {
        Path base = configDir != null ? configDir
                : (this.baseDir != null ? this.baseDir : Path.of("config", "readerbridge"));
        return base.resolve("sources");
    }

    private String sourcesDirLabel(Path configDir) {
        return sourcesDir(configDir).toString();
    }

    /**
     * 文件里是否写了 {@code "enabled": false}。
     *
     * <p>这是「默认启用哪些书源」的唯一开关：{@code shuquge-novel.json} 默认就是 false
     * （它没有站内搜索、恒定 10.6 秒才出 0 结果，会把每次搜索拖死）。
     * 只认这一个布尔字段；JSON 写坏时不把它当成禁用 —— 交给
     * {@link SourceRule#parse(String)} 报那句能看懂的错误。
     */
    private boolean isDisabledByFile(String json) {
        try {
            com.google.gson.JsonElement root = com.google.gson.JsonParser.parseString(json);
            if (root == null || !root.isJsonObject()) {
                return false;
            }
            com.google.gson.JsonElement enabled = root.getAsJsonObject().get("enabled");
            return enabled != null && enabled.isJsonPrimitive() && !enabled.getAsBoolean();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 后台重载（{@code ReaderEngine.prewarm} 调它）。
     *
     * <p>为什么不在主线程直接 {@link #reload(Path)}：{@code extraSources} 里可能有
     * {@code http(s)://} 的远程规则，拉一次要几百毫秒到数秒，放在客户端初始化里就是白卡启动。
     * 重载失败也只是保持原有的书源，不影响游戏里任何功能。
     */
    public void reloadAsync(Path configDir) {
        pool.execute(() -> {
            try {
                reload(configDir);
            } catch (Throwable t) {
                System.err.println("[readerbridge] 额外书源重载失败（沿用已加载的书源）：" + reason(t));
            }
        });
    }

    /**
     * 把一条配置项变成 {@link SourceRule}。支持四种写法（与 toml 注释一致）：
     * 绝对路径 / 相对 {@code config/readerbridge/sources/} 的文件名 / 内联 JSON / http(s) 地址。
     */
    private SourceRule loadToken(String token) throws IOException {
        String t = token == null ? "" : token.trim();
        if (t.isEmpty()) {
            return null;
        }
        if (t.startsWith("{")) {
            return SourceRule.parse(t);
        }
        if (t.startsWith("http://") || t.startsWith("https://")) {
            return SourceRule.parse(RuleSource.fetchText(t, RULE_FETCH_TIMEOUT_MS));
        }
        Path file = resolveFile(t);
        if (file == null || !Files.isRegularFile(file)) {
            throw new IOException("文件不存在：" + t);
        }
        String text;
        try {
            text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IOException("读取失败 " + file + "：" + e.getMessage(), e);
        }
        return SourceRule.parse(text);
    }

    private Path resolveFile(String token) {
        Path direct;
        try {
            direct = Path.of(token);
        } catch (RuntimeException e) {
            return null; // Windows 上的非法路径字符
        }
        boolean looksAbsolute = direct.isAbsolute()
                || token.startsWith("/") || token.startsWith("\\");
        if (looksAbsolute) {
            return direct;
        }
        Path base = this.baseDir;
        if (base != null) {
            Path inSources = base.resolve("sources").resolve(token);
            if (Files.isRegularFile(inSources)) {
                return inSources;
            }
            Path inBase = base.resolve(token);
            if (Files.isRegularFile(inBase)) {
                return inBase;
            }
        }
        return direct;
    }

    /** 当前已加载的规则源快照（只读，诊断/日志用）。 */
    public List<RuleSource> rules() {
        return rules;
    }

    public int ruleCount() {
        return rules.size();
    }

    /** 当前生效的书源总数（含 fqapi 与本地 txt）。 */
    public int totalSourceCount() {
        return rules.size() + 2;
    }

    private void reloadIfConfigChanged() {
        List<String> current;
        try {
            current = ReaderBridgeConfig.extraSources();
        } catch (Throwable t) {
            return;
        }
        if (current.equals(loadedTokens)) {
            return;
        }
        synchronized (this) {
            if (!current.equals(loadedTokens)) {
                reload(baseDir);
            }
        }
    }

    // ---------------------------------------------------------------- 搜索（并发）

    /**
     * 在所有书源上并发搜索同一个关键词。
     *
     * <p>永远不会抛异常：某个源连不上/超时/规则过期都只丢那一个源的结果。
     * 返回顺序稳定 —— 本地 txt 的结果在最前（它没有网络延迟，且通常就是用户要找的那本），
     * 然后是 fqapi，再按配置顺序排规则源，源内保持站点顺序。
     *
     * @param keyword 关键词（空白直接返回空表，不触发任何网络）
     */
    public List<FqApiClient.BookInfo> searchAll(String keyword) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) {
            return Collections.emptyList();
        }
        reloadIfConfigChanged();

        List<RuleSource> snapshot = rules;
        List<CompletableFuture<List<FqApiClient.BookInfo>>> futures = new ArrayList<>(snapshot.size() + 2);

        // 本地 txt：纯文件扫描，没有任何网络，理论上第一个落地。
        // 放在列表最前面 —— 合并时按 futures 顺序取结果，用户自己放进 books/ 的那本书就排在前面。
        // catch 里抛 CompletionException 而不是 return 空表：lambda 的返回类型必须单一，
        // 否则泛型会被推断成 List<? extends Object>，futures 装不进去（编译期就报错）。
        futures.add(CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return LocalBooks.get().search(kw);
                    } catch (Throwable t) {
                        throw new CompletionException(t);
                    }
                }, pool)
                .orTimeout(LOCAL_DEADLINE_MS, TimeUnit.MILLISECONDS)
                .exceptionally(t -> {
                    System.err.println("[readerbridge] 本地书搜索失败/超时（已跳过，其它书源继续）：" + reason(t));
                    return Collections.emptyList();
                }));

        futures.add(CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return fq.search(kw);
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                }, pool)
                .orTimeout(FQAPI_DEADLINE_MS, TimeUnit.MILLISECONDS)
                .exceptionally(t -> {
                    System.err.println("[readerbridge] 什锦铺 fqapi 搜索失败（已跳过，其它书源继续）：" + reason(t));
                    return Collections.emptyList();
                }));

        for (RuleSource source : snapshot) {
            futures.add(CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            return source.search(kw);
                        } catch (IOException e) {
                            throw new CompletionException(e);
                        }
                    }, pool)
                    .orTimeout(searchDeadlineMs(source), TimeUnit.MILLISECONDS)
                    .exceptionally(t -> {
                        System.err.println("[readerbridge] 额外书源 " + source.id() + " 搜索失败（已跳过）：" + reason(t));
                        return Collections.emptyList();
                    }));
        }

        // 等所有源都落地（每个都有自己的超时上限，所以不会无限等）
        CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).join();

        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<FqApiClient.BookInfo> out = new ArrayList<>();
        for (CompletableFuture<List<FqApiClient.BookInfo>> future : futures) {
            List<FqApiClient.BookInfo> part;
            try {
                part = future.join();
            } catch (RuntimeException e) {
                continue; // exceptionally 已经兜底，这里只是保险
            }
            if (part == null) {
                continue;
            }
            for (FqApiClient.BookInfo book : part) {
                if (book == null || book.bookId.isEmpty()) {
                    continue;
                }
                // 「源 + 定位串」才是全局唯一：不同源允许出现相同的书名
                if (seen.add(book.sourceId + "\u0000" + book.bookId)) {
                    out.add(book);
                    if (out.size() >= MAX_RESULTS) {
                        return out;
                    }
                }
            }
        }
        return out;
    }

    /**
     * 单个规则源搜索结果的最长等待时间：它与站点之间存在 pages 次串行请求，
     * 所以是 {@code timeoutMs × pages} 再加一点余量。
     */
    private static long searchDeadlineMs(RuleSource source) {
        int pages = 1;
        try {
            SourceRule rule = source.rule();
            if (rule.search != null) {
                pages = Math.max(1, rule.search.pages);
            }
        } catch (RuntimeException ignored) {
            // 用默认单页
        }
        long total = (long) source.timeoutMs() * pages + 3_000L;
        return Math.max(5_000L, Math.min(45_000L, total));
    }

    // ---------------------------------------------------------------- 目录 / 正文

    /**
     * 取目录，按 {@code book.sourceId} 分发。
     *
     * @throws IOException 网络失败、书源已被移出配置、或目录页选择器一条都没命中
     */
    public List<FqApiClient.ChapterInfo> chapters(FqApiClient.BookInfo book) throws IOException {
        if (book == null) {
            throw new IOException("书为空");
        }
        String sourceId = normalizeSourceId(book.sourceId);
        if (LOCAL.equalsIgnoreCase(sourceId)) {
            // 本地 txt：从 books/*.txt 现读现解析（带 LRU + TTL 缓存，见 LocalBooks#load）。
            return LocalBooks.get().chapters(book.bookId);
        }
        if (FQAPI.equalsIgnoreCase(sourceId)) {
            return fq.chapters(book.bookId);
        }
        RuleSource source = find(sourceId);
        if (source == null) {
            throw new IOException("书源 \"" + sourceId + "\" 已不可用（可能已从配置里移除），请在书架里删掉这本书再重搜");
        }
        try {
            return source.chapters(book.bookId);
        } catch (RuntimeException e) {
            // 规则书源内部若冒出非受检异常（非法 URL / 非法请求头等），统一归一成 IOException：
            // 调用方（ReaderEngine）只会 catch IOException，否则异常会直接打死合成线程。
            throw new IOException("书源 \"" + sourceId + "\" 取目录异常：" + e, e);
        }
    }

    /**
     * 取正文，按 {@code book.sourceId} 分发。
     *
     * @throws IOException 网络失败、书源不可用、或正文选择器没命中
     */
    public List<String> paragraphs(FqApiClient.BookInfo book, FqApiClient.ChapterInfo chapter) throws IOException {
        if (chapter == null) {
            throw new IOException("章节为空");
        }
        String sourceId = normalizeSourceId(book == null ? null : book.sourceId);
        if (LOCAL.equalsIgnoreCase(sourceId)) {
            if (book == null) {
                throw new IOException("本地书为空");
            }
            // 本地书源按 book + chapter 定位：chapter.itemId 只是 index 的字符串形式，
            // 单看它无法确定是哪本书的文件。
            return LocalBooks.get().paragraphs(book, chapter);
        }
        if (FQAPI.equalsIgnoreCase(sourceId)) {
            return fq.paragraphs(chapter.itemId);
        }
        RuleSource source = find(sourceId);
        if (source == null) {
            throw new IOException("书源 \"" + sourceId + "\" 已不可用（可能已从配置里移除）");
        }
        try {
            return source.paragraphs(chapter.itemId);
        } catch (RuntimeException e) {
            throw new IOException("书源 \"" + sourceId + "\" 取正文异常：" + e, e);
        }
    }

    /** 按 id 找规则源（大小写不敏感，忽略首尾空白）。 */
    public RuleSource find(String sourceId) {
        String id = sourceId == null ? "" : sourceId.trim();
        if (id.isEmpty()) {
            return null;
        }
        for (RuleSource source : rules) {
            if (source.id().equalsIgnoreCase(id)) {
                return source;
            }
        }
        return null;
    }

    /** 空/缺省的 sourceId 一律当什锦铺（兼容旧书架存档）。 */
    public static String normalizeSourceId(String sourceId) {
        if (sourceId == null) {
            return FQAPI;
        }
        String s = sourceId.trim();
        return s.isEmpty() ? FQAPI : s;
    }

    // ---------------------------------------------------------------- 小工具

    private static String brief(String token) {
        if (token == null) {
            return "<null>";
        }
        String t = token.trim();
        if (t.startsWith("{")) {
            return "<内联 JSON " + Math.min(t.length(), 60) + " 字符>";
        }
        return t.length() <= 120 ? t : (t.substring(0, 117) + "...");
    }

    /** 把异常链压成一句话（日志里不需要堆栈）。 */
    private static String reason(Throwable t) {
        Throwable cur = t;
        String message = null;
        int guard = 0;
        while (cur != null && guard++ < 8) {
            String m = cur.getMessage();
            if (m != null && !m.trim().isEmpty()) {
                message = m.trim();
            }
            cur = cur.getCause() == cur ? null : cur.getCause();
        }
        if (message == null) {
            message = t == null ? "未知错误" : t.getClass().getSimpleName();
        }
        return message.length() <= 300 ? message : (message.substring(0, 297) + "...");
    }

    /** 取消并清理线程池（仅测试/关服时用，正常游戏生命周期里不调用）。 */
    public void shutdown() {
        pool.shutdownNow();
    }
}
