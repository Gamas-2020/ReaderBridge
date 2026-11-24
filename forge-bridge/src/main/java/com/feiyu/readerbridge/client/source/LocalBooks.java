package com.feiyu.readerbridge.client.source;

import com.feiyu.readerbridge.client.fq.FqApiClient;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * 本地 TXT 书源：读 {@code <gameDirectory>/config/readerbridge/books/*.txt}。
 *
 * <p>与 Rust 侧 {@code reader-app/core/src/source/local.rs} 的 {@code LocalSource} 是同一套语义：
 * 扫描目录而不是持有文件列表，所以用户拖拽/手放进目录的文件下次搜索就能看到；
 * 分章是保守启发式，识别不出章节标题就按 {@value #FALLBACK_CHUNK_CHARS} 字硬切，永不丢正文。
 *
 * <p><b>目录必须是绝对路径</b>：{@code Minecraft.getInstance().gameDirectory} 的绝对形式，
 * 再拼 {@code config/readerbridge/books}。这个项目以前吃过亏 —— 相对路径在游戏里解析到了
 * 别的地方（JVM 工作目录 ≠ 游戏目录），所以这里一行相对路径都不用。
 *
 * <p>目录与既有的 {@code config/readerbridge/sources/}（书源规则 JSON）、
 * {@code config/readerbridge/library.json}（书架进度）平级，互不干扰。
 *
 * <p>线程：所有读盘方法都是阻塞 IO，禁止在渲染线程调用（{@link BookSources} 在自己的
 * 守护线程池里调）。缓存的读写用 {@code synchronized} 保护，可被多线程并发访问。
 */
public final class LocalBooks {

    /** 书源标识，与 {@link FqApiClient.BookInfo#sourceId} 对应。 */
    public static final String SOURCE_ID = "local";

    /** 面板/日志里显示的书源名。 */
    public static final String DISPLAY_NAME = "本地 TXT";

    /** 识别不出章节标题时的兜底切块长度（字符数），与 Rust 侧同名常量一致。 */
    static final int FALLBACK_CHUNK_CHARS = 4000;

    /** 章节标题最长容忍字符数，超出当正文（避免把长段落当标题）。与 Rust 侧一致。 */
    private static final int MAX_TITLE_CHARS = 40;

    /** 解析结果缓存上限（本数）。 */
    private static final int CACHE_LIMIT = 8;

    /** 解析结果缓存有效期。 */
    private static final long CACHE_TTL_MS = 600_000L;

    /** 文件名/安全化后的 bookKey 长度上限。 */
    private static final int MAX_KEY_CHARS = 80;

    /** 搜索最多返回本数（Rust 侧 MAX_SEARCH_HITS）。 */
    private static final int MAX_SEARCH_HITS = 200;

    /** 中文数字 + 阿拉伯数字（含全角），用于「第X章」识别。 */
    private static final String NUMERALS =
            "零〇一二三四五六七八九十百千万亿两0123456789０１２３４５６７８９";

    /** 「第X<单位>」的单位（Rust 侧是 6 个，含「部」）。 */
    private static final String[] CHAPTER_UNITS = {"章", "回", "节", "卷", "篇", "部"};

    /** 不需要数字前缀的固定章节名（Rust 侧同样 8 个）。 */
    private static final String[] SPECIAL_HEADS = {
            "序章", "楔子", "引子", "序幕", "尾声", "后记", "终章", "番外"};

    private static final LocalBooks INSTANCE = new LocalBooks();

    public static LocalBooks get() {
        return INSTANCE;
    }

    // ---------------------------------------------------------------- 缓存

    private static final class CachedBook {
        final List<Chapter> chapters;
        final long fetchedAtMs;
        final long fileSize;
        final long fileMtime;

        CachedBook(List<Chapter> chapters, long fetchedAtMs, long fileSize, long fileMtime) {
            this.chapters = chapters;
            this.fetchedAtMs = fetchedAtMs;
            this.fileSize = fileSize;
            this.fileMtime = fileMtime;
        }
    }

    /** accessOrder=true 即 LRU：读一次算一次访问，满了淘汰最久没读的那本。 */
    private final Map<String, CachedBook> cache =
            new LinkedHashMap<String, CachedBook>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedBook> eldest) {
                    return size() > CACHE_LIMIT;
                }
            };

    private LocalBooks() {
    }

    // ---------------------------------------------------------------- 目录

    /**
     * {@code <gameDirectory>/config/readerbridge/books} 的绝对值。不存在就建；已存在的文件一个不动。
     *
     * @throws IOException 目录建不出来（只读安装目录、权限不足等）—— 调用方自己决定怎么提示
     */
    public static Path booksDir() throws IOException {
        Path dir = booksDirIfKnown();
        Files.createDirectories(dir);
        return dir;
    }

    /** 与 {@link #booksDir()} 同路径，但<b>不建目录</b>、不抛异常（日志与诊断用）。 */
    public static Path booksDirIfKnown() {
        return gameRoot().resolve("config").resolve("readerbridge").resolve("books");
    }

    /**
     * 游戏根目录的绝对路径。
     *
     * <p>只认 {@code Minecraft.getInstance().gameDirectory}；Minecraft 还没起来（单元测试、
     * 极端早期的静态初始化）时才退到 {@code user.dir}，这条兜底只为不让调用方 NPE，
     * 正常游戏流程一定走前者。
     */
    private static Path gameRoot() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.gameDirectory != null) {
                return mc.gameDirectory.toPath().toAbsolutePath().normalize();
            }
        } catch (Throwable t) {
            System.err.println("[readerbridge] 取 gameDirectory 失败（退到 user.dir）：" + reason(t));
        }
        return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    /** 首次访问时把目录建出来（幂等；失败只打日志，不打断搜索）。 */
    public static void ensureDir() {
        try {
            Path dir = booksDir();
            if (!Files.isDirectory(dir)) {
                System.err.println("[readerbridge] 本地书目录不可用：" + dir);
            }
        } catch (Throwable t) {
            System.err.println("[readerbridge] 本地书目录创建失败：" + reason(t));
        }
    }

    /** 丢掉全部解析缓存（「刷新」按钮、文件刚复制进来时调用）。 */
    public void clearCache() {
        synchronized (cache) {
            cache.clear();
        }
    }

    // ---------------------------------------------------------------- 搜索 / 目录 / 正文

    /**
     * 按关键词搜本地书。
     *
     * <p>关键词为空时列出全部（与 Rust 侧 {@code LocalSource::search} 一致：本地书数量少，
     * 面板打开书架就想看到全量）。匹配文件名主干（大小写不敏感），也匹配作者。
     *
     * <p>永不抛异常：目录不存在/读不了就是 0 本。
     */
    public List<FqApiClient.BookInfo> search(String keyword) {
        String needle = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        List<Path> files = listTxtFiles();
        List<FqApiClient.BookInfo> out = new ArrayList<>(files.size());
        for (Path file : files) {
            String key = bookKeyOf(file);
            if (key.isEmpty()) {
                continue;
            }
            String title = displayTitleOf(file);
            if (!needle.isEmpty()) {
                String author = detectAuthor(file);
                boolean hit = key.toLowerCase(Locale.ROOT).contains(needle)
                        || title.toLowerCase(Locale.ROOT).contains(needle)
                        || (author != null && author.toLowerCase(Locale.ROOT).contains(needle));
                if (!hit) {
                    continue;
                }
            }
            String author = detectAuthor(file);
            out.add(new FqApiClient.BookInfo(key, title, author == null ? "" : author, "", SOURCE_ID));
            if (out.size() >= MAX_SEARCH_HITS) {
                break;
            }
        }
        return out;
    }

    /**
     * 取目录：解析整本书并返回章节表。
     *
     * @param bookKey 文件名主干安全化后的串（{@link FqApiClient.BookInfo#bookId}）
     * @throws IOException 文件不存在或读不了
     */
    public List<FqApiClient.ChapterInfo> chapters(String bookKey) throws IOException {
        List<Chapter> chapters = load(bookKey);
        List<FqApiClient.ChapterInfo> out = new ArrayList<>(chapters.size());
        for (int i = 0; i < chapters.size(); i++) {
            out.add(new FqApiClient.ChapterInfo(i, String.valueOf(i), chapters.get(i).title));
        }
        return out;
    }

    /**
     * 取一章正文，每个元素一段。
     *
     * @param chapter 必须来自 {@link #chapters(String)}（只用它的 {@code index}）
     * @throws IOException 文件不见了 / 章节越界 / 读盘失败
     */
    public List<String> paragraphs(FqApiClient.BookInfo book, FqApiClient.ChapterInfo chapter)
            throws IOException {
        if (book == null) {
            throw new IOException("书为空");
        }
        if (chapter == null) {
            throw new IOException("章节为空");
        }
        List<Chapter> chapters = load(book.bookId);
        int index = chapter.index;
        if (index < 0 || index >= chapters.size()) {
            throw new IOException("章节越界：" + index + " / " + chapters.size());
        }
        return toParagraphs(chapters.get(index).body);
    }

    // ---------------------------------------------------------------- 导入（复制进 books/）

    /** 一次导入的结果：复制成功几本、跳过了哪些、哪些失败。 */
    public static final class ImportReport {
        public final int copied;
        public final List<String> skipped = new ArrayList<>();
        public final List<String> failed = new ArrayList<>();

        ImportReport(int copied) {
            this.copied = copied;
        }

        /** 给面板用的一行中文摘要。 */
        public String summary() {
            StringBuilder sb = new StringBuilder("已导入 " + copied + " 本");
            if (!skipped.isEmpty()) {
                sb.append("；跳过 ").append(skipped.size()).append(" 个非 txt 文件");
            }
            if (!failed.isEmpty()) {
                sb.append("；").append(failed.size()).append(" 个失败");
            }
            return sb.toString();
        }
    }

    /**
     * 把若干外部文件<b>复制</b>进 books 目录（原文件保留）。
     *
     * <p>只收 {@code .txt}（大小写不敏感）；其它扩展名不报错、只记进 {@link ImportReport#skipped}。
     * 目标重名时加 {@code _1}、{@code _2} 后缀，<b>绝不覆盖</b>用户已有的文件。
     */
    public ImportReport importFiles(Collection<Path> paths) {
        List<String> skipped = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int copied = 0;
        if (paths == null || paths.isEmpty()) {
            return new ImportReport(0);
        }
        Path dir;
        try {
            dir = booksDir();
        } catch (Throwable t) {
            failed.add("目录不可用：" + reason(t));
            ImportReport broken = new ImportReport(0);
            broken.failed.addAll(failed);
            return broken;
        }
        for (Path src : paths) {
            if (src == null) {
                continue;
            }
            String name = src.getFileName() == null ? "" : src.getFileName().toString();
            if (!isTxt(name)) {
                skipped.add(name.isEmpty() ? "<未知文件>" : name);
                continue;
            }
            try {
                if (!Files.isRegularFile(src)) {
                    failed.add(name + "（不是文件）");
                    continue;
                }
                Path target = uniqueTarget(dir, name);
                Files.copy(src, target, StandardCopyOption.COPY_ATTRIBUTES);
                copied++;
            } catch (Throwable t) {
                failed.add(name + "（" + reason(t) + "）");
            }
        }
        if (copied > 0) {
            clearCache();
        }
        ImportReport result = new ImportReport(copied);
        result.skipped.addAll(skipped);
        result.failed.addAll(failed);
        return result;
    }

    /**
     * 弹系统文件选择器（AWT {@link java.awt.FileDialog}）选 txt 并导入。
     *
     * <p><b>会阻塞调用线程</b>，必须在后台线程调。返回 {@code null} 表示对话框在当前
     * GLFW 环境里起不来（HeadlessException、抢焦点失败、AWT 初始化被禁……）——
     * 调用方据此退化成「打开 books 文件夹让用户手动放」。
     *
     * <p>为什么不用 Swing {@code JFileChooser}：它在 GLFW 独占的窗口环境里更容易把
     * 事件线程和渲染线程缠在一起；FileDialog 是原生对话框，创建/销毁都更轻。
     */
    public ImportReport pickAndImportBlocking() {
        java.awt.FileDialog dialog = null;
        try {
            dialog = new java.awt.FileDialog((java.awt.Frame) null, "选择本地书籍（.txt）",
                    java.awt.FileDialog.LOAD);
            dialog.setMultipleMode(true);
            dialog.setFilenameFilter((d, name) -> isTxt(name));
            dialog.setVisible(true);
            java.io.File[] files = dialog.getFiles();
            List<Path> picked = new ArrayList<>();
            if (files != null) {
                for (java.io.File f : files) {
                    if (f != null && isTxt(f.getName())) {
                        picked.add(f.toPath());
                    }
                }
            }
            if (picked.isEmpty()) {
                ImportReport empty = new ImportReport(0);
                return empty;
            }
            return importFiles(picked);
        } catch (Throwable t) {
            System.err.println("[readerbridge] 文件选择器不可用（退化到打开文件夹）：" + reason(t));
            return null;
        } finally {
            if (dialog != null) {
                try {
                    dialog.dispose();
                } catch (Throwable ignored) {
                    // 关不掉就算了，不影响导入结果
                }
            }
        }
    }

    /**
     * 用资源管理器打开 books 目录（上传按钮的兜底方案）。
     *
     * @return 是否成功发起（失败只打日志，调用方仍提示用户手动去找目录）
     */
    public static boolean openBooksFolder() {
        Path dir;
        try {
            dir = booksDir();
        } catch (Throwable t) {
            System.err.println("[readerbridge] 打开本地书目录失败（创建目录）：" + reason(t));
            return false;
        }
        try {
            // 路径含空格必须引号包住，否则 explorer.exe 会把空格后的部分当第二个参数
            String cmd = "explorer.exe \"" + dir.toAbsolutePath() + "\"";
            Runtime.getRuntime().exec(cmd);
            return true;
        } catch (Throwable t) {
            System.err.println("[readerbridge] 打开本地书目录失败：" + reason(t));
            return false;
        }
    }

    // ---------------------------------------------------------------- 文件扫描

    /** 目录里全部 .txt（按修改时间降序，新导入的在前）。 */
    private List<Path> listTxtFiles() {
        Path dir = booksDirIfKnown();
        if (!Files.isDirectory(dir)) {
            return Collections.emptyList();
        }
        List<Path> files = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> isTxt(p.getFileName().toString()))
                    .forEach(files::add);
        } catch (Throwable t) {
            System.err.println("[readerbridge] 扫描本地书目录失败：" + reason(t));
            return Collections.emptyList();
        }
        files.sort(Comparator
                .comparingLong((Path p) -> lastModified(p))
                .reversed()
                .thenComparing(p -> p.getFileName().toString()));
        return files;
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static boolean isTxt(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".txt");
    }

    /** 文件名主干（书名原文，不做安全化）。 */
    private static String displayTitleOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return stem.trim();
    }

    /** 文件名主干 → bookKey（安全化 + 80 字符上限），与 Rust 的 {@code sanitize_title} 同规则。 */
    static String bookKeyOf(Path file) {
        return sanitizeKey(displayTitleOf(file));
    }

    /** 按 bookKey 反查文件（遍历比对安全化后的主干，这样原名带非法字符也能找到）。 */
    private Path resolveFile(String bookKey) {
        if (bookKey == null || bookKey.trim().isEmpty()) {
            return null;
        }
        String want = bookKey.trim();
        for (Path file : listTxtFiles()) {
            if (want.equals(bookKeyOf(file))) {
                return file;
            }
        }
        return null;
    }

    /** 用户给的 bookKey 找不到时，允许「未安全化原名」直接命中一次（兼容手改的书架存档）。 */
    private Path resolveFileLoose(String bookKey) {
        Path direct = resolveFile(bookKey);
        if (direct != null) {
            return direct;
        }
        String want = bookKey == null ? "" : bookKey.trim();
        if (want.isEmpty()) {
            return null;
        }
        for (Path file : listTxtFiles()) {
            if (want.equalsIgnoreCase(displayTitleOf(file))) {
                return file;
            }
        }
        return null;
    }

    /** 目标文件重名时加 {@code _1}/{@code _2}……，绝不覆盖已有文件。 */
    private static Path uniqueTarget(Path dir, String fileName) {
        String name = fileName;
        String stem;
        String ext = ".txt";
        int dot = name.toLowerCase(Locale.ROOT).lastIndexOf(".txt");
        if (dot >= 0) {
            stem = name.substring(0, dot);
        } else {
            stem = name;
        }
        Path target = dir.resolve(stem + ext);
        int suffix = 0;
        while (Files.exists(target)) {
            suffix++;
            target = dir.resolve(stem + "_" + suffix + ext);
            if (suffix > 999) {
                // 极端情况：用时间戳兜底，绝不覆盖
                target = dir.resolve(stem + "_" + System.currentTimeMillis() + ext);
                break;
            }
        }
        return target;
    }

    // ---------------------------------------------------------------- 解析 / 缓存

    /** 读文件 + 解析，命中缓存时零 IO。 */
    private List<Chapter> load(String bookKey) throws IOException {
        Path file = resolveFileLoose(bookKey);
        if (file == null) {
            throw new IOException("本地书不存在：" + bookKey);
        }
        String key = bookKeyOf(file);
        long size;
        long mtime;
        try {
            size = Files.size(file);
            mtime = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            size = -1L;
            mtime = 0L;
        }
        long now = System.currentTimeMillis();
        synchronized (cache) {
            CachedBook hit = cache.get(key);
            if (hit != null
                    && now - hit.fetchedAtMs < CACHE_TTL_MS
                    && hit.fileSize == size
                    && hit.fileMtime == mtime) {
                return hit.chapters;
            }
        }
        byte[] data;
        try {
            data = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new IOException("读取失败 " + file + "：" + reason(e), e);
        }
        String text = decode(data);
        List<Chapter> chapters = parseBook(text, displayTitleOf(file));
        synchronized (cache) {
            cache.put(key, new CachedBook(chapters, now, size, mtime));
        }
        return chapters;
    }

    // ---------------------------------------------------------------- 编码探测

    /**
     * 字节 → 文本。顺序不能乱：
     * <ol>
     *   <li>UTF-8 BOM（{@code EF BB BF}）→ 去掉 BOM 按 UTF-8 解</li>
     *   <li>UTF-16 LE（{@code FF FE}）/ BE（{@code FE FF}）→ 按 UTF-16 解</li>
     *   <li><b>严格</b> UTF-8（遇到非法字节即失败）</li>
     *   <li>上面失败才退 GB18030（兼容 GBK/GB2312，覆盖面比单纯 GBK 大）</li>
     * </ol>
     *
     * <p>为什么必须先试严格 UTF-8：UTF-8 的中文被 GBK 解码通常不报错、只会变乱码；
     * 反过来纯 GBK 文本用 UTF-8 解会抛 {@link CharacterCodingException}。
     * 所以「先严格 UTF-8、失败才退 GBK 系」是唯一能同时正确识别两者的顺序。
     */
    static String decode(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        if (data.length >= 3
                && (data[0] & 0xFF) == 0xEF
                && (data[1] & 0xFF) == 0xBB
                && (data[2] & 0xFF) == 0xBF) {
            return new String(data, 3, data.length - 3, StandardCharsets.UTF_8);
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
            return new String(data, 2, data.length - 2, StandardCharsets.UTF_16LE);
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
            return new String(data, 2, data.length - 2, StandardCharsets.UTF_16BE);
        }
        String utf8 = strictDecode(data, StandardCharsets.UTF_8);
        if (utf8 != null) {
            return utf8;
        }
        try {
            return new String(data, Charset.forName("GB18030"));
        } catch (Throwable t) {
            // 理论到不了这里（JDK 必带 GB18030）；退 ISO-8859-1 保证不出乱码字节崩解析
            return new String(data, StandardCharsets.ISO_8859_1);
        }
    }

    /** 严格解码：非法字节序列返回 null（而不是替换成 U+FFFD）。 */
    private static String strictDecode(byte[] data, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(java.nio.ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 分章

    /** 一章：标题 + 原始正文块。 */
    static final class Chapter {
        final String title;
        String body;

        Chapter(String title, String body) {
            this.title = title == null ? "正文" : title;
            this.body = body == null ? "" : body;
        }
    }

    /**
     * 文本 → 章节。逐行照搬 Rust {@code local.rs} 的 {@code parse_book}：
     *
     * <ul>
     *   <li>遇到标题行：前面已攒的正文非空就封章，否则丢弃（开头连续多行标题只保留最后一条）；</li>
     *   <li>正文行原样追加（保留行尾换行，段落处理时再拆）；</li>
     *   <li>一个标题都没识别到（或切完是空）→ {@link #chunkFallback(String)}；</li>
     *   <li>第一章标题后若紧跟一行就是书名，跳过这行重复书名（用户明确要求，Rust 侧没有）。</li>
     * </ul>
     */
    static List<Chapter> parseBook(String text, String bookTitle) {
        List<Chapter> chapters = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        String title = "正文";
        boolean sawMarker = false;

        for (String line : splitLines(text)) {
            if (isChapterTitle(line)) {
                sawMarker = true;
                if (!body.toString().trim().isEmpty()) {
                    chapters.add(new Chapter(title, body.toString()));
                    body.setLength(0);
                } else {
                    body.setLength(0);
                }
                title = line.trim();
                continue;
            }
            body.append(line).append('\n');
        }
        if (!body.toString().trim().isEmpty()) {
            chapters.add(new Chapter(title, body.toString()));
        }
        if (!sawMarker || chapters.isEmpty()) {
            chapters = chunkFallback(text);
        }
        if (chapters.isEmpty()) {
            chapters.add(new Chapter("正文", text == null ? "" : text));
        }
        dropRepeatedBookName(chapters, bookTitle);
        return chapters;
    }

    /**
     * 第一章正文如果第一行（跳过前导空行）就是书名，去掉它。
     *
     * <p>起因：不少 TXT 的排版是「第一章 xxx\n书名\n正文……」或「书名\n第一章 ……」，
     * 书名混进正文会被 TTS 念一遍。只在<b>第一行完全等于书名</b>时才丢，
     * 不做模糊匹配（宁可多留一行也不误删正文）。
     */
    static void dropRepeatedBookName(List<Chapter> chapters, String bookTitle) {
        if (chapters == null || chapters.isEmpty()) {
            return;
        }
        String name = bookTitle == null ? "" : bookTitle.trim();
        if (name.isEmpty()) {
            return;
        }
        Chapter first = chapters.get(0);
        if (first == null || first.body.isEmpty()) {
            return;
        }
        List<String> lines = splitLines(first.body);
        int i = 0;
        while (i < lines.size() && collapseWs(lines.get(i)).isEmpty()) {
            i++;
        }
        if (i >= lines.size()) {
            return;
        }
        if (!collapseWs(lines.get(i)).equals(name)) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int j = i + 1; j < lines.size(); j++) {
            sb.append(lines.get(j)).append('\n');
        }
        first.body = sb.toString();
    }

    /** 找不到章节标题时按字符数切块，永远不会丢正文。 */
    static List<Chapter> chunkFallback(String text) {
        List<Chapter> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        StringBuilder buf = new StringBuilder();
        int chars = 0;
        for (String line : splitLines(text)) {
            int len = line.codePointCount(0, line.length());
            if (chars > 0 && chars + len > FALLBACK_CHUNK_CHARS) {
                out.add(new Chapter("第 " + (out.size() + 1) + " 节", buf.toString()));
                buf.setLength(0);
                chars = 0;
            }
            buf.append(line).append('\n');
            chars += len;
        }
        if (!buf.toString().trim().isEmpty()) {
            out.add(new Chapter("第 " + (out.size() + 1) + " 节", buf.toString()));
        }
        return out;
    }

    /**
     * 一行是不是章节标题。规则与 Rust {@code local.rs} 的 {@code is_chapter_title} 逐条对齐：
     * <ol>
     *   <li>去掉首尾空白（含 {@code \uFEFF}）后为空、或超过 {@value #MAX_TITLE_CHARS} 字 → 不是；</li>
     *   <li>{@code 序章/楔子/引子/序幕/尾声/后记/终章/番外} 开头 → 是；</li>
     *   <li>{@code chapter} 开头（大小写不敏感，后面跟数字） → 是；</li>
     *   <li>{@code 第} 开头，<b>紧跟</b>的数字（阿拉伯/中文/全角，中间不允许夹空格）1~8 位，
     *       再接 {@code 章/回/节/卷/篇/部} 之一 → 是。</li>
     * </ol>
     * 第 4 条的「数字必须紧跟」是关键：它让「第二天」「第一次」「第三回合」这类正文
     * 不会被误判成章节标题。
     */
    static boolean isChapterTitle(String raw) {
        String line = trimTitleLine(raw);
        if (line.isEmpty()) {
            return false;
        }
        if (line.codePointCount(0, line.length()) > MAX_TITLE_CHARS) {
            return false;
        }
        for (String head : SPECIAL_HEADS) {
            if (line.startsWith(head)) {
                return true;
            }
        }
        if (line.toLowerCase(Locale.ROOT).startsWith("chapter")) {
            return true;
        }
        if (!line.startsWith("第")) {
            return false;
        }
        int i = 1;
        int digits = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (NUMERALS.indexOf(c) >= 0) {
                digits++;
                i++;
            } else {
                break;
            }
        }
        if (digits == 0 || digits > 8) {
            return false;
        }
        if (i >= line.length()) {
            return false;
        }
        String tail = line.substring(i, Math.min(line.length(), i + 2));
        for (String unit : CHAPTER_UNITS) {
            if (tail.startsWith(unit)) {
                return true;
            }
        }
        return false;
    }

    /** 标题行判定用的裁剪：首尾空白 + BOM。 */
    private static String trimTitleLine(String raw) {
        if (raw == null) {
            return "";
        }
        int start = 0;
        int end = raw.length();
        while (start < end && isBlankChar(raw.charAt(start))) {
            start++;
        }
        while (end > start && isBlankChar(raw.charAt(end - 1))) {
            end--;
        }
        return raw.substring(start, end);
    }

    private static boolean isBlankChar(char c) {
        return Character.isWhitespace(c) || c == '\uFEFF';
    }

    // ---------------------------------------------------------------- 正文 → 段落

    /**
     * 一章正文 → 段落列表。判定与 Rust {@code to_paragraphs} 一致：
     * 有效行（去空白后非空）≥ 3 且平均行长 &lt; 30 字 → 视为硬换行排版，
     * 整章合并成一段；否则一行一段。
     *
     * <p>与 Rust 的唯一差异：段落文本折叠多余空白时<b>保留段首缩进语义</b>
     * （段首有空白就压成一个半角空格，而不是像 Rust 那样 {@code trim()} 掉），
     * 其余（丢空行、折叠 {@code \u3000}/{@code \u00A0}、去尾空白）完全一致。
     */
    static List<String> toParagraphs(String body) {
        List<String> raw = new ArrayList<>();
        if (body == null || body.isEmpty()) {
            return raw;
        }
        java.util.List<String> lines = splitLines(body);
        List<String> kept = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            if (collapseWs(line).isEmpty()) {
                continue; // 丢弃空行
            }
            kept.add(line);
        }
        if (kept.isEmpty()) {
            return raw;
        }
        long total = 0L;
        for (String line : kept) {
            total += collapseWs(line).length();
        }
        int avg = (int) (total / kept.size());
        if (kept.size() >= 3 && avg < 30) {
            StringBuilder merged = new StringBuilder();
            for (String line : kept) {
                merged.append(collapseWs(line));
            }
            String one = collapseParagraph(merged.toString());
            if (!one.isEmpty()) {
                raw.add(one);
            }
            return raw;
        }
        for (String line : kept) {
            String text = collapseParagraph(line);
            if (!text.isEmpty()) {
                raw.add(text);
            }
        }
        return raw;
    }

    /** 折叠空白（含 {@code \u3000} / {@code \u00A0}），保留段首一个空格作为缩进语义。 */
    static String collapseParagraph(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        int i = 0;
        boolean leading = false;
        while (i < s.length() && isSpaceChar(s.charAt(i))) {
            leading = true;
            i++;
        }
        StringBuilder sb = new StringBuilder(s.length());
        if (leading) {
            sb.append(' ');
        }
        boolean prevSpace = leading;
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isSpaceChar(c)) {
                if (!prevSpace) {
                    sb.append(' ');
                    prevSpace = true;
                }
            } else {
                sb.append(c);
                prevSpace = false;
            }
        }
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == ' ') {
            end--;
        }
        return sb.substring(0, end);
    }

    /** 折叠空白并去掉首尾（用于长度判断/标题比对）。 */
    static String collapseWs(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        boolean prevSpace = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isSpaceChar(c)) {
                if (!prevSpace) {
                    sb.append(' ');
                    prevSpace = true;
                }
            } else {
                sb.append(c);
                prevSpace = false;
            }
        }
        return sb.toString().trim();
    }

    private static boolean isSpaceChar(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u3000' || c == '\u00A0';
    }

    // ---------------------------------------------------------------- 作者

    /**
     * 从前若干行里找 {@code 作者：xxx} / {@code 作者: xxx}。
     *
     * <p>找不到就返回空串 —— 不猜、不用文件名顶替（用户明确要求「没有就空，不要瞎编」）。
     * 只看前 30 行：作者信息在 txt 里几乎总在开头，扫全文件对几十 MB 的书是浪费。
     */
    static String detectAuthor(Path file) {
        if (file == null) {
            return "";
        }
        try {
            byte[] head = readHead(file, 8192);
            String text = decode(head);
            java.util.List<String> lines = splitLines(text);
            int limit = Math.min(lines.size(), 30);
            for (int i = 0; i < limit; i++) {
                String line = collapseWs(lines.get(i));
                if (line.isEmpty()) {
                    continue;
                }
                String author = authorFromLine(line);
                if (author != null && !author.isEmpty()) {
                    return author;
                }
            }
        } catch (Throwable t) {
            // 读不到就当没有作者
        }
        return "";
    }

    /** 单行解析 {@code 作者：xxx}；不是作者行返回 null。 */
    private static String authorFromLine(String line) {
        String s = line;
        // 允许「书名 作者：xxx」这种同行写法，所以不要求行首
        int idx = s.indexOf("作者");
        if (idx < 0) {
            return null;
        }
        int i = idx + 2;
        while (i < s.length() && isSpaceChar(s.charAt(i))) {
            i++;
        }
        if (i >= s.length()) {
            return null;
        }
        char c = s.charAt(i);
        if (c != '：' && c != ':') {
            return null;
        }
        i++;
        while (i < s.length() && isSpaceChar(s.charAt(i))) {
            i++;
        }
        String author = s.substring(i).trim();
        // 去掉常见的尾巴（「作者：xxx 著」/「作者：xxx 状态：连载」）
        int cut = author.length();
        int space = author.indexOf(' ');
        if (space > 0 && space < cut) {
            cut = space;
        }
        author = author.substring(0, cut).trim();
        if (author.isEmpty()) {
            return null;
        }
        if (author.length() > 40) {
            author = author.substring(0, 40);
        }
        return author;
    }

    /** 只读文件前 {@code maxBytes} 字节（大文件不整个读进内存）。 */
    private static byte[] readHead(Path file, int maxBytes) throws IOException {
        try (java.io.InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[4096];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(4096);
            int total = 0;
            int n;
            while (total < maxBytes && (n = in.read(buf, 0, Math.min(buf.length, maxBytes - total))) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            return out.toByteArray();
        }
    }

    // ---------------------------------------------------------------- 小工具

    /** 按 {@code \r\n} / {@code \r} / {@code \n} 切行（与 Java 8 的 split 行为一致，不带尾空串）。 */
    static java.util.List<String> splitLines(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        int start = 0;
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                out.add(text.substring(start, i));
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
                    i++;
                }
                i++;
                start = i;
                continue;
            }
            i++;
        }
        if (start < n) {
            out.add(text.substring(start));
        }
        return out;
    }

    /**
     * 书名 → 安全文件名主干。与 Rust {@code sanitize_title} 同规则：
     * {@code \ / : * ? " < > |} 换成 {@code _}，控制字符丢弃，最多 80 字，
     * 末尾的点和空格去掉（Windows 不允许）。
     */
    static String sanitizeKey(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int count = 0;
        String trimmed = raw.trim();
        for (int i = 0; i < trimmed.length(); ) {
            int cp = trimmed.codePointAt(i);
            i += Character.charCount(cp);
            if (count >= MAX_KEY_CHARS) {
                break;
            }
            if (cp == '\\' || cp == '/' || cp == ':' || cp == '*' || cp == '?'
                    || cp == '"' || cp == '<' || cp == '>' || cp == '|') {
                out.append('_');
            } else if (Character.isISOControl(cp)) {
                continue;
            } else {
                out.appendCodePoint(cp);
            }
            count++;
        }
        String s = out.toString().trim();
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '.' || s.charAt(end - 1) == ' ')) {
            end--;
        }
        return s.substring(0, end);
    }

    /** 把异常链压成一句话（日志不需要堆栈）。 */
    public static String reason(Throwable t) {
        // public：client.ui / client.gui 两个面板都要把它写进状态行/toast，
        // 跨包调用需要可见性（这是本项目里唯一的跨包异常文案工具）。
        return reasonText(t);
    }

    private static String reasonText(Throwable t) {
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
}
