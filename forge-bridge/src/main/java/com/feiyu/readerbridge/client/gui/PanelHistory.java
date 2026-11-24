package com.feiyu.readerbridge.client.gui;

import com.google.gson.JsonParseException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 搜索历史：最近 10 条，落 {@code config/readerbridge/panel_history.json}。
 *
 * <p>复刻成熟阅读器的「最近搜索」：去重、最近的在前、可单条删、可清空。
 * 只记关键词，不记结果（结果集可能很大且随时会变）。
 *
 * <p>线程约定：{@link #keywords()} 返回快照，可任意线程持有；所有写方法只在主线程调用
 * （面板的点击回调里），落盘在 {@code rb-panel-io} 后台线程。
 */
final class PanelHistory {

    /** 最多保留的条数。 */
    static final int MAX = 10;

    private static final String FILE_NAME = "panel_history.json";
    private static final int VERSION = 1;

    /** JSON 里的一条历史。{@code atMs} 只用于将来按时间展示，当前 UI 不显示。 */
    static final class Item {
        String keyword = "";
        long atMs = 0L;
    }

    static final class Data {
        int version = VERSION;
        List<Item> items = new ArrayList<>();
    }

    private final List<Item> items = new ArrayList<>();
    private volatile boolean saveQueued;

    // ---------------------------------------------------------------- 读

    /** 关键词快照，最近的在前。 */
    List<String> keywords() {
        List<Item> snapshot;
        synchronized (items) {
            snapshot = new ArrayList<>(items);
        }
        List<String> out = new ArrayList<>(snapshot.size());
        for (Item item : snapshot) {
            if (item != null && item.keyword != null && !item.keyword.isEmpty()) {
                out.add(item.keyword);
            }
        }
        return Collections.unmodifiableList(out);
    }

    boolean isEmpty() {
        return keywords().isEmpty();
    }

    // ---------------------------------------------------------------- 写

    /** 记一条；同词提到最前，超过 {@link #MAX} 截尾。 */
    void add(String keyword) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) {
            return;
        }
        synchronized (items) {
            for (int i = 0; i < items.size(); i++) {
                if (kw.equals(items.get(i).keyword)) {
                    items.remove(i);
                    break;
                }
            }
            Item item = new Item();
            item.keyword = kw;
            item.atMs = System.currentTimeMillis();
            items.add(0, item);
            while (items.size() > MAX) {
                items.remove(items.size() - 1);
            }
        }
        saveAsync();
    }

    /** 删一条（按词精确匹配）。 */
    void remove(String keyword) {
        if (keyword == null) {
            return;
        }
        boolean changed = false;
        synchronized (items) {
            for (int i = 0; i < items.size(); i++) {
                if (keyword.equals(items.get(i).keyword)) {
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
                System.err.println("[readerbridge] 搜索历史存档损坏，按空历史处理：" + e);
                return;
            }
            if (data == null || data.items == null) {
                return;
            }
            PanelIo.onMain(() -> {
                synchronized (items) {
                    items.clear();
                    for (Item item : data.items) {
                        if (item == null || item.keyword == null || item.keyword.isEmpty()) {
                            continue;
                        }
                        items.add(item);
                        if (items.size() >= MAX) {
                            break;
                        }
                    }
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
