package com.feiyu.readerbridge.client.audio;

import com.feiyu.readerbridge.ReaderBridgeMod;
import com.feiyu.readerbridge.impl.MainThreadDispatcher;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;

/**
 * 用游戏自带的 OpenAL 播放 App 推送的 WAV 音频（当前是"壳"）。
 *
 * <p>触发条件：{@code tts.audio} 事件的 payload 里存在 {@code wavPath} 字段
 * （App 侧正在增加 PCM WAV 输出；Edge TTS 原生只给 mp3）。没有该字段时本类完全不做事，
 * 因此不会影响现有功能。</p>
 *
 * <p>播放放在主线程：OpenAL 上下文由 Minecraft 在客户端初始化，主线程调用最安全。
 * 任何失败（文件不存在、格式不支持、无音频设备）只记录一次 debug，绝不抛出。</p>
 */
public final class TtsAudioPlayer {

    public static final TtsAudioPlayer INSTANCE = new TtsAudioPlayer();

    /** 单个 WAV 文件大小上限，避免误读超大文件把内存吃满。 */
    private static final int MAX_FILE_BYTES = 20 * 1024 * 1024;

    private final AtomicBoolean warned = new AtomicBoolean(false);

    private int sourceId = -1;
    private int bufferId = -1;
    private int bufferCapacitySamples = -1;

    private TtsAudioPlayer() {
    }

    /** WS 线程收到 {@code tts.audio} 时调用：解析出 wavPath 后派发到主线程播放。 */
    public void onTtsAudio(JsonObject payload) {
        if (payload == null) {
            return;
        }
        String wavPath = stringOf(payload, "wavPath");
        if (wavPath == null || wavPath.isBlank()) {
            // App 还没提供 WAV：什么也不做（保留原有 mp3 path 字段的语义）。
            return;
        }
        String finalPath = wavPath;
        MainThreadDispatcher.dispatch(() -> playWav(finalPath));
    }

    /** 主线程执行：读取 WAV 并交给 OpenAL。 */
    private void playWav(String wavPath) {
        try {
            Path path = Path.of(wavPath);
            if (!Files.isRegularFile(path)) {
                warnOnce("[readerbridge] tts.audio 指向的 WAV 不存在: " + wavPath);
                return;
            }
            long size = Files.size(path);
            if (size <= 44 || size > MAX_FILE_BYTES) {
                warnOnce("[readerbridge] WAV 大小异常，跳过播放: " + wavPath + " (" + size + " 字节)");
                return;
            }
            WavData wav = parseWav(Files.readAllBytes(path));
            if (wav == null) {
                warnOnce("[readerbridge] 只支持 16-bit PCM 单/双声道 WAV: " + wavPath);
                return;
            }
            if (!uploadAndPlay(wav)) {
                return;
            }
            ReaderBridgeMod.LOGGER.debug("[readerbridge] 播放 TTS 音频 {}（{} 帧，{} Hz，{} 声道）",
                    wavPath, wav.samples.length / wav.channels, wav.sampleRate, wav.channels);
        } catch (Throwable t) {
            warnOnce("[readerbridge] 播放 TTS 音频失败: " + t);
        }
    }

    private boolean uploadAndPlay(WavData wav) {
        try {
            if (sourceId < 0) {
                sourceId = AL10.alGenSources();
                if (AL10.alGetError() != AL10.AL_NO_ERROR) {
                    sourceId = -1;
                    warnOnce("[readerbridge] OpenAL 不可用（alGenSources 失败），TTS 音频将不会播放");
                    return false;
                }
            }
            // 先解绑再重填，避免修改正在播放的 buffer。
            AL10.alSourceStop(sourceId);
            AL10.alSourcei(sourceId, AL10.AL_BUFFER, 0);

            if (bufferId < 0 || wav.samples.length > bufferCapacitySamples) {
                if (bufferId >= 0) {
                    AL10.alDeleteBuffers(bufferId);
                }
                bufferId = AL10.alGenBuffers();
                if (bufferId < 0 || AL10.alGetError() != AL10.AL_NO_ERROR) {
                    bufferId = -1;
                    warnOnce("[readerbridge] OpenAL 生成 buffer 失败，TTS 音频将不会播放");
                    return false;
                }
                bufferCapacitySamples = wav.samples.length;
            }

            ShortBuffer data = BufferUtils.createShortBuffer(wav.samples.length);
            data.put(wav.samples).flip();
            int format = wav.channels == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
            AL10.alBufferData(bufferId, format, data, wav.sampleRate);
            AL10.alSourcei(sourceId, AL10.AL_BUFFER, bufferId);
            AL10.alSourcePlay(sourceId);
            return true;
        } catch (Throwable t) {
            warnOnce("[readerbridge] OpenAL 播放失败: " + t);
            return false;
        }
    }

