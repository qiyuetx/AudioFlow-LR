package com.audiomixing.app;

/**
 * 左右声道路由矩阵（合并版核心）。
 *
 * <p>输入/输出都是 <b>48 kHz 交错立体声 PCM16 小端</b>：一帧 4 字节 = 左 16 位 + 右 16 位。
 * 参考工程 AudioMixing 的手机端本来就是 48 kHz / 立体声 / PCM16（{@link Proto#CHANNELS}），
 * 而被解析的「音频流转」APK 是 44100 Hz / <b>单声道</b> / A-Law —— 本类就是把
 * 「立体声链路」真正变成「手机上可分别控制的左右声道」的那一层。
 *
 * <p><b>两级控制</b>：
 * <ol>
 *   <li><b>按设备分配</b>（{@link #CH_LEFT}/{@link #CH_RIGHT}/{@link #CH_BOTH}）：
 *       在 {@code AudioRecv.Stream.store()} 里把某一路来源折成单声道后只写进左/右槽位。
 *       于是「电脑 A 进左耳、电脑 B 进右耳」成为可能 —— 这正是两个项目各自长处的结合：
 *       APK 的多设备/环绕思路 + AudioMixing 的多流混音。</li>
 *   <li><b>全局输出矩阵</b>（本类 {@link #apply(byte[], int)}）：在混音完成、
 *       写进 {@code AudioTrack} 之前，统一套用声道模式、平衡、左右独立音量。</li>
 * </ol>
 *
 * <p><b>边界</b>：只改我们自己处理的 PCM，绝不调用
 * {@code AudioManager.setStreamVolume()} / {@code setStreamMute()}（项目铁律：不动系统音量）。
 * 所有系数都做削顶，回绕会变成刺耳爆音。
 *
 * <p>本类<b>零安卓依赖</b>，可用纯 JDK 编译单测（见 {@code tools/ChannelRouterTest.java}）。
 */
public final class ChannelRouter {

    // ── 全局声道模式 ──
    /** 立体声：左右原样（默认）。 */
    public static final int MODE_STEREO = 0;
    /**
     * 静音：本机一个字节都不往 AudioTrack 送声音，但**接收、混音、转发一样照跑**。
     *
     * <p>★ 2026-11-12 用户：「本机加入可以静音，但依然可以流转」——原来这一格是
     * 「单声道（L+R 取平均）」，而单声道化在手机链路上几乎没用（音源本来就是立体声，
     * 真要一只耳朵听全内容有「仅左/仅右」），反倒是"我想把这台手机当哑巴中继"这个需求
     * 每天都用得上。于是把它换成静音。
     *
     * <p>实现上**只把输出样本清零**（在 {@link #apply} 里），不碰
     * {@code AudioRecv} 的收包/抖动缓冲/流生命周期，也不碰发送侧：所以静音的手机
     * 照样在收、照样在送给别的设备，只是自己不出声。
     */
    public static final int MODE_MUTE = 1;
    /** 仅左：右声道清零。 */
    public static final int MODE_LEFT_ONLY = 2;
    /** 仅右：左声道清零。 */
    public static final int MODE_RIGHT_ONLY = 3;
    /**
     * 传送静音：本机把声音送出去时自己不出声。
     *
     * <p>★ 2026-11-12 用户：「把本机播放里面左右互换改成送声 + 手机静音这个功能：
     * 本机把声音送出去（送声音）时，自己不出声，名字是传送静音」。
     *
     * <p>样本层面它和 {@link #MODE_MUTE} 一样（输出清零，所以和系统音量无关，一定哑）；
     * 区别在 {@code Engine.setChannelMode} 那层：切到这一档会同时把「送声音」推到第 2 档，
     * 采集与发送照跑 —— 也就是"我在送，但我自己不听"。
     */
    public static final int MODE_SEND_MUTE = 4;

    // ── 按设备分配 ──
    /** 两台耳朵都放（默认）。 */
    public static final int CH_BOTH = 0;
    /** 只进左耳。 */
    public static final int CH_LEFT = 1;
    /** 只进右耳。 */
    public static final int CH_RIGHT = 2;

