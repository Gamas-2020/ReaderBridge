package com.feiyu.readerbridge.client.audio;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * mp3 解码命令行探针：不依赖 OpenAL / Minecraft，裸 {@code java -cp} 就能跑。
 *
 * <pre>
 * javac -encoding UTF-8 -cp libs/jlayer-1.0.1.jar -d build/probe \
 *     src/main/java/com/feiyu/readerbridge/client/audio/Mp3Decoder.java \
 *     src/main/java/com/feiyu/readerbridge/client/audio/AudioDecodeProbe.java
 * java -cp "build/probe;libs/jlayer-1.0.1.jar" \
 *     com.feiyu.readerbridge.client.audio.AudioDecodeProbe E:\rb_build\edgejava.mp3 6048
 * </pre>
 *
 * <p>断言（与任务书一致）：</p>
 * <ul>
 *   <li>解码帧数 / sampleRate ≈ 期望时长（默认 6048ms，容差 ±2%）</li>
 *   <li>sampleRate = 24000、channels = 1</li>
 *   <li>存在非零样本（不是解出一段静音）</li>
 * </ul>
 */
public final class AudioDecodeProbe {

    private AudioDecodeProbe() {
    }

    public static void main(String[] args) {
        String path = args.length > 0 ? args[0] : "E:\\rb_build\\edgejava.mp3";
        long expectedMs = args.length > 1 ? Long.parseLong(args[1]) : 6048L;
        boolean ok = true;
        try {
            Path file = Path.of(path);
            System.out.println("[probe] file        = " + file.toAbsolutePath());
            System.out.println("[probe] fileBytes   = " + Files.size(file));
            System.out.println("[probe] expectedMs  = " + expectedMs);

            long t0 = System.nanoTime();
            Mp3Decoder.Pcm pcm = Mp3Decoder.decode(file);
            long costMs = (System.nanoTime() - t0) / 1_000_000L;

            int frames = pcm.frames();
            double durationMs = (double) frames * 1000d / (double) pcm.sampleRate;
            double deviationPct = expectedMs <= 0 ? 0d : (durationMs - expectedMs) * 100d / expectedMs;
            int firstNonZero = pcm.firstNonZeroIndex();
            int peak = pcm.peakAbs();

            System.out.println("[probe] sampleRate  = " + pcm.sampleRate);
            System.out.println("[probe] channels    = " + pcm.channels);
            System.out.println("[probe] samples     = " + pcm.samples.length);
            System.out.println("[probe] frames      = " + frames);
            System.out.println("[probe] durationMs  = " + String.format("%.3f", durationMs)
                    + "  (deviation " + String.format("%+.2f", deviationPct) + "%)");
            System.out.println("[probe] decodeMs    = " + costMs);
            System.out.println("[probe] firstNonZero= " + firstNonZero);
            System.out.println("[probe] peakAbs     = " + peak);
            StringBuilder head = new StringBuilder();
            for (int i = 0; i < Math.min(8, pcm.samples.length); i++) {
                head.append(i == 0 ? "" : ", ").append(pcm.samples[i]);
            }
            System.out.println("[probe] head8       = [" + head + "]");

            ok &= check("sampleRate==24000", pcm.sampleRate == 24000);
            ok &= check("channels==1", pcm.channels == 1);
            ok &= check("duration within +-2%", Math.abs(deviationPct) <= 2.0d);
            ok &= check("has non-zero audio", firstNonZero >= 0 && peak > 0);
            ok &= check("length sane (frames>0)", frames > 0);
        } catch (Throwable t) {
            ok = false;
            System.out.println("[probe] EXCEPTION   = " + t);
            t.printStackTrace(System.out);
        }
        System.out.println(ok ? "[probe] RESULT = PASS" : "[probe] RESULT = FAIL");
        if (!ok) {
            System.exit(1);
        }
    }

    private static boolean check(String name, boolean condition) {
        System.out.println("[probe] assert " + (condition ? "OK   " : "FAIL ") + name);
        return condition;
    }
}
