package com.feiyu.readerbridge.client.source;

import com.feiyu.readerbridge.client.fq.FqApiClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一个「规则书源」的运行时实例：按 {@link SourceRule} 抓页面 + {@link MiniHtml} 选择器取值。
 *
 * <p>算法逐条对齐 Rust 侧 {@code reader-app/core/src/source/rulejson.rs} 的
 * {@code RuleJsonSource}，包括几个容易写错的地方：
 * <ul>
 *   <li>{@code {bookPath}} 是「去掉 scheme://host 的路径」，供 {@code /amp{bookPath}list.html}
 *       这类书页与目录页分离的站点使用；</li>
 *   <li>相对链接要相对<b>它所在那一页</b>解析（{@code absolutizeFrom}），
 *       按站点根拼会丢掉一层目录；</li>
 *   <li>{@code skipHrefPrefix} 必须对<b>原始</b> href 判断，绝对化之后
 *       {@code /} 前缀分辨不出站内导航；</li>
 *   <li>{@code {keyword}} 做完整 percent-encode，其它非 ASCII（规则里直接写的中文）
 *       逐字节编码但保留 ASCII 结构字符。</li>
 * </ul>
 *
 * <p>线程安全：实例可被多线程并发调用（HttpClient 与 chapterCache 都是线程安全的），
 * 但<b>所有方法都是阻塞 IO</b>，禁止在渲染线程/主线程调用。
 */
public final class RuleSource {

    /** 目录缓存上限，超出后整体清空（与 Rust 侧一致，不做 LRU）。 */
    private static final int CHAPTER_CACHE_LIMIT = 32;
    /** 目录缓存有效期。 */
    private static final long CHAPTER_CACHE_TTL_MS = 600_000L;
    /** 规则里没写 User-Agent 时用的默认值。 */
    private static final String DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    /** 正文容器按行切段时视为「块级」的标签。 */
    private static final List<String> BLOCK_TAGS = Arrays.asList(
            "p", "div", "section", "article", "li", "ul", "ol", "dl", "dd", "dt",
            "h1", "h2", "h3", "h4", "h5", "h6", "tr", "blockquote", "pre", "table", "hr");

    private final SourceRule rule;
    private final HttpClient http;
    private final Map<String, CachedChapters> chapterCache = new ConcurrentHashMap<>();