    private volatile int mode = MODE_STEREO;
    /** -1 = 全左，0 = 居中，+1 = 全右。 */
    private volatile float balance = 0f;
    /** 左声道独立音量 0..1。 */
    private volatile float leftGain = 1f;
    /** 右声道独立音量 0..1。 */
    private volatile float rightGain = 1f;
    /**
     * ★ 2026-11-12 用户：「默认音量虽然是 100%，但设备上的声音感觉还是小，调大一点」——
     * 本机播放增益，**允许到 2.0（+6dB）**。
     *
     * <p>为什么不直接用 {@code AudioTrack.setVolume}：那个上限就是 1.0，再往上没法放大。
     * 所以 ≤1 的部分仍然交给它（零成本），>1 的那部分必须乘在样本上 —— 乘完一定可能顶到
     * 满刻度，所以配套一个软削顶（见 {@link #softClip}）：过冲被压成缓慢弯曲，而不是
     * 硬削产生刺耳的高频毛刺。
     */
    private volatile float volume = 1f;

    public ChannelRouter() {
    }

    // ------------------------------------------------------------------ 设置

    public void setMode(int m) {
        mode = (m >= MODE_STEREO && m <= MODE_SEND_MUTE) ? m : MODE_STEREO;
    }

    public int mode() {
        return mode;
    }

    public void setBalance(float b) {
        balance = Math.max(-1f, Math.min(1f, b));
    }

    public float balance() {
        return balance;
    }

    public void setLeftGain(float g) {
        leftGain = clamp01(g);
    }

    public float leftGain() {
        return leftGain;
    }

    public void setRightGain(float g) {
        rightGain = clamp01(g);
    }

    /** 本机播放增益 0..2（1.0 = 原样，2.0 = 放大一倍）。 */
    public void setVolume(float v) {
        volume = Math.max(0f, Math.min(2f, v));
    }

    public float volume() {
        return volume;
    }

    public float rightGain() {
        return rightGain;
    }

    /** 一键还原：立体声 / 平衡居中 / 左右都 1.0。 */
    public void reset() {
        mode = MODE_STEREO;
        balance = 0f;
        leftGain = 1f;
        rightGain = 1f;
        volume = 1f;
    }

    /** 是否全默认（热路径用它跳过额外的峰值重算）。 */
    public boolean isDefault() {
        return mode == MODE_STEREO && balance == 0f && leftGain == 1f && rightGain == 1f
                && volume == 1f;
    }

    // ------------------------------------------------------------------ 处理

    /**
     * 对一段交错立体声 PCM16 就地套用「模式 + 平衡 + 左右独立音量」。
     *
     * <p>顺序：先做声道模式（单声道化/静音一侧/互换），再乘平衡与左右音量。
     * 平衡用线性衰减（b&gt;0 压左、b&lt;0 压右），居中时两侧都是 1.0，
     * 不引入额外增益 —— 不会比原始信号更响，也不会削顶。
     *
     * @param pcm 交错 PCM16，长度按 4 字节一帧处理（不足一帧的尾巴原样保留）
     * @param len 有效字节数
     */
    public void apply(byte[] pcm, int len) {
        if (pcm == null || len < 4) {
            return;
        }
        final int m = mode;
        final float b = balance;
        final float lg = leftGain;
        final float rg = rightGain;
        final float vol = volume;
        // 快路径：全默认时一个样本都不碰（每 10ms 一趟的热路径）。
        if (m == MODE_STEREO && b == 0f && lg == 1f && rg == 1f && vol == 1f) {
            return;
        }
        // 传送静音：整段清零就走（不在逐帧里判断）。清的是"本机要播放的样本"，
        // 采集与发送走的是另一条路，所以声音照样送给别的设备。
        if (m == MODE_SEND_MUTE) {
            java.util.Arrays.fill(pcm, 0, len, (byte) 0);
            return;
        }
        final float bl = b > 0f ? 1f - b : 1f;
        final float br = b < 0f ? 1f + b : 1f;
        final float gl = lg * bl * vol;
        final float gr = rg * br * vol;
        final int end = len - 3;
        for (int i = 0; i < end; i += 4) {
            int l = (short) ((pcm[i] & 0xFF) | ((pcm[i + 1] & 0xFF) << 8));
            int r = (short) ((pcm[i + 2] & 0xFF) | ((pcm[i + 3] & 0xFF) << 8));
            switch (m) {
                case MODE_MUTE: {
                    // 静音 = 这一帧直接归零。不是"暂停播放"：播放头照样往前走，
                    // 抖动缓冲不预缓冲、不重建流，取消静音时立刻就有声音。
                    l = 0;
                    r = 0;
                    break;
                }
                case MODE_LEFT_ONLY:
                    r = 0;
                    break;
                case MODE_RIGHT_ONLY:
                    l = 0;
                    break;
                default:
                    break;   // MODE_STEREO
            }
            // vol > 1 时可能顶到满刻度 → 走软削顶，别硬削成刺耳的毛刺。
            int ol = (vol > 1f) ? softClip(Math.round(l * gl)) : clip(Math.round(l * gl));
            int or = (vol > 1f) ? softClip(Math.round(r * gr)) : clip(Math.round(r * gr));
            pcm[i] = (byte) ol;
            pcm[i + 1] = (byte) (ol >> 8);
            pcm[i + 2] = (byte) or;
            pcm[i + 3] = (byte) (or >> 8);
        }
    }

