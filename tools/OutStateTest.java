package com.audiomixing.app;

import java.nio.charset.StandardCharsets;

/**
 * 「本机播放状态」上报 / 解析的离线门禁（2026-11-12）。
 *
 * <p>为什么单独一个测试：用户第 1、3 条要求（远程改别的设备的声道、控制别的设备的音量）
 * 全靠这一段——
 * <ul>
 *   <li>发送侧：{@link Diagnosis#outStateJson} 拼进统计包的两个键；</li>
 *   <li>接收侧：{@link Diagnosis#parse} 把 {@code out_ch} / {@code out_vol} 读出来。</li>
 * </ul>
 * 这两边一旦对不上，界面表现就是"按钮永远不亮"（读不到）或"值永远是 0"（默认值混进真值），
 * 而真机上要复现得两台手机同时在线、还得盯着屏幕等 2 秒 —— 不如在这里一条条断言。
 *
 * <p>运行（build_apk.py 只跑 ChannelRouterTest，这个要手工跑）：
 * <pre>
 * javac -encoding UTF-8 -cp &lt;android.jar&gt; -d out \
 *       app/src/main/java/com/audiomixing/app/{ChannelRouter,LinkPolicy,Diagnosis}.java \
 *       tools/OutStateTest.java
 * java -cp out:&lt;android.jar&gt; com.audiomixing.app.OutStateTest
 * </pre>
 *
 * <p>注意：{@code Diagnosis} 里有 {@code import android.*}，编译/运行都要带上 android.jar；
 * 本测试只调用纯解析的静态方法，不会碰到任何 android 实现（那些是 throw 的桩）。
 */
public final class OutStateTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        // 1. 片段形状：必须以逗号开头（要拼到现有 JSON 的 } 前面），键名和值都要对
        String frag = Diagnosis.outStateJson(ChannelRouter.MODE_MUTE, 40);
        check("片段以逗号开头", frag.startsWith(","));
        check("静音上报为 out_ch=1", frag.contains("\"out_ch\":1"));
        check("音量上报为 out_vol=40", frag.contains("\"out_vol\":40"));

        // 2. 越界的值要被夹住，绝不能把非法模式发给别人（对面会照着它改自己的输出）
        String clamped = Diagnosis.outStateJson(99, 250);
        check("非法模式回到立体声", clamped.contains("\"out_ch\":0"));
        check("音量上限 100", clamped.contains("\"out_vol\":100"));
        String neg = Diagnosis.outStateJson(-5, -30);
        check("负数音量夹到 0", neg.contains("\"out_vol\":0"));
        check("负数模式回到立体声", neg.contains("\"out_ch\":0"));

        // 3. 真实链路：Engine 就是这么把片段接在统计 JSON 尾巴上的
        String json = "{\"t\":\"stats\",\"node\":\"abc\",\"name\":\"vivo\",\"sent\":1,\"bytes\":2,"
                + "\"sending\":true,\"recv\":3,\"lost\":0,\"buf_ms\":120,\"streams\":1,\"target\":\"\""
                + Diagnosis.outStateJson(ChannelRouter.MODE_LEFT_ONLY, 35) + "}";
        Diagnosis.PeerStats ps = Diagnosis.parse(json.getBytes(StandardCharsets.UTF_8));
        check("拼进去能解析出来", ps != null);
        check("out_ch = 仅左", ps != null && ps.outCh == ChannelRouter.MODE_LEFT_ONLY);
        check("out_vol = 35", ps != null && ps.outVol == 35);
        check("原有字段没被挤坏", ps != null && ps.sent == 1 && ps.bufMs == 120);

        // 4. 老版本/电脑端的包没有这两个键 → 必须是 -1（"没上报"），不能是 0（会被画成"立体声 0%"）
        Diagnosis.PeerStats old = Diagnosis.parse(
                "{\"t\":\"stats\",\"name\":\"pc\",\"sent\":5}".getBytes(StandardCharsets.UTF_8));
        check("老包的 out_ch = -1", old != null && old.outCh == -1);
        check("老包的 out_vol = -1", old != null && old.outVol == -1);

        // 5. 远程控制消息不能被误判成统计包（否则会被塞进"对端统计"，界面上多出一台乱报的设备）
        Diagnosis.PeerStats ctl = Diagnosis.parse(
                "{\"t\":\"out_ctl\",\"ch\":2,\"vol\":40}".getBytes(StandardCharsets.UTF_8));
        check("远程消息不是统计包", ctl == null);

        System.out.println("OutStateTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
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
