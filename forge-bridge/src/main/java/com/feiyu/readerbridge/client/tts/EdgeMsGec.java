package com.feiyu.readerbridge.client.tts;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Edge「大声朗读」服务的 `Sec-MS-GEC` v2 授权令牌生成器。
 *
 * <p>令牌格式：`&lt;16 位大写 CS64&gt;-&lt;64 位大写 SHA256&gt;`，输入串是
 * `ticks 十进制字符串 + TrustedClientToken`，其中
 * `ticks = ((unixSec + 11644473600) / 300 * 300) * 10^7`（300 秒对齐，窗口内稳定）。
 *
 * <p><b>必须放进 WSS URL 的查询参数</b>（`&amp;Sec-MS-GEC=...`）。只放 HTTP 头会被服务端 403。
 *
 * <p><b>为什么不能用 {@link java.security.MessageDigest} 算后半段</b>：服务端的
 * SHA256 被改造过 —— 初始状态、前 14 个字的消息调度、以及第 15 个轮常量都不是标准值，
 * 且开头 7 个字节被提前折进初始状态、长度字段按 `57 + len` 计。任何标准实现都算不出这个值，
 * 只能照算法逐轮复刻。本文件与 `reader-app/core/src/tts/edge.rs` 里的 `mod gec_v2`
 * 逐行等价（同一份参考向量验证过）。
 *
 * <p>全部运算按 32 位无符号回绕：Java 的 long 乘法/加法溢出天然等于模 2^64，
 * 所以统一用 `long` 承载 u64 中间值，每次累加后 `&amp; 0xFFFFFFFFL` 取低 32 位。
 */
public final class EdgeMsGec {

    /** 微软 Edge 朗读服务的公开客户端令牌（写死在浏览器扩展里，非机密）。 */
    public static final String TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";

    /** 1601-01-01 到 1970-01-01 的秒数（Windows FILETIME 纪元差）。 */
    private static final long WIN_EPOCH_SECS = 11_644_473_600L;

    private static final long MASK = 0xFFFF_FFFFL;

    /** 标准 SHA-256 初始状态，作为改造版最后的加回项。 */
    private static final int[] SHA256_INITIAL = {
            0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A,
            0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19,
    };

    /** 改造版初始状态（常量表）。 */
    private static final int[] SHA256_STATE = {
            0xFD9FBEA2, 0x91243CE7, 0xBE499094, 0xCD2E1E0A,
            0x6C006F49, 0x04BD35AB, 0xFEB0A7E4, 0x03EFFBD2,
    };

    /** 前 14 个消息字的替身常量（真实数据通过 INIT 塞进第 15/16 字）。 */
    private static final int[] SHA256_SCHEDULE = {
            0xEB24BF9E, 0x1558691F, 0xA0C09EE2, 0x0CFA32BA, 0xA622EE8C, 0xD1DD9491, 0xC0DD25A0,
            0xF9E70AB6, 0xF0C31573, 0x7058CE4E, 0x7188E606, 0x00E5AB33, 0xD258B060, 0x72766564,
    };

    /** 标准 SHA-256 轮常量（用于摘要主体）。 */
    private static final int[] SHA256_ROUND_CONSTANTS = {
            0x428A2F98, 0x71374491, 0xB5C0FBCF, 0xE9B5DBA5, 0x3956C25B, 0x59F111F1, 0x923F82A4,
            0xAB1C5ED5, 0xD807AA98, 0x12835B01, 0x243185BE, 0x550C7DC3, 0x72BE5D74, 0x80DEB1FE,
            0x9BDC06A7, 0xC19BF174, 0xE49B69C1, 0xEFBE4786, 0x0FC19DC6, 0x240CA1CC, 0x2DE92C6F,
            0x4A7484AA, 0x5CB0A9DC, 0x76F988DA, 0x983E5152, 0xA831C66D, 0xB00327C8, 0xBF597FC7,
            0xC6E00BF3, 0xD5A79147, 0x06CA6351, 0x14292967, 0x27B70A85, 0x2E1B2138, 0x4D2C6DFC,
            0x53380D13, 0x650A7354, 0x766A0ABB, 0x81C2C92E, 0x92722C85, 0xA2BFE8A1, 0xA81A664B,
            0xC24B8B70, 0xC76C51A3, 0xD192E819, 0xD6990624, 0xF40E3585, 0x106AA070, 0x19A4C116,
            0x1E376C08, 0x2748774C, 0x34B0BCB5, 0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
            0x748F82EE, 0x78A5636F, 0x84C87814, 0x8CC70208, 0x90BEFFFA, 0xA4506CEB, 0xBEF9A3F7,
            0xC67178F2,
    };