    /**
     * 按设备分配 + 逐路增益：把 {@code src} 拷进 {@code dst}，
     * 指定 {@code ch} 时先折成单声道再只写对应那一侧，另一侧清零。
     *
     * <p>故意做成 static 且自包含：{@code AudioRecv.Stream} 在收包线程里直接调用它，
     * 不依赖任何安卓类，抖动缓冲的离线测试也能原样编译。
     *
     * @param ch {@link #CH_BOTH} / {@link #CH_LEFT} / {@link #CH_RIGHT}
     */
    public static void routeAndScale(byte[] src, byte[] dst, int len, float g, int ch) {
        if (src == null || dst == null) {
            return;
        }
        final int end = Math.min(len, dst.length) - 3;
        for (int i = 0; i < end; i += 4) {
            int l = (short) ((src[i] & 0xFF) | ((src[i + 1] & 0xFF) << 8));
            int r = (short) ((src[i + 2] & 0xFF) | ((src[i + 3] & 0xFF) << 8));
            if (ch == CH_LEFT) {
                l = (l + r) / 2;
                r = 0;
            } else if (ch == CH_RIGHT) {
                r = (l + r) / 2;
                l = 0;
            }
            int ol = clip(Math.round(l * g));
            int or = clip(Math.round(r * g));
            dst[i] = (byte) ol;
            dst[i + 1] = (byte) (ol >> 8);
            dst[i + 2] = (byte) or;
            dst[i + 3] = (byte) (or >> 8);
        }
    }

    /** 诊断/界面用的一行状态。 */
    public String text() {
        return modeText(mode) + " · 平衡 " + balanceText(balance)
                + " · 左 " + pct(leftGain) + " / 右 " + pct(rightGain)
                + " · 增益 " + Math.round(volume * 100f) + "%";
    }

    public static String modeText(int m) {
        switch (m) {
            case MODE_MUTE: return "静音（本机不出声，仍在流转）";
            case MODE_LEFT_ONLY: return "仅左声道";
            case MODE_RIGHT_ONLY: return "仅右声道";
            case MODE_SEND_MUTE: return "传送静音（本机不出声，声音照送给别的设备）";
            default: return "立体声";
        }
    }

    public static String channelText(int ch) {
        switch (ch) {
            case CH_LEFT: return "左耳";
            case CH_RIGHT: return "右耳";
            default: return "双耳";
        }
    }

    public static String balanceText(float b) {
        if (Math.abs(b) < 0.02f) {
            return "居中";
        }
        int p = Math.round(Math.abs(b) * 100f);
        return (b < 0 ? "左 " : "右 ") + p + "%";
    }

    private static String pct(float v) {
        return Math.round(v * 100f) + "%";
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /**
     * 软削顶：|v| 超过 {@code KNEE} 之后斜率降到 1/4，再夹到 16 位满刻度。
     *
     * <p>和硬削的区别：硬削在 32767 处直接截断，波形出现尖角 → 听感是"炸"；
     * 软削把过冲压成一段缓弯，同样避免回绕（回绕才是真正的爆音），但毛刺小得多。
     * 每帧 960 个样本、每 10ms 一趟，这点算术可以忽略。
     */
    private static int softClip(int v) {
        final int knee = 26000;
        if (v > knee) {
            v = knee + (v - knee) / 4;
        } else if (v < -knee) {
            v = -knee + (v + knee) / 4;
        }
        return clip(v);
    }

    private static int clip(int v) {
        if (v > 32767) return 32767;
        if (v < -32768) return -32768;
        return v;
    }
}
