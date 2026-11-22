package com.audiomixing.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.projection.MediaProjection;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 引擎：把「自主发现（Discovery）」「送声音（AudioSend）」「收听（AudioRecv）」串成一台可用的节点。
 *
 * <p>设计意图（见 docs/Android端设计.md 第四、五节）：
 * <ul>
 *   <li><b>没有配对、没有房间号、没有二维码。</b>Discovery 收到谁的信标，谁的卡片就出现在界面上，
 *       点一下就能用。电脑在不在都一样 —— 手机之间直连，不借任何中转。</li>
 *   <li>引擎是<b>进程级单例</b>：界面（MainActivity）和前台服务（MixService）共用同一个实例，
 *       所以 App 退到后台、界面被销毁，声音也不会断。</li>
 *   <li>发现 / 采集 / 播放各自在自己的线程里跑（见各自的类注释），这里只做状态簿记，
 *       <b>绝不阻塞界面线程</b>。</li>
 * </ul>
 *
 * <p>线程约定：本类的方法都可以从界面线程调。除了 {@link #peers()} 与
 * {@link #recvStats()} 会去读对方的快照（都是纯内存操作、对方也做了同步），其余纯状态读写。
 */
public final class Engine {

    private static final String TAG = "AudioMixing";

    /** 统计快照的复用窗口：界面每 150ms 刷一次，没必要每次都新建一个 Stats。 */
    private static final long STATS_TTL_MS = 120L;

    /** ★ 对端统计的交换周期：每 2 秒互报一次（用户说的"让俩设备自己交流"）。 */
    private static final long STATS_INTERVAL_MS = 2000L;
    /** 收到的对端统计保留多久：过期的留在内存里没意义（界面会显示"没在回话"）。 */
    private static final long PEER_STATS_KEEP_MS = 60000L;
    /** 最多记多少台的对端统计：局域网里不会有这么多，防的是被人乱刷撑爆内存。 */
    private static final int PEER_STATS_MAX = 32;

    private static volatile Engine sInstance;

    /** 取进程内唯一的引擎（Activity 与 Service 共用，保证退到后台不断流）。 */
    public static Engine get(Context ctx) {
        Engine e = sInstance;
        if (e != null) {
            return e;
        }
        synchronized (Engine.class) {
            if (sInstance == null) {
                Context app = (ctx != null) ? ctx.getApplicationContext() : null;
                sInstance = new Engine(app != null ? app : ctx);
            }
            return sInstance;
        }
    }

    private final Context app;
    private final Object lock = new Object();

    private volatile Discovery discovery;
    /** 同一时刻只送一路，换目标时先停旧的。 */
    private volatile AudioSend sender;
    /** 一个 UDP 端口收，收到几台就混几台 —— 这本来就是个"混音"工具。 */
    private volatile AudioRecv receiver;

    // ── ★ 送什么声音（用户指出过方向性缺陷：手机端不能只当对讲机）──

    /**
     * 用户选的音源。<b>默认"本机声音"</b>—— 这才是这个 App 的定位（把设备正在放的声音送出去），
     * 麦克风只是给对讲场景留的备选。
     *
     * <p>低于 Android 10 的机器没有 {@code AudioPlaybackCapture} 这个能力，
     * 构造时就直接定死麦克风 —— <b>不是"选了再说"，是根本不给选</b>，
     * 界面上会写明"你的安卓版本不支持"。
     */
    private volatile int sourceKind = AudioSend.playbackSupported()
            ? AudioSend.SRC_PLAYBACK : AudioSend.SRC_MIC;
    /**
     * ★ 音源开关（2026-10-19 用户第 4 条）：true = 按 sourceKind 采集，false = 已停止。
     *
     * <p>用户原话：「这俩按键要真实显示用户设备实际情况。点击未激活按键即为激活，再次点击即为关闭」。
     * 由用户点「🔊 本机声音」/「🎤 麦克风」切换（见 {@link SourcePolicy#toggle}）。
     * 它与 {@link #sourceKind} 一起，就是界面那两颗按钮的唯一真实来源。
     *
     * <p>★ 2026-11-12 用户：「软件一打开就是本机声音勾选的状态，实际应该是没有勾选的」——
     * 所以这里默认 <b>false</b>：一开 App 两颗音源按钮都不亮、什么都没在采。
     * 用户点了「送声音」而当时没有任何音源时，{@link #setSending} 会自动切到麦克风
     * （麦克风不需要额外授权，不会先弹一个投屏框把人吓一跳）。
     */
    private volatile boolean sourceEnabled = false;
    /** 上一次"音源被停掉"的原因（如实写进状态行；空 = 没停过）。 */
    private volatile String sourceStopReason = "";

    /**
     * 采本机声音要用的授权（MediaProjection）。<b>刻意在拿到之后一直留着</b>：
     * targetSdk 34 / Android 14 起，每次授权只能换一个 MediaProjection 对象，
     * 用完就丢的话用户每切一次音源都要再看一次系统弹窗。
     */
    private volatile MediaProjection projection;
    /** 授权失败的原因。界面和诊断报告必须把它写出来，不能让用户以为"选了就已经在送本机声音"。 */
    private volatile String projectionError = "";
    /** 授权弹窗已经弹出去、还没回来的那台设备 id（授权回来之后自动开始送）。 */
    private volatile String pendingSendId = "";
    /** 主线程 Handler：MediaProjection 的回调要用它。 */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /**
     * 授权被系统收回的回调。<b>刻意懒创建</b>：MediaProjection.Callback 是 API 29 才有的类，
     * 在构造器里 new 出来会让 Android 9 及以下的机器一开 App 就 NoClassDefFoundError。
     */
    private MediaProjection.Callback projectionCallback;

    /** 用户点了「收听」的设备 id（可以多台）。 */
    private final Set<String> listenIds = new LinkedHashSet<String>();
    /**
     * 收听目标的地址（按 id 记）。
     *
     * <p>为什么要单独记：白名单原本是在 applyAllowedHosts() 里现查 peers() 得到的，
     * 而 Wi-Fi 上偶尔丢一两个信标是常态。设备只要在列表里闪一下，白名单就会退化成
     * "只允许 255.255.255.255"，于是一个包都收不进来 —— 真机现场表现就是
     * "界面还显示收听中，但缓冲 0ms、流 0"，而且自己永远好不了。
     * 把地址记在用户点选的那一刻，就不受设备列表抖动影响了。
     */
    private final Map<String, String> listenHosts = new LinkedHashMap<String, String>();
    /** 用户点了「送声音」的设备 id（只会有 0 或 1 台）。 */
    private final Set<String> sendIds = new LinkedHashSet<String>();

    /**
     * ★ 2026-11-04（Q3）：「送声音」档位 —— 0 未激活 / 1 送声+外放 / 2 送声+静默。
     *
     * <p>为什么要三档：真机复测时用户发现「手机把声音送出去的同时本机外放也在响」，
     * 而他要的是"只听电脑那头的"。档位只影响<b>本机外放的静音</b>，不影响送不送；
     * 送不送仍然看 {@link #sendIds} / {@link #broadcastOn}。
     *
     * <p>档位随统计包上报（{@code send_mode} + {@code broadcast}），电脑端据此决定准入 ——
     * 第 1/2 档都属于"对端声明要送"，区别只是本地外放。
     */
    private volatile int sendModePref = 1;
    /** 采集/发送是否**真的在跑**（目标集非空且音源可用）。第 2 档只在这时候压音量。 */
    private volatile boolean sendingActive;

    /**
     * ★ 2026-11-08（用户方案）：静音档乘数策略 —— {@code internal_only}（默认，1.0）
     * 或 {@code last_nonzero}（沿用进入静音前最后一次非零系统音量）。
     *
     * <p>外放档永远乘当前系统媒体音量；这个开关只影响静音档。手机界面与电脑设置
     * 共用这一份状态（电脑通过 T_STATS 下发）。
     */
    private volatile String silentVolPolicy = SendVolume.POLICY_INTERNAL;
    /** ★ 2026-11-12：音量键控制"对方"音量时的当前值（0~100，随本机存盘）。 */
    private volatile int remoteVol = 100;
    /**
     * ★ 2026-11-16：每台设备最后一次下发的音量（host → 0~100），仅存内存。
     * 作为"相对增减"的基准：比对端回报更及时，连续快按不会读到旧值。
     * 重启后为空，第一次按键回退到对端回报的真实音量（那时反而最准）。
     */
    private final Map<String, Integer> peerVolShadow =
            new java.util.HashMap<String, Integer>();
    /** 每台电脑发来的静音档策略请求号（同一个号只处理一次）。 */
    private final Map<String, Long> silentPolicyReqSeen = new java.util.HashMap<String, Long>();

    /**
     * ★ ④ 用户在哪几台上选了「走蓝牙」（按地址记）。
     *
     * <p>用户原话（2026-10-15）：「手机确认走蓝牙后应当由电脑发送蓝牙请求并配对互相连接。」
     * 所以手机这边只负责**把这件事告诉电脑**（每 2 秒跟着统计包重复请求，幂等），
     * 真正去连的是电脑那边（电脑手里才有蓝牙设备列表）。
     *
     * <p>按地址而不是按 id：统计包是按地址逐台发的，这里必须能和那一处对上。
     * 界面线程添/删、发包线程读，所以用同步包装。
     */
    private final Map<String, Integer> btWantPorts =
            java.util.Collections.synchronizedMap(new LinkedHashMap<String, Integer>());

    /**
     * ★ 刚停掉「送声音」/「收听」之后，还要**再报几轮统计**的设备。
     *
     * <p>为什么需要（2026-10-15 用户反馈④）：「手机 WiFi 送声音关闭后，电脑听的选项不会自动关」。
     * 因为统计包的收件人就是"和我有关系的那些设备"——我这边一停，收件人立刻没了，
     * 电脑**再也收不到 want_listen=false**，只能一直以为自己还在被收听。
     * 所以停掉之后继续报 {@link #STATS_STOP_GRACE_MS} 毫秒，把"我停了"这件事送达。
     */
    private static final long STATS_STOP_GRACE_MS = 30000L;

    /**
     * ★ 对端"彻底没消息"多久之后，把它【替我们自动打开】的收听收回来（2026-10-15 自查补的缺口）。
     *
     * <p>为什么光靠 want_listen=false 不够：那条消息只有在<b>对端还在跑</b>时才送得到。
     * 对端（电脑）直接把 App 关掉、或者整台机器走了，就没有任何人来说这句 false ——
     * 那条自动打开的收听会一直挂着，界面显示"正在收听 XXX"，其实那边什么都没在发。
     * （用户第 3 条抱怨的正是这种"停不下来"，只是成因是关程序而不是关送声音。）
     *
     * <p>90 秒：对端每 2 秒就报一次统计，这个时长足够容忍 Wi-Fi 抖动和几轮丢包；
     * <b>只收自动开的那些</b>，用户自己点开的收听一律不碰（和 want_listen=false 一个边界）。
     */
    private static final long AUTO_LISTEN_STALE_MS = 90000L;
    /**
     * ★ 2026-11-04（A3）：智连自动收听的双判据阈值（毫秒），与电脑端
     * {@code AUTO_LISTEN_HANG_S = 4.0} 一致。
     * 音频包静默 + 对端统计心跳都超过它才收回；静音音频仍有包 ⇒ 永不误挂。
     */
    private static final long AUTO_LISTEN_HANG_MS = 4000L;
    /** 上一轮真正发过统计的地址（用来发现"刚刚停掉的那一台"）。 */
    private final Set<String> lastStatsHosts = new LinkedHashSet<String>();
    private final Map<String, Integer> statsGracePorts = new LinkedHashMap<String, Integer>();
    private final Map<String, Long> statsGraceUntil = new LinkedHashMap<String, Long>();

    /**
     * ★ 「听广播」（频道模式）：不挑发送方，谁往本机音频端口发声音就播谁。
     *
     * <p><b>为什么必须单独有这么一个开关</b>：我们的设计里广播和点对点是两条通道 ——
     * 电脑开着「广播我的声音」时，声音是发给<b>所有在线节点</b>的；而手机原来只会播
     * "用户点过🎧收听的那几台"（白名单），所以电脑广播过来的包会被按名单过滤掉。
     * 真机现场就是这样：电脑在广播，手机一个包都没播（"按名单过滤"那一条能看出来）。
     *
     * <p><b>为什么是"谁来都听"而不是"只听广播"</b>：音频包里没有路由标记（电脑端协议注释
     * 里写得很清楚：{@code An audio packet carries no route id}），接收端从包本身分不出
     * "这是广播给我的"还是"这是点对点给我的"。所以这里如实按"谁的都听"实现，
     * 界面上也照实这么写，不假装能分辨。
     */
    private volatile boolean channelListen;

    /** 系统媒体会话：让 Android 把我们识别成"正在放媒体的 App"（分应用音量用）。 */
    private volatile MediaSession mediaSession;
    /** 当前媒体会话是不是"远程音量"模式（true = 音量键被我们接管）。 */
    private boolean sessionRemote;

    /**
     * ★ 「📢 广播我的声音」：我的声音同时送给发现列表里每一台在线设备。
     *
     * <p>和上面那个「听广播」是一对：一个是"我收谁的"，一个是"我发给谁"。
     * 采集永远只有一份（一次采集 → 一包编码 → 分发给 N 个目标），
     * 所以开着广播不会多占一份麦克风/MediaProjection。
     */
    private volatile boolean broadcastOn;
    /** 上一次同步给发送端的目标签名：没变就不重复设置（界面 tick 每 150ms 调一次）。 */
    private volatile String lastTargetSig = "";

    // ── ★ ④ 对端活性（安全检查：对方关掉了就不能再发）──
    /** 目标 id → 它的地址（发现列表里掉出去之后还要靠它判断/恢复）。 */
    private final Map<String, String> sendHosts = new LinkedHashMap<String, String>();
    /** 目标 id → 最近一次"确认它还活着"的时刻（elapsedRealtime）：信标或心跳都算。 */
    private final Map<String, Long> sendLastAliveMs = new LinkedHashMap<String, Long>();
    /** "已停止向 XXX 发送"的说明（空 = 没有暂停的目标）。 */
    private volatile String sendSuspendedText = "";

    /** Discovery 回调线程推过来的快照；peers() 优先问 Discovery，失败时退回这份。 */
    private volatile List<Discovery.Peer> cache = Collections.emptyList();

    private volatile boolean started;
    private volatile boolean sawPeer;      // 这次运行里至少见过一台设备（用于判断"目标是真的走了"）
    private volatile float volume = 1f;
    private volatile int audioPort = Proto.DEFAULT_AUDIO_PORT;
    private volatile String lastSendError = "";

    private volatile AudioRecv.Stats statsCache;
    private volatile long statsCacheAt;

    // ── ★ 对端统计交换（用户要的"让俩设备自己交流"）──

    /** 对端自己报的统计，按来源 IP 存。key = host，value 每次收到都换成新对象（不再修改）。 */
    private final Map<String, Diagnosis.PeerStats> peerStats =
            new LinkedHashMap<String, Diagnosis.PeerStats>();
    /**
     * 专门保护 {@link #peerStats} 的锁，<b>刻意不复用 {@link #lock}</b>。
     *
     * <p>为什么：写这份统计的是<b>收包线程</b>（AudioRecv 的回调），而 {@link #lock} 会被
     * 界面线程拿着去停接收器（{@code stopReceiver()} 里有 join 等待收包线程退出）。
     * 如果两边抢同一把锁，就变成"界面线程等收包线程、收包线程等界面线程"——
     * 靠 join 的超时能解开，但界面会白卡一下。分开锁就完全没有这个交集。
     */
    private final Object peerLock = new Object();
    /**
     * 收听目标的端口（和 listenHosts 一起、在用户点选那一刻记下来）。
     *
     * <p>为什么要单独记端口：统计包要单播到对方的 audio_port（信标里公布的那个）。
     * 跟地址同样的道理 —— 设备列表抖一下不至于让统计发不出去。
     */
    private final Map<String, Integer> listenPorts = new LinkedHashMap<String, Integer>();

    /** 统计包发送线程：每 2 秒给"和我有关系的每一台"各发一个。 */
    private volatile Thread statsThread;
    private volatile boolean statsRunning;
    private volatile long statsSent;
    private volatile String statsLastError = "";
    /** 谁刚动了音量就置位（见 {@link #pokeStats()}）：让发送循环立刻醒一轮。 */
    private volatile boolean statsPoke;
    private final Object statsWake = new Object();
    /** 统计包的固定 ssrc：不是音频流，只为了在对端日志里能认出是同一个人发的。 */
    private final int statsSsrc = ((int) System.nanoTime()) | 1;

    private Engine(Context ctx) {
        this.app = ctx;
        // ★ 第 2 档崩溃兜底：上次进程被杀时如果停在"手机静音"，这里先还原再继续。
        //   必须在 loadSendMode 之前 —— 否则会先按第 2 档去 sync，短暂再压一次音量。
        restoreSilentVolumeIfNeeded();
        loadSmartHosts();   // 从本地把「智连」名单读回来（默认空 = 一台都不自动听）
        loadSendMode();     // ★ 2026-11-04（Q3）：上次选的送声音档位（默认第 1 档）
        loadSilentVolPolicy();   // ★ 2026-11-08：静音档乘数策略（默认 internal_only）
        loadChannelSettings();   // ★ 合并版：左右声道模式/平衡/左右音量/逐设备分配/输出延迟
        loadSurroundSettings();  // ★ 2026-11-15：环绕全部参数（记住上次的区间模式；不记启停）
        registerBgVolumeKeys(); // ★ 2026-11-16：后台按音量键也能改 A/B 上限
        EventLog.init(app);      // ★ 2026-11-14：事件落盘（临时诊断，问题解决后删）
    }

    // ────────────────────────────── 生命周期 ──────────────────────────────

    /** 启动发现。可以重复调用（前台服务与界面都会叫一次，第二次是空操作）。 */
    public void start() {
        synchronized (lock) {
            if (started) {
                return;
            }
            started = true;
            sawPeer = false;
            // ★ 2026-11-01 用户：手机端没有端口修改，电脑改端口后手机就连不上。
            //   本机端口现在从设置里读（发现端口默认 17830，音频端口固定自动选）。
            audioPort = configuredAudioPort();
            // ★ 接收器这次【不再】一开机就常驻。
            //
            // 上一个版本为了收对端那句"我在给你发声音、请收听我"（T_STATS 的 want_listen），
            // 把接收器一直开着、名单钉成一个不可能命中的地址 —— 用户当场就问过：
            // 「这一步不是瞎搞吗。我们设计了同一局域网设备能互相发现。那它本身就是
            //   多个设备都在互相同步数据啊」。
            //
            // 他说得对：那句话现在走**发现层**（见 Discovery 的类注释，onControl 回调），
            // 而那一层每 2 秒一发一收、本来就一直活着。接收器于是回到"按需启停"：
            // 真要收声音才起来（点「收听」/「听广播」/「送声音」那三条路各自会拉起它）。
            try {
                Discovery d = new Discovery(app, null, null, audioPort, 0);
                d.setDiscoveryPort(configuredDiscoveryPort());
                // 回调在发现线程上，这里只做一件事：把列表存下来，别在这里碰界面。
                d.setListener(new Discovery.Listener() {
                    @Override
                    public void onPeersChanged(List<Discovery.Peer> p) {
                        if (p != null) {
                            cache = p;
                        }
                    }

                    @Override
                    public void onControl(String host, String json) {
                        // ★ 控制消息现在从发现层来（见 Discovery 的类注释）：这一层一直活着，
                        //   所以接收器停着也收得到"请自动收听我"。正文和音频层那份对端统计
                        //   是同一份，所以这里直接走同一个入口 —— 两处绝不能有两套处理逻辑。
                        if (host == null || host.isEmpty() || json == null || json.isEmpty()) {
                            return;
                        }
                        try {
                            // ★ 2026-11-17 NACK：收到"请补发这几个包"的点名请求。
                            if (handleNackRequest(host, json)) {
                                return;
                            }
                            // ★ 2026-11-12：先看它是不是"远程改我这台的播放声道/音量"。
                            //   是就直接处理掉，不再往统计那条路上送（那份 JSON 里没有统计字段，
                            //   送过去也只会被当成垃圾包丢掉）。
                            if (handleRemoteOutCtl(host, json)) {
                                return;
                            }
                            onPeerStats(host, json.getBytes(StandardCharsets.UTF_8));
                        } catch (Throwable t) {
                            Log.w(TAG, "发现层的控制消息处理失败（已忽略这一条）", t);
                        }
                    }
                });
                d.start();
                discovery = d;
                // 端口真被占时 Discovery 会换一个，接收端必须跟着它走，否则信标里公布的端口没人听。
                audioPort = d.audioPort() > 0 ? d.audioPort() : Proto.DEFAULT_AUDIO_PORT;
            } catch (Throwable t) {
                // Wi-Fi 没开、组播锁拿不到等等：记下来但不让界面挂掉。
                Log.w(TAG, "启动自主发现失败", t);
            }
            // 对端统计的发送线程：和发现一起起来，它自己会在"没有任何关系"时静默空转。
            synchronized (peerLock) {
                peerStats.clear();
            }
            ensureStatsThread();
        }
    }

    /** 停止所有传输并关掉发现。 */
    public void stop() {
        synchronized (lock) {
            started = false;
            listenIds.clear();
            listenHosts.clear();
            listenPorts.clear();
            autoListenHosts.clear();
            sendIds.clear();
            channelListen = false;      // 「停止」= 一切都停，重启后不要留着一个用户看不见的开关
            broadcastOn = false;
            // ★ 第 2 档：服务销毁/退出 App 时一定要把媒体音量还回去（不能把用户手机留在哑的状态）。
            sendingActive = false;      // 先摘掉"真的在送"这个条件，否则 sync 会以为还在送、又压回去
            syncSilentVolume();
            stopReceiver();
            stopSender();
            // 采集停了就把投屏授权也放掉：一直攥着它，系统状态栏会一直挂着"正在投放"的提示。
            releaseProjection();
            stopStatsThread();
            synchronized (peerLock) {
                peerStats.clear();
            }
            Discovery d = discovery;
            discovery = null;
            if (d != null) {
                try {
                    d.stop();
                } catch (Throwable t) {
                    Log.w(TAG, "停止发现失败", t);
                }
            }
            cache = Collections.emptyList();
            statsCache = null;
        }
    }

    public boolean isStarted() {
        return started;
    }

    // ────────────────────────────── 设备（自主发现） ──────────────────────────────

    /** 当前局域网里看到的设备。返回的是拷贝，界面线程随便遍历，不受发现线程影响。 */
    public List<Discovery.Peer> peers() {
        Discovery d = discovery;
        if (d != null) {
            try {
                List<Discovery.Peer> l = d.peers();
                if (l != null) {
                    List<Discovery.Peer> fresh = dropStale(l);
                    cache = fresh;
                    if (!fresh.isEmpty()) {
                        sawPeer = true;
                    }
                    return new ArrayList<Discovery.Peer>(fresh);
                }
            } catch (Throwable t) {
                Log.w(TAG, "读取设备列表失败，用上一次的快照", t);
            }
        }
        return new ArrayList<Discovery.Peer>(dropStale(cache));
    }

    /**
     * ★ 断联超过 2 分钟的设备不再出现在列表里（口径见 {@link LinkPolicy#CARD_STALE_MS}）。
     *
     * <p>为什么这道过滤必须存在：设备列表的过期原本<b>只</b>靠 Discovery 里的 {@code sweep()}，
     * 而那一步在发现 socket 短暂不可用时会被整段跳过 —— 于是对端早就断联两分多钟了，
     * 卡片还挂在界面上（用户真机截图：一张 2025BJB 的卡片写着"还没收到它的心跳"，
     * 上面还有一行"已停止向 192.168.0.103 发送 —— 它已经 128 秒没有任何消息了"）。
     * 在这里按"它多久没消息了"再兜一道：超过 2 分钟的直接不返回，卡片就没了。
     */
    private static List<Discovery.Peer> dropStale(List<Discovery.Peer> in) {
        if (in == null || in.isEmpty()) {
            return new ArrayList<Discovery.Peer>();
        }
        List<Discovery.Peer> out = new ArrayList<Discovery.Peer>(in.size());
        for (Discovery.Peer p : in) {
            if (p == null || LinkPolicy.cardStale(p.ageMs())) {
                continue;
            }
            out.add(p);
        }
        return out;
    }

    /** 设备身份：优先用节点 UUID，没有就退回 IP（同一台机器不会因为换字段就变成两张卡片）。 */
    public static String idOf(Discovery.Peer p) {
        if (p == null) {
            return "";
        }
        String id = p.nodeId;
        if (id != null && id.length() > 0) {
            return id;
        }
        return p.host == null ? "" : p.host;
    }

    // ────────────────────────────── 收听 / 送声音 ──────────────────────────────

    /**
     * ★ ④ 在某台上选了「走蓝牙 / 走 WiFi」。
     *
     * <p>用户原话（2026-10-15）：「手机确认走蓝牙后应当由电脑发送蓝牙请求并配对互相连接。」
     * 手机这边只做两件事：① 记住这个选择 ② 每 2 秒跟着统计包把 {@code want_bt} 报给电脑
     * （幂等）。真正打开蓝牙 sink 的是电脑 —— 蓝牙设备列表在它手里。
     *
     * <p>选「走 WiFi」时传 false：请求自然撤回，不用额外的取消消息。
     */
    public void setBtWant(Discovery.Peer p, boolean on) {
        if (p == null || p.host == null || p.host.isEmpty()) {
            return;
        }
        if (on) {
            // 必须连端口一起记：它要进统计包的发送名单，而统计包是按 (地址, 端口) 发的。
            btWantPorts.put(p.host, p.audioPort > 0 ? p.audioPort : Proto.DEFAULT_AUDIO_PORT);
        } else {
            btWantPorts.remove(p.host);
        }
    }

    /** 这一台是不是被用户选了「走蓝牙」（发包线程读）。 */
    public boolean btWantTo(String host) {
        return host != null && !host.isEmpty() && btWantPorts.containsKey(host);
    }

    /** 点「收听」：收这台发来的声音。可以同时收听多台，声音会混在一起。 */
    public void setListening(Discovery.Peer p, boolean on) {
        if (p == null) {
            return;
        }
        String id = idOf(p);
        synchronized (lock) {
            if (on) {
                listenIds.add(id);
                // ★ 用户自己点开的：从"我们自动开的"里去掉 —— 以后对端说"我不送了"也不能收它。
                autoListenHosts.remove(p.host);
                if (p.host != null && !p.host.isEmpty()) {
                    // 记下地址：设备列表以后怎么抖，这条收听都不受影响。
                    listenHosts.put(id, p.host);
                    // 端口一起记住：对端统计要单播到它的 audio_port（信标里公布的那个）。
                    listenPorts.put(id, p.audioPort > 0 ? p.audioPort : Proto.DEFAULT_AUDIO_PORT);
                }
            } else {
                listenIds.remove(id);
                listenHosts.remove(id);
                listenPorts.remove(id);
                autoListenHosts.remove(p.host);     // 已经不在收听名单里了，不欠这一笔
                // ★ 用户手动关掉这条收听【不再】写进任何黑名单：要不要自动只由这台设备的
                //   「智连」开关说了算。旧代码在这里把设备永久记进 listenRevoked，正是用户第 1 条
                //   「电脑主动发送声音手机不会主动接收」卡死的根因（只要电脑一直在请求，那份记忆
                //   就永远不过期），已彻底拆掉。
            }
            if (listenIds.isEmpty()) {
                // 用户明确取消了全部收听：这时候才停接收器。
                // （注意顺序：上面已经把 id 移除，所以这里判断的是"用户真的都取消了"。）
                // ★ 但"听广播"还开着、或者还在送声音时不能停：前者靠它收频道音频，
                //   后者靠它收对端的统计回话。
                stopReceiverIfIdle();
            } else {
                ensureReceiver();
                applyAllowedHosts();
            }
        }
    }

    /** ★ 「听广播」开关。打开之后接收端不再按名单过滤（谁来都听），并且保持接收器活着。 */
    public boolean isChannelListening() {
        return channelListen;
    }

    public void setChannelListen(boolean on) {
        synchronized (lock) {
            if (channelListen == on) {
                return;
            }
            channelListen = on;
            if (on) {
                ensureReceiver();       // 没有它一个包都收不到
                applyAllowedHosts();    // 名单留空 = 谁的都听
            } else {
                applyAllowedHosts();
                stopReceiverIfIdle();
            }
        }
    }

    /**
     * 没有任何理由再收包时才停接收器。
     *
     * <p>三个理由：还开着「听广播」、还在送声音、还有收听目标。
     *
     * <p>★ 2026-10-15：这个方法以前是<b>反着写</b>的 —— 条件满足时不是停，而是把接收器
     * 拉起来并常驻（名单钉成一个不可能命中的地址，谁都播不了），只为了收对端那句
     * "请自动打开收听"。那是绕路，而且那条常驻的 Wi-Fi 锁会一直握着。用户当场就质疑过：
     * 「这一步不是瞎搞吗。我们设计了同一局域网设备能互相发现。那它本身就是多个设备都在
     *   互相同步数据啊」。
     *
     * <p>现在控制消息（want_listen / want_bt / 共享音量）都走发现层那个常驻 socket
     * （见 {@link Discovery} 的类注释与 {@code onControl} 回调），接收器不必再为了收请求
     * 而活着，所以这里回到本来的语义：真的没人要听、也没在送，就停掉。
     *
     * <p>「还在送声音」这一条留着：送的时候对端会把"我收了你多少"回过来，老版本对端
     * 是发到音频端口的，收不到那一句界面就只能显示"对方：等它回话"。
     */
    private void stopReceiverIfIdle() {
        if (channelListen || sender != null || !listenIds.isEmpty()) {
            return;
        }
        stopReceiver();
    }

    /**
     * 点「送声音」：把我的声音送给这台（<b>可以同时送给好几台</b>，不再互斥）。
     *
     * <p>为什么改成可累加（2026-10-14 用户反馈）：用户要「📢 广播我的声音」和
     * "每台设备独立的收/送电平条"，这两条的前提都是"一次采集、同时送给多台"。
     * 采集仍然只有一份（见 {@link #syncSenderTargets()}），多送一台只是多一个收包人。
     */
    public void setSending(Discovery.Peer p, boolean on) {
        if (p == null) {
            return;
        }
        String id = idOf(p);
        synchronized (lock) {
            if (on) {
                // ★ 2026-11-12：一开 App 音源是"没勾选"的（用户要求），所以第一次点
                //   「送声音」时这里得自己选一个 —— 选麦克风：它不需要额外授权，
                //   不会先弹投屏确认框；用户想要本机声音再自己点那颗按钮。
                if (!sourceEnabled) {
                    sourceKind = AudioSend.SRC_MIC;
                    sourceEnabled = true;
                }
                sendIds.add(id);
            } else {
                sendIds.remove(id);
            }
            syncSenderTargets();
            // ★ 第 2 档：开始/停止送声都要让静音状态跟上（停了最后一台就立刻还原音量）。
            syncSilentVolume();
            if (sendIds.isEmpty() && !broadcastOn) {
                // 送声停了、又没有别的收听目标：接收器只是刚才为了收对端统计才开着的，
                // 一起收掉（否则它会一直占着 UDP 端口和一个 WifiLock）。
                // ★ 但「听广播」开着就不能收 —— 那正是它存在的理由。
                // ★ 2026-11-01：用户主动停掉最后一台「送声音」＝不再需要投屏，
                //   立刻释放授权，系统那条「屏幕共享中」随之消失。
                releaseProjectionIfIdle();
                stopReceiverIfIdle();
            }
        }
    }

    // ────────────────── ★ 「📢 广播我的声音」（一次采集 → 送给每一台在线设备） ──────────────────

    /**
     * 「📢 广播我的声音」开关。
     *
     * <p>做法照电脑端：**对发现列表里每个在线节点各建一条单播发送**，
     * 而不是往广播地址盲发（Wi-Fi 上盲发不可靠，电脑端也没那么做）。
     * 对用户来说就是"我的声音，谁在线谁就能听见"。
     */
    public void setBroadcasting(boolean on) {
        synchronized (lock) {
            broadcastOn = on;
            syncSenderTargets();
            // ★ 第 2 档：广播也是"在送声"，第 2 档同样适用；关掉广播且没有逐台送声就还原音量。
            syncSilentVolume();
            if (!on && sendIds.isEmpty()) {
                // 广播关了、也没有逐台送声 → 不再需要采本机声音，释放投屏授权。
                releaseProjectionIfIdle();
                stopReceiverIfIdle();
            }
        }
    }

    public boolean isBroadcasting() {
        return broadcastOn;
    }

    // ────────────────── ★ 2026-11-04（Q3 + 第 2 档）：送声音三档 ──────────────────

    /**
     * 当前对外的送声音档位：<b>0 = 没有任何声音在往外送</b>（既没点对点也没广播），
     * 1 = 送声+外放，2 = 送声+手机静音（音频**照常**继续发给电脑，只把本机媒体音量降到 0）。
     */
    public int sendMode() {
        synchronized (lock) {
            if (sendIds.isEmpty() && !broadcastOn) {
                return 0;
            }
            return sendModePref == 2 ? 2 : 1;
        }
    }

    /** 用户选的档位偏好（0 / 1 / 2）。没在送声时也保留，下一次送声按这个档位开始。 */
    public int sendModePref() {
        return sendModePref;
    }

    /**
     * 切换档位。即时生效、不动采集（音频流一秒都不会断）：
     * <ul>
     *   <li>0 —— 停掉所有点对点「送声音」，并<b>恢复系统媒体音量</b>（广播是独立线路，不归这里管）；</li>
     *   <li>1 —— 继续送，本机外放照常，并<b>恢复系统媒体音量</b>；</li>
     *   <li>2 —— 继续送，把 <b>STREAM_MUSIC 临时降到 0</b>（只这一条流；铃声/通知/通话都不碰），
     *       手机扬声器不响、但音频数据照常完整发送，电脑那头照常能听。</li>
     * </ul>
     *
     * <p><b>已知副作用</b>：因为是压低系统媒体音量，手机上其它 App 的媒体声音也会一起变哑 ——
     * 这是用户明确接受的代价；本应用做不到"只压这一路"（采集的是别的 App，没有独立播放通道）。
     *
     * <p>切换档位只改目标集合与音量，<b>不重建发送会话</b>，所以不会出现"断一下再连"。
     */
    public void setSendMode(int mode) {
        int m = mode <= 0 ? 0 : (mode >= 2 ? 2 : 1);
        synchronized (lock) {
            boolean changed = sendModePref != m;
            sendModePref = m;
            if (m == 0) {
                sendIds.clear();
                syncSenderTargets();
                if (!broadcastOn) {
                    releaseProjectionIfIdle();
                    stopReceiverIfIdle();
                }
            } else if (changed) {
                syncSenderTargets();
            }
            // ★ 第 2 档：真正的开关只在这一处；0/1 档一律恢复（不会把用户的音量留在 0）。
            syncSilentVolume();
            // ★ 2026-11-08：音量乘数跟着档位即时变化（外放档乘系统音量；静音档默认不乘）。
            AudioSend s = sender;
            if (s != null) {
                s.setSendMode(m >= 2 ? 2 : 1);
            }
            if (changed) {
                saveSendMode();
            }
        }
    }

    /** 第 2 档对用户的一句话（UI 详情/长按用）。 */
    public static final String SEND_MODE_SILENT_NOTE =
            "第 2 档：手机会静音（系统媒体音量临时降到 0），声音仍在完整传给电脑。"
            + "退出 App、停止送声、切到第 0/1 档都会自动还原音量。"
            + "注意：手机其它 App 的媒体声音也会一起变哑 —— 本应用采集的就是系统媒体声，做不到只压一路。";

    private static final String PREF_SEND_MODE = "send_mode";
    /** ★ 2026-11-12：音量键调「对方音量」的当前值（0~100）。 */
    private static final String PREF_REMOTE_VOL = "remote_vol";
    // ★ 第 2 档崩溃兜底：进入静默前先落盘"原音量 + 进入时间"，下次启动若发现残留就立即还原。
    private static final String PREF_SILENT_ACTIVE = "silent_vol_active";
    private static final String PREF_SILENT_ORIG = "silent_vol_orig";
    private static final String PREF_SILENT_AT = "silent_vol_at";

    private void loadSendMode() {
        if (app == null) {
            return;
        }
        try {
            android.content.SharedPreferences sp =
                    app.getSharedPreferences("audiomixing", Context.MODE_PRIVATE);
            int v = sp.getInt(PREF_SEND_MODE, 1);
            sendModePref = v <= 0 ? 0 : (v >= 2 ? 2 : 1);
            remoteVol = Math.max(0, Math.min(100, sp.getInt(PREF_REMOTE_VOL, 100)));
        } catch (Throwable t) {
            sendModePref = 1;
            Log.w(TAG, "读送声音档位失败（按第 1 档处理）：" + t);
        }
    }

    private void saveSendMode() {
        if (app == null) {
            return;
        }
        try {
            app.getSharedPreferences("audiomixing", Context.MODE_PRIVATE).edit()
                    .putInt(PREF_SEND_MODE, sendModePref).apply();
        } catch (Throwable t) {
            Log.w(TAG, "存送声音档位失败（下次仍按默认第 1 档）：" + t);
        }
    }

    // ── ★ 2026-11-08：静音档乘数策略（internal_only / last_nonzero） ──

    private static final String PREF_SILENT_VOL_POLICY = "silent_vol_policy";

    private void loadSilentVolPolicy() {
        if (app == null) {
            return;
        }
        try {
            String v = app.getSharedPreferences("audiomixing", Context.MODE_PRIVATE)
                    .getString(PREF_SILENT_VOL_POLICY, SendVolume.POLICY_INTERNAL);
            silentVolPolicy = SendVolume.normalizePolicy(v);
        } catch (Throwable t) {
            silentVolPolicy = SendVolume.POLICY_INTERNAL;
            Log.w(TAG, "读静音档乘数策略失败（按 internal_only 处理）：" + t);
        }
    }

    /** 静音档乘数策略（internal_only / last_nonzero）。 */
    public String silentVolPolicy() {
        return silentVolPolicy;
    }

    /**
     * 切换静音档乘数策略。手机界面点击和电脑下发都走这里 —— 状态只有一份。
     *
     * @param persist true = 写进本地（两个入口都写，重启后保持一致）
     */
    public void setSilentVolPolicy(String policy, boolean persist) {
        String p = SendVolume.normalizePolicy(policy);
        silentVolPolicy = p;
        AudioSend s = sender;
        if (s != null) {
            s.setSilentVolPolicy(p);
        }
        if (persist && app != null) {
            try {
                app.getSharedPreferences("audiomixing", Context.MODE_PRIVATE).edit()
                        .putString(PREF_SILENT_VOL_POLICY, p).apply();
            } catch (Throwable t) {
                Log.w(TAG, "存静音档乘数策略失败（本次仍生效）：" + t);
            }
        }
        Log.i(TAG, "静音档乘数策略 = " + p + "（" + SendVolume.policyText(p) + "）");
    }

    /** 手机界面/诊断报告里的一行中文（当前档位 + 策略 + 实际乘数）。 */
    public String volumeModeText() {
        int volQ14 = SendVolume.q14(mediaVolumeRatio());
        int mediaQ14 = SendVolume.mediaQ14(sendModePref, silentVolPolicy, volQ14, volQ14);
        return SendVolume.text(sendModePref, silentVolPolicy, mediaQ14);
    }

    /** 当前实际生效的档位名（external/silent）—— 统计包用。 */
    public String volumeSource() {
        return SendVolume.modeName(sendModePref);
    }

    /**
     * ★ 第 2 档的唯一实现点：**整个工程里只有这里允许调用 setStreamVolume**。
     *
     * <p>为什么集中成一个函数：这条路径要能被审计（"到底哪里动了用户音量"），
     * 也保证 `setStreamMute` 一处都不出现 —— 静音用"音量归零"而不是 mute 标志，
     * 这样用户自己调音量条也能立刻听出来、不会被我们偷偷锁住。
     *
     * <p>进入前先把原音量与时间写进 SharedPreferences（用 commit 同步落盘），
     * 进程被杀/崩溃也不会把用户手机留在哑的状态；{@link #restoreSilentVolumeIfNeeded()}
     * 在下次启动时兜底还原。
     */
    private void applySilentVolume(boolean on) {
        if (app == null) {
            return;
        }
        try {
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                return;
            }
            if (on) {
                android.content.SharedPreferences sp =
                        app.getSharedPreferences("audiomixing", Context.MODE_PRIVATE);
                if (!sp.getBoolean(PREF_SILENT_ACTIVE, false)) {
                    int orig = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                    sp.edit()
                            .putBoolean(PREF_SILENT_ACTIVE, true)
                            .putInt(PREF_SILENT_ORIG, orig)
                            .putLong(PREF_SILENT_AT, System.currentTimeMillis())
                            .commit();          // 先落盘再静音：崩在下一行也能还原
                } else {
                    // ★ 2026-11-06（Q1）：现场「静音档下把 App 音量 100→10，耳机几乎不变」。
                    //   根因就在这里：旧实现只在进入静音那一刻记一次 orig，之后永远拿
                    //   这个旧值当乘数（实测 0.11 一直不动）。而我们的静音手段是把
                    //   STREAM_MUSIC 钉在 0，用户/App 每次调整都会先落到这个值上 ——
                    //   现在把它当成"用户最新想用的音量"记下来，再归零。
                    //   · 绝对值（>1）→ 直接采信（App 自己的音量滑杆多半走 setStreamVolume）；
                    //   · 系统已被钉在 0 时，硬件键一次只能抬到 1 档 → 这一档按"再加一档"
                    //     解释，用户才能连续调节（否则按一次就到顶、怎么按都停在 0.11）。
                    int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                    if (cur > 0) {
                        int user = sp.getInt(PREF_SILENT_ORIG, cur);
                        int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                        int next = cur;
                        if (cur == 1 && user >= 1) {
                            next = Math.min(max, user + 1);
                        }
                        next = Math.max(0, Math.min(max, next));
                        if (next != user) {
                            sp.edit().putInt(PREF_SILENT_ORIG, next).apply();
                        }
                    }
                }
                // 已经是 0 就不再重复写（本函数每 150ms 会被状态同步调到，不能去骚扰系统）。
                if (am.getStreamVolume(AudioManager.STREAM_MUSIC) != 0) {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
                }
            } else {
                restoreSilentVolumeIfNeeded();
            }
        } catch (Throwable t) {
            Log.w(TAG, "第 2 档音量切换失败（不改用户音量，如实记录）：" + t);
        }
    }

    /** 有残留标记（上次进程被杀/崩溃）就立刻还原，并把标记清掉。可重复调用。 */
    private void restoreSilentVolumeIfNeeded() {
        if (app == null) {
            return;
        }
        try {
            android.content.SharedPreferences sp =
                    app.getSharedPreferences("audiomixing", Context.MODE_PRIVATE);
            if (!sp.getBoolean(PREF_SILENT_ACTIVE, false)) {
                return;
            }
            int orig = sp.getInt(PREF_SILENT_ORIG, -1);
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            if (am != null && orig >= 0) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, orig, 0);
            }
            sp.edit()
                    .remove(PREF_SILENT_ACTIVE)
                    .remove(PREF_SILENT_ORIG)
                    .remove(PREF_SILENT_AT)
                    .commit();
            Log.i(TAG, "第 2 档残留标记已还原媒体音量：" + orig);
        } catch (Throwable t) {
            Log.w(TAG, "还原第 2 档音量失败（标记保留，下次启动再试）：" + t);
        }
    }

    /**
     * 让"系统媒体音量"跟当前档位/发送状态对齐（幂等，可每次状态变化都调）。
     * 只在 `syncSilentVolume` 里判条件，真正的 setStreamVolume 仍集中在 {@link #applySilentVolume}。
     */
    private void syncSilentVolume() {
        // 只有"真的在送"才压音量：目标都掉线/蓝牙断了/音源没授权时，手机不该继续哑着。
        boolean silent = sendModePref == 2 && sendingActive && sourceEnabled;
        applySilentVolume(silent);
        // ★ 2026-11-16：静音档会把系统媒体音量钉在 0，音量键在系统那边等于没反应。
        //   这时候（以及环绕运行期间）让媒体会话接管音量键，按键才不会丢。
        if (silent || surroundOn) {
            ensureMediaSession();
            setSessionRemoteVolume(true);
        } else {
            setSessionRemoteVolume(false);
        }
    }

    /**
     * 把"现在应该送给谁"同步给发送端。
     *
     * <p><b>关键</b>：目标集合变化<b>不会重启采集</b> —— 采集器只在"从没送过/停了"时起一次，
     * 之后都是 {@code setTargets()}。所以对方上线/下线时正在送的声音不会断一下。
     */
    private void syncSenderTargets() {
        // ★ 2026-11-06（Q2）：把当前送声身份同步进信标。这个函数每 150ms 被 tick
        //   调一次，值没变时 Discovery 内部直接跳过，不会增加网络负担。
        if (discovery != null) {
            discovery.setSendState(sendMode(), broadcastOn);
        }
        if (!sourceEnabled) {
            // ★ 音源关了 = 不采也不发（"送给谁"的意图已在 applySourceState 里清掉）。
            sendingActive = false;
            syncSilentVolume();      // ★ 第 2 档：音源不可用就还原音量，不能把手机留在哑的状态
            stopSender();
            return;
        }
        List<String> eps = collectSendTargets();
        // 这个函数每 150ms 会被界面 tick 调一次：目标没变就什么都不做，
        // 免得一直重建目标表（目标表的比较用字符串签名，几分之一微秒的事）。
        String sig = eps.toString();
        boolean changed = !sig.equals(lastTargetSig);
        lastTargetSig = sig;
        AudioSend s = sender;
        // ★ 2026-11-04（B 第一阶段）：扩展段只在**所有当前目标**都声明 ext:1 时启用。
        //   只要目标里还有一台老电脑，就整条链路发老包 —— 老电脑不能因为多出的 8 字节
        //   听到"开头 8 个字节被吃掉"的杂音。这是向后兼容的硬要求。
        boolean extOk = targetsSupportExt(eps);
        if (s != null) {
            s.setSelfOriginId(selfOriginId());
            s.setExtEnabled(extOk);
        }
        if (eps.isEmpty()) {
            // ★ 第 2 档：没有收包人了（关机/换网/蓝牙断）→ 还原音量，别让手机白哑着。
            sendingActive = false;
            syncSilentVolume();
            if (s != null) {
                stopSender();
            }
            // 注意：这里不要顺手 releaseProjection —— onProjectionGranted 会先 sync 再把
            // 刚授权的目标加进 sendIds；在这里释放会把刚拿到的授权当场作废。
            // 「用户主动停送 / 停广播」两个入口会各自调 releaseProjectionIfIdle()。
            return;
        }
        if (s == null || !s.isRunning()) {
            try {
                // 用不带目标的构造：采集只有一份，目标随后由 setTargets 给。
                AudioSend fresh = new AudioSend(app);
                fresh.setGain(volume);
                fresh.setSelfOriginId(selfOriginId());
                fresh.setExtEnabled(extOk);
                // ★ 2026-11-08：音量乘数跟着送声档位走（外放档乘系统音量、静音档默认不乘）。
                fresh.setSendMode(sendModePref);
                fresh.setSilentVolPolicy(silentVolPolicy);
                // ★ 2026-11-17：新建的发送器继承 NACK 开关（开/关都和 Engine 一致）。
                fresh.setNackEnabled(nackEnabled);
                // ★ 把"送什么声音"接上：音源 + 本机声音的授权。
                //   拿不到授权、或低于 Android 10 时，AudioSend 自己会退回麦克风并记下原因，
                //   界面拿 activeSourceKind() 如实显示"现在实际送的是哪一个"。
                fresh.setProjection(projection);
                fresh.setSourceKind(sourceKind);
                fresh.setTargets(eps);
                restoreGains(fresh);     // ★ 把（用户或对端同步来的）逐目标音量补回去
                fresh.start();
                sender = fresh;
                lastSendError = "";
                // ★ 送声音的同时把接收器也开起来：对方会把"我收到了多少"用统计包发回来，
                //   没有接收器就收不到这句回话（白名单里没有收听目标，所以不会出声）。
                ensureReceiver();
            } catch (Throwable t) {
                // 拿不到采集（被别的 App 占用 / 没权限）时不要把界面搞崩。
                Log.w(TAG, "启动发送失败 -> " + eps, t);
                lastSendError = String.valueOf(t);
                sendIds.clear();
                broadcastOn = false;
            }
        } else if (changed) {
            s.setTargets(eps);      // ★ 不重启采集，只换目标
            Log.i(TAG, "发送目标已更新：" + eps);
        }
        // ★ 第 2 档：以"发送器真的在跑"为准，而不是只看勾选意图 —— 采集失败时会还原音量。
        AudioSend nowSending = sender;
        sendingActive = nowSending != null && nowSending.isRunning();
        syncSilentVolume();
    }

    /**
     * 算出现在该送给哪些地址："广播"给全部在线设备 + 用户单独勾的那几台。
     *
     * <p><b>并且判"对端是不是确认走了"</b>（2026-10-14 用户反馈的安全问题：
     * 「电脑关闭应用，手机声音还在向电脑发送」）：勾选/广播是<b>意图</b>，
     * 这里再叠一层<b>活性</b> —— 确认走了的就不发，但意图保留，对方回来自动恢复。
     */
    /** ★ 2026-11-04：本机 originId = device_id 的稳定哈希（和电脑端 CRC32 口径一致）。 */
    private int selfOriginId() {
        Discovery d = discovery;
        return Proto.originIdOf(d == null ? "" : d.deviceId());
    }

    /**
     * ★ 2026-11-04（B 第一阶段）：这些发送目标是否**全部**声明 ext:1。
     *
     * <p>只认"发现列表里同一 host 的那台设备"；有一台找不到或 ext=false 就返回 false，
     * 整条链路退回老包。宁可少一层防环，也不能把老端的音频解码搞坏。
     */
    private boolean targetsSupportExt(List<String> eps) {
        if (eps == null || eps.isEmpty()) {
            return false;
        }
        Discovery d = discovery;
        List<Discovery.Peer> peers = d == null ? null : d.peers();
        if (peers == null || peers.isEmpty()) {
            return false;
        }
        for (String ep : eps) {
            if (ep == null) {
                return false;
            }
            int colon = ep.lastIndexOf(':');
            String host = colon > 0 ? ep.substring(0, colon).trim() : ep.trim();
            boolean found = false;
            for (Discovery.Peer p : peers) {
                if (p != null && p.host != null && p.host.equalsIgnoreCase(host)) {
                    found = true;
                    if (!p.ext) {
                        return false;
                    }
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private List<String> collectSendTargets() {
        List<String> out = new ArrayList<String>();
        long nowMs = SystemClock.elapsedRealtime();
        StringBuilder gone = new StringBuilder();

        // ① 发现层看得见的设备：能看见 = 它活着（信标就是活性证据）
        for (Discovery.Peer p : peers()) {
            if (p == null || p.host == null || p.host.isEmpty()) {
                continue;
            }
            String id = idOf(p);
            if (!broadcastOn && !sendIds.contains(id)) {
                continue;
            }
            sendHosts.put(id, p.host);
            sendLastAliveMs.put(id, nowMs);
            int port = p.audioPort > 0 ? p.audioPort : Proto.DEFAULT_AUDIO_PORT;
            String ep = p.host + ":" + port;
            if (!out.contains(ep)) {
                out.add(ep);
            }
        }
        // ② 用户单独勾过、但发现列表里暂时没有的：看心跳（T_STATS）还在不在，
        //    以及"上次确认它还活着"距今多久。超过宽限期就不发（意图保留）。
        //    ★ 但断联超过 2 分钟（LinkPolicy.CARD_STALE_MS）就直接删掉：这个对端不会回来了，
        //      连"送声音"的意图一起清（用户原话「这种已经信号断联两分钟的就直接删掉就好了，
        //      不然全堆积在程序里」）—— 那行"已停止向 XXX 发送"的陈旧提示也随之消失。
        List<String> staleIds = new ArrayList<String>();
        for (String id : new ArrayList<String>(sendIds)) {
            String host = sendHosts.get(id);
            Long last = sendLastAliveMs.get(id);
            if (host != null && last != null && LinkPolicy.cardStale(nowMs - last)) {
                staleIds.add(id);
                continue;
            }
            if (out.contains(endpointOf(id))) {
                continue;
            }
            if (broadcastOn && out.size() > 0) {
                continue;   // 广播模式下别的设备已经在发了，这台单独走下面的活性判断
            }
            if (host == null || last == null) {
                continue;   // 还没见过它长什么样：交给发现层去发现，不算"走了"
            }
            long silent = nowMs - last;
            if (silent > LinkPolicy.GRACE_MS) {
                gone.append(LinkPolicy.goneText(nameOfHost(host), silent)).append('\n');
            } else {
                Discovery.Peer p = findById(id);
                int port = (p != null && p.audioPort > 0) ? p.audioPort : Proto.DEFAULT_AUDIO_PORT;
                String ep = host + ":" + port;
                if (!out.contains(ep)) {
                    out.add(ep);
                }
            }
        }
        for (String id : staleIds) {
            // 意图、地址、活性记录一起清 —— 不留任何"堆在程序里"的陈旧条目。
            sendIds.remove(id);
            sendHosts.remove(id);
            sendLastAliveMs.remove(id);
            Log.i(TAG, "对端断联超过 2 分钟，已清掉送声目标：" + id);
        }
        if (broadcastOn && out.isEmpty() && !sendIds.isEmpty()) {
            gone.append("广播开着，但现在一台设备都联系不上（都离线了）。\n");
        }
        String gt = gone.toString().trim();
        if (!gt.equals(sendSuspendedText)) {
            sendSuspendedText = gt;
            if (gt.length() > 0) {
                Log.i(TAG, "发送目标被暂停：" + gt.replace('\n', ' '));
            }
        }
        return out;
    }

    /** 取 map 里的增益；没有就返回 GAIN_UNSET（= "我没报过"，对端会保持它自己的值）。 */
    private static float gainOf(Map<String, Float> m, String host) {
        Float v = m.get(host);
        return v == null ? Diagnosis.GAIN_UNSET : v;
    }

    private static long tsOf(Map<String, Long> m, String host) {
        Long v = m.get(host);
        return v == null ? 0L : v;
    }

    /** 某一台的目标地址（"host:port"）；不知道就返回空串。 */
    private String endpointOf(String id) {        String host = sendHosts.get(id);
        if (host == null || host.isEmpty()) {
            return "";
        }
        Discovery.Peer p = findById(id);
        int port = (p != null && p.audioPort > 0) ? p.audioPort : Proto.DEFAULT_AUDIO_PORT;
        return host + ":" + port;
    }

    /** ★ 因为"确认走了"而暂停发送的说明（没有就返回空串）。界面/通知/报告都用它。 */
    public String sendSuspendedText() {
        return sendSuspendedText == null ? "" : sendSuspendedText;
    }

    /** 某台设备现在是不是"用户想送、但确认走了所以暂停了"（界面显示用）。 */
    public boolean isSuspendedTarget(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        AudioSend s = sender;
        if (s != null) {
            for (SendFanout.TargetStats ts : s.targetStats()) {
                if (host.equals(ts.host)) {
                    return false;      // 还在目标表里 = 还在发
                }
            }
        }
        for (Map.Entry<String, String> e : sendHosts.entrySet()) {
            if (host.equals(e.getValue()) && (sendIds.contains(e.getKey()) || broadcastOn)) {
                return true;
            }
        }
        return false;
    }

    /** 「📢 广播我的声音」界面状态用的人话。 */
    public String broadcastStateText() {
        int n = sendTargetCount();
        StringBuilder sb = new StringBuilder(200);
        if (!broadcastOn) {
            sb.append("没开：只有你单独点了「送声音」的设备会收到。");
            if (n > 0) {
                sb.append("现在送 ").append(n).append(" 台。");
            }
            return sb.toString();
        }
        sb.append("正在广播：我的声音同时送给 ").append(n).append(" 台在线设备");
        if (n == 0) {
            sb.append("（现在一台都没看见 —— 对面得先能被发现）");
        }
        sb.append("。");
        sb.append(bandwidthText());
        return sb.toString();
    }

    /**
     * ★ 带宽的实话。
     *
     * <p>PCM16 立体声 48kHz = 48000×2×2 = 192000 字节/秒 ≈ <b>0.19 MB/s（1.5 Mbit/s）每个目标</b>。
     * （注意别把 1536 kbit/s 念成 1536 KB/s —— 差 8 倍。这里写的是字节数。）
     * 真正先吃紧的不是带宽，而是每个 1948 字节的包会被 IP 层拆成 2 个分片、以及每多一个目标
     * 就多一次系统调用 —— 所以目标多时提示"可能卡"，而不是编一个更吓人的数字。
     */
    public String bandwidthText() {
        int n = Math.max(1, sendTargetCount());
        double mbPerSec = n * (Proto.SAMPLE_RATE * Proto.CHANNELS * 2) / 1048576.0;
        String s = String.format(java.util.Locale.US,
                "每台约 0.19 MB/s（1.5 Mbit/s）；%d 台合计约 %.2f MB/s。", n, mbPerSec);
        if (n >= 4) {
            s += "⚠ 目标较多：包会被拆成 IP 分片、空口占用也成倍增加，可能卡顿。";
        }
        return s;
    }

    /** 当前实际送给了几台。 */
    public int sendTargetCount() {
        AudioSend s = sender;
        return (s == null) ? 0 : s.targetCount();
    }

    /** 逐目标发送统计（界面"送"电平条 + 诊断报告）。发送器不在时返回空表。 */
    public List<SendFanout.TargetStats> sendTargetStats() {
        AudioSend s = sender;
        if (s == null) {
            return new ArrayList<SendFanout.TargetStats>();
        }
        try {
            return s.targetStats();
        } catch (Throwable t) {
            return new ArrayList<SendFanout.TargetStats>();
        }
    }

    /** 送给某一台的电平 0~1（界面"送"电平条用）。 */
    public float sendPeakFor(String host) {
        AudioSend s = sender;
        if (s == null || host == null || host.isEmpty()) {
            return 0f;
        }
        try {
            return clamp01(s.targetPeak(host));
        } catch (Throwable t) {
            return 0f;
        }
    }

    /** 在线设备数（含本机）。 */
    public int onlineCountIncludingSelf() {
        return peers().size() + 1;
    }

    // ────────────────── ★ 每台设备自己的可调音量（"我听它的 / 我送它的"） ──────────────────
    //
    // 用户原话：「我要求的是各设备【可调节】的音量条。当然现在有的显示实际音量条也不错」
    // —— 所以电平条（实时显示）保留，旁边再加一条可调滑杆。
    // 两条滑杆控制的都是【我们这一层】的增益（绝不碰手机系统音量，这条口径和回归测试都在）：
    //   · 我听它的 → AudioRecv 里只乘这一路的样本
    //   · 我送它的 → SendFanout 里只缩放发给这一台的那份包
    // 主音量滑杆（"我们这一层的音量"）是总增益，叠在这些单台增益之上，互不冲突。

    /** 设定"我听它的"增益（0~1.5，1.0 = 原样）。立刻生效，并且会同步给对端（共享增益）。 */
    public void setPeerRecvGain(String host, float g) {
        float v = LinkPolicy.clampGain(g);
        applyRecvGain(host, v);
        linkRecvGainTs.put(host, System.currentTimeMillis());     // 本地改的：时间戳最新
        linkRecvGainChangedAtMs.put(host, SystemClock.elapsedRealtime());
        pokeGainStats();      // 别让对端等满 2 秒才看到新值（拖动时按 ~120ms 节流）
    }

    /** 某一台现在的"我听它的"增益。 */
    public float peerRecvGain(String host) {
        Float v = linkRecvGain.get(host);
        if (v != null) {
            return v;
        }
        AudioRecv r = receiver;
        if (r == null || host == null) {
            return 1f;
        }
        try {
            return r.hostGain(host);
        } catch (Throwable t) {
            return 1f;
        }
    }

    /** 设定"我送它的"增益（0~1.5，1.0 = 原样）。立刻生效，并且会同步给对端（共享增益）。 */
    public void setPeerSendGain(String host, float g) {
        float v = LinkPolicy.clampGain(g);
        applySendGain(host, v);
        linkSendGainTs.put(host, System.currentTimeMillis());
        linkSendGainChangedAtMs.put(host, SystemClock.elapsedRealtime());
        pokeGainStats();      // 同上：对端不等那 2 秒（拖动时按 ~120ms 节流）
    }

    /** 上一次因为"拖音量"叫醒统计线程的时刻（elapsedRealtime）。 */
    private volatile long lastGainPokeMs;

    /**
     * ★ 2026-11-01 真机自测（USB 调试）：拖动滑杆时每一帧都会调 setPeerRecvGain/
     * setPeerSendGain，原来每一帧都 {@link #pokeStats()}，一次拖动就是几十个统计包。
     * 电脑端按到达顺序落增益，UDP 乱序时最后可能停在**中间值**（实测 0.51 被 1.45 顶掉）。
     * 这里按 120ms 节流：拖动过程仍有连续的视觉反馈，松手时界面会再补一次
     * {@link #pokeStats()}（见 MainActivity.onStopTrackingTouch），保证**最终值**最后一个发出。
     */
    private void pokeGainStats() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastGainPokeMs < 120L) {
            return;
        }
        lastGainPokeMs = now;
        pokeStats();
    }

    /** 某一台现在的"我送它的"增益。 */
    public float peerSendGain(String host) {
        Float v = linkSendGain.get(host);
        if (v != null) {
            return v;
        }
        return 1f;
    }

    public boolean isListening(Discovery.Peer p) {
        synchronized (lock) {
            return p != null && listenIds.contains(idOf(p));
        }
    }

    /**
     * 这台设备现在会不会收到我的声音（**用户单独勾了它**才算）。
     *
     * <p>★ 2026-10-16 用户澄清（原话）：「手机点击开广播，手机上就会直接打开所有路由上的"送声音"…
     * 广播是设备只有开关本机"听""送"的权利的」——
     * 广播是**一条独立的线路**，不是"逐台针对性发送"。所以这一台卡片上的「送声音」
     * 永远只反映用户自己勾过的选择；广播开着时不再把每张卡都点亮
     * （"我正在广播"只由上面那个 📢 开关显示）。
     * 广播期间逐台那个按钮会被画成灰的、点了给一句说明（见 MainActivity.onSendClick）。
     */
    public boolean isSending(Discovery.Peer p) {
        synchronized (lock) {
            if (p == null) {
                return false;
            }
            return sendIds.contains(idOf(p));
        }
    }

    public int listeningCount() {
        synchronized (lock) {
            return listenIds.size();
        }
    }

    public int sendingCount() {
        synchronized (lock) {
            if (broadcastOn) {
                // 广播时"送声几台"就是实际目标数（否则通知栏会写"送声 0"而其实在广播）
                return Math.max(sendIds.size(), sendTargetCount());
            }
            return sendIds.size();
        }
    }

    /** 「已连接几台」：收或发都算连上（同一台既收又发只算一台；广播时算上所有在线设备）。 */
    public int connectedCount() {
        synchronized (lock) {
            Set<String> all = new LinkedHashSet<String>(listenIds);
            all.addAll(sendIds);
            if (broadcastOn) {
                for (Discovery.Peer p : peers()) {
                    all.add(idOf(p));
                }
            }
            return all.size();
        }
    }

    /**
     * 把已经消失的设备从「收听 / 送声」名单里摘掉。
     *
     * <p>为什么要这个：对端关机或走出 Wi-Fi 后，Discovery 15 秒会把它的卡片摘掉，
     * 卡片一没，用户就再也点不到那个开关 —— 麦克风却还开着。这既是隐私问题也是体验问题。
     * 所以卡片消失 = 自动停掉对应的传输。
     *
     * <p>只有"确实见过设备、且发现服务在跑"时才清理：否则 Wi-Fi 刚打开、列表还是空的时候，
     * 用户刚点下的开关会被立刻清掉。
     */
    public void pruneGoneTargets() {
        synchronized (lock) {
            // ★ 「听广播」或「广播我的声音」开着时不能提前返回：那时用户很可能一台设备都没看见，
            //   而目标集合的同步（新设备上线要自动开始收我的声音）就靠下面那几行。
            if (!channelListen && !broadcastOn
                    && (!sawPeer || (sendIds.isEmpty() && listenIds.isEmpty()))) {
                return;
            }
            Discovery d = discovery;
            if (d == null || !d.isRunning()) {
                return;
            }
            Set<String> alive = new LinkedHashSet<String>();
            for (Discovery.Peer p : peers()) {
                alive.add(idOf(p));
            }
            boolean changed = false;
            for (String id : new ArrayList<String>(sendIds)) {
                if (!alive.contains(id)) {
                    // ★ 不再把用户的"送声音"意图删掉（2026-10-14 安全检查）：
                    //   对方关掉 App 之后我们要停止发送，但**意图要留着** —— 它回来自动恢复，
                    //   不该让用户再点一次。真正决定"发不发"的是 collectSendTargets 里的活性判断。
                    changed = true;
                }
            }
            // ★ 关键修复（2026-10-12 真机反馈）：不再因为"设备列表里暂时没有"就取消
            // 用户的「收听」选择。Wi-Fi 上偶尔丢一两个信标是常态（15 秒没收到就当设备走了），
            // 设备只要在列表里闪一下，下面这段就会把 listenIds 清空 → 接收器被停 →
            // 而且再也不会自己起来。现场表现正是"界面显示收听中，但缓冲 0ms、流 0"。
            // 用户的勾选只有用户自己能取消；对方真的关机了，收不到包自然就没声音，不需要停。
            //
            // ★ 发送侧改成"同步目标集合"（2026-10-14）：广播开着时，新上线的设备要自动开始收到
            //   我的声音；目标没了就自动少发一份。全程不重启采集（见 syncSenderTargets）。
            syncSenderTargets();
            if (changed && sendIds.isEmpty() && !broadcastOn) {
                Log.i(TAG, "送声音的目标没了，发送器已停");
            }
            // 自愈：只要还有收听目标（或开着听广播），接收器就必须活着（可能被别处停掉过）。
            if ((!listenIds.isEmpty() || channelListen) && receiver == null) {
                ensureReceiver();
            } else if (listenIds.isEmpty() && sender == null && receiver != null && !channelListen) {
                // 既不听也不送、也没开听广播：接收器只是刚才为了收对端统计才开着的，
                // 可以收掉了（省电、放端口）。
                stopReceiver();
            }
            applyAllowedHosts();
        }
    }

    // ────────────────────────── ★ 送什么声音（音源 + 授权） ──────────────────────────

    /** 用户选的音源。注意这可能和实际采的不一样，看 {@link #activeSourceKind()}。 */
    public int sourceKind() {
        return sourceKind;
    }

    /** <b>实际</b>在采的音源：没在送声音时就是用户选的那个。界面显示"现在送的是什么"必须用它。 */
    public int activeSourceKind() {
        AudioSend s = sender;
        if (s != null && s.isRunning() && s.activeSourceKind() >= 0) {
            return s.activeSourceKind();
        }
        return sourceKind;
    }

    /** 用户选的音源和实际在采的不一样吗（= 界面上要显眼地写清楚原因）。 */
    public boolean sourceMismatch() {
        AudioSend s = sender;
        return s != null && s.isRunning() && s.activeSourceKind() >= 0
                && s.activeSourceKind() != sourceKind;
    }

    /** 音源开关：true = 在采（按 sourceKind），false = 已停止。 */
    public boolean sourceEnabled() {
        return sourceEnabled;
    }

    /**
     * 音源<b>真实的状态</b>（{@link SourcePolicy} 的 SRC_OFF / SRC_MIC / SRC_PLAYBACK）。
     *
     * <p>界面那两颗按钮的视觉状态只许从它读，而且<b>每次刷新都重新算</b>（实时问采集端
     * {@link #activeSourceKind()}）—— 绝不用缓存的标志位，否则就会出现用户报过的
     * "实际在采麦克风、按钮却还是灰的"。
     */
    public int sourceState() {
        return SourcePolicy.state(sourceEnabled, activeSourceKind());
    }

    /** 这一颗音源按钮现在该不该点亮（供界面读；返回的就是引擎当下的真实状态）。 */
    public boolean sourceActive(int kind) {
        return SourcePolicy.lit(sourceState(), kind);
    }

    /**
     * 应用一个音源状态（值来自 {@link SourcePolicy} 的纯函数，界面据此点亮/熄灭按钮）。
     *
     * <p>OFF = 停止采集：顺带把"送给谁 / 广播"的意图也清掉 —— 停止采集还显示"在送声音"是骗人的。
     * MIC / PLAYBACK = 打开（或热切）那一路；<b>同一时刻只有一路</b>（采集端只有一份 AudioRecord）。
     *
     * @param state           {@link SourcePolicy} 的状态值
     * @param whyIfStopping   停止时的原因（写进界面；空则用"你手动停止了采集"）
     */
    public void applySourceState(int state, String whyIfStopping) {
        synchronized (lock) {
            boolean on = state != SourcePolicy.SRC_OFF;
            sourceEnabled = on;
            if (on) {
                sourceKind = state;
                sourceStopReason = "";
            } else {
                String why = (whyIfStopping == null) ? "" : whyIfStopping;
                sourceStopReason = why.length() > 0 ? why : "你手动停止了采集";
                sendIds.clear();
                broadcastOn = false;
            }
            AudioSend s = sender;
            if (on && s != null) {
                try {
                    s.setProjection(projection);
                    s.setSourceKind(sourceKind);
                } catch (Throwable t) {
                    Log.w(TAG, "切换音源失败", t);
                }
            }
            syncSenderTargets();
            // ★ 2026-11-01 真机：关闭 App 后系统仍挂着「Audio Mixing 正在获取屏幕」，
            //   卸载后也还在。根因是 MediaProjection 被刻意长期持有，只有 Engine.stop()
            //   才释放；而前台服务是 START_STICKY、界面关了它还活着。
            //   现在只要不再采「本机声音」（手动停止 / 切到麦克风），就立刻 stop() 掉授权：
            //   系统那条「屏幕共享中」随之消失。下次再选本机声音＝重新弹一次系统授权，
            //   这是隐私上正确的代价。
            if (!on || sourceKind != AudioSend.SRC_PLAYBACK) {
                releaseProjection();
            }
            if (!on) {
                stopReceiverIfIdle();
            }
        }
    }

    /** 这台机器支不支持采本机声音（Android 10+）。 */
    public boolean playbackSupported() {
        return AudioSend.playbackSupported();
    }

    public boolean hasProjection() {
        return projection != null;
    }

    // ── ★ 端口设置（2026-11-01）：电脑端改过端口后，手机必须能填同样的值 ──

    private int savedPort(String key, int def) {
        try {
            int v = app.getSharedPreferences("audiomixing", Context.MODE_PRIVATE)
                    .getInt(key, def);
            return (v >= 1024 && v <= 65534) ? v : def;
        } catch (Throwable t) {
            return def;
        }
    }

    /**
     * 保存的发现端口（默认 17830）。
     *
     * ★ 2026-11-01 用户：两端默认端口选 17830。老版本存下来的是旧默认 47830 ——
     * 那是"从没改过"的值，升级时自动搬到 17830，和电脑端一起换（否则两端都停在
     * 47830 也还能连，但新的默认就永远落不了地）。用户自己填过的其它值（如 17930）
     * 一律不动。
     */
    public int configuredDiscoveryPort() {
        int v = savedPort("disc_port", Proto.DEFAULT_DISCOVERY_PORT);
        if (v == Proto.LEGACY_DISCOVERY_PORT) {
            v = Proto.DEFAULT_DISCOVERY_PORT;
            try {
                app.getSharedPreferences("audiomixing", Context.MODE_PRIVATE)
                        .edit().putInt("disc_port", v).apply();
            } catch (Throwable t) {
                Log.w(TAG, "迁移旧发现端口失败（本次仍按 17830 用）", t);
            }
        }
        return v;
    }

    /** 音频端口不再让用户填：自动选空闲口，并在信标里公布，电脑按它发。 */
    public int configuredAudioPort() {
        return Proto.DEFAULT_AUDIO_PORT;
    }

    /** 当前实际绑定的发现端口（没启动时返回配置值）。 */
    public int discoveryPort() {
        Discovery d = discovery;
        return (d != null) ? d.boundPort() : configuredDiscoveryPort();
    }

    /**
     * 只保存发现端口。音频端口自动选空闲口并通过信标公布，不再让用户填。
     * 端口在 {@link #start()} 时生效，所以界面保存后要提示"重启 App"。
     */
    public boolean savePorts(int disc) {
        if (disc < 1024 || disc > 65534) {
            return false;
        }
        try {
            app.getSharedPreferences("audiomixing", Context.MODE_PRIVATE)
                    .edit()
                    .putInt("disc_port", disc)
                    .remove("audio_port")
                    .apply();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "端口设置存不下来", t);
            return false;
        }
    }

    public String projectionError() {
        return projectionError == null ? "" : projectionError;
    }

    /** 授权弹窗之前先记下"授权回来要送给哪一台"，拿不到授权就清掉。 */
    public void requestSendWithPlayback(Discovery.Peer p) {
        pendingSendId = (p == null) ? "" : idOf(p);
    }

    /** 用户放弃了授权（关掉了系统窗口/点了拒绝）：把待办清掉，别让界面一直转圈。 */
    public void cancelPendingSend(String why) {
        String id = pendingSendId;
        pendingSendId = "";
        if (id != null && id.length() > 0) {
            lastSendError = (why == null || why.length() == 0) ? "没有拿到送本机声音的授权" : why;
        }
    }

    /** 待办的那台设备 id（诊断报告里用：不是空的就是"卡在等授权"）。 */
    public String pendingSendId() {
        return pendingSendId == null ? "" : pendingSendId;
    }

    /**
     * 授权成功。由 {@link MixService} 调用 —— 而且必须在<b>已经启动的、
     * 类型含 mediaProjection 的前台服务里</b>调：targetSdk 34 下顺序反了系统直接抛
     * SecurityException（这是 Android 14 的硬性要求）。
     */
    public void onProjectionGranted(MediaProjection p) {
        if (p == null) {
            onProjectionFailed("系统没有返回授权对象");
            return;
        }
        String target;
        synchronized (lock) {
            // ★ 2026-11-01：授权弹窗还开着时用户已经关掉了「本机声音」/停止采集 →
            //   这张授权回来也不能留：立刻 stop 掉，否则又会出现"已经停了，
            //   系统却一直显示正在获取屏幕"。
            if (!sourceEnabled) {
                try {
                    p.stop();
                } catch (Throwable ignored) {
                }
                projectionError = "授权回来时采集已经停止，已立即关闭投屏授权";
                return;
            }
            projection = p;
            projectionError = "";
            sourceStopReason = "";
            try {
                // 必须注册回调：授权被系统收回时要立刻知道，否则界面会一直显示"送声中"
                // 而实际上一片静音 —— 那是最难查的一种假象。
                p.registerCallback(projectionCallback(), mainHandler);
            } catch (Throwable t) {
                Log.w(TAG, "注册投屏回调失败（继续用，只是收不到系统收回的通知）", t);
            }
            target = (pendingSendId == null) ? "" : pendingSendId;
            pendingSendId = "";
            // ★ 用户点了系统授权窗口 = 明确要本机声音：把音源开关打开，再热切到本机声音。
            if (sourceKind == AudioSend.SRC_PLAYBACK) {
                sourceEnabled = true;
            }
            AudioSend s = sender;
            if (s != null && sourceEnabled && sourceKind == AudioSend.SRC_PLAYBACK) {
                try {
                    s.setProjection(p);
                    s.setSourceKind(AudioSend.SRC_PLAYBACK);
                } catch (Throwable t) {
                    Log.w(TAG, "授权到手后切换到本机声音失败", t);
                }
            }
            // 授权到位后重建一次发送端：之前没授权时它打不开，现在能采了。
            syncSenderTargets();
        }
        if (target.length() > 0) {
            Discovery.Peer t = findById(target);
            if (t != null && t.host != null && !t.host.isEmpty()) {
                setSending(t, true);       // 授权前用户点的那一台：现在真的开始送
            } else {
                lastSendError = "授权拿到了，但设备列表里已经找不到那台设备了";
                Log.w(TAG, lastSendError);
            }
        }
    }

    /** 授权失败（用户在系统窗口点了拒绝，或系统不给）。必须如实记下来。 */
    public void onProjectionFailed(String err) {
        String msg = (err == null || err.length() == 0) ? "授权失败（原因未知）" : err;
        synchronized (lock) {
            projectionError = msg;
            pendingSendId = "";
            AudioSend s = sender;
            if (s != null) {
                try {
                    s.setProjection(null);
                } catch (Throwable ignored) {
                }
            }
            // ★ 绝不自动开麦：本机声音这条路断了就【停止】，并由用户自己决定下一步。
            applySourceState(SourcePolicy.afterPlaybackLost(sourceState()), msg);
        }
        Log.w(TAG, "采本机声音的授权没拿到：" + msg);
    }

    /**
     * 系统把授权收回了（用户在状态栏点"停止投放"、或者被别的 App / 系统抢走）。
     *
     * <p>★ 2026-10-19 用户第 1 条（隐私硬约束）：<b>绝不自动退回麦克风</b>。
     * 旧代码在这里 {@code setSourceKind(SRC_MIC)}，于是用户"停止本机声音采集"时麦克风被
     * <b>悄悄打开</b>（而按钮还是灰的，显示与实际不一致）—— 这正是"严重危害用户安全"那条。
     * 现在的处理：本机声音采集一律<b>停止</b>，界面如实显示"已停止 + 原因"。
     */
    public void onProjectionStopped() {
        synchronized (lock) {
            boolean wasGranted = projection != null;
            projection = null;
            if (wasGranted) {
                projectionError = "系统收回了「录制/投射」授权（在状态栏点了停止，或被别的 App 抢走）";
            }
            String reason = projectionError.length() > 0 ? projectionError : "「录制/投射」授权没了";
            AudioSend s = sender;
            if (s != null) {
                try {
                    s.setProjection(null);
                } catch (Throwable t) {
                    Log.w(TAG, "清掉旧授权失败", t);
                }
            }
            // 本机声音还在采 → 停掉；麦克风在采则不受影响（那条路和授权无关）。
            applySourceState(SourcePolicy.afterPlaybackLost(sourceState()), reason);
        }
        Log.w(TAG, "采本机声音的授权被系统收回了");
    }

    /**
     * 没有实际采集需求时释放投屏授权。
     *
     * <p>判据必须看"发送端是否真的在跑"，不能只看用户选的音源：
     * 用户可能选了「本机声音」但所有目标都离线，此时 `sender == null`，
     * 继续持有 MediaProjection 只会让系统那条「屏幕共享中」白白挂着。
     */
    private void releaseProjectionIfIdle() {
        if (projection == null) {
            return;
        }
        if (sourceEnabled && sourceKind == AudioSend.SRC_PLAYBACK && sender == null) {
            releaseProjection();
        }
    }

    /** 释放授权（停引擎时调用）。释放之后要再送本机声音就得重新授权一次。 */
    private void releaseProjection() {
        MediaProjection p;
        synchronized (lock) {
            p = projection;
            projection = null;
            pendingSendId = "";
            // 采集端手里那份引用也要清掉：它继续拿着一个已经 stop 的对象，
            // 下次热切回本机声音只会得到一片静音。
            AudioSend s = sender;
            if (s != null) {
                try {
                    s.setProjection(null);
                } catch (Throwable ignored) {
                }
            }
        }
        if (p == null) {
            return;
        }
        try {
            MediaProjection.Callback cb = projectionCallback;
            if (cb != null) {
                p.unregisterCallback(cb);
            }
        } catch (Throwable ignored) {
            // 没注册成功过就会抛，忽略
        }
        try {
            p.stop();
        } catch (Throwable t) {
            Log.w(TAG, "停止投屏授权失败", t);
        }
    }

    private MediaProjection.Callback projectionCallback() {
        if (projectionCallback == null) {
            projectionCallback = new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    onProjectionStopped();
                }
            };
        }
        return projectionCallback;
    }

    // ── 这些是给界面和诊断报告用的人话（只在这一处定义，免得两种说法把用户绕晕）──

    /** 「当前选择」那一行。 */
    public String sourceNameText() {
        return AudioSend.sourceName(sourceKind)
                + (sourceKind == AudioSend.SRC_PLAYBACK ? "（手机上正在放的音乐/视频/游戏）" : "（对讲用）");
    }

    /** 「实际采集」那一行：和用户选的不一样时，把原因一起写出来。 */
    public String activeSourceText() {
        AudioSend s = sender;
        if (s == null || !s.isRunning() || s.activeSourceKind() < 0) {
            return "没在采集（没点过任何一台的「送声音」）";
        }
        int act = s.activeSourceKind();
        StringBuilder sb = new StringBuilder(AudioSend.sourceName(act));
        if (act == sourceKind) {
            sb.append(" ✓");
        } else {
            sb.append(" ⚠（你要的是 ").append(AudioSend.sourceName(sourceKind))
                    .append("，采不到：").append(mismatchReason(s)).append("）");
        }
        return sb.toString();
    }

    /** 采集不到时给用户看的原因（优先用采集线程记下的那条）。 */
    public String mismatchReason(AudioSend s) {
        String e = (s == null) ? "" : s.lastError();
        if (e != null && e.length() > 0) {
            return e;
        }
        String p = projectionError();
        return p.length() > 0 ? p : "原因未知（看诊断报告）";
    }

    /** 「系统支持」那一行。 */
    public String playbackSupportText() {
        String ver = "Android " + Build.VERSION.RELEASE + "（API " + Build.VERSION.SDK_INT + "）";
        if (AudioSend.playbackSupported()) {
            return "支持 ✓ —— " + ver + "，可以用系统声音采集（AudioPlaybackCapture）";
        }
        return "不支持 ✗ —— " + ver + " 低于 Android 10，系统没有采集本机声音这个能力，只能用麦克风";
    }

    /** 「授权状态」那一行。 */
    public String projectionStateText() {
        if (!AudioSend.playbackSupported()) {
            return "不需要（低版本没有这个能力）";
        }
        if (projection != null) {
            return "已授权 ✓（授权一次之后一直用；App 重启后要再授权一次）";
        }
        String e = projectionError();
        if (e.length() > 0) {
            return "没有授权 ✗ —— " + e;
        }
        return "还没授权 —— 点任意一台的「送声音」会弹一次系统授权框，允许后就能送本机声音";
    }

    /** 「采集格式」那一行。 */
    public String captureFormatText() {
        AudioSend s = sender;
        if (s == null || !s.isRunning()) {
            return "（没在采集）";
        }
        return s.captureFormatText();
    }

    /**
     * 「静音情况」那一行 —— 采本机声音时最值钱的一条：
     * 包在发、但一直是零，几乎就是 DRM 保护内容或"禁止被采集的 App"。
     */
    public String silenceText() {
        AudioSend s = sender;
        if (s == null || !s.isRunning()) {
            return "（没在采集）";
        }
        long ms = s.silentMs();
        if (ms < 1500) {
            return "有声音 ✓（最近 " + (ms / 100) / 10f + " 秒内采到过内容）";
        }
        String sec = String.format(java.util.Locale.US, "%.1f", ms / 1000.0);
        if (!s.everLoud()) {
            return "一直是静音 ✗（已连续 " + sec + " 秒没有任何声音）"
                    + (s.activeSourceKind() == AudioSend.SRC_PLAYBACK
                    ? "—— 采本机声音时这通常意味着：这段内容是 DRM 保护的（部分音乐/影视 App）、"
                    + "或者那个 App 主动禁止了被采集；先在手机上放点声音试试。"
                    : "—— 麦克风没收到声音（手机静音了？离得远？）");
        }
        return "最近 " + sec + " 秒没有声音（之前在正常采）";
    }

    /**
     * 界面主卡片里那句「现在送的是什么」。
     *
     * <p>★ 说的是<b>引擎当下的真实状态</b>（{@link #sourceState()}，实时问采集端），不是"用户上次选的"。
     * 用户第 1 条报的"实际在采麦克风、按钮却还是灰的"就是两者不一致造成的。
     */
    public String sourceStateText() {
        if (!AudioSend.playbackSupported()) {
            return "你的安卓版本（Android " + Build.VERSION.RELEASE + "）不支持采集本机声音，只能用麦克风。"
                    + "要送手机上正在放的声音，需要 Android 10 及以上。";
        }
        int state = sourceState();
        if (state == SourcePolicy.SRC_OFF) {
            String why = sourceStopReason.length() > 0 ? ("（" + sourceStopReason + "）") : "";
            return "已停止采集" + why + "：两个音源都关着（同一时刻只能采一路）。"
                    + "点「🔊 本机声音」或「🎤 麦克风」才会重新开始采。";
        }
        AudioSend s = sender;
        boolean sending = s != null && s.isRunning();
        if (state == SourcePolicy.SRC_PLAYBACK) {
            if (sending) {
                return "正在送：🔊 本机声音（手机上正在放的音乐/视频/游戏）";
            }
            return "已打开 🔊 本机声音采集；点任意一台设备的「送声音」开始送"
                    + (projection == null ? "（第一次会让你在系统窗口里点一次允许）" : "") + "。";
        }
        // 麦克风：把"另一颗为什么灭了"写清楚（用户第 3 条口径：同一时刻只能采一路）。
        String exclusive = "（同一时刻只能采一路：开了麦克风，「🔊 本机声音」那颗就是关的）";
        if (sending) {
            return "正在送：🎤 麦克风（对讲用的）" + exclusive;
        }
        return "已打开 🎤 麦克风采集；点任意一台设备的「送声音」开始送。" + exclusive;
    }

    /** 采本机声音却一直是静音时的提醒；不需要提醒就返回空串。 */
    public String silenceHintText() {
        AudioSend s = sender;
        if (s == null || !s.isRunning() || s.activeSourceKind() != AudioSend.SRC_PLAYBACK) {
            return "";
        }
        long ms = s.silentMs();
        if (ms < 4000) {
            return "";
        }
        String sec = String.format(java.util.Locale.US, "%.0f", ms / 1000.0);
        if (!s.everLoud()) {
            return "⚠ 本机声音已经送了 " + sec + " 秒，但一直是静音："
                    + "DRM 保护的内容（部分音乐/影视 App）和主动禁止被采集的 App 采不到，属正常现象；"
                    + "先在手机上放点声音验证一下。";
        }
        return "本机声音最近 " + sec + " 秒是静的（之前采到过）。";
    }

    private Discovery.Peer findById(String id) {
        for (Discovery.Peer p : peers()) {
            if (id.equals(idOf(p))) {
                return p;
            }
        }
        return null;
    }

    private void ensureReceiver() {
        if (receiver != null) {
            return;
        }
        try {
            // 带 Context 的构造会拿 WifiLock：不收包时 Wi-Fi 进省电模式会把抖动缓冲冲垮。
            AudioRecv r = new AudioRecv(app, audioPort);
            // ★ 2026-11-04（B 第一阶段）：自回声过滤的基准。对自己 device_id 的哈希；
            //   收到 originId == 自己的帧直接丢并计数（诊断页显示"自回声丢弃 N"）。
            r.setSelfOriginId(selfOriginId());
            // ★ 合并版：接收器是「按需启停」的，每次重建都要把用户设过的左右声道恢复回去，
            //   否则重开收听后左右平衡会悄悄跳回默认（和逐台增益 restoreGains 同一个道理）。
            applyChannelSettings(r);
            // ★ 2026-11-14：接收器每次重建都要把「缓冲档位 / 速率匹配」补回去。
            r.setTargetBufferMs(recvBufMs);
            //（本机输出延迟也在这套设置里，applyChannelSettings 会一并补回去。）
            // ★ 对端统计的回话入口：对方每 2 秒会说一次"我发了多少、是不是正在发"。
            //   回调在收包线程上，所以只做"解析 + 存起来"；解析失败就当没收到，
            //   绝不能让一份坏包把"收听"整条链路带走。
            r.setStatsListener(new AudioRecv.StatsListener() {
                @Override
                public void onStats(String host, byte[] payload) {
                    onPeerStats(host, payload);
                }
            });
            // ★ 2026-11-17 NACK：缺包时把 seq 列表经发现层回给发送端（收包线程上，必须快）。
            r.setNackEnabled(nackEnabled);
            r.setNackListener(new AudioRecv.NackListener() {
                @Override
                public void onMissing(String host, int[] missingSeqs) {
                    sendNackRequest(host, missingSeqs);
                }
            });
            // ★ 2026-11-02：默认音频端口被别的 App 占时，AudioRecv 会自己换空闲口；
            //   必须在信标里公布实际端口，否则电脑按旧端口发过来全部丢包。
            r.setPortListener(new AudioRecv.PortListener() {
                @Override
                public void onPortChanged(int oldPort, int newPort) {
                    audioPort = newPort;
                    Discovery d = discovery;
                    if (d != null) {
                        try {
                            d.setAudioPort(newPort);
                        } catch (Throwable t) {
                            Log.w(TAG, "同步音频端口到信标失败", t);
                        }
                    }
                    Log.i(TAG, "音频端口已改为 " + newPort + "（原 " + oldPort + "），信标已同步");
                }
            });
            r.start();
            receiver = r;
            // ★ 端口被别的 App 占了时 AudioRecv 会换一个；信标必须跟着改，
            //   否则电脑按信标里的旧端口发过来，手机永远收不到。
            int actual = r.port();
            if (actual > 0 && actual != audioPort) {
                audioPort = actual;
                Discovery d = discovery;
                if (d != null) {
                    d.setAudioPort(actual);
                }
            }
            applyAllowedHosts();
            // ★ 2026-11-02：接收器重建后，把每台设备用户设过的「我听它的」补回去。
            //   原来只补发送侧（restoreGains），重开收听/保存端口后滑杆还写着 0.40，
            //   实际接收增益却回到了 1.00 —— 条子和声音不一致。
            restoreRecvGains(r);
            ensureMediaSession();
        } catch (Throwable t) {
            Log.w(TAG, "启动收听失败（端口 " + audioPort + " 可能被占）", t);
        }
    }

    /**
     * 把「要听哪几台」同步给接收端。
     *
     * <p>地址取自 listenHosts（用户点选那一刻记下来的），**不**在这里现查设备列表 ——
     * 现查的话，设备只要在列表里闪一下，白名单就会退化成"只允许 255.255.255.255"，
     * 一个包都收不进来，而且不会自己恢复（这是真机上遇到的头号问题）。
     *
     * <p>AudioRecv 的语义是"名单空 = 全收"，所以这里必须把名单显式写满 ——
     * 一台都没有时留一个不可能命中的占位地址，避免退化成全收。
     */
    private void applyAllowedHosts() {
        AudioRecv r = receiver;
        if (r == null) {
            return;
        }
        try {
            r.clearAllowed();
            if (channelListen) {
                // ★ 听广播：名单留空 = 谁的都听（AudioRecv 的语义就是"空 = 全收"）。
                //   这一行就是"手机能听电脑广播"的关键：原来这里一定会写进至少一个地址
                //   （没有收听目标时写一个不可能命中的占位地址），于是广播包全被过滤掉。
                //
                //   为什么不能做得更"精确"：音频包里没有路由标记，接收端分不出
                //   "广播给我的"和"点对点给我的"，所以只能如实按"谁来都听"实现。
                Log.i(TAG, "听广播已打开：接收端不再按名单过滤（谁来都听）");
                return;
            }
            int n = 0;
            for (String id : listenIds) {
                String host = listenHosts.get(id);
                if (host != null && !host.isEmpty()) {
                    r.allow(host, true);
                    n++;
                }
            }
            if (n == 0) {
                r.allow("255.255.255.255", true);
            }
        } catch (Throwable t) {
            Log.w(TAG, "同步收听名单失败", t);
        }
    }

    private void stopReceiver() {
        AudioRecv r = receiver;
        receiver = null;
        statsCache = null;
        if (r != null) {
            try {
                r.stop();
            } catch (Throwable t) {
                Log.w(TAG, "停止收听失败", t);
            }
        }
        releaseMediaSession();
    }

    /**
     * ★ 2026-11-02 用户：手机音量面板里分「手机音量」和「App 音量」，
     * 希望本应用能出现在「App 音量」那一栏，而不是只跟着手机媒体总音量走。
     *
     * <p>Android 的分应用音量只认"正在播放媒体的、有 MediaSession 的 App"。
     * 我们播放用的是 AudioTrack（USAGE_MEDIA），但没有会话 —— 系统音量面板里
     * 就只剩一个总的媒体音量。这里在开始收听时建一个本地播放的 MediaSession
     * （不接管按键、不做播放控制，只为让系统把我们识别成媒体 App），
     * 停止收听时立刻释放；不常驻、不伪造"正在播放"。
     */
    private void ensureMediaSession() {
        if (mediaSession != null || app == null) {
            return;
        }
        synchronized (lock) {
            if (mediaSession != null) {
                return;
            }
            try {
                MediaSession s = new MediaSession(app, "AudioMixing");
                // ★ 2026-11-16：原来 setCallback(cb) 内部要 new Handler()，而这里是
                //   am-beacon-rx 线程（没 Looper）→ 直接抛 RuntimeException 把整段建会话
                //   逻辑打断，日志里一直在刷这个异常。带 Handler 的重载可以指定 Looper，
                //   不依赖调用线程。
                s.setCallback(new MediaSession.Callback() {
                    // 不接管播放/暂停：这个会话只为让系统看见"我们在放媒体"。
                }, mainHandler);
                // ★ 默认仍是"本地音量"：不干扰用户平时用音量键调手机音量。
                //   只有环绕开着的时候才切到远程音量（见 setSessionRemoteVolume）。
                s.setPlaybackToLocal(mediaAttrs());
                PlaybackState state = new PlaybackState.Builder()
                        .setState(PlaybackState.STATE_PLAYING,
                                PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                        .build();
                s.setPlaybackState(state);
                s.setActive(true);
                mediaSession = s;
                sessionRemote = false;   // 新建的会话是本地音量
                Log.i(TAG, "媒体会话已建立");
            } catch (Throwable t) {
                Log.w(TAG, "创建媒体会话失败（分应用音量可能不显示）", t);
            }
        }
    }

    private AudioAttributes mediaAttrs() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
    }

    /**
     * 环绕开启时把媒体会话切成「远程音量」，让系统把音量键直接回调给我们。
     *
     * <p>★ 2026-11-16：这条路**不看系统音量有没有变**，所以第 2 档把音量钉在 0、
     * 或 MIUI 后台卡掉 VOLUME_CHANGED 广播时，音量键照样能被我们接住。
     * 环绕一停就切回本地音量，绝不长期占用用户的音量键。
     */
    private void setSessionRemoteVolume(boolean remote) {
        MediaSession s = mediaSession;
        if (s == null) {
            return;
        }
        // 状态没变就别反复设（这个函数会被状态同步频繁调到）
        if (sessionRemote == remote) {
            return;
        }
        try {
            if (remote) {
                s.setPlaybackToRemote(new android.media.VolumeProvider(
                        // ★ 2026-11-16：必须是 RELATIVE，不能用 ABSOLUTE。
                        //   ABSOLUTE 等于告诉系统"音量由远端设备管"，系统就不再给本地流
                        //   应用音量衰减 —— 第 2 档把媒体音量钉到 0 也就不起作用了，
                        //   表现就是"环绕开着切后台，本机突然有声音"。
                        //   RELATIVE 只把"方向"回调给我们，本地流的音量仍由系统音量控制。
                        android.media.VolumeProvider.VOLUME_CONTROL_RELATIVE, 100, 50) {
                    @Override
                    public void onAdjustVolume(int direction) {
                        handleMediaVolumeKey(direction);
                    }

                    public void onSetVolume(int volume) {
                        // 绝对音量面板直接拖到某个值：不当成按键方向处理，忽略。
                    }
                });
                sessionRemote = true;
                Log.i(TAG, "媒体会话切到远程音量（音量键归本 App）");
            } else {
                s.setPlaybackToLocal(mediaAttrs());
                sessionRemote = false;
                Log.i(TAG, "媒体会话切回本地音量（音量键归还系统）");
            }
        } catch (Throwable t) {
            Log.w(TAG, "切换媒体会话音量模式失败", t);
        }
    }

    /**
     * 媒体会话拿到的音量键。
     *
     * <p>★ 2026-11-16：这是「不依赖系统音量变化」的那条路 —— 系统把按键直接交给我们，
     * 所以第 2 档把音量钉在 0、或者后台广播被 ROM 拦掉，都不影响这里。
     *
     * <ul>
     *   <li>环绕开着 + 逐台模式：按 A/B 各自的上限 ±5%（和前台面板一致的语义）。</li>
     *   <li>其它情况：老老实实调系统媒体音量，绝不能把用户的音量键吞掉。</li>
     * </ul>
     */
    private void handleMediaVolumeKey(int direction) {
        if (direction == 0) {
            return;
        }
        try {
            if (surroundOn && surroundPerSpeakerMode()) {
                int step = direction > 0 ? 5 : -5;
                surroundNudgeVolumeRange(step);
                final String msg = "A 上限 " + surroundMaxA + "%"
                        + (surroundHasB() ? "  ·  B 上限 " + surroundMaxB + "%" : "");
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            android.widget.Toast.makeText(app, msg,
                                    android.widget.Toast.LENGTH_SHORT).show();
                        } catch (Throwable ignored) {
                        }
                    }
                });
                Log.i(TAG, "[SR-VOL] media key step=" + step
                        + " A=" + surroundMaxA + " B=" + surroundMaxB);
                return;
            }

            // ② 本机哑着（「静音」/「传送静音」/ 送声第 2 档）：
            //    系统媒体音量被钉在 0，按键落到系统那边等于没反应 —— 这里接住，
            //    改成调"正在收我声音的那几台"的播放音量（和前台完全一致的语义）。
            int mode = channelMode();
            boolean outMuted = mode == ChannelRouter.MODE_MUTE
                    || mode == ChannelRouter.MODE_SEND_MUTE;
            boolean sendSilent = sendModePref == 2;
            if (outMuted || sendSilent) {
                int dir = direction > 0 ? 1 : -1;
                int sent = sendingCount();
                if (sent > 0) {
                    // ★ 2026-11-16（用户）：相对增减 —— 每台在自己已设音量上各 ±5，不拉平。
                    //   ★ 还要显示每台当前音量（像环绕那样），不能只报"+5%"让人猜。
                    int step = dir * 5;
                    String detail = nudgeRemoteVolumesAll(step);
                    toastOverApp(detail.isEmpty()
                            ? "这几台不支持远程调音量，要在那台上调"
                            : "音量 " + (step > 0 ? "↑" : "↓") + Math.abs(step) + "%\n"
                                    + detail + "（本机不出声）");
                    Log.i(TAG, "[SR-VOL] media key 相对调节 " + step + " → " + detail);
                    return;
                }
                // 没在送声：解除静音并把本机音量调一格，否则按了等于没按。
                if (outMuted) {
                    setChannelMode(ChannelRouter.MODE_STEREO);
                }
                if (sendModePref == 2) {
                    setSendMode(1);
                }
                float nv = Math.max(0f, Math.min(1f, outVolume + dir * 0.1f));
                setOutVolume(nv);
                toastOverApp("已解除静音 · 本机音量 " + Math.round(nv * 100f) + "%");
                return;
            }

            // ③ 正常状态：老老实实调系统媒体音量，绝不吞掉用户的音量键。
            AudioManager am = audioManager();
            if (am != null) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction,
                        AudioManager.FLAG_SHOW_UI);
            }
        } catch (Throwable t) {
            Log.w(TAG, "[SR-VOL] media key failed", t);
        }
    }

    private void releaseMediaSession() {
        MediaSession s = mediaSession;
        mediaSession = null;
        sessionRemote = false;
        if (s == null) {
            return;
        }
        try {
            s.setActive(false);
        } catch (Throwable ignored) {
        }
        try {
            s.release();
        } catch (Throwable ignored) {
        }
    }

    private void stopSender() {
        AudioSend s = sender;
        sender = null;
        if (s != null) {
            try {
                s.stop();
            } catch (Throwable t) {
                Log.w(TAG, "停止送声音失败", t);
            }
        }
    }

    // ────────────────────────── ★ 对端统计交换（两机互报） ──────────────────────────

    /**
     * 收到一份对端统计（<b>跑在收包线程上</b>）。
     *
     * <p>为什么这里还要再兜一层 try/catch：{@link AudioRecv} 的收包线程虽然也兜了，
     * 但真机 Wi-Fi 上什么怪包都有，解析失败顶多是这一份统计丢掉，
     * <b>绝不能让"收听"整条链路陪葬</b>（那条链路刚刚才修好）。
     */
    private void onPeerStats(String host, byte[] payload) {
        try {
            if (host == null || host.isEmpty() || payload == null) {
                return;
            }
            Diagnosis.PeerStats ps = Diagnosis.parse(payload, payload.length);
            if (ps == null) {
                return;                 // 不是统计包/被截断了，静默丢掉
            }
            // ★ 2026-11-01 用户：「电脑端修改音量手机不同步」。
            //   根因在这里：这一层原来把**UDP 来源 IP** 当设备键，而界面 / 收听名单 /
            //   发送目标用的都是**发现层选出的 peer.host**。电脑有双网卡 / VPN / Wi-Fi 与
            //   有线并存时，统计包从哪块网卡发出和信标里报的地址可能不是同一个 ——
            //   音量就被写进了一个界面永远不读的键（手机→电脑那条按 node_id 走，所以正常）。
            //   统计包里的 `node` 才是稳定身份：先用它反查出同一个 peer.host，再往下传，
            //   两处的键从此是同一个字符串。
            String key = resolveHost(ps.node, host);
            ps.host = key;
            ps.receivedAtMs = SystemClock.elapsedRealtime();
            // ★ 心跳也是一条"它还活着"的证据：电脑关掉 App 之后信标和心跳都会停，
            //   这两条一起构成"确认走了"的判据（见 collectSendTargets 的宽限期）。
            onHeartbeat(key);
            if (!key.equals(host)) {
                onHeartbeat(host);      // 发送名单万一还按来源地址记着，也一并刷新
            }
            // ★ 2026-11-08：电脑端可以切换"静音档乘数策略"（同一个设置，两个入口）。
            handleRemoteSilentPolicy(key, ps);
            if (ps.silentPolicyOnly) {
                return;             // 纯设置包：不参与收听/增益/对端统计，处理完就走
            }
            // ★ ① 对端请求我收听它（它正在把声音送给我）→ 自动打开收听（可撤销）
            handleWantListen(key, ps);
            // ★ ① 共享音量：对端报的"它送我的那条链路"的音量 = 我这条接收链路的音量。
            //   第二个地址是**音频包真正的来源**（AudioRecv 按它记增益），两者不一致时一起设。
            handleRemoteGain(key, ps, host);
            synchronized (peerLock) {
                peerStats.remove(key);
                peerStats.put(key, ps);    // 重新插入 = 最新的排在最后，下面按顺序淘汰最老的
                while (peerStats.size() > PEER_STATS_MAX) {
                    java.util.Iterator<String> it = peerStats.keySet().iterator();
                    if (!it.hasNext()) {
                        break;
                    }
                    it.next();
                    it.remove();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "对端统计解析失败（已忽略这一份）", t);
        }
    }

    // ────────────────── ★ ④ 心跳 / ① 自动收听 / ① 共享音量 ──────────────────

    /** 收到一个统计包 = 对端还活着：把这台设备的"最后确认活着"时间刷新。 */
    private void onHeartbeat(String host) {
        long nowMs = SystemClock.elapsedRealtime();
        for (Map.Entry<String, String> e : sendHosts.entrySet()) {
            if (host.equals(e.getValue())) {
                sendLastAliveMs.put(e.getKey(), nowMs);
            }
        }
    }

    /**
     * ★ 「智连」名单（按设备地址 host 记，2026-10-18 用户第 6 条）。
     *
     * <p>命中的设备：它点「送给它」时，本机<b>自动替它打开收听</b>。手机端<b>只</b>自动开"听"，
     * 绝不自动往外送声音 —— 那是用户点名的安全边界。
     *
     * <p>★ 2026-11-12 用户：「打开软件本机默认就是智连模式」——
     * 现在**默认就是开**，只有用户亲手关掉的设备才记进 {@link #smartOffHosts}。
     *
     * <p>为什么按 host 而不按节点 id：这张名单是**用户点出来**的，地址在发现列表里最直观；
     * 而统计包那条路（{@link #onPeerStats}）会先把 node 反查成同一个 peer.host 再进来，
     * 所以两处用的仍然是同一个键。
     */
    private final Set<String> smartHosts = new LinkedHashSet<String>();
    /** ★ 2026-11-12：被**显式关掉**智连的设备（地址 / 节点 id / 名字都会记一份，换 IP 也跟得住）。 */
    private final Set<String> smartOffHosts = new LinkedHashSet<String>();
    // ★ 2026-11-04（A）：电脑显式「请开始/停止接收」的请求号与回执。
    //   host → 已处理到的最大 reqId（同一个号只处理一次）；
    //   host → "id|action|reason"（拼进本机统计发回去，电脑端能看到结果）。
    private final Map<String, Long> lastListenReqId = new java.util.HashMap<String, Long>();
    private final Map<String, String> listenAckFor = new java.util.HashMap<String, String>();
    /**
     * ★ 我们替哪几台【自动打开过】收听（按 host 记，2026-10-15 用户反馈第 3 条）。
     *
     * <p>为什么必须单独记一笔：对端说"我不送了"（wantListen=false）时，只有
     * <b>我们替它自动开的</b>那一条该收回；用户自己点开的一律不碰（撤销不是惩罚，
     * 只是把当初替他打开的那一下收回去）。没有这份记录就只能猜，猜错就是动用户的设置。
     */
    private final Set<String> autoListenHosts = new LinkedHashSet<String>();

    /** 这台设备开没开「智连」（host 为空一律算没开；界面拿它给第 4 颗按钮上色）。 */
    public boolean isSmart(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        synchronized (lock) {
            return smartOnLocked(host);
        }
    }

    /**
     * ★ 2026-11-04（第 7 条）：智连名单**不能只按 IP** 判。
     *
     * <p>用户真机：手机上开了智连，电脑一「送声音」手机却不自动收听 —— 电脑有双网卡/
     * VPN 时，统计包从另一块网卡进来（或者信标里报的地址换过一次），`resolveHost`
     * 算出的 host 与当初点开关时的 host 不是同一个字符串，`smartHosts.contains(host)`
     * 就永远 false，代码看起来全对、功能就是不生效。
     *
     * <p>现在同一个设备只要命中任一稳定键就算开了：当前地址、节点 id、设备名。
     * 旧版本存下来的纯 IP 名单照常有效（向后兼容）。
     */
    private boolean smartOnLocked(String host) {
        Discovery.Peer p = peerByHost(host);
        // ★ 2026-11-12 用户：「打开软件本机默认就是智连模式」——
        //   判据从"在白名单里才算开"翻转成"只有被显式关掉才算关"。
        //   同一个设备命中地址 / 节点 id / 名字任一键都算关（换 IP、换网卡也不漏）。
        if (smartOffHosts.contains(host)) {
            return false;
        }
        if (p != null) {
            if (smartOffHosts.contains(idOf(p))) {
                return false;
            }
            String n0 = p.name == null ? "" : p.name.trim();
            if (!n0.isEmpty() && smartOffHosts.contains(n0)) {
                return false;
            }
        }
        // 老版本留下的白名单仍然算"开"（从旧版本升级上来的用户不会被翻成关）
        if (smartHosts.contains(host)) {
            return true;
        }
        if (p == null) {
            return true;      // 还没发现过它：默认开
        }
        if (smartHosts.contains(idOf(p))) {
            return true;
        }
        String name = p.name == null ? "" : p.name.trim();
        if (!name.isEmpty() && smartHosts.contains(name)) {
            return true;
        }
        return true;          // ★ 默认开
    }

    /**
     * 切换这台设备的「智连」开关并落盘，返回<b>新状态</b>（界面据此立刻上色，不必等下一帧）。
     *
     * <p>不用额外做别的：名单一变，下一轮统计包（每 ~2 秒一次）走到 {@link #handleWantListen}
     * 时判据自然就变了 —— 对端还在请求的话，那一轮就会自动把收听打开；对端没在请求，
     * 开了也只是"允许"，不会凭空多出一条收听。
     */
    public boolean toggleSmart(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        boolean nowOn;
        synchronized (lock) {
            boolean on;
            if (smartOnLocked(host)) {
                // 关掉时把该设备的几个键一起写进"显式关闭"名单（地址 / 节点 id / 名字），
                // 避免"换了 IP 还显示开、点一下却关不掉"。
                smartHosts.remove(host);
                smartOffHosts.add(host);
                Discovery.Peer p0 = peerByHost(host);
                if (p0 != null) {
                    smartHosts.remove(idOf(p0));
                    smartOffHosts.add(idOf(p0));
                    if (p0.name != null && !p0.name.trim().isEmpty()) {
                        smartHosts.remove(p0.name.trim());
                        smartOffHosts.add(p0.name.trim());
                    }
                }
                on = false;
            } else {
                // ★ 2026-11-04（A 硬规则）：广播模式和智连不能组合。广播开着时
                //   只能关、不能开；UI 也会置灰，但引擎必须再挡一次（防旧界面绕过）。
                if (isBroadcasting()) {
                    Log.i(TAG, "广播开着，拒绝打开智连：" + host);
                    return false;
                }
                // 同时记节点 id / 名字：以后它换 IP 了，智连仍然跟着这台设备走。
                smartHosts.add(host);
                smartOffHosts.remove(host);
                Discovery.Peer p1 = peerByHost(host);
                if (p1 != null) {
                    smartHosts.add(idOf(p1));
                    smartOffHosts.remove(idOf(p1));
                    if (p1.name != null && !p1.name.trim().isEmpty()) {
                        smartHosts.add(p1.name.trim());
                        smartOffHosts.remove(p1.name.trim());
                    }
                }
                on = true;
            }
            saveSmartHosts();
            nowOn = on;
        }
        // ★ 2026-11-04（第 11 条）：刚打开智连就**立刻**看一次对端缓存的统计，
        //   对方已经在送就马上开始收 —— 不再等下一轮 2 秒统计包，更不该等二十秒。
        if (nowOn) {
            applySmartNow(host);
        }
        return nowOn;
    }

    /** 开智连的瞬间用缓存里的最后一份统计走一遍同一套判据（不伪造发送状态）。 */
    private void applySmartNow(String host) {
        if (host == null || host.isEmpty()) {
            return;
        }
        Diagnosis.PeerStats cached = null;
        synchronized (peerLock) {
            cached = peerStats.get(host);
        }
        if (cached != null && cached.sending) {
            handleWantListen(host, cached);
        }
    }

    // ── 「智连」名单的持久化（键 smart_hosts，逗号分隔；host 里不会有逗号）──

    private void saveSmartHosts() {
        if (app == null) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder();
            for (String h : smartHosts) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(h);
            }
            app.getSharedPreferences("audiomixing", Context.MODE_PRIVATE)
                    .edit().putString("smart_hosts", sb.toString()).apply();

            // ★ 2026-11-12：显式关闭名单（空串也写，否则"全部恢复默认开"会存不下来）
            StringBuilder off = new StringBuilder();
            for (String h : smartOffHosts) {
                if (off.length() > 0) {
                    off.append(',');
                }
                off.append(h);
            }
            app.getSharedPreferences("audiomixing", Context.MODE_PRIVATE)
                    .edit().putString("smart_off", off.toString()).apply();
        } catch (Throwable t) {
            Log.w(TAG, "「智连」名单存不下来", t);
        }
    }

    private void loadSmartHosts() {
        if (app == null) {
            return;
        }
        try {
            String raw = app.getSharedPreferences("audiomixing", Context.MODE_PRIVATE)
                    .getString("smart_hosts", "");
            for (String h : raw.split(",")) {
                String t = h.trim();
                if (!t.isEmpty()) {
                    smartHosts.add(t);
                }
            }
            String rawOff = app.getSharedPreferences("audiomixing", Context.MODE_PRIVATE)
                    .getString("smart_off", "");
            for (String h : rawOff.split(",")) {
                String t = h.trim();
                if (!t.isEmpty()) {
                    smartOffHosts.add(t);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "「智连」名单读不回来", t);
        }
    }

    /** 对端在请求我收听：**只有这台设备开了「智连」**才自动加进收听名单。 */
    private void handleWantListen(String host, Diagnosis.PeerStats ps) {
        synchronized (lock) {
            boolean already = false;
            for (String id : listenIds) {
                if (host.equals(listenHosts.get(id))) {
                    already = true;
                    break;
                }
            }
            // ★ 2026-10-18 用户第 6 条：判据只剩「这台设备开没开『智连』」。
            //   删掉的两个旧判据（全局总开关 + "关过一次就永不再自动"的黑名单）见 LinkPolicy.listenAction。
            boolean smart = smartOnLocked(host);
            // ★ 这条收听是不是【我们替它自动开的】：只有这种才允许被"我不送了"收回去。
            boolean autoAdded = autoListenHosts.contains(host);
            // ★ 2026-11-01 用户：「智连不好用，实际状态和按键表达状态不匹配」。
            //   根因之一是 `want_listen` 由**发送方**决定发不发（旧电脑只在它自己开了
            //   「智连」时才发）。智连开在**本机**这张卡片上，就不该等对方开口：
            //   对方统计里 `sending=true` = 它确实在往我这儿发（点对点或广播都一样），
            //   本机直接收下。`want_listen` 仍然保留 —— 对方明确请求时也照样自动开。
            //   反向的停止语义不变：`sending=false` 且是我们替它开的，就收回。
            // ★ 2026-11-04（A 硬规则）：广播模式和智连不能组合 —— 广播开着时
            //   智连不再自动开收听（按钮也置灰）；用户手动点的收听不受影响。
            boolean want = !isBroadcasting() && (ps.wantListen || (smart && ps.sending));
            int action = LinkPolicy.listenAction(smart, already, want, autoAdded);
            if (action == LinkPolicy.LISTEN_ADD) {
                // 用节点 id 做键（找不到 Peer 就用地址兜底，和 listenHosts 的约定一致）
                Discovery.Peer p = peerByHost(host);
                String id = (p != null) ? idOf(p) : host;
                listenIds.add(id);
                listenHosts.put(id, host);
                listenPorts.put(id, (p != null && p.audioPort > 0) ? p.audioPort : Proto.DEFAULT_AUDIO_PORT);
                autoListenHosts.add(host);      // 记一笔：这条是我们替它开的
                // ★ 不再往界面上弹"XX 请求收听，已自动打开"这类提示（用户第 6 条点名不要）：
                //   是否需要自动听由「智连」开关说了算，开着就静默生效，不需要每次都说一句。
                Log.i(TAG, "「智连」已自动打开收听：" + ((p != null) ? p.label() : host));
                ensureReceiver();
                applyAllowedHosts();
            } else if (action == LinkPolicy.LISTEN_REMOVE) {
                // ★ 2026-10-15 用户反馈第 3 条「电脑停止送音后手机依旧收音不停止」：
                //   对端明确说"我不送了" → 收回我们替它自动打开的那一条。
                //   边界与电脑端逐字对齐：只收自动开的，用户自己点开的收听不碰。
                java.util.Iterator<String> it = listenIds.iterator();
                while (it.hasNext()) {
                    String id = it.next();
                    if (host.equals(listenHosts.get(id))) {
                        it.remove();
                        listenHosts.remove(id);
                        listenPorts.remove(id);
                    }
                }
                autoListenHosts.remove(host);
                Log.i(TAG, "对端停了送声音，已收回自动打开的收听：" + nameOrHost(host));
                applyAllowedHosts();
                stopReceiverIfIdle();
            }
            // ★ 2026-10-18 用户第 6 条：LISTEN_REVOKED 这个分支已随旧黑名单一起删掉。
            //   旧的「关过一次就永不再自动」会永久卡死自动收听（用户第 1 条真机 bug），
            //   现在"不自动"只是「智连」没开，对端再请求也会正常按开关重新判断。
            // ★ 2026-11-04（A）：显式「请开始/停止接收」必须处理并回 ack —— 不再单向。
            handleExplicitListenRequest(host, ps);
        }
    }

    /**
     * ★ 2026-11-04（A）：处理电脑发来的显式 listen_req，并写下回执。
     *
     * <p>策略和「智连」一致：**接收方的智连说了算**。没开智连 → 明确回 ignored，
     * 电脑能显示"对方没开智连"，而不是傻等；开了智连 → start 自动收听、stop 只收回
     * 我们替它自动开的那条（用户手动点开的收听绝不碰），并回 started/already/stopped/kept。
     */
    private void handleExplicitListenRequest(String host, Diagnosis.PeerStats ps) {
        synchronized (lock) {
            String req = ps.listenReq == null ? "" : ps.listenReq.trim();
            long reqId = ps.listenReqId;
            if (req.isEmpty() || reqId <= 0) {
                return;
            }
            Long before = lastListenReqId.get(host);
            if (before != null && reqId <= before) {
                return;                               // 同一个号只处理一次
            }
            lastListenReqId.put(host, reqId);
            boolean smart = smartOnLocked(host);
            boolean listening = false;
            for (String id : listenIds) {
                if (host.equals(listenHosts.get(id))) {
                    listening = true;
                    break;
                }
            }
            boolean autoAdded = autoListenHosts.contains(host);
            String action;
            String reason = "";
            if (!smart) {
                action = "ignored";
                reason = "本机未开智连";
            } else if (isBroadcasting() && "start".equals(req)) {
                action = "ignored";
                reason = "广播模式不能和智连组合";
            } else if ("start".equals(req)) {
                if (listening) {
                    action = "already";
                } else {
                    // 直接按"自动收听"打开（键=节点 id，地址=host），和 handleWantListen 同一套。
                    Discovery.Peer p = peerByHost(host);
                    String id = (p != null) ? idOf(p) : host;
                    listenIds.add(id);
                    listenHosts.put(id, host);
                    listenPorts.put(id, (p != null && p.audioPort > 0)
                            ? p.audioPort : Proto.DEFAULT_AUDIO_PORT);
                    autoListenHosts.add(host);
                    ensureReceiver();
                    applyAllowedHosts();
                    action = "started";
                }
            } else if ("stop".equals(req)) {
                if (autoAdded) {
                    java.util.Iterator<String> it = listenIds.iterator();
                    while (it.hasNext()) {
                        String id = it.next();
                        if (host.equals(listenHosts.get(id))) {
                            it.remove();
                            listenHosts.remove(id);
                            listenPorts.remove(id);
                        }
                    }
                    autoListenHosts.remove(host);
                    applyAllowedHosts();
                    stopReceiverIfIdle();
                    action = "stopped";
                } else {
                    action = "kept";
                    reason = "用户手动收听，不自动收回";
                }
            } else {
                action = "ignored";
                reason = "未知请求";
            }
            listenAckFor.put(host, reqId + "|" + action + "|" + reason);
            Log.i(TAG, "电脑请求 " + req + "（#" + reqId + "）→ " + action
                    + (reason.isEmpty() ? "" : "（" + reason + "）"));
        }
    }

    private String nameOrHost(String host) {
        Discovery.Peer p = peerByHost(host);
        return (p != null) ? p.label() : host;
    }

    private Discovery.Peer peerByHost(String host) {
        for (Discovery.Peer p : peers()) {
            if (p != null && host != null && host.equals(p.host)) {
                return p;
            }
        }
        return null;
    }

    /** 按节点 id 找这台设备（统计包里的 {@code node} → 发现列表里的同一台）。 */
    private Discovery.Peer peerByNode(String nodeId) {
        if (nodeId == null || nodeId.isEmpty()) {
            return null;
        }
        for (Discovery.Peer p : peers()) {
            if (p != null && nodeId.equals(p.nodeId)) {
                return p;
            }
        }
        return null;
    }

    /**
     * 把"一条消息里说的那台设备"落到发现层的地址上（2026-11-01）。
     *
     * <p>优先按 {@code node} 反查 {@code peer.host}；查不到（信标还没到 / 老对端没报 node）
     * 才退回消息来源地址。界面、收听名单、发送目标全都按 {@code peer.host} 记，
     * 所以统一到这一个键，音量条才会两端同步。
     */
    private String resolveHost(String nodeId, String fallback) {
        if (nodeId != null && !nodeId.isEmpty()) {
            Discovery.Peer p = peerByNode(nodeId);
            if (p != null && p.host != null && !p.host.isEmpty()) {
                return p.host;
            }
        }
        return fallback;
    }

    // ── 共享音量（一个增益，两端同一个值；谁后改以谁为准）──

    /** host → 我"送"它的增益（= 我→它那条链路的共享音量）。 */
    private final Map<String, Float> linkSendGain = new LinkedHashMap<String, Float>();
    /** host → 我"听"它的增益（= 它→我那条链路的共享音量）。 */
    private final Map<String, Float> linkRecvGain = new LinkedHashMap<String, Float>();
    /** 我最后一次修改"我送它的"的墙钟毫秒（发给对端用于判新旧）。 */
    private final Map<String, Long> linkSendGainTs = new LinkedHashMap<String, Long>();
    /** 我最后一次修改"我听它的"的墙钟毫秒（对端会采纳它当它的"送"）。 */
    private final Map<String, Long> linkRecvGainTs = new LinkedHashMap<String, Long>();
    /** 我最后一次修改的时刻（elapsedRealtime）。★ 两个方向必须分开，见字段注释。 */
    private final Map<String, Long> linkSendGainChangedAtMs = new LinkedHashMap<String, Long>();
    private final Map<String, Long> linkRecvGainChangedAtMs = new LinkedHashMap<String, Long>();

    /**
     * ★ 2026-11-08（用户方案）：电脑端下发的"静音档乘数策略"。
     *
     * <p>同一个号只处理一次（{@code silent_vol_policy_req} 单调递增）；电脑重启后号会从 1
     * 重新计，这时手机本地可能已经有一个别的选择 —— 用"策略不同也接受"兜底，保证电脑上的
     * 设置最终一定生效。旧电脑不发这两个键 ⇒ 这里什么都不做（手机本地选择保持不变）。
     */
    private void handleRemoteSilentPolicy(String host, Diagnosis.PeerStats ps) {
        String raw = ps.silentPolicy == null ? "" : ps.silentPolicy.trim().toLowerCase(java.util.Locale.US);
        if (!SendVolume.POLICY_INTERNAL.equals(raw) && !SendVolume.POLICY_LAST.equals(raw)) {
            return;              // 空 / 不认识：老电脑或别的包，保持本地选择
        }
        Long seenObj = silentPolicyReqSeen.get(host);
        long seen = seenObj == null ? 0L : seenObj;
        if (ps.silentPolicyReq > 0 && ps.silentPolicyReq <= seen) {
            return;              // 这个号已经处理过
        }
        silentPolicyReqSeen.put(host, ps.silentPolicyReq);
        if (!raw.equals(silentVolPolicy)) {
            setSilentVolPolicy(raw, true);
            Log.i(TAG, "电脑 " + host + " 切换静音档乘数策略 → " + raw
                    + "（" + SendVolume.policyText(raw) + "）");
        }
    }

    /**
     * 对端报来的增益：采纳"它送我的那条链路"的音量，作为我的"我听它的"。
     *
     * @param host      发现层的 peer.host（界面和发送目标用的键）
     * @param audioHost 这条统计包的来源地址（AudioRecv 按它记接收增益；通常和 host 相同，
     *                  电脑多网卡时会不同 —— 那时两边都要设，声音和滑杆才对得上）
     */
    private void handleRemoteGain(String host, Diagnosis.PeerStats ps, String audioHost) {
        if (!ps.hasGain()) {
            return;      // 老版本没有这个字段：什么都不做（混跑兼容）
        }
        float mine = linkRecvGain.containsKey(host) ? linkRecvGain.get(host) : 1f;
        Long localTsObj = linkRecvGainTs.get(host);
        long localTs = localTsObj == null ? 0L : localTsObj;
        long sinceRecvLocal = LinkPolicy.NEVER;
        Long recvAt = linkRecvGainChangedAtMs.get(host);
        if (recvAt != null) {
            sinceRecvLocal = SystemClock.elapsedRealtime() - recvAt;
        }
        long sinceSendLocal = LinkPolicy.NEVER;
        Long sendAt = linkSendGainChangedAtMs.get(host);
        if (sendAt != null) {
            sinceSendLocal = SystemClock.elapsedRealtime() - sendAt;
        }
        if (LinkPolicy.adoptRemoteGain(ps.gainTs, localTs, sinceRecvLocal, ps.gain, mine)) {
            applyRecvGain(host, ps.gain);
            if (audioHost != null && !audioHost.isEmpty() && !audioHost.equals(host)) {
                // 音频包来自另一个地址（多网卡/VPN）：接收端也要按那个键设一次，
                // 否则滑杆显示对了、真正播出来的声音还是旧音量。
                AudioRecv r = receiver;
                if (r != null) {
                    try {
                        r.setHostGain(audioHost, LinkPolicy.clampGain(ps.gain));
                    } catch (Throwable t) {
                        Log.w(TAG, "设置接收增益失败（音频来源地址）", t);
                    }
                }
            }
            linkRecvGainTs.put(host, ps.gainTs);
            linkRecvGainChangedAtMs.put(host, SystemClock.elapsedRealtime());
            Log.i(TAG, "采纳对端报的共享音量：" + host + " → 我听它的 " + ps.gain);
        }
        // ★ 对端报的"我听它的"（gain_rx）= 它→我这条链路的另一个视角 = 我的"我送它的"
        if (ps.gainRx != Diagnosis.GAIN_UNSET) {
            float mySend = linkSendGain.containsKey(host) ? linkSendGain.get(host) : 1f;
            Long mySendTsObj = linkSendGainTs.get(host);
            long mySendTs = mySendTsObj == null ? 0L : mySendTsObj;
            if (LinkPolicy.adoptRemoteGain(ps.gainRxTs, mySendTs, sinceSendLocal, ps.gainRx, mySend)) {
                applySendGain(host, ps.gainRx);
                linkSendGainTs.put(host, ps.gainRxTs);
                linkSendGainChangedAtMs.put(host, SystemClock.elapsedRealtime());
                Log.i(TAG, "采纳对端报的共享音量：" + host + " → 我送它的 " + ps.gainRx);
            }
        }
    }

    /** 真正把"我听它的"落到接收端（+记住值）。 */
    private void applyRecvGain(String host, float g) {
        float v = LinkPolicy.clampGain(g);
        linkRecvGain.put(host, v);
        AudioRecv r = receiver;
        if (r != null) {
            try {
                r.setHostGain(host, v);
            } catch (Throwable t) {
                Log.w(TAG, "设置接收增益失败", t);
            }
        }
    }

    /** 真正把"我送它的"落到发送端（+记住值）。 */
    private void applySendGain(String host, float g) {
        float v = LinkPolicy.clampGain(g);
        linkSendGain.put(host, v);
        AudioSend s = sender;
        if (s != null) {
            try {
                s.setTargetGain(host, v);
            } catch (Throwable t) {
                Log.w(TAG, "设置发送增益失败", t);
            }
        }
    }

    /** 发送端新建/目标重新上线时，把用户（或对端同步过来）的增益补回去。 */
    private void restoreGains(AudioSend s) {
        if (s == null) {
            return;
        }
        for (Map.Entry<String, Float> e : linkSendGain.entrySet()) {
            try {
                s.setTargetGain(e.getKey(), e.getValue());
            } catch (Throwable ignored) {
                // 补不上就保持默认 1.00
            }
        }
    }

    /** 接收端重建时，把每台设备的「我听它的」补回去（和 restoreGains 对称）。 */
    private void restoreRecvGains(AudioRecv r) {
        if (r == null) {
            return;
        }
        try {
            for (Map.Entry<String, Float> e : new LinkedHashMap<String, Float>(linkRecvGain).entrySet()) {
                r.setHostGain(e.getKey(), e.getValue());
            }
        } catch (Throwable t) {
            Log.w(TAG, "恢复接收增益失败（继续用默认 1.00）", t);
        }
    }

    /** 某一台自己报告的状态；没有就是 null（界面据此显示"等它回话…"）。 */
    public Diagnosis.PeerStats peerStats(String host) {
        if (host == null) {
            return null;
        }
        synchronized (peerLock) {
            Diagnosis.PeerStats direct = peerStats.get(host);
            if (direct != null) {
                return direct;
            }
        }
        // ★ 2026-11-01：统计包可能比信标先到 —— 那时只能按来源 IP 存，而界面拿的是
        //   信标里的地址。用这台设备的 node id 再对一次，免得"明明在回话却显示等它回话"。
        //   （先取 node 再进 peerLock：peerByHost 会去读发现表，别把两把锁套在一起。）
        Discovery.Peer p = peerByHost(host);
        String node = (p != null) ? p.nodeId : "";
        if (node == null || node.isEmpty()) {
            return null;
        }
        synchronized (peerLock) {
            for (Diagnosis.PeerStats ps : peerStats.values()) {
                if (node.equals(ps.node)) {
                    return ps;
                }
            }
        }
        return null;
    }

    /** 所有对端报告的统计（按最近回话排序）。返回拷贝，界面线程随便遍历。 */
    public List<Diagnosis.PeerStats> peerStatsList() {
        synchronized (peerLock) {
            return new ArrayList<Diagnosis.PeerStats>(peerStats.values());
        }
    }

    /** 本机已经发出去了多少个统计包（诊断报告里用：为 0 说明统计交换根本没跑起来）。 */
    public long statsPacketsSent() {
        return statsSent;
    }

    /** 本应用还在已安装状态吗？（APK 文件消失是比 getPackageInfo 更硬的证据）。 */
    private boolean packageStillInstalled() {
        try {
            String apk = app.getPackageCodePath();
            if (apk != null && !apk.isEmpty() && !new File(apk).exists()) {
                return false;
            }
        } catch (Throwable ignored) {
            // 取不到路径就不能据此判负，继续看包名
        }
        try {
            app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            return true;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    private void ensureStatsThread() {
        if (statsRunning) {
            return;
        }
        statsRunning = true;
        Thread t = new Thread(this::statsLoop, "am-stats-tx");
        t.setDaemon(true);
        statsThread = t;
        t.start();
    }

    private void stopStatsThread() {
        statsRunning = false;
        pokeStats();
        Thread t = statsThread;
        statsThread = null;
        if (t != null && t != Thread.currentThread()) {
            try {
                // 循环里每 100ms 看一眼开关，所以这里很快就能等到它退出，不用干等一轮 2 秒。
                t.join(600);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 本机刚动了音量 / 收发状态 → <b>立刻</b>补发一轮统计，不等那 2 秒一次的周期。
     *
     * <p>用户真机反馈（2026-10-16）：「两边的音量调同步太慢了，要反应很久」。
     * 统计包是唯一携带共享增益（{@code gain / gain_ts / gain_rx}）的消息，而它原来是固定的
     * 2 秒一轮 —— 拖完滑杆，对端最多要等 2 秒才看见新值（再叠上对端自己的处理周期）。
     * 这里只叫醒发送循环，包还是由它那一条线程发出去，界面线程不会为了一个包去等网络。
     */
    public void pokeStats() {
        statsPoke = true;
        synchronized (statsWake) {
            statsWake.notifyAll();
        }
    }

    /**
     * 统计包发送循环：每 2 秒给"和我有关系的每一台"单播一个 {@code T_STATS}。
     *
     * <p><b>为什么放在引擎这一层、而不是 AudioSend 里</b>：用户完全可能"只收听、不送声音"，
     * 那时候 AudioSend 根本不存在 —— 可恰恰是这种时候最需要告诉对方"我没在发"。
     * 否则对面看到的就是"连上了、数字在动、就是没声音"，还是只能靠猜。
     * 放在这里，统计和具体的音频收发解耦：只要和某台有收听/送声关系，就互报。
     *
     * <p><b>★ 发到哪条路（2026-10-15）</b>：对端信标里有 {@code ctl:1} → 走<b>发现层</b>
     * （{@link Discovery#sendControl}，那层的 socket 一直活着）；老版本对端 → 照旧发
     * <b>对方的音频端口</b>（信标里公布的 {@code audio_port}，由对方的 AudioRecv 收）。
     * 分流的理由见 {@link Discovery} 的类注释：控制消息挂在按需启停的音频接收器上，
     * 对端一停就永远收不到 —— 那就是"电脑送声音开着、手机的收听却不自动打开"的成因。
     */
    private void statsLoop() {
        DatagramSocket s;
        try {
            s = new DatagramSocket();
            s.setSendBufferSize(16 * 1024);
        } catch (Throwable t) {
            // 统计发不出去不影响音频，只是用户少了"对方视角"这一半信息。
            statsLastError = "统计 socket 打不开：" + t;
            Log.w(TAG, statsLastError, t);
            statsRunning = false;
            return;
        }
        try {
            while (statsRunning) {
                // ★ 2026-11-02（第二道保险）：卸载时前台服务可能已被系统停掉，
                //   但进程还活着；服务里的 installWatch 就一起没了。引擎自己的常驻线程
                //   每 2 秒再看一眼"包/ apk 还在不在"，不在了立刻 stop() 释放投屏授权。
                if (app != null && !packageStillInstalled()) {
                    Log.w(TAG, "引擎检测到本应用已被卸载，停止并释放投屏授权");
                    stop();
                    return;
                }
                try {
                    sendStatsRound(s);
                } catch (Throwable t) {
                    // 对端还没起来/刚走，send 报错很正常，绝不能因此退出循环。
                    statsLastError = "发统计失败：" + t;
                    Log.w(TAG, statsLastError);
                }
                // 拆成 100ms 小步睡：stop() 时能立刻醒来，不用等满 2 秒；
                // 谁刚动了音量（pokeStats）也能立刻醒来，不必把新值压到下一轮 2 秒。
                for (int i = 0; i < STATS_INTERVAL_MS / 100 && statsRunning && !statsPoke; i++) {
                    synchronized (statsWake) {
                        try {
                            statsWake.wait(100);
                        } catch (InterruptedException ignored) {
                            break;
                        }
                    }
                }
                statsPoke = false;
            }
        } finally {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 发一轮统计：给每台"有关系的设备"各发一个（内容按方向区分）。 */
    /** ★ 2026-11-04（A）：本机对这台设备的 listen_req 回执，拼成 JSON 片段；没有则空串。 */
    private String listenAckJson(String host) {
        String v;
        synchronized (lock) {
            v = listenAckFor.get(host);
        }
        if (v == null || v.isEmpty()) {
            return "";
        }
        String[] parts = v.split("\\|", 3);
        if (parts.length < 2) {
            return "";
        }
        String reason = parts.length >= 3 ? parts[2] : "";
        // 回执内容是我们自己写死的短词/短句（没有引号、反斜杠），可以安全内联。
        return ",\"listen_ack_id\":" + parts[0]
                + ",\"listen_ack_action\":\"" + parts[1] + "\""
                + ",\"listen_ack_reason\":\"" + reason + "\"";
    }

    private void sendStatsRound(DatagramSocket s) {
        // ★ 每轮先看一眼"对端是不是已经彻底没消息了"（关程序/离开局域网时没人来说
        //   want_listen=false，只能靠这条兜底）—— 必须在下面那个 targets.isEmpty() 提前返回
        //   之前跑，否则"什么都没在发生"的那一轮正好把该收的收听留下了。
        revokeStaleAutoListens();
        AudioSend snd;
        Map<String, Integer> targets = new LinkedHashMap<String, Integer>();
        synchronized (lock) {
            // 我收听的那些：告诉它们"我收了你多少、我有没有在发"。
            for (Map.Entry<String, String> e : listenHosts.entrySet()) {
                String host = e.getValue();
                if (host == null || host.isEmpty()) {
                    continue;
                }
                Integer port = listenPorts.get(e.getKey());
                targets.put(host, (port != null && port > 0) ? port : Proto.DEFAULT_AUDIO_PORT);
            }
            // ★ 我正在送声音的每一台：逐台告诉它"我发给你多少、我又收了你多少"。
            //   （原来是单目标，只报一台；现在广播/多目标时每台都要收到自己那一份回话。）
            snd = sender;
            if (snd != null) {
                for (SendFanout.TargetStats ts : snd.targetStats()) {
                    if (ts.host != null && !ts.host.isEmpty()) {
                        targets.put(ts.host, ts.port > 0 ? ts.port : Proto.DEFAULT_AUDIO_PORT);
                    }
                }
            }
            // ★ ④ 只是"请电脑去连我的蓝牙"的那些，也必须进名单。
            //   2026-10-15 用户真机：手机点了「走蓝牙（电脑去连）」，电脑毫无反应 ——
            //   因为这种时候我既没在收听、也没在发送，targets 是空的，下面那句
            //   `if (targets.isEmpty() && !channelListen) return;` 直接把整轮跳过，
            //   want_bt **一个包都发不出去**（等于死代码）。
            //   记在 btWantPorts 里的就是"我还要请它连蓝牙"的设备，直到用户改选 WiFi。
            targets.putAll(btWantPorts);
            // ★ ④ 「我刚停了」也要送达：停掉的那一台再报 30 秒（want_listen 已经是 false），
            //   否则电脑那边永远收不到"你停了"，它替自动打开的那条收听就一直开着。
            long nowMs = SystemClock.elapsedRealtime();
            for (String host : lastStatsHosts) {
                if (!targets.containsKey(host) && !statsGracePorts.containsKey(host)) {
                    statsGracePorts.put(host, portForHost(host));
                    statsGraceUntil.put(host, nowMs + STATS_STOP_GRACE_MS);
                }
            }
            java.util.Iterator<Map.Entry<String, Integer>> it =
                    statsGracePorts.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Integer> e = it.next();
                Long until = statsGraceUntil.get(e.getKey());
                if (until == null || until <= nowMs) {
                    it.remove();
                    statsGraceUntil.remove(e.getKey());
                    continue;
                }
                targets.put(e.getKey(), e.getValue());
            }
            lastStatsHosts.clear();
            lastStatsHosts.addAll(targets.keySet());
        }
        // ★ 听广播时，把"我正在收的每一路"也告诉对方：对面才知道它广播的东西到没到我这儿。
        //   （否则用户只能看到"手机没声音"，而电脑那边毫无反馈。）
        if (channelListen) {
            AudioRecv.Stats st0 = recvStats();
            if (st0 != null && st0.streams != null) {
                for (AudioRecv.StreamStats ss : st0.streams) {
                    if (ss.host == null || ss.host.isEmpty() || targets.containsKey(ss.host)) {
                        continue;
                    }
                    targets.put(ss.host, portForHost(ss.host));
                }
            }
        }
        // ★ 2026-11-12：把局域网里**每一台声明了 outctl 的手机**都算进统计目标。
        //   为什么不能只报给"有收听/送声关系"的那几台：用户会在 A 上改 C 的声道，
        //   而 A 和 C 之间可能压根没有音频关系 —— 那时候 C 的按钮状态就没地方来。
        //   代价是每台手机每 2 秒多发一小包（约 300 字节），家里两三台可以忽略。
        for (Discovery.Peer p : peers()) {
            if (p != null && p.outCtl && p.host != null && !p.host.isEmpty()
                    && !targets.containsKey(p.host)) {
                targets.put(p.host, portForHost(p.host));
            }
        }
        if (targets.isEmpty() && !channelListen) {
            expirePeerStats();
            return;
        }
        // ★ 每台各自算"我有没有在发给你、发了多少"：广播时每台都是"在发"，各报各的包数。
        boolean sendingNow = snd != null && snd.isRunning();
        Map<String, SendFanout.TargetStats> perTarget = new LinkedHashMap<String, SendFanout.TargetStats>();
        if (snd != null) {
            for (SendFanout.TargetStats ts : snd.targetStats()) {
                perTarget.put(ts.host, ts);
            }
        }
        AudioRecv.Stats st = recvStats();
        int streams = (st == null) ? 0 : st.streamCount;

        for (Map.Entry<String, Integer> t : targets.entrySet()) {
            String host = t.getKey();
            if (host == null || host.isEmpty()) {
                continue;
            }
            int port = (t.getValue() == null || t.getValue() <= 0)
                    ? Proto.DEFAULT_AUDIO_PORT : t.getValue();
            // 只对"我正在送的那些"报已发包数；对别的设备要如实说"我没在发给你"，
            // 否则对面会以为那些包是发给它的（负载里的 target 字段就是干这个的）。
            SendFanout.TargetStats ts = perTarget.get(host);
            boolean iSendToIt = sendingNow && ts != null;
            long mySent = (ts == null) ? 0 : ts.packetsSent;
            long myBytes = (ts == null) ? 0 : ts.bytesSent;
            // ★ 2026-10-16 用户澄清的语义：广播是**一条独立线路** ——
            //   "我在广播"这件事不再被写成"我在针对性送给你、请你自动打开收听"。
            //   所以 want_listen 只对**用户单独勾过的那几台**为真（点对点路由）；
            //   广播覆盖的那些照旧如实报 sending/sent（对端能看到有声音在来），
            //   但绝不替对端把收听打开 —— 要不要听由**对端自己那台**的「听」决定
            //   （用户原话：「广播是设备只有开关本机"听""送"的权利的」）。
            Discovery.Peer dp = peerByHost(host);
            boolean targeted = dp != null && sendIds.contains(idOf(dp));
            // 这条链路的收包数单独取：对方最想知道的就是"我发出去的它到底收到多少"。
            long recv = 0;
            long lost = 0;
            int buf = 0;
            long rxBytes = -1;      // ★ 2026-11-17 晚：我从这台收到的字节数（-1 = 这条链路还没流过）
            if (st != null && st.streams != null) {
                for (AudioRecv.StreamStats ss : st.streams) {
                    if (host.equals(ss.host)) {
                        recv = ss.received;
                        lost = ss.lost;
                        buf = ss.bufferedMs;
                        rxBytes = ss.bytes;
                        break;
                    }
                }
            }
            Discovery dForId = discovery;
            String myNodeId = (dForId != null) ? dForId.nodeId() : "";
            byte[] rawPayload = Diagnosis.json(myNodeId, nodeName(),
                    iSendToIt ? mySent : 0, iSendToIt ? myBytes : 0, iSendToIt,
                    recv, lost, buf, streams, host,
                    // ★ ① 请求对端自动收听：**只有用户单独点了「送声音」的那几台**才请求
                    //   （广播这条独立线路不请求 —— 见上面 targeted 的说明）
                    iSendToIt && targeted,
                    // ★ ① 我→它这条链路的共享音量（我这边的"我送它的"）
                    gainOf(linkSendGain, host), tsOf(linkSendGainTs, host),
                    // ★ ① 它→我这条链路的共享音量（我这边的"我听它的"）
                    gainOf(linkRecvGain, host), tsOf(linkRecvGainTs, host),
                    // ★ ④ 我在这台上选了「走蓝牙」→ 请**电脑**去连我的蓝牙（幂等）
                    btWantTo(host),
                    // ★ 2026-11-03 Q3：我的媒体/App 音量 → 电脑端按这条路由衰减
                    mediaVolumeRatio(),
                    // ★ 2026-11-04（Q3）：显式声明档位（0/1/2）与"这是广播线路吗"。
                    //   电脑端准入只认这两个键，不再从 want_listen 推断。
                    sendMode(), broadcastOn,
                    // ★ 2026-11-08：音量乘数由档位决定 —— 上报档位/策略，电脑端据此恒不叠乘。
                    volumeSource(), volumeSource(), silentVolPolicy());
            // ★ 2026-11-04（A）：把对"请开始/停止接收"的回执拼进统计 JSON。
            // ★ 2026-11-06（第 2 条取证）：同时把采集层的增益乘数/前后电平拼进去。
            //   两者都只含数字和固定 ASCII，不含引号，老电脑忽略多余键。
            byte[] payload = rawPayload;
            AudioSend diagSender = sender;
            String extra = listenAckJson(host) + (diagSender != null ? diagSender.diagJson() : "")
                    // ★ 2026-11-12：我这台的本机播放状态（声道模式 + 音量）。别的手机就靠这两个键
                    //   把自己的卡片画对 —— 远程控制之后"按钮立刻变"也是它带来的。
                    // ★ 2026-11-13：把本机输出延迟也报出去 —— 别的手机「它的延迟」滑杆靠它对齐真相。
                    + Diagnosis.outStateJson(channelMode, Math.round(outVolume * 100f), outDelayMs,
                            st != null ? st.bufferedMs : 0, nackEnabled ? 1 : 0, recvBufMs)
                    // ★ 2026-11-17 晚（用户要求）：把这个方向「收到多少字节」也报出去 ——
                    //   接收端在监控页要看"接收多少 M"。老版本没有这个键，忽略即可。
                    + (rxBytes >= 0 ? ",\"rx_bytes\":" + rxBytes : "");
            if (!extra.isEmpty()) {
                String text = new String(rawPayload, StandardCharsets.UTF_8);
                payload = (text.substring(0, text.length() - 1) + extra + "}")
                        .getBytes(StandardCharsets.UTF_8);
            }
            // ★ 2026-10-15：对端信标里有 ctl:1 → 这条统计/请求改发**发现端口**。
            //   那一层每 2 秒一发一收、一直活着，所以对端（手机）即使没在收听、接收器停着，
            //   也照样收得到"我在把声音送给你，请自动打开收听" —— 这就是 ⑤ 的正解。
            //   老版本对端没有这个键：落到下面照旧发音频端口，一个字都不少（新旧混跑不打哑）。
            //   （dp 就是上面为了算 targeted 已经查过的那一台，别查第二遍。）
            if (dp != null && dp.ctl && discovery != null) {
                int dport = (dp.discPort > 0) ? dp.discPort : Proto.DEFAULT_DISCOVERY_PORT;
                try {
                    if (discovery.sendControl(new String(payload, StandardCharsets.UTF_8),
                            host, dport)) {
                        statsSent++;
                        continue;
                    }
                } catch (Throwable e) {
                    // 发不出去（对端刚走 / Wi-Fi 抖动）：绝不能静默丢掉 —— 落到音频端口再发一份。
                    statsLastError = "控制消息发不到 " + host + ":" + dport + "：" + e;
                    Log.w(TAG, statsLastError);
                }
            }
            Proto.Pkt pkt = new Proto.Pkt(Proto.T_STATS);
            pkt.ssrc = statsSsrc;                                  // 固定一个，对端日志里好认
            pkt.tsNs = System.currentTimeMillis() * 1_000_000L;
            pkt.payload = payload;
            byte[] raw = Proto.encode(pkt);
            try {
                s.send(new DatagramPacket(raw, raw.length, InetAddress.getByName(host), port));
                statsSent++;
            } catch (Throwable e) {
                statsLastError = "统计包发不到 " + host + ":" + port + "：" + e;
                Log.w(TAG, statsLastError);
            }
        }
        expirePeerStats();
    }

    /**
     * ★ 把"对端已经彻底没消息"的那些【自动打开的收听】收回来（见 AUTO_LISTEN_STALE_MS）。
     *
     * <p>跑在统计线程上（每 2 秒一轮）—— 必须是常驻线程：界面关掉时也要收，
     * 否则用户回到界面看到的就是一条挂着的收听。
     */
    private void revokeStaleAutoListens() {
        java.util.List<String> hosts;
        synchronized (lock) {
            if (autoListenHosts.isEmpty()) {
                return;
            }
            hosts = new ArrayList<String>(autoListenHosts);
        }
        // 先只读快照对端统计（peerLock），再去动收听名单（lock）—— 两把锁不嵌套，避免出现
        // "收包线程等界面线程、界面线程等收包线程"那种谁都不动的局面。
        long now = SystemClock.elapsedRealtime();
        java.util.List<String> stale = new ArrayList<String>();
        // ★ 2026-11-04（A3）：双判据，和电脑端逐字对齐 ——「没声音」不挂断，
        //   音频包静默 **且** 对端统计（发现层心跳）也消失，两个都超过 4 秒才收回。
        //   静音音频仍然有包 ⇒ 音频 age 很小 ⇒ 永远不会被误挂。
        java.util.Map<String, Long> audioAge = new java.util.HashMap<String, Long>();
        AudioRecv r = receiver;
        AudioRecv.Stats rst = (r == null) ? null : r.stats();
        if (rst != null && rst.streams != null) {
            for (AudioRecv.StreamStats ss : rst.streams) {
                if (ss == null || ss.host == null) {
                    continue;
                }
                Long old = audioAge.get(ss.host);
                if (old == null || ss.ageMs < old) {
                    audioAge.put(ss.host, ss.ageMs);
                }
            }
        }
        synchronized (peerLock) {
            for (String host : hosts) {
                Diagnosis.PeerStats ps = peerStats.get(host);
                long age = (ps == null) ? Long.MAX_VALUE : (now - ps.receivedAtMs);
                Long a = audioAge.get(host);
                long audio = (a == null) ? Long.MAX_VALUE : a;
                if (age > AUTO_LISTEN_HANG_MS && audio > AUTO_LISTEN_HANG_MS) {
                    stale.add(host);
                    Log.i(TAG, "自动收听收回判据：心跳 " + age + "ms / 音频静默 " + audio + "ms");
                }
            }
        }
        if (stale.isEmpty()) {
            return;
        }
        synchronized (lock) {
            for (String host : stale) {
                if (!autoListenHosts.remove(host)) {
                    continue;
                }
                java.util.Iterator<String> ids = listenIds.iterator();
                while (ids.hasNext()) {
                    String id = ids.next();
                    if (host.equals(listenHosts.get(id))) {
                        ids.remove();
                        listenHosts.remove(id);
                        listenPorts.remove(id);
                    }
                }
                Log.i(TAG, "对端已经没消息了，已收回自动打开的收听：" + nameOrHost(host));
            }
            applyAllowedHosts();
            stopReceiverIfIdle();
        }
    }

    /** 过期的对端统计定期清掉：界面会显示"没在回话"，没必要一直占着内存。 */
    private void expirePeerStats() {
        long now = SystemClock.elapsedRealtime();
        synchronized (peerLock) {
            java.util.Iterator<Map.Entry<String, Diagnosis.PeerStats>> it =
                    peerStats.entrySet().iterator();
            while (it.hasNext()) {
                Diagnosis.PeerStats ps = it.next().getValue();
                if (ps == null || now - ps.receivedAtMs > PEER_STATS_KEEP_MS) {
                    it.remove();
                }
            }
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ────────────────────────────── 电平 ──────────────────────────────

    /** 送声音的实时电平 0~1（AudioSend 已经归一化过）。 */
    public float sendPeak() {
        AudioSend s = sender;
        if (s == null) {
            return 0f;
        }
        try {
            return clamp01(s.peak());
        } catch (Throwable t) {
            return 0f;
        }
    }

    /** 收听的混音总电平 0~1。 */
    public float recvPeak() {
        AudioRecv r = receiver;
        if (r == null) {
            return 0f;
        }
        try {
            return clamp01(r.peak());
        } catch (Throwable t) {
            return 0f;
        }
    }

    /** 某一台正在发来的声音的电平 0~1（这一行自己的电平条）。 */
    public float recvPeakFor(String host) {
        if (host == null || host.isEmpty()) {
            return 0f;
        }
        AudioRecv.Stats st = recvStats();
        if (st == null || st.streams == null) {
            return 0f;
        }
        for (AudioRecv.StreamStats ss : st.streams) {
            if (host.equals(ss.host)) {
                return clamp01(ss.peak);
            }
        }
        return 0f;   // 这台还没在发（点了收听但对方没点送声音）
    }

    /**
     * 这一路的「信号质量」0~100；没有可测的链路返回 {@code -1}（界面据此写"不可测"）。
     *
     * <p>★ 2026-10-16 用户（图6）：「电脑手机现在都只有"送""收"两个条，现在定义为音量
     * 显示条。并添加一个信号质量条。」—— 分值只用<b>实测计数器</b>算：丢包比例越小分越高，
     * 缓冲越深（说明这一路一直在补迟到/重排的包）再扣一点。
     *
     * <p>为什么没有依据时返回 -1 而不是 0：0 分看起来像"链路很差"，而"根本没测到"和
     * "链路很差"是两件事。界面按 -1 写"不可测"，绝不编一个分数。
     */
    public int peerQualityFor(String host) {
        if (host == null || host.isEmpty()) {
            return -1;
        }
        AudioRecv.Stats st = recvStats();
        // ★ 2026-11-02（2.6）：接收器没跑（没点「收听」/「听广播」）时 recvStats() 返回 null，
        //   旧代码在这里直接 return -1 → 卡片永远写「还没收到它的心跳」，
        //   哪怕对方的信标每 2 秒都在、设备在线。这就是用户说的"反馈不真实"。
        //   现在：没有音频统计就往下走心跳那条兜底（信标新鲜 = 链路正常）。
        if (st != null && st.streams != null) {
            for (AudioRecv.StreamStats ss : st.streams) {
                if (!host.equals(ss.host)) {
                    continue;
                }
                // 算式放在 LinkPolicy 里：那样 PolicyProbe 那道零桩门禁能直接把它测了，
                // 不用起 Android 运行时（这条判据是纯计算，没有理由藏在 Engine 里）。
                return LinkPolicy.qualityScore(ss.received, ss.lost, ss.bufferedMs);
            }
        }
        // ★ 2026-10-16 用户（图1）：「虽然二者没有收发声音，但是设备间信号应当存在」——
        //   没有音频流的时候**不能写"没有依据"**：链路本身一直在（互相看得见信标）。
        //   手机端不回 PING/PONG，所以这条路是唯一的兜底 —— 用心跳新鲜度给分。
        Discovery.Peer peer = peerByHost(host);
        if (peer != null && peer.online()) {
            return LinkPolicy.heartbeatScore(peer.ageMs());
        }
        return -1;
    }

    private static float clamp01(float v) {
        if (Float.isNaN(v) || v <= 0f) {
            return 0f;
        }
        return v > 1f ? 1f : v;
    }

    /**
     * 接收统计快照。120ms 内复用同一个对象：界面每 150ms 刷一次、每行都要读电平，
     * 不缓存的话一秒会新建几十个 Stats，没必要。
     */
    public AudioRecv.Stats recvStats() {
        AudioRecv r = receiver;
        if (r == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        AudioRecv.Stats st = statsCache;
        if (st != null && now - statsCacheAt < STATS_TTL_MS) {
            return st;
        }
        try {
            st = r.stats();
            statsCache = st;
            statsCacheAt = now;
            return st;
        } catch (Throwable t) {
            return statsCache;
        }
    }

    // ────────────────────────────── 统计 / 本机信息 ──────────────────────────────

    /**
     * 只看一眼当前实例，<b>不创建</b>。
     *
     * <p>给 {@link Diagnosis#build(android.content.Context)} 用：生成一份诊断报告
     * 不该顺手把引擎建起来（那会真的去开组播端口、拿 WifiLock）。没有实例就返回 null，
     * 报告里照实写"引擎没在运行"。
     */
    public static Engine peek() {
        return sInstance;
    }

    /** 接收器在跑吗（诊断报告和界面都要用）。 */
    public boolean receiverRunning() {
        AudioRecv r = receiver;
        return r != null && r.isRunning();
    }

    /** 发送器在跑吗。注意 start() 之后麦克风还有可能打开失败，所以这里问的是它自己的状态。 */
    public boolean senderRunning() {
        AudioSend s = sender;
        return s != null && s.isRunning();
    }

    public boolean discoveryRunning() {
        Discovery d = discovery;
        return d != null && d.isRunning();
    }

    /** 正在收听哪几台的地址（给诊断报告用的人话）。 */
    public String listenTargetsText() {
        synchronized (lock) {
            StringBuilder sb = new StringBuilder();
            for (String h : listenHosts.values()) {
                if (h == null || h.isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(" / ");
                }
                sb.append(h);
            }
            return sb.length() == 0 ? "（无）" : sb.toString();
        }
    }

    /** 正在送给谁（可能多台；没在送就是"（无）"）。 */
    public String sendTargetText() {
        AudioSend s = sender;
        if (s == null || s.targetCount() == 0) {
            return "（无）";
        }
        int n = s.targetCount();
        String list = s.targetCount() <= 4 ? s.targetText() : (n + " 台");
        return n + " 台 · " + list + (s.isRunning() ? "" : "（已停止）");
    }

    // ────────────────────────── ★ 本机名称（可编辑） ──────────────────────────

    /**
     * 改本机名称。
     *
     * <p>用户原话：「本机id最好支持自己编辑，不排除多人用同型号设备」。
     * 注意分工：<b>用户编辑的是"名字"，节点 ID 是身份、永远保持持久唯一</b>（不能让人改成重复的，
     * 否则电脑端的路由表就指向不清了）。改完立刻生效：下一个信标（≤2 秒）带出去，
     * 别人列表里马上变。
     */
    public void setLocalName(String name) {
        Discovery d = discovery;
        if (d == null) {
            return;
        }
        d.setName(name);
    }

    /** 本机名称（信标里对外用的那个）。 */
    public String localName() {
        return nodeName();
    }

    public boolean localNameIsCustom() {
        Discovery d = discovery;
        return d != null && d.customName();
    }

    public long sentPackets() {
        AudioSend s = sender;
        return s == null ? 0 : s.packetsSent();
    }

    public long sentBytes() {
        AudioSend s = sender;
        return s == null ? 0 : s.bytesSent();
    }

    public int sendErrors() {
        AudioSend s = sender;
        return s == null ? 0 : s.sendErrors();
    }

    /** 发送侧最近一次错误（没有发送器时退回"启动失败"的那条）。 */
    public String sendLastError() {
        AudioSend s = sender;
        String e = (s == null) ? lastSendError : s.lastError();
        return e == null ? "" : e;
    }

    public String recvLastError() {
        AudioRecv r = receiver;
        String e = (r == null) ? "" : r.lastError();
        return e == null ? "" : e;
    }

    public String discLastError() {
        Discovery d = discovery;
        String e = (d == null) ? "" : d.lastError();
        return e == null ? "" : e;
    }

    /** 详情面板里的「丢包统计」。 */
    public String statsText() {
        if (receiver == null) {
            return "未在收听（点某台的「收听」开始）";
        }
        AudioRecv.Stats st = recvStats();
        if (st == null) {
            return "暂无统计";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("收 ").append(st.received).append(" 包 · 丢 ").append(st.lost)
                .append(" · 迟到 ").append(st.late).append(" · 坏包 ").append(st.badPackets);
        // ★ 坏包分类：详情面板也要能一眼看出"坏在哪"（真机排查就卡在一个总数上）
        if (st.badPackets > 0) {
            sb.append("\n坏包明细 magic ").append(st.badMagic)
                    .append(" · 版本 ").append(st.badVersion)
                    .append(" · 太短 ").append(st.badShort)
                    .append(" · 编码 ").append(st.badCodec)
                    .append(" · 帧长 ").append(st.badFrames);
            String sample = (st.lastCodecSample != null && st.lastCodecSample.length() > 0)
                    ? st.lastCodecSample : st.lastMagicSample;
            if (sample != null && sample.length() > 0) {
                sb.append("\n").append(sample);
            }
        }
        sb.append("\n缓冲 ").append(st.bufferedMs).append("ms（目标 ").append(st.targetMs)
                .append("ms）· 流 ").append(st.streamCount);
        if (st.duplicates > 0 || st.filtered > 0) {
            sb.append("\n重复 ").append(st.duplicates).append(" · 按名单过滤 ").append(st.filtered);
        }
        if (st.trackErrors > 0) {
            sb.append("\n音频轨错误 ").append(st.trackErrors);
        }
        AudioRecv r = receiver;
        if (r != null) {
            String err = r.lastError();
            if (err != null && err.length() > 0) {
                sb.append("\n最近：").append(err);
            }
            // 每一路单独一行：谁在发、发了多少、丢了多少，一眼能对上号。
            if (st.streams != null) {
                for (AudioRecv.StreamStats ss : st.streams) {
                    sb.append("\n· ").append(ss.host)
                            .append(" 收 ").append(ss.received)
                            .append(" 丢 ").append(ss.lost)
                            .append(" 缓冲 ").append(ss.bufferedMs).append("ms");
                }
            }
        }
        return sb.toString();
    }

    /** 详情面板里的「发送统计」。 */
    public String sendStatsText() {
        AudioSend s = sender;
        if (s == null) {
            return lastSendError.length() > 0 ? ("未在送声音\n" + lastSendError) : "未在送声音";
        }
        try {
            double mb = s.bytesSent() / 1048576.0;
            StringBuilder sb = new StringBuilder();
            // ★ 多目标：逐台列出"发了多少包 / 多少字节 / 失败几次 / 最近一次成功多久前"，
            //   用户才能看出"是哪一台没在收"（单目标时代只有一个数字，广播时根本不够用）。
            sb.append("目标 ").append(s.targetCount()).append(" 台");
            if (broadcastOn) {
                sb.append("（📢 广播中）");
            }
            sb.append("\n合计已发 ").append(s.packetsSent()).append(" 包 / ")
                    .append(String.format(java.util.Locale.US, "%.1f", mb)).append(" MB");
            if (s.sendErrors() > 0) {
                sb.append(" · 失败 ").append(s.sendErrors()).append(" 次");
            }
            for (SendFanout.TargetStats ts : s.targetStats()) {
                sb.append("\n· ").append(ts.host).append(':').append(ts.port)
                        .append(" 发 ").append(ts.packetsSent).append(" 包");
                if (ts.sendErrors > 0) {
                    sb.append(" 失败 ").append(ts.sendErrors).append(" 次");
                }
                if (ts.lastOkAgeMs < 0) {
                    sb.append("（还没成功发出过）");
                } else if (ts.lastOkAgeMs > 2000) {
                    sb.append("（最近一次成功 ").append(ts.lastOkAgeMs / 1000).append(" 秒前）");
                }
            }
            // ★ 音源写在详情里：用户看"送声音"到底送的是什么，这里是最直接的答案。
            int act = s.activeSourceKind();
            sb.append("\n音源 ").append(act < 0 ? "（还没打开）" : AudioSend.sourceName(act));
            if (act >= 0 && act != sourceKind) {
                sb.append("（你要的是 ").append(AudioSend.sourceName(sourceKind)).append("）");
            }
            sb.append(" · ").append(s.captureFormatText());
            if (s.targetCount() > 1) {
                sb.append("\n带宽 ").append(bandwidthText());
            }
            String hint = silenceHintText();
            if (hint.length() > 0) {
                sb.append('\n').append(hint);
            }
            String last = s.lastError();
            if (last != null && last.length() > 0) {
                sb.append("\n最近：").append(last);
            }
            return sb.toString();
        } catch (Throwable t) {
            return "发送统计不可用";
        }
    }

    /** 界面「听广播」那一行的人话（带"收到几个包"这种能直接定性的数字）。 */
    public String channelStateText() {
        if (!channelListen) {
            return "没开：只有你点过「🎧 收听」的设备才会出声。";
        }
        AudioRecv.Stats st = recvStats();
        long recv = st == null ? 0 : st.received;
        int streams = st == null ? 0 : st.streamCount;
        StringBuilder sb = new StringBuilder(200);
        sb.append("正在听广播：谁往本机音频端口发声音就播谁（不用先点某台设备）。");
        if (!receiverRunning()) {
            sb.append("⚠ 接收器没起来（音频端口可能被别的 App 占了）。");
        } else if (recv == 0) {
            sb.append("已经收到 0 个音频包 —— 对面要开着「广播我的声音」，")
                    .append("而且它得先能看见本机（点「详情」看发现自检那几行）。");
        } else if (streams == 0) {
            sb.append("累计收到 ").append(recv).append(" 包，但当前没有活跃的流（对面可能刚停）。");
        } else {
            sb.append(recvStreamsText()).append("。");
            if (st != null && st.dupRoutePackets > 0) {
                sb.append("（另有同一台设备的重复流已被抑制，不会叠加播放）");
            }
        }
        return sb.toString();
    }

    /** 详情面板里的「发现统计」（含 ★ 发现层自检：列表空着时到底卡在哪一步）。 */
    public String discoveryStatsText() {
        Discovery d = discovery;
        if (d == null) {
            return "发现未启动";
        }
        try {
            StringBuilder sb = new StringBuilder(768);
            sb.append("发现端口 ").append(d.boundPort())
                    .append(" · 组播 ").append(Proto.DISCOVERY_GROUP).append(" · TTL 2\n");
            sb.append(d.selfCheckText().trim());
            String err = d.lastError();
            if (err != null && err.length() > 0) {
                sb.append("\n最近：").append(err);
            }
            return sb.toString();
        } catch (Throwable t) {
            return "发现统计不可用";
        }
    }

    /** 某台设备的音频端口（从发现列表里查；查不到就用默认端口）。 */
    private int portForHost(String host) {
        if (host == null || host.isEmpty()) {
            return Proto.DEFAULT_AUDIO_PORT;
        }
        try {
            for (Discovery.Peer p : peers()) {
                if (host.equals(p.host) && p.audioPort > 0) {
                    return p.audioPort;
                }
            }
        } catch (Throwable ignored) {
            // 查不到就用默认端口
        }
        return Proto.DEFAULT_AUDIO_PORT;
    }

    /** 本机 IP：和电脑端 netcheck 同一套判定 —— 只要私有地址，避开 VPN 叠加网段。 */
    public String localIp() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            String best = null;
            int bestScore = Integer.MIN_VALUE;
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (ni == null || !ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                String nic = ni.getName() == null ? "" : ni.getName().toLowerCase();
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia == null) {
                        continue;
                    }
                    InetAddress a = ia.getAddress();
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) {
                        continue;
                    }
                    String ip = a.getHostAddress();
                    int score = scoreIp(ip, nic);
                    if (score > bestScore) {
                        bestScore = score;
                        best = ip;
                    }
                }
            }
            if (best != null) {
                return best;
            }
        } catch (Throwable t) {
            Log.w(TAG, "读取本机 IP 失败", t);
        }
        return "—";
    }

    /** 本机所有局域网地址（主地址在前），Discovery 那边扫到的也一起列出来。 */
    public String localIpsText() {        String best = localIp();
        StringBuilder sb = new StringBuilder(best);
        Discovery d = discovery;
        if (d != null) {
            try {
                for (String ip : d.localIps()) {
                    if (ip != null && !ip.equals(best)) {
                        sb.append(" / ").append(ip);
                    }
                }
            } catch (Throwable ignored) {
                // 拿不到就只显示主地址
            }
        }
        return sb.toString();
    }

    // ────────────────────── ★ 音量：只动我们这一层 ──────────────────────

    /**
     * 「我们这一层的音量」当前值（发送侧 / 播放侧各是多少）。
     *
     * <p><b>为什么要有这个方法</b>（2026-10-12 真机反馈，用户原话）：
     * 「手机在调节本机音量时（本意应当是使收听方音量更适合），会直接调节系统声音大小。
     * 我们的声音计算方式应当是：系统声音 × 被监听软件声音 × 我们软件声音 = 实际接收端声音大小」。
     * 所以滑杆从此只作用于<b>我们自己处理的 PCM</b>（发侧采样后乘增益 / 收侧 AudioTrack 音量），
     * <b>绝不碰系统媒体音量</b>。这一行就是"我们这一层"的如实值。
     */
    public String volumeText() {
        AudioSend s = sender;
        AudioRecv r = receiver;
        float send = (s != null) ? s.gain() : volume;
        float recv = (r != null) ? r.gain() : Math.min(1f, Math.max(0f, volume));
        return String.format(java.util.Locale.US,
                "发送 %.2f / 播放 %.2f（滑杆 %.2f）", send, recv, volume);
    }

    /**
     * 系统媒体音量：<b>只显示，永远不修改</b>。
     *
     * <p>用户要的分层是：系统音量 × 播放器自己的音量 × 我们这一层 = 实际听到的大小。
     * 我们只负责最后一层 —— 把系统音量读出来写在界面上，是让用户知道"这一层你得自己调"，
     * 而不是让我们替他调。
     */
    public String systemVolumeText() {
        if (app == null) {
            return "读不到（没有 Context）";
        }
        try {
            android.media.AudioManager am =
                    (android.media.AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                return "读不到（没有音频服务）";
            }
            int cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
            int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            return cur + " / " + max + "（只显示，我们不修改它）";
        } catch (Throwable t) {
            return "读不到：" + t;
        }
    }

    /**
     * ★ 2026-11-03 Q3 / v2.28（用户第 2 条）：当前系统媒体音量归一化到 0..1。
     *
     * <p>★ v2.28 为什么要改成"手机自己乘"：v2.27 假设 {@code AudioPlaybackCapture}
     * 采到的 PCM 已经带上了系统/App 音量，于是让电脑端不要乘（{@code vol_in_capture}）。
     * 真机结果是**音量旋钮完全不影响耳机**——说明采集点在系统音量之前（录屏语义：
     * 录屏不该受扬声器音量影响）。App 自己的音量（{@code AudioTrack.setVolume}）在混音前
     * 就写进轨道了，所以它在 PCM 里；缺的是**系统媒体音量**这一级。
     * 现在由 {@link AudioSend} 在发送前一帧乘上本函数的值，电脑端仍然不乘（单级衰减）。
     *
     * <p>只读，仍然不调用 {@code setStreamVolume} —— 手机自己的音量面板/按键行为不变。
     *
     * <p>用 {@code getStreamMinVolume} 做区间归一化（有些 ROM 的 min 不是 0）。
     * 读不到（极少数 ROM 抛异常）时返回 1.0：**绝不能因为读音量失败而把对端变成静音**。
     */
    public float mediaVolumeRatio() {
        return mediaVolumeRatioFor(app);
    }

    /**
     * 静态版：给发送线程（AudioSend）直接读。
     *
     * <p>第 2 档（"手机不出声、只送声音"）会把系统媒体音量压到 0；那时如果还读当前值，
     * 送出去的 PCM 也会被乘成 0 —— 用户听到的就是"开了第 2 档反而没声音"。所以
     * 静音标记存在时，返回**用户最后一次设定的那一档**；这个值由
     * {@link #applySilentVolume} 在每次归零前按"用户/App 刚调过的值"实时更新，
     * 不再固定为进入静音那一刻的旧值（v2.29 的 0.11 一直不动就是它造成的）。
     */
    public static float mediaVolumeRatioFor(Context ctx) {
        if (ctx == null) {
            return 1f;
        }
        try {
            android.media.AudioManager am =
                    (android.media.AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                return 1f;
            }
            int cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
            android.content.SharedPreferences sp =
                    ctx.getSharedPreferences("audiomixing", Context.MODE_PRIVATE);
            if (sp.getBoolean(PREF_SILENT_ACTIVE, false)) {
                int orig = sp.getInt(PREF_SILENT_ORIG, -1);
                if (orig >= 0) {
                    cur = orig;
                }
            }
            int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            int min = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    min = am.getStreamMinVolume(android.media.AudioManager.STREAM_MUSIC);
                } catch (Throwable ignored) {
                    min = 0;
                }
            }
            int span = max - min;
            if (span <= 1) {
                // 真的只有"最小/最大"两档的 ROM：如实报 0/1，让这一级仍然能静音/满音量。
                return cur <= min ? 0f : 1f;
            }
            return Math.max(0f, Math.min(1f, (cur - min) / (float) span));
        } catch (Throwable t) {
            Log.w(TAG, "读媒体音量失败（按 1.0 处理）：" + t);
            return 1f;
        }
    }

    /**
     * ★ 监控档的实时数字面板（一屏读完）。
     *
     * <p>为什么放在 Engine 而不是界面里拼：这四段用的全是本类已有的查询接口
     * （发现层、收端、发端、音量），界面只负责把它画出来 —— 这样"同一份数据三种呈现"
     * 才成立，不会出现"监控档的数字和诊断报告对不上"这种事。
     */
    public String monitorText() {
        StringBuilder sb = new StringBuilder(1024);

        // ── 发现 ──
        sb.append("── 发现 ──\n");
        Discovery d = discovery;
        if (d == null || !d.isRunning()) {
            sb.append("发现没在跑 ✗\n");
        } else {
            sb.append("信标 收 ").append(d.beaconsReceived())
                    .append(" · 发 ").append(d.beaconsSent())
                    .append(" · 自己绕回 ").append(d.beaconsSelfReceived()).append('\n');
            sb.append("组播 ").append(d.multicastIfaces().isEmpty() ? "没加进去 ✗" : d.multicastIfaces())
                    .append(" · 组播锁 ").append(d.multicastLockHeld() ? "✓" : "✗")
                    .append(" · 端口 ").append(d.boundPort()).append('\n');
            sb.append("最近 ").append(d.lastBeaconText()).append('\n');
        }

        // ── 接收 ──
        sb.append("── 接收 ──\n");
        AudioRecv.Stats st = recvStats();
        if (st == null || !receiverRunning()) {
            sb.append("接收器没跑（没开听广播、也没点过收听）\n");
        } else {
            sb.append("收 ").append(st.received).append(" · 丢 ").append(st.lost)
                    .append(" · 迟到 ").append(st.late).append(" · 重复 ").append(st.duplicates)
                    .append(" · 坏包 ").append(st.badPackets).append('\n');
            sb.append("坏包分类 magic ").append(st.badMagic).append(" / 版本 ").append(st.badVersion)
                    .append(" / 太短 ").append(st.badShort).append(" / 编码 ").append(st.badCodec)
                    .append(" / 帧长 ").append(st.badFrames).append('\n');
            if (st.samplesMismatch > 0) {
                // ★ 不是坏包：是对方 samples 字段语义不同（电脑端历史版本填累计值）。
                //   单独一行写出来，免得又被当成"包全坏了"。
                sb.append("samples 字段异常 ").append(st.samplesMismatch)
                        .append(" 次（已按负载长度接收）\n");
            }
            sb.append("缓冲 ").append(st.bufferedMs).append("ms（目标 ").append(st.targetMs)
                    .append("）· 流 ").append(st.streamCount)
                    .append(" · 混音 ").append(pctText(st.peak)).append('\n');
            if (st.streams != null) {
                for (AudioRecv.StreamStats ss : st.streams) {
                    sb.append("  · ").append(ss.host).append(" 收 ").append(ss.received)
                            .append(" · 丢 ").append(ss.lost)
                            .append(" · 缓冲 ").append(ss.bufferedMs).append("ms")
                            .append(" · 电平 ").append(pctText(ss.peak))
                            .append(" · ").append(ss.ageMs).append("ms 前\n");
                }
            }
            if (st.dupRoutePackets > 0) {
                sb.append("  重复流已抑制 ").append(st.dupRoutePackets).append(" 包\n");
            }
        }

        // ── 发送 ──
        sb.append("── 发送 ──\n");
        AudioSend snd = sender;
        if (snd == null || !snd.isRunning()) {
            sb.append("发送器没跑（没点过送声音、也没开发广播）\n");
        } else {
            sb.append("目标 ").append(snd.targetCount()).append(" 台")
                    .append(broadcastOn ? "（📢 广播中）" : "")
                    .append(" · 合计 ").append(snd.packetsSent()).append(" 包 / ")
                    .append(Diagnosis.bytesText(snd.bytesSent()))
                    .append(" · 失败 ").append(snd.sendErrors()).append('\n');
            for (SendFanout.TargetStats ts : snd.targetStats()) {
                sb.append("  · ").append(ts.host).append(':').append(ts.port)
                        .append(" 发 ").append(ts.packetsSent).append(" 包")
                        .append(" · ").append(pctText(ts.peak))
                        .append(" · 音量 ").append(String.format(java.util.Locale.US, "%.2f", ts.gain));
                if (ts.sendErrors > 0) {
                    sb.append(" · 失败 ").append(ts.sendErrors);
                }
                if (ts.lastOkAgeMs < 0) {
                    sb.append(" · 还没成功 ✗");
                } else if (ts.lastOkAgeMs > 2000) {
                    sb.append(" · ").append(ts.lastOkAgeMs / 1000).append(" 秒前 ✗");
                } else {
                    sb.append(" · 正在发 ✓");
                }
                sb.append('\n');
            }
            int n = Math.max(1, snd.targetCount());
            sb.append("带宽 每台约 0.19 MB/s（1.5 Mbit/s）· 合计约 ")
                    .append(String.format(java.util.Locale.US, "%.2f",
                            n * (Proto.SAMPLE_RATE * Proto.CHANNELS * 2) / 1048576.0))
                    .append(" MB/s\n");
        }

        // ── 音量（三层）──
        sb.append("── 音量 ──\n");
        sb.append("我们这一层 ").append(volumeText()).append('\n');
        sb.append("系统 ").append(systemVolumeText()).append('\n');
        // ★ 合并版：左右声道状态如实进诊断报告，用户截报告发回来就能一眼看出设置。
        sb.append("左右声道 ").append(stereoText()).append('\n');
        sb.append("音频格式 48000 Hz / 立体声 PCM16 / 10 ms 一帧（非单声道 A-Law）\n");
        synchronized (peerChannelMap) {
            if (!peerChannelMap.isEmpty()) {
                sb.append("逐台分配 ");
                for (Map.Entry<String, Integer> e : peerChannelMap.entrySet()) {
                    sb.append(e.getKey()).append('→')
                            .append(ChannelRouter.channelText(e.getValue())).append(' ');
                }
                sb.append('\n');
            }
        }
        // ★ 2026-11-12：逐台延迟补偿 + 实时积压。用户报"两台不同步/有一台卡"时，
        //   这两个数字能直接区分"是加少了"（积压稳定但偏小）和"链路在掉队"（积压一直飘）。
        // ★ 2026-11-13：延迟已改为"本机输出延迟"（out_ctl delay 远程可控），不再是逐台补偿。
        sb.append("输出延迟 本机 ").append(outDelayMs).append("ms\n");
        return sb.toString();
    }

    /** 0~1 变成 "34%"（监控面板里数字要短）。 */
    private static String pctText(float v) {
        if (Float.isNaN(v) || v < 0f) {
            v = 0f;
        }
        return Math.round(Math.min(1f, v) * 100f) + "%";
    }

    /** 三层关系的人话（界面和报告共用一处，免得两种说法）。 */
    public String volumeExplainText() {
        return "实际听到的大小 = 系统音量 × 播放器自己的音量 × 我们这一层的音量。"
                + "这个滑杆只管最后一层，不动你的系统音量。";
    }

    /** 节点 ID：持久化保存，重启不变（电脑端据此把同一台手机认成同一台）。 */
    public String nodeIdText() {
        Discovery d = discovery;
        String id = (d != null) ? d.nodeId() : "";
        if (id == null || id.isEmpty()) {
            return "（还没有）";
        }
        return id + "（持久化，重启不变 ✓）";
    }

    /** 本机信标原文（诊断报告里贴一行，证明 id 不是空的、信标组得出来）。 */
    public String beaconPreviewText() {
        Discovery d = discovery;
        if (d == null) {
            return "发现未启动";
        }
        try {
            return d.beaconPreview();
        } catch (Throwable t) {
            return "读取信标失败：" + t;
        }
    }

    // ────────────────────── ★ 实际收到哪几路（界面要看得见） ──────────────────────

    /**
     * 「当前实际收到哪几路」——用户说的"路由模式和广播模式混淆"最后就落在这一行上：
     * 开关是意图，这一行才是事实。
     */
    public String recvStreamsText() {
        AudioRecv r = receiver;
        if (r == null || !r.isRunning()) {
            return "接收器没在跑（没开「听广播」，也没点过任何一台的「🎧 收听」）";
        }
        AudioRecv.Stats st = recvStats();
        if (st == null || st.streams == null || st.streams.isEmpty()) {
            return "当前没在收任何音频流（对面还没开始发，或者包没到）";
        }
        StringBuilder sb = new StringBuilder(96);
        sb.append("当前实际收到 ").append(st.streams.size()).append(" 路：");
        for (int i = 0; i < st.streams.size(); i++) {
            AudioRecv.StreamStats ss = st.streams.get(i);
            if (i > 0) {
                sb.append('、');
            }
            sb.append(nameOfHost(ss.host));
            if (ss.ageMs > 1000) {
                sb.append("（静了 ").append(ss.ageMs / 1000).append(" 秒）");
            }
        }
        return sb.toString();
    }

    /** 某台设备现在有没有真的在出声（设备卡片上显示"实际生效状态"用）。 */
    public boolean recvReceivingFrom(String host) {
        AudioRecv r = receiver;
        if (r == null) {
            return false;
        }
        try {
            return r.receivingFrom(host);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 去重（同一台设备的第 2 条流被抑制）的情况，给人看的一句话；没有就返回空串。 */
    public String dupRouteStatsText() {
        AudioRecv.Stats st = recvStats();
        if (st == null || st.dupRoutePackets <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(160);
        sb.append("已抑制 ").append(st.dupRoutePackets).append(" 个包（同一台设备的第 2 条流，绝不叠加播放）");
        if (st.dupRouteConfirmed > 0) {
            sb.append("；其中 ").append(st.dupRouteConfirmed)
                    .append(" 个包内容指纹相同 → 确认是同一份声音从两条路进来（广播 + 点对点路由）");
        }
        if (st.dupRouteText != null && st.dupRouteText.length() > 0) {
            sb.append("；").append(st.dupRouteText);
        }
        return sb.toString();
    }

    /** 把一个 host 换成人能认的名字（找不到就退回 IP）。 */
    public String nameOfHost(String host) {
        if (host == null || host.isEmpty()) {
            return "?";
        }
        try {
            for (Discovery.Peer p : peers()) {
                if (host.equals(p.host)) {
                    return p.label() + "（" + host + "）";
                }
            }
        } catch (Throwable ignored) {
            // 查不到就用 IP
        }
        return host;
    }

    private static int scoreIp(String ip, String nic) {
        int score;
        if (ip.startsWith("192.168.")) {
            score = 50;                       // 家用 Wi-Fi 最常见
        } else if (ip.startsWith("10.")) {
            score = 40;
        } else if (ip.startsWith("172.")) {
            int second = -1;
            try {
                second = Integer.parseInt(ip.split("\\.")[1]);
            } catch (Throwable ignored) {
                // 保持 -1
            }
            score = (second >= 16 && second <= 31) ? 40 : 5;
        } else if (ip.startsWith("169.254.")) {
            score = -20;                      // 没拿到 DHCP 的自分配地址
        } else {
            score = 0;
        }
        // VPN / 隧道网卡优先级压低：真正能通局域网的是 wlan0。
        if (nic.startsWith("tun") || nic.startsWith("ppp") || nic.startsWith("tap")) {
            score -= 30;
        }
        if (nic.startsWith("wlan")) {
            score += 5;
        }
        return score;
    }

    /** 音频端口（接收绑的端口，也是信标里对外公布的端口）。 */
    public int audioPort() {
        return audioPort;
    }

    /**
     * 本机名字（信标/通知/统计包里对外用的那个）。
     *
     * <p>注意必须取 {@link Discovery#name()}（用户可能改过），<b>不能</b>现算 {@code Build.MODEL}
     * —— 那样用户改的名字在通知和对端列表里就不生效了。
     */
    public String nodeName() {
        Discovery d = discovery;
        String n = (d != null) ? d.name() : null;
        if (n == null || n.length() == 0) {
            n = Discovery.defaultName();
        }
        return (n == null || n.length() == 0) ? "Android 设备" : n;
    }

    public String platform() {
        return Discovery.defaultPlatform();
    }

    // ────────────────────────────── 增益 ──────────────────────────────

    public float volume() {
        return volume;
    }

    /**
     * 「我们这一层的音量」滑杆（0~1.5）。
     *
     * <p>只作用于我们自己处理的 PCM：发送侧交给 AudioSend.setGain()（协议里的 gain_q14 会如实
     * 描述音量），接收侧交给 AudioRecv.setGain()（它只认 0~1，超出就削到 1）。
     * <b>刻意不碰系统媒体音量</b> —— 用户明确要求过这一点（系统音量是另一层，我们只显示它）。
     */
    public void setVolume(float v) {
        volume = v;
        AudioSend s = sender;
        if (s != null) {
            try {
                s.setGain(v);
            } catch (Throwable ignored) {
                // 调不了音量不影响传输
            }
        }
        AudioRecv r = receiver;
        if (r != null) {
            try {
                r.setGain(Math.min(1f, Math.max(0f, v)));
            } catch (Throwable ignored) {
                // 同上
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ★ 合并版：手机左右声道（Engine 这一层只负责「记住 + 转发」）
    //
    //  为什么不直接把设置塞进 AudioRecv：接收器是按需启停的（点收听/广播/送声时才建），
    //  一停一建就会把用户调好的左右平衡丢掉。所以设置存在 Engine + SharedPreferences，
    //  每次 ensureReceiver() 都 applyChannelSettings() 补回去 —— 与项目已有的
    //  restoreGains / applyAllowedHosts 是同一套做法。
    //
    //  ★ 铁律不变：所有系数只乘在我们自己处理的 PCM 上，绝不改系统媒体音量。
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * ★ 2026-11-12：键名从 {@code ch_mode} 改成 {@code ch_mode_v2}。
     *
     * <p>因为 2.22 及以前 {@code 1} 表示「单声道」，而 2.23 起 {@code 1} 表示「静音」。
     * 沿用旧键的话，任何设置过单声道的手机升级后**会突然一点声音都没有**（用户会以为坏了）。
     * 换键 = 老值一次性作废、回到默认立体声；用户在 2.23 上重新选一次即可。
     */
    private static final String PREF_CH_MODE = "ch_mode_v2";
    private static final String PREF_CH_BALANCE = "ch_balance";
    private static final String PREF_CH_LEFT = "ch_left";
    private static final String PREF_CH_RIGHT = "ch_right";
    private static final String PREF_CH_HOSTS = "ch_hosts";
    // ★ 2026-11-14（用户要求）：抖动缓冲档位持久化。
    private static final String PREF_RECV_BUF = "recv_buf_ms";       // 冗余副本（老协议）档位
    private static final String PREF_NACK_BUF = "nack_buf_ms";      // ★ 2026-11-17：NACK 档位，两套各自独立记住
    /** 档位表版本号：只用于"升级后把老档位抬到新默认"的一次性迁移，不参与日常读写。 */
    private static final String PREF_BUF_TIER_VER = "buf_tier_ver";
    private static final int BUF_TIER_VER = 2;   // 2 = 引入 3000ms 档，老数据抬到 3000
    private volatile int recvBufMs = AudioRecv.DEFAULT_TARGET_MS;

    private volatile int channelMode = ChannelRouter.MODE_STEREO;
    private volatile float channelBalance = 0f;
    private volatile float channelLeftGain = 1f;
    private volatile float channelRightGain = 1f;
    // ★ 2026-11-12（用户第 3 条）：「加入可以控制其他设备的音量」——
    //   这一台自己的播放音量（0~1），别的手机可以远程改它，自己也可以改。
    //   实现上交给 AudioRecv.setGain → AudioTrack.setVolume：不乘样本、不改系统音量。
    private static final String PREF_OUT_VOL = "out_vol";
    private volatile float outVolume = 1f;
    /** host → 1 左 / 2 右（双耳不存，省得列表越来越长）。 */
    private final Map<String, Integer> peerChannelMap = new HashMap<>();

    /**
     * 抖动缓冲可选档位（毫秒）。★ 2026-11-14 用户定案：<b>700 / 1000 / 1500</b>。
     *
     * <p>注意 700 的物理余量很薄：本机延迟为 0 时深度约 700ms，而副本延迟 640ms，
     * 副本只剩 ~60ms 超前量 —— 比 500 档（曾经 100% 白发崩掉）好，但仍可能被
     * 350~500ms 的成簇丢包打穿。实测为准，若确实不稳就只留 1000/1500。
     */
    /**
     * ★ 2026-11-17（用户要求）：两套缓冲【彻底分开】，切换协议时联动切到对应那套。
     *  - 冗余副本（老协议）：每包带 640ms 旧副本 + 深缓冲。档位 {700,1000,1500}，可调。
     *  - NACK 重传（低延迟）：缺包即时补，浅缓冲才低延迟。**固定 500ms，档位不可调**。
     *
     * <p>★ 2026-11-17 晚（用户定案）：「NACK 低延迟删掉 3 个档位，默认就是 500ms，
     * 切到 NACK 模式时把那 3 个档位显示灰色不可选」。所以界面永远只渲染
     * {@link #RECV_BUF_TIERS} 这三个 chip —— NACK 模式下它们整体灰掉不可点，
     * 真正生效的缓冲由 {@link #NACK_FIXED_BUF_MS} 决定。
     */
    public static final int[] RECV_BUF_TIERS = {700, 1000, 1500};   // 冗余副本（老协议），界面固定渲染这三档
    /** ★ NACK 重传模式下的固定缓冲（用户定案：不提供档位，就是 500ms）。 */
    public static final int NACK_FIXED_BUF_MS = 500;
    /**
     * 当前协议下【允许设置】的档位表（只读返回静态数组，调用方别改）。
     * NACK 只有一档 500，所以 setRecvBufMs 校验时自然会拒掉 700/1000/1500 与 100/300。
     */
    public int[] bufTiers() {
        return nackEnabled ? NACK_ONLY_TIERS : RECV_BUF_TIERS;
    }
    private static final int[] NACK_ONLY_TIERS = {NACK_FIXED_BUF_MS};
    /** 当前协议的默认（也是唯一）档位：切协议时用。冗余副本=1500（稳），NACK=500（固定）。 */
    private int defaultBufFor(boolean nack) {
        return nack ? NACK_FIXED_BUF_MS : AudioRecv.DEFAULT_TARGET_MS;
    }

    // ═══════════════ ★ 2026-11-17 NACK 按需补包协议开关（默认关闭，可随时切回老协议） ═══════════════
    // 关闭（默认）：发送端仍"每包附带 640ms 旧副本"，接收端 1500ms 深缓冲 —— 和 v2.96 逐字一致。
    // 打开：接收端检测缺包→经发现层回请求→发送端从环形缓存补真包；配合 300ms 档冲低延迟。
    // 两套可同时开（多几个被去重的包，不会出错）。测试不行把开关关掉即完全回到老行为。
    private static final String PREF_NACK = "nack_enabled";
    private volatile boolean nackEnabled = false;
    public boolean nackEnabled() { return nackEnabled; }

    // ── NACK 命中计数（诊断页用，判断补包机制有没有真在跑）──
    /** 本机作为接收端，累计发起的补包请求批次数。 */
    public long nackReqBatches() {
        AudioRecv r = receiver;
        return r == null ? 0 : r.nackReqBatches();
    }
    /** 本机作为接收端，累计请求补的 seq 个数。 */
    public long nackReqSeqs() {
        AudioRecv r = receiver;
        return r == null ? 0 : r.nackReqSeqs();
    }
    /** 本机作为发送端，累计给别人补发出去的包数。 */
    public long nackSentCount() {
        AudioSend s = sender;
        return s == null ? 0 : s.nackSent();
    }

    /**
     * 切换补包协议（界面调用）：立刻作用到本地收发两端 + 联动切到该协议的档位表，
     * 并【自动同步到所有在线设备】（用户 2026-11-17 明确要求：广播端切了，接收端要跟着切）。
     */
    public void setNackEnabled(boolean on) {
        setNackEnabled(on, true);
    }

    /**
     * @param broadcast true = 本机切换后再经发现层下发给所有在线设备。
     *                  收到远程指令的一侧必须传 false —— 否则 A→B→A 无限互相下发。
     */
    public void setNackEnabled(boolean on, boolean broadcast) {
        // ★ 2026-11-17 晚（用户要求「记住不同模式的记忆数据」）：切协议前先把当前协议的
        //   延迟落到它自己那个 key —— 切完 outDelayPrefKey() 就指向新协议了。
        if (app != null) {
            try {
                channelPrefs().edit().putInt(outDelayPrefKey(), outDelayMs).apply();
            } catch (Throwable ignored) {
            }
        }
        nackEnabled = on;
        AudioSend s = sender;
        if (s != null) {
            s.setNackEnabled(on);
        }
        AudioRecv r = receiver;
        if (r != null) {
            r.setNackEnabled(on);
        }
        // 联动缓冲：NACK 固定 500ms（无档位）；冗余副本从它自己记住的那一档恢复（没存过就用默认）。
        int target;
        try {
            target = channelPrefs().getInt(on ? PREF_NACK_BUF : PREF_RECV_BUF, -1);
        } catch (Throwable t) {
            target = -1;
        }
        boolean hit = false;
        for (int t : bufTiers()) {
            if (t == target) { hit = true; break; }
        }
        if (!hit) {
            target = defaultBufFor(on);
        }
        recvBufMs = target;
        if (r != null) {
            r.setTargetBufferMs(target);
        }
        try {
            if (app != null) {
                channelPrefs().edit().putBoolean(PREF_NACK, on).apply();
            }
        } catch (Throwable ignored) {
        }
        // ★ 2026-11-17 晚（用户要求）：延迟同样按协议记忆 —— 切到新协议就自动读回它上次那个值
        //   （从没在这个协议下调过 = 0ms）。只在本机生效，随后跟协议一起下发给接收端。
        int delayMs;
        try {
            delayMs = channelPrefs().getInt(on ? PREF_OUT_DELAY_NACK : PREF_OUT_DELAY, 0);
        } catch (Throwable t) {
            delayMs = 0;
        }
        applyOutDelayLocal(delayMs);
        pokeStats();
        Log.i(TAG, "补包协议已切到 " + (on ? "NACK 重传" : "冗余副本") + "，缓冲 " + target
                + "ms，延迟 " + outDelayMs + "ms（该协议自己记住的值）");
        if (broadcast) {
            broadcastNackMode(on, target, outDelayMs);
        }
    }

    private static final String NACK_TAG = "nack_rx";

    /**
     * 接收端发现缺包 → 经发现层把 seq 列表发回给发送端（收包线程调用，必须快）。
     * 走发现端口（对方一直活着），不走音频端口（对方那个临时 socket 只发不收）。
     */
    private void sendNackRequest(String host, int[] missingSeqs) {
        if (!nackEnabled || host == null || host.isEmpty() || missingSeqs == null) {
            return;
        }
        final Discovery d = discovery;
        if (d == null) {
            return;
        }
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (int s : missingSeqs) {
                arr.put(s & 0xFFFF);
            }
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("t", NACK_TAG);
            o.put("seq", arr);
            String body = o.toString();
            Discovery.Peer p = peerByHost(host);
            int port = (p != null && p.discPort > 0) ? p.discPort : Proto.DEFAULT_DISCOVERY_PORT;
            d.sendControl(body, host, port);
        } catch (Throwable t) {
            Log.w(TAG, "发 NACK 请求失败（已忽略）：" + host + " " + t);
        }
    }

    /**
     * 发送端收到 NACK 请求 → 从环形缓存补发对应 seq（回调在发现线程上）。
     * @return true = 这是 NACK 消息（已被处理，不再往统计那条路送）
     */
    private boolean handleNackRequest(String host, String json) {
        if (!nackEnabled || json == null || json.indexOf(NACK_TAG) < 0) {
            return false;
        }
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            if (!NACK_TAG.equals(o.optString("t", ""))) {
                return false;
            }
            org.json.JSONArray arr = o.optJSONArray("seq");
            if (arr == null || arr.length() == 0) {
                return true;
            }
            int[] seqs = new int[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                seqs[i] = arr.optInt(i, 0) & 0xFFFF;
            }
            AudioSend s = sender;
            if (s != null) {
                int n = s.requestResend(seqs);
                if (n > 0 && n % 20 == 1) {
                    Log.i(TAG, "NACK 补包 → " + host + " 共 " + s.nackSent());
                }
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "NACK 请求解析失败（已忽略）：" + t);
            return false;
        }
    }

    /** 抖动缓冲档位（见 {@link #RECV_BUF_TIERS}）。 */
    public int recvBufMs() {
        return recvBufMs;
    }

    public void setRecvBufMs(int ms) {
        setRecvBufMs(ms, true);
    }

    /**
     * @param broadcast ★ 2026-11-14（用户要求「这三个大档位也要同步到其他设备」）：
     *                  true = 设完本机后，再把这个档位下发给所有在线设备。
     *                  收到远程指令的那一侧必须传 false —— 否则 A→B、B→A 会无限互相下发。
     */
    public void setRecvBufMs(int ms, boolean broadcast) {
        boolean ok = false;
        for (int t : bufTiers()) {
            if (t == ms) {
                ok = true;
                break;
            }
        }
        if (!ok) {
            return;
        }
        recvBufMs = ms;
        AudioRecv r = receiver;
        if (r != null) {
            r.setTargetBufferMs(ms);
        }
        try {
            // ★ 2026-11-17：两套档位各自存盘，切协议时互不覆盖。
            channelPrefs().edit().putInt(nackEnabled ? PREF_NACK_BUF : PREF_RECV_BUF, ms).apply();
        } catch (Throwable ignored) {
        }
        pokeStats();
        if (broadcast) {
            broadcastRecvBuf(ms);
        }
    }

    /**
     * 把缓冲档位下发给所有在线、且支持被远程设置的设备。
     *
     * <p>为什么复用「远程设置」通道（{@code out_ctl}）：那条通道本来就是"谁改了谁的输出，
     * 当场生效并存盘"，还自带对端能力协商（{@code p.outCtl}）和保序线程。档位本质上是
     * 同一类东西 —— 只是它现在也允许改"接收缓冲"。
     */
    private void broadcastRecvBuf(final int ms) {
        Discovery d = discovery;
        if (d == null) {
            return;
        }
        java.util.List<Discovery.Peer> peers;
        try {
            peers = d.peers();
        } catch (Throwable t) {
            return;
        }
        int sent = 0;
        for (final Discovery.Peer p : peers) {
            if (p == null || p.host == null || p.host.isEmpty() || !p.outCtl) {
                continue;
            }
            final String body = buildOutCtlJson(-1, -1, -1, ms);
            final int port = p.discPort > 0 ? p.discPort : Proto.DEFAULT_DISCOVERY_PORT;
            try {
                outCtlExec().execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            d.sendControl(body, p.host, port);
                        } catch (Throwable ignored) {
                        }
                    }
                });
                sent++;
            } catch (Throwable ignored) {
            }
        }
        if (sent > 0) {
            Log.i(TAG, "缓冲档位 " + ms + "ms 已下发给 " + sent + " 台设备");
        }
    }

    /**
     * ★ 2026-11-17（用户要求）：把"补包协议 + 对应缓冲 + 对应延迟"一起下发给所有在线设备。
     * 这样广播端点一下，接收端自动跟着切到同一协议、同一缓冲、同一延迟，不用每台手动点。
     *
     * @param delayMs 本机在新协议下记住的延迟值（用户要求：切回来读回上一次那个值，并同步过去）。
     */
    private void broadcastNackMode(final boolean on, final int bufMs, final int delayMs) {
        Discovery d = discovery;
        if (d == null) {
            return;
        }
        java.util.List<Discovery.Peer> peers;
        try {
            peers = d.peers();
        } catch (Throwable t) {
            return;
        }
        final String body = buildOutCtlJson(-1, -1, delayMs, bufMs, on ? 1 : 0);
        int sent = 0;
        for (final Discovery.Peer p : peers) {
            if (p == null || p.host == null || p.host.isEmpty() || !p.outCtl) {
                continue;
            }
            final int port = p.discPort > 0 ? p.discPort : Proto.DEFAULT_DISCOVERY_PORT;
            try {
                outCtlExec().execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            d.sendControl(body, p.host, port);
                        } catch (Throwable ignored) {
                        }
                    }
                });
                sent++;
            } catch (Throwable ignored) {
            }
        }
        if (sent > 0) {
            Log.i(TAG, "补包协议 " + (on ? "NACK" : "冗余副本") + " + 缓冲 " + bufMs
                    + "ms + 延迟 " + delayMs + "ms 已下发给 " + sent + " 台设备");
        }
    }

    private android.content.SharedPreferences channelPrefs() {
        return app.getSharedPreferences("audiomixing", Context.MODE_PRIVATE);
    }

    private void loadChannelSettings() {
        try {
            android.content.SharedPreferences sp = channelPrefs();
            channelMode = sp.getInt(PREF_CH_MODE, ChannelRouter.MODE_STEREO);
            channelBalance = sp.getFloat(PREF_CH_BALANCE, 0f);
            channelLeftGain = sp.getFloat(PREF_CH_LEFT, 1f);
            channelRightGain = sp.getFloat(PREF_CH_RIGHT, 1f);
            outVolume = Math.max(0f, Math.min(1f, sp.getFloat(PREF_OUT_VOL, 1f)));
            // ★ 2026-11-17：恢复 NACK 开关（默认 false = 冗余副本，老用户零感知）。
            //   必须先读它 —— 下面延迟/缓冲都要按当前协议各自那套 key 取。
            nackEnabled = sp.getBoolean(PREF_NACK, false);
            // ★ 2026-11-17 晚：延迟按协议各读一套（冗余副本 out_delay_ms / NACK out_delay_nack_ms）。
            outDelayMs = Math.max(0, Math.min(AudioRecv.MAX_EXTRA_DELAY_MS,
                    sp.getInt(outDelayPrefKey(), 0)));
            // ★ 2026-11-13：滑杆一格 = 5ms，旧版本存下来的任意毫秒值（比如 137）
            //   吸附到 5ms 网格，否则回显时滑杆落在 135、文字写 137，会来回跳。
            outDelayMs = Math.round(outDelayMs / 5f) * 5;
            // 两套档位各自恢复：按当前协议的 key 读、用当前协议的表校验。
            recvBufMs = sp.getInt(nackEnabled ? PREF_NACK_BUF : PREF_RECV_BUF, defaultBufFor(nackEnabled));
            boolean tierOk = false;
            for (int t : bufTiers()) {
                if (t == recvBufMs) {
                    tierOk = true;
                    break;
                }
            }
            if (!tierOk) {
                recvBufMs = defaultBufFor(nackEnabled);
            }
            // ★ 2026-11-14（用户：「继续把延迟加大到 3000 毫秒」）：一次性迁移。
            //   老版本存下来的档位（2000/2500）本身合法，会把新默认 3000 顶掉，
            //   用户装了新版却还是旧延迟。故加版本标记：从没迁移过的老数据一律抬到
            //   新默认值；迁移一次后记标记，之后用户在界面上选什么就尊重什么。
            if (sp.getInt(PREF_BUF_TIER_VER, 0) < BUF_TIER_VER && !nackEnabled) {
                recvBufMs = AudioRecv.DEFAULT_TARGET_MS;
                sp.edit()
                        .putInt(PREF_BUF_TIER_VER, BUF_TIER_VER)
                        .putInt(PREF_RECV_BUF, recvBufMs)
                        .apply();
                Log.i(TAG, "缓冲档位一次性迁移 → " + recvBufMs + "ms");
            }
            String raw = sp.getString(PREF_CH_HOSTS, "");
            for (String part : raw.split(";")) {
                int eq = part.indexOf('=');
                if (eq > 0) {
                    try {
                        int v = Integer.parseInt(part.substring(eq + 1));
                        if (v == 1 || v == 2) {
                            peerChannelMap.put(part.substring(0, eq), v);
                        }
                    } catch (NumberFormatException ignored) {
                        // 坏条目跳掉，不影响其它设备
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "读取左右声道设置失败，按默认立体声继续", t);
        }
    }

    private void saveChannelSettings() {
        try {
            StringBuilder sb = new StringBuilder();
            synchronized (peerChannelMap) {
                for (Map.Entry<String, Integer> e : peerChannelMap.entrySet()) {
                    sb.append(e.getKey()).append('=').append(e.getValue()).append(';');
                }
            }
            channelPrefs().edit()
                    .putInt(PREF_CH_MODE, channelMode)
                    .putFloat(PREF_CH_BALANCE, channelBalance)
                    .putFloat(PREF_CH_LEFT, channelLeftGain)
                    .putFloat(PREF_CH_RIGHT, channelRightGain)
                    .putFloat(PREF_OUT_VOL, outVolume)
                    // ★ 2026-11-17 晚：延迟按协议各存一套（切协议时互不覆盖）
                    .putInt(outDelayPrefKey(), outDelayMs)
                    .putString(PREF_CH_HOSTS, sb.toString())
                    .apply();
        } catch (Throwable t) {
            Log.w(TAG, "保存左右声道设置失败", t);
        }
    }

    /** 接收器（重）建时把全套左右声道设置补回去。 */
    private void applyChannelSettings(AudioRecv r) {
        if (r == null) {
            return;
        }
        try {
            r.setChannelMode(channelMode);
            r.setBalance(channelBalance);
            r.setLeftGain(channelLeftGain);
            r.setRightGain(channelRightGain);
            r.setOutVolume(outVolume);   // ★ 本机播放增益（接收器重建后也要补回去）
            r.setOutDelay(outDelayMs);   // ★ 本机输出延迟（接收器重建后也要补回去）


            synchronized (peerChannelMap) {
                for (Map.Entry<String, Integer> e : peerChannelMap.entrySet()) {
                    r.setHostChannel(e.getKey(), e.getValue());
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "恢复左右声道设置失败", t);
        }
    }

    private void applyChannelNow() {
        AudioRecv r = receiver;
        if (r != null) {
            applyChannelSettings(r);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ★ 2026-11-12：逐设备延迟补偿（对齐"谁先出声"）
    //
    //  和抖动缓冲的分工：抖动缓冲（100~200ms）吸收忽快忽慢、解决卡顿；
    //  这里的延迟（0~300ms）把某一路整体往后挪、解决**两台设备不同步**。
    //  用户原话：「还有延迟测试控制，避免 Wi-Fi 延迟」——
    //  Wi-Fi 好的那台先出声，给它加几十毫秒，两台就同时到耳朵了。
    //
    //  存在 SharedPreferences 里而不是只放内存：接收器是按需启停的，
    //  一停一建就会把调好的延迟丢掉（和 loadChannelSettings 同一个理由）。
    // ══════════════════════════════════════════════════════════════════════════
    private static final String PREF_OUT_DELAY = "out_delay_ms";            // 冗余副本（老协议）
    /** ★ 2026-11-17 晚（用户要求）：延迟也【按补包协议分两套记忆】，切回来读回上一次那个值。 */
    private static final String PREF_OUT_DELAY_NACK = "out_delay_nack_ms";  // NACK 重传
    /** 本机输出延迟（毫秒，0~300）。和 outVolume 一样：别的手机可以远程改它，自己也可以改。 */
    private volatile int outDelayMs = 0;
    /** 当前协议的延迟存盘 key（NACK / 冗余副本各一套，互不覆盖）。 */
    private String outDelayPrefKey() {
        return nackEnabled ? PREF_OUT_DELAY_NACK : PREF_OUT_DELAY;
    }
    /** ★ 拖动本机延迟时给对端下发的节流（毫秒）：一格 5ms，不节流会一秒发几十条。 */
    private static final long DELAY_SYNC_MIN_INTERVAL_MS = 150;
    private long lastDelaySyncMs = 0;

    /**
     * 设定本机输出延迟（0~300ms）：整条混音一起往后挪，用来对齐"谁先出声"。
     *
     * <p>★ 2026-11-13 用户：「拖动其他设备的延迟滑块，其他设备上没有实时滑块 UI 更新，
     * 只有音量滑块可以」—— 原来的"逐台延迟补偿"是纯本机的，对端根本不知道；现在改成
     * 和音量完全对称：谁拖「它的延迟」，值就走 out_ctl 落到那一台的
     * {@link AudioRecv#setOutDelay}，那台自己的「本机延迟」滑杆立刻动，
     * 统计里再报回来让所有人的滑杆都跟上。
     *
     * <p>★ 2026-11-17 晚（用户要求）：「广播端拖动本机延迟时，自动同步给其它接收端」——
     * 现在拖本机延迟会经 out_ctl 下发给所有在线设备（节流 {150}ms，松手 force 一次补最终值）。
     */
    public void setOutDelayMs(int ms) {
        setOutDelayMs(ms, false);
    }

    /**
     * @param forceSync true = 松手时调用：跳过节流，把最终值一定发出去。
     *                  收到远程指令的那一侧绝不能传 true（否则 A→B→A 无限互相下发）。
     */
    public void setOutDelayMs(int ms, boolean forceSync) {
        int v = Math.max(0, Math.min(AudioRecv.MAX_EXTRA_DELAY_MS, ms));
        v = Math.round(v / 5f) * 5;   // ★ 吸附到 5ms 网格（滑杆一格 = 5ms）
        boolean changed = v != outDelayMs;
        if (!changed && !forceSync) {
            return;
        }
        if (changed) {
            applyOutDelayLocal(v);     // 存盘 + 应用到接收器 + 让别人的卡片立刻跟上
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (forceSync || now - lastDelaySyncMs >= DELAY_SYNC_MIN_INTERVAL_MS) {
            lastDelaySyncMs = now;
            broadcastOutDelay(v);
        }
    }

    /**
     * 只在本机生效（不向外下发）：存盘、交给接收器、刷一轮统计。
     * 远程指令与协议切换恢复记忆值都走这里 —— 否则会 A→B→A 打回环。
     */
    private void applyOutDelayLocal(int ms) {
        int v = Math.max(0, Math.min(AudioRecv.MAX_EXTRA_DELAY_MS, ms));
        v = Math.round(v / 5f) * 5;
        outDelayMs = v;
        saveChannelSettings();
        AudioRecv r = receiver;
        if (r != null) {
            try {
                r.setOutDelay(v);
            } catch (Throwable t) {
                Log.w(TAG, "设置本机输出延迟失败", t);
            }
        }
        pokeStats();   // 让别的手机那张卡片上的「它的延迟」立刻跟上（而不是等 2 秒）
    }

    /** 把本机延迟下发给所有在线、且支持被远程设置的设备（拖动时节流调用）。 */
    private void broadcastOutDelay(final int ms) {
        Discovery d = discovery;
        if (d == null) {
            return;
        }
        java.util.List<Discovery.Peer> peers;
        try {
            peers = d.peers();
        } catch (Throwable t) {
            return;
        }
        final String body = buildOutCtlJson(-1, -1, ms, -1);
        for (final Discovery.Peer p : peers) {
            if (p == null || p.host == null || p.host.isEmpty() || !p.outCtl) {
                continue;
            }
            final int port = p.discPort > 0 ? p.discPort : Proto.DEFAULT_DISCOVERY_PORT;
            try {
                outCtlExec().execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            d.sendControl(body, p.host, port);
                        } catch (Throwable ignored) {
                        }
                    }
                });
            } catch (Throwable ignored) {
            }
        }
    }

    /** 本机现在的输出延迟（毫秒）。 */
    public int outDelayMs() { return outDelayMs; }

    /** 这一台报上来的本机输出延迟（0~300）；-1 = 它没报（老版本 / 电脑端）。 */
    public int peerOutDelay(String host) {
        synchronized (peerLock) {
            Diagnosis.PeerStats ps = peerStats.get(host);
            return ps == null ? -1 : ps.outDelay;
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  ★ 2026-11-15 双音箱环绕摆动
    //
    //  需求（用户原话）：选两台音箱配对，音量反向联动 —— A 升 B 降，到区间边界反向，
    //  来回循环；区间/速度可调；开启时快照原始音量、运行中锁住这两台的滑杆、
    //  停止后恢复原音量；其余音箱完全不受影响。
    //
    //  实现要点：
    //   · 只调**这两台自己的输出音量**（复用已有的 out_ctl 远程设置通道），
    //     不碰本机混音、不碰其他设备，天然"只影响这一对"。
    //   · 定时器跑在主线程（50ms 一趟），每趟算一次目标音量再下发；音量本身是
    //     百分比整数，50ms 步进 1% 已经足够平滑；低速时用小数累加器攒够 1% 才动，
    //     所以速度滑杆拖到最低也是真的慢，而不是被取整吃掉。
    //   · 音量在接收端是 AudioTrack.setVolume 的乘数，系统内部有 ramp，不会爆音。
    // ══════════════════════════════════════════════════════════════════

    /** 摆动定时器步长（毫秒）。50ms = 每秒 20 趟，1% 一档足够顺滑。 */
    private static final int SURROUND_TICK_MS = 50;

    private volatile boolean surroundOn;
    private volatile String surroundHostA = "";
    private volatile String surroundHostB = "";
    /**
     * 摆动区间（百分比，永远落在 5% 网格上）。
     *
     * <p>★ 2026-11-15 追加需求 3：A、B **各自一套**，不再共用。
     * 反向联动改成"共用一条归一化进度 t"来天然保证：
     * A 走 t（0=下限 → 1=上限），B 走 (1-t)（0=它的下限 → 1=它的上限）。
     * 于是 A 到自己上限的同一刻，B 正好到自己下限；两边的区间跨度可以完全不同。
     */
    private volatile int surroundMinA = 20;
    private volatile int surroundMaxA = 80;
    private volatile int surroundMinB = 20;
    private volatile int surroundMaxB = 80;

    // ── ★ 2026-11-15 追加：共用区间 / 逐台区间【两套参数分开存】+ 当前生效模式 ──
    /** 共用模式：A、B 都按同一套 shared 区间摆（老行为）。 */
    public static final int RANGE_SHARED = 0;
    /** 独立模式：A、B 各按自己的区间摆。 */
    public static final int RANGE_PER_SPEAKER = 1;
    /** 当前生效的区间模式。默认共用。 */
    private volatile int surroundRangeMode = RANGE_SHARED;
    /**
     * ★ 2026-11-16（用户澄清）：音量键**不要**搞一个额外的"整体音量"层，
     *   而是直接改 A、B 各自区间的<b>上限</b>（min/max 按同一相对比例一起缩放）。
     *
     * <p>于是 A（上限 80）按 -5% → 76，B（上限 90）按 -5% → 86：比例相同、绝对值不同，
     *   面板上那两根「最大」滑杆会当场跟着动，用户看到的就是"上限变了"，
     *   没有中间那一层抽象增益。共用模式下音量键仍然只管系统音量（不接管）。
     */
    /**
     * 共用区间（只在共用模式下参与摆动）。
     *
     * <p>★ 关键：它和逐台的 minA/maxA/minB/maxB 是<b>两份互不覆盖</b>的存储 ——
     * 从独立模式切回共用模式时，绝不会拿逐台的值改写这里，共用区间永远保持自己
     * 上一次的值（用户明确要求）。
     */
    private volatile int surroundSharedMin = 20;
    private volatile int surroundSharedMax = 80;
    /** 摆动速度：每秒音量变化百分比。 */
    private volatile int surroundSpeed = 20;
    /** 两台当前的目标音量（百分比）。 */
    private volatile int surroundVolA = 50;
    private volatile int surroundVolB = 50;
    /** +1 = 正在往区间上限走；-1 = 往 下限走。 */
    private int surroundDir = 1;

    // ── ★ 2026-11-15 追加：运动曲线 / 端点停留 / 广播联动 ──

    /** 线性匀速。 */
    public static final int CURVE_LINEAR = 0;
    /** 正弦曲线：两端自然放慢（更像人耳的感觉）。 */
    public static final int CURVE_SINE = 1;

    /** 当前运动曲线（默认线性，和上一版行为一致）。 */
    private volatile int surroundCurve = CURVE_LINEAR;
    /** 到上下限后停留几秒再反向（0 = 不停）。★ 追加需求 2：两种曲线都生效。 */
    private volatile int surroundHoldSec = 0;
    /**
     * 归一化进度 0..1：0 = 区间下限，1 = 区间上限。
     *
     * <p>两条曲线共用这一个进度，只是映射到音量时用的函数不同：
     * 线性 {@code v = min + span*t}；正弦 {@code v = min + span*(1-cos(πt))/2}。
     * 这样"摆动速度（%/秒）"在两种曲线下都是平均速度，不会一切到正弦就变味。
     */
    private double surroundT;
    /** 端点停留的截止时刻（elapsedRealtime）；0/过去 = 不在停留。 */
    private long surroundHoldUntil;
    /** 上一次广播联动的判断结果（true = 正在往外送声音），供界面显示"已暂停"。 */
    private volatile boolean surroundBroadcasting;

    /** 小数步进累加器（低速档不被四舍五入吃掉）。 */
    private float surroundAcc;
    /**
     * ★ 2026-11-16「按了音量键却听不出变化」：手推量（百分点）。
     *
     * <p>音量键改的是<b>上限</b>，而当前音量由摆动进度 t 决定 —— 摆动正好在中段或
     * 停在另一端时，上限 +5% 只让当前值涨 5%×shape，几乎听不出来，用户就以为
     * "没反应"。这里给每台加一个瞬时推力：按一下就先把当前音量顶上去 ±5，
     * 再用 {@link #SURROUND_PUSH_MS} 线性淡出，回到正常摆动曲线。
     */
    private volatile int surroundPushA;
    private volatile int surroundPushB;
    private long surroundPushAt;
    private static final long SURROUND_PUSH_MS = 900;
    /** 开启瞬间的原始音量快照（-1 = 对端没上报，停止时不恢复）。 */
    private int surroundSnapA = -1;
    private int surroundSnapB = -1;
    private Runnable surroundTick;

    // ══ 环绕参数的本地持久化（★ 2026-11-15 追加需求 4） ══
    //
    //  存：A/B 两台、摆动速度、运动曲线、两端停留、**当前区间模式**、
    //      共用区间 min/max、逐台区间 minA/maxA/minB/maxB。
    //  不存：环绕是否正在运行（surroundOn）—— App 每次启动必须回到"关闭"，
    //       否则一打开就自己开始改别人音量，属于危险行为。
    private static final String PREF_SR_HOST_A = "sr_host_a";
    private static final String PREF_SR_HOST_B = "sr_host_b";
    private static final String PREF_SR_SPEED = "sr_speed";
    private static final String PREF_SR_CURVE = "sr_curve";
    private static final String PREF_SR_HOLD = "sr_hold";
    private static final String PREF_SR_MODE = "sr_mode";
    private static final String PREF_SR_SH_MIN = "sr_shared_min";
    private static final String PREF_SR_SH_MAX = "sr_shared_max";
    private static final String PREF_SR_MIN_A = "sr_min_a";
    private static final String PREF_SR_MAX_A = "sr_max_a";
    private static final String PREF_SR_MIN_B = "sr_min_b";
    private static final String PREF_SR_MAX_B = "sr_max_b";

    private void loadSurroundSettings() {
        try {
            android.content.SharedPreferences sp = channelPrefs();
            surroundHostA = sp.getString(PREF_SR_HOST_A, "");
            surroundHostB = sp.getString(PREF_SR_HOST_B, "");
            surroundSpeed = Math.max(1, Math.min(100, sp.getInt(PREF_SR_SPEED, 20)));
            surroundCurve = sp.getInt(PREF_SR_CURVE, CURVE_LINEAR) == CURVE_SINE
                    ? CURVE_SINE : CURVE_LINEAR;
            surroundHoldSec = Math.max(0, Math.min(10, sp.getInt(PREF_SR_HOLD, 0)));
            surroundRangeMode = sp.getInt(PREF_SR_MODE, RANGE_SHARED) == RANGE_PER_SPEAKER
                    ? RANGE_PER_SPEAKER : RANGE_SHARED;
            surroundSharedMin = snap5(sp.getInt(PREF_SR_SH_MIN, 20));
            surroundSharedMax = snap5(sp.getInt(PREF_SR_SH_MAX, 80));
            surroundMinA = snap5(sp.getInt(PREF_SR_MIN_A, 20));
            surroundMaxA = snap5(sp.getInt(PREF_SR_MAX_A, 80));
            surroundMinB = snap5(sp.getInt(PREF_SR_MIN_B, 20));
            surroundMaxB = snap5(sp.getInt(PREF_SR_MAX_B, 80));
            Log.i(TAG, "读回环绕配置：模式=" + (surroundRangeMode == RANGE_PER_SPEAKER ? "独立" : "共用")
                    + " 共用 " + surroundSharedMin + "~" + surroundSharedMax
                    + "% 逐台 A " + surroundMinA + "~" + surroundMaxA
                    + "% B " + surroundMinB + "~" + surroundMaxB
                    + "% 速度 " + surroundSpeed + " 曲线 " + surroundCurve
                    + " 停留 " + surroundHoldSec + "s");
        } catch (Throwable t) {
            Log.w(TAG, "读回环绕配置失败（用默认值）", t);
        }
    }

    private void saveSurroundSettings() {
        try {
            channelPrefs().edit()
                    .putString(PREF_SR_HOST_A, surroundHostA == null ? "" : surroundHostA)
                    .putString(PREF_SR_HOST_B, surroundHostB == null ? "" : surroundHostB)
                    .putInt(PREF_SR_SPEED, surroundSpeed)
                    .putInt(PREF_SR_CURVE, surroundCurve)
                    .putInt(PREF_SR_HOLD, surroundHoldSec)
                    .putInt(PREF_SR_MODE, surroundRangeMode)
                    .putInt(PREF_SR_SH_MIN, surroundSharedMin)
                    .putInt(PREF_SR_SH_MAX, surroundSharedMax)
                    .putInt(PREF_SR_MIN_A, surroundMinA)
                    .putInt(PREF_SR_MAX_A, surroundMaxA)
                    .putInt(PREF_SR_MIN_B, surroundMinB)
                    .putInt(PREF_SR_MAX_B, surroundMaxB)
                    // ★ 2026-11-16（用户：逐台区间重开就复位）：原来用 apply() 异步落盘，
                    //   调完区间马上切后台/被系统杀掉，这次写入就丢了，重开自然读到旧值。
                    //   这里改成 commit() 同步写：一次几毫秒，只有拖滑杆/按键时才会调，
                    //   不影响音频线程。
                    .commit();
        } catch (Throwable ignored) {
        }
    }

    public boolean surroundOn() {
        return surroundOn;
    }

    public String surroundHostA() {
        return surroundHostA;
    }

    public String surroundHostB() {
        return surroundHostB;
    }

    /** 当前生效模式（{@link #RANGE_SHARED} / {@link #RANGE_PER_SPEAKER}）。 */
    public int surroundRangeMode() {
        return surroundRangeMode;
    }

    public boolean surroundPerSpeakerMode() {
        return surroundRangeMode == RANGE_PER_SPEAKER;
    }

    /**
     * 切换区间模式。切到独立模式时，按用户要求把**当前共用区间**填进已选音箱的
     * 逐台区间作为初始参考值；切回共用模式时**不动**共用区间（两套各自保存）。
     *
     * @return 空串 = 成功；否则是给人看的原因
     */
    public String setSurroundRangeMode(int mode) {
        int m = (mode == RANGE_PER_SPEAKER) ? RANGE_PER_SPEAKER : RANGE_SHARED;
        if (m == surroundRangeMode) {
            return "";
        }
        surroundRangeMode = m;
        // ★ 2026-11-16（用户）：两套参数**独立记忆，切换模式互不覆盖**。
        //   切进独立模式不再拿共用值去填逐台（那样会冲掉用户微调过的值）；
        //   切回共用也不会拿逐台值改写共用。切模式只改"当前用哪一套"。
        Log.i(TAG, "区间模式 → " + (m == RANGE_PER_SPEAKER ? "独立逐台" : "共用")
                + "；共用 " + surroundSharedMin + "~" + surroundSharedMax
                + "% / 逐台 A " + surroundMinA + "~" + surroundMaxA
                + "% B " + surroundMinB + "~" + surroundMaxB + "% （各自保持，互不覆盖）");
        // 运行中切模式：立刻按新模式下发的音量，别让音量停在旧区间外面。
        if (surroundOn) {
            double shape = surroundCurve == CURVE_SINE
                    ? (1 - Math.cos(Math.PI * surroundT)) / 2.0
                    : surroundT;
            retargetVolumes(shape);
        }
        saveSurroundSettings();
        pokeStats();
        return "";
    }

    // ── 逐台模式的音量键：直接缩放 A / B 自己的区间（改的是它们各自的上限） ──

    /**
     * 音量键 → **只改每台自己的音量上限（max）**，下限（min）纹丝不动。
     *
     * <p>★ 2026-11-16 用户第三次校准：「我按一下应该加 5%」—— 不是按比例乘 1.05，
     * 而是<b>每按一下固定 +5 个百分点</b>（0~100 刻度上的 5）：
     * 例：A 45~80 按一次 → 45~85；B 20~60 按一次 → 20~65。
     * 每台都以自己的上限为基准各加 5，A、B 的绝对值仍然各自不同，不会被拉平。
     *
     * <p>约束：上限不超过 100%，且必须比下限至少高 10%（否则区间太窄、环绕开不起来）；
     * 顶到约束就原地不动。数值精确 ±5，不做网格吸附（吸附会让 72→75 看着像 +3）。
     *
     * @param stepPct 正 = 调大（如 +5），负 = 调小（如 -5），单位是"百分点"
     * @return 调整后 A 的上限（给界面回显用）；没动返回 -1
     */
    public int surroundNudgeVolumeRange(int stepPct) {
        if (!surroundPerSpeakerMode()) {
            return -1;      // 共用模式不接管音量键
        }
        int oldMaxA = surroundMaxA;
        int oldMaxB = surroundMaxB;
        surroundMaxA = stepCeil(surroundMinA, surroundMaxA, stepPct);   // 只动 max
        if (surroundHasB()) {
            surroundMaxB = stepCeil(surroundMinB, surroundMaxB, stepPct);
        }
        // ★ 实际生效的变化量（被 100% 或 min+10 夹住时可能比 stepPct 小，甚至为 0）。
        int deltaA = surroundMaxA - oldMaxA;
        int deltaB = surroundHasB() ? (surroundMaxB - oldMaxB) : 0;
        // 立刻按新区间下发（用户按一下要马上听到，不等下一拍）
        if (surroundOn) {
            // ★ 「按了听不出」：当前音量 = min + span×shape，上限 +5 在当前值上只体现
            //   5×shape。摆到中段/另一头时几乎无感，用户以为没反应。这里把实际生效的
            //   delta 直接加成"手推量"，当下就顶上去，900ms 内淡出回摆动曲线。
            if (deltaA != 0 || deltaB != 0) {
                if (deltaA != 0) {
                    surroundPushA = clampPush(surroundPushA + deltaA);
                }
                if (deltaB != 0) {
                    surroundPushB = clampPush(surroundPushB + deltaB);
                }
                surroundPushAt = SystemClock.elapsedRealtime();
            }
            double shape = surroundCurve == CURVE_SINE
                    ? (1 - Math.cos(Math.PI * surroundT)) / 2.0
                    : surroundT;
            retargetVolumes(shape);
        }
        saveSurroundSettings();
        Log.i(TAG, "音量键 " + (stepPct > 0 ? "+" : "") + stepPct + " 个百分点：只调上限 → A 上限 "
                + surroundMaxA + "%（区间 " + surroundMinA + "~" + surroundMaxA + "%）"
                + (surroundHasB() ? " B 上限 " + surroundMaxB + "%（区间 "
                        + surroundMinB + "~" + surroundMaxB + "%）" : "")
                + " 实测 delta A=" + deltaA + " B=" + deltaB);
        pokeStats();
        return surroundMaxA;
    }

    /**
     * 只动"上限"：在当前上限上加 {@code step} 个百分点，**min 不参与运算、不会被改**。
     *
     * <p>直接 {@code hi + step}，夹紧到 [min+10, 100]：不会把区间压得比 10% 更窄，
     * 也不会超过 100%。
     */
    private static int stepCeil(int min, int hi, int step) {
        // ★ 精确 +5 / -5：不吸附 5% 网格（否则 72 会被拉回 70，看起来只加了 3）。
        int nHi = hi + step;
        int floor = min + 10;              // 允许的最小上限（保证跨度 ≥10%）
        if (nHi < floor) {
            nHi = floor;
        }
        if (nHi > 100) {
            nHi = 100;
        }
        if (nHi < floor) {
            nHi = floor;                   // min 已经很高（比如 95）时优先保住 min+10
        }
        return nHi;
    }

    // ── 共用区间 ──

    public int surroundSharedMin() {
        return surroundSharedMin;
    }

    public int surroundSharedMax() {
        return surroundSharedMax;
    }

    public String surroundSharedText() {
        return surroundSharedMin + "%~" + surroundSharedMax + "%";
    }

    /** 设共用区间（5% 网格、自动 lo<=hi）。**只动共用这一套**，绝不碰逐台值。 */
    public void setSurroundSharedRange(int min, int max) {
        int lo = snap5(min);
        int hi = snap5(max);
        if (lo > hi) {
            int t = lo;
            lo = hi;
            hi = t;
        }
        surroundSharedMin = lo;
        surroundSharedMax = hi;
        if (surroundOn && surroundRangeMode == RANGE_SHARED) {
            double shape = surroundCurve == CURVE_SINE
                    ? (1 - Math.cos(Math.PI * surroundT)) / 2.0
                    : surroundT;
            retargetVolumes(shape);
        }
        saveSurroundSettings();
    }

    // ── 逐台区间（原始值，供"详细"子面板显示/编辑） ──

    public int surroundMinOf(boolean isA) {
        return isA ? surroundMinA : surroundMinB;
    }

    public int surroundMaxOf(boolean isA) {
        return isA ? surroundMaxA : surroundMaxB;
    }

    /** 给界面拼摘要用：「20%~80%」。 */
    public String surroundRangeText(boolean isA) {
        return surroundMinOf(isA) + "%~" + surroundMaxOf(isA) + "%";
    }

    // ── 当前【真正参与摆动】的区间（按模式取两套里的一套） ──

    /** A 侧生效下限。 */
    public int surroundEffectiveMinOf(boolean isA) {
        if (surroundRangeMode == RANGE_SHARED) {
            return surroundSharedMin;
        }
        return isA ? surroundMinA : surroundMinB;
    }

    /** A 侧生效上限。 */
    public int surroundEffectiveMaxOf(boolean isA) {
        if (surroundRangeMode == RANGE_SHARED) {
            return surroundSharedMax;
        }
        return isA ? surroundMaxA : surroundMaxB;
    }

    /** 兼容旧接口：A 侧生效区间。 */
    public int surroundMin() {
        return surroundEffectiveMinOf(true);
    }

    public int surroundMax() {
        return surroundEffectiveMaxOf(true);
    }

    public int surroundSpeed() {
        return surroundSpeed;
    }

    public int surroundCurve() {
        return surroundCurve;
    }

    public void setSurroundCurve(int curve) {
        surroundCurve = (curve == CURVE_SINE) ? CURVE_SINE : CURVE_LINEAR;
        saveSurroundSettings();
    }

    public int surroundHoldSec() {
        return surroundHoldSec;
    }

    /** 端点停留秒数（0~10）。★ 追加需求 2：线性/正弦两种曲线都生效。 */
    public void setSurroundHoldSec(int sec) {
        surroundHoldSec = Math.max(0, Math.min(10, sec));
        saveSurroundSettings();
    }

    /** 配对里有没有第二台（false = 单台模式，只让 A 自己起伏）。 */
    public boolean surroundHasB() {
        return surroundHostB != null && !surroundHostB.isEmpty();
    }

    /**
     * 最近一拍"本机是否真的在往外送声音"。
     *
     * <p>用户的第 1 条要求：广播没在传音频时环绕要自动暂停。判断依据不能只看
     * 「广播」开关 —— 开关开着但采集挂了（授权被收走、音源打不开）时其实一个包都没发。
     * 所以这里看 {@link AudioSend#isRunning()}：它true 才代表采集线程活着、真的在发。
     */
    public boolean surroundBroadcasting() {
        return surroundBroadcasting;
    }

    private boolean isReallyBroadcasting() {
        if (!broadcastOn) {
            return false;
        }
        AudioSend s = sender;
        return s != null && s.isRunning();
    }

    /** 这一台当前被环绕驱动的音量（-1 = 不在配对里）。 */
    public int surroundVolOf(String host) {
        if (host == null) {
            return -1;
        }
        if (host.equals(surroundHostA)) {
            return surroundVolA;
        }
        if (host.equals(surroundHostB)) {
            return surroundVolB;
        }
        return -1;
    }

    /** 环绕运行时这两台的音量滑杆必须锁住，否则手动拖动会和摆动打架。 */
    public boolean surroundLocks(String host) {
        return surroundOn && host != null
                && (host.equals(surroundHostA) || host.equals(surroundHostB));
    }

    /** 5% 网格化（四舍五入，夹在 0~100）。 */
    private static int snap5(int v) {
        int s = Math.round(v / 5f) * 5;
        return Math.max(0, Math.min(100, s));
    }

    /** 选配对。传空字符串 = 取消该侧。同一台不能同时占 A/B（后来的会顶掉对方）。 */
    public void setSurroundPair(String a, String b) {
        String na = a == null ? "" : a;
        String nb = b == null ? "" : b;
        if (!na.isEmpty() && na.equals(nb)) {
            nb = "";        // 不许同一台占两边
        }
        surroundHostA = na;
        surroundHostB = nb;
        saveSurroundSettings();
        pokeStats();
    }

    /** 设摆动区间；自动保证 min <= max，并对齐到 5% 网格。 */
    public void setSurroundRange(int min, int max) {
        setSurroundRangeFor(true, min, max);
        setSurroundRangeFor(false, min, max);
    }

    /**
     * ★ 追加需求 3：给**某一侧**单独设区间（A、B 各一套，互不影响）。
     *
     * <p>运行中改区间时按当前归一化进度 t 立刻重算两边音量，避免"改完区间后
     * 音量卡在新区间外面、要等摆到边界才回来"。
     */
    public void setSurroundRangeFor(boolean isA, int min, int max) {
        int lo = snap5(min);
        int hi = snap5(max);
        if (lo > hi) {
            int t = lo;
            lo = hi;
            hi = t;
        }
        if (isA) {
            surroundMinA = lo;
            surroundMaxA = hi;
        } else {
            surroundMinB = lo;
            surroundMaxB = hi;
        }
        if (surroundOn) {
            double shape = surroundCurve == CURVE_SINE
                    ? (1 - Math.cos(Math.PI * surroundT)) / 2.0
                    : surroundT;
            retargetVolumes(shape);
        }
        saveSurroundSettings();
    }

    /** 手推量上限（±100 个百分点，实际用不到这么大，防止连按累积成离谱值）。 */
    private static int clampPush(int p) {
        return Math.max(-100, Math.min(100, p));
    }

    /**
     * 当前这一刻还有多少手推量在生效（线性淡出到 0）。
     *
     * <p>{@link #SURROUND_PUSH_MS} 之后完全归零，摆动曲线接管，不会永久偏移。
     */
    private int pushNow(int push, long now) {
        if (push == 0) {
            return 0;
        }
        long el = now - surroundPushAt;
        if (el <= 0 || el >= SURROUND_PUSH_MS) {
            return 0;
        }
        return (int) Math.round(push * (1.0 - (double) el / SURROUND_PUSH_MS));
    }

    /** 按归一化形状把两边音量重算一遍并下发（区间被改动时用）。 */
    private void retargetVolumes(double shape) {
        // ★ 按当前模式的"生效区间"重算（共用模式两侧同区间，独立模式各用各的）
        long now = SystemClock.elapsedRealtime();
        int pushA = pushNow(surroundPushA, now);
        int pushB = pushNow(surroundPushB, now);
        int aMin = surroundEffectiveMinOf(true);
        int aMax = surroundEffectiveMaxOf(true);
        int spanA = aMax - aMin;
        int vA = aMin + (int) Math.round(spanA * shape) + pushA;
        vA = Math.max(aMin, Math.min(aMax, vA));
        surroundVolA = vA;
        remoteSetPeerVolume(surroundHostA, vA);
        if (surroundHasB()) {
            int bMin = surroundEffectiveMinOf(false);
            int bMax = surroundEffectiveMaxOf(false);
            int spanB = bMax - bMin;
            int vB = bMin + (int) Math.round(spanB * (1.0 - shape)) + pushB;
            vB = Math.max(bMin, Math.min(bMax, vB));
            surroundVolB = vB;
            remoteSetPeerVolume(surroundHostB, vB);
        }
    }

    /** 摆动速度（每秒音量变化百分比，1~100）。 */
    public void setSurroundSpeed(int pctPerSec) {
        surroundSpeed = Math.max(1, Math.min(100, pctPerSec));
        saveSurroundSettings();
    }

    // ══════════════════════════════════════════════════════════════════
    // ★ 2026-11-16（用户：「软件切后台以后不生效」）：后台音量键
    //
    //  Activity 的 onKeyDown 只在界面可见时才收得到音量键；App 一切到后台，
    //  按键就回到系统手里，A/B 上限一动不动。
    //
    //  这里补一条后路：注册系统「音量已改变」广播，后台按下音量键时系统会先改
    //  媒体音量 → 我们收到广播，把这"一格"翻译成 A/B 上限的 ±5，并立刻把系统音量
    //  还原回去（所以用户看到的系统音量还是原样）。
    //
    //  注意：前台时 Activity 已经吃掉了按键，系统音量没变 → 不会触发这条；
    //  我们自己 setStreamVolume 还原时会产生一次广播，用时间窗忽略掉，避免死循环。
    // ══════════════════════════════════════════════════════════════════

    private static final String ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    private static final String EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";

    /**
     * 每条音量流上一次的值（下标 = streamType）。
     *
     * <p>★ 关键：后台按音量键时，系统改的<b>不一定是媒体流</b> —— 没有媒体在放的时候
     * 改的是通知流/铃声流（实测盒子后台按出来是 stream=5）。所以必须"每条流各记一份
     * 历史值"，否则换流那一刻没有基准，算不出用户是加还是减。
     */
    private final android.util.SparseIntArray bgVolHist = new android.util.SparseIntArray();
    /** 我们自己最后一次 setStreamVolume 定下去的值（按流）。回到这个值 = 回音，不是按键。 */
    private final android.util.SparseIntArray bgLastSet = new android.util.SparseIntArray();
    private final android.util.SparseLongArray bgLastSetAt = new android.util.SparseLongArray();
    /** 上一次真的处理了按键的时刻：同一次按键连发多条广播时，只算第一下。 */
    private long bgKeyAt;
    /** 诊断用：上一次打"入口被触发但没发现变化"日志的时刻（节流）。 */
    private long bgProbeAt;
    /** Settings.System 音量值的观察者（第二条通道，防广播被 ROM 拦）。 */
    private android.database.ContentObserver bgVolObserver;
    private boolean bgVolRegistered;

    private void registerBgVolumeKeys() {
        if (bgVolRegistered) {
            return;
        }
        try {
            android.content.IntentFilter f =
                    new android.content.IntentFilter(ACTION_VOLUME_CHANGED);
            app.registerReceiver(new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context c, android.content.Intent intent) {
                    handleBgVolumeChanged(intent);
                }
            }, f);
            bgVolRegistered = true;
            seedVolumeHistory();
            Log.i(TAG, "[SR-VOL] bg receiver ready streams=" + bgVolHist.size());
        } catch (Throwable t) {
            Log.w(TAG, "[SR-VOL] register failed", t);
        }
        // ★ 2026-11-16：第二条通道。小米/部分 ROM 对后台应用的 VOLUME_CHANGED
        //   广播有节流或干脆不投递，只靠广播会出现"按了没反应"。音量值最终都会写进
        //   Settings.System，用 ContentObserver 直接盯它，比广播更难被拦。
        //   两条通道都走同一个入口，靠"历史值是否真的变了"天然去重（先到的那条
        //   处理完会把历史对齐，后到的那条扫不到变化，直接返回）。
        try {
            if (bgVolObserver == null) {
                bgVolObserver = new android.database.ContentObserver(mainHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        handleBgVolumeChanged(null);
                    }
                };
                app.getContentResolver().registerContentObserver(
                        android.provider.Settings.System.CONTENT_URI, true, bgVolObserver);
                Log.i(TAG, "[SR-VOL] settings observer ready");
            }
        } catch (Throwable t) {
            Log.w(TAG, "[SR-VOL] observer failed", t);
        }
    }

    /** 把每条音量流当前值记进历史表，作为后面判断"加/减"的基准。 */
    private void seedVolumeHistory() {
        bgVolHist.clear();
        android.media.AudioManager am = audioManager();
        if (am == null) {
            return;
        }
        // 0..10 覆盖 VOICE_CALL / SYSTEM / RING / MUSIC / ALARM / NOTIFICATION …
        for (int st = 0; st <= 10; st++) {
            int v = readVol(am, st);
            if (v >= 0) {
                bgVolHist.put(st, v);
            }
        }
    }

    private android.media.AudioManager audioManager() {
        try {
            return (android.media.AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读某个流的音量；无效流/被策略挡住时返回 -1（不参与比较）。 */
    private static int readVol(android.media.AudioManager am, int stream) {
        try {
            return am.getStreamVolume(stream);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 挑出这次"按键动作"真正要处理的那一条流，并把顺带一起变了的流静默同步掉。
     *
     * <p>★ 2026-11-16 实测（k40g / MIUI）抓到的真凶：<b>按一下音量键，系统连发 3 条
     * VOLUME_CHANGED</b>（MUSIC / ALARM / ACCESSIBILITY 同时 2→3，间隔 6~17ms）。
     * 以前每收到一条就调一次上限 —— 一次按键被算成 +15%，几下就把上限顶到 100%，
     * 之后再按就完全没反应了。这也正是"有时候按了不动"的来源。
     *
     * <p>现在只认一条<b>主控流</b>（优先媒体流，其次广播里点名的那条，再次第一个），
     * 其余"伴随流"只把历史值更新掉、不触发调整。这样一来一次按键只算一次，
     * 而且第二批广播到达时历史已经全部对齐，天然去重。
     *
     * @param hint 广播里带的流类型；没有就传 -1
     * @return 主控流；没有流变化（回音）返回 -1
     */
    private int pickPrimaryStream(android.media.AudioManager am, int hint) {
        int[] movedStream = new int[11];
        int[] movedVal = new int[11];
        int n = 0;
        for (int st = 0; st <= 10; st++) {
            int v = readVol(am, st);
            if (v < 0) {
                continue;
            }
            int h = bgVolHist.get(st, Integer.MIN_VALUE);
            if (h == Integer.MIN_VALUE || v == h) {
                continue;
            }
            movedStream[n] = st;
            movedVal[n] = v;
            n++;
        }
        if (n == 0) {
            return -1;
        }
        int primary = -1;
        for (int i = 0; i < n; i++) {
            if (movedStream[i] == android.media.AudioManager.STREAM_MUSIC) {
                primary = i;
                break;
            }
        }
        if (primary < 0 && hint >= 0) {
            for (int i = 0; i < n; i++) {
                if (movedStream[i] == hint) {
                    primary = i;
                    break;
                }
            }
        }
        if (primary < 0) {
            primary = 0;
        }
        // 伴随流：只对齐历史，不参与上限调整
        for (int i = 0; i < n; i++) {
            if (i != primary) {
                bgVolHist.put(movedStream[i], movedVal[i]);
            }
        }
        return movedStream[primary];
    }

    private void handleBgVolumeChanged(android.content.Intent intent) {
        try {
            long now = SystemClock.elapsedRealtime();
            android.media.AudioManager am = audioManager();
            if (am == null) {
                return;
            }

            // ── ① 定位主控流（多条流一起变时只算一次） ──
            String src = intent == null ? "obs" : "bc";
            int hint = -1;
            if (intent != null) {
                try {
                    hint = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1);
                } catch (Throwable ignored) {
                }
            }
            int stream = pickPrimaryStream(am, hint);
            if (stream < 0) {
                // 诊断：通道通了、只是没发现变化（回音/无关设置变动）—— 0.5s 内只记一条
                if (now - bgProbeAt > 500) {
                    bgProbeAt = now;
                    Log.i(TAG, "[SR-VOL] tick src=" + src + " noChange on=" + surroundOn
                            + " per=" + surroundPerSpeakerMode());
                }
                return;
            }
            // 有变化：无条件记一条，方便现场定位
            Log.i(TAG, "[SR-VOL] HIT src=" + src + " stream=" + stream
                    + " on=" + surroundOn + " per=" + surroundPerSpeakerMode());
            // 同一次按键带出来的后续广播：90ms 内只处理第一下。
            // （人手连按最快也在 120ms 以上，不会误吞；这条只是防御性兜底。）
            if (now - bgKeyAt < 90) {
                int v = readVol(am, stream);
                if (v >= 0) {
                    bgVolHist.put(stream, v);   // 历史照样对齐，免得下次方向算错
                }
                return;
            }
            bgKeyAt = now;

            int cur = readVol(am, stream);
            if (cur < 0) {
                return;
            }
            int prev = bgVolHist.get(stream, -1);
            bgVolHist.put(stream, cur);
            if (prev < 0 || cur == prev) {
                return;
            }

            boolean takeover = surroundOn && surroundPerSpeakerMode();
            int step = cur > prev ? 5 : -5;
            Log.i(TAG, "[SR-VOL] changed stream=" + stream + " " + prev + "->" + cur
                    + " on=" + surroundOn + " per=" + surroundPerSpeakerMode());
            EventLog.log("SR-VOL", "key stream=" + stream + " " + prev + "->" + cur
                    + " step=" + step + " on=" + surroundOn
                    + " per=" + surroundPerSpeakerMode());

            // 只有在"环绕运行 + 逐台模式"时才接管；其它情况让系统音量正常工作
            if (!takeover) {
                return;
            }

            // ── ③ 还原系统音量 ──
            //   ★ 2026-11-16「有时候没反应」的根因：以前一律还原到 prev。
            //     prev 一旦贴在 0 或满格（别的 App 调过、或还原被 DND 挡掉后一路漂到顶），
            //     下一按同方向就没有可动空间 —— 系统音量压根不变、发不出广播，
            //     于是彻底哑掉。这里"贴边就退回中间"，永远给上下都留出空间。
            int maxVol = 8;
            try {
                maxVol = am.getStreamMaxVolume(stream);
            } catch (Throwable ignored) {
            }
            if (maxVol < 2) {
                maxVol = 2;
            }
            int restoreTo = prev;
            if (restoreTo <= 0 || restoreTo >= maxVol) {
                restoreTo = Math.max(1, (maxVol + 1) / 2);
            }
            try {
                am.setStreamVolume(stream, restoreTo, 0);
            } catch (Throwable ignored) {
            }
            // ★ 读回真实值再记历史：有些机型 setStreamVolume 会被 DND/音量策略挡掉，
            //   如果按"以为还原成了 restoreTo"来记，下一次按键的方向就会算反。
            int actual = readVol(am, stream);
            if (actual < 0) {
                actual = restoreTo;
            }
            bgVolHist.put(stream, actual);
            bgLastSet.put(stream, actual);
            bgLastSetAt.put(stream, now);

            surroundNudgeVolumeRange(step);
            String msg = "A 上限 " + surroundMaxA + "%"
                    + (surroundHasB() ? "  ·  B 上限 " + surroundMaxB + "%" : "");
            toastOverApp(msg);
            Log.i(TAG, "[SR-VOL] bg key step=" + step + " A=" + surroundMaxA
                    + " B=" + surroundMaxB + " restored=" + actual);
            EventLog.log("SR-VOL", "max A=" + surroundMaxA + " B=" + surroundMaxB
                    + " restored=" + actual);
        } catch (Throwable t) {
            Log.w(TAG, "[SR-VOL] handle failed", t);
        }
    }

    /** 应用在后台时也要能弹出来（Toast 挂在 Application 上下文，不依赖 Activity）。 */
    private void toastOverApp(final String msg) {
        try {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        android.widget.Toast.makeText(app, msg,
                                android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /**
     * 开始环绕。返回空串 = 成功；否则是给人看的原因。
     *
     * <p>开头就把两台当前音量快照下来 —— 停止时原样恢复，用户的手动设置不会被吃掉。
     */
    public String startSurround() {
        String a = surroundHostA;
        String b = surroundHostB == null ? "" : surroundHostB;
        // ★ 2026-11-15 追加需求 3：不再强制两台 —— 只选 A 也能开（单台自己起伏）。
        if (a == null || a.isEmpty()) {
            return "先选一台音箱（A）";
        }
        if (!b.isEmpty() && a.equals(b)) {
            return "A 和 B 不能是同一台";
        }
        if (surroundOn) {
            return "";
        }
        // ★ 用【当前模式】的生效区间校验（共用模式两侧同一套；独立模式各查各的）。
        int aMin = surroundEffectiveMinOf(true);
        int aMax = surroundEffectiveMaxOf(true);
        int bMin = surroundEffectiveMinOf(false);
        int bMax = surroundEffectiveMaxOf(false);
        int spanA = aMax - aMin;
        if (spanA < 10) {
            return "A 的音量区间太窄了（至少留 10%）";
        }
        if (!b.isEmpty() && bMax - bMin < 10) {
            return "B 的音量区间太窄了（至少留 10%）";
        }
        surroundSnapA = peerOutVolume(a);
        surroundSnapB = b.isEmpty() ? -1 : peerOutVolume(b);

        int start = surroundSnapA >= 0 ? surroundSnapA : (aMin + aMax) / 2;
        start = Math.max(aMin, Math.min(aMax, snap5(start)));
        // 用归一化进度起步：起点在区间偏上就往下摆，反之往上。
        surroundT = (start - aMin) / (double) spanA;
        if (surroundT < 0) {
            surroundT = 0;
        } else if (surroundT > 1) {
            surroundT = 1;
        }
        surroundDir = surroundT >= 0.5 ? -1 : 1;
        surroundVolA = start;
        if (!b.isEmpty()) {
            int spanB = bMax - bMin;
            surroundVolB = bMin + (int) Math.round(spanB * (1.0 - surroundT));
            surroundVolB = Math.max(bMin, Math.min(bMax, surroundVolB));
        } else {
            surroundVolB = -1;
        }
        surroundAcc = 0f;
        surroundHoldUntil = 0;
        // ★ 手推量清零：上一次运行残留的推力不能带进新一轮摆动。
        surroundPushA = 0;
        surroundPushB = 0;
        surroundPushAt = 0;
        // 开局就把"是否在广播"定下来，第一拍之前界面上的暂停提示就是准的。
        surroundBroadcasting = isReallyBroadcasting();
        surroundOn = true;
        // ★ 2026-11-16：环绕一开就让媒体会话接管音量键（后台/前台都一样管用）。
        //   只有"逐台模式"才真的有 A/B 上限可调，共用模式在回调里会走"调系统音量"。
        ensureMediaSession();
        setSessionRemoteVolume(true);
        startSurroundTick();
        Log.i(TAG, "环绕开始：" + a + (b.isEmpty() ? "（单台）" : " ⇄ " + b)
                + " 区间模式=" + (surroundRangeMode == RANGE_PER_SPEAKER ? "独立" : "共用")
                + " A=" + aMin + "~" + aMax + "%"
                + (b.isEmpty() ? "" : " B=" + bMin + "~" + bMax + "%")
                + " 速度 " + surroundSpeed + "%/s"
                + " 曲线 " + (surroundCurve == CURVE_SINE ? "正弦" : "线性")
                + (surroundHoldSec > 0 ? " 端点停留 " + surroundHoldSec + "s" : "")
                + " 原始音量 " + surroundSnapA + "/" + surroundSnapB);
        pokeStats();
        return "";
    }

    /** 停止环绕：停定时器 + 把两台音量恢复成开启前的快照。 */
    public void stopSurround() {
        if (!surroundOn) {
            return;
        }
        surroundOn = false;
        setSessionRemoteVolume(false);   // 音量键立刻归还系统
        if (surroundTick != null) {
            mainHandler.removeCallbacks(surroundTick);
        }
        // 恢复原始音量（音量是乘数，系统内部有 ramp，不会爆音）。
        if (surroundSnapA >= 0) {
            remoteSetPeerVolume(surroundHostA, surroundSnapA);
        }
        if (surroundSnapB >= 0) {
            remoteSetPeerVolume(surroundHostB, surroundSnapB);
        }
        Log.i(TAG, "环绕停止，恢复原始音量 " + surroundSnapA + "/" + surroundSnapB);
        surroundSnapA = -1;
        surroundSnapB = -1;
        surroundPushA = 0;
        surroundPushB = 0;
        surroundPushAt = 0;
        pokeStats();
    }

    private void startSurroundTick() {
        if (surroundTick == null) {
            surroundTick = new Runnable() {
                @Override
                public void run() {
                    if (!surroundOn) {
                        return;
                    }
                    try {
                        stepSurround();
                    } catch (Throwable t) {
                        Log.w(TAG, "环绕摆动异常", t);
                    }
                    mainHandler.postDelayed(this, SURROUND_TICK_MS);
                }
            };
        }
        mainHandler.removeCallbacks(surroundTick);
        mainHandler.post(surroundTick);
    }

    /** 一趟摆动：广播联动 → 端点停留 → 推进一步 → 按曲线算音量 → 下发。 */
    private void stepSurround() {
        // ① ★ 追加需求 1：本机没在往外送声音就原地暂停（音量保持，不推进也不下发）。
        //   只看 broadcastOn 不够 —— 开关开着但采集挂了时其实一个包都没发。
        boolean sending = isReallyBroadcasting();
        if (surroundBroadcasting != sending) {
            surroundBroadcasting = sending;
            Log.i(TAG, sending ? "环绕恢复摆动（本机正在广播）" : "环绕暂停（本机没在广播音频）");
        }
        if (!sending) {
            return;
        }

        long now = SystemClock.elapsedRealtime();
        // ② ★ 追加需求 2：端点停留对【两种曲线】都生效 —— 到边界先停 N 秒，再反向。
        //   ★ 2026-11-16：停留期间如果还有"音量键手推量"在淡出，要把它慢慢收回来，
        //     否则按一下音量键后音量会卡在推上去的位置，一直等停留结束才动。
        if (now < surroundHoldUntil) {
            if (pushNow(surroundPushA, now) == 0 && pushNow(surroundPushB, now) == 0) {
                return;
            }
            double held = surroundCurve == CURVE_SINE
                    ? (1 - Math.cos(Math.PI * surroundT)) / 2.0
                    : surroundT;
            retargetVolumes(held);
            return;
        }

        // ★ 追加需求 3：A、B 各有一套区间，取较大的一侧当"速度基准"，
        //   保证任何一台的实际变化率都不会超过设定值（区间相同时与旧版完全一致）。
        // ★ 2026-11-15：区间按【当前模式】取 —— 共用模式两侧同一套，独立模式各用各的。
        int aMin = surroundEffectiveMinOf(true);
        int aMax = surroundEffectiveMaxOf(true);
        int bMin = surroundEffectiveMinOf(false);
        int bMax = surroundEffectiveMaxOf(false);
        int spanA = aMax - aMin;
        int spanB = surroundHasB() ? (bMax - bMin) : 0;
        int refSpan = Math.max(spanA, spanB);
        if (refSpan <= 0) {
            return;
        }

        // ③ 推进归一化进度。速度按"平均 %/秒"折算成 t/秒，所以线性/正弦的平均速度一致。
        surroundT += (double) surroundSpeed * SURROUND_TICK_MS / 1000.0 * surroundDir / refSpan;
        if (surroundT >= 1.0) {
            surroundT = 1.0;
            surroundDir = -1;
            // ★ 追加需求 2：线性、正弦都支持端点停留（原来只给正弦）。
            if (surroundHoldSec > 0) {
                surroundHoldUntil = now + surroundHoldSec * 1000L;
            }
        } else if (surroundT <= 0.0) {
            surroundT = 0.0;
            surroundDir = 1;
            if (surroundHoldSec > 0) {
                surroundHoldUntil = now + surroundHoldSec * 1000L;
            }
        }

        // ④ 曲线映射：
        //    线性 = t 本身（匀速）；
        //    正弦 = (1-cos(πt))/2（中点最快、两端自然放慢，听感更像"荡"过去）。
        double shape = surroundCurve == CURVE_SINE
                ? (1 - Math.cos(Math.PI * surroundT)) / 2.0
                : surroundT;

        // ⑤ A 走 shape（下限→上限），B 走 1-shape（它的下限→它的上限），
        //    于是"A 到顶"的另一端正好是"B 到底"，反向联动天然成立，且两边跨度可不同。
        //    ★ 2026-11-16：再叠加正在淡出的"音量键手推量"，让按下去的那一刻就能听到。
        int pushA = pushNow(surroundPushA, now);
        int pushB = pushNow(surroundPushB, now);
        int vA = aMin + (int) Math.round(spanA * shape) + pushA;
        vA = Math.max(aMin, Math.min(aMax, vA));
        if (vA != surroundVolA) {
            surroundVolA = vA;
            remoteSetPeerVolume(surroundHostA, vA);
        }

        if (surroundHasB()) {
            int vB = bMin + (int) Math.round(spanB * (1.0 - shape)) + pushB;
            vB = Math.max(bMin, Math.min(bMax, vB));
            if (vB != surroundVolB) {
                surroundVolB = vB;
                remoteSetPeerVolume(surroundHostB, vB);
            }
        }
    }

    /** ★ 2026-11-13（用户要求）：全群最小的输出延迟 —— 面板延迟着色的对齐基准。
     *   最快的那台是绿基准，其它设备按落后毫秒数变色。没有数据返回 -1。 */
    public int minPeerOutDelay() {
        synchronized (peerLock) {
            int min = -1;
            for (Diagnosis.PeerStats ps : peerStats.values()) {
                int d = ps.outDelay;
                if (d >= 0 && (min < 0 || d < min)) {
                    min = d;
                }
            }
            return min;
        }
    }

    /**
     * 这一台现在的实时缓冲积压（毫秒）—— 界面上的"延迟测试"读数。
     *
     * <p>读法：<b>积压 ≈ 抖动目标 + 你加的延迟</b>。稳定不动 = 它的 Wi-Fi 跟得上，
     * 加延迟是安全的；一路往上飘 = 它在持续掉队，再加延迟只会更糟，该把它挪近路由器。
     */
    public int peerBufferMs(String host) {
        if (host == null || host.isEmpty()) {
            return 0;
        }
        // ★ 2026-11-13（用户：「其他设备就是接收端在收，却一直显示没在收」）：
        //   旧实现读的是【本机】AudioRecv 里来自 host 的那条流——广播时本机根本没从
        //   接收端收到音频流，必然恒为 0。改成读【那台设备自己上报】的接收缓冲：
        //   my_buf 优先（无条件上报、不绑定方向），老版本回退 buf_ms。
        //   附带好处：纯内存读取，UI 刷新不再碰 AudioRecv 的锁/统计计算（治卡）。
        synchronized (peerLock) {
            Diagnosis.PeerStats ps = peerStats.get(host);
            if (ps == null) {
                return 0;
            }
            if (SystemClock.elapsedRealtime() - ps.receivedAtMs > PEER_STATS_KEEP_MS) {
                return 0;   // 过期数据不冒充实时
            }
            if (ps.myBufMs > 0) {
                return ps.myBufMs;
            }
            return Math.max(0, ps.bufMs);
        }
    }

    /** 这一台累计迟到 / 丢包数（"延迟测试"里判断链路好不好）。返回 {late, lost}。 */
    public long[] peerPacketStats(String host) {
        AudioRecv r = receiver;
        if (r == null) {
            return new long[]{0, 0};
        }
        try {
            return r.hostPacketStats(host);
        } catch (Throwable t) {
            return new long[]{0, 0};
        }
    }

    // ── 全局矩阵 ──

    public int channelMode() { return channelMode; }

    public void setChannelMode(int m) {
        int prev = channelMode;
        channelMode = (m >= ChannelRouter.MODE_STEREO && m <= ChannelRouter.MODE_SEND_MUTE)
                ? m : ChannelRouter.MODE_STEREO;
        // ★ 2026-11-12：「传送静音」= 送声 + 手机静音 的一键入口。
        //   进来时把送声档位推到第 2 档（系统媒体音量临时降 0，采集/发送照跑）；
        //   离开时退回第 1 档（外放），把系统音量还原 —— 否则用户会一直哑在 0 档上。
        if (channelMode == ChannelRouter.MODE_SEND_MUTE) {
            if (sendModePref != 2) {
                setSendMode(2);
            }
        } else if (prev == ChannelRouter.MODE_SEND_MUTE && sendModePref == 2) {
            setSendMode(1);
        }
        saveChannelSettings();
        applyChannelNow();
        // ★ 2026-11-12：改了本机播放状态就立刻把统计包推出去一轮。
        //   不 poke 的话别的手机最多要等 2 秒才看见按钮变化 —— 用户报的
        //   「其他设备的 app 显示没有实时同步」就是这 2 秒。
        pokeStats();
    }

    public float channelBalance() { return channelBalance; }

    public void setChannelBalance(float b) {
        channelBalance = Math.max(-1f, Math.min(1f, b));
        saveChannelSettings();
        applyChannelNow();
    }

    public float channelLeftGain() { return channelLeftGain; }

    public void setChannelLeftGain(float g) {
        channelLeftGain = Math.max(0f, Math.min(1f, g));
        saveChannelSettings();
        applyChannelNow();
    }

    public float channelRightGain() { return channelRightGain; }

    public void setChannelRightGain(float g) {
        channelRightGain = Math.max(0f, Math.min(1f, g));
        saveChannelSettings();
        applyChannelNow();
    }

    // ── 本机播放音量（自己调 + 别人远程调，同一份值）──

    /** 本机播放音量 0~1（只作用于我们这条 AudioTrack，不动系统媒体音量）。 */
    public float outVolume() { return outVolume; }

    public void setOutVolume(float v) {
        // 0~1（2026-11-12 曾试过放到 2.0 做放大，用户说不是这个问题，已撤回）。
        outVolume = Math.max(0f, Math.min(1f, v));
        saveChannelSettings();
        AudioRecv r = receiver;
        if (r != null) {
            try {
                r.setOutVolume(outVolume);
            } catch (Throwable t) {
                Log.w(TAG, "设置本机播放音量失败", t);
            }
        }
        // 让别的手机那张卡片上的「它的音量」立刻跟上（而不是等下一轮 2 秒）。
        pokeStats();
    }

    /** 一键还原：立体声 / 音量 100%（平衡与左右独立音量一并回到默认，界面上已不提供这两个滑杆）。 */
    public void resetChannels() {
        boolean wasRelayMute = channelMode == ChannelRouter.MODE_SEND_MUTE;
        channelMode = ChannelRouter.MODE_STEREO;
        channelBalance = 0f;
        channelLeftGain = 1f;
        channelRightGain = 1f;
        outVolume = 1f;
        // ★ 2026-11-12：「还原」是"回到默认"，默认里不该留着一个会静音的送声档。
        //   否则点了还原，界面写着立体声，下一次送声音却还是第 2 档（手机哑着）——
        //   这正是用户最讨厌的那种"看着对、用着不对"。
        if (wasRelayMute && sendModePref == 2) {
            setSendMode(1);
        }
        saveChannelSettings();
        applyChannelNow();
        pokeStats();
    }

    // ── 按设备分配 ──

    /** 某一台来源设备现在进哪只耳朵（0 双 / 1 左 / 2 右）。 */
    public int peerChannel(String host) {
        if (host == null) {
            return 0;
        }
        synchronized (peerChannelMap) {
            Integer v = peerChannelMap.get(host);
            return v == null ? 0 : v;
        }
    }

    /** 把某一台来源设备指定到左耳 / 右耳 / 双耳，立刻生效并记住。 */
    public void setPeerChannel(String host, int ch) {
        if (host == null || host.isEmpty()) {
            return;
        }
        int v = (ch == 1 || ch == 2) ? ch : 0;
        synchronized (peerChannelMap) {
            if (v == 0) {
                peerChannelMap.remove(host);
            } else {
                peerChannelMap.put(host, v);
            }
        }
        saveChannelSettings();
        AudioRecv r = receiver;
        if (r != null) {
            try {
                r.setHostChannel(host, v);
            } catch (Throwable t) {
                Log.w(TAG, "设置逐设备声道失败", t);
            }
        }
    }

    /**
     * ★ 2026-11-12：监控页那几个"总账"数字，给可视化界面用。
     *
     * <p>用户原话：「监控里面用可视化的界面」—— 原来那一段等宽文字在手机上要左右拖才看得全，
     * 数字还挤在一起。这里把同一批数字按"一格放一条"交给界面去画，值仍然全部来自既有查询接口，
     * 不另算一套（项目铁律：界面不许自己编数字）。
     *
     * @return 固定 4 条：{@code [0]} 发现 / {@code [1]} 接收 / {@code [2]} 发送 / {@code [3]} 电平
     */
    public String[] monitorCounters() {
        String[] out = new String[4];
        Discovery d = discovery;
        if (d == null || !d.isRunning()) {
            out[0] = "发现没在跑";
        } else {
            out[0] = "信标 收 " + d.beaconsReceived() + " · 发 " + d.beaconsSent()
                    + " · 端口 " + d.boundPort() + (d.multicastLockHeld() ? " · 组播 ✓" : " · 组播 ✗");
        }
        AudioRecv.Stats st = recvStats();
        if (st == null || !receiverRunning()) {
            out[1] = "接收器没跑（没点过收听、也没开听广播）";
        } else {
            out[1] = "收 " + st.received + " 包 · 丢 " + st.lost + " · 迟到 " + st.late
                    + " · 缓冲 " + st.bufferedMs + "ms · 流 " + st.streamCount;
        }
        int failSum = 0;
        for (SendFanout.TargetStats ts : sendTargetStats()) {
            failSum += ts.sendErrors;
        }
        if (!senderRunning()) {
            out[2] = "没在送（还没点过「送声音」）";
        } else {
            out[2] = "送 " + sendTargetCount() + " 台 · " + sentPackets() + " 包 / "
                    + bytesText(sentBytes()) + " · 失败 " + failSum;
        }
        out[3] = "收 " + pctText(recvPeak()) + " · 送 " + pctText(sendPeak());
        return out;
    }

    /**
     * ★ 2026-11-13 用户：「监控里面也应该加入我广播本机的状态监控」——
     * 监控页顶部「本机广播」行的说明文字：音源 / 送给几台 / 流量 / 失败数。
     * 没在广播时返回空串（界面显示引导语）。
     */
    public String broadcastStatusText() {
        if (!isBroadcasting()) {
            return "";
        }
        int failSum = 0;
        for (SendFanout.TargetStats ts : sendTargetStats()) {
            failSum += ts.sendErrors;
        }
        String src = activeSourceKind() == AudioSend.SRC_PLAYBACK ? "本机声音" : "麦克风";
        return "音源 " + src + " · 送 " + sendTargetCount() + " 台 · "
                + sentPackets() + " 包 / " + bytesText(sentBytes()) + " · 失败 " + failSum;
    }

    /** 字节数写成人看的（1.2 MB）—— 监控页每一格都要短。 */
    private static String bytesText(long n) {
        if (n < 1024) {
            return n + " B";
        }
        if (n < 1024L * 1024L) {
            return String.format(java.util.Locale.US, "%.1f KB", n / 1024.0);
        }
        return String.format(java.util.Locale.US, "%.1f MB", n / (1024.0 * 1024.0));
    }

    /**
     * 诊断/界面上的一行真实状态（如实显示，不编数字）。
     *
     * <p>★ 2026-11-12 用户：「左 100% / 右 100% / 平衡居中 感觉没有用，删掉」——
     * 界面上那两个滑杆已经去掉，这一行也不再写它们，只留"模式 + 音量"这两个还在起作用的东西。
     */
    public String stereoText() {
        String s = ChannelRouter.modeText(channelMode)
                + " · 音量 " + Math.round(outVolume * 100f) + "%";
        if (channelMode == ChannelRouter.MODE_SEND_MUTE) {
            s += sendingActive
                    ? " · 正在送 " + sendingCount() + " 台，本机不出声"
                    : " · 现在没在送，点某台「送声音」或「广播我的声音」";
        } else if (channelMode == ChannelRouter.MODE_MUTE) {
            s += sendingActive ? " · 只转不发本机" : "";
        }
        return s;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ★ 2026-11-12：远程控制别的手机（声道模式 / 本机音量）+ 状态实时同步
    //
    //  用户原话：「app 里面设置了另外一台设备的左右声道后，其他设备的 app 显示没有
    //  实时同步变换按钮状态」「加入可以控制其他设备的音量」。
    //
    //  设计要点（为什么这么做，而不是"本地记一份猜的值"）：
    //    · 真值只有**那台设备自己**知道 —— 所以它每 2 秒在统计包里报 out_ch/out_vol
    //      （见 Diagnosis.outStateJson），所有 app 上的按钮都按这份值画。
    //    · 控制指令是**幂等的一条状态**（"你的输出 = 仅左 / 音量 = 40"），不是"加/减"，
    //      丢包最多晚一拍，重发一次就对上了 —— 局域网 UDP 不需要为它做可靠传输。
    //    · 走发现层的控制消息（{@link Discovery#sendControl}）：那一层一直活着，
    //      对面没在收听也收得到，不会出现"发了但对面接收器是停的"。
    //    · 只认声明了 {@code outctl:1} 的对端（手机↔手机）。电脑端不声明，
    //      界面上那两个控件就不出现 —— 不给用户"拖了没反应"的死控件。
    // ══════════════════════════════════════════════════════════════════════════

    /** 远程控制消息的类型标记（放在 JSON 的 {@code "t"} 里，和统计包的 "stats" 区分开）。 */
    private static final String REMOTE_TAG = "out_ctl";

    /** 这台设备能不能被远程改播放声道/音量（对端信标声明了 outctl:1）。 */
    public boolean peerSupportsOutCtl(String host) {
        Discovery.Peer p = peerByHost(host);
        return p != null && p.outCtl;
    }

    /**
     * 远程把某一台设成某种声道模式（立体声 / 静音 / 仅左 / 仅右 / 互换）。
     *
     * @return 空串 = 已排队发出；非空 = <b>没发出去的原因</b>，界面直接把它显示给用户。
     *         （★ 2026-11-12 真机教训：这条路径原来是**同步**在界面线程上发 UDP 的 ——
     *         Android 在 {@code DatagramSocket.send} 里直接抛
     *         {@code NetworkOnMainThreadException}，被 {@code Discovery.sendControl} 的
     *         {@code catch (RuntimeException)} 吞掉后返回 false，用户看到的就只有一句
     *         "没发出去：没连上它"，却完全不知道是"自己这台的问题"。现在改成后台线程发，
     *         并把原因分开写清楚。）
     */
    public String remoteSetPeerChannel(String host, int mode) {
        return sendOutCtl(host, mode, -1, -1);
    }

    /**
     * ★ 2026-11-12 用户：「点击本机静音后，又不能通过音量加减控制其他设备的音量了」——
     * 音量键在"本机哑着"的时候改为控制<b>正在收我声音的那几台</b>的播放音量。
     *
     * <p>这个方法是那件事的落点：把当前值存在本机（重启记得），并逐个发 out_ctl 给
     * 正在送的设备（广播时就是所有在线设备）。返回<b>真的发出去了几台</b> ——
     * 0 表示现在根本没在送，调用方应改成调本机音量。
     */
    public int setRemoteVolumeAll(int percent) {
        int v = Math.max(0, Math.min(100, percent));
        remoteVol = v;
        channelPrefs().edit().putInt(PREF_REMOTE_VOL, v).apply();
        int sent = 0;
        boolean bcast = isBroadcasting();
        for (Discovery.Peer p : peers()) {
            if (p == null) {
                continue;
            }
            if (!bcast && !isSending(p)) {
                continue;
            }
            // 空串 = 已交给后台线程发出；非空是失败原因（对面版本太旧/不在线）。
            if (remoteSetPeerVolume(p.host, v).isEmpty()) {
                sent++;
            }
        }
        Log.i(TAG, "音量键 → 对方音量 " + v + "%，发往 " + sent + " 台");
        return sent;
    }

    /**
     * ★ 2026-11-16（用户）：简洁面板/后台按音量键应该是<b>相对增减</b> ——
     * 每台设备以<b>它自己当前音量</b>为基准各加/减固定百分点，互不拉平。
     *
     * <p>和环绕逐台模式的加减完全同一套逻辑：A 设 20%、B 设 30%，按一下"+" →
     * A 25%、B 35%，而不是旧版用 {@code remoteVol + dir*10} 算出一个统一值再
     * {@link #setRemoteVolumeAll(int)} 把全部设备刷成同一个数。
     *
     * <p>基准取对端自己报上来的音量（{@link #peerOutVolume(String)}），
     * 它没报（老版本/暂时没回报）才退回上次设置值 {@link #remoteVol}。
     *
     * @param deltaPct 正 = 加，负 = 减，单位"百分点"（环绕同款，固定 5）
     * @return 各台调整后的音量（如 "A 25%   B 35%"，A/B/C 编号一行排完）；空串 = 没发出去
     */
    public String nudgeRemoteVolumesAll(int deltaPct) {
        StringBuilder sb = new StringBuilder();
        int sent = 0;
        boolean bcast = isBroadcasting();
        for (Discovery.Peer p : peers()) {
            if (p == null || p.host == null || p.host.isEmpty()) {
                continue;
            }
            if (!bcast && !isSending(p)) {
                continue;
            }
            // 基准优先级：本机最后下发值（最及时）> 对端回报 > 上次统一值。
            Integer shadow = peerVolShadow.get(p.host);
            int cur = shadow != null ? shadow : peerOutVolume(p.host);
            if (cur < 0) {
                cur = remoteVol;    // 都没报过：退回上次设的值当基准
            }
            int nv = Math.max(0, Math.min(100, cur + deltaPct));
            if (remoteSetPeerVolume(p.host, nv).isEmpty()) {
                // ★ 像环绕那样编号 A/B/C，短名一行排完（小米 Toast 多行会被挤，短名才看得全）。
                if (sb.length() > 0) {
                    sb.append("   ");
                }
                sb.append((char) ('A' + sent)).append(" ").append(nv).append("%");
                sent++;
            }
            Log.i(TAG, "音量键相对调节 " + p.label() + " " + cur + "->" + nv + "%");
        }
        return sent > 0 ? sb.toString() : "";
    }

    /** 上一次用音量键设的"对方音量"（0~100）。 */
    public int remoteVolume() {
        return remoteVol;
    }

    /** 远程把某一台的播放音量设成 {@code percent}%（0~100）。返回同上（空串 = 已发出）。 */
    public String remoteSetPeerVolume(String host, int percent) {
        int v = Math.max(0, Math.min(100, percent));
        peerVolShadow.put(host, v);   // 记住最后下发值，供"相对增减"当基准
        return sendOutCtl(host, -1, v, -1);
    }

    /**
     * ★ 2026-11-13：远程把某一台的<b>输出延迟</b>设成 {@code ms} 毫秒（0~300）。
     *
     * <p>「它的延迟」滑杆的落点 —— 和 remoteSetPeerVolume 同一套 out_ctl 机制：
     * 拖动时那台自己的「本机延迟」滑杆当场就动，然后它把新值报回来。
     * 返回同上（空串 = 已发出；非空 = 没发出去的原因）。
     */
    public String remoteSetPeerDelay(String host, int ms) {
        return sendOutCtl(host, -1, -1,
                Math.max(0, Math.min(AudioRecv.MAX_EXTRA_DELAY_MS, ms)));
    }

    /**
     * 拼一条远程控制消息，交给后台线程发给对端的发现端口。
     *
     * <p>{@code ch} / {@code vol} / {@code delay} 传 -1 表示"这一项不动" —— 于是
     * "只改音量"的指令不会顺手把对面的声道模式改回立体声（反过来也一样）。
     */
    private String sendOutCtl(String host, int ch, int vol, int delay) {
        final Discovery d = discovery;
        if (d == null) {
            return "本机的发现服务没起来（先退出重进一次 App）";
        }
        if (host == null || host.isEmpty()) {
            return "这台设备没有地址";
        }
        Discovery.Peer p = peerByHost(host);
        if (p == null) {
            return "发现列表里已经没有它了（它刚换了 IP 或断线）";
        }
        if (!p.outCtl) {
            return "对方是旧版本/电脑端，不支持被远程改声道/音量/延迟";
        }
        final String body = buildOutCtlJson(ch, vol, delay, -1);
        final int port = p.discPort > 0 ? p.discPort : Proto.DEFAULT_DISCOVERY_PORT;
        final String addr = host;
        final String name = p.label();
        try {
            outCtlExec().execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        boolean ok = d.sendControl(body, addr, port);
                        Log.i(TAG, "远程设置 " + (ok ? "已发出" : "发送失败") + " → " + addr
                                + ":" + port + "：" + body);
                    } catch (Throwable t) {
                        Log.w(TAG, "远程控制消息发送异常：" + addr, t);
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "远程控制任务排队失败", t);
            return "内部线程忙不过来：" + t;
        }
        Log.i(TAG, "已排队远程设置 " + name + "：ch=" + ch + " vol=" + vol + " delay=" + delay);
        return "";
    }

    /** 拼远程控制的 JSON 正文（{@code {"t":"out_ctl",...}}）。 */
    private String buildOutCtlJson(int ch, int vol, int delay, int buf) {
        return buildOutCtlJson(ch, vol, delay, buf, -1);
    }

    /**
     * ★ 2026-11-17：补包协议跨设备同步 —— 多带一个 nack 字段。
     * @param nack -1 = 本次不改协议；0 = 切冗余副本；1 = 切 NACK 重传。
     */
    private String buildOutCtlJson(int ch, int vol, int delay, int buf, int nack) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("{\"t\":\"").append(REMOTE_TAG).append('"');
        Discovery d = discovery;
        if (d != null && d.nodeId() != null && !d.nodeId().isEmpty()) {
            sb.append(",\"node\":\"").append(d.nodeId()).append('"');
        }
        String n = nodeName();
        sb.append(",\"name\":\"").append(n == null ? "" : n.replace("\"", "")).append('"');
        if (ch >= 0) {
            sb.append(",\"ch\":").append(ch);
        }
        if (vol >= 0) {
            sb.append(",\"vol\":").append(vol);
        }
        if (delay >= 0) {
            sb.append(",\"delay\":").append(delay);
        }
        // ★ 2026-11-14：接收缓冲档位（毫秒）。-1 = 本次不改档位。
        if (buf >= 0) {
            sb.append(",\"buf\":").append(buf);
        }
        // ★ 2026-11-17：补包协议（0=冗余副本 1=NACK）。-1 = 本次不改协议。
        if (nack >= 0) {
            sb.append(",\"nack\":").append(nack);
        }
        sb.append(",\"ts\":").append(System.currentTimeMillis()).append('}');
        return sb.toString();
    }

    /**
     * 远程控制的发送线程（单线程、懒创建）。
     *
     * <p>为什么必须是独立线程：界面线程碰 Socket 会被系统直接拦下来（见
     * {@link #remoteSetPeerChannel} 的注释）。用单线程而不是每点一下 new 一个线程：
     * 拖音量滑杆时每秒能产生十几条指令，线程反复创建反而更贵，而且单线程天然保序 ——
     * 先"仅左"再"静音"，对面一定是按这个顺序收到的。
     */
    private java.util.concurrent.ExecutorService outCtlExec() {
        java.util.concurrent.ExecutorService ex = outCtlExec;
        if (ex != null) {
            return ex;
        }
        synchronized (peerLock) {
            if (outCtlExec == null) {
                outCtlExec = java.util.concurrent.Executors.newSingleThreadExecutor(
                        new java.util.concurrent.ThreadFactory() {
                            @Override
                            public Thread newThread(Runnable r) {
                                Thread t = new Thread(r, "out-ctl");
                                t.setDaemon(true);
                                return t;
                            }
                        });
            }
            return outCtlExec;
        }
    }

    /** 远程控制的发送线程（懒创建；见 {@link #outCtlExec()}）。 */
    private volatile java.util.concurrent.ExecutorService outCtlExec;

    /**
     * 收到别的手机的远程控制指令（由 {@link #handleRemoteOutCtl} 调用）。
     *
     * <p>当场生效 + 存盘：这台设备重启后仍然是"仅左 / 40%"，不会自己跳回默认 ——
     * 因为它就是这台设备的用户设置，只不过这次是别人替它按的。
     */
    private void applyRemoteOutCtl(int ch, int vol, int delay, int buf, int nack, String fromName) {
        // ★ 2026-11-17：先切协议（setNackEnabled 内部会联动切到新协议的默认档位，
        //   且 broadcast=false 防止 A→B→A）。下面收到的 buf 再覆盖成对方指定的那一档。
        if (nack >= 0) {
            setNackEnabled(nack == 1, false);
        }
        if (ch >= ChannelRouter.MODE_STEREO && ch <= ChannelRouter.MODE_SEND_MUTE) {
            setChannelMode(ch);
        }
        if (vol >= 0) {
            setOutVolume(vol / 100f);
        }
        if (delay >= 0) {
            // ★ 2026-11-17 晚：用"只在本机生效"的入口 —— 直接调 setOutDelayMs 会再广播一次，
            //   多台设备之间就会 A→B→A 无限互相下发。
            applyOutDelayLocal(delay);
        }
        // ★ 2026-11-14（用户要求档位同步）：本机跟着对方一起改。
        //   注意传 broadcast=false —— 否则两台机器会 A→B→A 无限互相下发。
        if (buf >= 0) {
            setRecvBufMs(buf, false);
        }
        Log.i(TAG, "收到远程设置（来自 " + fromName + "）：ch=" + ch + " vol=" + vol
                + " delay=" + delay + " buf=" + buf + " nack=" + nack);
        // 自己再报一轮：让"发指令的那台"和"第三台"立刻看到按钮已经变了（不用等 2 秒）。
        pokeStats();
    }

    /**
     * 尝试把一条控制消息当"远程设置"处理。
     *
     * @return true = 它确实是远程设置（调用方别再把这份负载当统计包解析）
     */
    private boolean handleRemoteOutCtl(String host, String json) {
        if (json == null || json.indexOf(REMOTE_TAG) < 0) {
            return false;
        }
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            if (!REMOTE_TAG.equals(o.optString("t", ""))) {
                return false;
            }
            int ch = o.has("ch") ? o.optInt("ch", -1) : -1;
            int vol = o.has("vol") ? o.optInt("vol", -1) : -1;
            int delay = o.has("delay") ? o.optInt("delay", -1) : -1;
            // ★ 2026-11-14：别人点了缓冲档位，本机跟着改（-1 = 本次不改）。
            int buf = o.has("buf") ? o.optInt("buf", -1) : -1;
            // ★ 2026-11-17：别人切了补包协议（-1 = 本次不改；0=冗余副本 1=NACK）。
            int nack = o.has("nack") ? o.optInt("nack", -1) : -1;
            String fromName = o.optString("name", "");
            if (fromName.isEmpty()) {
                fromName = nameOrHost(host);
            }
            applyRemoteOutCtl(ch, vol, delay, buf, nack, fromName);
            return true;
        } catch (Throwable t) {
            // 解析失败就当不是远程设置（继续走统计那条路，不能把正常的统计包吞掉）
            Log.w(TAG, "远程设置消息解析失败（已忽略）：" + t);
            return false;
        }
    }

    /** 这一台现在报上来的本机声道模式；-1 = 它没报（老版本 / 电脑端）。 */
    public int peerOutChannel(String host) {
        synchronized (peerLock) {
            Diagnosis.PeerStats ps = peerStats.get(host);
            return ps == null ? -1 : ps.outCh;
        }
    }

    /** 这一台现在报上来的本机音量百分比；-1 = 它没报。 */
    public int peerOutVolume(String host) {
        synchronized (peerLock) {
            Diagnosis.PeerStats ps = peerStats.get(host);
            return ps == null ? -1 : ps.outVol;
        }
    }
}
