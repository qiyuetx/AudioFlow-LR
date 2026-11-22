package com.audiomixing.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

/**
 * 开机自启 + 后台常驻的落地实现（2026-11-12 用户要求：「能不能加入开机自动启动加后台保活」）。
 *
 * <p><b>先说清安卓这边能做到什么、做不到什么</b>（不然用户会以为"开了就一定不死"）：
 * <ol>
 *   <li><b>前台服务</b>（{@link MixService}，已在跑，带常驻通知）—— 这是唯一"合法常驻"的通道，
 *       锁屏、切后台都不会停；</li>
 *   <li><b>电池优化白名单</b> —— 不加的话国产 ROM 的省电策略会在息屏一段时间后掐掉服务。
 *       这一条要用户在系统弹窗里点一次"允许"，App 只能请求、不能代替；</li>
 *   <li><b>厂商自启动白名单</b>（小米/红米、vivo/iQOO、OPPO/一加、华为/荣耀）—— 各家自己一套，
 *       没有公开 API，只能帮用户跳到对应的设置页，让用户点一下；</li>
 *   <li><b>开机自启</b>（{@link BootReceiver}）—— 收到 BOOT_COMPLETED 直接起前台服务，
 *       这条在 Android 12+ 仍然被允许（开机广播是官方豁免场景之一）。</li>
 * </ol>
 *
 * <p>做不到的部分老实说：Android 12 起，App 在后台<b>不允许</b>随意拉起前台服务；
 * 所以"被系统杀掉之后自己爬起来"没有任何可靠做法（AlarmManager/JobScheduler 都会被拦）。
 * 真正有效的组合只有上面三条 —— 这也是为什么这里做的是"引导用户去点"，而不是假装能自愈。
 */
public final class KeepAlive {

    private static final String TAG = "AudioMixing";
    private static final String PREF = "audiomixing";
    /** 开机自启开关（默认开 —— 用户要的就是"开机就跑"）。 */
    private static final String KEY_AUTO_START = "boot_auto_start";

    private KeepAlive() {
    }

    // ────────────────────────────── 开机自启 ──────────────────────────────

    /** 开机是否自动启动。默认 true。 */
    public static boolean autoStart(Context ctx) {
        if (ctx == null) {
            return true;
        }
        try {
            return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getBoolean(KEY_AUTO_START, true);
        } catch (Throwable t) {
            return true;
        }
    }

    public static void setAutoStart(Context ctx, boolean on) {
        if (ctx == null) {
            return;
        }
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_AUTO_START, on).apply();
        } catch (Throwable t) {
            Log.w(TAG, "保存开机自启开关失败", t);
        }
    }

    // ────────────────────────── 电池优化白名单 ──────────────────────────

    /** 本 App 是否已经在"不优化"白名单里。API 23 以下没有这个概念，直接当已加。 */
    public static boolean ignoringBattery(Context ctx) {
        if (ctx == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 弹系统的"允许忽略电池优化"确认框。
     *
     * @return true = 弹窗已经弹出去了（用户点完回到 App 会看到状态变化）；
     *         false = 这台机器不认这个请求（老系统/被定制掉），调用方该退到应用详情页
     */
    public static boolean requestIgnoreBattery(Activity a) {
        if (a == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + a.getPackageName()));
            a.startActivity(i);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "忽略电池优化的弹窗打不开，退到列表页", t);
        }
        try {
            a.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "电池优化列表也打不开，退到应用详情", t);
        }
        return openAppDetails(a);
    }

    // ─────────────────────── 厂商「自启动」设置页 ───────────────────────

    /** 当前 ROM 名字（给界面文案用，认不出来就写"这台手机"）。 */
    public static String romName() {
        String brand = Build.BRAND == null ? "" : Build.BRAND.toLowerCase(java.util.Locale.US);
        String man = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase(java.util.Locale.US);
        String all = brand + " " + man;
        if (all.contains("xiaomi") || all.contains("redmi") || all.contains("poco")) {
            return "小米/红米";
        }
        if (all.contains("vivo") || all.contains("iqoo")) {
            return "vivo/iQOO";
        }
        if (all.contains("oppo") || all.contains("realme") || all.contains("oneplus")) {
            return "OPPO/一加/realme";
        }
        if (all.contains("huawei") || all.contains("honor")) {
            return "华为/荣耀";
        }
        if (all.contains("samsung")) {
            return "三星";
        }
        return "";
    }

    /**
     * 跳到这家 ROM 的"自启动 / 后台管理"页。
     *
     * <p>逐条试：不同型号的组件名不完全一样，试不到就退到"应用详情"（那里至少能看权限、
     * 也能进电池选项）。<b>不假装成功</b>：一个都没打开就返回 false，界面如实提示。
     */
    public static boolean openAutoStartSettings(Activity a) {
        if (a == null) {
            return false;
        }
        String[][] candidates = {
                // 小米 / 红米 / POCO
                {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                // vivo / iQOO
                {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
                // OPPO / 一加 / realme
                {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
                {"com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"},
                // 华为 / 荣耀
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
                // 三星
                {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
                // 通用兜底：部分原生/接近原生的 ROM 有这个
                {"com.android.settings", "com.android.settings.Settings$HighPowerApplicationsActivity"},
        };
        for (String[] c : candidates) {
            try {
                Intent i = new Intent();
                i.setComponent(new ComponentName(c[0], c[1]));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                a.startActivity(i);
                Log.i(TAG, "已打开自启动设置页：" + c[0] + "/" + c[1]);
                return true;
            } catch (Throwable ignored) {
                // 这台机器没有这个组件：试下一个
            }
        }
        Log.w(TAG, "这台机器没找到已知的自启动设置页，退到应用详情");
        return openAppDetails(a);
    }

    /** 应用详情页（最差的兜底，但一定打得开）。 */
    public static boolean openAppDetails(Activity a) {
        if (a == null) {
            return false;
        }
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + a.getPackageName()));
            a.startActivity(i);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "应用详情页也打不开", t);
            return false;
        }
    }

    /** 给界面的一句话状态：现在到底"能不能在后台活着"。 */
    public static String stateText(Context ctx) {
        if (ctx == null) {
            return "";
        }
        boolean battery = ignoringBattery(ctx);
        boolean auto = autoStart(ctx);
        return "开机自启：" + (auto ? "开" : "关")
                + " · 电池优化白名单：" + (battery ? "已加入 ✓" : "还没加 ✗");
    }
}