    /** 与上表相同，仅下标 14 的常量（0x9BDC06A7）被换成 {@link #SHA256_ADJUSTMENT}。 */
    private static final int[] SHA256_BLOCK_CONSTANTS = {
            0x428A2F98, 0x71374491, 0xB5C0FBCF, 0xE9B5DBA5, 0x3956C25B, 0x59F111F1, 0x923F82A4,
            0xAB1C5ED5, 0xD807AA98, 0x12835B01, 0x243185BE, 0x550C7DC3, 0x72BE5D74, 0x80DEB1FE,
            0xC9DC06A7, 0xC19BF174, 0xE49B69C1, 0xEFBE4786, 0x0FC19DC6, 0x240CA1CC, 0x2DE92C6F,
            0x4A7484AA, 0x5CB0A9DC, 0x76F988DA, 0x983E5152, 0xA831C66D, 0xB00327C8, 0xBF597FC7,
            0xC6E00BF3, 0xD5A79147, 0x06CA6351, 0x14292967, 0x27B70A85, 0x2E1B2138, 0x4D2C6DFC,
            0x53380D13, 0x650A7354, 0x766A0ABB, 0x81C2C92E, 0x92722C85, 0xA2BFE8A1, 0xA81A664B,
            0xC24B8B70, 0xC76C51A3, 0xD192E819, 0xD6990624, 0xF40E3585, 0x106AA070, 0x19A4C116,
            0x1E376C08, 0x2748774C, 0x34B0BCB5, 0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
            0x748F82EE, 0x78A5636F, 0x84C87814, 0x8CC70208, 0x90BEFFFA, 0xA4506CEB, 0xBEF9A3F7,
            0xC67178F2,
    };

    /** CS64 校验和的 14 组系数（乘数, 偏移），顺序不能动。 */
    private static final int[][] CS64_COEFFICIENTS = {
            {0xE82E91B1, 0xB32700D0},
            {0x44DE24C6, 0x88DFF51D},
            {0x3BEB65BC, 0x29514038},
            {0xC89C5933, 0xB328CBDF},
            {0x8D1EC211, 0xD405B4D5},
            {0x314EF5D5, 0x26E55F3C},
            {0xE92491B1, 0x607801C6},
            {0xF0A5B9D5, 0x82B2C1FB},
            {0x06064CF3, 0xFA51A3DA},
            {0xDA79FA60, 0x207CC464},
            {0xBED20560, 0x3C3C74F3},
            {0x89F9C9F8, 0xD2D14B61},
            {0x35FA38C6, 0xBB04A2C1},
            {0xCB49E3C6, 0xC5626167},
    };

    private static final int CS64_FACTOR = 0x82893933;
    private static final int CS64_ADJUSTMENT0 = 0xF2F90B1A;
    private static final int CS64_ADJUSTMENT1 = 0x0B1A0000;
    private static final int SHA256_ADJUSTMENT = 0xC9DC06A7;
    private static final int SHA256_SIGMA0_ADJUSTMENT = 0x059C0B80;
    private static final int SHA256_SIGMA1_ADJUSTMENT = 0x000B92C0;

    private EdgeMsGec() {
    }

    /**
     * 用当前时间生成令牌（正常调用走这里）。
     *
     * @return `<16 位 CS64>-<64 位 SHA256>`，全大写十六进制
     */
    public static String generateNow() {
        return generate(System.currentTimeMillis() / 1000.0);
    }

