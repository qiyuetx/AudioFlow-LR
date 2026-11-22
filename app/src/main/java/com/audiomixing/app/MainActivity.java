package com.audiomixing.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.text.InputType;
import android.util.Log;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 界面：打开就能看见局域网里的设备，点一下就能用。
 *
 * <p><b>没有配对、没有房间号、没有二维码</b> —— 用户原话是"一个局域网下的没什么意义"。
 * 设备列表完全来自 {@link Discovery} 的自主发现：谁在发信标，谁的卡片就出现；
 * 15 秒没信标就自己消失。手机之间直连，电脑关掉照常工作。
 *
 * <p>每一行两个开关：
 * <ul>
 *   <li><b>🎧 收听</b>：收这台发来的声音（可以同时收听多台，声音会混在一起）。</li>
 *   <li><b>🔊 送声音</b>：把<b>本机正在播放的声音</b>送给这台（音乐/视频/游戏）——
 *       这是这个 App 本来的用法；切成 🎤 麦克风就是对讲。</li>
 * </ul>
 *
 * <p><b>★ 为什么"送什么声音"要做成显式可选的</b>：早期版本只采麦克风，用户的原话是
 * 「你这手机都是采集的麦克风的声音，那不成对讲机了吗？我的需求一直都是桌面音频啊」。
 * 所以这个页面上：①音源选择放在最显眼的位置；②下面那行<b>永远写"实际在送的是哪一个"</b>；
 * ③限制（安卓 10 以下没有这个能力、DRM/主动禁止采集的内容采不到、部分 ROM 阉割）都写在界面上。
 *
 * <p>★ 2026-10-19 用户第 1/2/3/4 条：主控区那两颗音源按钮（🔊 本机声音 / 🎤 麦克风）现在是
 * <b>真正的 toggle</b>：视觉状态每次刷新都从引擎的<b>真实状态</b>读（见 {@link Engine#sourceState}），
 * 点未激活的 = 激活、点已激活的 = 关闭。两个音源<b>互斥</b>（同一时刻只能采一路）——
 * 点麦克风时「本机声音」那颗会真地灭掉，并在状态行里写明原因。<b>绝不自动开麦</b>：
 * 本机声音采集停止/授权被收回时一律显示"已停止 + 原因"，麦克风只由用户主动点击才开。
 *
 * <p>视觉对齐 {@code audiomixing/ui/phone.html}：深色底 + 圆角卡片 + 大状态 + 电平条。
 * 工程零依赖（没有 appcompat），所以界面全部用代码搭、圆角用 {@link GradientDrawable} 画。
 */
public class MainActivity extends Activity {

    // ── 配色：和手机网页版 phone.html 同一套 ──
    private static final int BG = 0xFF070B12;
    private static final int FG = 0xFFEAF1FB;
    private static final int DIM = 0xFF8EA0B8;
    private static final int ACC = 0xFF38BDF8;
    private static final int ACC2 = 0xFF6366F1;
    private static final int OK = 0xFF34D399;
    private static final int WARN = 0xFFFBBF24;
    private static final int CARD_BG = 0x0BFFFFFF;   // rgba(255,255,255,.045)
    private static final int LINE = 0x17FFFFFF;      // rgba(255,255,255,.09)
    private static final int TRACK = 0x1AFFFFFF;     // 电平条底槽
    private static final int ON_TEXT = 0xFF04121F;   // 亮色按钮上的深色字

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static final int REQ_MIC = 1001;
    private static final int REQ_NOTIF = 1002;
    /** ★ 「采本机声音」的系统授权窗口（MediaProjection 的一次性许可）。 */
    private static final int REQ_PROJ = 1003;
    /** 界面刷新间隔：150ms 足够跟手，又不会一直在重画。 */
    private static final long TICK_MS = 150L;

    private Engine engine;
    private Handler ui;

    private LinearLayout listBox;
    private TextView pill;
    private LinearLayout detailWrap;
    /** 头部那行"本机名字 · 平台"（点它就能改名）。 */
    private TextView subLine;
    // ★ 主控大块里分成"三档都能看见"和"只有详细档才有"的两部分
    /** 音源的实时状态（"现在实际送的是哪一个" + 静音提醒）：补充信息，只在详细档露出。 */
    private LinearLayout sourceInfoWrap;
    /** 「🚀 快速上手 / ✏️ 改名」：只在详细档出现（简洁档一个多余的字都不要）。 */
    private LinearLayout detailOnlyRow;
    // ★ 音源
    private TextView playChip;
    private TextView micChip;
    private TextView sourceNow;
    private TextView sourceWarn;
    private String lastSrcSig;
    // ★ 「听广播」（频道模式）
    private TextView channelChip;
    private TextView channelNow;
    private boolean lastChannelOn;
    // ★ 「📢 广播我的声音」（发出去）
    private TextView broadcastChip;
    private TextView broadcastNow;
    private boolean lastBroadcastOn;
    // ★ ④ 安全停发提示（"已停止向 XXX 发送…"，和自动收听是两件事，保留）。
    //   ★ 2026-10-18 用户第 6 条：全局的「自动收听路由声音」开关及其提示/一键收回已整段删掉 ——
    //     自动听不听改由每台设备卡片上的第 4 颗「智连」按钮各自决定（默认关），
    //     不再有能"随机触发"的全局开关，也就没有需要解释/收回的东西。
    private TextView suspendedLine;

    private final Map<String, PeerRow> rows = new LinkedHashMap<String, PeerRow>();
    private final Map<String, TextView> kvValues = new LinkedHashMap<String, TextView>();

    /** null 表示还没建过列表，这样第一次刷新一定会把"搜索中"的空状态画出来。 */
    private String lastSig;
    private boolean ticking;
    private int tickCount;
    /** 等权限 / 等投屏授权回来之后再开始送的那台设备。 */
    private Discovery.Peer pendingSend;

    // ══════════════════ ★ 三档浏览模式：简洁 / 详细 / 监控 ══════════════════
    //
    // 用户原话：「补充：手机app也应当设置监控/简洁/详细，三个页面。」
    // 三档的定位（和电脑端、和用户统一的口径，名字不再改）：
    //   · 简洁：只留最关键的东西 —— 打开就知道点哪里
    //   · 详细：本机信息 + 每台的完整信息 + 所有说明文字，常驻展开
    //   · 监控：只读的实时数字面板（收发/丢包/坏包/缓冲/带宽/逐流/发现层自检），一屏读完
    //
    // ★ 2026-10-15 用户把这条规则说死了：「简洁模式下所有的标注补充式文本全部不要」，
    //   以及「（快速上手/改名/详情/展开说明）全部删掉，这些详细页全部都有还不常用，
    //   凭什么出现在简洁页面」。所以「简洁」现在是【真的简洁】：一句说明、一个多余入口都没有
    //   （连"❔ 展开说明"那个按钮也一起去掉了 —— 有它在，简洁档就等于还没简洁）。
    // ★ 同一份数据三种呈现：三档全部复用 Engine 的查询接口，没有第二套取数逻辑。
    private static final int MODE_SIMPLE = 0;
    private static final int MODE_DETAIL = 1;
    private static final int MODE_MONITOR = 2;
    private static final String PREFS_NAME = "audiomixing";
    private static final String KEY_VIEW_MODE = "view_mode";

    private int viewMode = MODE_SIMPLE;
    private TextView modeSimple;
    private TextView modeDetail;
    private TextView modeMonitor;

    // ── ★ 2026-11-15 双音箱环绕摆动 ──
    /** 顶部第 4 个入口。文字恒为「环绕」，**状态只靠高亮色表达**（运行中 = 强调色）。 */
    private TextView modeSurround;
    /** 上一次画出的环绕状态（避免每 600ms 重建一次渐变背景）。 */
    private boolean lastSurroundOnState;
    /** 面板是否展开。点顶部【环绕】只切它，不启停环绕（防误触）。 */
    private boolean surroundPanelOpen;
    private LinearLayout surroundPanel;
    /** 配对两侧的显示块（显示当前选中的设备名，点击弹列表重选）。 */
    private TextView surroundChipA;
    private TextView surroundChipB;
    /** 面板里真正启停环绕的大按钮。 */
    private TextView surroundToggleBtn;
    private SeekBar surroundSpeedBar;
    private TextView surroundSpeedVal;
    /** 主面板那行区间摘要（当前"真正生效"的那套区间）。 */
    private TextView surroundRangeVal;
    // ── ★ 2026-11-15 追加：共用区间 / 独立逐台 两套 UI + 模式 chips ──
    /** 「共用区间」「详细（逐台）」两个 chip。 */
    private TextView[] surroundModeChips;
    /** 外层共用区间那一整块（独立模式下 alpha 压暗 + 禁用）。 */
    private LinearLayout surroundSharedRow;
    private TextView surroundSharedMinLabel;
    private TextView surroundSharedMaxLabel;
    private SeekBar surroundSharedMinBar;
    private SeekBar surroundSharedMaxBar;
    /** 子面板里 A 那一组（与 B 一样，共用模式下整组置灰）。 */
    private LinearLayout surroundGroupA;
    /** 逐台模式下音量键的说明行（不是"整体音量"，只是操作提示）。 */
    private TextView surroundMasterVal;
    // ── ★ 2026-11-15 追加：曲线 / 端点停留 / 实时数值 / 暂停提示 ──
    /** 「线性 / 正弦」两个 chip。 */
    private TextView[] surroundCurveChips;
    /** 「两端停留」滑杆行。★ 追加需求 2：两种曲线都显示、都生效。 */
    private LinearLayout surroundHoldRow;
    private SeekBar surroundHoldBar;
    private TextView surroundHoldVal;
    // ── ★ 2026-11-15 追加需求 3：可折叠的「逐台音量区间」子面板 ──
    /** 子面板是否展开（默认收起，省页面空间）。 */
    private boolean surroundSubOpen;
    /** 折叠按钮本身（点它才展开/收起子面板）。 */
    private TextView surroundSubToggle;
    /** 子面板容器。 */
    private LinearLayout surroundSubPanel;
    /** B 那一整组（B 未选时整组隐藏 —— 单台模式只配 A 一台）。 */
    private LinearLayout surroundGroupB;
    private TextView surroundLabelA;
    private TextView surroundLabelB;
    /** A 的上下限滑杆与标签（标签实时显示百分比）。 */
    private SeekBar surroundMinBarA;
    private SeekBar surroundMaxBarA;
    private TextView surroundMinLabelA;
    private TextView surroundMaxLabelA;
    /** B 的上下限滑杆与标签（与 A 完全独立）。 */
    private SeekBar surroundMinBarB;
    private SeekBar surroundMaxBarB;
    private TextView surroundMinLabelB;
    private TextView surroundMaxLabelB;
    /** 面板里那行状态（广播暂停时会写出来）。 */
    private TextView surroundStatus;
    /** ★ 2026-11-12：后台常驻 / 开机自启卡片（只在「详细」档出现）。 */
    private View keepAliveCard;
    private TextView keepAliveState;
    private TextView keepAutoChip;
    private TextView keepBatteryChip;

    /** ★ 2026-11-12：监控档改可视化后不再是一整段等宽文字，而是一格一格的读数 + 电平条。 */
    private View monitorCard;
    private TextView[] monitorKv;
    private LinearLayout monitorRowsBox;
    /** ★ 2026-11-13 用户：「监控里面也应该加入我广播本机的状态监控」—— 顶部的本机广播行。 */
    private TextView monSelfStatus;
    private LevelBar monSelfBar;
    private TextView monSelfTail;
    /** ★ 2026-11-13（用户）：监控行按角色显示 —— 广播方显示「送」，接收方显示「收」。 */
    private TextView monSelfTitle;
    private TextView monSelfBarLabel;
    private final List<MonRow> monRows = new ArrayList<MonRow>();
    private View channelCard;
    private View hintCard;
    /** ★ 合并版：左右声道控制面板（本机播放的全局矩阵 + 测试音）。 */
    private View stereoCard;
    private TextView stereoState;
    /** ★ 2026-11-12：本机播放音量（0~100%）—— 左右声道卡片里唯一的那根滑杆。 */
    private TextView volLabel;
    private SeekBar volBar;
    /** ★ 2026-11-13：本机输出延迟（0~300ms）—— 别的手机可以远程改它（out_ctl delay）。 */
    private SeekBar localDelayBar;
    /** ★ 2026-11-14：缓冲档位 chip（固定渲染 700/1000/1500；NACK 模式下整排灰掉）。 */
    private TextView[] bufChips;
    /** ★ 2026-11-17：缓冲档位那一行的小标题（NACK 模式下改文案说明「固定 500ms」）。 */
    private TextView bufHeaderText;
    /** ★ 2026-11-17：补包协议切换的 chip（老协议 / NACK），默认老协议。 */
    private TextView nackChipOld;
    private TextView nackChipNew;
    /** ★ 2026-11-13（用户：「拖动一格改成 5，不是 1」）：延迟滑杆一格 = 5ms。
     *  SeekBar 内部进度按「格」存，显示/下发时统一乘这个步长。 */
    private static final int DELAY_STEP_MS = 5;

    private TextView localDelayLabel;
    private final TextView[] stereoModeChips = new TextView[5];
    /** 说明性长文字：只有「详细」档才显示（简洁档一个补充字都不要）。 */
    private final List<View> longTextViews = new ArrayList<View>();
    /**
     * 每次换设备列表（{@link #rebuild}）都会重建的那几块长说明（空列表状态卡片里的）。
     * 单独放一个列表、每次 rebuild 清空，免得把已经丢弃的旧 View 一直留在集合里（那是个内存泄漏）。
     */
    private final List<View> dynLongTextViews = new ArrayList<View>();

    /** 三档切换（分段控件，和音源那两格同一套视觉）。 */
    private void setViewMode(int mode) {
        viewMode = mode;
        saveViewMode();
        applyMode();
        if (mode == MODE_MONITOR) {
            refreshMonitor();      // 切过去立刻有内容，不等 300ms
        }
        refreshNow();
    }

    private void saveViewMode() {
        try {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putInt(KEY_VIEW_MODE, viewMode).apply();
        } catch (Throwable ignored) {
            // 存不了就只是"下次打开回到默认档"，不影响使用
        }
    }

    private int loadViewMode() {
        try {
            int m = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getInt(KEY_VIEW_MODE, MODE_SIMPLE);
            return (m == MODE_DETAIL || m == MODE_MONITOR) ? m : MODE_SIMPLE;
        } catch (Throwable t) {
            return MODE_SIMPLE;      // 默认「简洁」：第一次用的人最需要"点哪里"
        }
    }

    /**
     * 说明性长文字该不该露出来：<b>只有「详细」档露</b>。
     *
     * <p>★ 2026-10-15：原来「简洁」档也能靠「❔ 展开说明」把这些放出来 —— 用户把这条否了：
     * 「简洁模式下所有的标注补充式文本全部不要」。而那个展开按钮本身就是"简洁页上多出来
     * 的一个东西"，所以按钮一起去掉：简洁档从此一句补充说明都没有。
     */
    private boolean longTextVisible() {
        return viewMode == MODE_DETAIL;
    }

    /** 把一块解释性长说明登记进"只有详细档才显示"的集合，并按当前档位立刻定一次可见性。 */
    private void registerLong(List<View> into, View v) {
        into.add(v);
        v.setVisibility(longTextVisible() ? View.VISIBLE : View.GONE);
    }

    /** 按当前档位切换"显示什么"—— 数据一份，呈现三样。 */
    private void applyMode() {
        boolean monitor = viewMode == MODE_MONITOR;
        boolean detail = viewMode == MODE_DETAIL;
        if (modeSimple != null) {
            styleChip(modeSimple, viewMode == MODE_SIMPLE, "简洁");
            styleChip(modeDetail, detail, "详细");
            styleChip(modeMonitor, monitor, "监控");
        }
        // ★ 2026-11-15：顶部【环绕】的高亮**只表示环绕正在运行**，和当前是简洁/详细/监控无关。
        //   文字恒为「环绕」，不写"开/关"——用户明确要求状态靠颜色区分。
        if (modeSurround != null) {
            styleChip(modeSurround, engine.surroundOn(), "环绕");
        }
        // 展开的面板：配置期间不藏在监控档后面（那档是只读面板），其余照常显示。
        if (surroundPanel != null) {
            surroundPanel.setVisibility(surroundPanelOpen && !monitor
                    ? View.VISIBLE : View.GONE);
        }
        if (monitorCard != null) {
            monitorCard.setVisibility(monitor ? View.VISIBLE : View.GONE);
        }
        if (channelCard != null) {
            channelCard.setVisibility(monitor ? View.GONE : View.VISIBLE);
        }
        if (stereoCard != null) {
            stereoCard.setVisibility(monitor ? View.GONE : View.VISIBLE);
        }
        if (listBox != null) {
            listBox.setVisibility(monitor ? View.GONE : View.VISIBLE);
        }
        // ★ 音源那两格（本机声音 / 麦克风）：监控档是只读面板，不出现。
        //   诊断那一格照旧常在 —— 它是"看"的入口，不是开关。
        if (playChip != null) {
            playChip.setVisibility(monitor ? View.GONE : View.VISIBLE);
        }
        if (micChip != null) {
            micChip.setVisibility(monitor ? View.GONE : View.VISIBLE);
        }
        // 下面这些全是"补充信息/不常用入口"：只有详细档露（用户原话见类里的那段说明）。
        if (sourceInfoWrap != null) {
            sourceInfoWrap.setVisibility(detail ? View.VISIBLE : View.GONE);
        }
        if (detailOnlyRow != null) {
            detailOnlyRow.setVisibility(detail ? View.VISIBLE : View.GONE);
        }
        if (keepAliveCard != null) {
            keepAliveCard.setVisibility(detail ? View.VISIBLE : View.GONE);
        }
        if (hintCard != null) {
            hintCard.setVisibility(detail ? View.VISIBLE : View.GONE);
        }
        for (View v : longTextViews) {
            v.setVisibility(detail ? View.VISIBLE : View.GONE);
        }
        for (View v : dynLongTextViews) {
            v.setVisibility(detail ? View.VISIBLE : View.GONE);
        }
        if (detailWrap != null) {
            // 「详细」档：本机信息块（IP/端口/统计/对端回话…）常驻展开。
            detailWrap.setVisibility(detail ? View.VISIBLE : View.GONE);
        }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!ticking) {
                return;
            }
            refresh();
            tickCount++;
            if (tickCount % 4 == 0) {
                refreshDetails();     // 详情里的统计没必要 150ms 刷一次
                // ★ 2026-11-14：档位可能被别的设备远程改掉（同步机制），高亮要跟着变，
                //   否则本机界面还亮着旧档位，和实际生效的值不一致。
                // ★ 2026-11-17：补包协议同样会被别的设备远程切换，协议 chip + 档位 chip 一起刷。
                refreshNackChips();
                refreshBufChips();
                // ★ 2026-11-15：环绕面板（配对名/速度/区间/大按钮配色）+ 顶部高亮。
                refreshSurroundUi();
            }
            ui.postDelayed(this, TICK_MS);
        }
    };

    // ────────────────────────────── 生命周期 ──────────────────────────────

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // ★ 2026-11-02：手机音量键要作用在媒体流上。
        //   以前没有 setVolumeControlStream，界面在前台时音量键可能落到铃声/通知流，
        //   表现为"按音量键声音不变"。我们播放用的就是 STREAM_MUSIC（AudioTrack 的
        //   USAGE_MEDIA），这里明确把音量键指向它；绝不主动改系统音量。
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        engine = Engine.get(this);
        ui = new Handler(Looper.getMainLooper());
        // ★ 系统媒体音量现在只在「详细」档和诊断报告里出现（引擎那边读的），
        //   界面上不再单开一行 —— 用户把「送什么声音」那一整块连同它一起删掉了。
        viewMode = loadViewMode();     // ★ 上次看的是哪一档，这次还看哪一档

        Window w = getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);

        setContentView(buildUi());

        engine.start();               // 打开 App 就开始找设备，不需要用户做任何事
        MixService.safeStart(this);   // 常驻通知；服务起不来也不影响界面可用
        askNotificationPermission();  // 安卓 13+ 通知要运行时授权
    }

    @Override
    protected void onResume() {
        super.onResume();
        ticking = true;
        ui.removeCallbacks(tick);
        ui.post(tick);
        // ★ 2026-11-12：从系统的「电池优化 / 自启动」页回来时，状态可能已经变了，
        //   这里重读一次再画（不然用户刚点完"允许"，界面还写着"还没加"）。
        syncKeepAliveUi();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ticking = false;              // 界面不可见就别刷了（音频照跑，那是 Service 的事）
        ui.removeCallbacks(tick);
    }

    /**
     * ★ 2026-11-12 用户：「感觉自带的 app 音量有点小，得手机加音量才行，
     * 但是点击本机静音后，又不能通过音量加减控制其他设备的音量了」。
     *
     * <p>这两句话说的是两件事，所以处理方式也不一样：
     * <ul>
     *   <li><b>没静音时</b>：音量键<b>原样交给系统</b>（返回 false）。这就是"手机加音量"，
     *       谁也不能抢 —— 抢了反而更小声（我们的增益上限只有 1.0，系统媒体音量才是真的后级）。</li>
     *   <li><b>本机哑着时</b>（「静音」/「传送静音」/ 送声第 2 档）：系统音量被钉在 0，
     *       按了等于没按 —— 这时才接管：
     *       <br>· 正在送声/广播 → 调<b>对方</b>的播放音量（发 out_ctl，对方立刻跟着变）；
     *       <br>· 没在送 → 解除静音并把本机音量调上去（否则按钮像坏的）。</li>
     * </ul>
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            // ★ 2026-11-16（用户）：环绕开启 + 逐台模式时，音量键只调 A/B 各自的上限。
            //   注意：这只是「界面在前台时」的行为 —— Activity 的 onKeyDown 只有可见时
            //   才收得到按键。切到后台后不再接管（后台那一路已按用户要求删掉），
            //   那时音量键就是普通的系统音量键。
            if (engine != null && engine.surroundOn() && engine.surroundPerSpeakerMode()) {
                int step = keyCode == KeyEvent.KEYCODE_VOLUME_UP ? 5 : -5;
                int aMax = engine.surroundNudgeVolumeRange(step);
                if (aMax >= 0) {
                    // 直接反馈"上限变了"，不引入任何"整体音量"概念
                    String msg = "A 上限 " + aMax + "%";
                    if (engine.surroundHasB()) {
                        msg += "  ·  B 上限 " + engine.surroundMaxOf(false) + "%";
                    }
                    toast(msg);
                }
                refreshSurroundUi();
                return true;
            }
            int dir = keyCode == KeyEvent.KEYCODE_VOLUME_UP ? 1 : -1;
            if (handleSilentVolumeKey(dir)) {
                return true;      // 已处理：别再让系统同时把系统音量调一遍
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    /** 环绕吃掉按下事件后，抬起也一并吃掉，免得系统在 UP 时又把媒体音量调一格。 */
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
                && engine != null && engine.surroundOn() && engine.surroundPerSpeakerMode()) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    /**
     * 本机哑着的时候，把音量键的意图翻译成"该调谁"。
     *
     * @return true = 这次按键由我们处理掉了
     */
    private boolean handleSilentVolumeKey(int dir) {
        int mode = engine.channelMode();
        boolean outMuted = mode == ChannelRouter.MODE_MUTE || mode == ChannelRouter.MODE_SEND_MUTE;
        boolean sendSilent = engine.sendMode() == 2;      // 送声第 2 档：系统媒体音量被钉在 0
        if (!outMuted && !sendSilent) {
            return false;     // 正常状态：还给系统（"手机加音量"就是这条）
        }
        if (engine.sendingCount() > 0) {
            // ★ 2026-11-16（用户）：相对增减 —— 每台在自己已设音量上各 ±5，不拉平。
            //   ★ 还要显示每台当前音量（像环绕那样），不能只报"+5%"让人猜。
            int step = dir * 5;
            String detail = engine.nudgeRemoteVolumesAll(step);
            toast(detail.isEmpty()
                    ? "这几台不支持远程调音量，要在那台上调"
                    : "音量 " + (step > 0 ? "↑" : "↓") + Math.abs(step) + "%\n"
                            + detail + "（本机不出声）");
            refreshNow();
            return true;
        }
        // 没在送：解除静音，再把本机音量调一格 —— 不然用户按了半天没有任何反应。
        if (outMuted) {
            engine.setChannelMode(ChannelRouter.MODE_STEREO);
        }
        if (engine.sendMode() == 2) {
            engine.setSendMode(1);
        }
        float nv = Math.max(0f, Math.min(1f, engine.outVolume() + dir * 0.1f));
        engine.setOutVolume(nv);
        syncStereoUi();
        toast("已解除静音 · 本机音量 " + Math.round(nv * 100f) + "%");
        refreshNow();
        return true;
    }

    @Override
    protected void onDestroy() {
        // ★ 2026-11-01：用户按返回键/关掉界面（isFinishing）＝"关 App"，
        //   这时必须把前台服务一起停掉，否则它会继续持有 MediaProjection，
        //   系统状态栏一直显示「正在获取屏幕」。
        //   按 Home 或切到别的 App 不算 finishing，后台传输照旧由服务负责。
        if (isFinishing()) {
            try {
                stopService(new Intent(this, MixService.class));
            } catch (Throwable t) {
                Log.w("AM-Main", "关闭界面时停止前台服务失败", t);
            }
        }
        ui.removeCallbacks(tick);
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] granted) {
        super.onRequestPermissionsResult(req, perms, granted);
        if (req != REQ_MIC) {
            return;
        }
        boolean ok = granted.length > 0 && granted[0] == PackageManager.PERMISSION_GRANTED;
        Discovery.Peer p = pendingSend;
        pendingSend = null;
        if (!ok) {
            // 采本机声音也要这个权限（系统对 AudioRecord 一律要求），所以这句话必须说准，
            // 否则用户会以为"我不给麦克风权限就送不了本机声音"是 Bug。
            toast("没有录音权限，送不了声音（采本机声音也需要它，但不会采你说话的声音）");
            engine.cancelPendingSend("没有录音权限");
            refreshNow();
            return;
        }
        // 权限到手了，再叫一次服务把 microphone 这个前台类型补上（安卓 14 要求）。
        // 注意是"再叫一次"而不是 stop+start —— 后者会把刚开起来的送声一起掐掉。
        MixService.safeStart(this);
        if (p != null) {
            startSendFlow(p);     // 接着走：如果选的是本机声音，下一步就是投屏授权
        }
        refreshNow();
    }

    /**
     * ★ 投屏授权的返回。这里<b>不</b>自己换 MediaProjection，而是交给前台服务去做：
     * targetSdk 34 起必须先有带 mediaProjection 类型的前台服务，再调 getMediaProjection，
     * 顺序反了系统直接抛异常。服务那边会把这两步按顺序做完（见 MixService.armProjection）。
     */
    @Override
    protected void onActivityResult(int req, int resultCode, Intent data) {
        super.onActivityResult(req, resultCode, data);
        if (req != REQ_PROJ) {
            return;
        }
        if (resultCode != RESULT_OK || data == null) {
            pendingSend = null;
            engine.cancelPendingSend("你在系统窗口里拒绝了授权");
            toast("没有授权采不到本机声音；也可以改用麦克风");
            refreshNow();
            return;
        }
        MixService.armProjection(this, resultCode, data);
        toast("已授权，正在开始送本机声音…");
        refreshNow();
    }

    // ────────────────────────────── 列表刷新 ──────────────────────────────

    private void refresh() {
        // 服务被停掉（通知里的「停止」）而界面还开着：自动恢复发现，别让用户对着空列表发呆。
        if (!engine.isStarted()) {
            engine.start();
        }
        // 目标设备 15 秒没信标就自动停掉：否则对端走了，麦克风还一直开着，界面上却找不到开关。
        engine.pruneGoneTargets();

        List<Discovery.Peer> peers;
        try {
            peers = engine.peers();
        } catch (Throwable t) {
            peers = new ArrayList<Discovery.Peer>();
        }
        String sig = signature(peers);
        if (lastSig == null || !sig.equals(lastSig)) {
            lastSig = sig;
            rebuild(peers);
        }
        for (PeerRow r : rows.values()) {
            r.sync();
        }
        refreshSourceRow();
        refreshChannelRow();
        // ★ 2026-11-12：别的手机可能远程改了**我这台**的声道模式/音量（用户第 1 条）。
        //   本机这张卡片必须跟着变，否则就是"别人改了、我这边按钮还是旧的"。
        //   只在卡片可见时刷（隐藏时刷了也看不见，白花 CPU）。
        if (stereoCard != null && stereoCard.getVisibility() == View.VISIBLE) {
            syncStereoUi();
        }
        if (subLine != null) {
            // 名字被改过之后，标题下面那行要跟着变（信标 2 秒内带出去，界面立刻变）
            setTextIfChanged(subLine, engine.localName() + " ✏️ · " + engine.platform());
        }
        if (viewMode == MODE_MONITOR && tickCount % 2 == 0) {
            // 监控档每 300ms 刷一次就够（数字跳太快反而看不清）
            refreshMonitor();
        }
        updateHeader(peers.size());
    }

    /** 刷新「听广播 / 广播我的声音」那一块（状态会随"收到几个包"变，所以每 150ms 一起刷）。 */
    private void refreshChannelRow() {
        if (channelNow == null) {
            return;
        }
        boolean on = engine.isChannelListening();
        if (channelChip != null && on != lastChannelOn) {
            lastChannelOn = on;
            styleChip(channelChip, on, "📻 听广播");
        }
        setTextIfChanged(channelNow, engine.channelStateText());
        int color = on ? OK : DIM;
        if (channelNow.getCurrentTextColor() != color) {
            channelNow.setTextColor(color);
        }

        // ★ 发送侧：📢 广播我的声音
        boolean bc = engine.isBroadcasting();
        if (broadcastChip != null && bc != lastBroadcastOn) {
            lastBroadcastOn = bc;
            styleChip(broadcastChip, bc, "📢 广播我的声音");
        }
        if (broadcastNow != null) {
            setTextIfChanged(broadcastNow, engine.broadcastStateText());
            broadcastNow.setVisibility(
                    (bc || engine.sendTargetCount() > 0) ? View.VISIBLE : View.GONE);
            // 目标多的时候用警告色 —— 带宽/分片的代价要显眼，但不拦着用户用
            int bcColor = !bc ? DIM : (engine.sendTargetCount() >= 4 ? WARN : OK);
            if (broadcastNow.getCurrentTextColor() != bcColor) {
                broadcastNow.setTextColor(bcColor);
            }
        }

        // ★ ④ 「已停止向 XXX 发送 —— 它已经 18 秒没有消息了…」
        if (suspendedLine != null) {
            String susp = engine.sendSuspendedText();
            setTextIfChanged(suspendedLine, susp.length() > 0 ? ("⏸ " + susp) : "");
            suspendedLine.setVisibility(susp.length() > 0 ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * 刷新「送什么声音」那一块。
     *
     * <p>为什么每 150ms 都刷：音源会因为"授权被系统收回""设备采不到本机声音"而<b>自己变</b>，
     * 用户看到的必须是当下真实的那一个，而不是他上次点的那个。
     *
     * <p>★ 2026-10-19 用户第 2/4 条：两颗按钮的视觉状态<b>每次都从引擎的真实状态</b>
     * （{@link Engine#sourceActive}）现读，不用任何缓存的标志位 —— 否则就会出现
     * "实际在采麦克风、按钮却还是灰的"。
     */
    private void refreshSourceRow() {
        if (sourceNow == null) {
            return;
        }
        boolean supported = engine.playbackSupported();
        boolean sending = engine.senderRunning();
        int state = engine.sourceState();      // 真实状态：OFF / MIC / PLAYBACK
        // 只在状态真的变了才重建按钮背景（150ms 一次，别一直产生垃圾对象）
        String sig = state + "|" + supported + "|" + sending + "|" + engine.hasProjection();
        if (!sig.equals(lastSrcSig)) {
            lastSrcSig = sig;
            // 低版本把「本机声音」画成没选中的样子：点了会明确告诉你"不支持"，
            // 而不是选中了却什么也采不到（那正是会误导人的地方）。
            styleChip(playChip, supported && engine.sourceActive(AudioSend.SRC_PLAYBACK), "🔊 本机声音");
            styleChip(micChip, engine.sourceActive(AudioSend.SRC_MIC), "🎤 麦克风");
        }
        setTextIfChanged(sourceNow, engine.sourceStateText());
        int color = engine.sourceMismatch() ? WARN : (sending ? OK : DIM);
        if (sourceNow.getCurrentTextColor() != color) {
            sourceNow.setTextColor(color);
        }
        String hint = engine.silenceHintText();
        setTextIfChanged(sourceWarn, hint);
        sourceWarn.setVisibility(hint.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private void refreshNow() {
        refresh();
        refreshDetails();
    }

    private static String signature(List<Discovery.Peer> peers) {
        StringBuilder sb = new StringBuilder();
        for (Discovery.Peer p : peers) {
            // ★ 2026-11-12：outCtl 也要进签名。不然会出现这种真实现场：
            //   这台手机的卡片早就建好了，对面随后升级成 2.23（信标开始声明 outctl:1），
            //   但签名没变 → 卡片不重建 → 「它的输出 / 它的音量」永远不出现，
            //   用户只会看到"左右声道还是有问题"（其实是这一行根本没长出来）。
            sb.append(Engine.idOf(p)).append(p.outCtl ? "+" : "-").append('|');
        }
        return sb.toString();
    }

    private void rebuild(List<Discovery.Peer> peers) {
        listBox.removeAllViews();
        rows.clear();
        dynLongTextViews.clear();      // 空状态卡片里的长说明跟着旧 View 一起丢掉
        if (peers.isEmpty()) {
            listBox.addView(buildEmptyCard());
            return;
        }
        for (Discovery.Peer p : peers) {
            PeerRow r = new PeerRow(p);
            rows.put(Engine.idOf(p), r);
            listBox.addView(r.card);
        }
    }

    private void updateHeader(int peerCount) {
        int conn = engine.connectedCount();
        // ★ 在线数含本机（用户原话：「右上角应当显示三台设备在线。因为本机也算。」）。
        //   文案上写清楚"含本机"，不让人自己猜这个数字包不包含自己。
        int online = peerCount + 1;
        if (conn > 0) {
            setTextIfChanged(pill, "正在传输 本机 + " + conn + " 台");
            stylePill(OK);
        } else if (peerCount > 0) {
            setTextIfChanged(pill, online + " 台在线（含本机）");
            stylePill(ACC);
        } else {
            setTextIfChanged(pill, "搜索中…（本机 1 台）");
            stylePill(DIM);
        }
    }

    private void refreshDetails() {
        if (viewMode != MODE_DETAIL) {
            return;      // 只有「详细」档才刷这串数字（别的档这些 View 本来就看不见，白算一遍）
        }
        put("name", engine.nodeName() + " · " + engine.platform()
                + (engine.localNameIsCustom() ? "（名字：用户自定义）" : "（名字：默认）"));
        put("ip", engine.localIpsText());
        put("port", engine.audioPort() + "（UDP，收发都用它）");
        put("disc_port", engine.discoveryPort() + "（UDP，发现/控制；要和电脑一致）");
        put("found", engine.peers().size() + " 台");
        put("conn", "收听 " + engine.listeningCount() + " 台 / 送声 " + engine.sendingCount() + " 台");
        // ★ 「实际收到哪几路」：开关是意图，这一行才是事实（放最前面，跟"已连接"挨着）
        put("streams", engine.recvStreamsText());
        // ★ 发出去：几台、逐台发了多少、带宽是多少（不藏代价）
        put("send", engine.sendStatsText());
        put("volume", engine.volumeText() + "\n" + engine.systemVolumeText());
        put("volmode", engine.volumeModeText());
        put("recv", engine.statsText());
        put("send", engine.sendStatsText());
        // 对端回话：这是"哪一端没在发"最直接的证据（对方自己说的）。
        put("peers", Diagnosis.peersSummary(engine.peerStatsList(), SystemClock.elapsedRealtime()));
        put("disc", engine.discoveryStatsText());
    }

    private void put(String key, String value) {
        TextView v = kvValues.get(key);
        if (v != null) {
            setTextIfChanged(v, value);
        }
    }

    // ────────────────────────────── 交互 ──────────────────────────────

    private void onListenClick(Discovery.Peer p) {
        boolean on = engine.isListening(p);
        engine.setListening(p, !on);
        toast((on ? "已停止收听 " : "开始收听 ") + p.label());
        refreshNow();
    }

    private void onSendClick(Discovery.Peer p) {
        // ★ 2026-10-16 用户把这件事钉死了：「广播是和路由独立开的。你别混一起了。
        //   广播就是设备想说就说想听就听，但是不能关闭或者开启其他设备的听说。」
        //   所以这里**不再**因为"正在广播"去拦着逐台那个按钮 —— 广播是电台，
        //   逐台「送声音」是点对点，两条线互不相干：广播开着也能单独补送给某台。
        if (engine.isSending(p)) {
            // ★ 2026-11-04（第 4 条 + 第 2 档）：用户要求"按一下换一下"的循环，**不弹窗**。
            //   顺序固定 0 → 1 → 2 → 0：
            //     第 1 档（送声+外放）→ 再按一次进入第 2 档（送声+手机静音）；
            //     第 2 档 → 再按一次回到第 0 档（停止送声，并还原系统媒体音量）。
            int mode = engine.sendMode();
            if (mode >= 2) {
                engine.setSendMode(0);      // 内部会把音量还原
                toast("已停止送声音：" + p.label() + "（第 0 档）");
            } else {
                engine.setSendMode(2);
                toast("第 2 档：手机会静音，声音仍在传给电脑");
            }
            refreshNow();
            return;
        }
        // 已经在送别的设备（档位 1/2）：把这一台加进目标，保持当前档位。
        // 这就是"多送一台只是多一个收包人"，不重建采集。
        if (engine.sendMode() >= 1 && engine.sourceEnabled()) {
            engine.setSending(p, true);
            toast("开始送声音：" + p.label() + "（第 " + engine.sendMode() + " 档）");
            refreshNow();
            return;
        }
        // ★ 2026-10-19：音源开关关着就没有声音可送 —— 明确提示去主控区打开，而不是悄悄替你开
        //   （尤其是绝不能替用户打开麦克风）。
        if (!engine.sourceEnabled()) {
            toast("音源已关闭：先在主控区点「🔊 本机声音」或「🎤 麦克风」打开采集");
            return;
        }
        // ★ ④ 用户原话：「手机界面点击送声音后，应当判断手机是否在电脑的蓝牙设备列表里。
        //   如果是就让用户判断到底用蓝牙还是WiFi。如果否就直接走WiFi路径。」
        //   只在"从没在送 → 要开始送"这条路上问（上面的"正在送 → 停止"已经提前返回，绝不弹窗）。
        //   匹配不上（老电脑没报 bt / 名单里没有我这台）就完全保持原样，直接走 WiFi。
        if (LinkPolicy.btNameMatches(engine.localName(), p.btNames)) {
            askBluetoothOrWifi(p);
            return;
        }
        startSendFlow(p);
    }

    /**
     * ★ ④ 这台电脑的蓝牙名单里有我 —— 先问一句走蓝牙还是 WiFi。
     *
     * <p>为什么默认/推荐是 WiFi：用户前几条反馈刚把 WiFi 这条路修好并测通，而蓝牙那条
     * （电脑端面板 ②「设备与路由」里的蓝牙那一段）还在验证中；所以推荐项永远是 WiFi，
     * 让用户"什么都不想，点一下最左边的按钮"就能拿到今天这个已经能用的结果。
     *
     * <p>为什么选「走蓝牙」不直接开始发送：蓝牙这条路要先在系统设置里把电脑连上、
     * 还要确认「媒体音频」开着 —— 这两件事 App 没有权限替用户做，只能把人送到设置页。
     */
    private void askBluetoothOrWifi(final Discovery.Peer p) {
        final String pcName = p.label();      // 电脑名：toast 里要照着念给它
        // 用工程自己的深色 Dialog，不用系统 AlertDialog —— 理由见 showDiagnosisDialog 的注释：
        // 系统那个是亮色底板，和整页深色视觉不搭（这个工程零依赖、圆角全靠自己画）。
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(roundRect(0xF5070B12, dp(18), LINE, Math.max(1, dpi(1))));
        int pad = dpi(16);
        box.setPadding(pad, pad, pad, pad);

        box.addView(text("这台电脑也能用蓝牙接你的声音", 16f, FG, true));
        TextView hint = text("它认得你 —— 你的手机名出现在这台电脑的蓝牙设备列表里。\n"
                + "走 WiFi：和现在一样，声音走局域网，已经在正常工作（推荐）。\n"
                + "走蓝牙：**由电脑去连你的蓝牙**（连接由那边发起，你这边不用动手）—— "
                + "声音就不占 WiFi 了。要是电脑回报「没找到你这台」，说明还没在 Windows 里配对过，"
                + "去手机蓝牙设置里配对一次就好。", 11.5f, DIM, false);
        hint.setLineSpacing(dp(3), 1f);
        box.addView(hint, margins(MATCH, WRAP, 0, dpi(8), 0, dpi(12)));

        TextView wifi = chip("走 WiFi（推荐）");
        styleChip(wifi, true, "走 WiFi（推荐）");     // 推荐项高亮，等于"点最上面那个就是今天的效果"
        wifi.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
                startSendFlow(p);                    // 保持今天的行为
            }
        });
        box.addView(wifi, margins(MATCH, dpi(46), 0, 0, 0, dpi(8)));

        TextView bt = chip("走蓝牙（电脑去连）");
        bt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
                askPcForBluetooth(p);
            }
        });
        box.addView(bt, margins(MATCH, dpi(46), 0, 0, 0, dpi(8)));

        TextView cancel = chip("取消");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        box.addView(cancel, margins(MATCH, dpi(46), 0, 0, 0, 0));

        d.setContentView(box);
        Window dw = d.getWindow();
        if (dw != null) {
            dw.setBackgroundDrawable(new ColorDrawable(0));
            dw.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dw.setDimAmount(0.62f);
            dw.setLayout(MATCH, WRAP);
        }
        d.show();
    }

    // ★ 2026-11-04（第 4 条）：三档弹窗已删除，改为「按一下换一下」的循环按键（0↔1）。

    /**
     * ★ ④ 请**电脑**去连我的蓝牙（用户原话：「手机确认走蓝牙后应当由电脑发送蓝牙请求
     * 并配对互相连接」）。
     *
     * <p>手机这边只把请求挂在这台的统计包里（每 2 秒重复一次，幂等）；真正打开
     * A2DP sink 的是电脑 —— 蓝牙设备列表在它那边，而且一次连接要跑几秒 PowerShell。
     * <p>电脑连成没连成，会在**电脑面板 ② 里「蓝牙（手机的声音）」那一段**写出来
     * （以及电脑的诊断报告里），所以这里只负责把话说清楚，不让用户盯着手机等。
     */
    private void askPcForBluetooth(final Discovery.Peer p) {
        engine.setBtWant(p, true);
        toast("已请电脑去连你的蓝牙 —— 连上后声音就走蓝牙过去；"
                + "连成没连成看电脑面板 ② 里「蓝牙（手机的声音）」那一段");
    }

    /**
     * 「送声音」的完整流程。顺序是有原因的：
     * <ol>
     *   <li>低于安卓 10 → 本机声音这个能力根本不存在，直接改成麦克风并说清楚；</li>
     *   <li>要录音权限 —— <b>采本机声音同样需要它</b>（系统对 AudioRecord 一律要求 RECORD_AUDIO），
     *       界面上会写明"不会采你说话的声音"，免得用户又以为这是对讲机；</li>
     *   <li>选的是本机声音且还没授权 → 弹一次系统授权窗口，授权回来之后由前台服务接着开始送。</li>
     * </ol>
     */
    private void startSendFlow(Discovery.Peer p) {
        if (p == null) {
            return;
        }
        // 走这条路 = 走 WiFi：把之前"请电脑连蓝牙"的请求撤掉（幂等字段，撤了自然变 false），
        // 免得用户改主意之后电脑还一直往蓝牙那条路使劲。
        engine.setBtWant(p, false);
        int want = engine.sourceKind();
        if (want == AudioSend.SRC_PLAYBACK && !engine.playbackSupported()) {
            // ★ 绝不自动改用麦克风（用户隐私硬约束）：如实说明，让用户自己去点「🎤 麦克风」。
            toast("你的安卓版本不支持采本机声音（要 Android 10 及以上），请改用麦克风");
            return;
        }
        // ★ 2026-11-12：checkSelfPermission / requestPermissions 都是 API 23 才有的。
        //   5.1 上是安装时授权，没有"运行时申请"这回事 —— 直接往下走。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            pendingSend = p;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (want == AudioSend.SRC_PLAYBACK && !engine.hasProjection()) {
            pendingSend = p;
            engine.requestSendWithPlayback(p);      // 授权回来之后还知道要送给谁
            if (!requestProjection()) {
                pendingSend = null;
                engine.cancelPendingSend("这台设备没有投屏服务，送不了本机声音");
                toast("这台设备没有投屏服务，送不了本机声音；可以改用麦克风");
            }
            return;
        }
        engine.setSending(p, true);
        // 提示里说的是"打算送什么"；真实采到的是什么，下面那行状态 150ms 内就会如实刷新
        // （采不到本机声音时会写"已退回麦克风 + 原因"）。
        toast("开始送声音：" + AudioSend.sourceShortName(want) + " → " + p.label());
        MixService.safeStart(this);
        refreshNow();
    }

    /** 弹系统授权窗口（「是否允许 Audio Mixing 录制或投放？」）。返回 false = 连窗口都拉不起来。 */
    private boolean requestProjection() {
        try {
            MediaProjectionManager mgr =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            if (mgr == null) {
                return false;
            }
            startActivityForResult(mgr.createScreenCaptureIntent(), REQ_PROJ);
            return true;
        } catch (Throwable t) {
            toast("拉不起授权窗口：" + t);
            return false;
        }
    }

    /**
     * 用户点了音源按钮。
     *
     * <p>★ 2026-10-19 用户第 4 条：<b>真正的 toggle</b> ——
     * 「点击未激活按键即为激活，再次点击即为关闭」。新状态由纯策略
     * {@link SourcePolicy#toggle} 算出来（当前真实状态 → 点这颗之后的状态），
     * 所以"点在已亮的那颗 = 关闭"这件事和线上跑的是同一段逻辑（离线探针逐条钉住）。
     *
     * <p>同一时刻只能采一路（采集端只有一份 AudioRecord）：点麦克风时"本机声音"那颗会自动灭，
     * 界面在状态行里写明原因（见 Engine.sourceStateText）。
     */
    private void onSourceClick(int kind) {
        int current = engine.sourceState();
        int next = SourcePolicy.toggle(current, kind);
        if (next == SourcePolicy.SRC_PLAYBACK && !engine.playbackSupported()) {
            toast("你的安卓版本不支持采集本机声音（要 Android 10 及以上），只能用麦克风");
            return;
        }
        engine.applySourceState(next, "");
        if (next == SourcePolicy.SRC_PLAYBACK && !engine.hasProjection()) {
            if (!requestProjection()) {
                engine.applySourceState(SourcePolicy.SRC_OFF, "这台设备没有投屏服务");
                toast("这台设备没有投屏服务，采不了本机声音；可以改用麦克风");
            } else {
                toast("请在系统窗口里点「立即开始」/「允许」（授权一次，之后一直用）");
            }
        } else if (next == SourcePolicy.SRC_OFF) {
            toast("已关闭「" + AudioSend.sourceShortName(kind) + "」采集");
        } else {
            toast("已打开「" + AudioSend.sourceShortName(kind) + "」采集；点任意一台的「送声音」开始送");
        }
        // ★ 2026-11-12 用户：「当我点击本机声音时也是默认勾选广播我的声音」——
        //   选上「本机声音」的语义就是"我要把手机上放的东西放给所有人"，所以顺手把广播勾上，
        //   不用再点第二次。（麦克风不自动勾：开麦送出去是更敏感的动作，必须用户自己按广播。）
        if (next == SourcePolicy.SRC_PLAYBACK && engine.sourceEnabled() && !engine.isBroadcasting()) {
            engine.setBroadcasting(true);
            toast("已同时勾选「📢 广播我的声音」：本机声音现在送给所有在线设备");
        }
        refreshNow();
    }

    // ────────────────────────────── 诊断 ──────────────────────────────

    /**
     * ★ 「🚀 快速上手」对话框：把"该点哪里"写成 30 秒能读完的步骤。
     *
     * <p>为什么要有它：用户的评价标准是"打开就知道点哪里"。三档切换之后，
     * 简洁档把长说明收起来了，所以必须有一个**三档都进得来**的地方把这些话说清楚。
     */
    private void showQuickStartDialog() {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(roundRect(0xF5070B12, dp(18), LINE, Math.max(1, dpi(1))));
        int pad = dpi(16);
        box.setPadding(pad, pad, pad, pad);

        box.addView(text("🚀 快速上手（30 秒）", 16f, FG, true));

        TextView body = text(
                "① 手机和电脑 / 另一台手机连同一个 Wi-Fi，打开 App —— 设备会自己出现，不用配对、不用扫码。\n"
                + "② 想听电脑正在放的声音：电脑上开「广播我的声音」→ 手机上点「📻 听广播」。\n"
                + "③ 想让别的设备听我这台的声音：点「📢 广播我的声音」（所有人都听，逐个单播、不盲发），"
                + "或者点某一台的「🔊 送声音」（只送它）。\n"
                + "④ 送出去的是手机正在放的音乐/视频/游戏（第一次会让你授权一次）；"
                + "换成「🎤 麦克风」就是对讲。\n"
                + "⑤ 没声音时点「🩺 诊断」，把整段复制出来发给对方 —— 报告里写明了是哪一端没在发。\n"
                + "⑥ 音量滑杆只管「我们这一层」（不改你的系统音量）；系统音量单独一行只显示。\n"
                + "⑦ 顶部三档：简洁（只留操作）/ 详细（带完整信息）/ 监控（只读实时数字）。",
                12f, FG, false);
        body.setLineSpacing(dp(5), 1f);
        ScrollView sv = new ScrollView(this);
        sv.addView(body, new ViewGroup.LayoutParams(MATCH, WRAP));
        int bodyH = Math.max(dpi(200),
                (int) (getResources().getDisplayMetrics().heightPixels * 0.46f));
        box.addView(sv, margins(MATCH, bodyH, 0, dpi(10), 0, dpi(12)));

        TextView ok = chip("知道了");
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        box.addView(ok, margins(MATCH, dpi(46), 0, 0, 0, 0));

        d.setContentView(box);
        Window dw = d.getWindow();
        if (dw != null) {
            dw.setBackgroundDrawable(new ColorDrawable(0));
            dw.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dw.setDimAmount(0.62f);
            dw.setLayout(MATCH, WRAP);
        }
        d.show();
    }

    /**
     * ★ 「✏️ 改本机名称」对话框。
     *
     * <p>用户原话：「本机id最好支持自己编辑，不排除多人用同型号设备」。
     * 这里改的只是<b>名字</b>（别人列表里看到的那个）；节点 ID 是身份，不在界面上暴露、也不允许改
     * —— 改重复了电脑端就分不清谁是谁，路由表也会指向不清。
     */
    private void showRenameDialog() {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(roundRect(0xF5070B12, dp(18), LINE, Math.max(1, dpi(1))));
        int pad = dpi(16);
        box.setPadding(pad, pad, pad, pad);

        box.addView(text("✏️ 改本机名称", 16f, FG, true));
        TextView hint = text("别人在设备列表里看到的就是这个名字（下一个信标 2 秒内带出去）。\n"
                + "两台同型号手机默认名已经用后缀区分（例如 M332BF-7f3a），想改成「小红的手机」这种更好认的，在这里改。\n"
                + "改完会记住，重启还在。（节点 ID 是身份，不在这里改 —— 改重了别人就分不清了。）",
                11.5f, DIM, false);
        hint.setLineSpacing(dp(3), 1f);
        box.addView(hint, margins(MATCH, WRAP, 0, dpi(8), 0, dpi(10)));

        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setText(engine.localName());
        et.setSelection(et.getText().length());
        et.setTextColor(FG);
        et.setHintTextColor(DIM);
        et.setHint("例如：小红的手机");
        et.setBackground(roundRect(0x12FFFFFF, dp(10), LINE, Math.max(1, dpi(1))));
        et.setPadding(dpi(10), dpi(10), dpi(10), dpi(10));
        box.addView(et, margins(MATCH, WRAP, 0, 0, 0, dpi(12)));

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);

        TextView save = chip("保存");
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String name = et.getText() == null ? "" : et.getText().toString().trim();
                if (name.isEmpty()) {
                    toast("名字不能是空的（保持原名字就行）");
                    return;
                }
                engine.setLocalName(name);
                toast("已改名为：" + engine.localName());
                d.dismiss();
                refreshNow();
            }
        });
        LinearLayout.LayoutParams slp = margins(0, dpi(46), 0, 0, dpi(8), 0);
        slp.weight = 1f;
        btns.addView(save, slp);

        TextView cancel = chip("取消");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        LinearLayout.LayoutParams clp = margins(0, dpi(46), 0, 0, 0, 0);
        clp.weight = 1f;
        btns.addView(cancel, clp);
        box.addView(btns);

        d.setContentView(box);
        Window dw = d.getWindow();
        if (dw != null) {
            dw.setBackgroundDrawable(new ColorDrawable(0));
            dw.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dw.setDimAmount(0.62f);
            dw.setLayout(MATCH, WRAP);
            // 弹出键盘：改名是个纯输入动作，用户不该再点一次输入框
            dw.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        d.show();
    }

    /**
     * 「🩺 诊断」对话框：上半部分是可滚动、可长按选中的报告全文，下面是「复制」。
     *
     * <p><b>为什么不用 AlertDialog</b>：这个工程零依赖、主题是深色的，
     * 系统 AlertDialog 的亮色底板和整套视觉不搭，还得为它单独调主题；自己搭一个
     * {@link Dialog} 反而代码更少、圆角和配色都能直接复用页面那套 tools。
     *
     * <p><b>为什么自动刷新</b>：这报告是给"现场盯着数字变化"用的（对方还在不在发、
     * 丢包涨不涨），一打开就定格的快照没什么用。所以每 1.5 秒重算一次；
     * 用户点一下正文（想手动选中一段）就停掉自动刷新，免得把选区刷没。
     */
    private void showDiagnosisDialog() {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(roundRect(0xF5070B12, dp(18), LINE, Math.max(1, dpi(1))));
        int pad = dpi(16);
        box.setPadding(pad, pad, pad, pad);

        box.addView(text("🩺 诊断报告", 16f, FG, true));

        TextView hint = text("整段可以长按选中；点「复制」一键拷走。"
                + "数字每 1.5 秒自动刷新，点一下正文可暂停刷新再慢慢选。", 11.5f, DIM, false);
        hint.setLineSpacing(dp(3), 1f);
        box.addView(hint, margins(MATCH, WRAP, 0, dpi(6), 0, dpi(10)));

        final TextView body = text("", 11f, FG, false);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);      // 用户要的是"能复制"，这里连手动选也一并给上
        body.setLineSpacing(dp(3), 1f);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(true);
        sv.addView(body, new ViewGroup.LayoutParams(MATCH, WRAP));
        // 固定占屏幕一半多一点：报告很长必须能滚，又不能把对话框撑出屏幕。
        int bodyH = Math.max(dpi(220),
                (int) (getResources().getDisplayMetrics().heightPixels * 0.52f));
        box.addView(sv, new LinearLayout.LayoutParams(MATCH, bodyH));

        final boolean[] auto = {true};
        body.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (auto[0]) {
                    auto[0] = false;
                    toast("已暂停自动刷新，可以慢慢选了");
                }
            }
        });

        final Runnable refreshBody = new Runnable() {
            @Override
            public void run() {
                try {
                    setTextIfChanged(body, Diagnosis.build(MainActivity.this));
                } catch (Throwable t) {
                    setTextIfChanged(body, "生成诊断报告失败：" + t);
                }
                // 对话框关了（isShowing 为假）就自然停掉，不会一直挂着刷。
                if (auto[0] && d.isShowing()) {
                    ui.postDelayed(this, 1500L);
                }
            }
        };

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);

        TextView copy = chip("📋 复制");
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyToClipboard("AudioFlow.LR 诊断/关于报告", body.getText().toString());
            }
        });
        LinearLayout.LayoutParams clp = margins(0, dpi(46), 0, dpi(12), dpi(8), 0);
        clp.weight = 1f;
        btns.addView(copy, clp);

        TextView close = chip("关闭");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        LinearLayout.LayoutParams xlp = margins(0, dpi(46), 0, dpi(12), 0, 0);
        xlp.weight = 1f;
        btns.addView(close, xlp);
        box.addView(btns);

        d.setContentView(box);
        Window dw = d.getWindow();
        if (dw != null) {
            dw.setBackgroundDrawable(new ColorDrawable(0));   // 背景透明，圆角才看得出来
            dw.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dw.setDimAmount(0.62f);
            dw.setLayout(MATCH, WRAP);
        }
        d.show();
        refreshBody.run();      // 立刻出一版，别让用户对着空白等 1.5 秒
    }

    /** 复制到剪贴板并提示。任何异常都只提示、不崩（某些 ROM 在受限状态下会抛）。 */
    private void copyToClipboard(String label, String value) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null) {
                toast("这台设备没有剪贴板服务");
                return;
            }
            cm.setPrimaryClip(ClipData.newPlainText(label, value == null ? "" : value));
            toast("已复制");
        } catch (Throwable t) {
            toast("复制失败：" + t);
        }
    }

    private void askNotificationPermission() {
        // 安卓 13（TIRAMISU）起通知也要运行时授权；12 及以下不用问。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ────────────────────────────── 界面搭建 ──────────────────────────────

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(new PageBg());
        int pad = dpi(18);
        root.setPadding(pad, dpi(20), pad, dpi(28));
        scroll.addView(root, new ViewGroup.LayoutParams(MATCH, WRAP));

        root.addView(buildHeader());
        // ★ 一个大块、6 个小格（用户 2026-10-15）：
        //   简洁 / 详细 / 监控 / 诊断 / 本机声音 / 麦克风。
        //   原来这三样（三档切换、工具栏、音源）散在三张卡片里，第 6 格还拖着一整块
        //   说明和一根全局音量滑杆 —— 用户原话是无用的"整个大块全删掉"。
        root.addView(buildMainCard());
        // ★ 「听广播 / 广播我的声音」放在设备列表【上面】：用户现在最常见的情况就是列表里 0 台设备
        //   （电脑在广播、手机看不见它），那时候列表里没有任何东西可点，
        //   而这个开关恰恰是"电脑在广播，我要能听见"的唯一入口 —— 放进列表里等于藏起来。
        channelCard = buildChannelCard();
        root.addView(channelCard);
        // ★ 合并版：左右声道控制放在广播开关下面、设备列表上面 ——
        //   它是本机播放的全局属性，和设备列表里的「逐台分配」是上下级关系。
        stereoCard = buildStereoCard();
        root.addView(stereoCard);

        // ★ 2026-11-12 用户：「能不能加入开机自动启动加后台保活」——
        //   这一块只管"怎么让它在后台活着"，与声音路由无关，所以放在设备列表上面、独立成卡。
        keepAliveCard = buildKeepAliveCard();
        root.addView(keepAliveCard);

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(listBox, margins(MATCH, WRAP, 0, dpi(16), 0, 0));

        // 监控档：只读的实时数字面板（其他两档隐藏）
        monitorCard = buildMonitorCard();
        root.addView(monitorCard);

        // 底部说明：只有「详细」档才显示（用户原话："简洁模式下所有的标注补充式文本全部不要"）
        hintCard = buildHintCard();
        root.addView(hintCard);
        applyMode();
        return scroll;
    }

    /**
     * ★ 主控大块（2026-10-15 用户要求）：<b>一个大块，分 6 个小格</b>。
     *
     * <p>用户原话：「合成一个大块，分6个小块。简洁/详细/监控/诊断/本机声音/麦克风」——
     * 这 6 样原来散在三张卡片里（三档切换一张、工具栏一张、"送什么声音"一张），
     * 最后那张还塞了一整块说明和一根全局音量滑杆。
     *
     * <p>他同时把"多出来的东西"点掉了：
     * <ul>
     *   <li>「整个大块全删掉，音量功能重复了，诊断保留了，改名在页面左上角名字那也重复了」
     *       —— 所以"我们这一层的音量"滑杆、只读的系统音量那一行、那一长段音源限制说明、
     *       {@code ▸ 详情} 开关，以及诊断/改名的副本，全部删掉；</li>
     *   <li>「全部删掉，这些详细页全部都有还不常用，凭什么出现在简洁页面」
     *       —— 快速上手、改名、本机信息那串 kv，只在「详细」档出现。</li>
     * </ul>
     *
     * <p>音量滑杆并没有消失：每台设备卡片里本来就有一对自己的「收 / 送」滑杆（见
     * {@link #gainRow}），那才是用户真正在调的东西 —— 全局那一根是重复的，所以拿掉。
     */
    // ══════════════════════════════════════════════════════════════════
    //  ★ 2026-11-15 双音箱环绕摆动 —— 配置面板
    // ══════════════════════════════════════════════════════════════════

    /** 4 舍 5 入到 5% 网格（用户要求：所有音量步进 5%，1% 太细不好拖）。 */
    private static int snap5(int v) {
        int s = Math.round(v / 5f) * 5;
        return Math.max(0, Math.min(100, s));
    }

    /** 给某一台设备显示的名字（取不到就退回 host）。 */
    private String labelOf(String host) {
        if (host == null || host.isEmpty()) {
            return "";
        }
        List<Discovery.Peer> list = engine.peers();
        if (list != null) {
            for (Discovery.Peer p : list) {
                if (host.equals(p.host)) {
                    return p.label();
                }
            }
        }
        return host;
    }

    /**
     * 环绕面板专用的一行「标签 + 滑杆」：只做外观，**不挂任何业务监听**（由调用方加）。
     *
     * <p>不能复用 {@link #volumeRow(TextView, SeekBar)} —— 那个自带"本机音量"监听，
     * 万一漏覆盖就会拖动环绕滑杆改到本机输出音量，必须彻底分开。
     */
    private LinearLayout surroundRow(TextView label, SeekBar bar) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(label, new LinearLayout.LayoutParams(dpi(96), WRAP));
        bar.setProgressTintList(ColorStateList.valueOf(ACC));
        bar.setThumbTintList(ColorStateList.valueOf(ACC));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, WRAP);
        blp.weight = 1f;
        row.addView(bar, blp);
        return row;
    }

    /**
     * ★ 追加需求 3：构建**某一侧**（A 或 B）的独立音量区间组。
     *
     * <p>两个滑杆都是 5% 步进、标签实时显示百分比；最小/最大互相约束（拖过头会把
     * 另一根一起推走），所以永远不会出现"最小值大于最大值"。
     */
    private LinearLayout buildSurroundRangeGroup(final boolean isA) {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);

        TextView title = text("", 11f, ON_TEXT, true);
        final TextView lo = text("", 11f, DIM, false);
        final TextView hi = text("", 11f, DIM, false);
        final SeekBar loBar = new SeekBar(this);
        final SeekBar hiBar = new SeekBar(this);
        loBar.setMax(100);
        hiBar.setMax(100);

        if (isA) {
            surroundLabelA = title;
            surroundMinLabelA = lo;
            surroundMaxLabelA = hi;
            surroundMinBarA = loBar;
            surroundMaxBarA = hiBar;
        } else {
            surroundLabelB = title;
            surroundMinLabelB = lo;
            surroundMaxLabelB = hi;
            surroundMinBarB = loBar;
            surroundMaxBarB = hiBar;
        }

        g.addView(title, margins(MATCH, WRAP, 0, dpi(10), 0, dpi(2)));
        g.addView(surroundRow(lo, loBar), margins(MATCH, WRAP, 0, 0, 0, 0));
        g.addView(surroundRow(hi, hiBar), margins(MATCH, WRAP, 0, 0, 0, dpi(2)));

        // 最小值：吸附 5% → 与最大值互斥 → 写回引擎（只改这一侧）
        loBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                int v = snap5(progress);
                if (v != progress) {
                    // 让滑块视觉上吸附到 5% 网格。这会再回调一次（fromUser=false，直接返回）。
                    // ★ 关键：这里**不能 return** —— 早先版本 return 了，引擎没收到吸附后的值，
                    //   150ms 后的界面刷新又把滑块拉回旧值，表现为"拖了不动 / 弹回去"。
                    sb.setProgress(v);
                }
                setTextIfChanged(lo, "最小 " + v + "%");
                if (v > hiBar.getProgress()) {
                    hiBar.setProgress(v);
                }
                engine.setSurroundRangeFor(isA, v, hiBar.getProgress());
                refreshSurroundUi();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) { }

            @Override
            public void onStopTrackingTouch(SeekBar sb) { }
        });

        // 最大值：同理反向互斥
        hiBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                int v = snap5(progress);
                if (v != progress) {
                    sb.setProgress(v);      // 吸附到 5% 网格（同上，不能 return）
                }
                setTextIfChanged(hi, "最大 " + v + "%");
                if (v < loBar.getProgress()) {
                    loBar.setProgress(v);
                }
                engine.setSurroundRangeFor(isA, loBar.getProgress(), v);
                refreshSurroundUi();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) { }

            @Override
            public void onStopTrackingTouch(SeekBar sb) { }
        });

        return g;
    }

    /**
     * 构建环绕配置面板（只在 buildMainCard 时建一次，之后靠可见性开关 + 数值刷新）。
     *
     * <p>面板里才放真正启停的【开启/停止环绕】大按钮 —— 顶部那个「环绕」标签只负责
     * 展开/收起这个面板，避免手滑点到就直接开始摆。
     */
    private LinearLayout buildSurroundPanel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(0x0FFFFFFF);
        bg.setStroke(Math.max(1, dpi(1)), LINE);
        box.setBackground(bg);
        box.setPadding(dpi(12), dpi(10), dpi(12), dpi(12));

        box.addView(text("环绕配对：选 1 台 = 自己起伏；选 2 台 = 两台反向摆动；其余设备不受影响",
                        11f, DIM, false),
                margins(MATCH, WRAP, 0, 0, 0, dpi(8)));

        // ── A ⇄ B 两台音箱的选择 ──
        LinearLayout pairRow = new LinearLayout(this);
        pairRow.setOrientation(LinearLayout.HORIZONTAL);
        pairRow.setGravity(Gravity.CENTER_VERTICAL);

        surroundChipA = chip("A：未选");
        surroundChipA.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickSurroundPeer(true);
            }
        });
        LinearLayout.LayoutParams lpA = new LinearLayout.LayoutParams(0, dpi(40));
        lpA.weight = 1f;
        pairRow.addView(surroundChipA, lpA);

        TextView arrow = text("⇄", 15f, DIM, true);
        arrow.setGravity(Gravity.CENTER);
        pairRow.addView(arrow, new LinearLayout.LayoutParams(dpi(34), dpi(40)));

        surroundChipB = chip("B：未选");
        surroundChipB.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickSurroundPeer(false);
            }
        });
        LinearLayout.LayoutParams lpB = new LinearLayout.LayoutParams(0, dpi(40));
        lpB.weight = 1f;
        pairRow.addView(surroundChipB, lpB);
        box.addView(pairRow);

        // ── 摆动速度（1~100 %/秒）──
        surroundSpeedVal = text("", 11f, DIM, false);
        surroundSpeedBar = new SeekBar(this);
        surroundSpeedBar.setMax(99);        // progress+1 = 1~100
        box.addView(surroundRow(surroundSpeedVal, surroundSpeedBar),
                margins(MATCH, WRAP, 0, dpi(10), 0, 0));
        surroundSpeedBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int v = progress + 1;
                setTextIfChanged(surroundSpeedVal, "摆动速度 " + v + " %/秒");
                if (fromUser) {
                    engine.setSurroundSpeed(v);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) { }

            @Override
            public void onStopTrackingTouch(SeekBar sb) { }
        });

        // ── ★ 追加需求 4：运动曲线（线性匀速 / 正弦曲线）──
        box.addView(text("运动曲线", 11f, DIM, false),
                margins(MATCH, WRAP, 0, dpi(10), 0, dpi(2)));
        final String[] curveLabels = {"线性匀速", "正弦曲线"};
        surroundCurveChips = new TextView[curveLabels.length];
        LinearLayout curveRow = new LinearLayout(this);
        curveRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < curveLabels.length; i++) {
            final int curve = i;        // 0 = 线性，1 = 正弦
            TextView c = chip(curveLabels[i]);
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    engine.setSurroundCurve(curve);
                    refreshSurroundUi();
                }
            });
            surroundCurveChips[i] = c;
            LinearLayout.LayoutParams lp = margins(0, dpi(36), i == 0 ? 0 : dpi(6), 0, 0, 0);
            lp.weight = 1f;
            curveRow.addView(c, lp);
        }
        box.addView(curveRow);

        // ── ★ 追加需求 2：两端停留（线性 / 正弦**两种曲线都有**）──
        surroundHoldVal = text("", 11f, DIM, false);
        surroundHoldBar = new SeekBar(this);
        surroundHoldBar.setMax(10);         // 0~10 秒
        surroundHoldRow = new LinearLayout(this);
        surroundHoldRow.setOrientation(LinearLayout.VERTICAL);
        surroundHoldRow.addView(surroundRow(surroundHoldVal, surroundHoldBar),
                margins(MATCH, WRAP, 0, dpi(2), 0, 0));
        box.addView(surroundHoldRow);
        surroundHoldBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                setTextIfChanged(surroundHoldVal, "两端停留 " + progress + " 秒");
                if (fromUser) {
                    engine.setSurroundHoldSec(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) { }

            @Override
            public void onStopTrackingTouch(SeekBar sb) { }
        });

        // ── ★ 2026-11-15 追加：区间模式（共用 / 独立逐台）+ 当前生效摘要 ──
        surroundRangeVal = text("", 11f, DIM, false);
        box.addView(surroundRangeVal, margins(MATCH, WRAP, 0, dpi(10), 0, dpi(2)));

        final String[] modeLabels = {"共用区间", "详细（逐台）"};
        surroundModeChips = new TextView[modeLabels.length];
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < modeLabels.length; i++) {
            final int mode = i;     // 0 = 共用，1 = 独立
            TextView c = chip(modeLabels[i]);
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (engine.surroundRangeMode() == mode) {
                        // 已经在目标模式：只把子面板展开/收起，不重复填充
                        if (mode == Engine.RANGE_PER_SPEAKER) {
                            surroundSubOpen = true;
                        }
                        refreshSurroundUi();
                        return;
                    }
                    engine.setSurroundRangeMode(mode);
                    if (mode == Engine.RANGE_PER_SPEAKER) {
                        surroundSubOpen = true;     // 进独立模式自动展开子面板
                        // 两套参数各自记忆，切换不再互相填充/覆盖
                        toast("已切到独立区间（逐台参数单独保存，与共用互不影响）");
                    } else {
                        toast("已切回共用区间 " + engine.surroundSharedText()
                                + "（逐台区间已保留，未被覆盖）");
                    }
                    refreshSurroundUi();
                }
            });
            surroundModeChips[i] = c;
            LinearLayout.LayoutParams lp = margins(0, dpi(38), i == 0 ? 0 : dpi(6), 0, 0, 0);
            lp.weight = 1f;
            modeRow.addView(c, lp);
        }
        box.addView(modeRow, margins(MATCH, WRAP, 0, dpi(4), 0, 0));

        // 整体音量读数：逐台模式下按音量键改的就是它（等比作用于每一台）
        surroundMasterVal = text("", 10.5f, DIM, false);
        box.addView(surroundMasterVal, margins(MATCH, WRAP, 0, dpi(6), 0, 0));

        // ── 外层【共用区间】两根滑块（独立模式下整行置灰禁用）──
        surroundSharedRow = new LinearLayout(this);
        surroundSharedRow.setOrientation(LinearLayout.VERTICAL);
        surroundSharedMinLabel = text("", 11f, DIM, false);
        surroundSharedMinBar = new SeekBar(this);
        surroundSharedMinBar.setMax(100);
        surroundSharedMaxLabel = text("", 11f, DIM, false);
        surroundSharedMaxBar = new SeekBar(this);
        surroundSharedMaxBar.setMax(100);
        surroundSharedRow.addView(surroundRow(surroundSharedMinLabel, surroundSharedMinBar),
                margins(MATCH, WRAP, 0, dpi(4), 0, 0));
        surroundSharedRow.addView(surroundRow(surroundSharedMaxLabel, surroundSharedMaxBar),
                margins(MATCH, WRAP, 0, 0, 0, dpi(2)));
        box.addView(surroundSharedRow);

        surroundSharedMinBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                int v = snap5(progress);
                if (v != progress) {
                    sb.setProgress(v);      // 吸附到 5%（回调 fromUser=false 直接返回，不能 return）
                }
                setTextIfChanged(surroundSharedMinLabel, "共用最小 " + v + "%");
                if (v > surroundSharedMaxBar.getProgress()) {
                    surroundSharedMaxBar.setProgress(v);
                }
                engine.setSurroundSharedRange(v, surroundSharedMaxBar.getProgress());
                refreshSurroundUi();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) { }

            @Override
            public void onStopTrackingTouch(SeekBar sb) { }
        });

        surroundSharedMaxBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                int v = snap5(progress);
                if (v != progress) {
                    sb.setProgress(v);
                }
                setTextIfChanged(surroundSharedMaxLabel, "共用最大 " + v + "%");
                if (v < surroundSharedMinBar.getProgress()) {
                    surroundSharedMinBar.setProgress(v);
                }
                engine.setSurroundSharedRange(surroundSharedMinBar.getProgress(), v);
                refreshSurroundUi();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) { }

            @Override
            public void onStopTrackingTouch(SeekBar sb) { }
        });

        // ── 可折叠的「逐台音量区间」子面板（共用模式下整块置灰不可编辑）──
        surroundSubToggle = text("", 11f, ON_TEXT, false);
        surroundSubToggle.setGravity(Gravity.CENTER_VERTICAL);
        surroundSubToggle.setPadding(dpi(11), 0, dpi(11), 0);
        surroundSubToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                surroundSubOpen = !surroundSubOpen;
                refreshSurroundUi();
            }
        });
        box.addView(surroundSubToggle, margins(MATCH, dpi(40), 0, dpi(8), 0, 0));

        surroundSubPanel = new LinearLayout(this);
        surroundSubPanel.setOrientation(LinearLayout.VERTICAL);
        surroundSubPanel.setVisibility(surroundSubOpen ? View.VISIBLE : View.GONE);
        surroundGroupA = buildSurroundRangeGroup(true);
        surroundSubPanel.addView(surroundGroupA);
        surroundGroupB = buildSurroundRangeGroup(false);
        surroundSubPanel.addView(surroundGroupB);
        box.addView(surroundSubPanel);

        // ★ 追加需求 1：广播没在传音频时的暂停提示（正常运行时不显示）。
        surroundStatus = text("", 10.5f, WARN, false);
        box.addView(surroundStatus, margins(MATCH, WRAP, 0, dpi(8), 0, 0));

        // ── 真正启停环绕的大按钮 ──
        surroundToggleBtn = text("开启环绕", 15f, ON_TEXT, true);
        surroundToggleBtn.setGravity(Gravity.CENTER);
        surroundToggleBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (engine.surroundOn()) {
                    engine.stopSurround();
                    toast("环绕已停止，音量已恢复");
                } else {
                    String err = engine.startSurround();
                    if (err != null && !err.isEmpty()) {
                        toast(err);
                    } else {
                        toast("环绕开始摆动");
                    }
                }
                refreshSurroundUi();
            }
        });
        box.addView(surroundToggleBtn, margins(MATCH, dpi(46), 0, dpi(10), 0, 0));

        refreshSurroundUi();
        return box;
    }

    /** 弹设备列表选 A 或 B。B 侧多一项「不选」= 单台模式（追加需求 3）。 */
    private void pickSurroundPeer(final boolean isA) {
        List<Discovery.Peer> list = engine.peers();
        if (list == null) {
            list = new java.util.ArrayList<Discovery.Peer>();
        }
        int n = list.size() + (isA ? 0 : 1);
        if (n == 0) {
            toast("还没发现其他设备");
            return;
        }
        final String[] hosts = new String[n];
        final String[] names = new String[n];
        String curA = engine.surroundHostA();
        String curB = engine.surroundHostB();
        for (int i = 0; i < list.size(); i++) {
            Discovery.Peer p = list.get(i);
            hosts[i] = p.host;
            String tag = "";
            if (p.host.equals(curA)) {
                tag = "（当前 A）";
            } else if (p.host.equals(curB)) {
                tag = "（当前 B）";
            }
            names[i] = p.label() + tag;
        }
        if (!isA) {
            // 单台模式：B 留空，只让 A 自己在区间里起伏
            hosts[list.size()] = "";
            names[list.size()] = "不选（单台模式：只让 A 起伏）";
        }
        new AlertDialog.Builder(this)
                .setTitle(isA ? "选 A 音箱" : "选 B 音箱（可不选）")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (isA) {
                            engine.setSurroundPair(hosts[which], engine.surroundHostB());
                        } else {
                            engine.setSurroundPair(engine.surroundHostA(), hosts[which]);
                        }
                        refreshSurroundUi();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * ★ 追加需求 3：把某一侧（A 或 B）的区间滑杆 / 百分比标签同步成引擎里的值。
     *
     * <p>正在被手指按住的那根不覆盖（否则拖到一半会被刷回去）。
     * {@code setProgress} 触发的回调 {@code fromUser == false}，监听器里会直接返回，
     * 所以这里不会递归回 {@link #refreshSurroundUi()}。
     */
    private void refreshSurroundRangeSide(boolean isA) {
        SeekBar loBar = isA ? surroundMinBarA : surroundMinBarB;
        SeekBar hiBar = isA ? surroundMaxBarA : surroundMaxBarB;
        TextView loTxt = isA ? surroundMinLabelA : surroundMinLabelB;
        TextView hiTxt = isA ? surroundMaxLabelA : surroundMaxLabelB;
        TextView title = isA ? surroundLabelA : surroundLabelB;
        if (loBar == null || hiBar == null) {
            return;
        }
        int lo = engine.surroundMinOf(isA);
        int hi = engine.surroundMaxOf(isA);
        if (!loBar.isPressed() && loBar.getProgress() != lo) {
            loBar.setProgress(lo);
        }
        if (!hiBar.isPressed() && hiBar.getProgress() != hi) {
            hiBar.setProgress(hi);
        }
        // 标签里的百分比就是"实时数值"，拖动时每改一格都跟着变
        if (loTxt != null) {
            setTextIfChanged(loTxt, "最小 " + lo + "%");
        }
        if (hiTxt != null) {
            setTextIfChanged(hiTxt, "最大 " + hi + "%");
        }
        if (title != null) {
            setTextIfChanged(title, "音箱 " + (isA ? "A" : "B") + " 的区间  "
                    + engine.surroundRangeText(isA));
        }
    }

    /** 启用/禁用某一侧的两根区间滑杆（共用模式下逐台一律锁死）。 */
    private void setSurroundSideEnabled(SeekBar lo, SeekBar hi, boolean on) {
        if (lo != null) {
            lo.setEnabled(on);
        }
        if (hi != null) {
            hi.setEnabled(on);
        }
    }

    /** 面板里所有跟状态有关的东西（A/B 名字、速度、区间、大按钮文字与配色）一次刷完。 */
    private void refreshSurroundUi() {
        if (surroundChipA == null) {
            return;
        }
        // 顶部【环绕】的高亮跟着"运行状态"走（状态变了才重画，省得每 600ms 造一次背景）
        boolean onNow = engine.surroundOn();
        if (modeSurround != null && lastSurroundOnState != onNow) {
            lastSurroundOnState = onNow;
            styleChip(modeSurround, onNow, "环绕");
        }
        String a = engine.surroundHostA();
        String b = engine.surroundHostB();
        setTextIfChanged(surroundChipA, "A：" + (a.isEmpty() ? "未选" : labelOf(a)));
        setTextIfChanged(surroundChipB, "B：" + (b.isEmpty() ? "未选" : labelOf(b)));

        if (!surroundSpeedBar.isPressed()) {
            int sp = engine.surroundSpeed();
            if (surroundSpeedBar.getProgress() != sp - 1) {
                surroundSpeedBar.setProgress(sp - 1);
            }
            setTextIfChanged(surroundSpeedVal, "摆动速度 " + sp + " %/秒");
        }
        // ── ★ 2026-11-15 追加：区间模式（共用 / 独立逐台）──
        //   共用模式：外层共用滑块可编辑、逐台置灰；独立模式：反过来。双向自由切换。
        boolean per = engine.surroundPerSpeakerMode();
        boolean hasB = engine.surroundHasB();

        if (surroundModeChips != null) {
            styleChip(surroundModeChips[0], !per, "共用区间");
            styleChip(surroundModeChips[1], per, "详细（逐台）");
            surroundModeChips[0].setAlpha(1f);
            surroundModeChips[1].setAlpha(1f);
        }

        // 音量键说明（没有"整体音量"这一层，按的是各台自己的上限）
        if (surroundMasterVal != null) {
            setTextIfChanged(surroundMasterVal, per
                    ? "按音量键：每次把 A / B 各自的上限各加/减 5%（下限不动，值不拉平）"
                    : "共用模式：音量键不接管，仍是系统音量（切到逐台才按上限调）");
            surroundMasterVal.setAlpha(per ? 1f : 0.45f);
        }

        // 外层共用区间：数值回填 + 可编辑性跟着模式走
        if (surroundSharedMinBar != null) {
            int shLo = engine.surroundSharedMin();
            int shHi = engine.surroundSharedMax();
            if (!surroundSharedMinBar.isPressed() && surroundSharedMinBar.getProgress() != shLo) {
                surroundSharedMinBar.setProgress(shLo);
            }
            if (!surroundSharedMaxBar.isPressed() && surroundSharedMaxBar.getProgress() != shHi) {
                surroundSharedMaxBar.setProgress(shHi);
            }
            setTextIfChanged(surroundSharedMinLabel, "共用最小 " + shLo + "%");
            setTextIfChanged(surroundSharedMaxLabel, "共用最大 " + shHi + "%");
            // 共用模式下可拖；独立模式下锁定（否则用户会以为改了有用）
            surroundSharedMinBar.setEnabled(!per);
            surroundSharedMaxBar.setEnabled(!per);
            if (surroundSharedRow != null) {
                surroundSharedRow.setAlpha(per ? 0.35f : 1f);
            }
        }

        // 逐台区间：数值回填（只在独立模式下可编辑）
        refreshSurroundRangeSide(true);
        refreshSurroundRangeSide(false);
        if (surroundGroupA != null) {
            surroundGroupA.setAlpha(per ? 1f : 0.35f);
        }
        if (surroundGroupB != null) {
            // 单台模式下 B 那一整组不显示，避免"给没选中的音箱设区间"的困惑
            surroundGroupB.setVisibility(hasB ? View.VISIBLE : View.GONE);
            surroundGroupB.setAlpha(per ? 1f : 0.35f);
        }
        setSurroundSideEnabled(surroundMinBarA, surroundMaxBarA, per);
        setSurroundSideEnabled(surroundMinBarB, surroundMaxBarB, per);

        // 摘要：明确写出"当前真正在用哪套参数"
        if (per) {
            setTextIfChanged(surroundRangeVal, hasB
                    ? "当前生效（独立逐台）：A " + engine.surroundRangeText(true)
                        + "  ·  B " + engine.surroundRangeText(false)
                    : "当前生效（独立逐台）：A " + engine.surroundRangeText(true) + "（单台）");
        } else {
            setTextIfChanged(surroundRangeVal, "当前生效（共用区间）："
                    + engine.surroundSharedText());
        }

        // 折叠按钮：展开状态 + 收起时把"当前生效的逐台值"写在按钮上
        if (surroundSubToggle != null) {
            // ★ 盒子（Android 5.1）的系统字体没有 ▸/▾，会渲染成方块；用 +/− 更保险。
            String head = surroundSubOpen ? "− " : "+ ";
            String tail = surroundSubOpen ? ""
                    : "    A " + engine.surroundRangeText(true)
                        + (hasB ? " / B " + engine.surroundRangeText(false) : "");
            setTextIfChanged(surroundSubToggle, per
                    ? head + "逐台音量区间（A / B 各自设置）" + tail
                    : head + "逐台音量区间（共用模式下已锁定）");
            surroundSubToggle.setBackground(roundRect(0x0FFFFFFF, dp(10), LINE,
                    Math.max(1, dpi(1))));
        }
        if (surroundSubPanel != null) {
            surroundSubPanel.setVisibility(surroundSubOpen ? View.VISIBLE : View.GONE);
        }

        // ★ 追加需求 2：曲线 chip 高亮；「两端停留」对两种曲线都生效，所以一直显示。
        int curve = engine.surroundCurve();
        if (surroundCurveChips != null) {
            styleChip(surroundCurveChips[0], curve == Engine.CURVE_LINEAR, "线性匀速");
            styleChip(surroundCurveChips[1], curve == Engine.CURVE_SINE, "正弦曲线");
        }
        if (surroundHoldRow != null) {
            surroundHoldRow.setVisibility(View.VISIBLE);
        }
        if (!surroundHoldBar.isPressed()) {
            int hs = engine.surroundHoldSec();
            if (surroundHoldBar.getProgress() != hs) {
                surroundHoldBar.setProgress(hs);
            }
            setTextIfChanged(surroundHoldVal, "两端停留 " + hs + " 秒");
        }

        // ★ 追加需求 1：广播停了就明说"已暂停"，别让人以为坏了
        if (engine.surroundOn() && !engine.surroundBroadcasting()) {
            setTextIfChanged(surroundStatus, "⏸ 本机没在广播音频，摆动已自动暂停");
        } else {
            setTextIfChanged(surroundStatus, "");
        }

        boolean on = engine.surroundOn();
        setTextIfChanged(surroundToggleBtn, on ? "停止环绕（恢复原音量）" : "开启环绕");
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(11));
        d.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
        if (on) {
            // 运行中：醒目的橙红，和顶部【环绕】的强调色呼应
            d.setColors(new int[]{0xFFF97316, 0xFFEF4444});
        } else {
            d.setColors(new int[]{ACC, ACC2});
        }
        surroundToggleBtn.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), d, null));
        surroundToggleBtn.setTextColor(ON_TEXT);
    }

    private View buildMainCard() {
        LinearLayout card = card();

        // ── 第 1 行：三档切换（简洁 / 详细 / 监控）──
        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);

        modeSimple = chip("简洁");
        modeSimple.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setViewMode(MODE_SIMPLE);
            }
        });
        LinearLayout.LayoutParams p1 = margins(0, dpi(40), 0, 0, dpi(6), 0);
        p1.weight = 1f;
        modes.addView(modeSimple, p1);

        modeDetail = chip("详细");
        modeDetail.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setViewMode(MODE_DETAIL);
            }
        });
        LinearLayout.LayoutParams p2 = margins(0, dpi(40), 0, 0, dpi(6), 0);
        p2.weight = 1f;
        modes.addView(modeDetail, p2);

        modeMonitor = chip("监控");
        modeMonitor.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setViewMode(MODE_MONITOR);
            }
        });
        LinearLayout.LayoutParams p3 = margins(0, dpi(40), 0, 0, dpi(6), 0);
        p3.weight = 1f;
        modes.addView(modeMonitor, p3);

        // ★ 2026-11-15（用户需求）：第 4 个入口「环绕」，放在「详细」后面（即监控之后）。
        //   两个规矩，别搞混：
        //   ① 点它只展开/收起下面的配置面板，**绝不直接启停环绕**（防误触）；
        //   ② 它的高亮**只表示"环绕正在运行"**，文字永远是「环绕」，不写"开/关"。
        modeSurround = chip("环绕");
        modeSurround.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                surroundPanelOpen = !surroundPanelOpen;
                applyMode();
            }
        });
        LinearLayout.LayoutParams p4 = margins(0, dpi(40), 0, 0, 0, 0);
        p4.weight = 1f;
        modes.addView(modeSurround, p4);
        card.addView(modes);

        // 环绕配置面板（默认收起；展开后常驻在这里，不随数据刷新重建）
        surroundPanel = buildSurroundPanel();
        card.addView(surroundPanel, margins(MATCH, WRAP, 0, dpi(8), 0, 0));

        // ── 第 2 行：诊断 / 本机声音 / 麦克风（三档都看得见 —— 诊断是"看"的入口）──
        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER_VERTICAL);

        // ★ 「诊断·关于」入口：点开是一段可复制的完整报告，末尾附作者联系方式。
        TextView diagChip = chip("诊断·关于");
        diagChip.setSingleLine(true);   // 强制单行：之前的「诊断 / 关于」带斜杠会折成两行
        diagChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDiagnosisDialog();
            }
        });
        LinearLayout.LayoutParams dlp = margins(0, dpi(42), 0, 0, dpi(6), 0);
        dlp.weight = 1f;
        tools.addView(diagChip, dlp);

        // ★ 「🔊 本机声音」—— 这一格必须放在最显眼的地方。
        //   用户的原话：「你这手机都是采集的麦克风的声音，那不成对讲机了吗？
        //   我的需求一直都是桌面音频啊」—— 音源选错是这个 App 最大的方向性缺陷。
        playChip = chip("🔊 本机声音");
        playChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSourceClick(AudioSend.SRC_PLAYBACK);
            }
        });
        LinearLayout.LayoutParams plp = margins(0, dpi(42), 0, 0, dpi(6), 0);
        plp.weight = 1f;
        tools.addView(playChip, plp);

        micChip = chip("🎤 麦克风");
        micChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSourceClick(AudioSend.SRC_MIC);
            }
        });
        LinearLayout.LayoutParams mlp = margins(0, dpi(42), 0, 0, 0, 0);
        mlp.weight = 1f;
        tools.addView(micChip, mlp);
        card.addView(tools, margins(MATCH, WRAP, 0, dpi(8), 0, 0));

        // ── 音源的实时状态（"现在实际送的是哪一个" + 静音提醒 + 限制说明）：只有详细档露 ──
        sourceInfoWrap = new LinearLayout(this);
        sourceInfoWrap.setOrientation(LinearLayout.VERTICAL);

        // 这一行是"现在到底送的是什么"的唯一权威说法（绿=正常，黄=和你要的不一样）
        sourceNow = text("", 12f, FG, false);
        sourceNow.setLineSpacing(dp(3), 1f);
        sourceInfoWrap.addView(sourceNow, margins(MATCH, WRAP, 0, dpi(10), 0, 0));

        // 采本机声音却一直是静音时的提醒（DRM / 禁止被采集的 App 就是这样）
        sourceWarn = text("", 11.5f, WARN, false);
        sourceWarn.setLineSpacing(dp(3), 1f);
        sourceWarn.setVisibility(View.GONE);
        sourceInfoWrap.addView(sourceWarn, margins(MATCH, WRAP, 0, dpi(8), 0, 0));

        // 限制一次写清：别让用户以为"选上了就一定能采到"
        TextView limit = text("🔊 本机声音 = 手机上正在放的音乐/视频/游戏（第一次要授权一次，之后一直用；"
                + "安卓 10 以下没有这个能力，只能用麦克风）。\n"
                + "注意：DRM 保护的内容（部分音乐/影视 App）和主动禁止被采集的 App 采不到，会是静音；"
                + "少数厂商 ROM 也阉割了这个接口。\n"
                + "🎤 麦克风 = 对讲：说给对面听。两种都走同一条音频链路，音质一样。", 11.5f, DIM, false);
        limit.setLineSpacing(dp(3), 1f);
        sourceInfoWrap.addView(limit, margins(MATCH, WRAP, 0, dpi(10), 0, 0));
        card.addView(sourceInfoWrap);

        // ── 只有详细档才出现的那两个入口：快速上手 / 改名 ──
        //   （"详情"已经变成这一档本身；"展开说明"连按钮一起去掉了 —— 简洁档一个补充字都没有。）
        detailOnlyRow = new LinearLayout(this);
        detailOnlyRow.setOrientation(LinearLayout.HORIZONTAL);
        detailOnlyRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView qsChip = chip("🚀 快速上手");
        qsChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showQuickStartDialog();
            }
        });
        LinearLayout.LayoutParams qlp = margins(0, dpi(42), 0, dpi(10), dpi(6), 0);
        qlp.weight = 1f;
        detailOnlyRow.addView(qsChip, qlp);

        // ★ 「✏️ 改名」：点标题下那行名字也能改，这里再给一个显式入口
        TextView renameChip = chip("✏️ 改名");
        renameChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showRenameDialog();
            }
        });
        LinearLayout.LayoutParams rlp = margins(0, dpi(42), 0, dpi(10), 0, 0);
        rlp.weight = 1f;
        detailOnlyRow.addView(renameChip, rlp);
        card.addView(detailOnlyRow);

        // ── 详细档的本机信息（原来「▸ 详情」里那一串）──
        detailWrap = new LinearLayout(this);
        detailWrap.setOrientation(LinearLayout.VERTICAL);
        card.addView(detailWrap);

        kv("本机名称", "name");
        kv("本机 IP", "ip");
        kv("音频端口", "port");
        kv("发现端口", "disc_port");
        kv("已发现", "found");
        kv("已连接", "conn");
        kv("实际收到", "streams");
        kv("音量分层", "volume");
        kv("收端统计", "recv");
        kv("发端统计", "send");
        // ★ 对端自己报的统计（两台设备互报的"我发了多少/我收了你多少"）
        kv("对端回话", "peers");
        kv("发现统计", "disc");

        // ★ 2026-11-08（用户方案）：音量乘数绑在送声档位上；这里只暴露"静音档乘数策略"。
        kv("音量乘数", "volmode");

        // ★ 2026-11-01 用户：手机端没有端口修改，电脑改端口后手机连不上。
        //   这里放在「详细」档，和电脑的「设置 → 端口设置」对应：
        //   · 发现端口：要和电脑一致，否则收不到对方的信标/控制消息；
        //   · 音频端口：本机接收声音的 UDP 端口，电脑按信标里的这个值发过来。
        detailWrap.addView(buildPortSettings(), margins(MATCH, WRAP, 0, dpi(14), 0, 0));
        detailWrap.addView(buildVolumePolicySettings(), margins(MATCH, WRAP, 0, dpi(14), 0, 0));
        return card;
    }

    /**
     * ★ 2026-11-08（用户方案）：静音档乘数策略 —— 两个选项，中文写清。
     *
     * <p>外放档永远乘当前系统媒体音量，与本项无关；静音档里 {@code STREAM_MUSIC} 被我们
     * 锁在 0，默认 {@code internal_only}（乘 1.0，只保留 App 内部增益）。
     * 两条限制也写在下面，不能让用户自己猜。
     */
    private View buildVolumePolicySettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        box.addView(text("静音档音量乘数（只影响第 2 档；外放档永远乘系统媒体音量）", 11.5f, DIM, false));

        final TextView status = text(engine.volumeModeText(), 11.5f, DIM, false);
        status.setLineSpacing(dp(2), 1f);
        box.addView(status, margins(MATCH, WRAP, 0, dpi(4), 0, dpi(6)));

        final TextView internal = chip("只用 App 内部音量（推荐）");
        final TextView last = chip("沿用进静音前的音量");
        final Runnable paint = new Runnable() {
            @Override
            public void run() {
                boolean useLast = SendVolume.POLICY_LAST.equals(engine.silentVolPolicy());
                styleChip(internal, !useLast, "只用 App 内部音量（推荐）");
                styleChip(last, useLast, "沿用进静音前的音量");
                setTextIfChanged(status, engine.volumeModeText());
            }
        };
        internal.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                engine.setSilentVolPolicy(SendVolume.POLICY_INTERNAL, true);
                paint.run();
                toast("静音档改为：只用 App 内部音量（乘 1.0）");
            }
        });
        last.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                engine.setSilentVolPolicy(SendVolume.POLICY_LAST, true);
                paint.run();
                toast("静音档改为：沿用进静音前的音量");
            }
        });

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dpi(40), 1f);
        row.addView(internal, lp);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dpi(40), 1f);
        lp2.leftMargin = dpi(8);
        row.addView(last, lp2);
        box.addView(row);

        TextView limits = text("注意：静音档乘 1.0 时耳机侧相当于满格放，从外放档切过来可能明显变响；"
                + "滑杆本身改系统媒体音量的 App（B 站、抖音这类）在静音档里调音量无效，要用滑杆请切外放档；"
                + "内部增益类 App（YouTube 播放器这类）不受影响。", 11.5f, DIM, false);
        limits.setLineSpacing(dp(2), 1f);
        box.addView(limits, margins(MATCH, WRAP, 0, dpi(8), 0, 0));

        paint.run();
        return box;
    }

    /** 「详细」档里的端口设置：发现/音频两个输入框 + 保存。 */
    private View buildPortSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView title = text("发现端口设置（手机/电脑必须一致；保存后自动重启发现服务）", 11.5f, DIM, false);
        title.setLineSpacing(dp(2), 1f);
        box.addView(title);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        EditText disc = new EditText(this);
        disc.setInputType(InputType.TYPE_CLASS_NUMBER);
        disc.setText(String.valueOf(engine.configuredDiscoveryPort()));
        disc.setHint("发现端口 17830");
        disc.setTextSize(12f);
        disc.setTextColor(FG);
        disc.setSingleLine(true);
        row.addView(disc, new LinearLayout.LayoutParams(0, dpi(42), 1f));

        TextView save = chip("💾 保存端口");
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int d;
                try {
                    d = Integer.parseInt(disc.getText().toString().trim());
                } catch (Throwable t) {
                    toast("端口必须是数字");
                    return;
                }
                if (!engine.savePorts(d)) {
                    toast("端口不合法：范围 1024–65534");
                    return;
                }
                // ★ 2026-11-01 用户：「修改端口号后自动重启」。
                //   发现端口是启动时绑死的，保存后必须真的重绑；不再让用户手动重启 App。
                toast("已保存发现端口 " + d + "，正在自动重启…");
                restartDiscovery(d);
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(WRAP, dpi(42));
        slp.leftMargin = dpi(8);
        row.addView(save, slp);

        box.addView(row, margins(MATCH, WRAP, 0, dpi(6), 0, 0));
        TextView auto = text("音频端口：自动选择（信标里公布，电脑按它发过来），不需要手填。",
                10.5f, DIM, false);
        auto.setLineSpacing(dp(2), 1f);
        box.addView(auto, margins(MATCH, WRAP, 0, dpi(4), 0, 0));
        TextView hint = text("只有「发现端口」需要和电脑端一致；面板端口/音频端口由电脑自动选择，"
                + "手机通过发现信标读取。", 10.5f, DIM, false);
        hint.setLineSpacing(dp(2), 1f);
        box.addView(hint, margins(MATCH, WRAP, 0, dpi(4), 0, 0));
        return box;
    }

    /**
     * 保存发现端口后自动重启引擎（2026-11-01 用户要求）。
     *
     * <p>为什么必须 stop→start：发现 socket 在启动那一刻就绑死了，只改
     * SharedPreferences 不会换端口。放在后台线程里做，避免 UI 卡在
     * Discovery/AudioRecv 的 join 上；重启期间界面照常显示，完成后刷新一次。
     */
    private void restartDiscovery(final int wantPort) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    engine.stop();
                    engine.start();
                } catch (Throwable t) {
                    Log.w("AudioMixing", "端口保存后的自动重启失败", t);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            toast("自动重启失败：" + t);
                        }
                    });
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        toast("发现端口 " + wantPort + " 已生效");
                        refreshNow();
                    }
                });
            }
        }, "am-port-restart").start();
    }

    /**
     * ★ 2026-11-12 用户：「监控里面用可视化的界面」——
     * 原来那一整段等宽文字在手机上要左右拖才看得全、数字全挤在一起。
     *
     * <p>现在分成三层，从上到下就是「越往下越细」：
     * <ol>
     *   <li><b>四个总账格子</b>：发现 / 接收 / 发送 / 电平，各一行短读数；</li>
     *   <li><b>每台设备一块</b>：收、送、信号三条电平条 + 丢包读数 ——
     *       哪一端没在发、哪一路在掉包，眼睛扫一遍就有结论；</li>
     *   <li>一句口径说明（信号分怎么算、更细的数字去哪看）。</li>
     * </ol>
     *
     * <p>所有数字仍然全部来自 {@link Engine} 的查询接口，界面这一层一个都不自己编。
     */
    private View buildMonitorCard() {
        LinearLayout card = card();
        card.addView(text("📊 监控 · 实时", 13.5f, FG, true));
        TextView sub = text("每 300ms 刷新 · 全部是实测计数", 11f, DIM, false);
        card.addView(sub, margins(MATCH, WRAP, 0, dpi(3), 0, 0));

        final String[] keys = {"发现", "接收", "发送", "电平"};
        monitorKv = new TextView[4];
        for (int i = 0; i < keys.length; i++) {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.addView(text(keys[i], 10.5f, DIM, false));
            TextView v = text("—", 11.5f, FG, false);
            v.setLineSpacing(dp(2), 1f);
            monitorKv[i] = v;
            box.addView(v);
            card.addView(box, margins(MATCH, WRAP, 0, i == 0 ? dpi(9) : dpi(7), 0, 0));
        }

        View line = new View(this);
        line.setBackgroundColor(TRACK);
        card.addView(line, margins(MATCH, 1, 0, dpi(11), 0, 0));

        monitorRowsBox = new LinearLayout(this);
        monitorRowsBox.setOrientation(LinearLayout.VERTICAL);
        card.addView(monitorRowsBox, margins(MATCH, WRAP, 0, dpi(4), 0, 0));

        TextView note = text("「信号」是丢包比例 + 缓冲深度算出的 0~100 分；还没有心跳时照实写「不可测」，"
                + "不拿 0 冒充。更细的坏包分类、编解码、发现层地址都在「诊断」里。"
                + "这一档只看数字，要操作（收听 / 送声音 / 广播）请切回「简洁」或「详细」。",
                11f, DIM, false);
        note.setLineSpacing(dp(3), 1f);
        registerLong(longTextViews, note);
        card.addView(note, margins(MATCH, WRAP, 0, dpi(8), 0, 0));

        refreshMonitor();
        return card;
    }

    /** 监控里每台设备那一块：名字 + 三条电平条 + 一行读数。 */
    private static final class MonRow {
        String host = "";
        String sig = "";
        TextView title;
        TextView status;
        TextView tail;
        LevelBar recv;
        LevelBar send;
        LevelBar quality;
    }

    /** 只更新监控面板（数字 + 电平条），不动别的卡片。 */
    private void refreshMonitor() {
        if (monitorRowsBox == null) {
            return;
        }
        String[] kv = engine.monitorCounters();
        if (monitorKv != null) {
            for (int i = 0; i < monitorKv.length && i < kv.length; i++) {
                setTextIfChanged(monitorKv[i], kv[i]);
            }
        }
        List<Discovery.Peer> peers;
        try {
            peers = engine.peers();
        } catch (Throwable t) {
            peers = new ArrayList<Discovery.Peer>();
        }
        // 设备集合变了才重建（150ms/300ms 一刷，别每次都 new View）
        StringBuilder sig = new StringBuilder("self,");   // ★ 本机行也在重建集合里
        for (Discovery.Peer p : peers) {
            sig.append(Engine.idOf(p)).append(',');
        }
        if (!sig.toString().equals(monShownSig)) {
            monShownSig = sig.toString();
            monitorRowsBox.removeAllViews();
            monRows.clear();
            buildMonSelfRow();          // ★ 2026-11-13：本机广播状态行（置顶）
            for (Discovery.Peer p : peers) {
                monRows.add(buildMonRow(p));
            }
        }
        refreshMonSelfRow();
        for (MonRow r : monRows) {
            Discovery.Peer p = null;
            for (Discovery.Peer q : peers) {
                if (Engine.idOf(q).equals(r.sig)) {
                    p = q;
                    break;
                }
            }
            if (p == null) {
                continue;
            }
            boolean listening = engine.isListening(p);
            boolean sending = engine.isSending(p) || engine.isBroadcasting();
            float recv = listening ? engine.recvPeakFor(p.host) : 0f;
            float send = sending ? engine.sendPeakFor(p.host) : 0f;
            int q = engine.peerQualityFor(p.host);
            r.recv.setLevel(recv);
            r.send.setLevel(send);
            r.quality.setLevel(q < 0 ? 0f : q / 100f);
            // ★ 2026-11-17 晚（用户要求）：删掉「它收 60% · 它送我 没在送」这类没用的措辞，
            //   换成详细页那套实数：收多少包 / 丢多少包 / 接收多少 M（对方自己上报的字节数）。
            Diagnosis.PeerStats ps = engine.peerStats(p.host);
            String line;
            if (ps == null) {
                line = "收包 · 丢包 · 接收流量：等它回话…";
            } else {
                line = "收 " + ps.recv + " 包 · 丢 " + ps.lost + " 包"
                        + " · 接收 " + (ps.rxBytes >= 0 ? Diagnosis.bytesText(ps.rxBytes) : "—")
                        + " · 信号 " + (q < 0 ? "不可测" : (q + " 分"));
            }
            setTextIfChanged(r.tail, line);
            setTextIfChanged(r.status, (sending ? "在收" : "")
                    + (listening ? (sending ? " · " : "") + "在送" : "")
                    + (engine.isSmart(p.host) ? "" : " · 智连关"));
        }
    }

    private String monShownSig = "";

    /** ★ 2026-11-13：监控页顶部的「本机广播」行 —— 本机作为发送方的实时状态。 */
    private void buildMonSelfRow() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        monSelfTitle = text("本机广播", 13f, FG, true);
        head.addView(monSelfTitle,
                new LinearLayout.LayoutParams(0, WRAP, 1f));
        monSelfStatus = text("未广播", 11f, DIM, true);
        head.addView(monSelfStatus);
        box.addView(head);

        monSelfBar = new LevelBar(this, Math.max(2, dpi(3)));
        // ★ 2026-11-13（用户）：标签按角色切换 —— 送（广播方）/ 收（接收方）。
        LinearLayout selfBarRow = new LinearLayout(this);
        selfBarRow.setOrientation(LinearLayout.HORIZONTAL);
        selfBarRow.setGravity(Gravity.CENTER_VERTICAL);
        monSelfBarLabel = text("送", 10.5f, DIM, false);
        selfBarRow.addView(monSelfBarLabel, new LinearLayout.LayoutParams(dpi(34), WRAP));
        LinearLayout.LayoutParams selfBlp = new LinearLayout.LayoutParams(0, dpi(7));
        selfBlp.weight = 1f;
        selfBarRow.addView(monSelfBar, selfBlp);
        box.addView(selfBarRow, margins(MATCH, WRAP, 0, dpi(4), 0, 0));

        monSelfTail = text("", 10.5f, DIM, false);
        monSelfTail.setLineSpacing(dp(2), 1f);
        box.addView(monSelfTail, margins(MATCH, WRAP, dpi(34), dpi(4), 0, 0));

        monitorRowsBox.addView(box, margins(MATCH, WRAP, 0, dpi(10), 0, 0));
    }

    /** 每次刷新监控时更新「本机广播」行：广播开关 / 发送电平 / 音源·台数·流量。 */
    private void refreshMonSelfRow() {
        if (monSelfStatus == null || monSelfBar == null || monSelfTail == null) {
            return;
        }
        // ★ 2026-11-13（用户）：按本机角色显示 —— 广播方 = 送；开「听广播」的接收方 = 收。
        boolean bc = engine.isBroadcasting();
        boolean listening = !bc && engine.isChannelListening();
        if (bc) {
            setTextIfChanged(monSelfTitle, "本机广播");
            setTextIfChanged(monSelfBarLabel, "送");
            setTextIfChanged(monSelfStatus, "广播中");
            monSelfStatus.setTextColor(OK);
            monSelfBar.setLevel(engine.sendPeak());
            String t = engine.broadcastStatusText();
            setTextIfChanged(monSelfTail, t.isEmpty()
                    ? "没在广播。点「广播我的声音」后，这里实时显示音源、送给几台和流量"
                    : t);
        } else if (listening) {
            setTextIfChanged(monSelfTitle, "本机收听");
            setTextIfChanged(monSelfBarLabel, "收");
            setTextIfChanged(monSelfStatus, "收听中");
            monSelfStatus.setTextColor(OK);
            monSelfBar.setLevel(engine.recvPeak());
            setTextIfChanged(monSelfTail,
                    "正在接收广播音频（音源来自广播发起方）");
        } else {
            setTextIfChanged(monSelfTitle, "本机广播");
            setTextIfChanged(monSelfBarLabel, "送");
            setTextIfChanged(monSelfStatus, "未广播");
            monSelfStatus.setTextColor(DIM);
            monSelfBar.setLevel(0f);
            setTextIfChanged(monSelfTail,
                    "没在广播。点「广播我的声音」后，这里实时显示音源、送给几台和流量");
        }
    }

    private MonRow buildMonRow(Discovery.Peer p) {
        MonRow r = new MonRow();
        r.sig = Engine.idOf(p);
        r.host = p.host;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        r.title = text(p.label(), 13f, FG, true);
        r.title.setSingleLine(true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, WRAP);
        tlp.weight = 1f;
        head.addView(r.title, tlp);
        r.status = text("", 11f, OK, true);
        head.addView(r.status);
        box.addView(head);

        // ★ 2026-11-13（用户）：收/送两个字对调 —— 这些设备是【在收】我的广播，
        //   所以"我发给它的"那根电平条标【收】，"它发给我的"标【送】。
        r.recv = new LevelBar(this, Math.max(2, dpi(2)));
        box.addView(monBarRow("送", r.recv), margins(MATCH, WRAP, 0, dpi(5), 0, 0));
        r.send = new LevelBar(this, Math.max(2, dpi(2)));
        box.addView(monBarRow("收", r.send), margins(MATCH, WRAP, 0, dpi(4), 0, 0));
        r.quality = new LevelBar(this, Math.max(2, dpi(2)));
        box.addView(monBarRow("信号", r.quality), margins(MATCH, WRAP, 0, dpi(4), 0, 0));

        r.tail = text("", 10.5f, DIM, false);
        r.tail.setLineSpacing(dp(2), 1f);
        box.addView(r.tail, margins(MATCH, WRAP, dpi(34), dpi(4), 0, 0));

        monitorRowsBox.addView(box, margins(MATCH, WRAP, 0, dpi(10), 0, 0));
        return r;
    }

    /** 监控里的一条：小标签 + 电平条。 */
    private LinearLayout monBarRow(String key, LevelBar bar) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text(key, 10.5f, DIM, false), new LinearLayout.LayoutParams(dpi(34), WRAP));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, dpi(7));
        blp.weight = 1f;
        row.addView(bar, blp);
        return row;
    }

    /** 0~1 的峰值写成百分比（监控里要短，所以不用"声音大小"那种长说法）。 */
    private static String pctText(float v) {
        return Math.round(v * 100f) + "%";
    }

    private View buildStereoCard() {
        LinearLayout card = card();

        card.addView(text("🎚 左右声道 · 本机播放", 15f, FG, true));
        // 如实写链路规格，不含糊：让用户一眼看出这一版和「单声道版」的差别。
        TextView fmt = text("48 kHz / 立体声 PCM16 / 10 ms 一帧 · 左右可分别控制", 11.5f, DIM, false);
        fmt.setLineSpacing(dp(2), 1f);
        card.addView(fmt, margins(MATCH, WRAP, 0, dpi(4), 0, dpi(2)));

        // ── 5 种声道模式，两行放得下 ──
        //  ★ 2026-11-12 用户：「把本机播放里面的单声道改成静音」——
        //    单声道在手机链路上几乎没用（音源本来就是立体声；真要一只耳朵听全声音有「仅左/仅右」），
        //    而"这台手机当哑巴、但声音照样转给别的设备"是天天要用的。于是这一格换成静音。
        final String[] labels = {"立体声", "静音", "传送静音", "仅左", "仅右", "还原"};
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 3; i++) {
            final int mode = i == 2 ? ChannelRouter.MODE_SEND_MUTE : i;
            TextView c = chip(labels[i]);
            stereoModeChips[mode] = c;
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    engine.setChannelMode(mode);
                    syncStereoUi();
                    // ★ 2026-11-12 用户：「静音点了没反应」——
                    //   多数时候是"当时本来就没有网络声音在放"，或者紧接着点了测左/测右
                    //   （那两个是本地提示音，故意不受静音影响）。这里把话说全，免得以为坏了。
                    toast(mode == ChannelRouter.MODE_MUTE
                            ? "已静音：本机不再放网络声音（收听、转发照常）；测左/测右 是本地提示音，仍会响"
                            : mode == ChannelRouter.MODE_SEND_MUTE
                            ? "传送静音：本机不出声，采到的声音照送给别的设备（送声已切到第 2 档）"
                            : "声道模式：" + ChannelRouter.modeText(mode));
                }
            });
            LinearLayout.LayoutParams lp = margins(0, dpi(40), i == 0 ? 0 : dpi(6), 0, 0, 0);
            lp.weight = 1f;
            row1.addView(c, lp);
        }
        card.addView(row1, margins(MATCH, WRAP, 0, dpi(10), 0, 0));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 3; i < 5; i++) {
            final int mode = i == 3 ? ChannelRouter.MODE_LEFT_ONLY : ChannelRouter.MODE_RIGHT_ONLY;
            TextView c = chip(labels[i]);
            stereoModeChips[mode] = c;
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    engine.setChannelMode(mode);
                    syncStereoUi();
                    // ★ 2026-11-12 用户：「静音点了没反应」——
                    //   多数时候是"当时本来就没有网络声音在放"，或者紧接着点了测左/测右
                    //   （那两个是本地提示音，故意不受静音影响）。这里把话说全，免得以为坏了。
                    toast(mode == ChannelRouter.MODE_MUTE
                            ? "已静音：本机不再放网络声音（收听、转发照常）；测左/测右 是本地提示音，仍会响"
                            : mode == ChannelRouter.MODE_SEND_MUTE
                            ? "传送静音：本机不出声，采到的声音照送给别的设备（送声已切到第 2 档）"
                            : "声道模式：" + ChannelRouter.modeText(mode));
                }
            });
            LinearLayout.LayoutParams lp = margins(0, dpi(40), i == 3 ? 0 : dpi(6), 0, 0, 0);
            lp.weight = 1f;
            row2.addView(c, lp);
        }
        TextView reset = chip(labels[5]);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                engine.resetChannels();
                syncStereoUi();
                toast("已还原：立体声 · 本机音量 100%");
            }
        });
        LinearLayout.LayoutParams rlp = margins(0, dpi(40), dpi(6), 0, 0, 0);
        rlp.weight = 1f;
        row2.addView(reset, rlp);
        card.addView(row2, margins(MATCH, WRAP, 0, dpi(6), 0, 0));

        // ★ 2026-11-12 用户：「左 100% / 右 100% / 平衡居中 感觉没有用，删掉」。
        //   三根滑杆（平衡、左、右）整段去掉：真正天天要用的是下面那一根本机音量，
        //   左右的分工交给"每台设备进哪只耳"更直观。
        //   引擎里的 balance/leftGain/rightGain 仍然保留默认值（0 / 1 / 1），
        //   声道矩阵照旧工作，只是界面不再提供这三个入口。
        volLabel = text("", 11f, DIM, false);
        volBar = new SeekBar(this);
        volBar.setMax(100);          // 0~100%（>100 的放大方案按用户要求先撤掉了）
        card.addView(volumeRow(volLabel, volBar), margins(MATCH, WRAP, 0, dpi(10), 0, 0));

        // ★ 2026-11-13 用户：「拖动其他设备的延迟滑块，其他设备上没有实时滑块 UI 更新，
        //   只有音量滑块可以」—— 音量能做到联动，靠的就是"每个值都有本机控件 + 统计回报"。
        //   延迟照抄同一套：这根本机延迟滑杆（给快的那台自己加延迟用）
        //   + 对端卡片上的「它的延迟」远程滑杆（从任何一台调整某一台）。
        localDelayLabel = text("", 11f, DIM, false);
        localDelayBar = new SeekBar(this);
        localDelayBar.setMax(AudioRecv.MAX_EXTRA_DELAY_MS / DELAY_STEP_MS);   // 0~300ms，一格 5ms
        card.addView(outDelayRow(localDelayLabel, localDelayBar),
                margins(MATCH, WRAP, 0, dpi(2), 0, dpi(4)));

        // ★ 2026-11-17：补包协议切换（切换会自动同步到所有在线设备；两套档位各自独立）。
        card.addView(text("补包协议（切换自动同步到所有设备）", 11f, DIM, false),
                margins(MATCH, WRAP, 0, dpi(12), 0, 0));
        LinearLayout protoRow = new LinearLayout(this);
        protoRow.setOrientation(LinearLayout.HORIZONTAL);
        nackChipOld = chip("冗余副本 稳");
        nackChipNew = chip("NACK 重传 低延迟");
        nackChipOld.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                engine.setNackEnabled(false);
                refreshNackChips();
                refreshBufChips();
                // ★ 2026-11-17 晚：延迟也按协议记忆，切回来自动读回上次那个值 —— toast 里说清楚，
                //   免得用户以为"我明明调过 120ms，怎么变成别的数了"。
                toast("补包协议：冗余副本（缓冲 " + engine.recvBufMs() + "ms，延迟 "
                        + engine.outDelayMs() + "ms · 已同步）");
            }
        });
        nackChipNew.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                engine.setNackEnabled(true);
                refreshNackChips();
                refreshBufChips();
                toast("补包协议：NACK 重传（缓冲固定 500ms，延迟 " + engine.outDelayMs()
                        + "ms 用的是这个模式上次的值 · 已同步）");
            }
        });
        LinearLayout.LayoutParams oldLp = margins(0, dpi(40), 0, 0, 0, 0);
        oldLp.weight = 1f;
        protoRow.addView(nackChipOld, oldLp);
        LinearLayout.LayoutParams newLp = margins(0, dpi(40), dpi(6), 0, 0, 0);
        newLp.weight = 1f;
        protoRow.addView(nackChipNew, newLp);
        card.addView(protoRow, margins(MATCH, WRAP, 0, dpi(6), 0, 0));
        refreshNackChips();

        // ★ 2026-11-14（用户要求）：缓冲档位，两行排（720px 宽的盒子一行放不下太多）。
        //   点任意一档会【同时下发给所有在线设备】—— 用户原话：「这三个大档位也要同步到其他设备」。
        bufHeaderText = text("", 11f, DIM, false);
        card.addView(bufHeaderText, margins(MATCH, WRAP, 0, dpi(12), 0, 0));
        // ★ 2026-11-17 晚（用户定案）：chip 永远只渲染【冗余副本那 3 档】700/1000/1500。
        //   NACK 重传的缓冲固定 500ms、不提供档位，所以切到 NACK 后这 3 个 chip 整体灰掉不可点
        //   （不再像上一版那样把标签换成 100/300/500）。
        final int[] bufTiers = Engine.RECV_BUF_TIERS;
        bufChips = new TextView[bufTiers.length];
        final int perRow = 3;
        for (int row = 0; row * perRow < bufTiers.length; row++) {
            LinearLayout bufRow = new LinearLayout(this);
            bufRow.setOrientation(LinearLayout.HORIZONTAL);
            for (int col = 0; col < perRow; col++) {
                final int i = row * perRow + col;
                if (i >= bufTiers.length) {
                    // 补一个空占位，保证最后一行按钮宽度和上一行对齐
                    View spacer = new View(this);
                    LinearLayout.LayoutParams slp = margins(0, dpi(40), col == 0 ? 0 : dpi(6), 0, 0, 0);
                    slp.weight = 1f;
                    bufRow.addView(spacer, slp);
                    continue;
                }
                TextView c = chip(bufTiers[i] + "ms");
                final int idx = i;
                c.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        // NACK 重传的缓冲固定 500ms，档位不可选（chip 已经被灰掉，这里再兜一层）
                        if (engine.nackEnabled()) {
                            toast("NACK 重传缓冲固定 500ms，档位不可调（切回冗余副本才能选）");
                            return;
                        }
                        if (idx >= bufTiers.length) {
                            return;
                        }
                        final int v = bufTiers[idx];
                        final int old = engine.recvBufMs();
                        engine.setRecvBufMs(v);
                        refreshBufChips();
                        // ★ 2026-11-14：调大档位会"原地等"把缓冲垫深，这段是静音，
                        //   必须说清楚，否则用户以为卡住了。
                        toast(v > old
                                ? "缓冲 " + v + "ms：正在加深，约静音 " + (v - old) + "ms（已同步）"
                                : "缓冲 " + v + "ms（已同步到所有设备）");
                    }
                });
                bufChips[i] = c;
                LinearLayout.LayoutParams lp = margins(0, dpi(40), col == 0 ? 0 : dpi(6), 0, 0, 0);
                lp.weight = 1f;
                bufRow.addView(c, lp);
            }
            card.addView(bufRow, margins(MATCH, WRAP, 0, dpi(6), 0, 0));
        }

        refreshBufChips();

        // ── 不经过网络的左右声道测试音 ──
        final String[] toneLabels = {"测左", "测右", "测双"};
        LinearLayout tones = new LinearLayout(this);
        tones.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 3; i++) {
            final int ch = i == 0 ? 1 : (i == 1 ? 2 : 0);
            TextView c = chip(toneLabels[i]);
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    playChannelTone(ch);
                }
            });
            LinearLayout.LayoutParams lp = margins(0, dpi(40), i == 0 ? 0 : dpi(6), 0, 0, 0);
            lp.weight = 1f;
            tones.addView(c, lp);
        }
        card.addView(tones, margins(MATCH, WRAP, 0, dpi(10), 0, 0));

        stereoState = text("", 11.5f, OK, true);
        stereoState.setLineSpacing(dp(2), 1f);
        card.addView(stereoState, margins(MATCH, WRAP, 0, dpi(9), 0, 0));

        TextView hint = text("测试音是手机本地生成的 440 Hz 提示音，不走网络，用来确认耳机物理左右。"
                + "「静音」只让本机不出声：收听、混音、把声音转给别的设备照常进行。"
                + "「传送静音」= 送声 + 手机静音：本机把声音送出去，自己一点声音都不出"
                + "（切到这一档会自动把「送声音」推到第 2 档；本机哑着的时候音量键改为调对方的音量）。"
                + "「仅左 / 仅右」是**只留左边/右边那一路**（立体声里另一半的内容会没有），"
                + "要两边听到同样内容请用「立体声」；而设备卡片上把某台设成单耳时，"
                + "那一台会自动折成单声道，不会丢内容。"
                + "下面每台设备卡片还能单独指定「它进我耳」（它的声音进我哪只耳朵），"
                + "以及「它的输出 / 它的音量」（远程改那一台自己怎么出声，所有手机上的状态会一起变）。"
                + "所有调节只作用于本 App 处理的 PCM，不会改动手机系统媒体音量。", 11f, DIM, false);
        hint.setLineSpacing(dp(3), 1f);
        registerLong(longTextViews, hint);
        card.addView(hint, margins(MATCH, WRAP, 0, dpi(8), 0, 0));

        syncStereoUi();
        return card;
    }

    /**
     * ★ 2026-11-12 用户：「能不能加入开机自动启动加后台保活」。
     *
     * <p>这张卡只干三件事，而且每件都写得明明白白（不吹"绝对不死"）：
     * <ol>
     *   <li><b>开机自启开关</b>：关掉之后 {@link BootReceiver} 就不会再拉起服务；</li>
     *   <li><b>电池优化白名单</b>：一键弹系统确认框。不加这一条，国产 ROM 息屏一段时间
     *       就会把前台服务掐掉 —— 这是"锁屏就没声"的头号原因；</li>
     *   <li><b>厂商自启动设置</b>：小米/红米、vivo/iQOO、OPPO、华为各家一套，没有公开 API，
     *       只能帮用户跳到对应页面让他点一下。</li>
     * </ol>
     *
     * <p>这三条都做到之后，"锁屏继续跑 / 开机自动上线"才是真的。系统层面被强制停止的
     * （用户在设置里点了"强行停止"）任何 App 都救不回来，这一点不骗人。
     */
    private View buildKeepAliveCard() {
        LinearLayout card = card();
        card.addView(text("🔋 后台常驻 · 开机自启", 15f, FG, true));

        keepAliveState = text("", 11.5f, DIM, false);
        keepAliveState.setLineSpacing(dp(2), 1f);
        card.addView(keepAliveState, margins(MATCH, WRAP, 0, dpi(5), 0, 0));

        // 第一行：开机自启 开关 + 加入电池白名单
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        keepAutoChip = chip("开机自启");
        keepAutoChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean now = !KeepAlive.autoStart(MainActivity.this);
                KeepAlive.setAutoStart(MainActivity.this, now);
                toast(now ? "已打开：下次开机自动上线（还要在系统里允许自启动才有效）"
                          : "已关闭：开机不会自动启动");
                syncKeepAliveUi();
            }
        });
        LinearLayout.LayoutParams l1 = margins(0, dpi(42), 0, 0, dpi(6), 0);
        l1.weight = 1f;
        row1.addView(keepAutoChip, l1);

        keepBatteryChip = chip("加电池白名单");
        keepBatteryChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (KeepAlive.ignoringBattery(MainActivity.this)) {
                    toast("已经在白名单里了，不用再加");
                    return;
                }
                if (!KeepAlive.requestIgnoreBattery(MainActivity.this)) {
                    toast("这台手机不支持这个请求，已打开电池优化列表，手动把本 App 设为「不优化」");
                }
                // 系统页回来后 onResume 会再刷一次状态
            }
        });
        LinearLayout.LayoutParams l2 = margins(0, dpi(42), 0, 0, 0, 0);
        l2.weight = 1f;
        row1.addView(keepBatteryChip, l2);
        card.addView(row1, margins(MATCH, WRAP, 0, dpi(9), 0, 0));

        // 第二行：厂商自启动设置
        TextView oem = chip("打开自启动设置");
        oem.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String rom = KeepAlive.romName();
                boolean ok = KeepAlive.openAutoStartSettings(MainActivity.this);
                if (!ok) {
                    toast("这台手机没有公开的自启动设置页，已打开应用详情，请手动找「自启动 / 后台运行」");
                    return;
                }
                toast((rom.isEmpty() ? "已打开系统的自启动设置" : "已打开 " + rom + " 的自启动设置")
                        + "：把本 App 打开（允许自启动 / 允许后台运行）");
            }
        });
        card.addView(oem, margins(MATCH, dpi(42), 0, dpi(6), 0, 0));

        TextView hint = text("本 App 用的是「前台服务 + 常驻通知」，锁屏、切后台都不会停。"
                + "真正会掐掉它的只有两件事：① 系统的电池优化（所以要加白名单）；"
                + "② 厂商的「自启动/后台」管理（小米、vivo、OPPO、华为各家都有，需要在那个列表里允许一次）。"
                + "两条都做完，开机就会自动上线，不用先解锁点 App。"
                + "另外：从最近任务里把 App 划掉会主动停掉服务（不这样的话系统会一直显示"
                + "「正在获取屏幕」，那是采集本机声音的授权）；要停就用通知栏里的停止。"
                + "注意：如果你在系统设置里点过「强行停止」，任何 App 都不会自己起来，要手动开一次。",
                11f, DIM, false);
        hint.setLineSpacing(dp(3), 1f);
        card.addView(hint, margins(MATCH, WRAP, 0, dpi(8), 0, 0));

        syncKeepAliveUi();
        return card;
    }

    /** 把保活状态回写到两颗按钮和状态行（系统设置页回来之后也要刷新）。 */
    private void syncKeepAliveUi() {
        if (keepAliveState == null) {
            return;
        }
        boolean auto = KeepAlive.autoStart(this);
        boolean battery = KeepAlive.ignoringBattery(this);
        setTextIfChanged(keepAliveState, KeepAlive.stateText(this));
        if (keepAutoChip != null) {
            styleChip(keepAutoChip, auto, auto ? "开机自启 · 开" : "开机自启 · 关");
        }
        if (keepBatteryChip != null) {
            styleChip(keepBatteryChip, battery, battery ? "已在白名单 ✓" : "加电池白名单");
        }
    }

    /**
     * 本机音量那一行（0~100%）。数值就写在左边标签里，省掉一列。
     *
     * <p>2026-11-12 起这一行是三根滑杆（平衡 / 左 / 右）里唯一留下来的那根 ——
     * 用户原话：「左 100% 和右 100%，平衡居中 感觉没有用删掉」。
     */
    private LinearLayout volumeRow(final TextView label, final SeekBar bar) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(label, new LinearLayout.LayoutParams(dpi(88), WRAP));
        bar.setProgressTintList(ColorStateList.valueOf(ACC));
        bar.setThumbTintList(ColorStateList.valueOf(ACC));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, WRAP);
        blp.weight = 1f;
        row.addView(bar, blp);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                // 只缩放我们这条 AudioTrack，绝不动系统媒体音量。
                setTextIfChanged(label, "本机音量 " + progress + "%");
                if (fromUser) {
                    engine.setOutVolume(progress / 100f);
                }
                if (stereoState != null) {
                    setTextIfChanged(stereoState, engine.stereoText());
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) { }

            @Override
            public void onStopTrackingTouch(SeekBar sb) { }
        });
        return row;
    }

    /**
     * 本机输出延迟那一行（0~300ms）。数值就写在左边标签里（和本机音量同一排版）。
     *
     * <p>拖它 = 把这一台的混音整体往后挪多少毫秒，用来对齐"谁先出声"；
     * 别的手机也可以远程改（out_ctl delay），改的时候这根滑杆会实时跟着动 ——
     * 和「本机音量」被远程改时一模一样。
     */
    private LinearLayout outDelayRow(final TextView label, final SeekBar bar) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(label, new LinearLayout.LayoutParams(dpi(88), WRAP));
        bar.setProgressTintList(ColorStateList.valueOf(ACC));
        bar.setThumbTintList(ColorStateList.valueOf(ACC));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, WRAP);
        blp.weight = 1f;
        row.addView(bar, blp);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int ms = progress * DELAY_STEP_MS;      // ★ 一格 = 5ms
                setTextIfChanged(label, "本机延迟 " + ms + " ms");
                if (fromUser) {
                    engine.setOutDelayMs(ms);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) { }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                // ★ 一格本来就是 5ms，不再做旧的 10ms 吸附（那会把 5/15/25 弹回 10/20）。
                // ★ 2026-11-17 晚（用户要求「拖动本机延迟自动同步给其它接收端」）：
                //   拖动中途是节流下发的，松手这里 force 一次，保证最终值一定送到每一台。
                engine.setOutDelayMs(sb.getProgress() * DELAY_STEP_MS, true);
            }
        });
        return row;
    }

    /** 把引擎里（含上次保存的）真实设置回写到所有控件，避免界面显示与实况不符。 */
    private void syncStereoUi() {
        int m = engine.channelMode();
        for (int i = 0; i < stereoModeChips.length; i++) {
            if (stereoModeChips[i] != null) {
                styleChip(stereoModeChips[i], i == m, new String[]{
                        "立体声", "静音", "仅左", "仅右", "传送静音"}[i]);
            }
        }
        if (volBar != null && !volBar.isPressed()) {
            volBar.setProgress(Math.round(engine.outVolume() * 100f));
        }
        if (volLabel != null) {
            setTextIfChanged(volLabel, "本机音量 " + Math.round(engine.outVolume() * 100f) + "%");
        }
        // ★ 2026-11-13：本机输出延迟被远程改时，这根滑杆也要实时跟上（音量同款逻辑）。
        if (localDelayBar != null && !localDelayBar.isPressed()) {
            localDelayBar.setProgress(engine.outDelayMs() / DELAY_STEP_MS);
        }
        if (localDelayLabel != null) {
            setTextIfChanged(localDelayLabel, "本机延迟 " + engine.outDelayMs() + " ms");
        }
        if (stereoState != null) {
            setTextIfChanged(stereoState, engine.stereoText());
        }
    }

    /**
     * 本地左右声道测试音：440 Hz、900 ms、带 30 ms 淡入淡出。
     *
     * <p><b>刻意不经过接收链路</b>：它直接写 AudioTrack，用来确认「手机 + 耳机」的
     * 物理左右是通的。若这里左右分得开、但网络声音分不开，问题就在音源本身是单声道
     * 或者是全局声道模式，而不会误判成 App 的 bug —— 和项目「如实告知」的规矩一致。
     */
    private void playChannelTone(final int ch) {
        // ★ 2026-11-12 用户：「点击测左，测右，测双一直显示音频设备忙」。
        //
        // 根因：这里原先是 MODE_STATIC + setBufferSizeInBytes(整段 86 KB)。静态模式要求
        // 一次性把整段填进内核缓冲，很多机型（尤其和接收端那条 AudioTrack 并存时）直接
        // 初始化失败 → getState() != STATE_INITIALIZED → 用户只看到"设备忙"。
        //
        // 现在改成最通用的做法：MODE_STREAM + minBufferSize，边写边播，写在后台线程里
        // （主线程写 43 KB 会卡 UI）；48 kHz 起不来就退到 44.1 kHz 再试一次，
        // 两次都不行才如实报错，并说清是系统拒绝了哪一步。
        final int[] rates = {48000, 44100};
        final int ms = 900;
        final int[] chOut = {ch};
        toast(ch == 1 ? "只应在【左耳】听到 440 Hz"
                : ch == 2 ? "只应在【右耳】听到 440 Hz"
                : "左右耳都应听到 440 Hz（两边一样）");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = "";
                for (int rate : rates) {
                    AudioTrack t = null;
                    try {
                        byte[] pcm = buildTonePcm(rate, ms, chOut[0]);
                        int minBuf = AudioTrack.getMinBufferSize(rate,
                                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                        if (minBuf <= 0) {
                            minBuf = 8192;
                        }
                        int buf = Math.max(minBuf * 2, 8192);
                        // 走 AudioCompat：Android 5.1 上没有 AudioTrack.Builder。
                        t = AudioCompat.newTrack(rate, AudioFormat.CHANNEL_OUT_STEREO, buf, false);
                        if (t == null) {
                            err = rate + " Hz 建不出播放设备";
                            continue;
                        }
                        if (t.getState() != AudioTrack.STATE_INITIALIZED) {
                            err = rate + " Hz 初始化失败（state=" + t.getState() + "）";
                            t.release();
                            t = null;
                            continue;
                        }
                        t.play();
                        int off = 0;
                        while (off < pcm.length) {
                            int n = Math.min(3840, pcm.length - off);
                            int w = t.write(pcm, off, n);
                            if (w <= 0) {
                                err = "写入被拒绝（" + w + "）";
                                break;
                            }
                            off += w;
                        }
                        Thread.sleep(ms + 120L);
                        return;      // 放完了
                    } catch (Throwable e) {
                        err = String.valueOf(e);
                    } finally {
                        if (t != null) {
                            try {
                                t.stop();
                            } catch (Throwable ignored) {
                            }
                            try {
                                t.release();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
                final String msg = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        toast("测试音没放出来：" + msg
                                + "（另一路播放正占着声卡时偶尔会这样，先「停止收听」再试一次）");
                    }
                });
            }
        }, "am-tone").start();
    }

    /** 生成 440 Hz 测试音（交错立体声 PCM16，带 30 ms 淡入淡出）。{@code ch}: 1 左 / 2 右 / 0 双。 */
    private static byte[] buildTonePcm(int rate, int ms, int ch) {
        int frames = rate * ms / 1000;
        byte[] pcm = new byte[frames * 4];
        int fade = Math.max(1, rate * 30 / 1000);
        for (int i = 0; i < frames; i++) {
            double env = 1.0;
            if (i < fade) {
                env = i / (double) fade;
            } else if (i > frames - fade) {
                env = (frames - i) / (double) fade;
            }
            short s = (short) (Math.sin(2 * Math.PI * 440.0 * i / rate) * 0.32 * 32767 * env);
            if (ch != 2) {                    // 左
                pcm[i * 4] = (byte) s;
                pcm[i * 4 + 1] = (byte) (s >> 8);
            }
            if (ch != 1) {                    // 右
                pcm[i * 4 + 2] = (byte) s;
                pcm[i * 4 + 3] = (byte) (s >> 8);
            }
        }
        return pcm;
    }

    private View buildChannelCard() {
        LinearLayout card = card();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        channelChip = chip("📻 听广播");
        channelChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean on = !engine.isChannelListening();
                engine.setChannelListen(on);
                toast(on ? "已打开听广播：谁在广播就听谁" : "已关闭听广播");
                refreshNow();
            }
        });
        LinearLayout.LayoutParams clp = margins(0, dpi(44), 0, 0, 0, 0);
        clp.weight = 1f;
        row.addView(channelChip, clp);

        // ★ 「📢 广播我的声音」：用户原话「没有发广播」。
        //   做法：对发现列表里每个在线节点各建一条单播发送（一次采集、一包编码、发 N 份）。
        broadcastChip = chip("📢 广播我的声音");
        broadcastChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean on = !engine.isBroadcasting();
                if (on && !engine.sourceEnabled()) {
                    // ★ 2026-11-13 用户定版：「其实想让广播时自动勾本机声音」——
                    //   广播的用途就是"把我手机上放的声音放给所有人"，所以默认就选这一路；
                    //   第一次会弹系统那个「开始录制或投放」的授权，点一次之后一直用。
                    //   老系统（安卓 10 以下）没有这个能力，才退到麦克风。
                    if (engine.playbackSupported()) {
                        engine.applySourceState(AudioSend.SRC_PLAYBACK, "");
                        if (!engine.hasProjection() && !requestProjection()) {
                            engine.applySourceState(SourcePolicy.SRC_OFF, "这台设备没有投屏服务");
                            toast("这台设备没有投屏服务，采不了本机声音，请改用「🎤 麦克风」");
                            return;
                        }
                        toast(engine.hasProjection()
                                ? "已自动勾选「🔊 本机声音」：手机上的声音送给所有在线设备"
                                : "已自动勾选「🔊 本机声音」：请在系统窗口点「立即开始」允许采集");
                    } else {
                        engine.applySourceState(AudioSend.SRC_MIC, "");
                        toast("这台设备的安卓版本采不了本机声音，已自动用麦克风");
                    }
                }
                engine.setBroadcasting(on);
                toast(on ? "已开始广播：我的声音送给每一台在线设备" : "已停止广播");
                refreshNow();
            }
        });
        LinearLayout.LayoutParams blp = margins(0, dpi(44), dpi(8), 0, 0, 0);
        blp.weight = 1f;
        row.addView(broadcastChip, blp);
        card.addView(row);

        channelNow = text("", 12f, DIM, false);
        channelNow.setLineSpacing(dp(3), 1f);
        card.addView(channelNow, margins(MATCH, WRAP, 0, dpi(10), 0, 0));

        // ★ ④ 安全检查的结果：「已停止向 XXX 发送 —— 它已经 18 秒没有消息了…」
        suspendedLine = text("", 11.5f, WARN, false);
        suspendedLine.setLineSpacing(dp(3), 1f);
        suspendedLine.setVisibility(View.GONE);
        card.addView(suspendedLine, margins(MATCH, WRAP, 0, dpi(8), 0, 0));

        // ★ 2026-10-18 用户第 6 条：这里原来的「自动收听路由声音」全局开关 + 说明 +「一键收回」
        //   三块整段删掉 —— 自动听不听改由每台设备卡片上各自的第 4 颗「智连」按钮决定（默认关），
        //   不再有全局开关，也就没有"请求收听横幅"和"一键收回"存在的必要。

        // 发送侧的状态（含"送给几台 + 真实带宽"）
        broadcastNow = text("", 12f, DIM, false);
        broadcastNow.setLineSpacing(dp(3), 1f);
        broadcastNow.setVisibility(View.GONE);
        card.addView(broadcastNow, margins(MATCH, WRAP, 0, dpi(8), 0, 0));

        TextView hint = text("📻 听广播 = 「谁在广播就听谁」：电脑上开着「广播我的声音」时选这个就能听见，"
                + "不用先在这里点某台设备。\n"
                + "📢 广播我的声音 = 反过来：我这台的声音同时送给局域网里每一台在线设备"
                + "（逐个单播，不往广播地址盲发 —— 盲发在 Wi-Fi 上不可靠）。\n"
                + "🎧 收听 / 🔊 送声音 = 点对点，只跟你点名的那一台来往。\n"
                + "★ 广播和路由是【两条独立的线】，就像局域网里的 FM 电台：你想说就说、想听就听；"
                + "它不会去开关别人的「听」「送」，也不会替你改动任何一台设备的路由状态"
                + "（开着广播时，卡片上那些按钮还是你自己的选择）。\n"
                + "同一台设备的声音如果从两条路都进来（电脑同时开着广播和点对点路由），只会播一路、不叠加。",
                11.5f, DIM, false);
        hint.setLineSpacing(dp(3), 1f);
        registerLong(longTextViews, hint);      // 简洁档收起来
        card.addView(hint, margins(MATCH, WRAP, 0, dpi(10), 0, 0));
        return card;
    }

    private View buildHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        row.addView(new LogoView(this), new LinearLayout.LayoutParams(dpi(38), dpi(38)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = margins(0, WRAP, dpi(11), 0, 0, 0);
        tlp.weight = 1f;
        row.addView(titles, tlp);

        // ★ 2026-11-13 用户：「最上面加入版本号」—— 名字右边跟一个 vX.YY(code)。
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(text("AudioFlow.LR", 16f, FG, true));
        // 只放版本名（v2.34）：带 versionCode 会在这个窄位置换行，看着像排版坏了。
        TextView ver = text("v" + BuildConfig.VERSION_NAME, 10.5f, DIM, false);
        ver.setSingleLine(true);
        ver.setPadding(dpi(7), dpi(2), dpi(7), dpi(3));
        ver.setBackground(roundRect(0x14FFFFFF, dp(7), LINE, Math.max(1, dpi(1))));
        // 注意第一个参数是**宽度**：传 0 会被量成 0px（这一版第一次就是因此看不见）。
        titleRow.addView(ver, margins(WRAP, WRAP, dpi(9), 0, 0, 0));
        titles.addView(titleRow);
        TextView sub = text(engine.localName() + " ✏️ · " + engine.platform(), 12f, DIM, false);
        sub.setSingleLine(true);
        // ★ 点名字就能改（用户原话：「本机id最好支持自己编辑，不排除多人用同型号设备」）
        sub.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showRenameDialog();
            }
        });
        subLine = sub;
        titles.addView(sub);

        pill = text("搜索中…", 11.5f, DIM, false);
        pill.setPadding(dpi(10), dpi(5), dpi(10), dpi(5));
        stylePill(DIM);
        row.addView(pill);
        return row;
    }

    private View buildHintCard() {
        LinearLayout card = card();
        TextView t = text("同一 Wi-Fi 下打开这个 App，设备会自己出现 —— 不用配对、不用房间号、不用扫码。\n"
                + "📻 听广播 = 谁在广播就听谁（电脑上开着「广播我的声音」时用这个）；\n"
                + "🎧 收听 = 点对点，只收你点名的那一台（可多台混音）。\n"
                + "🔊 送声音 = 把【本机正在放的声音】送给它（音乐/视频/游戏）—— 这才是这个 App 的用法；\n"
                + "🎤 换成麦克风就是对讲（说给对面听）。\n"
                + "手机之间直连，关掉电脑照常工作。", 12f, DIM, false);
        t.setLineSpacing(dp(4), 1f);
        card.addView(t);
        return card;
    }

    /**
     * 一条「标签 + 电平条 + 说明」的行（设备卡片上的「收」/「送」）。
     *
     * <p>为什么要带文字标签：用户原话「每个设备都应当配备独立的收/送声音音频条」——
     * 光有两根条子还是分不清哪根是哪根，所以每条前面写「收」/「送」，后面写一句人话。
     */
    private LinearLayout levelRow(String tag, LevelBar bar, String tail) {
        return levelRow(tag, bar, tail, null);
    }

    /**
     * 同上的版本，但把右边那句说明的 TextView 交回给调用方，好让它每 150ms 跟着改。
     *
     * <p>★ 2026-10-16（图6）之后多了「信号」那一条（信号质量条），它的说明是动态的
     * （「78 分」/「不可测」），不能像原来那样在构造时写死。
     *
     * <p>标签宽度从 18dp 放宽到 28dp：现在会出现两个字（「信号」）。
     */
    private LinearLayout levelRow(String tag, LevelBar bar, String tail, TextView[] tailOut) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView k = text(tag, 11.5f, DIM, true);
        row.addView(k, new LinearLayout.LayoutParams(dpi(28), WRAP));

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, dpi(10));
        blp.weight = 1f;
        row.addView(bar, blp);

        TextView t = text(tail, 10.5f, DIM, false);
        row.addView(t, margins(WRAP, WRAP, dpi(8), 0, 0, 0));
        if (tailOut != null && tailOut.length > 0) {
            tailOut[0] = t;
        }
        return row;
    }

    /**
     * 一条「标签 + 可调滑杆 + 数值」的行（设备卡片上的「我听它的」/「我送它的」）。
     *
     * <p>为什么和电平条分开两条：用户原话「我要求的是各设备<b>可调节</b>的音量条。
     * 当然现在有的显示实际音量条也不错」—— 电平条看实时、滑杆动手调，两件事不合并。
     *
     * @param recv true = 收侧增益（只乘这一路的样本）；false = 送侧增益（只缩放发给这一台的包）
     */
    private LinearLayout gainRow(final Discovery.Peer peer, String tag, final SeekBar bar,
                                 final boolean recv) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView k = text(tag, 11f, DIM, false);
        row.addView(k, new LinearLayout.LayoutParams(dpi(52), WRAP));

        bar.setMax(200);              // 0.00 ~ 2.00，和电脑端滑杆**同一个上限**（2026-10-17 用户要求统一）
        bar.setProgress(100);
        bar.setProgressTintList(ColorStateList.valueOf(ACC));
        bar.setThumbTintList(ColorStateList.valueOf(ACC));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, WRAP);
        blp.weight = 1f;
        row.addView(bar, blp);

        final TextView val = text("1.00", 11f, DIM, false);
        val.setGravity(Gravity.END);
        val.setTypeface(Typeface.MONOSPACE);
        val.setTag("gainVal");                 // 只是标识：这行显示的是数值
        row.addView(val, new LinearLayout.LayoutParams(dpi(38), WRAP));
        bar.setTag(val);                       // 监听器里要更新它

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                float g = progress / 100f;
                Object t = sb.getTag();
                if (t instanceof TextView) {
                    setTextIfChanged((TextView) t, String.format(Locale.US, "%.2f", g));
                }
                if (fromUser && peer != null && peer.host != null && !peer.host.isEmpty()) {
                    // 立刻生效：收侧改这一路的样本增益，送侧改发给这一台的包
                    if (recv) {
                        engine.setPeerRecvGain(peer.host, g);
                    } else {
                        engine.setPeerSendGain(peer.host, g);
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                // ★ 2026-11-01 真机自测：拖动过程按 120ms 节流，松手时补发一次**最终值**。
                //   否则一次快速拖动的最后一个值可能被电脑端的 1.2 秒防抢挡掉，
                //   两端会停在中间值（实测松手 0.51，几秒后又弹回 1.45）。
                engine.pokeStats();
            }
        });
        return row;
    }

    /**
     * 未激活的可调音量条要和 PC 端长得一样：灰掉、不能拖、数值写「—」。
     *
     * <p>只改"表现"，不改引擎里的值 —— 链路一旦连上，下一帧 {@code sync()} 会恢复成
     * 真实值。PC 端（index.html 的 {@code gainTargetFor}）就是"没有那条路由/目标时
     * target 为 null → 滑杆 disabled"，这里用的是同一套判据。
     */
    private void styleGainBar(SeekBar bar, boolean active, float value) {
        if (bar == null) {
            return;
        }
        bar.setEnabled(active);
        bar.setAlpha(active ? 1f : 0.35f);
        Object tag = bar.getTag();
        if (tag instanceof TextView) {
            TextView shown = (TextView) tag;
            shown.setAlpha(active ? 1f : 0.35f);
            setTextIfChanged(shown, active
                    ? String.format(Locale.US, "%.2f", value) : "—");
        }
    }

    /**
     * 「送声音」按钮上的字：图标表达当前音源（🔊 本机声音 / 🎤 麦克风），后缀表达档位。
     *
     * ★ 2026-11-04（Q3 + 第 2 档）：同一颗按钮表达档位，循环 0 → 1 → 2 → 0。
     * 用户要求"别新加一排按钮"。
     */
    private static String sendChipLabel(int kind, int mode) {
        String src = (kind == AudioSend.SRC_PLAYBACK) ? "🔊" : "🎤";
        if (mode >= 2) {
            return src + " 送声中 · 手机静音";
        }
        if (mode == 1) {
            return src + " 送声中 · 外放";
        }
        return src + " 送声音";
    }

    /** 兼容旧调用：只看音源，按第 0 档文字。 */
    private static String sendChipLabel(int kind) {
        return sendChipLabel(kind, 0);
    }

    private View buildEmptyCard() {
        LinearLayout card = card();
        TextView t = text("正在搜索局域网里的设备…", 14.5f, FG, true);
        t.setGravity(Gravity.CENTER);
        card.addView(t);

        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setIndeterminate(true);
        pb.setIndeterminateTintList(ColorStateList.valueOf(ACC));
        card.addView(pb, margins(MATCH, dpi(4), 0, dpi(14), 0, 0));

        TextView h = text("让另一台手机 / 电脑打开 AudioFlow.LR，它就会自动出现在这里。", 12f, DIM, false);
        h.setGravity(Gravity.CENTER);
        card.addView(h, margins(MATCH, WRAP, 0, dpi(12), 0, 0));
        registerLong(dynLongTextViews, h);      // 教学文字：简洁档收起来

        // ★ 列表空着不等于没得听：电脑在广播时「听广播」照样能出声。
        //   真机现场最常见的就是"列表 0 台设备"，这时候必须给用户一条能走的路。
        TextView h2 = text("电脑上开着「广播我的声音」？直接点上面的「📻 听广播」就能听见 —— "
                + "不用等它出现在这个列表里。", 12f, ACC, false);
        h2.setGravity(Gravity.CENTER);
        card.addView(h2, margins(MATCH, WRAP, 0, dpi(10), 0, 0));

        TextView e = text("本机 " + engine.nodeName() + " · " + engine.localIpsText(),
                11.5f, DIM, false);
        e.setGravity(Gravity.CENTER);
        card.addView(e, margins(MATCH, WRAP, 0, dpi(6), 0, 0));
        registerLong(dynLongTextViews, e);      // 本机 IP/名字属于细节：简洁档收起来

        // 一张小抄：列表空着时按这个顺序查（和诊断报告里的【发现自检】对得上）
        TextView tip = text("一直搜不到设备？点「详情」看「发现统计」里的自检："
                + "如果写着\"从没收到过自己的信标\"，那是本机/路由器的组播问题（换 Wi-Fi、关掉 AP 隔离）。",
                11.5f, DIM, false);
        tip.setGravity(Gravity.CENTER);
        tip.setLineSpacing(dp(3), 1f);
        card.addView(tip, margins(MATCH, WRAP, 0, dpi(10), 0, 0));
        registerLong(dynLongTextViews, tip);    // 排查说明（原理/教程）：简洁档收起来
        return card;
    }

    private void kv(String label, String key) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView k = text(label, 12f, DIM, false);
        k.setTypeface(Typeface.MONOSPACE);
        row.addView(k, new LinearLayout.LayoutParams(dpi(70), WRAP));
        TextView v = text("—", 12f, FG, false);
        v.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams vlp = margins(0, WRAP, 0, 0, 0, 0);
        vlp.weight = 1f;
        row.addView(v, vlp);
        kvValues.put(key, v);
        detailWrap.addView(row, margins(MATCH, WRAP, 0, dpi(10), 0, 0));
    }

    // ────────────────────────────── 小工具 ──────────────────────────────

    /** 一张深色圆角卡片：rgba(255,255,255,.045) 底 + 1px 白边，对齐网页版 .card。 */
    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(roundRect(CARD_BG, dp(16), LINE, Math.max(1, dpi(1))));
        int p = dpi(14);
        l.setPadding(p, p, p, p);
        l.setLayoutParams(margins(MATCH, WRAP, 0, dpi(10), 0, 0));
        return l;
    }

    private TextView text(String s, float sizeSp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif-medium",
                bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    private static void setTextIfChanged(TextView t, String s) {
        String cur = (t.getText() == null) ? "" : t.getText().toString();
        String next = (s == null) ? "" : s;
        if (!next.equals(cur)) {
            t.setText(next);
        }
    }

    private void stylePill(int color) {
        int stroke = (color & 0x00FFFFFF) | 0x59000000;   // 同色 35% 透明描边
        pill.setBackground(roundRect(0x0AFFFFFF, dp(999), stroke, Math.max(1, dpi(1))));
        pill.setTextColor(color);
    }

    /**
     * ★ 2026-11-04（A 硬规则）：广播开着时「智连」按钮置灰 + 写明原因。
     * 只改按钮可用性/透明度/文案，不碰任何路由开关（广播不碰用户已勾的选择）。
     */
    private void applySmartAvailability(TextView chip, boolean available) {
        if (chip == null) {
            return;
        }
        chip.setEnabled(available);
        chip.setAlpha(available ? 1f : 0.45f);
        // ★ 2026-11-13（用户）：不可用时也只显示「智连」（反正都是灰的）。
        chip.setText("智连");
        chip.setContentDescription(available ? "智连"
                : "广播模式不能和智连组合：智连只服务具名点对点");
    }

    /** 开关按钮：关 = 透明底 + 细描边；开 = 蓝紫渐变实底（对齐网页版 .seg button.sel）。 */
    private void styleChip(TextView v, boolean on, String label) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(11));
        if (on) {
            d.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
            d.setColors(new int[]{ACC, ACC2});
            v.setTextColor(ON_TEXT);
        } else {
            d.setColor(0x12FFFFFF);
            d.setStroke(Math.max(1, dpi(1)), LINE);
            v.setTextColor(DIM);
        }
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), d, null));
        setTextIfChanged(v, label);
    }

    /** radius / strokeWidth 都按像素算（GradientDrawable 的单位就是 px），调用处用 dp() 换算。 */
    private static GradientDrawable roundRect(int fill, float radiusPx, int stroke, int strokePx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(radiusPx);
        if (strokePx > 0) {
            d.setStroke(strokePx, stroke);
        }
        return d;
    }

    private LinearLayout.LayoutParams margins(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.leftMargin = l;
        p.topMargin = t;
        p.rightMargin = r;
        p.bottomMargin = b;
        return p;
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private int dpi(float v) {
        return Math.round(dp(v));
    }

    // ────────────────────────────── 一行设备 ──────────────────────────────

    private final class PeerRow {
        final Discovery.Peer peer;
        final LinearLayout card;
        final TextView name;
        final TextView meta;
        final TextView state;
        final TextView listenChip;
        final TextView sendChip;
        /** ★ 2026-10-18 第 6 条：这台设备的「智连」（默认关；只自动开"听"）。 */
        final TextView smartChip;
        /** 「收」电平条：我从它收到多少（= 音量大小）。 */
        final LevelBar recvLevel;
        /** 「送」电平条：我送给它多少（逐目标统计，见 SendFanout）（= 音量大小）。 */
        final LevelBar sendLevel;
        /** ★ 「信号」条：这一路的链路质量（见 Engine.peerQualityFor）。 */
        final LevelBar signalLevel;
        /** 「信号」条右边那句动态说明（「78 分」/「不可测」）。 */
        final TextView signalTail;
        /** 「收」「送」两条右边那句动态说明（「声音大小 43%」）。 */
        final TextView recvTail;
        final TextView sendTail;
        /** ★ 「我听它的」可调滑杆（收侧增益，只影响这一路）。 */
        final SeekBar recvGainBar;
        /** ★ 「我送它的」可调滑杆（送侧逐目标增益）。 */
        final SeekBar sendGainBar;
        /** ★ 2026-11-13：「它的延迟」滑杆（0~300ms）—— 远程改那一台的输出延迟。 */
        final SeekBar delayBar;
        /** 延迟滑杆右边的数值（「60 ms」）。 */
        final TextView delayVal;
        /** 拖动时节流：最多 120ms 发一条 out_ctl delay（和「它的音量」同一个节奏）。 */
        private long lastOutDelaySentMs;
        /** 延迟滑杆下面那行读数：「迟到 2 · 丢 0」。 */
        final TextView delayStat;
        /** ★ 合并版：这一台的声音进哪只耳朵 —— [0] 左 / [1] 双 / [2] 右。 */
        final TextView[] channelChips = new TextView[3];
        /** ★ 2026-11-12：「全局模式 × 逐台分配」互相抵消时的那句提醒（没有冲突就隐藏）。 */
        private TextView chanWarn;
        /** ★ 「对方：…」那一行（两台设备互报的统计）。 */
        final TextView peerLine;
        private boolean styledOnce;
        private boolean styledListen;
        private boolean styledSend;
        private boolean styledSmart;
        // ★ 2026-11-04（A 硬规则）：广播开着时「智连」不可用；记住上次的可用状态，
        //   只在变化时改按钮，避免每 150ms 反复 setAlpha/setEnabled。
        private boolean styledSmartAvail = true;
        private int styledSrc = -1;
        /** ★ 2026-11-04（Q3）：这颗按钮上一次显示的档位（0/1/2），只在变化时重建。 */
        private int styledSendMode = -1;
        /** ★ 合并版：上一次画过的声道分配，避免 150ms 刷新时反复重设背景。 */
        private int styledChannel = -1;
        /**
         * ★ 2026-11-12：这台设备的「输出」控件（远程控制它的声道模式与音量）。
         * {@code null} = 对方没声明 outctl（电脑端/老版本）→ 整块不出现，不给死控件。
         */
        private TextView[] outChips;
        private SeekBar outVolBar;
        private TextView outVolVal;
        /** 上一次画过的「它的输出」/「它的音量」；-2 表示还没画过（要画一次）。 */
        private int styledOutCh = -2;
        private int styledOutVol = -2;
        /** 音量滑杆上一次真正发出去的时刻（拖动时别每个像素发一个 UDP 包）。 */
        private long lastOutVolSentMs;

        PeerRow(Discovery.Peer p) {
            this.peer = p;
            card = card();

            // ★ 2026-11-17 晚（用户反馈）：原来「设备名 / 详情」竖排、右边并排一个「它在接收」，
            //   详情那行被右侧状态挤掉半截 —— 于是"缓 512ms"这类排在 IP 后面的字段直接被裁掉，
            //   看着像被挡住。现在改成：
            //     第一行：设备名 +v3.01 ……………………  它在接收 ✓（右对齐，跟名字同一行）
            //     第二行：详情（独占整行宽度，不再被状态挤）
            LinearLayout head = new LinearLayout(MainActivity.this);
            head.setOrientation(LinearLayout.VERTICAL);

            LinearLayout titleRow = new LinearLayout(MainActivity.this);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);

            name = text(nameText(p), 15.5f, FG, true);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nlp = margins(0, WRAP, 0, 0, 0, 0);
            nlp.weight = 1f;
            titleRow.addView(name, nlp);

            state = text("", 12f, OK, true);
            state.setSingleLine(true);
            titleRow.addView(state, margins(WRAP, WRAP, dpi(8), 0, 0, 0));
            head.addView(titleRow, margins(MATCH, WRAP, 0, 0, 0, dpi(2)));

            meta = text(metaText(p), 12f, DIM, false);
            meta.setSingleLine(true);
            meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
            head.addView(meta, margins(MATCH, WRAP, 0, 0, 0, 0));
            card.addView(head);

            // ★ 两条独立电平条（收 / 送）—— 用户原话：
            //   「每个设备都应当配备独立的收/送声音音频条」。原来只有一条，看不出是收还是送。
            // ★ 2026-10-16 用户（图6）之后这两条的右边写**音量百分比**（不再是"我从它收到
            //   多少"这种说明），因为它们现在就是"音量显示条"。
            TextView[] sendTailOut = new TextView[1];
            sendLevel = new LevelBar(MainActivity.this, Math.max(2, dpi(3)));
            // ★ 2026-11-13（用户：「简洁里其他设备不是送，而是在接收」）：
            //   这些设备是【接收端在收我的广播】，标签按设备视角写：
            //   上面这条 = 我发给它的电平 →「它收」；下面这条 = 它发给我的 →「它送」。
            //   标签和右边说明同时改，避免出现「收…没有在送」那种打架。
            card.addView(levelRow("它收", sendLevel, "没在收", sendTailOut),
                    margins(MATCH, WRAP, 0, dpi(11), 0, 0));
            sendTail = sendTailOut[0];
            TextView[] recvTailOut = new TextView[1];
            recvLevel = new LevelBar(MainActivity.this, Math.max(2, dpi(3)));
            card.addView(levelRow("它送", recvLevel, "没在送", recvTailOut),
                    margins(MATCH, WRAP, 0, dpi(5), 0, 0));
            recvTail = recvTailOut[0];

            // ★ 2026-10-16 用户（图6）：「电脑手机现在都只有"送""收"两个条，现在定义为
            //   音量显示条。并添加一个信号质量条。」——前两条本来就是**峰值**（音量），
            //   这里补上第三条：信号质量。它只用实测计数器算（丢包比例 + 缓冲深度），
            //   测不到就如实写"不可测"，不编一个分数。
            TextView[] signalTailOut = new TextView[1];
            signalLevel = new LevelBar(MainActivity.this, Math.max(2, dpi(3)));
            card.addView(levelRow("信号", signalLevel, "—", signalTailOut),
                    margins(MATCH, WRAP, 0, dpi(5), 0, 0));
            signalTail = signalTailOut[0];

            // ★★ 可调音量条（用户原话：「我要求的是各设备【可调节】的音量条。
            //    当然现在有的显示实际音量条也不错」）—— 电平条保留，旁边再加一条可调滑杆。
            //    两条滑杆控制的都是【我们这一层】的增益：
            //      · 我听它的 → 只乘这一路的样本（AudioRecv.Stream.gain）
            //      · 我送它的 → 只缩放发给这一台的那份包（SendFanout.Target.gain）
            //    都不碰手机系统音量。
            recvGainBar = new SeekBar(MainActivity.this);
            sendGainBar = new SeekBar(MainActivity.this);
            // ★ 2026-11-13（用户要求）：「我听它的 / 我送它的」两根滑杆删除——没用。
            //   SeekBar 仍创建（刷新逻辑里有引用），只是不再加进卡片。

            // ★★ 合并版：逐台声道分配。把「音频流转」APK 的多设备/环绕思路
            //    落到 AudioMixing 本就有的多流混音上 —— 指定后，这一路先折单声道、
            //    只写进左槽或右槽，于是两台设备可以各占一只耳朵。
            LinearLayout chanRow = new LinearLayout(MainActivity.this);
            chanRow.setOrientation(LinearLayout.HORIZONTAL);
            chanRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView chanKey = text("它进我耳", 11f, DIM, false);
            chanRow.addView(chanKey, new LinearLayout.LayoutParams(dpi(60), WRAP));
            final String[] chanLabels = {"左耳", "双耳", "右耳"};
            final int[] chanValues = {1, 0, 2};
            for (int k = 0; k < 3; k++) {
                final int v = chanValues[k];
                TextView c = chip(chanLabels[k]);
                channelChips[k] = c;
                c.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        if (peer == null || peer.host == null || peer.host.isEmpty()) {
                            return;
                        }
                        engine.setPeerChannel(peer.host, v);
                        styledChannel = v;
                        styleChannelChips(v);
                        toast(peer.label() + " 的声音 → " + ChannelRouter.channelText(v)
                                + (v == 0 ? "（两边都放）" : "（另一边不再放它）"));
                    }
                });
                LinearLayout.LayoutParams chanLp = margins(0, dpi(38), k == 0 ? 0 : dpi(6), 0, 0, 0);
                chanLp.weight = 1f;
                chanRow.addView(c, chanLp);
            }
            // ★ 2026-11-14（用户要求）：其他设备的「它进我耳」这一行没用，不再显示。
            //   三个 chip 仍创建（刷新逻辑 styleChannelChips 会引用），只是不挂到卡片上。
            // card.addView(chanRow, margins(MATCH, WRAP, 0, dpi(9), 0, 0));

            // ★ 2026-11-12：**两级声道会互相抵消**，这件事必须写在卡片上。
            //   本机「仅左」+ 这一台「进右耳」= 右声道被清零、左声道里又没有它 → 整路没声音。
            //   之前没有任何提示，用户只会觉得"左右声道坏了"（这正是他 11-12 报的那句）。
            chanWarn = text("", 10.5f, WARN, false);
            chanWarn.setLineSpacing(dp(2), 1f);
            chanWarn.setVisibility(View.GONE);
            card.addView(chanWarn, margins(MATCH, WRAP, dpi(60), dpi(4), 0, 0));

            // ══════════════════════════════════════════════════════════════════
            //  ★ 2026-11-12（用户第 1、3 条）：**它的输出** —— 远程改这台设备的播放声道与音量
            //
            //  和上面那一行的区别（这一行必须说清楚，否则两个"声道"必然被混）：
            //    · 「它进我耳」 = 它的声音进**我的**哪只耳朵（只影响我这台手机）；
            //    · 「它的输出」 = **它自己**怎么出声（会影响所有听到它的人），是远程控制。
            //
            //  状态从它自己的统计包来（out_ch / out_vol），所以 A 改了 B，B 自己的界面、
            //  以及第三台 C 上 B 的那张卡片，都会在下一拍变成同一个值 —— 这就是用户要的
            //  「实时同步」，而不是各台各记一份猜测。
            //
            //  只有声明了 outctl:1 的对端才生成这两行（电脑端/老版本没有，不生成死控件）。
            // ══════════════════════════════════════════════════════════════════
            if (peer.outCtl) {
                LinearLayout outRow = new LinearLayout(MainActivity.this);
                outRow.setOrientation(LinearLayout.HORIZONTAL);
                outRow.setGravity(Gravity.CENTER_VERTICAL);
                outRow.addView(text("它的输出", 11f, DIM, false),
                        new LinearLayout.LayoutParams(dpi(60), WRAP));
                // ★ 2026-11-12：第 5 颗跟着本机改名 —— 左右互换已经换成「传送静音」，
                //   这里再写"互换"就是在向对面下发一个本机自己都没有的档。
                // ★ 2026-11-13 用户：「只有本机设备有传送静音，其它设备不要传送静音按钮」。
                //   远程给对面下发"传送静音"（它哑着但继续转我的声音）几乎用不上，
                //   而且容易和「静音」搞混 —— 这一排只留 4 档；本机自己的档里仍有传送静音。
                final String[] outLabels = {"立体声", "静音", "仅左", "仅右"};
                final int[] outValues = {ChannelRouter.MODE_STEREO, ChannelRouter.MODE_MUTE,
                        ChannelRouter.MODE_LEFT_ONLY, ChannelRouter.MODE_RIGHT_ONLY};
                outChips = new TextView[outLabels.length];
                for (int k = 0; k < outLabels.length; k++) {
                    final int modeValue = outValues[k];
                    TextView c = chip(outLabels[k]);
                    outChips[k] = c;
                    c.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View view) {
                            if (peer == null || peer.host == null || peer.host.isEmpty()) {
                                return;
                            }
                            String err = engine.remoteSetPeerChannel(peer.host, modeValue);
                            if (err.isEmpty()) {
                                // 指令只是"请它这么改"：按钮要等它自己回报才亮 ——
                                // 这样屏幕上亮着的永远是真实状态，不是我们的乐观猜测。
                                toast("已发给 " + peer.label() + "：" + ChannelRouter.modeText(modeValue)
                                        + "（它回报后按钮才亮）");
                            } else {
                                toast("没发出去：" + err);
                            }
                        }
                    });
                    LinearLayout.LayoutParams olp =
                            margins(0, dpi(38), k == 0 ? 0 : dpi(5), 0, 0, 0);
                    olp.weight = 1f;
                    outRow.addView(c, olp);
                }
                card.addView(outRow, margins(MATCH, WRAP, 0, dpi(6), 0, 0));

                LinearLayout outVolRow = new LinearLayout(MainActivity.this);
                outVolRow.setOrientation(LinearLayout.HORIZONTAL);
                outVolRow.setGravity(Gravity.CENTER_VERTICAL);
                outVolRow.addView(text("它的音量", 11f, DIM, false),
                        new LinearLayout.LayoutParams(dpi(60), WRAP));
                outVolBar = new SeekBar(MainActivity.this);
                outVolBar.setMax(100);
                outVolBar.setProgressTintList(ColorStateList.valueOf(ACC));
                outVolBar.setThumbTintList(ColorStateList.valueOf(ACC));
                LinearLayout.LayoutParams ovlp = new LinearLayout.LayoutParams(0, WRAP);
                ovlp.weight = 1f;
                outVolRow.addView(outVolBar, ovlp);
                outVolVal = text("—", 11f, DIM, false);
                outVolVal.setGravity(Gravity.END);
                outVolVal.setTypeface(Typeface.MONOSPACE);
                outVolRow.addView(outVolVal, new LinearLayout.LayoutParams(dpi(48), WRAP));
                card.addView(outVolRow, margins(MATCH, WRAP, 0, dpi(1), 0, 0));

                outVolBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                        // ★ 2026-11-15（用户要求）：所有音量步进 5% —— 1% 太细，拖不准。
                        //   拖动时先吸附到 5 的倍数，再往下走。
                        if (fromUser) {
                            int snapped = snap5(progress);
                            if (snapped != progress) {
                                // 视觉上吸附到 5% 网格；★ 不能 return，否则这条命令发不出去，
                                // 松手后刷新会按对端真实音量把滑杆弹回旧值。
                                sb.setProgress(snapped);
                                progress = snapped;
                            }
                            // 环绕运行期间这两台被锁定，手动拖动一律忽略（防打架）。
                            if (peer != null && engine.surroundLocks(peer.host)) {
                                return;
                            }
                        }
                        setTextIfChanged(outVolVal, progress + "%");
                        if (!fromUser || peer == null || peer.host == null
                                || peer.host.isEmpty()) {
                            return;
                        }
                        // 拖动时最多每 120ms 发一条：UDP 很小，但每个像素一条就是刷屏。
                        long now = android.os.SystemClock.elapsedRealtime();
                        if (now - lastOutVolSentMs < 120) {
                            return;
                        }
                        lastOutVolSentMs = now;
                        engine.remoteSetPeerVolume(peer.host, progress);
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar sb) { }

                    @Override
                    public void onStopTrackingTouch(SeekBar sb) {
                        // 松手一定补发一次最终值（拖动中间那些可能被节流丢掉）。
                        if (peer != null && peer.host != null && !peer.host.isEmpty()) {
                            lastOutVolSentMs = android.os.SystemClock.elapsedRealtime();
                            engine.remoteSetPeerVolume(peer.host, sb.getProgress());
                        }
                    }
                });
            }

            // ★★ 2026-11-13：「它的延迟」—— 远程改**那一台**的输出延迟（和「它的音量」
            //    同一套 out_ctl 机制，用户原话「其他设备上没有实时滑块 UI 更新，只有音量可以」）。
            //    两台设备放同一个声音时，Wi-Fi 好的先出声、差的慢半拍，听起来是两重唱；
            //    从任何一台手机上给快的那台加几十毫秒就对齐了 —— 拖动时对方屏幕上的
            //    「本机延迟」滑杆会实时跟着走，然后它把新值报回来，这里也对齐真相。
            //
            //    滑杆下面那行是**读数**（迟到 / 丢包）—— 它才是"延迟测试"：
            //    积压稳定就说明这台跟得上，可以放心加延迟；一路往上飘说明它在持续掉队，
            //    再加只会更糟，该把它挪近路由器。
            LinearLayout delayBox = new LinearLayout(MainActivity.this);
            delayBox.setOrientation(LinearLayout.VERTICAL);

            LinearLayout delayLine = new LinearLayout(MainActivity.this);
            delayLine.setOrientation(LinearLayout.HORIZONTAL);
            delayLine.setGravity(Gravity.CENTER_VERTICAL);
            delayLine.addView(text("它的延迟", 11f, DIM, false),
                    new LinearLayout.LayoutParams(dpi(60), WRAP));

            delayBar = new SeekBar(MainActivity.this);
            delayBar.setMax(AudioRecv.MAX_EXTRA_DELAY_MS / DELAY_STEP_MS);  // 0~300ms，一格 5ms
            delayBar.setProgressTintList(ColorStateList.valueOf(ACC));
            delayBar.setThumbTintList(ColorStateList.valueOf(ACC));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0, WRAP);
            dlp.weight = 1f;
            delayLine.addView(delayBar, dlp);

            delayVal = text("0 ms", 11f, DIM, false);
            delayVal.setGravity(Gravity.END);
            delayVal.setTypeface(Typeface.MONOSPACE);
            delayLine.addView(delayVal, new LinearLayout.LayoutParams(dpi(48), WRAP));

            delayStat = text("", 10.5f, DIM, false);
            delayBox.addView(delayLine);
            delayBox.addView(delayStat, margins(MATCH, WRAP, dpi(60), dpi(1), 0, 0));
            card.addView(delayBox, margins(MATCH, WRAP, 0, dpi(2), 0, 0));

            delayBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    int ms = progress * DELAY_STEP_MS;      // ★ 一格 = 5ms
                    if (delayVal != null) {
                        setTextIfChanged(delayVal, ms + " ms");
                        // ★ 2026-11-13（用户：「拖动它的延迟时颜色有点不对有 bug」）：
                        //   这里是【正在设定的值】，不是实测同步偏差。旧逻辑拿滑杆位置
                        //   相对全群最小延迟着色 —— 拖大反而变红（语义反了），而且返回 0
                        //   时不重置，颜色会残留上一种 = 看到的 bug。
                        //   设定值不着色（缓冲读数与同步颜色已按用户要求整体删除）。
                        delayVal.setTextColor(DIM);
                    }
                    if (!fromUser || peer == null || peer.host == null
                            || peer.host.isEmpty()) {
                        return;
                    }
                    // 拖动时最多每 120ms 发一条（和「它的音量」同一个节奏）：值走 out_ctl
                    // 落到**那一台**上，它屏幕上的「本机延迟」滑杆当场就动。
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now - lastOutDelaySentMs < 120) {
                        return;
                    }
                    lastOutDelaySentMs = now;
                    engine.remoteSetPeerDelay(peer.host, ms);
                }

                @Override
                public void onStartTrackingTouch(SeekBar sb) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar sb) {
                    // ★ 一格已 5ms，去掉 10ms 吸附；松手只补发最终值。
                    int ms = sb.getProgress() * DELAY_STEP_MS;
                    if (peer != null && peer.host != null && !peer.host.isEmpty()) {
                        lastOutDelaySentMs = android.os.SystemClock.elapsedRealtime();
                        engine.remoteSetPeerDelay(peer.host, ms);
                    }
                }
            });

            // ★ 「对方」那一行：两台设备互报的结果。用户原话是"不知道是哪一端没在发"，
            //   这一行就是答案 —— 对方自己说它有没有在发、它收了我多少。
            //   放宽在电平条下面（而不是塞进右上角），因为这句话有时候要换行。
            peerLine = text("", 11.5f, DIM, false);
            peerLine.setLineSpacing(dp(3), 1f);
            peerLine.setVisibility(View.GONE);
            card.addView(peerLine, margins(MATCH, WRAP, 0, dpi(9), 0, 0));

            LinearLayout btns = new LinearLayout(MainActivity.this);
            btns.setOrientation(LinearLayout.HORIZONTAL);

            listenChip = chip("🎧 收听");
            listenChip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onListenClick(peer);
                }
            });
            LinearLayout.LayoutParams llp = margins(0, dpi(44), 0, 0, 0, 0);
            llp.weight = 1f;
            btns.addView(listenChip, llp);

            sendChip = chip(sendChipLabel(engine.sourceKind()));
            sendChip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onSendClick(peer);
                }
            });
            // ★ 2026-11-04（第 4 条 + 第 2 档）：长按把三档顺序、静音语义、还原路径
            //   与副作用一次说全 —— 用户明确要求"详情/长按里写清"。
            sendChip.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    toast("送声音按一下换一档：0 停止 → 1 送声+外放 → 2 送声+手机静音 → 0。"
                            + Engine.SEND_MODE_SILENT_NOTE);
                    return true;
                }
            });
            LinearLayout.LayoutParams slp = margins(0, dpi(44), dpi(8), 0, 0, 0);
            slp.weight = 1f;
            btns.addView(sendChip, slp);

            // ★ 2026-10-18 用户第 6 条：第 4 颗按钮「智连」，**默认关闭**。
            //   手机端**只**自动开"听"（用户原话「手机端只能自动开启'听'，为了用户安全考虑」）——
            //   开了之后：这台设备送声音过来，本机自动开始收听它。
            //   绝不自动替本机往外送声音（那是把话筒往别人耳朵里塞，必须自己点）。
            smartChip = chip("智连");
            smartChip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // ★ 广播和智连不能组合：点击时再挡一次（即使按钮因刷新延迟还亮着）。
                    if (engine.isBroadcasting()) {
                        toast("广播模式不能和智连组合：智连只服务具名点对点。请先关闭广播。");
                        return;
                    }
                    boolean on = engine.toggleSmart(peer.host);
                    toast(on ? ("已开「智连」：" + peer.label() + " 送声音过来会自动收听")
                             : ("已关「智连」：" + peer.label() + " 以后要自己点「🎧 收听」"));
                    refreshNow();
                }
            });
            LinearLayout.LayoutParams mlp = margins(0, dpi(44), dpi(8), 0, 0, 0);
            mlp.weight = 1f;
            btns.addView(smartChip, mlp);

            card.addView(btns, margins(MATCH, WRAP, 0, dpi(12), 0, 0));
            sync();
        }

        /** ★ 合并版：三颗「左耳 / 双耳 / 右耳」按钮的亮暗，永远跟着引擎真实值走。 */
        private void styleChannelChips(int ch) {
            styleChip(channelChips[0], ch == 1, "左耳");
            styleChip(channelChips[1], ch == 0, "双耳");
            styleChip(channelChips[2], ch == 2, "右耳");
        }

        /**
         * 「它的输出」四颗按钮的亮暗 —— 依据是**对端自己报的** out_ch，不是本地猜测。
         *
         * <p>{@code mode < 0} = 它还没报过来（刚上线、或对方是老版本）：四颗全灭，
         * 界面由此能看出"这不是它选了立体声，而是还不知道"。
         */
        private void styleOutChips(int mode) {
            if (outChips == null) {
                return;
            }
            styleChip(outChips[0], mode == ChannelRouter.MODE_STEREO, "立体声");
            styleChip(outChips[1], mode == ChannelRouter.MODE_MUTE, "静音");
            styleChip(outChips[2], mode == ChannelRouter.MODE_LEFT_ONLY, "仅左");
            styleChip(outChips[3], mode == ChannelRouter.MODE_RIGHT_ONLY, "仅右");
            // ★ 2026-11-13 修复闪退：远程档已去掉「传送静音」按钮（数组只剩 4 格），
            //   这里必须判长度 —— 不然对面还报着 mode=4（老版本设过传送静音）时
            //   直接数组越界，设备列表一刷（每 150~300ms）就崩一次。
            if (outChips.length > 4) {
                styleChip(outChips[4], mode == ChannelRouter.MODE_SEND_MUTE, "传送静音");
            }
        }

        void sync() {
            // ★ 合并版：逐台声道分配也走「只在变化时重画」，和上面几颗按钮同一套规矩。
            int channelNow = engine.peerChannel(peer.host);
            if (channelNow != styledChannel) {
                styledChannel = channelNow;
                styleChannelChips(channelNow);
            }
            // ★ 2026-11-12：它自己报上来的播放状态（out_ch / out_vol）。
            //   只在变化时重画 —— 150ms 一轮，没必要每轮都 setBackground 一遍。
            if (outChips != null) {
                int outCh = engine.peerOutChannel(peer.host);
                if (outCh != styledOutCh) {
                    styledOutCh = outCh;
                    styleOutChips(outCh);
                }
                int outVol = engine.peerOutVolume(peer.host);
                if (outVol != styledOutVol) {
                    styledOutVol = outVol;
                    if (outVolVal != null) {
                        // -1 = 它还没报过（刚上线/还没回心跳）—— 写"—"，不拿 0 冒充。
                        setTextIfChanged(outVolVal, outVol < 0 ? "—" : (outVol + "%"));
                    }
                    if (outVolBar != null) {
                        // ★ 2026-11-15：环绕运行期间这两台的音量被摆动接管 —— 滑杆置灰锁定，
                        //   但进度条照样跟着摆动走（用户能看见"它在动"，只是拖不动）。
                        boolean locked = peer != null && engine.surroundLocks(peer.host);
                        if (outVolBar.isEnabled() == locked) {
                            outVolBar.setEnabled(!locked);
                            outVolBar.setAlpha(locked ? 0.4f : 1f);
                        }
                        if (outVol >= 0 && !outVolBar.isPressed()) {
                            outVolBar.setProgress(outVol);
                        }
                    }
                }
            }
            boolean listen = engine.isListening(peer);
            boolean send = engine.isSending(peer);
            // ★ 2026-11-12：把「两级声道打架」说清楚（见 chanWarn 的注释）。
            if (chanWarn != null) {
                int gmode = engine.channelMode();
                String warn = "";
                if (gmode == ChannelRouter.MODE_MUTE && (listen || engine.isBroadcasting())) {
                    warn = "本机现在是「静音」：这一路只会转给别的设备，本机不出声。";
                } else if (gmode == ChannelRouter.MODE_SEND_MUTE && listen) {
                    warn = "本机现在是「传送静音」：这一路只在送出去（别的设备能听到），本机自己不出声。";
                } else if (gmode == ChannelRouter.MODE_LEFT_ONLY && channelNow == 2) {
                    warn = "⚠ 本机「仅左」× 这一台「进右耳」= 互相抵消，它这一路现在完全没有声音。"
                            + "把本机改成「立体声」，或把这一台改成「双耳」。";
                } else if (gmode == ChannelRouter.MODE_RIGHT_ONLY && channelNow == 1) {
                    warn = "⚠ 本机「仅右」× 这一台「进左耳」= 互相抵消，它这一路现在完全没有声音。"
                            + "把本机改成「立体声」，或把这一台改成「双耳」。";
                }
                setTextIfChanged(chanWarn, warn);
                chanWarn.setVisibility(warn.isEmpty() ? View.GONE : View.VISIBLE);
            }
            // 按钮上的图标直接表达"送的是什么"：🔊 本机声音 / 🎤 麦克风。
            int src = engine.sourceKind();
            // ★ 2026-11-04（Q3）：档位只对"正在送这台"有意义；没送时显示第 0 档的文字。
            int sendMode = send ? engine.sendMode() : 0;
            boolean smart = engine.isSmart(peer.host);
            // ★ 2026-11-04（A 硬规则）：广播模式和智连不能组合。广播开着时按钮置灰、
            //   文案写"广播中不可用"，点击给出原因；关掉广播立刻恢复。UI 和引擎双保险
            //   （引擎 setSmart 也会拒绝，防止旧界面绕过）。
            boolean smartAvail = !engine.isBroadcasting();
            if (!styledOnce) {
                styledOnce = true;
                styledListen = listen;
                styledSend = send;
                styledSrc = src;
                styledSendMode = sendMode;
                styledSmart = smart;
                styledSmartAvail = smartAvail;
                styleChip(listenChip, listen, "🎧 收听");
                styleChip(sendChip, send, sendChipLabel(src, sendMode));
                styleChip(smartChip, smart, "智连");
                applySmartAvailability(smartChip, smartAvail);
            } else {
                if (listen != styledListen) {
                    styledListen = listen;
                    styleChip(listenChip, listen, "🎧 收听");
                }
                if (send != styledSend || src != styledSrc || sendMode != styledSendMode) {
                    styledSend = send;
                    styledSrc = src;
                    styledSendMode = sendMode;
                    styleChip(sendChip, send, sendChipLabel(src, sendMode));
                }
                if (smart != styledSmart) {
                    styledSmart = smart;
                    styleChip(smartChip, smart, "智连");
                }
                if (smartAvail != styledSmartAvail) {
                    styledSmartAvail = smartAvail;
                    applySmartAvailability(smartChip, smartAvail);
                }
            }

            // ★ 这里**故意没有**"广播开着就把逐台按钮变灰"那套 —— 用户明确否了：
            //   「广播是和路由独立开的…也不能对特定设备关闭听」。广播不碰任何一条路由的开关。
            //   （卡片上按钮的亮/暗只反映用户自己勾过的选择；见 Engine.isSending。）

            // ★ 两条电平条各画各的（用户要的"每个设备独立的收/送音频条"）：
            //   「收」= 我从它收到多少（对方那一路的收包电平，不是混音总值）；
            //   「送」= 我送给它多少（SendFanout 逐目标统计，这一路发不出去它会自己塌下去）。
            //   ★ 2026-10-16：广播开着时**每一台都真的在收到我的声音**（电台式），
            //     所以这条音量条不能只看"用户有没有单独勾过它" —— 那样会显示 0 而其实在送。
            //     按钮的亮/暗仍然只反映用户自己的勾选（广播不点亮卡片），这两件事分开。
            boolean broadcasting = engine.isBroadcasting();
            boolean getsMySound = send || broadcasting;
            float recvPeak = listen ? engine.recvPeakFor(peer.host) : 0f;
            float sendPeak = getsMySound ? engine.sendPeakFor(peer.host) : 0f;
            // ★ 2026-11-13（用户明确）：其他设备是接收端，口径一律按【那台设备】写：
            //   「它收」= 我发给它多少（它正在接收的），「它送」= 它发给我多少。
            //   数据源不变、标签和右边文字一起改，不会再出现标签与说明打架。
            recvLevel.setLevel(recvPeak);
            sendLevel.setLevel(sendPeak);
            // 「音量显示条」：右边写清楚这一条现在是多少 dB 平量的百分比。0 就是真没有声音
            // （不是"没在收"）—— 点了收听但对方没送声音时也走这一句，和电脑端的口径一致。
            // 方向写在文字里：一眼看出这条是"从它来"还是"往它去"。
            setTextIfChanged(recvTail, recvPeak > 0.001f
                    ? ("它送出 " + Math.round(recvPeak * 100) + "%")
                    : (listen ? "它送的是静音" : "没在送"));
            setTextIfChanged(sendTail, sendPeak > 0.001f
                    ? ("它收到 " + Math.round(sendPeak * 100) + "%")
                    : (getsMySound ? "它收的是静音" : "没在收"));
            // ★ 第三条：信号质量。只用实测计数器算；没有依据时（还没收到过它的包）
            //   条子留空、右边写"不可测" —— 不拿一个 0 冒充"链路很差"。
            int quality = engine.peerQualityFor(peer.host);
            if (quality < 0) {
                signalLevel.setLevel(0f);
                setTextIfChanged(signalTail, "还没收到它的心跳");
            } else {
                signalLevel.setLevel(quality / 100f);
                // ★ 2026-10-16 用户（图1）：「虽然二者没有收发声音，但是设备间信号应当存在」——
                //   没有音频流时这一格也要有读数（链路一直在）。右边那句话还必须说清
                //   这个分是拿什么量出来的：正在收它的声音 / 只是"在线"（心跳）。
                setTextIfChanged(signalTail, quality + " 分 · "
                        + (engine.recvReceivingFrom(peer.host) ? "正在收声音" : "在线"));
            }

            // ★ 两条可调滑杆跟着引擎里的真实值走（对方重新上线、接收器重建之后不会"跳回 1.00"）；
            //   正在被用户拖的那条不动（否则会跟手指打架）。
            float recvGain = engine.peerRecvGain(peer.host);
            float sendGain = engine.peerSendGain(peer.host);
            if (recvGainBar != null && !recvGainBar.isPressed()) {
                int want = Math.round(recvGain * 100);
                if (recvGainBar.getProgress() != want) {
                    recvGainBar.setProgress(want);
                }
            }
            if (sendGainBar != null && !sendGainBar.isPressed()) {
                int want = Math.round(sendGain * 100);
                if (sendGainBar.getProgress() != want) {
                    sendGainBar.setProgress(want);
                }
            }
            // ★ 2026-11-01 用户：「未激活的音量条应该像 PC 端一样是灰色的，手机端依旧是可调」。
            //   PC 端的判据是"这条链路现在有没有对象"：没有路由就灰掉、写「—」、不能拖。
            //   手机照抄同一套：
            //     · 我听它的 —— 只有真的有一条"它→我"的收听才可调；
            //     · 我送它的 —— 只有真的在送它（或广播覆盖到它）才可调。
            //   不能只看"这台设备在线"，否则会出现"调了一个根本不存在的音量"。
            styleGainBar(recvGainBar, listen, recvGain);
            styleGainBar(sendGainBar, getsMySound, sendGain);

            // ★ 2026-11-13：「它的延迟」滑杆跟着**那一台自己报的**真实值走 ——
            //   和「它的音量」同一个道理：延迟的真相只在它身上，谁都不许拿本地猜测冒充。
            //   对方没上报（老版本/电脑端）时保持原样，不伪装成 0。
            if (delayBar != null && !delayBar.isPressed()) {
                int wantDelay = engine.peerOutDelay(peer.host);
                int wantPos = wantDelay / DELAY_STEP_MS;   // 真实毫秒 → 滑杆格数
                if (wantDelay >= 0 && delayBar.getProgress() != wantPos) {
                    delayBar.setProgress(wantPos);
                    if (delayVal != null) {
                        setTextIfChanged(delayVal, wantDelay + " ms");
                        delayVal.setTextColor(DIM);   // 设定值不着色（缓冲读数/颜色已删）
                    }
                }
            }
            // ★ 2026-11-13（用户：「把缓冲读数完全删掉」）：全 App 界面不再出现缓冲数字。
            //   这里只保留迟到/丢包（纯计数器，读它不碰缓冲、不抢播放线程的锁）。
            if (delayStat != null) {
                if (listen && engine.recvReceivingFrom(peer.host)) {
                    long[] pk = engine.peerPacketStats(peer.host);
                    setTextIfChanged(delayStat, String.format(Locale.US,
                            "迟到 %d · 丢 %d", pk[0], pk[1]));
                } else {
                    setTextIfChanged(delayStat, "没在收这一台，读数无意义（延迟设置仍在它那台上）");
                }
            }

            String st;
            int color;
            // ★ 「实际生效状态」：点了「收听」不等于真的在收 —— 用户说的"路由模式和广播模式
            //   混淆"就出在这里。所以这里区分三种：真的在收 / 点了但没收到包 / 没在听。
            //   （broadcasting 上面算音量条时已经取过一次，这里不再查第二遍。）
            boolean reallyReceiving = listen && engine.recvReceivingFrom(peer.host);
            if (listen && send) {
                // ★ 2026-11-13：按设备视角写，不再把这台设备标成"送声"
                st = "收听✓ + 它在接收" + (broadcasting ? "（广播）" : "");
                color = OK;
            } else if (listen) {
                st = reallyReceiving ? "收听中 ✓ 正在收" : "收听中…（还没收到包）";
                color = reallyReceiving ? OK : WARN;
            } else if (send) {
                // ★ 2026-11-13（用户）：这是接收端在收，不是它在"送"
                st = "它在接收 ✓" + (broadcasting ? "（广播）" : "");
                color = WARN;
            } else if (broadcasting) {
                st = "它在接收 ✓（广播）";
                color = OK;
            } else if (peer.busy) {
                st = "对端忙";
                color = WARN;
            } else {
                st = "";
                color = DIM;
            }
            setTextIfChanged(state, st);
            state.setTextColor(color);

            // ★ 2026-11-17 晚：版本并进标题（设备名后面），所以这里整串重算。
            setTextIfChanged(name, nameText(peer));
            setTextIfChanged(meta, metaText(peer));

            // ★ 「对方：已发 5938 包 / 11.0 MB · 正在发 ✓」/「没在发 ✗」那一行。
            //   只在有「收听/送声音」关系时显示：没关系时对方本来就不会回话，
            //   显示了反而让人以为"对方坏了"。
            //   ★ 三档差异：简洁档不显示（"打开就知道点哪里"），详细/监控档显示。
            if ((listen || send) && viewMode != MODE_SIMPLE) {
                long nowMs = SystemClock.elapsedRealtime();
                Diagnosis.PeerStats ps = engine.peerStats(peer.host);
                setTextIfChanged(peerLine, Diagnosis.cardLine(ps, nowMs));
                // 颜色也表达结论：绿=对面在发、黄=对面没在发、灰=还没回话/已过期。
                int pc = DIM;
                if (ps != null && ps.fresh(nowMs)) {
                    pc = ps.sending ? OK : WARN;
                }
                if (peerLine.getCurrentTextColor() != pc) {
                    peerLine.setTextColor(pc);
                }
                peerLine.setVisibility(View.VISIBLE);
            } else {
                peerLine.setVisibility(View.GONE);
            }
        }
    }

    /**
     * ★ 2026-11-17 晚（用户要求）：版本紧跟在设备名称后面，例如「小米盒子 v3.01」。
     * 以前版本塞在下面那行详情里，既占地方又容易被裁掉。
     */
    private String nameText(Discovery.Peer p) {
        String n = p.label();
        if (p.version != null && !p.version.isEmpty()) {
            n = n + " v" + p.version;
        }
        return n;
    }

    /**
     * 设备卡片名字下面那一行详情（★ 2026-11-17 晚重排）。
     *
     * <p>用户反馈：排在 IP 后面的「缓冲 xxxms」被右侧状态挤掉、看不见。现在状态已经移到
     * 第一行（跟设备名同一行），这行独占整行宽度；字段顺序也按重要性排：
     * <b>IP · 补包协议 + 实际缓冲 · 平台 · 有网页面板</b> —— 缓冲紧跟在 IP 后面第二段，
     * 就算以后还要截断，先被截掉的也是末尾的平台/面板。
     *
     * <p>缓冲显示的是对方<b>实际缓冲了多少 ms</b>（它上报的 my_buf），不是配置档位值。
     */
    private String metaText(Discovery.Peer p) {
        StringBuilder sb = new StringBuilder();
        if (p.host != null && !p.host.isEmpty()) {
            sb.append(p.host);
        }
        // 补包协议 + 实际缓冲（对方统计上报；-1 = 老版本没报）
        Diagnosis.PeerStats ps = engine.peerStats(p.host);
        if (ps != null && ps.nackOn >= 0) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(ps.nackOn == 1 ? "NACK" : "副本");
            int bufMs = ps.myBufMs > 0 ? ps.myBufMs : ps.bufTierMs;
            if (bufMs > 0) {
                sb.append(" 缓 ").append(bufMs).append("ms");
            }
        }
        String plat = (p.platform == null || p.platform.isEmpty()) ? "未知平台" : p.platform;
        if (sb.length() > 0) {
            sb.append(" · ");
        }
        sb.append(plat);
        if (p.webPort > 0) {
            sb.append(" · 有网页面板");
        }
        return sb.toString();
    }

    /**
     * ★ 2026-11-17：刷新「缓冲档位」chip 的文字 + 高亮。
     *
     * <p>★ 2026-11-17 晚（用户定案）：chip 永远只显示 700/1000/1500 这三档。
     * 切到 NACK 重传后，三个 chip 全部灰掉且不可点 —— 因为 NACK 的缓冲固定 500ms，
     * 没有档位可选。标题同时换成「NACK 重传固定 500ms」的说明，避免看着像坏了。
     */
    private void refreshBufChips() {
        boolean nack = engine.nackEnabled();
        int cur = engine.recvBufMs();
        int[] tiers = Engine.RECV_BUF_TIERS;
        if (bufHeaderText != null) {
            setTextIfChanged(bufHeaderText, nack
                    ? "缓冲档位（NACK 重传固定 500ms，下面三档不可选 · 切回冗余副本才可调）"
                    : "缓冲档位（越大越不卡，延迟也越大 · 点一下同步到所有设备）");
        }
        if (bufChips != null) {
            for (int i = 0; i < bufChips.length; i++) {
                int t = i < tiers.length ? tiers[i] : -1;
                if (nack) {
                    // 灰掉：不亮、不可点、文字压暗一点点
                    styleChipDisabled(bufChips[i], (t >= 0 ? t + "ms" : ""));
                } else {
                    styleChip(bufChips[i], t == cur, t >= 0 ? t + "ms" : "");
                    // 从 NACK 切回来时把「可点」恢复（灰显时被关掉了）
                    bufChips[i].setEnabled(true);
                    bufChips[i].setClickable(true);
                }
            }
        }
    }

    /** ★ 2026-11-17：不可选状态（灰掉）—— 比普通未选中更暗，且彻底不响应点击。 */
    private void styleChipDisabled(TextView v, String label) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(11));
        d.setColor(0x08FFFFFF);                       // 比未选中更淡
        d.setStroke(Math.max(1, dpi(1)), 0x0DFFFFFF);
        v.setBackground(d);                           // 无 ripple、无按压反馈
        v.setTextColor(0xFF55657A);                   // 灰字
        v.setClickable(false);
        v.setEnabled(false);
        setTextIfChanged(v, label);
    }

    /** ★ 2026-11-17：刷新补包协议切换 chip 的高亮。 */
    private void refreshNackChips() {
        boolean nack = engine.nackEnabled();
        if (nackChipOld != null) {
            styleChip(nackChipOld, !nack, "冗余副本 稳");
        }
        if (nackChipNew != null) {
            styleChip(nackChipNew, nack, "NACK 重传 低延迟");
        }
    }

    private TextView chip(String label) {
        TextView t = text(label, 14.5f, DIM, true);
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        t.setFocusable(true);
        styleChip(t, false, label);
        return t;
    }

    // ────────────────────────────── 自绘小控件 ──────────────────────────────

    /** 电平条：22 段小方块，从青到绿，和网页版那排竖条一个观感。 */
    private static final class LevelBar extends View {
        private static final int SEG = 22;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int gap;
        private float shown;

        LevelBar(Context c, int gapPx) {
            super(c);
            gap = gapPx;
        }

        void setLevel(float v) {
            if (Float.isNaN(v) || v < 0f) {
                v = 0f;
            }
            if (v > 1f) {
                v = 1f;
            }
            // 快起慢落：真实电平表都是这个观感，否则 150ms 一刷会闪得看不清。
            shown = Math.max(v, shown * 0.72f);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            float segW = (w - gap * (SEG - 1)) / SEG;
            if (segW <= 0) {
                segW = w / SEG;
            }
            int lit = Math.round(shown * SEG);
            for (int i = 0; i < SEG; i++) {
                float left = i * (segW + gap);
                p.setColor(i < lit ? blend(ACC, OK, i / (float) (SEG - 1)) : TRACK);
                cv.drawRoundRect(left, 0, left + segW, h, h / 2f, h / 2f, p);
            }
        }

        private static int blend(int a, int b, float t) {
            int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
            int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
            return 0xFF000000
                    | ((int) (ar + (br - ar) * t) << 16)
                    | ((int) (ag + (bg - ag) * t) << 8)
                    | (int) (ab + (bb - ab) * t);
        }
    }

    /**
     * 顶部左上角那枚角标：蓝色渐变圆角方块 + 白色「A」。
     *
     * <p>★ 2026-10-18 用户：「现在这个版本的手机版，左上角角标还没有更换」。
     * 上一轮只换了**桌面上那个启动图标**（mipmap 里的 ic_launcher）和电脑端/网页上的方块，
     * 手机 App 界面里这枚角标画的还是旧标识那四条音频柱 —— 里外就对不上，正是用户图3 说的那件事。
     * 现在这里画的就是同一套东西：140° 蓝色渐变（{@link #ACC} → {@link #ACC2}）圆角方块 + 白 A，
     * 和 exe 图标、APK 图标、电脑面板、监控窗逐字一致。
     */
    private static final class LogoView extends View {
        private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint wave = new Paint(Paint.ANTI_ALIAS_FLAG);

        LogoView(Context c) {
            super(c);
            // ★ 2026-11-22 新 logo：与应用图标一致——蓝色渐变方块 + 白色声波。
            wave.setColor(ON_TEXT);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            box.setShader(new LinearGradient(0, 0, w, h, ACC, ACC2, Shader.TileMode.CLAMP));
            cv.drawRoundRect(0, 0, w, h, w * 0.30f, w * 0.30f, box);
            // 白色声波：4 条竖条，中间高两边低（对应桌面图标的音波，38dp 下塞不下 L/R）。
            float barW = w * 0.075f;
            float mid = w / 2f;
            float cy = h / 2f;
            float[] xs = {mid - w * 0.225f, mid - w * 0.075f, mid + w * 0.075f, mid + w * 0.225f};
            float[] hs = {h * 0.30f, h * 0.50f, h * 0.50f, h * 0.30f};
            for (int i = 0; i < xs.length; i++) {
                float top = cy - hs[i] / 2f;
                cv.drawRoundRect(xs[i] - barW / 2f, top, xs[i] + barW / 2f, top + hs[i],
                        barW / 2f, barW / 2f, wave);
            }
        }
    }

    /** 页面底色：深蓝黑 + 顶部偏蓝、右下偏紫两团辉光（网页版的 CSS 渐变用 Canvas 重画）。 */
    private static final class PageBg extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        @Override
        public void draw(Canvas c) {
            int w = getBounds().width();
            int h = getBounds().height();
            if (w <= 0 || h <= 0) {
                return;
            }
            c.drawColor(BG);
            p.setShader(new RadialGradient(w * 0.5f, -h * 0.10f, Math.max(1f, w * 0.78f),
                    0xFF14324D, 0x0014324D, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(new RadialGradient(w * 1.10f, h * 1.10f, Math.max(1f, w * 0.72f),
                    0xFF2A1B52, 0x002A1B52, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }
}
