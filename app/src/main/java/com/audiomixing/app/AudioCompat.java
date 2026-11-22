package com.audiomixing.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.os.Build;
import android.util.Log;

/**
 * 音频对象的兼容构造（2026-11-12：为了能装在 Android 5.1 的电视盒子上，minSdk 24 → 22）。
 *
 * <p><b>为什么需要这样一个类</b>：{@code AudioTrack.Builder} / {@code AudioRecord.Builder} /
 * {@code AudioFormat.Builder} 都是 <b>API 23</b> 才有的。以前 minSdk 是 24，直接 new 没问题；
 * 要支持 5.1（API 22）就必须换回那套从 API 1 就存在的构造函数：
 * <pre>
 *   new AudioTrack(streamType, sampleRate, channelConfig, audioFormat, bufferSize, mode)
 *   new AudioRecord(audioSource, sampleRate, channelConfig, audioFormat, bufferSize)
 * </pre>
 *
 * <p><b>两套写法的等价性</b>（踩过的坑都写在这里）：
 * <ul>
 *   <li>{@code AudioFormat.CHANNEL_OUT_STEREO}（12）与 {@code CHANNEL_CONFIGURATION_STEREO}（3）
 *       数值不同，但旧构造器要的是"通道配置"，正好就是 {@code CHANNEL_OUT_*} 这套常量；
 *       输入侧旧构造器同样吃 {@code CHANNEL_IN_*}。所以这里原样传，不做换算。</li>
 *   <li>旧构造器用的是<b>流类型</b>（STREAM_MUSIC），不是 AudioAttributes —— 两者在系统里
 *       等价（AudioAttributes.USAGE_MEDIA 会映射到 STREAM_MUSIC），音量曲线也一致。</li>
 *   <li>低延迟通道（{@code PERFORMANCE_MODE_LOW_LATENCY}）是 API 26 的，旧机器上直接不提，
 *       只是延迟略大，功能不受影响。</li>
 *   <li>采本机声音（{@code AudioPlaybackCaptureConfiguration}）是 API 29 的，
 *       5.1 上根本不存在 —— 那一路由 {@code AudioSend.playbackSupported()} 挡在外面，
 *       所以这里<b>故意不</b>提供"带配置的 AudioRecord"兼容构造：老机器只能用麦克风。</li>
 * </ul>
 */
public final class AudioCompat {

    private static final String TAG = "AudioMixing";

    private AudioCompat() {
    }

    /**
     * 建一条播放用 AudioTrack（48 kHz 立体声 PCM16 的常用形态由调用方给参数）。
     *
     * @param rate        采样率
     * @param channelMask 输出通道掩码（{@link AudioFormat#CHANNEL_OUT_STEREO} 等）
     * @param bufBytes    缓冲字节数
     * @param lowLatency  是否希望走低延迟通道（API 26+ 才有效，老机器自动忽略）
     * @return 已建好的 AudioTrack（可能是 STATE_UNINITIALIZED，调用方自己要判一次）
     */
    public static AudioTrack newTrack(int rate, int channelMask, int bufBytes, boolean lowLatency) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioFormat fmt = new AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(channelMask)
                    .build();
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            AudioTrack.Builder b = new AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(bufBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM);
            if (lowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
                } catch (Throwable t) {
                    // 厂商 ROM 不认这个模式：忽略，只是延迟略大
                }
            }
            return b.build();
        }
        // ── API 22 及以下：老构造器 ──
        try {
            return new AudioTrack(AudioManager.STREAM_MUSIC, rate, channelMask,
                    AudioFormat.ENCODING_PCM_16BIT, bufBytes, AudioTrack.MODE_STREAM);
        } catch (Throwable t) {
            // AudioTrack 没有公开的无参构造（那是 @hide 的），所以失败只能返回 null；
            // 调用方本来就按"拿不到就当成没有输出设备"处理（见 AudioRecv.openTrack）。
            Log.w(TAG, "老构造器建 AudioTrack 失败", t);
            return null;
        }
    }

    /**
     * 建一条麦克风 AudioRecord（5.1 上唯一能用的采集方式）。
     *
     * @param source      音源（{@link android.media.MediaRecorder.AudioSource} 里的常量）
     * @param rate        采样率
     * @param channelMask 输入通道掩码（{@link AudioFormat#CHANNEL_IN_MONO}/{@code IN_STEREO}）
     * @param bufBytes    缓冲字节数
     */
    public static AudioRecord newMicRecord(int source, int rate, int channelMask, int bufBytes) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioFormat af = new AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(channelMask)
                    .build();
            return new AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(af)
                    .setBufferSizeInBytes(bufBytes)
                    .build();
        }
        // ★ API 22 及以下：旧构造器。注意它没有 setAudioAttributes 这一步
        //   （原来那套 Builder 写法里，AudioSend 也刻意没设属性，理由见那边的注释）。
        try {
            return new AudioRecord(source, rate, channelMask,
                    AudioFormat.ENCODING_PCM_16BIT, bufBytes);
        } catch (RuntimeException t) {
            Log.w(TAG, "老构造器建 AudioRecord 失败（source=" + source + "）", t);
            throw t;
        }
    }

    /** 这台机器上有没有"采本机正在播放的声音"的能力（API 29+）。 */
    public static boolean playbackCaptureSupported(Context ctx) {
        return Build.VERSION.SDK_INT >= 29;
    }
}