    /**
     * 用指定 unix 秒生成令牌（便于单测与复现）。
     *
     * <p>注意 `ticks * 1e7 ≈ 1.34e17`，**超出 double 精确整数范围（2^53）**，
     * 所以必须用 `double` 复刻原实现的舍入，再用 `%.0f` 打印成整数串 ——
     * 任何整数类型在这里都会得到与服务端不一致的 ticks。这与 Rust 参考实现一致。
     *
     * @param unixSeconds unix 时间戳（秒，可带小数）
     * @return 令牌
     */
    public static String generate(double unixSeconds) {
        double ticks = unixSeconds + (double) WIN_EPOCH_SECS;
        ticks -= ticks % 300.0;
        ticks *= 10_000_000.0;
        String input = String.format(Locale.ROOT, "%.0f", ticks) + TRUSTED_CLIENT_TOKEN;
        byte[] data = input.getBytes(StandardCharsets.US_ASCII);
        if (data.length < 7) {
            throw new IllegalStateException("令牌输入串过短: " + data.length);
        }
        byte[] digest = digestBytes(data);
        int[] words = cs64Digest(data, digest);
        byte[] cs64 = new byte[8];
        writeLe32(cs64, 0, words[0]);
        writeLe32(cs64, 4, words[1]);
        return hexUpper(cs64) + "-" + hexUpper(digest);
    }

    // ---- 摘要（改造版 SHA-256）

    /** 对令牌输入串算 32 字节摘要。 */
    private static byte[] digestBytes(byte[] data) {
        // 长度字段按 57 + len 填（前 7 字节已进初始状态），而不是标准 SHA-256 的 len。
        int length = 57 + data.length;
        int bodyLen = data.length - 7;
        int pad = Math.floorMod(55 - length, 64);

        byte[] padded = new byte[bodyLen + 1 + pad + 8];
        System.arraycopy(data, 7, padded, 0, bodyLen);
        int cursor = bodyLen;
        padded[cursor++] = (byte) 0x80;
        cursor += pad;
        long bits = ((long) length) * 8L;
        for (int i = 0; i < 8; i++) {
            padded[cursor + i] = (byte) (bits >>> (56 - 8 * i));
        }

        int[] state = initState(data);
        for (int offset = 0; offset + 64 <= padded.length; offset += 64) {
            int[] words = new int[64];
            for (int i = 0; i < 16; i++) {
                int p = offset + 4 * i;
                words[i] = ((padded[p] & 0xFF) << 24)
                        | ((padded[p + 1] & 0xFF) << 16)
                        | ((padded[p + 2] & 0xFF) << 8)
                        | (padded[p + 3] & 0xFF);
            }
            expandSchedule(words, 16);
            int[] working = compress(state, words, SHA256_ROUND_CONSTANTS, 0);
            for (int i = 0; i < 8; i++) {
                state[i] = state[i] + working[i];
            }
        }

        byte[] out = new byte[32];
        for (int i = 0; i < 8; i++) {
            out[4 * i] = (byte) (state[i] >>> 24);
            out[4 * i + 1] = (byte) (state[i] >>> 16);
            out[4 * i + 2] = (byte) (state[i] >>> 8);
            out[4 * i + 3] = (byte) state[i];
        }
        return out;
    }

