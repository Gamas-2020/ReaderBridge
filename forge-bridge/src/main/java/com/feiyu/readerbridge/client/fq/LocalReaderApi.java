package com.feiyu.readerbridge.client.fq;

import com.feiyu.readerbridge.api.ReaderBridgeApi;
import com.feiyu.readerbridge.api.ReaderStateListener;
import com.feiyu.readerbridge.api.ReadingState;
import com.feiyu.readerbridge.impl.MainThreadDispatcher;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 把本地 {@link ReaderEngine} 适配成对外 Java API（{@link ReaderBridgeApi}）。
 *
 * <p>为什么要有这一层：HUD、世界文字、第三方模组都通过
 * {@code ReaderBridgeProvider.get().getState()} 取状态。原来这个状态来自
 * WebSocket 对端（外部 App）。现在朗读就在本进程里跑，所以用本适配器把
 * 引擎状态装进同一个入口 —— HUD 与世界文字的绘制代码一行都不用改。
 *
 * <p>线程约定（与 API.md 一致）：{@link #addListener} 注册的回调
 * <b>一律在主线程触发</b>，经 {@link MainThreadDispatcher} 派发。
 */
public final class LocalReaderApi implements ReaderBridgeApi {

    public static final LocalReaderApi INSTANCE = new LocalReaderApi();

    private final ReaderEngine engine = ReaderEngine.get();
    private final List<ReaderStateListener> listeners = new CopyOnWriteArrayList<>();

    /** 上一次构建的快照，字段全等时复用，避免每帧给 GC 制造垃圾。 */
    private volatile Snapshot last;

    private LocalReaderApi() {
        engine.addListener(this::onEngineChanged);
    }

    private void onEngineChanged() {
        if (listeners.isEmpty()) {
            return;
        }
        final ReadingState snapshot = snapshot();
        MainThreadDispatcher.dispatch(() -> {
            for (ReaderStateListener l : listeners) {
                try {
                    l.onReadingStateChanged(snapshot);
                } catch (Throwable ignored) {
                    // 单个监听器异常不能影响其他监听器
                }
            }
        });
    }

    @Override
    public ReadingState getState() {
        return snapshot();
    }

    private ReadingState snapshot() {
        String sentence = engine.sentence();
        // 只在「句子变了」时重建快照：进度/暂停等由 ReadingState 字段一并带出，
        // 但句子是变化最频繁、也是消费者最关心的字段。
        Snapshot prev = last;
        if (prev != null && prev.currentSentence.equals(sentence)
                && prev.active == engine.isActive()
                && prev.paused == engine.isPaused()
                && prev.progress == engine.progress()
                && prev.chapterTitle.equals(engine.chapterTitle())) {
            return prev;
        }
        Snapshot s = new Snapshot(
                engine.isActive(),
                engine.bookTitle(),
                engine.chapterTitle(),
                sentence,
                engine.progress(),
                engine.isPaused(),
                engine.voiceShortName(),
                engine.lastUpdateMs());
        last = s;
        return s;
    }

    @Override
    public void addListener(ReaderStateListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    @Override
    public void removeListener(ReaderStateListener listener) {
        listeners.remove(listener);
    }

    @Override
    public void requestPause() {
        engine.pause();
    }

    @Override
    public void requestResume() {
        engine.play();
    }

    @Override
    public void requestPlay() {
        engine.play();
    }

    @Override
    public void requestStop() {
        engine.stop();
    }

    @Override
    public void requestNext() {
        engine.nextChapter();
    }

    @Override
    public void requestPrev() {
        engine.prevChapter();
    }

    /** 本地模式下恒为「已就绪」：数据与语音都在本进程内。 */
    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public String wsUrl() {
        return "local://reader";
    }

    /** 不可变状态快照。 */
    private static final class Snapshot implements ReadingState {
        private final boolean active;
        private final String bookTitle;
        private final String chapterTitle;
        private final String currentSentence;
        private final double progress;
        private final boolean paused;
        private final String voiceId;
        private final long updateMs;

        Snapshot(boolean active, String bookTitle, String chapterTitle, String currentSentence,
                 double progress, boolean paused, String voiceId, long updateMs) {
            this.active = active;
            this.bookTitle = bookTitle == null ? "" : bookTitle;
            this.chapterTitle = chapterTitle == null ? "" : chapterTitle;
            this.currentSentence = currentSentence == null ? "" : currentSentence;
            this.progress = progress;
            this.paused = paused;
            this.voiceId = voiceId == null ? "" : voiceId;
            this.updateMs = updateMs;
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public String bookTitle() {
            return bookTitle;
        }

        @Override
        public String chapterTitle() {
            return chapterTitle;
        }

        @Override
        public String currentSentence() {
            return currentSentence;
        }

        @Override
        public double progress() {
            return progress;
        }

        @Override
        public boolean isPaused() {
            return paused;
        }

        @Override
        public String ttsVoiceId() {
            return voiceId;
        }

        @Override
        public long lastUpdateEpochMs() {
            return updateMs;
        }

        @Override
        public String toString() {
            return "ReadingState{active=" + active + ", book=" + bookTitle
                    + ", chapter=" + chapterTitle + ", sentence=" + currentSentence + "}";
        }
    }
}
