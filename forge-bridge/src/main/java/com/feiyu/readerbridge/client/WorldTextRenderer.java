package com.feiyu.readerbridge.client;

import com.feiyu.readerbridge.ReaderBridgeMod;
import com.feiyu.readerbridge.api.ReaderBridgeApi;
import com.feiyu.readerbridge.api.ReaderBridgeProvider;
import com.feiyu.readerbridge.api.ReadingState;
import com.feiyu.readerbridge.client.world.AnchorMode;
import com.feiyu.readerbridge.client.world.LyricAnchor;
import com.feiyu.readerbridge.client.world.SubtitleAnimator;
import com.feiyu.readerbridge.client.world.SubtitleFxMode;
import com.feiyu.readerbridge.client.world.SubtitlePainter;
import com.feiyu.readerbridge.config.ReaderBridgeConfig;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * 世界内 3D 悬浮文字：把当前朗读中的句子画成世界坐标里的一块「永远正对屏幕」的广告牌。
 *
 * <p>实现全部照抄 MuoniumPlayer 的 3D 世界歌词（{@code WorldLyricsBridge} +
 * {@code WorldLyricsState}），锚点逻辑在 {@link LyricAnchor}。要点：</p>
 *
 * <ol>
 *   <li><b>渲染阶段必须是 {@link RenderLevelStageEvent.Stage#AFTER_LEVEL}</b>。
 *       这时原版只剩手和 GUI 没画，半透明目标已合成回主目标，水/玻璃不会盖住文字。
 *       改成 {@code AFTER_TRANSLUCENT_BLOCKS} 反而会被 Fabulous 半透明通道吞掉
 *       （参考实现 {@code WorldLyricsBridge.java:91-97} 换点位就是踩了这个坑）。</li>
 *   <li><b>相机姿态自算</b>{@code rotateX(pitch) → rotateY(yaw + 180)}，
 *       不要用来加载器给的 {@code event.getPoseStack()} —— Forge 侧那份矩阵会让文字
 *       左右镜像（{@code WorldLyricsBridge.java:28-31}）。</li>
 *   <li><b>把 modelview 置单位矩阵</b>，于是后续所有顶点都在「相机空间」，
 *       x 向右、y 向上、z 沿视线，广告牌只需反向抵消 yaw/pitch 就永远面向镜头。</li>
 *   <li><b>GL 状态成对进出</b>，且退出时还原成<b>原版默认值</b>而不是「进入时的值」：
 *       本阶段之后立刻就是原版 GUI 渲染，混合态残留会污染没有 transparency shard 的
 *       RenderType（{@code WorldLyricsBridge.java:204-217}）。</li>
 *   <li><b>1ms 幂等守卫</b>：同一帧内若还有别的 mixin/事件也来画一遍，
 *       加色混合会叠成白团（{@code WorldLyricsBridge.java:110-114}）。</li>
 * </ol>
 *
 * <p>与参考实现的差异：参考项目用 Skija 自绘字形图集 + 三层高斯辉光；这里改用原版
 * {@link Font#drawInBatch} 的硬阴影，观感更「MC 原生」，代价是没有柔边光晕。</p>
 *
 * <p><b>两档预设</b>（都在 Configured 里改，改完下一帧立刻生效）：</p>
 *
 * <ul>
 *   <li>{@code anchorPreset} —— 「文字停在哪」，见 {@link AnchorMode}：<br>
 *       {@code follow}（跟随，默认）/ {@code stay}（原地停留）/ {@code head}（贴在镜头上）。
 *       三档只在「锚点怎么算」这一步分叉，锚点之后的缩放、字面朝向、折行、居中全部共用。</li>
 *   <li>{@code subtitleFx} —— 「换句怎么过渡」，见 {@link SubtitleFxMode}：<br>
 *       {@code crossfade}（交叉淡化，默认）/ {@code typewriter} / {@code typewriterBounce} /
 *       {@code wave} / {@code slideUp} / {@code glow} / {@code none}。
 *       七档共用同一个 {@link SubtitlePainter}、同一个 {@link Matrix4f}、同一次
 *       {@code endBatch()}，只在「每行/每字怎么落笔」上分叉。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = ReaderBridgeMod.MOD_ID, value = Dist.CLIENT)
public final class WorldTextRenderer {

    /** 单行最宽（设计像素）。原版中文字宽 9px，130 ≈ 14 个汉字。 */
    private static final float MAX_LINE_WIDTH = 130.0F;

    /** 最多显示行数，超出用省略号收尾。 */
    private static final int MAX_LINES = 3;

    /** 行高（设计像素）。原版 font 行高 9，按 LYRIC_SIZE/LINE_HEIGHT 折算到 11。 */
    private static final float LINE_H = 11.0F;

    /** 世界缩放系数，乘在 {@link LyricAnchor#PX_TO_WORLD} 上。1.0 = 与参考项目字号等价。 */
    private static final float WORLD_SCALE = 1.0F;

    /** 未暂停时的文字颜色。 */
    private static final int COLOR_ACTIVE = 0xFFFFFFFF;

    /** 暂停时的文字颜色（压暗，不消失）。 */
    private static final int COLOR_PAUSED = 0xFFB4B4B4;

    /** 全亮光照贴图坐标，避免文字在暗处变黑。 */
    private static final int FULL_BRIGHT = 0xF000F0;

    /** 同一帧内重复绘制的判定间隔。 */
    private static final long MIN_FRAME_GAP_NANOS = 1_000_000L;

    /**
     * {@code head} 档的视线下沉角（度）：复用 spawn 的基准下沉量
     * （{@link LyricAnchor#SPAWN_PITCH_DROP}，{@code State.java:64}）。
     * 相机空间里相当于 y ≈ {@code -distance · sin(3°) ≈ -0.052 · distance}，
     * 即「视野正中略微偏下」，避免压住准星。取固定值而不是 spawn 那段 3~13° 的随机量，
     * 否则每帧重算会让文字上下抖。
     */
    private static final double HEAD_PITCH_DROP_DEG = LyricAnchor.SPAWN_PITCH_DROP;

    /** 波浪档周期（毫秒）：2s 一个完整起落，MC 生态里常见的「呼吸」节奏。 */
    private static final long WAVE_PERIOD_MS = 2000L;

    /** 波浪档相邻字符的相位差（弧度）：0.5 rad ≈ 28.6°，42 个字正好铺开 3 个波。 */
    private static final double WAVE_PHASE_STEP = 0.5D;

    /** 文闪档周期（毫秒）：1.6s，比波浪快一点，脉动更「亮」。 */
    private static final long GLOW_PERIOD_MS = 1600L;

    /** 文闪档相邻字符的相位差（弧度）：0.35 rad ≈ 20°，同屏字符亮度错落但不刺眼。 */
    private static final double GLOW_PHASE_STEP = 0.35D;

    /** 文闪档的亮度下限：alpha 在 0.55 ~ 1.0 之间波动，再低就看不清字了。 */
    private static final double GLOW_MIN_ALPHA = 0.55D;

    /** 文闪档可选的 y 浮动系数（相对 {@code subtitleFxAmplitude}）：0.25 表示「保持克制」。 */
    private static final double GLOW_BOB_RATIO = 0.25D;

    /** 跳动档每个字从弹起到落回的时间（毫秒）：200ms 走完，比每字间隔略长一点才有连弹感。 */
    private static final double BOUNCE_MS = 200.0D;

    /** 打字机光标的亮/灭半周期（毫秒）：250ms 亮 + 250ms 灭 ≈ 500ms 一个周期。 */
    private static final long CURSOR_BLINK_HALF_MS = 250L;

    /** 跟随 / 停留两档的锚点状态机。 */
    private static final LyricAnchor ANCHOR = new LyricAnchor();

    /**
     * 换句时间轴：包着 {@link com.feiyu.readerbridge.client.world.TextFade} 的交叉淡化状态机，
     * 额外记住「当前句从哪一刻开始显示」，供逐字档算各自的显现时刻。
     * 纯逻辑，不碰 GL，只回答「本帧该画哪两段文字、各自的 alpha / 进度是多少」。
     */
    private static final SubtitleAnimator ANIM = new SubtitleAnimator();

    /** 落笔器：整帧复用，避免每帧分配（{@link SubtitlePainter#begin} 每帧刷新字段）。 */
    private static final SubtitlePainter PAINTER = new SubtitlePainter();

    /** 上一帧生效的锚点预设，用来检测「切档」并在切档时复位锚点。 */
    private static AnchorMode lastAnchorMode = AnchorMode.FOLLOW;

    /** 上一帧生效的动效预设，切档时复位时间轴，免得两档状态互相污染。 */
    private static SubtitleFxMode lastFxMode = SubtitleFxMode.CROSSFADE;

    private static long lastRenderNanos;

    private WorldTextRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null || minecraft.player == null
                || minecraft.gameRenderer == null) {
            return;
        }
        if (!ReaderBridgeConfig.showWorldText()) {
            ANCHOR.reset();
            ANIM.clear();
            return;
        }
        // 必须走 Provider 而不是 ReaderBridgeApiImpl.instanceOrNull()：本地朗读引擎通过
        // ReaderBridgeProvider.install(LocalReaderApi.INSTANCE) 注入，WS 客户端在无外部 App 时
        // 状态恒为 inactive —— 走 impl 会在第一帧就 return，字幕永远不出现。
        ReaderBridgeApi api = ReaderBridgeProvider.getIfPresent().orElse(null);
        if (api == null) {
            ANIM.clear();
            return;
        }
        ReadingState state = api.getState();
        if (state == null || !state.isActive()) {
            // 停止朗读 / 退出世界：淡化状态就地清空，绝不留半透明残影。
            ANCHOR.reset();
            ANIM.clear();
            return;
        }
        String sentence = state.currentSentence();
        if (sentence == null || sentence.isBlank()) {
            // 这一帧没有可画的内容：只跳过绘制，不清状态。
            // 上游偶尔给一帧空句子时句子还是同一句，清掉会让文字硬切重播一次淡入；
            // 真正的「停止朗读」由上面的 isActive() 分支负责清空。
            return;
        }
        long now = System.nanoTime();
        if (now - lastRenderNanos < MIN_FRAME_GAP_NANOS) {
            return;
        }
        lastRenderNanos = now;
        try {
            render(minecraft, sentence.strip(), state.isPaused(), now);
        } catch (Throwable t) {
            // 渲染阶段抛异常会崩客户端，一律吞掉。
            ReaderBridgeMod.LOGGER.debug("[readerbridge] 3D 文字渲染失败: {}", t.toString());
        }
    }

    private static void render(Minecraft minecraft, String text, boolean paused, long nowNanos) {
        Camera camera = minecraft.gameRenderer.getMainCamera();
        if (camera == null) {
            return;
        }
        Font font = minecraft.font;

        // ---- 0. 预设：每帧读配置（Configured 里改完下一帧立刻生效）----
        AnchorMode anchorMode = AnchorMode.fromId(ReaderBridgeConfig.anchorPreset());
        SubtitleFxMode fx = SubtitleFxMode.fromId(ReaderBridgeConfig.subtitleFx());
        if (anchorMode != lastAnchorMode) {
            // 三档的锚点状态互不通用（follow 有弹簧速度、stay 有钉死的世界坐标、head 根本没有
            // 缓存）。切档时直接清空，新档从「未锚定」开始，下一帧按自己的规则重新定位。
            ANCHOR.reset();
            ReaderBridgeMod.LOGGER.info("[readerbridge] anchorPreset {} -> {}",
                    lastAnchorMode.id(), anchorMode.id());
            lastAnchorMode = anchorMode;
        }
        if (fx != lastFxMode) {
            // 换句时间轴同样复位：否则 typewriter 会继承上一档的起始纳秒，切档瞬间整句已经
            // 「打完」，观感上动效被吃掉。清空后下一句按首句处理（直接全显）也符合直觉。
            ANIM.clear();
            ReaderBridgeMod.LOGGER.info("[readerbridge] subtitleFx {} -> {}", lastFxMode.id(), fx.id());
            lastFxMode = fx;
        }

        // ---- 1. 换句时间轴 ----
        // 时长每帧实时读配置（而不是在换句瞬间快照）：玩家在淡出中把淡出时长调到 0，
        // 下一帧就立刻硬切收尾，不会卡在一半的残影里。
        int fadeInMs = ReaderBridgeConfig.worldTextFadeInMs();
        int fadeOutMs = ReaderBridgeConfig.worldTextFadeOutMs();
        float amplitude = ReaderBridgeConfig.subtitleFxAmplitude();
        int msPerChar = ReaderBridgeConfig.subtitleFxMsPerChar();
        // 只有 crossfade / slideUp 需要旧句残影；其余档「旧句直接消失」——
        // 给 TextFade 传 fadeOutMs = 0 就会在 update 里立刻把上一句丢掉。
        boolean keepsPrevious = fx.usesPreviousSentence();
        int animFadeIn = fx == SubtitleFxMode.NONE ? 0 : fadeInMs;
        int animFadeOut = keepsPrevious ? fadeOutMs : 0;
        ANIM.update(text, nowNanos, animFadeIn, animFadeOut);

        List<String> lines = wrap(font, text);
        if (lines.isEmpty()) {
            return;
        }
        // 上一句只在「还有可见 alpha」时才需要折行：alpha 已经到 0 的残影一顶点都不提交。
        float previousAlpha = 0.0F;
        List<String> previousLines = null;
        String previousText = ANIM.previousText();
        if (keepsPrevious && previousText != null && !previousText.equals(text)) {
            previousAlpha = ANIM.previousAlpha(nowNanos, animFadeOut);
            if (previousAlpha > 0.0F) {
                List<String> wrapped = wrap(font, previousText);
                if (!wrapped.isEmpty()) {
                    previousLines = wrapped;
                }
            }
        }
        float maxWidth = 0.0F;
        for (String line : lines) {
            maxWidth = Math.max(maxWidth, font.width(line));
        }
        int clampLineCount = lines.size();
        if (previousLines != null) {
            // 回屏边界按「两段里更宽/更高的那个」算，免得旧句行数更多时残影被顶到屏幕外。
            for (String line : previousLines) {
                maxWidth = Math.max(maxWidth, font.width(line));
            }
            clampLineCount = Math.max(clampLineCount, previousLines.size());
        }

        double distance = LyricAnchor.clampDistance(ReaderBridgeConfig.worldTextDistance());
        // stay 档「每句换位置」的横向随机幅度；follow / head 恒传 0，不受这个配置影响。
        double spreadDeg = ReaderBridgeConfig.anchorSpreadDeg();
        LyricAnchor.View view = new LyricAnchor.View(
                camera.getPosition().x, camera.getPosition().y, camera.getPosition().z,
                camera.getYRot(), camera.getXRot(),
                minecraft.options.fov().get(), aspect(minecraft));

        // ---- 2. 锚点：三档只在这里分叉，之后的绘制几何完全相同 ----
        // 先给默认值，保证 switch 在任何路径上都不会用到「可能未赋值」的局部变量。
        double anchorX = view.eyeX;
        double anchorY = view.eyeY;
        double anchorZ = view.eyeZ;
        double len = distance;
        switch (anchorMode) {
            case FOLLOW -> {
                // 句间固定：整段朗读里文字停在同一个位置（首次/传送/越界才 spawn，换句只换文本）。
                if (!ANCHOR.isAnchored() || !ANCHOR.isSane(view, distance)) {
                    // spreadDeg = 0：句子钉在视线正前方，不随机散开（阅读场景要位置稳定）。
                    ANCHOR.spawn(view, text, distance, 0.0D, maxWidth, WORLD_SCALE, true);
                } else {
                    ANCHOR.setSentence(text);
                }
                ANCHOR.follow(view, distance, true, true);
                ANCHOR.clampIntoView(view, distance, true, true, maxWidth, clampLineCount, LINE_H, WORLD_SCALE);
                anchorX = ANCHOR.x();
                anchorY = ANCHOR.y();
                anchorZ = ANCHOR.z();
                len = ANCHOR.distanceTo(view);
            }
            case STAY -> {
                // 每句一个新位置（参考项目 MuoniumPlayer 的默认观感）：句子一变就重新 spawn。
                // 「固定」指这一句朗读期间世界坐标不动，不是整段朗读固定在同一处 ——
                // 若改成「只 spawn 一次、换句仅换文本」，所有句子就会叠在同一个点上。
                boolean textChanged = !java.util.Objects.equals(text, ANCHOR.sentence());
                if (!ANCHOR.isAnchored() || textChanged || !ANCHOR.isSane(view, distance)) {
                    ANCHOR.spawn(view, text, distance, spreadDeg, maxWidth, WORLD_SCALE, true);
                }
                // 这里**刻意不调** follow / clampIntoView。
                // 之前调了，于是 stay 与 follow 表现完全一样（用户实测结论：「两个模式完全一样」）：
                // follow 会把锚点随玩家位移整体平移、clampIntoView 会把飘出视野的句子拉回来，
                // 两者叠加之后「留在原地」这条语义就一点不剩了。
                // 现在的语义：spawn 那一刻算出世界坐标，之后走、转头、转身全都不动；
                // 走出视野/走远看不见是预期行为，下一句会按新的随机位置重新生成。
                anchorX = ANCHOR.x();
                anchorY = ANCHOR.y();
                anchorZ = ANCHOR.z();
                // 缩放用 spawn 时的 distance，而不是 distanceTo(view)：
                // 世界坐标固定之后，靠近/远离只应该由透视决定近大远小，
                // 再乘一次 len 会让字被放大两次（贴脸时字糊满屏幕）。
                len = distance;
            }
            case HEAD -> {
                // 视线跟随：每帧把锚点钉在相机空间正前方固定距离，永远在视野正中。
                //
                // 「前方 = 负 z」的依据（不是猜的，是从现有渲染路径推出来的）：
                //   本方法下面构造的 basePose = rotateX(pitch) · rotateY(yaw + 180)；
                //   把 LyricAnchor.yawPitchDirection(yaw, pitch) 的视线方向代进去逐项化简：
                //     Ry(yaw+180) 之后：x = 0，y = -sin(pitch)，z = -cos(pitch)（cos/sin 项互相抵消）
                //     Rx(pitch)   之后：y = 0，z = -1
                //   也就是说 follow 档算出的锚点（eye + 视线方向 × distance）在**相机空间**的坐标
                //   恒为 (0, 0, -distance)。字面随后经过 matrix.scale(-scale, -scale, scale) 的双负号
                //   翻正（见双负号那几行注释），所以 -z 一定是「在玩家前方、正对镜头」。
                //
                // 实现上仍然写回**世界坐标**（eye + 方向 × distance），这样 HEAD 与 FOLLOW/STAY
                // 共用同一个「anchor - eye」平移量、同一个 scale = len / DISTANCE_NORM 公式，
                // 也共用同一套折行/居中几何。相机空间里它等于 (0, -distance·sin(3°), -distance·cos(3°))，
                // 即正中略微偏下（3° 来自 SPAWN_PITCH_DROP，理由见常量注释）。
                double headPitch = view.pitchDeg - HEAD_PITCH_DROP_DEG;
                if (headPitch < -89.0D) {
                    headPitch = -89.0D;
                } else if (headPitch > 89.0D) {
                    headPitch = 89.0D;
                }
                double[] dir = LyricAnchor.yawPitchDirection(view.yawDeg, headPitch);
                anchorX = view.eyeX + dir[0] * distance;
                anchorY = view.eyeY + dir[1] * distance;
                anchorZ = view.eyeZ + dir[2] * distance;
                // yawPitchDirection 是单位向量 ⇒ 锚点到眼睛的距离就是 distance。
                len = distance;
            }
        }

        if (len < 0.05D) {
            return;
        }
        // 世界尺寸与距离成正比 ⇒ 屏幕上的角度大小恒定，走远走近字不缩水。
        float scale = (float) (LyricAnchor.PX_TO_WORLD * WORLD_SCALE * (len / LyricAnchor.DISTANCE_NORM));

        Matrix4f basePose = new Matrix4f()
                .rotateX((float) Math.toRadians(view.pitchDeg))
                .rotateY((float) Math.toRadians(view.yawDeg + 180.0D));

        PoseStack modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        modelView.setIdentity();
        RenderSystem.applyModelViewMatrix();

        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();

        try {
            PoseStack poseStack = new PoseStack();
            poseStack.pushPose();
            Matrix4f matrix = poseStack.last().pose();
            matrix.set(basePose);
            matrix.translate(
                    (float) (anchorX - view.eyeX),
                    (float) (anchorY - view.eyeY),
                    (float) (anchorZ - view.eyeZ));
            // facePlayer：字面朝向直接取相机角度，因此永远正对屏幕。
            matrix.rotateY((float) -Math.toRadians(view.yawDeg));
            matrix.rotateX((float) Math.toRadians(view.pitchDeg));
            // 两个负号是硬性要求：同时翻转 x/y 轴方向，把 MC 的「y 向下」翻成世界坐标的
            // 「y 向上」。少了负号字会上下左右镜像且背面朝你（看到反字）；若这时 cull 还开着，
            // 整句会直接消失。
            matrix.scale(-scale, -scale, scale);

            // 暂停态只压暗 RGB，不参与淡化计算：两段都沿用同一个 baseColor，再各自乘 alpha，
            // 于是暂停时残影也是灰的，不会有一瞬间被洗白。
            int baseColor = paused ? COLOR_PAUSED : COLOR_ACTIVE;
            MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
            PAINTER.begin(font, matrix, buffers, LINE_H);
            drawSentence(fx, lines, previousLines, previousAlpha, baseColor, nowNanos,
                    animFadeIn, amplitude, msPerChar);
            // 必须显式 endBatch，否则顶点留在缓冲里不落地（本阶段没有别的东西会替我们 flush）。
            // 整帧**只此一次**：旧句、新句、打字机光标写的是同一个 BufferSource，
            // 统一在这里 flush；任何一档动效都不允许自己再调 endBatch。
            buffers.endBatch();
            poseStack.popPose();
        } finally {
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
        }
    }

    /**
     * 按当前动效预设把这一帧的字写进顶点缓冲。
     *
     * <p><b>整帧唯一的 {@code endBatch()} 在调用方</b>（{@code render} 的 try 块里），
     * 本方法与 {@link SubtitlePainter} 的任何方法都不许自己 flush。</p>
     *
     * <p>各档读的配置：</p>
     * <ul>
     *   <li>{@code crossfade} / {@code slideUp}：{@code worldTextFadeInMs}、{@code worldTextFadeOutMs}</li>
     *   <li>{@code typewriter} / {@code typewriterBounce}：{@code subtitleFxMsPerChar}、
     *       {@code subtitleFxAmplitude}（仅跳动）</li>
     *   <li>{@code wave} / {@code glow}：{@code subtitleFxAmplitude}</li>
     *   <li>{@code none}：不读任何时长，硬切</li>
     * </ul>
     *
     * @param previousLines 旧句折行结果；{@code null} 表示这一档不需要残影
     * @param previousAlpha 旧句本帧 alpha（仅 crossfade / slideUp 用）
     * @param fadeInMs      新句淡入/上滑时长（{@code none} 档已按 0 传入，虽然它也不用）
     * @param amplitude     位移幅度（渲染单位 ≈ 设计像素）
     * @param msPerChar     打字机每字间隔（毫秒）
     */
    private static void drawSentence(SubtitleFxMode fx, List<String> lines, List<String> previousLines,
            float previousAlpha, int baseColor, long nowNanos, int fadeInMs, float amplitude, int msPerChar) {
        boolean instant = ANIM.isInstant();
        double elapsedMs = ANIM.elapsedMs(nowNanos);
        long nowMs = nowNanos / 1_000_000L;

        switch (fx) {
            case NONE -> {
                // 硬切：不淡入、不留残影（fadeIn/fadeOut 在 update 时已按 0 传入）。
                PAINTER.drawSolidLines(lines, baseColor, 1.0F, 0.0F);
            }
            case CROSSFADE -> {
                // 保持改动前的观感：旧句先画（叠在下层）、新句后画（压在上层）；
                // 两段各自按自己的行数垂直居中，共用同一个锚点中心。
                if (previousLines != null) {
                    PAINTER.drawSolidLines(previousLines, baseColor, previousAlpha, 0.0F);
                }
                PAINTER.drawSolidLines(lines, baseColor, ANIM.currentAlpha(nowNanos, fadeInMs), 0.0F);
            }
            case SLIDE_UP -> {
                // 旧句原地淡出；新句从下方 %AMPLITUDE·2% 处 easeOut 滑到原位，同时 alpha 0 → 1。
                if (previousLines != null) {
                    PAINTER.drawSolidLines(previousLines, baseColor, previousAlpha, 0.0F);
                }
                float progress = instant ? 1.0F : SubtitleAnimator.progress(elapsedMs, fadeInMs);
                float ease = 1.0F - (1.0F - progress) * (1.0F - progress); // easeOutQuad
                // Font.drawInBatch 的 y 向下为正 ⇒ 正偏移即「在下方」，滑入就是把它收回 0。
                PAINTER.drawSolidLines(lines, baseColor, ease, amplitude * 2.0F * (1.0F - ease));
            }
            case TYPEWRITER -> {
                int total = PAINTER.drawCharLines(lines, baseColor, (codePoint, index, count) ->
                        revealed(instant, elapsedMs, index, msPerChar) ? 1.0F : 0.0F);
                drawCursorIfDone(lines, baseColor, total, instant, elapsedMs, msPerChar, nowMs);
            }
            case TYPEWRITER_BOUNCE -> {
                int total = PAINTER.drawCharLines(lines, baseColor, new SubtitlePainter.CharShader() {
                    @Override
                    public float alpha(int codePoint, int index, int count) {
                        return revealed(instant, elapsedMs, index, msPerChar) ? 1.0F : 0.0F;
                    }

                    @Override
                    public float dy(int codePoint, int index, int count) {
                        if (instant || amplitude <= 0.0F) {
                            return 0.0F;
                        }
                        double since = elapsedMs - (double) index * (double) msPerChar;
                        if (since <= 0.0D) {
                            return 0.0F;
                        }
                        double p = since / BOUNCE_MS;
                        if (p >= 1.0D) {
                            // 已稳定的字符偏移为 0（也避免 sin(π) 的 1e-16 残量）。
                            return 0.0F;
                        }
                        // 需求给的位移曲线：amplitude · sin(πp) · e^(-3p)。
                        // 取负号是因为 Font.drawInBatch 的 y 轴向下为正，「向上弹」必须是负偏移；
                        // sin(πp) 在 p∈[0,1] 内恒非负，所以整段是「弹起 → 落回 0」。
                        return (float) (-amplitude * Math.sin(Math.PI * p) * Math.exp(-3.0D * p));
                    }
                });
                drawCursorIfDone(lines, baseColor, total, instant, elapsedMs, msPerChar, nowMs);
            }
            case WAVE -> {
                // 整句立刻全显，逐字按 sin 上下浮动；相位用「绝对时间取模」，
                // 直接拿 nowMs 当角度会在大数值上丢精度。
                double phase = cycleRadians(nowMs, WAVE_PERIOD_MS);
                PAINTER.drawCharLines(lines, baseColor, new SubtitlePainter.CharShader() {
                    @Override
                    public float alpha(int codePoint, int index, int count) {
                        return 1.0F;
                    }

                    @Override
                    public float dy(int codePoint, int index, int count) {
                        return (float) (amplitude * Math.sin(phase + index * WAVE_PHASE_STEP));
                    }
                });
            }
            case GLOW -> {
                // 文闪（参考 DangoMusic CHANGELOG 里的「文闪」）：整句常驻，逐字 alpha 在
                // 0.55 ~ 1.0 之间脉动；同一相位再叠一点点 y 浮动（幅度 × 0.25，保持克制）。
                double phase = cycleRadians(nowMs, GLOW_PERIOD_MS);
                PAINTER.drawCharLines(lines, baseColor, new SubtitlePainter.CharShader() {
                    @Override
                    public float alpha(int codePoint, int index, int count) {
                        double wave = 0.5D + 0.5D * Math.sin(phase + index * GLOW_PHASE_STEP);
                        return (float) (GLOW_MIN_ALPHA + (1.0D - GLOW_MIN_ALPHA) * wave);
                    }

                    @Override
                    public float dy(int codePoint, int index, int count) {
                        return (float) (amplitude * GLOW_BOB_RATIO * Math.sin(phase + index * GLOW_PHASE_STEP));
                    }
                });
            }
        }
    }

    /** 打字机节奏：首句直接全显；其余情况第 {@code index} 个字在 {@code index × msPerChar} 毫秒后出现。 */
    private static boolean revealed(boolean instant, double elapsedMs, int index, int msPerChar) {
        if (instant) {
            return true;
        }
        return elapsedMs >= (double) index * (double) msPerChar;
    }

    /**
     * 打字机类动效的光标：整句打完（或首句直接全显）后，在末行右端画一个闪烁竖线。
     *
     * <p>闪烁周期 500ms（250ms 亮 / 250ms 灭），相位取 {@code nowMs} 的绝对时间；
     * 光标走的是 {@link SubtitlePainter#drawCursor} → 同一个 {@code Font.drawInBatch}，
     * 不引入 {@code GuiGraphics}（这是世界渲染阶段，没有 GUI 上下文）。</p>
     */
    private static void drawCursorIfDone(List<String> lines, int baseColor, int total, boolean instant,
            double elapsedMs, int msPerChar, long nowMs) {
        if (!instant && elapsedMs < (double) total * (double) msPerChar) {
            return;
        }
        if ((nowMs / CURSOR_BLINK_HALF_MS) % 2L != 0L) {
            return;
        }
        PAINTER.drawCursor(lines, baseColor, 1.0F);
    }

    /**
     * 绝对时间 → 0..2π 的周期相位。
     *
     * <p>先对周期取模再转弧度：{@code System.nanoTime()} 的毫秒数可以到 1e12 量级，
     * 直接 {@code sin(2π · nowMs / T)} 会让相位涨到 1e9 弧度，double 的尾数精度开始掉。</p>
     */
    private static double cycleRadians(long nowMs, long periodMs) {
        if (periodMs <= 0L) {
            return 0.0D;
        }
        double cycle = (double) (nowMs % periodMs) / (double) periodMs;
        return cycle * Math.PI * 2.0D;
    }

    /**
     * 只替换 ARGB 的最高字节（alpha 通道），RGB 原样保留。
     *
     * <p>{@link Font#drawInBatch} 的 {@code color} 是 {@code 0xAARRGGBB}：所以
     * {@link #COLOR_PAUSED} 的 {@code 0xB4B4B4} 不会被洗白，{@link #COLOR_ACTIVE} 也不会染色。</p>
     */
    static int withAlpha(int argb, float alpha) {
        int rgb = argb & 0x00FFFFFF;
        // 用 !(alpha > 0) 而不是 alpha <= 0：NaN 也走这条，直接判成完全透明。
        if (!(alpha > 0.0F)) {
            return rgb;
        }
        if (alpha >= 1.0F) {
            return 0xFF000000 | rgb;
        }
        int a = Math.round(alpha * 255.0F);
        return (a << 24) | rgb;
    }

    private static double aspect(Minecraft minecraft) {
        int width = minecraft.getWindow().getWidth();
        int height = Math.max(1, minecraft.getWindow().getHeight());
        return (double) width / (double) height;
    }

    /** 按设计像素宽度折行，最多 {@value #MAX_LINES} 行，末行溢出加省略号。 */
    static List<String> wrap(Font font, String text) {
        List<String> lines = new ArrayList<>(MAX_LINES);
        StringBuilder current = new StringBuilder();
        float width = 0.0F;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\n' || ch == '\r') {
                continue;
            }
            float advance = font.width(String.valueOf(ch));
            if (current.length() > 0 && width + advance > MAX_LINE_WIDTH) {
                lines.add(current.toString());
                current.setLength(0);
                width = 0.0F;
                if (lines.size() >= MAX_LINES) {
                    return lines;
                }
            }
            current.append(ch);
            width += advance;
        }
        if (current.length() > 0) {
            if (lines.size() >= MAX_LINES) {
                String last = lines.get(MAX_LINES - 1);
                lines.set(MAX_LINES - 1, last.substring(0, Math.max(0, last.length() - 1)) + "…");
            } else {
                lines.add(current.toString());
            }
        }
        return lines;
    }

    /** 把长句截断到 {@code maxChars}，末尾加省略号（供 HUD 等其它渲染复用）。 */
    public static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, Math.max(0, maxChars - 1)) + "…";
    }
}