    /** 改造过的初始状态：前 7 字节直接进消息字，再用常量表把前 30 轮预跑一遍。 */
    private static int[] initState(byte[] data) {
        int[] words = new int[64];
        // 前 4 字节按大端解成一个字，其中最高字节固定补 0（等价 Python 的 bytes([0, d0, d1, d2])）。
        words[14] = ((data[0] & 0xFF) << 16) | ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
        words[15] = ((data[3] & 0xFF) << 24) | ((data[4] & 0xFF) << 16)
                | ((data[5] & 0xFF) << 8) | (data[6] & 0xFF);

        for (int index = 16; index < 30; index++) {
            long value = SHA256_SCHEDULE[index - 16] & MASK;
            // index-2 恒 >= 14，所以 σ1 项总是存在。
            long s1 = sigma1(words[index - 2]);
            value += index == 16 ? (s1 ^ (SHA256_SIGMA1_ADJUSTMENT & MASK)) : s1;
            if (index >= 21) {
                value += words[index - 7] & MASK;
            }
            if (index >= 29) {
                long s0 = sigma0(words[index - 15]);
                value += index == 29 ? (s0 ^ (SHA256_SIGMA0_ADJUSTMENT & MASK)) : s0;
            }
            words[index] = (int) (value & MASK);
        }

        // 第 31 个字是人工注入的调整项。
        long injected = (sigma1(words[28])
                + (words[23] & MASK)
                + sigma0(words[15])
                + (words[14] & MASK)
                + (SHA256_ADJUSTMENT & MASK)
                - (SHA256_ROUND_CONSTANTS[14] & MASK)) & MASK;
        words[30] = (int) injected;
        expandSchedule(words, 31);

        // 前 14 轮已折进初始状态，压缩从第 14 轮开始，用替换过的常量表。
        int[] working = compress(SHA256_STATE, words, SHA256_BLOCK_CONSTANTS, 14);
        int[] out = new int[8];
        for (int i = 0; i < 8; i++) {
            out[i] = SHA256_INITIAL[i] + working[i];
        }
        return out;
    }

    /** 消息扩展：从 {@code first} 号字开始补齐到 64 个字。 */
    private static void expandSchedule(int[] words, int first) {
        for (int index = first; index < 64; index++) {
            long x = words[index - 2] & MASK;
            long y = words[index - 15] & MASK;
            long s1 = ((x >>> 17) ^ (x << 15) ^ (x >>> 19) ^ (x << 13) ^ (x >>> 10)) & MASK;
            long s0 = ((y >>> 7) ^ (y << 25) ^ (y >>> 18) ^ (y << 14) ^ (y >>> 3)) & MASK;
            long value = (s1 + (words[index - 7] & MASK) + s0 + (words[index - 16] & MASK)) & MASK;
            words[index] = (int) value;
        }
    }

