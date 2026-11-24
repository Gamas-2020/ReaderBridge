package com.feiyu.readerbridge.client.audio;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

/**
 * 纯 Java 的 mp3 → 16-bit PCM 解码器（JLayer）。
 *
 * <p>JDK 17 只带 WAV/AIFF/AU 的 SPI，没有 mp3 解码器；Edge TTS 只能给 mp3，
 * 所以这里用 JLayer（{@code javazoom.jl.decoder}，约 140KB 单 jar，无传递依赖）把
 * mp3 解成 OpenAL 能直接吃的交错 16-bit 小端样本。</p>
 *
 * <p><b>本类刻意不依赖 OpenAL，也不依赖 Minecraft</b>：它只做字节进、PCM 出，
 * 因此可以脱离游戏用裸 {@code java -cp} 跑单测（见 {@link AudioDecodeProbe}）。</p>
 *
 * <p>线程约定：解码是纯 CPU 计算（24kHz 单声道约 0.5~1.5 秒音频/10ms 的解码速度），
 * 调用方应放在工作线程，不要占渲染线程。</p>
 */
public final class Mp3Decoder {

    /**
     * 单次解码的样本数硬上限，防止畸形/超长 mp3 把堆吃穿。
     *
     * <p>48kHz 立体声下约 4.1 分钟；本模组的块是 24kHz 单声道，约 8.3 分钟，
     * 远大于一个朗读块（约 1000 字 ≈ 3~4 分钟）的实际长度。</p>
     */
    public static final int MAX_SAMPLES = 24_000_000;

    private Mp3Decoder() {
    }

    /** 解码结果：交错（L,R,L,R…）16-bit 样本 + 采样率 + 声道数。 */
    public static final class Pcm {

        /** 交错样本；mono 时就是逐帧样本。 */
        public final short[] samples;
        public final int sampleRate;
        public final int channels;

        public Pcm(short[] samples, int sampleRate, int channels) {
            this.samples = samples;
            this.sampleRate = sampleRate;
            this.channels = channels;
        }

        /** 采样帧数（每帧含 {@link #channels} 个样本）。 */
        public int frames() {
            return channels <= 0 ? 0 : samples.length / channels;
        }

        /** 时长（秒）；采样率未知时为 0。 */
        public double durationSeconds() {
            return sampleRate <= 0 ? 0d : (double) frames() / (double) sampleRate;
        }

        /** 第一个非零样本的下标；全零返回 -1（用于验证解码没吐出静音）。 */
        public int firstNonZeroIndex() {
            for (int i = 0; i < samples.length; i++) {
                if (samples[i] != 0) {
                    return i;
                }
            }
            return -1;
        }

        /** 峰值绝对值（0~32768），用于判断解码出来的确实是声音而不是噪声/静音。 */
        public int peakAbs() {
            int peak = 0;
            for (short s : samples) {
                int v = s < 0 ? -s : s;
                if (v > peak) {
                    peak = v;
                }
            }
            return peak;
        }
    }

    /** 从文件解码。 */
    public static Pcm decode(Path file) throws IOException {
        if (file == null) {
            throw new IOException("mp3 路径为空");
        }
        return decode(Files.readAllBytes(file));
    }

    /**
     * 从字节数组解码。
     *
     * @throws IOException 数据不是可解的 mp3（没有音频帧）
     */
    public static Pcm decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 4) {
            throw new IOException("mp3 数据为空");
        }
        ShortSink out = new ShortSink(Math.min(Math.max(bytes.length * 8, 4096), MAX_SAMPLES));
        int sampleRate = 0;
        int channels = 0;
        int decodedFrames = 0;

        Bitstream bitstream = new Bitstream(new ByteArrayInputStream(bytes));
        Decoder decoder = new Decoder();
        try {
            while (true) {
                Header header;
                try {
                    header = bitstream.readFrame();
                } catch (Throwable eof) {
                    // JLayer 在截断文件/垃圾数据上会抛 BitstreamException；按流结束处理。
                    break;
                }
                if (header == null) {
                    break;
                }
                SampleBuffer buffer = null;
                try {
                    buffer = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                } catch (Throwable badFrame) {
                    // 单帧损坏不致命：丢掉这一帧继续。
                    buffer = null;
                }
                if (buffer != null) {
                    if (sampleRate == 0) {
                        sampleRate = buffer.getSampleFrequency();
                        channels = buffer.getChannelCount();
                    }
                    short[] chunk = buffer.getBuffer();
                    int length = buffer.getBufferLength();
                    if (chunk != null && length > 0) {
                        out.add(chunk, length);
                    }
                }
                try {
                    bitstream.closeFrame();
                } catch (Throwable ignored) {
                    // closeFrame 的异常只意味着当前帧尾部不完整，无碍已解出的数据。
                }
                decodedFrames++;
                if (out.size() >= MAX_SAMPLES) {
                    break;
                }
            }
        } finally {
            try {
                bitstream.close();
            } catch (Throwable ignored) {
                // 关闭失败无副作用
            }
        }

        if (out.size() == 0) {
            throw new IOException("mp3 没有可解码的音频帧（已扫描 " + decodedFrames + " 帧）");
        }
        if (sampleRate <= 0) {
            sampleRate = 24000;
        }
        if (channels <= 0) {
            channels = 1;
        }
        return new Pcm(out.toArray(), sampleRate, channels);
    }

    /** 可增长 short 缓冲（避免 ArrayList&lt;short[]&gt; 的装箱与二次拷贝开销）。 */
    private static final class ShortSink {

        private short[] data;
        private int size;

        ShortSink(int initialCapacity) {
            this.data = new short[Math.max(1024, initialCapacity)];
        }

        void add(short[] src, int length) {
            int count = Math.min(length, src.length);
            if (count <= 0) {
                return;
            }
            ensure(size + count);
            System.arraycopy(src, 0, data, size, count);
            size += count;
        }

        int size() {
            return size;
        }

        short[] toArray() {
            short[] exact = new short[size];
            System.arraycopy(data, 0, exact, 0, size);
            return exact;
        }

        private void ensure(int needed) {
            if (needed <= data.length) {
                return;
            }
            int capacity = data.length;
            while (capacity < needed) {
                capacity = capacity + (capacity >> 1) + 16;
                if (capacity > MAX_SAMPLES) {
                    capacity = MAX_SAMPLES;
                    break;
                }
            }
            short[] bigger = new short[capacity];
            System.arraycopy(data, 0, bigger, 0, size);
            data = bigger;
        }
    }
}
