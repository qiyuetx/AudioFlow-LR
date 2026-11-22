package com.audiomixing.app;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * ★ 2026-11-14（用户要求）：把关键事件写到内部文件，事后导出诊断用。
 *
 * <p>为什么需要：故障“偶尔才出现”，而 adb logcat 在盒子上只能维持 40~55 秒就断，
 * 抓不到长窗口。把事件落盘后，事后再导出分析，就不依赖长连 adb 了。
 *
 * <p>文件：{@code getExternalFilesDir(null)/am-events.log}
 * （Android 5.1 的盒子可直接
 * {@code adb pull /sdcard/Android/data/com.audiomixing.app/files/am-events.log}）；
 * 拿不到外部目录时退回 {@code filesDir/am-events.log}。
 *
 * <p>只记关键事件（启动 / 断流重置 / 压延迟丢帧 / 写失败 / 每 5 秒缓冲快照），不记每包。
 * 超过 512KB 自动清空重来。任何异常都吞掉，绝不影响音频。
 *
 * <p>★ 这是临时诊断功能，问题解决后按用户要求删除。
 */
public final class EventLog {

    private static final String TAG = "AM-EventLog";
    private static final long MAX_BYTES = 512 * 1024;
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
    private static final Object LOCK = new Object();
    private static File file;
    private static boolean initTried;

    private EventLog() {
    }

    public static void init(Context ctx) {
        if (initTried || ctx == null) {
            return;
        }
        initTried = true;
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) {
                dir = ctx.getFilesDir();
            }
            if (dir != null) {
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                file = new File(dir, "am-events.log");
            }
            Log.i(TAG, "事件日志：" + (file == null ? "无" : file.getAbsolutePath()));
        } catch (Throwable t) {
            Log.w(TAG, "初始化失败", t);
        }
    }

    /** 文件绝对路径（诊断页显示用）；没初始化返回空串。 */
    public static String path() {
        File f = file;
        return f == null ? "" : f.getAbsolutePath();
    }

    /** 记一条。tag 用短词（RX/PLAY/SNAP），msg 写清关键数字。 */
    public static void log(String tag, String msg) {
        File f = file;
        if (f == null) {
            return;
        }
        synchronized (LOCK) {
            try {
                if (f.length() > MAX_BYTES) {
                    FileWriter clear = new FileWriter(f, false);
                    clear.close();
                }
                FileWriter w = new FileWriter(f, true);
                w.write(FMT.format(new Date()));
                w.write(' ');
                w.write(tag);
                w.write(' ');
                w.write(msg);
                w.write('\n');
                w.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
