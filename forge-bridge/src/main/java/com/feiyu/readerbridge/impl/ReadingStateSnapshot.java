package com.feiyu.readerbridge.impl;

import com.feiyu.readerbridge.api.ReadingState;

/**
 * {@link ReadingState} 的不可变实现。
 *
 * <p>所有字段都是 {@code final}，构造时一次性确定，因此可以自由跨线程传递。</p>
 */
public final class ReadingStateSnapshot implements ReadingState {

    /** 「未连接 / 从未收到状态」的默认快照。 */
    public static final ReadingStateSnapshot INACTIVE =
            new ReadingStateSnapshot(false, null, null, null, 0.0D, false, null, 0L);

    private final boolean active;
    private final String bookTitle;
    private final String chapterTitle;
    private final String currentSentence;
    private final double progress;
    private final boolean paused;
    private final String ttsVoiceId;
    private final long lastUpdateEpochMs;

    public ReadingStateSnapshot(boolean active,
                                String bookTitle,
                                String chapterTitle,
                                String currentSentence,
                                double progress,
                                boolean paused,
                                String ttsVoiceId,
                                long lastUpdateEpochMs) {
        this.active = active;
        this.bookTitle = bookTitle;
        this.chapterTitle = chapterTitle;
        this.currentSentence = currentSentence;
        // 进度强制裁剪到 [0,1]，NaN 退化为 0，避免下游用进度做进度条时产生异常绘制。
        this.progress = Double.isNaN(progress) ? 0.0D : Math.max(0.0D, Math.min(1.0D, progress));
        this.paused = paused;
        this.ttsVoiceId = ttsVoiceId;
        this.lastUpdateEpochMs = lastUpdateEpochMs;
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
        return ttsVoiceId;
    }

    @Override
    public long lastUpdateEpochMs() {
        return lastUpdateEpochMs;
    }

    @Override
    public String toString() {
        return "ReadingState{active=" + active
                + ", bookTitle=" + bookTitle
                + ", chapterTitle=" + chapterTitle
                + ", sentence=" + currentSentence
                + ", progress=" + progress
                + ", paused=" + paused
                + ", voiceId=" + ttsVoiceId
                + ", lastUpdateEpochMs=" + lastUpdateEpochMs
                + '}';
    }
}
