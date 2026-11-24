package com.feiyu.readerbridge.client.world;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 世界内 3D 文字的锚点状态机：纯逻辑，不依赖任何渲染/GL 调用，可单独测试。
 *
 * <p>算法逐条照抄 MuoniumPlayer 3D 世界歌词（{@code 1.20.1 recode}）的
 * {@code WorldLyricsState}，常量与公式出处均已标注。三段式语义：</p>
 *
 * <ol>
 *   <li><b>spawn</b>（首次出现）：用球坐标把锚点钉在 {@code 眼 + 视线方向 × distance}；
 *       之后锚点就是<b>世界坐标</b>，不再随视角转动而移动；</li>
 *   <li><b>follow</b>（每帧）：把眼位差分 {@code dx/dy/dz} 整段加到锚点上，
 *       于是「锚点 − 眼睛」这个偏移向量精确守恒 —— 玩家走动时文字跟着走，
 *       距离与方向都不变；</li>
 *   <li><b>clampIntoView</b>（每帧）：玩家转头后锚点留在原处、飘出视野，
 *       此时用临界阻尼弹簧<b>只改球坐标的两个角、半径 len 保持不变</b>，
 *       把句子沿视线滑回视野内侧 {@value #STAY_INNER_PULL_DEG}°，于是回屏过程中
 *       字号恒定、不会忽远忽近。</li>
 * </ol>
 *
 * <p><b>与参考实现的一处刻意偏离</b>：参考项目是歌词，每句 spawn 时都会随机散开一个
 * 新位置（同一屏多行错落）。阅读场景要求句子在本章内位置稳定，所以这里加了
 * {@link #isAnchored()} —— 只有首次、传送、越界三种情况才重新 spawn，
 * 句子变化只换文本不换锚点。见 {@code docs/WORLDTEXT.md} D-004。</p>
 *
 * <p>线程约束：只在客户端渲染线程调用。</p>
 */
public final class LyricAnchor {

    /** 1 设计像素 = 0.03 世界格（在 distance = {@link #DISTANCE_NORM} 时）。State.java:67 */
    public static final float PX_TO_WORLD = 0.03F;

    /** 世界缩放公式的距离归一基准。State.java:68 */
    public static final float DISTANCE_NORM = 6.5F;

    /** keep-away 最小距离比（distance × 0.6 内算糊脸，加速拉开）。State.java:62 */
    public static final double KEEP_AWAY_MIN_RATIO = 0.6D;

    /** 回屏弹簧角频率；临界阻尼（ζ=1），约 0.3s 到位。State.java:977 */
    public static final double STAY_SPRING = 12.0D;

    /** spawn 时视线下方下沉角度基准。State.java:64 */
    public static final double SPAWN_PITCH_DROP = 3.0D;

    /** spawn 时下沉角度的随机增量，合计下沉 3~13°。State.java:65 */
    public static final double SPAWN_PITCH_RANDOM = 10.0D;

    /** 回屏时字与视野边缘之间留的角边距。State.java:1230-1231 */
    public static final double STAY_EDGE_MARGIN_DEG = 2.0D;

    /** 回屏目标收进视野内侧的角度，避免字停在画面边缘线上。State.java:1248-1249 */
    public static final double STAY_INNER_PULL_DEG = 4.0D;

    /** 距离回中死区（格）。State.java:1132 */
    public static final double RECENTER_DEAD_ZONE = 0.5D;

    /** 眼位单帧位移超过此值视为传送，不做跟随平移。State.java:1069 */
    public static final double TELEPORT_GUARD = 16.0D;

    /** 锚点越界判定：distance × 4 + 64 格。State.java:800 */
    public static final double ANCHOR_MAX_FACTOR = 4.0D;

    /** 锚点越界判定的固定余量（格）。State.java:800 */
    public static final double ANCHOR_MAX_EXTRA = 64.0D;

    /** spawn 时 pitch 的夹取下界（低头）。State.java:1677 */
    public static final double MIN_PITCH_DEG = -60.0D;

    /** spawn 时 pitch 的夹取上界（抬头）。State.java:1677 */
    public static final double MAX_PITCH_DEG = 30.0D;

    /** 回屏回写 pitch 的硬夹取。State.java:1277 */
    public static final double CLAMP_PITCH_DEG = 89.0D;

    /** 径向长度收敛时间常数。State.java:1119 */
    public static final double GLIDE_TAU = 0.2D;

    /** 距离回中：非临界区时间常数。State.java:1135 */
    public static final double RECENTER_TAU_SLOW = 0.3D;

    /** 距离回中：糊脸临界区时间常数。State.java:1135 */
    public static final double RECENTER_TAU_FAST = 0.12D;

    /** 弹簧积分子步长，低帧率下也稳定。State.java:1268 */
    public static final double SPRING_SUBSTEP = 1.0D / 240.0D;

    /** 单帧 dt 上限，防止掉帧后一步跳飞。State.java:1149 */
    public static final double MAX_DT = 0.1D;

    /** distance 可调区间下界。Settings.java:89 */
    public static final double MIN_DISTANCE = 3.0D;

    /** distance 可调区间上界。Settings.java:89 */
    public static final double MAX_DISTANCE = 48.0D;

    /** distance 默认值。Settings.java:33 */
    public static final double DEFAULT_DISTANCE = 10.5D;

    /**
     * 每帧相机快照，全部单位：格 / 度。
     *
     * <p>刻意只取 yaw/pitch 而不是 look vector —— 参考项目
     * （{@code State.java:1358-1367}）也用同一套角度同时算「锚点位置」与「字面朝向」，
     * 这样两者不会因为 look vector 与角度之间的极小不一致而漂移。</p>
     */
    public static final class View {
        public final double eyeX;
        public final double eyeY;
        public final double eyeZ;
        public final double yawDeg;
        public final double pitchDeg;
        public final double fovDeg;
        public final double aspect;

        public View(double eyeX, double eyeY, double eyeZ, double yawDeg, double pitchDeg, double fovDeg,
                double aspect) {
            this.eyeX = eyeX;
            this.eyeY = eyeY;
            this.eyeZ = eyeZ;
            this.yawDeg = yawDeg;
            this.pitchDeg = pitchDeg;
            this.fovDeg = fovDeg;
            this.aspect = aspect;
        }
    }

    private boolean anchored;
    private String sentence = "";
    private double x;
    private double y;
    private double z;
    private double faceYawDeg;
    private double facePitchDeg;
    private double vYaw;
    private double vPitch;
    private double followEyeX = Double.NaN;
    private double followEyeY = Double.NaN;
    private double followEyeZ = Double.NaN;
    private long frameNanos;

    /** 是否已经有锚点（首次 spawn 之前为 false）。 */
    public boolean isAnchored() {
        return anchored;
    }

    /** 锚点当前绑定的文本，仅用于调试日志。 */
    public String sentence() {
        return sentence;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    /** 字面朝向的 yaw（facePlayer 关闭时才使用）。 */
    public double faceYawDeg() {
        return faceYawDeg;
    }

    /** 字面朝向的 pitch（facePlayer 关闭时才使用）。 */
    public double facePitchDeg() {
        return facePitchDeg;
    }

    /** 锚点到眼睛的当前距离（格）。 */
    public double distanceTo(View view) {
        double rx = x - view.eyeX;
        double ry = y - view.eyeY;
        double rz = z - view.eyeZ;
        return Math.sqrt(rx * rx + ry * ry + rz * rz);
    }

    /** 清空锚点，下次渲染重新 spawn（切书、停止朗读时调用）。 */
    public void reset() {
        anchored = false;
        sentence = "";
        followEyeX = Double.NaN;
        followEyeY = Double.NaN;
        followEyeZ = Double.NaN;
        frameNanos = 0L;
        vYaw = 0.0D;
        vPitch = 0.0D;
    }

    /**
     * 把锚点钉到「眼睛 + 视线方向 × distance」。
     *
     * <p>{@code State.java:1384-1462}。yaw 会按 {@code spreadDeg} 做 ±random 散开，
     * 但散开幅度受三重钳制（视野半宽 − 字半宽 − 2°、以及按默认距离标定的横向帽），
     * 避免距离拉远后字散到玩家左右侧。</p>
     *
     * @param spreadDeg        yaw 随机散开幅度（度）；阅读场景传 0 表示正中
     * @param textWidthDesign  单行最宽的设计像素宽度，用于算散开上限
     */
    public void spawn(View view, String text, double distance, double spreadDeg, float textWidthDesign,
            double worldScale, boolean facePlayer) {
        double d = clampDistance(distance);
        double vHalf = Math.max(1.0D, view.fovDeg) / 2.0D;
        double aspect = view.aspect > 0.0D ? view.aspect : 16.0D / 9.0D;
        double hHalf = Math.toDegrees(Math.atan(Math.tan(Math.toRadians(vHalf)) * aspect));
        double sPx = PX_TO_WORLD * worldScale * (d / DISTANCE_NORM);
        double halfW = Math.toDegrees(Math.atan2(textWidthDesign * sPx / 2.0D, d));
        double lateralCap = Math.toDegrees(Math.asin(
                Math.min(1.0D, DEFAULT_DISTANCE * Math.sin(Math.toRadians(Math.abs(spreadDeg))) / d)));
        double spreadLimit = Math.max(2.0D, Math.min(hHalf - halfW - STAY_EDGE_MARGIN_DEG, lateralCap));

        double roll = ThreadLocalRandom.current().nextDouble() * 2.0D - 1.0D;
        double spread = Math.abs(spreadDeg) <= 1.0E-4D ? 0.0D : roll * Math.min(Math.abs(spreadDeg), spreadLimit);
        double yaw = view.yawDeg + spread;

        double vLimSpawn = Math.max(4.0D, vHalf - STAY_EDGE_MARGIN_DEG);
        double drop = Math.min(SPAWN_PITCH_DROP + ThreadLocalRandom.current().nextDouble() * SPAWN_PITCH_RANDOM,
                vLimSpawn);
        double pitch = clampSpawnPitch(view.pitchDeg - drop);

        double[] dir = yawPitchDirection(yaw, pitch);
        this.x = view.eyeX + dir[0] * d;
        this.y = view.eyeY + dir[1] * d;
        this.z = view.eyeZ + dir[2] * d;
        this.sentence = text;
        this.faceYawDeg = facePlayer ? view.yawDeg : yaw;
        this.facePitchDeg = facePlayer ? view.pitchDeg : pitch;
        this.vYaw = 0.0D;
        this.vPitch = 0.0D;
        this.followEyeX = Double.NaN;
        this.followEyeY = Double.NaN;
        this.followEyeZ = Double.NaN;
        this.anchored = true;
    }

    /** 只换文本，不动锚点（同章内句子推进时调用）。 */
    public void setSentence(String text) {
        this.sentence = text;
    }

    /** 锚点是否还在合理范围内（{@code State.java:996-1015}）。 */
    public boolean isSane(View view, double distance) {
        double max = clampDistance(distance) * ANCHOR_MAX_FACTOR + ANCHOR_MAX_EXTRA;
        return distanceTo(view) <= max;
    }

    /**
     * 位移跟随：把眼位差分整段加到锚点上（{@code State.java:1047-1076}）。
     *
     * @param keepDistance 全 XYZ 跟随（阅读场景恒 true）
     * @param stayOnScreen 仅跟随 Y；{@code keepDistance} 为 true 时被忽略
     */
    public void follow(View view, double distance, boolean keepDistance, boolean stayOnScreen) {
        boolean followAll = keepDistance;
        boolean followY = !followAll && stayOnScreen;

        if (Double.isNaN(followEyeX)) {
            followEyeX = view.eyeX;
            followEyeY = view.eyeY;
            followEyeZ = view.eyeZ;
            return;
        }

        double dx = view.eyeX - followEyeX;
        double dy = view.eyeY - followEyeY;
        double dz = view.eyeZ - followEyeZ;
        followEyeX = view.eyeX;
        followEyeY = view.eyeY;
        followEyeZ = view.eyeZ;

        if (!followAll && !followY) {
            followEyeX = Double.NaN;
            followEyeY = Double.NaN;
            followEyeZ = Double.NaN;
            return;
        }
        if (dx * dx + dy * dy + dz * dz > TELEPORT_GUARD * TELEPORT_GUARD) {
            return;
        }

        double dt = frameDt();
        if (!followAll) {
            this.y += dy;
            return;
        }

        this.x += dx;
        this.y += dy;
        this.z += dz;
        double rx = this.x - view.eyeX;
        double ry = this.y - view.eyeY;
        double rz = this.z - view.eyeZ;
        double len = Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (len < 1.0E-6D) {
            return;
        }
        // 整段平移本身已保证距离不变，glideLen 平时恒等；它只处理离散事件
        // （刚开关跟随时、拖距离滑块、传送后重锚），把径向长度指数收敛回设定值。
        double newLen = glideLen(len, clampDistance(distance), dt);
        if (newLen != len) {
            double k = newLen / len;
            this.x = view.eyeX + rx * k;
            this.y = view.eyeY + ry * k;
            this.z = view.eyeZ + rz * k;
        }
    }

    /**
     * 回屏弹簧：锚点飘出视野时沿视线拉回（{@code State.java:1147-1287}）。
     *
     * <p>只改球坐标的 yaw/pitch，半径 len 不变 → 回屏过程中字号恒定。</p>
     */
    public void clampIntoView(View view, double distance, boolean keepDistance, boolean stayOnScreen,
            float textWidthDesign, int lineCount, double lineHeightDesign, double worldScale) {
        double dt = frameDt();
        frameNanos = System.nanoTime();

        if (!stayOnScreen) {
            if (!keepDistance) {
                vYaw = 0.0D;
                vPitch = 0.0D;
            }
            return;
        }

        double d = clampDistance(distance);
        double vHalf = Math.max(1.0D, view.fovDeg) / 2.0D;
        double aspect = view.aspect > 0.0D ? view.aspect : 16.0D / 9.0D;
        double hHalf = Math.toDegrees(Math.atan(Math.tan(Math.toRadians(vHalf)) * aspect));
        double s = PX_TO_WORLD * worldScale * (d / DISTANCE_NORM);

        double rx = this.x - view.eyeX;
        double ry = this.y - view.eyeY;
        double rz = this.z - view.eyeZ;
        double len = Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (len < 1.0E-6D) {
            return;
        }

        if (!keepDistance) {
            double newLen = recenteredLen(len, d, dt);
            if (newLen != len) {
                double k = newLen / len;
                rx *= k;
                ry *= k;
                rz *= k;
                this.x = view.eyeX + rx;
                this.y = view.eyeY + ry;
                this.z = view.eyeZ + rz;
                len = newLen;
            }
        }

        double halfW = Math.toDegrees(Math.atan2(textWidthDesign * s / 2.0D, len));
        double halfH = Math.toDegrees(Math.atan2(Math.max(1, lineCount) * lineHeightDesign * s / 2.0D, len));
        double hLim = Math.max(0.0D, hHalf - halfW - STAY_EDGE_MARGIN_DEG);
        double vLim = Math.max(0.0D, vHalf - halfH - STAY_EDGE_MARGIN_DEG);

        double curYaw = Math.toDegrees(Math.atan2(-rx, rz));
        double curPitch = Math.toDegrees(Math.asin(Math.max(-1.0D, Math.min(1.0D, -ry / len))));
        double dYaw = wrapDegrees(curYaw - view.yawDeg);
        double dPitch = curPitch - view.pitchDeg;

        double innerH = Math.max(0.0D, hLim - STAY_INNER_PULL_DEG);
        double innerV = Math.max(0.0D, vLim - STAY_INNER_PULL_DEG);
        double tYaw = Math.max(-innerH, Math.min(innerH, dYaw));
        double tPitch = Math.max(-innerV, Math.min(innerV, dPitch));
        double eYaw = dYaw - tYaw;
        double ePitch = dPitch - tPitch;

        if (Math.abs(eYaw) < 1.0E-4D && Math.abs(ePitch) < 1.0E-4D
                && Math.abs(vYaw) < 1.0E-4D && Math.abs(vPitch) < 1.0E-4D) {
            vYaw = 0.0D;
            vPitch = 0.0D;
            return;
        }

        double moveYaw = 0.0D;
        double movePitch = 0.0D;
        double left = dt;
        while (left > 1.0E-6D) {
            double h = Math.min(left, SPRING_SUBSTEP);
            left -= h;
            vYaw += (-STAY_SPRING * STAY_SPRING * (eYaw - moveYaw) - 2.0D * STAY_SPRING * vYaw) * h;
            vPitch += (-STAY_SPRING * STAY_SPRING * (ePitch - movePitch) - 2.0D * STAY_SPRING * vPitch) * h;
            moveYaw += vYaw * h;
            movePitch += vPitch * h;
        }

        double newYaw = curYaw + moveYaw;
        double newPitch = Math.max(-CLAMP_PITCH_DEG, Math.min(CLAMP_PITCH_DEG, curPitch + movePitch));
        double yr = Math.toRadians(newYaw);
        double pr = Math.toRadians(newPitch);
        this.x = view.eyeX - Math.sin(yr) * Math.cos(pr) * len;
        this.y = view.eyeY - Math.sin(pr) * len;
        this.z = view.eyeZ + Math.cos(yr) * Math.cos(pr) * len;
        // 非「永远正对玩家」模式下，字面朝向跟着一起转，免得斜着看像被拍扁。
        this.faceYawDeg += wrapDegrees(newYaw - curYaw);
        this.facePitchDeg = Math.max(-90.0D,
                Math.min(90.0D, this.facePitchDeg + (newPitch - curPitch)));
    }

    private double frameDt() {
        return frameNanos == 0L ? 0.016D
                : Math.max(0.0D, Math.min(MAX_DT, (System.nanoTime() - frameNanos) / 1.0E9D));
    }

    /** distance 夹到可调区间；非有限值回落到默认值。 */
    public static double clampDistance(double distance) {
        if (!Double.isFinite(distance)) {
            return DEFAULT_DISTANCE;
        }
        return Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, distance));
    }

    private static double clampSpawnPitch(double pitch) {
        return Math.max(MIN_PITCH_DEG, Math.min(MAX_PITCH_DEG, pitch));
    }

    /**
     * MC 口径的视线方向：yaw 0 朝 +Z，pitch 向下为正。
     * {@code State.java:1358-1367}
     */
    public static double[] yawPitchDirection(double yawDeg, double pitchDeg) {
        double y = Math.toRadians(yawDeg);
        double p = Math.toRadians(pitchDeg);
        return new double[] {
                -Math.sin(y) * Math.cos(p),
                -Math.sin(p),
                Math.cos(y) * Math.cos(p)
        };
    }

    /** 角度差 wrap 到 -180..180。State.java:1017-1023 */
    public static double wrapDegrees(double delta) {
        double r = delta % 360.0D;
        if (r > 180.0D) {
            r -= 360.0D;
        }
        if (r < -180.0D) {
            r += 360.0D;
        }
        return r;
    }

    /** 径向长度指数收敛到设定距离。State.java:1118-1123 */
    static double glideLen(double len, double target, double dt) {
        if (len <= 1.0E-6D) {
            return len;
        }
        if (Math.abs(len - target) < 1.0E-4D) {
            return target;
        }
        double k = 1.0D - Math.exp(-dt / GLIDE_TAU);
        return len + (target - len) * k;
    }

    /** 距离回中：死区 0.5 格，糊脸时 τ=0.12s。State.java:1131-1138 */
    static double recenteredLen(double len, double target, double dt) {
        if (Math.abs(len - target) <= RECENTER_DEAD_ZONE) {
            return len;
        }
        boolean critical = len < target * KEEP_AWAY_MIN_RATIO;
        double tau = critical ? RECENTER_TAU_FAST : RECENTER_TAU_SLOW;
        double k = 1.0D - Math.exp(-dt / tau);
        return len + (target - len) * k;
    }
}