    /**
     * 压缩函数：{@code firstRound} 之前的状态不重算，直接从给定轮次继续。
     *
     * <p>参考实现用 `x * (2^32+1)` 再右移的技巧算 ROTR，这里直接用
     * {@link Integer#rotateRight} —— 两者在低 32 位上严格等价（推导见提交说明）。
     */
    private static int[] compress(int[] state, int[] words, int[] constants, int firstRound) {
        int a = state[0];
        int b = state[1];
        int c = state[2];
        int d = state[3];
        int e = state[4];
        int f = state[5];
        int g = state[6];
        int h = state[7];

        for (int index = firstRound; index < 64; index++) {
            int bigSigma1 = Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11) ^ Integer.rotateRight(e, 25);
            int bigSigma0 = Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13) ^ Integer.rotateRight(a, 22);
            long t1 = (h & MASK)
                    + (bigSigma1 & MASK)
                    + ((g ^ (e & (f ^ g))) & MASK)
                    + (constants[index] & MASK)
                    + (words[index] & MASK);
            long t2 = (bigSigma0 & MASK) + (((a & b) | (c & (a | b))) & MASK);
            int newA = (int) ((t1 + t2) & MASK);
            int newE = (int) (((d & MASK) + t1) & MASK);
            h = g;
            g = f;
            f = e;
            e = newE;
            d = c;
            c = b;
            b = a;
            a = newA;
        }
        return new int[]{a, b, c, d, e, f, g, h};
    }

    /** 调度函数 σ0（= ROTR7 ^ ROTR18 ^ SHR3）。 */
    private static long sigma0(int x) {
        long v = x & MASK;
        return ((v >>> 7) ^ (v << 25) ^ (v >>> 18) ^ (v << 14) ^ (v >>> 3)) & MASK;
    }

    /** 调度函数 σ1（= ROTR17 ^ ROTR19 ^ SHR10）。 */
    private static long sigma1(int x) {
        long v = x & MASK;
        return ((v >>> 17) ^ (v << 15) ^ (v >>> 19) ^ (v << 13) ^ (v >>> 10)) & MASK;
    }

    // ---- CS64 校验和

    /**
     * CS64 校验和：返回两个 32 位字（小端打包后就是令牌前 16 位十六进制）。
     *
     * @param data   令牌输入串
     * @param digest 上一步算出的 32 字节摘要
     * @return `{w0, w1}`
     */
    private static int[] cs64Digest(byte[] data, byte[] digest) {
        int keyA = (le32(digest, 0) | 1);
        int keyB = (le32(digest, 4) | 1);
        long keyAL = keyA & MASK;
        long keyBL = keyB & MASK;
        long[] factors = {
                ((keyA & MASK) * (CS64_FACTOR & MASK)) & MASK,
                ((keyB & MASK) * (CS64_FACTOR & MASK)) & MASK,
        };

        // 前置 1 个 0 字节：与原实现一致（把数据整体错开半个字）。
        byte[] buf = new byte[data.length + 1];
        System.arraycopy(data, 0, buf, 1, data.length);
        long[] dataWords = wordsLe4Even(buf);

        int total = CS64_COEFFICIENTS.length + dataWords.length;
        long[] terms = new long[total];
        long[] offsets = new long[total];
        for (int i = 0; i < CS64_COEFFICIENTS.length; i++) {
            long coefficient = CS64_COEFFICIENTS[i][0] & MASK;
            terms[i] = (coefficient * factors[i % 2]) & MASK;
            offsets[i] = CS64_COEFFICIENTS[i][1] & MASK;
        }

        int slot = CS64_COEFFICIENTS.length;
        for (int index = 0; index < dataWords.length; index++) {
            long word = dataWords[index];
            long key = (index % 2 == 0) ? keyAL : keyBL;
            long term = (word * key) & MASK;
            long rot = Integer.rotateLeft((int) word, 16) & MASK;
            long offset = (rot * (index % 2 == 0 ? 0xEF0569FBL : 0xC31713DBL)) & MASK;
            if (index == 0) {
                // 首字注入两处调整项。
                term = (term + (CS64_ADJUSTMENT0 & MASK) * factors[0]) & MASK;
                offset = (offset + (CS64_ADJUSTMENT1 & MASK)) & MASK;
            }
            terms[slot] = term;
            offsets[slot] = offset;
            slot++;
        }

        long swapAcc = 0L;
        long swapLast = 0L;
        long state = 0L;
        long acc = 0L;
        int index = 0;
        while (index + 1 < terms.length) {
            long x = terms[index];
            long y = terms[index + 1];

            long t = Integer.rotateLeft((int) ((offsets[index] + x) & MASK), 16) & MASK;
            long q = (t * 0x76F50F34L) & MASK;
            swapAcc = (swapAcc + (((Integer.rotateLeft((int) q, 16) & MASK) * 0xC3EFEA97L + q) & MASK)) & MASK;

            long t2 = Integer.rotateLeft((int) ((offsets[index + 1] + y) & MASK), 16) & MASK;
            long q2 = (t2 * 0x1881CE3CL) & MASK;
            swapLast = ((((Integer.rotateLeft((int) q2, 16) & MASK) * 0x35BD1EC9L) + q2)) & MASK;
            swapAcc = (swapAcc + swapLast) & MASK;

            state = ((state * keyAL) + x) & MASK;
            state = ((Integer.rotateLeft((int) state, 16) & MASK) * 0xCF98B111L) & MASK;
            state = ((Integer.rotateLeft((int) state, 16) & MASK) * 0x87085B9FL) & MASK;
            state = ((Integer.rotateLeft((int) state, 16) & MASK) * 0x12CEB96DL) & MASK;
            state = ((Integer.rotateLeft((int) state, 16) & MASK) * 0x257E1D83L) & MASK;
            acc = (acc + state) & MASK;

            long mixed = ((state * keyBL) + y) & MASK;
            mixed = ((Integer.rotateLeft((int) mixed, 16) & MASK) * 0xA27416F5L) & MASK;
            mixed = ((Integer.rotateLeft((int) mixed, 16) & MASK) * 0xD38396FFL) & MASK;
            state = ((Integer.rotateLeft(
                    (int) (((Integer.rotateLeft((int) mixed, 16) & MASK) * 0x7C932B89L) & MASK), 16)
                    & MASK) * 0xBFA49F69L) & MASK;
            acc = (acc + state) & MASK;

            index += 2;
        }

        return new int[]{(int) ((swapLast ^ state) & MASK), (int) ((swapAcc ^ acc) & MASK)};
    }

    /** 把字节切成小端 32 位字，并补到偶数个（后续按两两配对处理）。 */
    private static long[] wordsLe4Even(byte[] data) {
        if (data.length == 0) {
            return new long[0];
        }
        int pad = (4 - data.length % 4) % 4;
        int byteLen = data.length + pad;
        int count = byteLen / 4;
        if (count % 2 == 1) {
            count++;
        }
        byte[] buf = new byte[count * 4];
        System.arraycopy(data, 0, buf, 0, data.length);
        long[] words = new long[count];
        for (int i = 0; i < count; i++) {
            words[i] = (buf[4 * i] & 0xFFL)
                    | ((buf[4 * i + 1] & 0xFFL) << 8)
                    | ((buf[4 * i + 2] & 0xFFL) << 16)
                    | ((buf[4 * i + 3] & 0xFFL) << 24);
        }
        return words;
    }

    // ---- 小工具

    private static int le32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private static void writeLe32(byte[] out, int offset, int value) {
        out[offset] = (byte) value;
        out[offset + 1] = (byte) (value >>> 8);
        out[offset + 2] = (byte) (value >>> 16);
        out[offset + 3] = (byte) (value >>> 24);
    }

    private static String hexUpper(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >>> 4) & 0x0F, 16));
            sb.append(Character.forDigit(b & 0x0F, 16));
        }
        return sb.toString().toUpperCase(Locale.ROOT);
    }

    /**
     * 自检入口：用 4 个参考向量（由 Rust 版逐字节验证过、真实服务端接受）校验实现。
     *
     * <pre>java -cp build\classes\java\main com.feiyu.readerbridge.client.tts.EdgeMsGec</pre>
     *
     * @param args 忽略
     */
    public static void main(String[] args) {
        long[] unix = {1_600_000_000L, 1_700_000_000L, 1_795_357_547L, 1_800_000_000L};
        String[] expected = {
                "1F5E89103EC98A08-E69D008C1241797258F0EE06F56044C11FAEC4B60F95C6AB0DB3C5E79BE2F010",
                "2CD23A2CA0B2595A-F3C846C370E05F9CC83ACC5F995C5F5D87A4D0F14CE8936A0F902E64099E38A2",
                "842B98C090EB1068-6280A99E65EED2D3EAE57772E4D5CC17D5AA0136CF92A35BF9B0BAE6BC843AB0",
                "99E54519B490BE5C-E162DB0977FBDCB9B86DD8148BF1BCDC3C0303176CE39852A42B415935C20654",
        };
        int failed = 0;
        for (int i = 0; i < unix.length; i++) {
            String actual = generate((double) unix[i]);
            boolean ok = expected[i].equals(actual);
            if (!ok) {
                failed++;
            }
            System.out.println((ok ? "OK   " : "FAIL ") + "unix=" + unix[i]);
            System.out.println("  want=" + expected[i]);
            System.out.println("  got =" + actual);
        }
        String now = generateNow();
        System.out.println("now token = " + now);
        System.out.println(failed == 0 ? "SELFTEST PASS" : "SELFTEST FAILED (" + failed + ")");
        if (failed != 0) {
            throw new IllegalStateException("Sec-MS-GEC v2 自检失败");
        }
    }
}
