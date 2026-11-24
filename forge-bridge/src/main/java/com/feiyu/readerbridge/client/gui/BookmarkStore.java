package com.feiyu.readerbridge.client.gui;

import com.google.gson.JsonParseException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 书签存储：落 {@code config/readerbridge/bookmarks.json}。
 *
 * <p>一条书签 = 书 + 章 + 句 + 句子预览。之所以连 {@code author/intro} 也存：
 * 面板的跳转要能脱离书架独立成立（书架被清过、或书是别的存档里加的），
 * 拿到 {@code BookInfo} 的三要素就能重建一次 {@code resumeEntry}。
 *
 * <p>去重键是 {@code bookId|chapterIndex|sentenceIndex}：同章同句只留一条，
 * 再点一次「加书签」会把它提到最新（时间戳刷新）。
 *
 * <p>线程约定同 {@link PanelHistory}：读快照可跨线程，写只在主线程，落盘在后台。
 */
final class BookmarkStore {

    private static final String FILE_NAME = "bookmarks.json";
    private static final int VERSION = 1;

    /** JSON 键名即字段名。 */
    static final class Bookmark {
        public String bookId = "";
        public String bookTitle = "";
        public String author = "";
        public String intro = "";
        public int chapterIndex = -1;
        public String chapterTitle = "";
        public int sentenceIndex = 0;
        /** 当前句前 48 字的预览，列表里当副标题用。 */
        public String preview = "";
        public long createdAtMs = 0L;

        /** 去重键（不是持久化字段，Gson 不会写它，因为它是方法而不是字段）。 */
        String key() {
            return bookId + "|" + chapterIndex + "|" + sentenceIndex;
        }

        /** 列表里的主标题：书 · 章。 */
        String headline() {
            String book = bookTitle == null || bookTitle.isEmpty() ? "未知书" : bookTitle;
            String chap = chapterTitle == null || chapterTitle.isEmpty()
                    ? (chapterIndex >= 0 ? ("第 " + (chapterIndex + 1) + " 章") : "未定位章节")
                    : chapterTitle;
            return book + " · " + chap;
        }
    }

    static final class Data {
        int version = VERSION;
        List<Bookmark> items = new ArrayList<>();
    }

    private final List<Bookmark> items = new ArrayList<>();
    private volatile boolean saveQueued;

    // ---------------------------------------------------------------- 读

    /** 全部书签，新建的在前。 */
    List<Bookmark> all() {
        List<Bookmark> snapshot;
        synchronized (items) {
            snapshot = new ArrayList<>(items);
        }
        snapshot.sort(Comparator.comparingLong((Bookmark b) -> b.createdAtMs).reversed());
        return Collections.unmodifiableList(snapshot);
    }

    int count() {
        synchronized (items) {
            return items.size();
        }
    }

    /** 当前这本书的章内是否已有书签（书架/目录画标记用）。 */
    boolean has(String bookId, int chapterIndex, int sentenceIndex) {
        if (bookId == null) {
            return false;
        }
        String key = bookId + "|" + chapterIndex + "|" + sentenceIndex;
        synchronized (items) {
            for (Bookmark b : items) {
                if (key.equals(b.key())) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 写

    /** 添加或刷新一条书签（同键替换）。 */
    void add(Bookmark bookmark) {
        if (bookmark == null || bookmark.bookId == null || bookmark.bookId.isEmpty()) {
            return;
        }
        if (bookmark.createdAtMs <= 0L) {
            bookmark.createdAtMs = System.currentTimeMillis();
        }
        synchronized (items) {
            String key = bookmark.key();
            for (int i = 0; i < items.size(); i++) {
                if (key.equals(items.get(i).key())) {
                    items.remove(i);
                    break;
                }
            }
            items.add(bookmark);
        }
        saveAsync();
    }

    /** 按去重键删除。 */
    void remove(Bookmark bookmark) {
        if (bookmark == null) {
            return;
        }
        boolean changed = false;
        synchronized (items) {
            String key = bookmark.key();
            for (int i = 0; i < items.size(); i++) {
                if (key.equals(items.get(i).key())) {
                    items.remove(i);
                    changed = true;
                    break;
                }
            }
        }
        if (changed) {
            saveAsync();
        }
    }

    void clear() {
        synchronized (items) {
            if (items.isEmpty()) {
                return;
            }
            items.clear();
        }
        saveAsync();
    }

    // ---------------------------------------------------------------- 落盘

    void loadAsync() {
        PanelIo.background(() -> {
            String text = PanelIo.readText(file());
            if (text == null || text.trim().isEmpty()) {
                return;
            }
            Data data;
            try {
                data = PanelIo.GSON.fromJson(text, Data.class);
            } catch (JsonParseException e) {
                System.err.println("[readerbridge] 书签存档损坏，按空书签处理：" + e);
                return;
            }
            if (data == null || data.items == null) {
                return;
            }
            final List<Bookmark> parsed = new ArrayList<>();
            for (Bookmark b : data.items) {
                if (b != null && b.bookId != null && !b.bookId.isEmpty()) {
                    parsed.add(b);
                }
            }
            PanelIo.onMain(() -> {
                synchronized (items) {
                    items.clear();
                    items.addAll(parsed);
                }
            });
        });
    }

    private void saveAsync() {
        if (saveQueued) {
            return;
        }
        saveQueued = true;
        PanelIo.background(() -> {
            saveQueued = false;
            Data data = new Data();
            synchronized (items) {
                data.items = new ArrayList<>(items);
            }
            PanelIo.writeText(file(), PanelIo.GSON.toJson(data));
        });
    }

    private static Path file() {
        return PanelIo.configDir().resolve(FILE_NAME);
    }
}
