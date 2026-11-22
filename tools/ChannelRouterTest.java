package com.audiomixing.app;

/**
 * ChannelRouter 的纯 JDK 回归测试（不需要 Android SDK / 不需要手机）。
 *
 * <p>运行：由工程根目录的 build_apk.py 自动编译执行；也可手工：
 * <pre>
 * javac -encoding UTF-8 -d out app/src/main/java/com/audiomixing/app/ChannelRouter.java \
 *       tools/ChannelRouterTest.java
 * java -cp out com.audiomixing.app.ChannelRouterTest
 * </pre>
 */
public final class ChannelRouterTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        // 1. 默认状态：一个样本都不该改
        ChannelRouter r = new ChannelRouter();
        byte[] pcm = frame(12000, -8000);
        r.apply(pcm, pcm.length);
        check("默认立体声不改样本", sample(pcm, 0) == 12000 && sample(pcm, 1) == -8000);

        // 2. 静音：两个声道都归零（2026-11-12 起这一格取代了原来的「单声道」）
        r.setMode(ChannelRouter.MODE_MUTE);
        pcm = frame(12000, -8000);
        r.apply(pcm, pcm.length);
        check("静音：两边都清零", sample(pcm, 0) == 0 && sample(pcm, 1) == 0);

        // 2b. 静音叠在左右音量上也不该有任何残留（先归零，再乘系数）
        r.setMode(ChannelRouter.MODE_MUTE);
        r.setLeftGain(0.5f);
        pcm = frame(30000, 30000);
        r.apply(pcm, pcm.length);
        check("静音 + 音量：仍是全零", sample(pcm, 0) == 0 && sample(pcm, 1) == 0);
        r.setLeftGain(1f);

        // 3. 仅左 / 仅右 / 互换
        r.setMode(ChannelRouter.MODE_LEFT_ONLY);
        pcm = frame(12000, -8000);
        r.apply(pcm, pcm.length);
        check("仅左：右清零", sample(pcm, 0) == 12000 && sample(pcm, 1) == 0);

        r.setMode(ChannelRouter.MODE_RIGHT_ONLY);
        pcm = frame(12000, -8000);
        r.apply(pcm, pcm.length);
        check("仅右：左清零", sample(pcm, 0) == 0 && sample(pcm, 1) == -8000);

        // 3.5 ★ 2026-11-12：「左右互换」换成了「传送静音」—— 输出必须整段清零
        //     （清零 = 与系统音量无关，一定哑；采集/发送不经过这里，所以照跑）
        r.setMode(ChannelRouter.MODE_SEND_MUTE);
        pcm = frame(12000, -8000);
        r.apply(pcm, pcm.length);
        check("传送静音：两边全零", sample(pcm, 0) == 0 && sample(pcm, 1) == 0);

        // 4. 平衡：右 50% 时只压左
        r.reset();
        r.setBalance(0.5f);
        pcm = frame(10000, 10000);
        r.apply(pcm, pcm.length);
        check("平衡偏右压左", Math.abs(sample(pcm, 0) - 5000) <= 1
                && Math.abs(sample(pcm, 1) - 10000) <= 1);

        r.setBalance(-1f);
        pcm = frame(10000, 10000);
        r.apply(pcm, pcm.length);
        check("平衡全左：右为 0", sample(pcm, 0) == 10000 && sample(pcm, 1) == 0);

        // 5. 左右独立音量
        r.reset();
        r.setLeftGain(0.25f);
        r.setRightGain(1f);
        pcm = frame(20000, 20000);
        r.apply(pcm, pcm.length);
        check("左右独立音量", Math.abs(sample(pcm, 0) - 5000) <= 1
                && sample(pcm, 1) == 20000);

        // 6. 削顶：增益不设上限时也不能回绕成负数（本类 setter 已夹到 0..1，
        //    这里用"仅左"叠加满幅信号验证 clip 分支不会溢出）
        r.reset();
        r.setMode(ChannelRouter.MODE_LEFT_ONLY);
        pcm = frame(32767, 32767);
        r.apply(pcm, pcm.length);
        check("满幅不溢出", sample(pcm, 0) == 32767 && sample(pcm, 1) == 0);

        // 7. 按设备分配：只进左 / 只进右
        byte[] src = frame(10000, 4000);
        byte[] dst = new byte[4];
        ChannelRouter.routeAndScale(src, dst, 4, 1f, ChannelRouter.CH_LEFT);
        check("按设备→左耳", sample(dst, 0) == 7000 && sample(dst, 1) == 0);

        dst = new byte[4];
        ChannelRouter.routeAndScale(src, dst, 4, 1f, ChannelRouter.CH_RIGHT);
        check("按设备→右耳", sample(dst, 0) == 0 && sample(dst, 1) == 7000);

        dst = new byte[4];
        ChannelRouter.routeAndScale(src, dst, 4, 0.5f, ChannelRouter.CH_BOTH);
        check("按设备→双耳 + 增益", sample(dst, 0) == 5000 && sample(dst, 1) == 2000);

        // 8. 两级串联：设备 A 左、设备 B 右，混音后仍各占一边
        byte[] a = frame(10000, 10000);
        byte[] b = frame(6000, 6000);
        byte[] mix = new byte[4];
        ChannelRouter.routeAndScale(a, mix, 4, 1f, ChannelRouter.CH_LEFT);
        byte[] tmp = new byte[4];
        ChannelRouter.routeAndScale(b, tmp, 4, 1f, ChannelRouter.CH_RIGHT);
        for (int i = 0; i < 4; i += 2) {
            int v = sample(mix, i / 2) + sample(tmp, i / 2);
            write(mix, i / 2, v);
        }
        check("两台各占一只耳朵", sample(mix, 0) == 10000 && sample(mix, 1) == 6000);

        // 9. reset 恢复默认
        r.setMode(ChannelRouter.MODE_SEND_MUTE);
        r.setBalance(1f);
        r.setLeftGain(0f);
        r.reset();
        check("reset 还原", r.mode() == ChannelRouter.MODE_STEREO
                && r.balance() == 0f && r.leftGain() == 1f && r.rightGain() == 1f);

        System.out.println("ChannelRouterTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** 造一帧交错立体声 PCM16。 */
    private static byte[] frame(int l, int r) {
        byte[] b = new byte[4];
        write(b, 0, l);
        write(b, 1, r);
        return b;
    }

    private static int sample(byte[] b, int index) {
        int i = index * 2;
        return (short) ((b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8));
    }

    private static void write(byte[] b, int index, int v) {
        int i = index * 2;
        if (v > 32767) v = 32767;
        if (v < -32768) v = -32768;
        b[i] = (byte) v;
        b[i + 1] = (byte) (v >> 8);
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  PASS  " + name);
        } else {
            failed++;
            System.out.println("  FAIL  " + name);
        }
    }
}
