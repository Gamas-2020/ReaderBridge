package com.feiyu.readerbridge.api;

/**
 * 阅读状态的一次不可变快照。
 *
 * <p>线程安全约定：</p>
 * <ul>
 *   <li>实现类必须是<b>不可变</b>（immutable）的：所有字段在构造时确定，之后不再变化。</li>
 *   <li>任意线程都可以安全地读取同一个快照实例，无需额外同步。</li>
 *   <li>{@link ReaderBridgeApi#getState()} 返回的实例可以在线程之间自由传递（例如从主线程
 *       交给异步任务处理）。</li>
 * </ul>
 *
 * <p>字段取值约定：</p>
 * <ul>
 *   <li>文本类字段（书名 / 章节名 / 当前句子 / 音色 id）在数据缺失时返回 {@code null}，
 *       调用方必须判空。</li>
 *   <li>{@link #progress()} 的返回值已经被裁剪到 {@code [0.0, 1.0]}。</li>
 *   <li>{@link #lastUpdateEpochMs()} 为 {@code 0} 表示「从未收到过状态」，其余情况为
 *       状态产生方的毫秒级 Unix 时间戳。</li>
 * </ul>
 */
public interface ReadingState {

    /**
     * 阅读器当前是否处于朗读状态。
     *
     * @return 正在朗读返回 {@code true}
     */
    boolean isActive();

    /**
     * 当前书名。
     *
     * @return 书名，可能为 {@code null}
     */
    String bookTitle();

    /**
     * 当前章节名。
     *
     * @return 章节名，可能为 {@code null}
     */
    String chapterTitle();

    /**
     * 当前正在朗读的句子（对应协议中的 {@code sentence} 字段）。
     *
     * @return 当前句子，可能为 {@code null}
     */
    String currentSentence();

    /**
     * 当前阅读进度，取值区间为 {@code [0.0, 1.0]}。
     *
     * @return 进度比例
     */
    double progress();

    /**
     * 阅读是否处于暂停状态。
     *
     * @return 暂停中返回 {@code true}
     */
    boolean isPaused();

    /**
     * 当前 TTS 音色 id。
     *
     * @return 音色 id，可能为 {@code null}
     */
    String ttsVoiceId();

    /**
     * 该状态最近一次更新的毫秒级 Unix 时间戳。
     *
     * @return 时间戳；{@code 0} 表示从未收到过状态
     */
    long lastUpdateEpochMs();
}