    public RuleSource(SourceRule rule) {
        if (rule == null) {
            throw new IllegalArgumentException("rule 不能为空");
        }
        this.rule = rule;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(rule.timeoutMs, 10_000)))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public SourceRule rule() {
        return rule;
    }

    public String id() {
        return rule.id;
    }

    public String name() {
        return rule.name;
    }

    public int timeoutMs() {
        return rule.timeoutMs;
    }

    @Override
    public String toString() {
        return "RuleSource{" + rule.id + "}";
    }

    // ---------------------------------------------------------------- 三个只读接口

    /**
     * 按关键词搜索。
     *
     * <p>{@code url} 里的 {@code |} 表示多个模板（多个分类页/镜像），
     * {@code {page}} 按 {@code pages} 展开，结果按定位串合并去重。
     * 单个页面失败不致命：只有全部页面都失败且一条结果都没拿到时才抛 {@link IOException}。
     */
    public List<FqApiClient.BookInfo> search(String keyword) throws IOException {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> urls = expandUrls(rule.search.url, kw, rule.search.pages);
        if (urls.isEmpty()) {
            throw new IOException("search.url 未展开出任何地址");
        }
        // 搜索页地址经常写成站点内相对路径（readnovel 的 "/so/{keyword}" 就是），
        // 必须先按 baseUrl 补成绝对 URL，否则 HttpClient 会抛 "URI with undefined scheme"。
        // 目录/正文分支各自都做了这步（见 chapters/paragraphs），这里补齐搜索分支。
        List<String> expanded = new ArrayList<>(urls.size());
        for (String raw : urls) {
            expanded.add(startsWithHttp(raw) ? raw : absolutize(encodeNonAscii(raw)));
        }
        urls = expanded;

        Map<String, String[]> merged = new LinkedHashMap<>(); // key -> {title, author}
        IOException lastError = null;
        for (String url : urls) {
            MiniHtml.Node doc;
            try {
                doc = fetchHtml(url);
            } catch (IOException e) {
                System.err.println("[readerbridge] 书源 " + rule.id + " 搜索页拉取失败（忽略）：" + url + " -> " + describe(e));
                lastError = e;
                continue;
            }
            for (Item item : extractItems(doc, rule.search, url)) {
                if (!merged.containsKey(item.key)) {
                    merged.put(item.key, new String[]{item.title, item.author});
                }
            }
        }

        if (merged.isEmpty()) {
            if (lastError != null) {
                throw lastError;
            }
            return Collections.emptyList();
        }

        List<String[]> items = new ArrayList<>(merged.size());
        for (Map.Entry<String, String[]> e : merged.entrySet()) {
            items.add(new String[]{e.getValue()[0], e.getKey(), e.getValue()[1]});
        }
        if (rule.search.filterKeyword) {
            items = filterByKeyword(items, kw);
        }

        List<FqApiClient.BookInfo> out = new ArrayList<>(items.size());
        for (String[] it : items) {
            // bookId 用绝对 URL：它同时是目录页/正文页的唯一定位串
            out.add(new FqApiClient.BookInfo(it[1], it[0], it[2], "", rule.id));
        }
        return out;
    }

    /**
     * 取目录。带内存缓存（32 本 / 10 分钟），缓存命中时零网络。
     *
     * @param bookKey 书的定位串（{@link #search} 返回的绝对 URL）
     * @throws IOException 拉取失败或选择器一条都没命中（后者说明规则过期，需要让用户看见）
     */
    public List<FqApiClient.ChapterInfo> chapters(String bookKey) throws IOException {
        List<FqApiClient.ChapterInfo> cached = cachedChapters(bookKey);
        if (cached != null) {
            return cached;
        }

        String rawUrl;
        String tpl = rule.chapters.url.trim();
        if (tpl.contains("{bookPath}")) {
            rawUrl = tpl.replace("{bookPath}", bookPathOf(bookKey));
        } else if (tpl.contains("{bookKey}")) {
            rawUrl = tpl.replace("{bookKey}", bookKey);
        } else {
            rawUrl = tpl;
        }
        String url = startsWithHttp(rawUrl) ? rawUrl : absolutize(encodeNonAscii(rawUrl));

        MiniHtml.Node doc = fetchHtml(url);
        List<Item> items = extractItems(doc, rule.chapters, url);
        if (items.isEmpty()) {
            throw new IOException("目录页未匹配到任何章节（选择器 `" + rule.chapters.item + "`）：" + url);
        }

        List<FqApiClient.ChapterInfo> out = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            out.add(new FqApiClient.ChapterInfo(i, items.get(i).key, items.get(i).title));
        }
        cacheChapters(bookKey, out);
        return out;
    }

    /**
     * 取一章正文，每个元素一段。
     *
     * @param chapterLocator 章节定位串（{@link #chapters} 返回的绝对 URL）
     * @throws IOException 拉取失败、选择器非法或正文为空
     */
    public List<String> paragraphs(String chapterLocator) throws IOException {
        String target = resolveContentUrl(chapterLocator);
        MiniHtml.Node doc = fetchHtml(target);
        List<String> paragraphs = extractParagraphs(doc);
        if (paragraphs.isEmpty()) {
            throw new IOException("正文为空（选择器未命中）：" + target);
        }
        return paragraphs;
    }

    /**
     * 正文 URL 模板处理。
     *
     * <p>Rust 侧直接拿章节定位串当正文地址、{@code content.url} 实际没被使用。
     * 这里保持一致，但额外允许模板带前缀（{@code /content{chapterKey}}）；
     * 模板里既没有 {@code {chapterKey}} 也没有 {@code {bookKey}} 时回落到定位串本身。
     */
    private String resolveContentUrl(String chapterLocator) {
        String locator = chapterLocator == null ? "" : chapterLocator.trim();
        String tpl = rule.content.url == null ? "" : rule.content.url.trim();
        if (tpl.isEmpty() || !(tpl.contains("{chapterKey}") || tpl.contains("{bookKey}"))) {
            return startsWithHttp(locator) ? locator : absolutize(encodeNonAscii(locator));
        }
        String out = tpl.replace("{chapterKey}", locator).replace("{bookKey}", locator);
        return startsWithHttp(out) ? out : absolutize(encodeNonAscii(out));
    }

    // ---------------------------------------------------------------- HTTP

    private HttpRequest.Builder request(String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(prepareUrl(url)))
                .timeout(Duration.ofMillis(rule.timeoutMs))
                .GET();
        boolean hasUa = false;
        for (Map.Entry<String, String> e : rule.headers.entrySet()) {
            String key = e.getKey();
            if (key == null || key.trim().isEmpty()) {
                continue;
            }
            // Host/Content-Length 由 HttpClient 自己管，写死会抛 IllegalArgumentException
            if ("host".equalsIgnoreCase(key) || "content-length".equalsIgnoreCase(key)) {
                continue;
            }
            if ("user-agent".equalsIgnoreCase(key)) {
                hasUa = true;
            }
            builder.header(key, e.getValue());
        }
        if (!hasUa) {
            builder.header("User-Agent", DEFAULT_UA);
        }
        return builder;
    }

    /** 异常压成一句话；{@code getMessage()} 为 null 时退化成类名（网络异常常见）。 */
    private static String describe(Throwable t) {
        String m = t == null ? null : t.getMessage();
        if (m == null || m.trim().isEmpty()) {
            return t == null ? "未知错误" : t.getClass().getSimpleName();
        }
        return m.trim();
    }

    private MiniHtml.Node fetchHtml(String url) throws IOException {
        byte[] body = fetchBytes(url);
        // MiniHtml 自己按 BOM / <meta charset> 猜编码（不少中文盗版站仍是 GBK）
        return MiniHtml.parse(body);
    }

    private byte[] fetchBytes(String url) throws IOException {
        HttpRequest req;
        try {
            req = request(url).build();
        } catch (IllegalArgumentException e) {
            throw new IOException("URL 非法 " + url + "：" + e.getMessage(), e);
        }
        HttpResponse<byte[]> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("请求被中断：" + url, e);
        } catch (IOException e) {
            throw new IOException("请求失败 " + url + "：" + describe(e), e);
        }
        int code = resp.statusCode();
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code + " 于 " + url);
        }
        byte[] body = resp.body();
        if (body == null || body.length == 0) {
            throw new IOException("响应为空：" + url);
        }
        return body;
    }

    // ---------------------------------------------------------------- 抽取

    /** {@code extract_items} 的返回项。 */
    private static final class Item {
        final String title;
        final String key;
        final String author;

        Item(String title, String key, String author) {
            this.title = title;
            this.key = key;
            this.author = author;
        }
    }

    private List<Item> extractItems(MiniHtml.Node doc, SourceRule.ListRule listRule, String pageUrl) {
        List<MiniHtml.Node> elements;
        try {
            elements = MiniHtml.select(doc, listRule.item);
        } catch (RuntimeException e) {
            System.err.println("[readerbridge] 书源 " + rule.id + " 选择器无效 `" + listRule.item + "`：" + e.getMessage());
            return Collections.emptyList();
        }
        List<Item> out = new ArrayList<>(elements.size());
        for (MiniHtml.Node el : elements) {
            String title = extractValue(el, listRule.title);
            if (title == null || title.isEmpty()) {
                continue;
            }
            String rawKey = listRule.key == null ? title : extractValue(el, listRule.key);
            if (rawKey == null || rawKey.isEmpty()) {
                rawKey = title;
            }
            if (!listRule.skipHrefPrefix.isEmpty() && startsWithAny(rawKey, listRule.skipHrefPrefix)) {
                continue; // 基于原始 href 过滤（绝对化之后分辨不出站内导航）
            }
            String key = listRule.key == null ? rawKey : absolutizeFrom(pageUrl, rawKey);
            String author = listRule.authorExpr == null ? "" : extractValue(el, listRule.authorExpr);
            out.add(new Item(title, key, author == null ? "" : author));
        }
        return out;
    }

    /**
     * 取值表达式：{@code text} / {@code html} / {@code attr:name} / 其它任意 CSS。
     * 与 Rust 的 {@code extract_value} 对齐；CSS 分支只取后代（不含元素自身）。
     */
    private static String extractValue(MiniHtml.Node el, String spec) {
        if (spec == null) {
            return null;
        }
        String s = spec.trim();
        if (s.isEmpty() || "text".equals(s)) {
            String text = normalizeWs(el.text());
            return text.isEmpty() ? null : text;
        }
        if ("html".equals(s)) {
            return el.html();
        }
        if (s.startsWith("attr:")) {
            String attr = s.substring(5).trim();
            if (attr.isEmpty() || !el.hasAttr(attr)) {
                return null;
            }
            String v = el.attr(attr).trim();
            return v.isEmpty() ? null : v;
        }
        List<MiniHtml.Node> inner;
        try {
            inner = MiniHtml.select(el, s);
        } catch (RuntimeException e) {
            System.err.println("[readerbridge] 取值选择器无效 `" + s + "`：" + e.getMessage());
            return null;
        }
        for (MiniHtml.Node candidate : inner) {
            if (candidate == el) {
                continue; // MiniHtml.select 也会把自身算进来；Rust 只取后代
            }
            String text = normalizeWs(candidate.text());
            if (!text.isEmpty()) {
                return text;
            }
        }
        return null;
    }

    private List<String> extractParagraphs(MiniHtml.Node doc) {
        List<String> removed = new ArrayList<>();
        for (String sel : rule.content.remove) {
            List<MiniHtml.Node> els;
            try {
                els = MiniHtml.select(doc, sel);
            } catch (RuntimeException e) {
                System.err.println("[readerbridge] 剔除选择器无效 `" + sel + "`：" + e.getMessage());
                continue;
            }
            for (MiniHtml.Node el : els) {
                removed.add(normalizeWs(el.text()));
            }
        }

        List<String> paragraphs = new ArrayList<>();
        if (rule.content.item != null) {
            List<MiniHtml.Node> els;
            try {
                els = MiniHtml.select(doc, rule.content.item);
            } catch (RuntimeException e) {
                System.err.println("[readerbridge] 正文选择器无效 `" + rule.content.item + "`：" + e.getMessage());
                return Collections.emptyList();
            }
            for (MiniHtml.Node el : els) {
                String text = normalizeWs(el.text());
                if (text.isEmpty() || removed.contains(text)) {
                    continue;
                }
                paragraphs.add(text);
            }
        } else if (rule.content.container != null) {
            List<MiniHtml.Node> containers;
            try {
                containers = MiniHtml.select(doc, rule.content.container);
            } catch (RuntimeException e) {
                System.err.println("[readerbridge] 正文容器选择器无效 `" + rule.content.container + "`：" + e.getMessage());
                return Collections.emptyList();
            }
            MiniHtml.Node container = containers.isEmpty() ? null : containers.get(0);
            if (container != null) {
                boolean pushed = false;
                for (MiniHtml.Node p : MiniHtml.select(container, "p")) {
                    String text = normalizeWs(p.text());
                    if (!text.isEmpty()) {
                        paragraphs.add(text);
                        pushed = true;
                    }
                }
                if (!pushed) {
                    // 没有 <p> 就按 <br>/块级标签切行
                    StringBuilder sb = new StringBuilder();
                    collectLines(container, sb);
                    for (String line : sb.toString().split("\n")) {
                        String text = normalizeWs(line);
                        if (!text.isEmpty() && !removed.contains(text)) {
                            paragraphs.add(text);
                        }
                    }
                }
            }
        }
        return paragraphs;
    }

    /** 把元素内部的文本节点按 {@code <br>}/块级标签切行，追加到 {@code sb}（以 {@code \n} 分隔）。 */
    private static void collectLines(MiniHtml.Node node, StringBuilder sb) {
        for (MiniHtml.Node child : node.childNodes()) {
            if (child.isText()) {
                sb.append(child.rawWholeText());
            } else {
                String tag = child.normalName();
                if ("script".equals(tag) || "style".equals(tag) || "noscript".equals(tag)) {
                    continue;
                }
                if ("br".equals(tag)) {
                    sb.append('\n');
                    continue;
                }
                boolean block = BLOCK_TAGS.contains(tag);
                if (block) {
                    sb.append('\n');
                }
                collectLines(child, sb);
                if (block) {
                    sb.append('\n');
                }
            }
        }
    }

    // ---------------------------------------------------------------- 目录缓存

    private static final class CachedChapters {
        final List<FqApiClient.ChapterInfo> chapters;
        final long fetchedAtMs;

        CachedChapters(List<FqApiClient.ChapterInfo> chapters, long fetchedAtMs) {
            this.chapters = chapters;
            this.fetchedAtMs = fetchedAtMs;
        }
    }

    private List<FqApiClient.ChapterInfo> cachedChapters(String bookKey) {
        if (bookKey == null || bookKey.isEmpty()) {
            return null;
        }
        CachedChapters entry = chapterCache.get(bookKey);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() - entry.fetchedAtMs >= CHAPTER_CACHE_TTL_MS) {
            chapterCache.remove(bookKey);
            return null;
        }
        return entry.chapters;
    }

    private void cacheChapters(String bookKey, List<FqApiClient.ChapterInfo> chapters) {
        if (bookKey == null || bookKey.isEmpty()) {
            return;
        }
        if (chapterCache.size() >= CHAPTER_CACHE_LIMIT) {
            chapterCache.clear();
        }
        chapterCache.put(bookKey, new CachedChapters(Collections.unmodifiableList(chapters), System.currentTimeMillis()));
    }

    /** 丢掉全部目录缓存（配置重载时调用）。 */
    public void clearCache() {
        chapterCache.clear();
    }

    // ---------------------------------------------------------------- URL 工具（对齐 Rust）

    /** 把可能是相对路径的链接补成绝对 URL（按站点根）。 */
    private String absolutize(String href) {
        String h = href == null ? "" : href.trim();
        if (startsWithHttp(h)) {
            return h;
        }
        String base = rule.baseUrl == null ? "" : rule.baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (h.startsWith("//")) {
            int i = base.indexOf("://");
            String scheme = i > 0 ? base.substring(0, i) : "https";
            return scheme + ":" + h;
        }
        if (h.startsWith("/")) {
            int i = base.indexOf("://");
            String scheme = i > 0 ? base.substring(0, i) : "https";
            String origin = i > 0 ? base.substring(i + 3) : base;
            int slash = origin.indexOf('/');
            String host = slash >= 0 ? origin.substring(0, slash) : origin;
            return scheme + "://" + host + h;
        }
        if (base.isEmpty()) {
            return h;
        }
        return base + "/" + h;
    }

    /**
     * 把相对链接按<b>它所在那一页</b>解析成绝对 URL。
     * <p>{@code /ct_rujia/lunyuindex.html} 上的 {@code lunyu/lunyu01.html}
     * 必须拼成 {@code /ct_rujia/lunyu/lunyu01.html}，按站点根拼会丢一层目录。
     */
    private String absolutizeFrom(String page, String href) {
        String h = href == null ? "" : href.trim();
        if (h.isEmpty() || startsWithHttp(h) || h.startsWith("//") || h.startsWith("/")) {
            return absolutize(h);
        }
        if (page == null) {
            return absolutize(h);
        }
        int idx = page.lastIndexOf('/');
        if (idx < 0) {
            return absolutize(h);
        }
        return normalizeDots(page.substring(0, idx + 1) + h);
    }

    /** 展开 {@code url} 模板：{@code |} 分隔多模板，{@code {page}} 按 pages 展开。 */
    private static List<String> expandUrls(String template, String keyword, int pages) {
        List<String> out = new ArrayList<>();
        if (template == null) {
            return out;
        }
        for (String partRaw : template.split("\\|")) {
            String part = partRaw.trim();
            if (part.isEmpty()) {
                continue;
            }
            int pageCount = part.contains("{page}") ? Math.max(1, pages) : 1;
            for (int page = 1; page <= pageCount; page++) {
                String url = part;
                if (url.contains("{bookKey}") || url.contains("{chapterKey}")) {
                    // 这两类模板由调用方直接给出完整 URL，不编码也不绝对化
                    out.add(url);
                    continue;
                }
                url = url.replace("{page}", String.valueOf(page));
                url = url.replace("{keyword}", percentEncode(keyword));
                url = encodeNonAscii(url);
                out.add(url);
            }
        }
        return out;
    }

    /** 取出 URL 的路径部分（去掉 {@code scheme://host}），供 {@code {bookPath}} 使用。 */
    static String bookPathOf(String bookKey) {
        String s = bookKey == null ? "" : bookKey.trim();
        int i = s.indexOf("://");
        if (i < 0) {
            return s;
        }
        String rest = s.substring(i + 3);
        int j = rest.indexOf('/');
        return j < 0 ? "/" : rest.substring(j);
    }

    /** 消掉 URL path 里的 {@code .} 与 {@code ..} 段。 */
    static String normalizeDots(String url) {
        String s = url == null ? "" : url;
        int i = s.indexOf("://");
        if (i < 0) {
            return s;
        }
        String scheme = s.substring(0, i);
        String rest = s.substring(i + 3);
        int j = rest.indexOf('/');
        if (j < 0) {
            return s;
        }
        String host = rest.substring(0, j);
        String path = rest.substring(j);
        List<String> segs = new ArrayList<>();
        for (String seg : path.split("/")) {
            if (seg.isEmpty() || ".".equals(seg)) {
                continue;
            }
            if ("..".equals(seg)) {
                if (!segs.isEmpty()) {
                    segs.remove(segs.size() - 1);
                }
                continue;
            }
            segs.add(seg);
        }
        if (segs.isEmpty()) {
            return scheme + "://" + host + "/";
        }
        return scheme + "://" + host + "/" + String.join("/", segs);
    }

    /** 折叠空白：全角空格、不换行空格、多余空行统一处理（中文正文的缩进会被吃掉）。 */
    static String normalizeWs(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length());
        boolean prevSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean space = Character.isWhitespace(c) || c == '\u3000' || c == '\u00A0';
            if (space) {
                if (!prevSpace && out.length() > 0) {
                    out.append(' ');
                }
                prevSpace = true;
            } else {
                out.append(c);
                prevSpace = false;
            }
        }
        return out.toString().trim();
    }

    /** 把 URL 里的非 ASCII 字符按 UTF-8 逐字节 percent-encode，ASCII 结构字符原样保留。 */
    static String encodeNonAscii(String url) {
        String s = url == null ? "" : url;
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int v = b & 0xFF;
            if (v < 0x80) {
                out.append((char) v);
            } else {
                out.append('%').append(HEX[v >> 4]).append(HEX[v & 0x0F]);
            }
        }
        return out.toString();
    }

    /** 对搜索关键字做完整 percent-encode（保留 unreserved 字符）。 */
    static String percentEncode(String s) {
        String src = s == null ? "" : s;
        StringBuilder out = new StringBuilder(src.length() * 3);
        for (byte b : src.getBytes(StandardCharsets.UTF_8)) {
            int v = b & 0xFF;
            if ((v >= 'A' && v <= 'Z') || (v >= 'a' && v <= 'z') || (v >= '0' && v <= '9')
                    || v == '-' || v == '_' || v == '.' || v == '~') {
                out.append((char) v);
            } else {
                out.append('%').append(HEX[v >> 4]).append(HEX[v & 0x0F]);
            }
        }
        return out.toString();
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private static boolean startsWithHttp(String s) {
        return s != null && (s.startsWith("http://") || s.startsWith("https://"));
    }

    private static boolean startsWithAny(String s, List<String> prefixes) {
        if (s == null || prefixes == null) {
            return false;
        }
        for (String p : prefixes) {
            if (p != null && !p.isEmpty() && s.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** 发请求前把空格等会惹恼 {@link URI#create} 的字符转掉。 */
    private static String prepareUrl(String url) {
        String s = encodeNonAscii(url == null ? "" : url.trim());
        return s.replace(" ", "%20").replace("\n", "").replace("\r", "").replace("\t", "");
    }

    /** 本地按关键字过滤（搜索页不支持服务端搜索时用），大小写不敏感，匹配标题或作者。 */
    private static List<String[]> filterByKeyword(List<String[]> items, String keyword) {
        String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        if (kw.isEmpty()) {
            return items;
        }
        List<String[]> out = new ArrayList<>(items.size());
        for (String[] it : items) {
            String title = it[0] == null ? "" : it[0].toLowerCase(Locale.ROOT);
            String author = it[2] == null ? "" : it[2].toLowerCase(Locale.ROOT);
            if (title.contains(kw) || author.contains(kw)) {
                out.add(it);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 远程规则拉取（给 BookSources 用）

    private static volatile HttpClient sharedHttp;

    private static HttpClient sharedHttp() {
        HttpClient local = sharedHttp;
        if (local == null) {
            synchronized (RuleSource.class) {
                local = sharedHttp;
                if (local == null) {
                    local = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(6))
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .build();
                    sharedHttp = local;
                }
            }
        }
        return local;
    }

    /**
     * 拉取一段文本（用于 {@code extraSources} 里直接写 http(s):// 的远程规则）。
     *
     * @param url       规则地址
     * @param timeoutMs 超时毫秒（会 clamp 到 2s~30s）
     * @return 规则 JSON 文本（UTF-8 解码，去 BOM 由调用方处理）
     * @throws IOException 网络失败或非 2xx
     */
    public static String fetchText(String url, int timeoutMs) throws IOException {
        int timeout = Math.max(2_000, Math.min(30_000, timeoutMs));
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(prepareUrl(url)))
                    .timeout(Duration.ofMillis(timeout))
                    .header("User-Agent", DEFAULT_UA)
                    .GET()
                    .build();
        } catch (IllegalArgumentException e) {
            throw new IOException("规则地址非法 " + url + "：" + e.getMessage(), e);
        }
        HttpResponse<byte[]> resp;
        try {
            resp = sharedHttp().send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("拉取规则被中断：" + url, e);
        } catch (IOException e) {
            // ConnectException 的 message 常常是 null，直接拼会打出「失败 ...：null」
            throw new IOException("拉取规则失败 " + url + "：" + describe(e), e);
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("拉取规则 HTTP " + resp.statusCode() + " 于 " + url);
        }
        byte[] body = resp.body();
        if (body == null || body.length == 0) {
            throw new IOException("规则响应为空：" + url);
        }
        return new String(body, StandardCharsets.UTF_8);
    }
}