    /** 释放 OpenAL 资源（客户端关闭时调用；失败忽略）。 */
    public void release() {
        try {
            if (sourceId >= 0) {
                AL10.alSourceStop(sourceId);
                AL10.alDeleteSources(sourceId);
                sourceId = -1;
            }
            if (bufferId >= 0) {
                AL10.alDeleteBuffers(bufferId);
                bufferId = -1;
            }
            bufferCapacitySamples = -1;
        } catch (Throwable ignored) {
            // 上下文已销毁时忽略
        }
    }

    /**
     * 解析 WAV 的 {@code fmt } 与 {@code data} 块。
     *
     * <p>只接受 PCM（audioFormat=1）、16-bit、单声道或双声道；其它格式返回 {@code null}。</p>
     */
    private static WavData parseWav(byte[] bytes) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if (buffer.remaining() < 12
                    || buffer.get(0) != 'R' || buffer.get(1) != 'I' || buffer.get(2) != 'F' || buffer.get(3) != 'F'
                    || buffer.get(8) != 'W' || buffer.get(9) != 'A' || buffer.get(10) != 'V' || buffer.get(11) != 'E') {
                return null;
            }
            int position = 12;
            int channels = 0;
            int sampleRate = 0;
            int bitsPerSample = 0;
            int audioFormat = 0;
            int dataOffset = -1;
            int dataLength = 0;
            while (position + 8 <= buffer.limit()) {
                String chunkId = new String(new byte[] {buffer.get(position), buffer.get(position + 1),
                        buffer.get(position + 2), buffer.get(position + 3)});
                int chunkSize = buffer.getInt(position + 4);
                int bodyOffset = position + 8;
                if (chunkSize < 0 || bodyOffset + chunkSize > buffer.limit()) {
                    break;
                }
                if ("fmt ".equals(chunkId) && chunkSize >= 16) {
                    audioFormat = buffer.getShort(bodyOffset) & 0xFFFF;
                    channels = buffer.getShort(bodyOffset + 2) & 0xFFFF;
                    sampleRate = buffer.getInt(bodyOffset + 4);
                    bitsPerSample = buffer.getShort(bodyOffset + 14) & 0xFFFF;
                } else if ("data".equals(chunkId)) {
                    dataOffset = bodyOffset;
                    dataLength = chunkSize;
                }
                // 块长度按偶数对齐
                position = bodyOffset + chunkSize + (chunkSize % 2);
            }
            if (audioFormat != 1 || bitsPerSample != 16 || (channels != 1 && channels != 2)
                    || sampleRate <= 0 || dataOffset < 0 || dataLength <= 1) {
                return null;
            }
            int sampleCount = Math.min(dataLength, buffer.limit() - dataOffset) / 2;
            short[] samples = new short[sampleCount];
            for (int i = 0; i < sampleCount; i++) {
                samples[i] = buffer.getShort(dataOffset + i * 2);
            }
            return new WavData(samples, sampleRate, channels);
        } catch (Throwable t) {
            return null;
        }
    }

    private void warnOnce(String message) {
        if (warned.compareAndSet(false, true)) {
            ReaderBridgeMod.LOGGER.warn(message + "（同类提示只输出一次）");
        } else {
            ReaderBridgeMod.LOGGER.debug(message);
        }
    }

    private static String stringOf(JsonObject object, String key) {
        try {
            JsonElement value = object.get(key);
            if (value == null || value.isJsonNull()) {
                return null;
            }
            return value.getAsString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private record WavData(short[] samples, int sampleRate, int channels) {
    }
}
