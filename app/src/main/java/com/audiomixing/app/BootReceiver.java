package com.audiomixing.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机自启（2026-11-12 用户：「能不能加入开机自动启动」）。
 *
 * <p>收到开机广播 → 直接起 {@link MixService}（前台服务，带常驻通知）。服务的
 * {@code onCreate} 会把 {@link Engine} 拉起来，于是"开机就在线、别人能直接听到这台"，
 * 不用先解锁点一次 App。
 *
 * <p><b>为什么可以这么做</b>：Android 8 起禁止后台随便起服务，但 BOOT_COMPLETED 是官方
 * 豁免场景；Android 12 起对"后台起前台服务"的收紧也明确把开机广播排除在外。所以这条路
 * 在新系统上依然成立 —— 前提是用户没在系统里关掉本 App 的"自启动"。
 *
 * <p><b>为什么还要监听 QUICKBOOT_POWERON</b>：部分国产 ROM（老小米、部分 MTK 机器）发的不是
 * 标准 BOOT_COMPLETED，而是这个厂商自定义广播；两个都收的兼容性最好。
 *
 * <p><b>MY_PACKAGE_REPLACED</b>：覆盖安装后把服务重新拉起来，否则用户装完新版本，
 * 要等下次手动打开 App 才会恢复在线。
 */
public final class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "AudioMixing";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (ctx == null) {
            return;
        }
        String action = (intent == null || intent.getAction() == null) ? "" : intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (!boot) {
            return;
        }
        if (!KeepAlive.autoStart(ctx)) {
            Log.i(TAG, "开机自启已在设置里关掉，跳过：" + action);
            return;
        }
        Log.i(TAG, "收到 " + action + "：自动拉起前台服务");
        // safeStart 内部已经 catch（起不来只记日志），这里不需要再兜一层。
        MixService.safeStart(ctx);
    }
}
