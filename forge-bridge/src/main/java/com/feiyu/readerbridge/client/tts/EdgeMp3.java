package com.feiyu.readerbridge.client.tts;

/**
 * 手写 MPEG 音频帧遍历：只算时长与采样率，**不解码**。
 *
 * <p>为什么不用解码库：Edge TTS 只会给我们 `audio-24khz-48kbitrate-mono-mp3`
 * （MPEG-2 Layer III，576 samples/帧 → 24ms/帧），逐帧累加 `样本数 / 采样率`
 * 得到的总时长误差小于 1ms，完全够句子时间轴用；引一个 mp3 解码库只为算时长
 * 属于杀鸡用牛刀，还会破坏"零第三方依赖"的约束。
 *
 * <p>算法与 {@code reader-app/core/src/tts/edge.rs} 里的 {@code mod mp3} 逐行对应：
 * 跳过 ID3v2、按帧头算帧长与样本数、用微秒累加避免逐帧取整误差放大。
 */
final class EdgeMp3 {

    /** MPEG-1 Layer III 比特率表（kbps；索引 0 = free，15 = 非法）。 */
    private static final int[] BITRATE_MPEG1_L3 =
            {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};

    /** MPEG-2 / MPEG-2.5 Layer III 比特率表。 */
    private static final int[] BITRATE_MPEG2_L3 =
            {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160};

    /** MPEG-1 Layer I/II 比特率表（兼容老文件）。 */
    private static final int[] BITRATE_MPEG1_L12 =
            {0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384};

    private EdgeMp3() {
    }

    /** 单帧解析结果。 */
    private static final class FrameInfo {
        final int frameLen;
        final int samples;
        final int sampleRate;

        FrameInfo(int frameLen, int samples, int sampleRate) {
            this.frameLen = frameLen;
            this.samples = samples;
            this.sampleRate = sampleRate;
        }
    }

    /** 探测结果：时长（毫秒）与采样率（Hz）。 */
    static final class Info {
        public final long durationMs;
        public final int sampleRate;

        Info(long durationMs, int sampleRate) {
            this.durationMs = durationMs;
            this.sampleRate = sampleRate;
        }
    }

    /**
     * 粗略判断是不是 mp3：ID3v2 头，或前 4KiB 内出现帧同步字 `FF Ex/Fx`。
     *
     * @param bytes 音频字节
     * @return true 表示按 mp3 处理
     */
    static boolean looksLikeMp3(byte[] bytes) {
        if (bytes.length >= 3 && bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3') {
            return true;
        }
        int limit = Math.min(bytes.length - 1, 4096);
        for (int i = 0; i < limit; i++) {
            if ((bytes[i] & 0xFF) == 0xFF && (bytes[i + 1] & 0xE0) == 0xE0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 遍历全部 MPEG 帧算出总时长。
     *
     * @param bytes mp3 字节
     * @return 时长与采样率
     * @throws RuntimeException 一个合法帧都找不到
     */
    static Info probe(byte[] bytes) {
        int pos = skipId3(bytes);
        long totalMicros = 0L;
        int sampleRate = 0;
        int frames = 0;

        while (pos + 4 <= bytes.length) {
            FrameInfo info = parseFrame(bytes, pos);
            if (info != null) {
                if (frames == 0) {
                    sampleRate = info.sampleRate;
                }
                frames++;
                // 用微秒累加：逐帧直接取整毫秒会让 24ms/帧 的误差累积成几十毫秒。
                totalMicros += (long) info.samples * 1_000_000L / info.sampleRate;
                pos += Math.max(info.frameLen, 4);
            } else {
                pos++; // 不是帧头就对不齐时逐个字节往后找同步字
            }
        }

        if (frames == 0 || sampleRate == 0) {
            throw new RuntimeException("未找到合法的 MPEG 音频帧（mp3 解析失败，共 "
                    + bytes.length + " 字节）");
        }
        return new Info(totalMicros / 1000L, sampleRate);
    }

    /** 跳过 ID3v2 标签：`ID3` + 2 字节版本 + 1 字节 flag + 4 字节 syncsafe 长度。 */
    private static int skipId3(byte[] bytes) {
        if (bytes.length >= 10 && bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3') {
            int size = ((bytes[6] & 0x7F) << 21)
                    | ((bytes[7] & 0x7F) << 14)
                    | ((bytes[8] & 0x7F) << 7)
                    | (bytes[9] & 0x7F);
            return Math.min(10 + size, bytes.length);
        }
        return 0;
    }

    /** 解析从 {@code offset} 开始的 MPEG 帧头；不是合法帧头返回 null。 */
    private static FrameInfo parseFrame(byte[] b, int offset) {
        if (offset + 4 > b.length) {
            return null;
        }
        if ((b[offset] & 0xFF) != 0xFF || (b[offset + 1] & 0xE0) != 0xE0) {
            return null;
        }
        // bits 4-3：版本（0=MPEG2.5, 2=MPEG2, 3=MPEG1, 1=保留）
        int versionBits = (b[offset + 1] >> 3) & 0x03;
        // bits 2-1：层（1=Layer3, 2=Layer2, 3=Layer1, 0=保留）
        int layerBits = (b[offset + 1] >> 1) & 0x03;
        if (versionBits == 1 || layerBits == 0) {
            return null;
        }
        int bitrateIndex = (b[offset + 2] >> 4) & 0x0F;
        int sampleRateIndex = (b[offset + 2] >> 2) & 0x03;
        int padding = (b[offset + 2] >> 1) & 0x01;
        if (bitrateIndex == 0 || bitrateIndex == 15 || sampleRateIndex == 3) {
            return null;
        }

        boolean isMpeg1 = versionBits == 3;
        // 采样率表：MPEG-1 / MPEG-2 / MPEG-2.5 三套
        int[] baseRates = {44_100, 48_000, 32_000};
        int sampleRate = baseRates[sampleRateIndex];
        if (versionBits == 2) {
            sampleRate /= 2;
        } else if (versionBits == 0) {
            sampleRate /= 4;
        }

        int bitrateKbps;
        int samples;
        switch (layerBits) {
            case 3 -> { // Layer I
                bitrateKbps = BITRATE_MPEG1_L12[bitrateIndex];
                samples = 384;
            }
            case 2 -> { // Layer II
                bitrateKbps = isMpeg1 ? BITRATE_MPEG1_L12[bitrateIndex] : BITRATE_MPEG2_L3[bitrateIndex];
                samples = 1152;
            }
            default -> { // Layer III（Edge TTS 实际用的就是它）
                bitrateKbps = isMpeg1 ? BITRATE_MPEG1_L3[bitrateIndex] : BITRATE_MPEG2_L3[bitrateIndex];
                samples = isMpeg1 ? 1152 : 576;
            }
        }
        if (bitrateKbps == 0) {
            return null;
        }

        int bitrateBps = bitrateKbps * 1000;
        int frameLen;
        if (layerBits == 3) {
            // Layer I：槽位 4 字节
            frameLen = (12 * bitrateBps / sampleRate + padding) * 4;
        } else {
            // Layer II / III：samples/8 * bitrate / sample_rate + padding
            frameLen = (samples / 8) * bitrateBps / sampleRate + padding;
        }
        if (frameLen < 4) {
            return null;
        }
        return new FrameInfo(frameLen, samples, sampleRate);
    }
}
