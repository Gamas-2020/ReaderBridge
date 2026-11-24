package com.feiyu.readerbridge.client.fq;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 书架 + 续听进度（跨启动持久化）。
 *
 * <p>落盘位置 {@code config/readerbridge/library.json}。为什么不用 ForgeConfigSpec：
 * 那是一组标量开关，书架是「数量可变、元素可增删」的列表，塞进 TOML 会变成手工拼字符串。
 *
 * <p>写盘节流到 1.5 秒一次：朗读时每 80ms 就可能推进一句，逐次落盘等于把磁盘 IO
 * 拖进游戏进程的热路径（config 目录在机械盘上时尤其明显）。真正需要「立刻落盘」的
 * 只有关面板和退出游戏，那两处走 {@link #flush()}。
 *
 * <p>线程约定：所有方法可在任意线程调用，内部 synchronized 保护；写盘走单个守护线程，
 * 不阻塞调用方（{@link #flush()} 除外，它同步写）。
 */
public final class LibraryStore {

    /** 一本书在书架上的记录。字段名即 JSON 键名，App 侧可共用同一份 schema。 */
    public static final class Entry {
        public String bookId = "";
        public String title = "";
        public String author = "";
        public String intro = "";
        /** 上次听到第几章；-1 = 只打开过、还没开始读。 */
        public int chapterIndex = -1;
        public String chapterTitle = "";
        /** 章内第几句（0 基）。续听时从这一句开始合成，而不是从章头重读。 */
        public int sentenceIndex = 0;
        public int totalChapters = 0;
        public long updatedAtMs = 0L;
        /**
         * 来源书源 id（{@code "fqapi"} = 什锦铺，其它 = 规则书源）。
         *
         * <p>旧存档（多书源改造之前）没有这个键，Gson 读出来是 null，
         * {@link #toBookInfo()} 统一归一成 {@code "fqapi"}，所以老书架照旧能续听。
         */
        public String sourceId = "";

        public boolean hasProgress() {
            return chapterIndex >= 0;
        }

        public FqApiClient.BookInfo toBookInfo() {
            // 5 参构造器会把 null/空串归一成 fqapi，这里不用再判空
            return new FqApiClient.BookInfo(bookId, title, author, intro, sourceId);
        }

        public Entry copy() {
            Entry e = new Entry();
            e.bookId = bookId;
            e.title = title;
            e.author = author;
            e.intro = intro;
            e.chapterIndex = chapterIndex;
            e.chapterTitle = chapterTitle;
            e.sentenceIndex = sentenceIndex;
            e.totalChapters = totalChapters;
            e.updatedAtMs = updatedAtMs;
            e.sourceId = sourceId;
            return e;
        }
    }

    private static final LibraryStore INSTANCE = new LibraryStore();
    private static final Type LIST_TYPE = new TypeToken<List<Entry>>() {
    }.getType();
    private static final long FLUSH_PERIOD_MS = 1500L;

    public static LibraryStore get() {
        return INSTANCE;
    }

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final List<Entry> entries = new ArrayList<>();
    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "rb-library");
        t.setDaemon(true);
        return t;
    });

    private Path file;
    private volatile boolean dirty;
    private boolean started;

    private LibraryStore() {
    }

    /** 读盘。configDir 由 {@code ReaderEngine.prewarm} 传入（{@code config/readerbridge}）。 */
    public synchronized void load(Path configDir) {
        if (configDir == null) {
            return;
        }
        this.file = configDir.resolve("library.json");
        entries.clear();
        if (Files.isRegularFile(file)) {
            try {
                String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                List<Entry> parsed = gson.fromJson(text, LIST_TYPE);
                if (parsed != null) {
                    for (Entry e : parsed) {
                        if (e != null && e.bookId != null && !e.bookId.isEmpty()) {
                            entries.add(e);
                        }
                    }
                }
            } catch (Throwable t) {
                // 存档损坏不该让游戏起不来：当成空书架，下次写盘会覆盖掉坏文件。
                System.err.println("[readerbridge] 书架存档读取失败，按空书架处理：" + t);
            }
        }
        sortLocked();
        dirty = false;
        startWriterLocked();
    }

    /** 书架全部条目，最近读的在前。返回副本，调用方可随意持有。 */
    public synchronized List<Entry> entries() {
        List<Entry> out = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            out.add(e.copy());
        }
        return Collections.unmodifiableList(out);
    }

    /** 最近在读的一本；没有则 null。 */
    public synchronized Entry latest() {
        return entries.isEmpty() ? null : entries.get(0).copy();
    }

    public synchronized Entry find(String bookId) {
        if (bookId == null || bookId.isEmpty()) {
            return null;
        }
        for (Entry e : entries) {
            if (bookId.equals(e.bookId)) {
                return e.copy();
            }
        }
        return null;
    }

    /** 记录「打开过这本书」。chapterIndex 传 -1 表示只看了目录。 */
    public synchronized void touch(FqApiClient.BookInfo book, int chapterIndex,
                                   String chapterTitle, int sentenceIndex, int totalChapters) {
        if (book == null || book.bookId == null || book.bookId.isEmpty()) {
            return;
        }
        Entry e = null;
        for (Entry it : entries) {
            if (book.bookId.equals(it.bookId)) {
                e = it;
                break;
            }
        }
        if (e == null) {
            e = new Entry();
            e.bookId = book.bookId;
            entries.add(e);
        }
        e.title = book.name == null ? "" : book.name;
        e.author = book.author == null ? "" : book.author;
        e.intro = book.intro == null ? "" : book.intro;
        // 记住来源：续听时要靠它把目录/正文请求发回正确的书源
        e.sourceId = book.sourceId == null ? "" : book.sourceId;
        if (chapterIndex >= 0) {
            // 换到别的章时句号归零；同一章内只推句号。
            if (chapterIndex != e.chapterIndex) {
                e.chapterIndex = chapterIndex;
                e.chapterTitle = chapterTitle == null ? "" : chapterTitle;
                e.sentenceIndex = Math.max(0, sentenceIndex);
            } else {
                if (chapterTitle != null && !chapterTitle.isEmpty()) {
                    e.chapterTitle = chapterTitle;
                }
                e.sentenceIndex = Math.max(0, sentenceIndex);
            }
        }
        if (totalChapters > 0) {
            e.totalChapters = totalChapters;
        }
        e.updatedAtMs = System.currentTimeMillis();
        sortLocked();
        dirty = true;
    }

    /** 只更新进度，不动书目信息（tick 每句都会调，必须便宜）。 */
    public synchronized void touchProgress(String bookId, int chapterIndex, int sentenceIndex) {
        if (bookId == null || bookId.isEmpty() || chapterIndex < 0) {
            return;
        }
        for (Entry e : entries) {
            if (bookId.equals(e.bookId)) {
                if (e.chapterIndex != chapterIndex) {
                    e.chapterIndex = chapterIndex;
                    e.sentenceIndex = Math.max(0, sentenceIndex);
                } else if (sentenceIndex > e.sentenceIndex) {
                    e.sentenceIndex = sentenceIndex;
                } else {
                    return; // 没变化就不标脏，省掉无意义的写盘
                }
                e.updatedAtMs = System.currentTimeMillis();
                dirty = true;
                return;
            }
        }
    }

    public synchronized boolean remove(String bookId) {
        if (bookId == null || bookId.isEmpty()) {
            return false;
        }
        for (int i = 0; i < entries.size(); i++) {
            if (bookId.equals(entries.get(i).bookId)) {
                entries.remove(i);
                dirty = true;
                return true;
            }
        }
        return false;
    }

    /** 同步写盘。关面板、退出游戏时调。 */
    public synchronized void flush() {
        if (!dirty || file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            String json = gson.toJson(entries, LIST_TYPE);
            Files.write(file, json.getBytes(StandardCharsets.UTF_8));
            dirty = false;
        } catch (IOException e) {
            System.err.println("[readerbridge] 书架存档写入失败：" + e);
        }
    }

    private void startWriterLocked() {
        if (started) {
            return;
        }
        started = true;
        writer.scheduleWithFixedDelay(() -> {
            synchronized (LibraryStore.this) {
                flush();
            }
        }, FLUSH_PERIOD_MS, FLUSH_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    private void sortLocked() {
        // 最近读的在前；同一毫秒按书名兜底，保证顺序稳定（否则列表会自己跳）
        entries.sort(Comparator
                .comparingLong((Entry e) -> -e.updatedAtMs)
                .thenComparing(e -> e.title == null ? "" : e.title));
    }
}
