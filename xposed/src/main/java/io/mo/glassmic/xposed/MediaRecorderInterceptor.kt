package io.mo.glassmic.xposed

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import io.mo.glassmic.core.audio.ComfortNoise
import io.mo.glassmic.core.model.SourceType
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * MediaRecorder 纯音频录音的虚拟化。
 *
 * MediaRecorder 的采集发生在 mediaserver 进程里，App 进程内的 AudioRecord / AAudio / OpenSL hook
 * 都看不到这段音频（典型：Snapchat 语音消息）。这里在 App 进程内接管 MediaRecorder：
 * 音源为 FILE / TTS / SILENCE 时，不让系统真正 prepare/start，而是自己从 PcmStreamProvider 读 PCM，
 * 用 MediaCodec 编成 AAC，写进 App 指定的同一个输出文件，start/stop/pause/getMaxAmplitude 都由这里应答。
 *
 * 只接管有把握的情况，其余一律交还系统，保证原有流程不受影响：
 *  - 有视频（setVideoSource / setCamera / setInputSurface）→ 交还（视频录制本来就能被现有 hook 处理或不需要处理）
 *  - 当前音源是真实麦克风 → 交还
 *  - 编码器不是 AAC 系列、容器不是 MPEG_4 / THREE_GPP / AAC_ADTS → 交还
 *  - 打开 PCM 管道或创建编码器失败 → 当场交还系统，照常真实录音
 *
 * 本类只负责状态与决策，不依赖 Xposed API；API 101 与 legacy API 82 的适配见 [MediaRecorderHook]。
 */
internal object MediaRecorderInterceptor {

    private const val TAG = "GlassMic-MediaRec"

    sealed interface Decision {
        data object Proceed : Decision
        data class Replace(val value: Any?) : Decision
    }

    /** App 对一个 MediaRecorder 实例的配置，setter 照常交给系统执行，这里只旁路记录。 */
    private class Config {
        var audioSource = -1
        var hasVideo = false
        var outputFormat = -1
        var audioEncoder = -1
        var sampleRate = 0
        var channels = 0
        var bitRate = 0
        var maxDurationMs = 0
        var path: String? = null
        var fd: FileDescriptor? = null
        var infoListener: MediaRecorder.OnInfoListener? = null
        var errorListener: MediaRecorder.OnErrorListener? = null
        var session: VirtualRecording? = null
        /** 虚拟录音起不来时置位：随后补调的系统 prepare 不再被接管。 */
        var forceSystem = false
    }

    private val configs = WeakHashMap<MediaRecorder, Config>()

    private fun config(recorder: MediaRecorder): Config =
        synchronized(configs) { configs.getOrPut(recorder) { Config() } }

    private fun sessionOf(recorder: MediaRecorder): VirtualRecording? =
        synchronized(configs) { configs[recorder]?.session }

    fun handle(ctx: Context, pkg: String, recorder: MediaRecorder, method: String, args: Array<Any?>): Decision =
        when (method) {
            "setAudioSource" -> config(recorder).run { audioSource = args.int(0); Decision.Proceed }
            "setVideoSource", "setCamera", "setInputSurface" -> config(recorder).run { hasVideo = true; Decision.Proceed }
            "setOutputFormat" -> config(recorder).run { outputFormat = args.int(0); Decision.Proceed }
            "setAudioEncoder" -> config(recorder).run { audioEncoder = args.int(0); Decision.Proceed }
            "setAudioSamplingRate" -> config(recorder).run { sampleRate = args.int(0); Decision.Proceed }
            "setAudioChannels" -> config(recorder).run { channels = args.int(0); Decision.Proceed }
            "setAudioEncodingBitRate" -> config(recorder).run { bitRate = args.int(0); Decision.Proceed }
            "setMaxDuration" -> config(recorder).run { maxDurationMs = args.int(0); Decision.Proceed }
            "setOutputFile" -> config(recorder).run {
                when (val target = args.getOrNull(0)) {
                    is String -> { path = target; fd = null }
                    is File -> { path = target.path; fd = null }
                    is FileDescriptor -> { fd = target; path = null }
                }
                Decision.Proceed
            }
            "setOnInfoListener" -> config(recorder).run {
                infoListener = args.getOrNull(0) as? MediaRecorder.OnInfoListener; Decision.Proceed
            }
            "setOnErrorListener" -> config(recorder).run {
                errorListener = args.getOrNull(0) as? MediaRecorder.OnErrorListener; Decision.Proceed
            }
            "prepare" -> prepare(ctx, pkg, recorder)
            "start" -> start(recorder)
            "stop" -> {
                val s = synchronized(configs) { configs[recorder]?.let { c -> c.session.also { c.session = null } } }
                if (s == null) Decision.Proceed
                else {
                    runCatching { s.stop() }.onFailure { Log.w(TAG, "stop failed: ${it.message}") }
                    Decision.Replace(null)
                }
            }
            "pause" -> sessionOf(recorder)?.let { it.paused = true; Decision.Replace(null) } ?: Decision.Proceed
            "resume" -> sessionOf(recorder)?.let { it.paused = false; Decision.Replace(null) } ?: Decision.Proceed
            "getMaxAmplitude" -> sessionOf(recorder)?.let { Decision.Replace(it.takeMaxAmplitude()) } ?: Decision.Proceed
            "reset", "release" -> {
                val c = synchronized(configs) { configs.remove(recorder) }
                c?.session?.let { s -> runCatching { s.stop() } }
                // 系统侧只经历了 setter（虚拟录音时没有 prepare/start），reset/release 照常执行即可
                Decision.Proceed
            }
            else -> Decision.Proceed
        }

    /**
     * 决定这次录音由谁来做。返回 Replace 表示虚拟录音已就绪、系统 prepare 被跳过；
     * 返回 Proceed 则一切照旧。
     */
    private fun prepare(ctx: Context, pkg: String, recorder: MediaRecorder): Decision {
        val c = config(recorder)
        c.session?.let { runCatching { it.stop() } }
        c.session = null
        if (c.forceSystem) {
            c.forceSystem = false
            return Decision.Proceed
        }
        // 视频录制：不查询、不接管，开销与原流程一致
        if (c.hasVideo || c.audioSource < 0) return Decision.Proceed

        val unsupported = unsupportedReason(c)
        val source = XBridge.resolveSource(ctx, pkg)
        if (source == SourceType.REAL_MIC) return Decision.Proceed
        if (unsupported != null) {
            Log.i(TAG, "passthrough in $pkg: $unsupported")
            XBridge.reportDiagnosticEvent(ctx, pkg, "media_recorder_passthrough", Bundle().apply {
                putString("path", "MediaRecorder")
                putString("source", source.name)
                putString("reason", unsupported)
            })
            return Decision.Proceed
        }

        // 系统 MediaRecorder 在 prepare 时就复制了 App 传入的 fd，App 随后关掉自己那份也不影响录音；
        // 这里同样在 prepare 阶段 dup 一份，行为保持一致
        val outFd = c.fd?.let { fd ->
            runCatching { ParcelFileDescriptor.dup(fd) }.getOrElse {
                Log.w(TAG, "dup output fd failed, passthrough: ${it.message}")
                return Decision.Proceed
            }
        }
        val spec = Spec(
            container = c.outputFormat,
            encoder = c.audioEncoder,
            sampleRate = c.sampleRate.takeIf { it in 8_000..96_000 } ?: DEFAULT_SAMPLE_RATE,
            channels = c.channels.coerceIn(1, 2).takeIf { c.channels > 0 } ?: 1,
            bitRate = c.bitRate.takeIf { it > 0 }?.coerceIn(8_000, 320_000) ?: DEFAULT_BIT_RATE,
            maxDurationMs = c.maxDurationMs,
            path = c.path,
            fd = outFd
        )
        c.session = VirtualRecording(ctx, pkg, recorder, spec, source, c.infoListener, c.errorListener)
        Log.i(TAG, "virtual recording prepared in $pkg: $spec source=$source")
        XBridge.reportDiagnosticEvent(ctx, pkg, "media_recorder_virtual", Bundle().apply {
            putString("path", "MediaRecorder")
            putString("source", source.name)
            putLong("sample_rate", spec.sampleRate.toLong())
            putLong("channels", spec.channels.toLong())
            putString("detail", "format=${spec.container} encoder=${spec.encoder} bitrate=${spec.bitRate}")
        })
        return Decision.Replace(null)
    }

    private fun start(recorder: MediaRecorder): Decision {
        val c = config(recorder)
        val session = c.session ?: return Decision.Proceed
        val ok = runCatching { session.start() }.getOrElse {
            Log.w(TAG, "virtual start error: ${it.message}", it)
            false
        }
        if (ok) return Decision.Replace(null)

        // 虚拟录音起不来（管道打不开 / 设备没有可用的 AAC 编码器……）→ 交还系统真实录音。
        // 系统侧此前只收到了 setter，这里补一次真正的 prepare，再让 start 照常执行。
        c.session = null
        c.forceSystem = true
        Log.w(TAG, "virtual recording unavailable, falling back to system recording")
        return runCatching {
            recorder.prepare()
            Decision.Proceed
        }.getOrElse {
            Log.w(TAG, "fallback prepare failed: ${it.message}")
            Decision.Proceed
        }
    }

    private fun unsupportedReason(c: Config): String? = when {
        c.audioSource !in SUPPORTED_SOURCES -> "audio source ${c.audioSource}"
        c.path == null && c.fd == null -> "no output file"
        c.audioEncoder !in AAC_ENCODERS -> "audio encoder ${c.audioEncoder}"
        c.outputFormat !in SUPPORTED_CONTAINERS -> "output format ${c.outputFormat}"
        else -> null
    }

    private fun Array<Any?>.int(i: Int): Int = (getOrNull(i) as? Int) ?: -1

    private const val DEFAULT_SAMPLE_RATE = 44_100
    private const val DEFAULT_BIT_RATE = 96_000

    private val SUPPORTED_SOURCES = setOf(
        MediaRecorder.AudioSource.DEFAULT,
        MediaRecorder.AudioSource.MIC,
        MediaRecorder.AudioSource.CAMCORDER,
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        MediaRecorder.AudioSource.UNPROCESSED,
        MediaRecorder.AudioSource.VOICE_PERFORMANCE
    )
    private val AAC_ENCODERS = setOf(
        MediaRecorder.AudioEncoder.AAC,
        MediaRecorder.AudioEncoder.HE_AAC,
        MediaRecorder.AudioEncoder.AAC_ELD
    )
    private val SUPPORTED_CONTAINERS = setOf(
        MediaRecorder.OutputFormat.THREE_GPP,
        MediaRecorder.OutputFormat.MPEG_4,
        MediaRecorder.OutputFormat.AAC_ADTS
    )

    private data class Spec(
        val container: Int,
        val encoder: Int,
        val sampleRate: Int,
        val channels: Int,
        val bitRate: Int,
        val maxDurationMs: Int,
        val path: String?,
        val fd: ParcelFileDescriptor?
    ) {
        override fun toString() =
            "format=$container encoder=$encoder ${sampleRate}Hz ch=$channels ${bitRate}bps max=${maxDurationMs}ms " +
                (if (path != null) "path" else "fd")
    }

    /**
     * 一次虚拟录音：独立线程按实时节奏从 PCM 管道读数据 → AAC 编码 → 写文件。
     * stop() 会等编码收尾、文件写完再返回，App 拿到的是完整可播的文件。
     */
    private class VirtualRecording(
        private val ctx: Context,
        private val pkg: String,
        private val recorder: MediaRecorder,
        private val spec: Spec,
        private val source: SourceType,
        private val infoListener: MediaRecorder.OnInfoListener?,
        private val errorListener: MediaRecorder.OnErrorListener?
    ) {
        @Volatile var paused = false
        @Volatile private var running = false
        private val maxAmplitude = AtomicInteger(0)
        private var thread: Thread? = null
        private var reader: XposedPcmReader? = null
        private var codec: MediaCodec? = null
        private var sink: EncodedSink? = null
        private val chunkBytes = (spec.sampleRate / 50) * spec.channels * 2 // 20ms

        fun takeMaxAmplitude(): Int = maxAmplitude.getAndSet(0)

        /** 同步完成「首段音频可读 + 编码器 + 输出文件」全部准备，任一失败返回 false 由调用方回退。 */
        fun start(): Boolean {
            val first = ByteArray(chunkBytes)
            if (source != SourceType.SILENCE) {
                val r = XposedPcmReader(ctx, spec.sampleRate, spec.channels)
                reader = r
                if (r.read(first, 0, first.size) < 0) {
                    r.release()
                    reader = null
                    return false
                }
            } else {
                ComfortNoise.fillBytes(first, 0, first.size)
            }
            try {
                codec = createEncoder()
                sink = createSink()
            } catch (t: Throwable) {
                Log.w(TAG, "encoder/output setup failed: ${t.message}")
                releaseAll()
                return false
            }
            running = true
            thread = Thread({ loop(first) }, "GlassMic-MediaRec").apply { start() }
            return true
        }

        fun stop() {
            running = false
            val t = thread
            if (t == null) {
                // prepare 之后没 start 就结束（reset / release / 再次 prepare）：只需释放 dup 的 fd 等资源
                releaseAll()
                return
            }
            if (t !== Thread.currentThread()) runCatching { t.join(STOP_TIMEOUT_MS) }
            thread = null
        }

        private fun createEncoder(): MediaCodec {
            val profile = when (spec.encoder) {
                MediaRecorder.AudioEncoder.HE_AAC -> MediaCodecInfo.CodecProfileLevel.AACObjectHE
                MediaRecorder.AudioEncoder.AAC_ELD -> MediaCodecInfo.CodecProfileLevel.AACObjectELD
                else -> MediaCodecInfo.CodecProfileLevel.AACObjectLC
            }
            // 部分设备的编码器不支持 HE / ELD，退回 AAC-LC（任何 AAC 解码器都能播）
            val profiles = listOf(profile, MediaCodecInfo.CodecProfileLevel.AACObjectLC).distinct()
            var lastError: Throwable? = null
            for (p in profiles) {
                val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                try {
                    val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, spec.sampleRate, spec.channels).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, p)
                        setInteger(MediaFormat.KEY_BIT_RATE, spec.bitRate)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, chunkBytes * 4)
                    }
                    c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    c.start()
                    return c
                } catch (t: Throwable) {
                    lastError = t
                    runCatching { c.release() }
                }
            }
            throw lastError ?: IllegalStateException("no AAC encoder")
        }

        private fun createSink(): EncodedSink = when (spec.container) {
            MediaRecorder.OutputFormat.AAC_ADTS -> {
                val out = spec.path?.let { FileOutputStream(it) } ?: FileOutputStream(spec.fd!!.fileDescriptor)
                AdtsSink(out, closeStream = spec.path != null, sampleRate = spec.sampleRate, channels = spec.channels)
            }
            else -> {
                val fmt = if (spec.container == MediaRecorder.OutputFormat.THREE_GPP)
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                MuxerSink(spec.path?.let { MediaMuxer(it, fmt) } ?: MediaMuxer(spec.fd!!.fileDescriptor, fmt))
            }
        }

        private fun loop(first: ByteArray) {
            val buf = ByteArray(chunkBytes)
            var framesQueued = 0L
            var maxReached = false
            var failed = false
            try {
                updateAmplitude(first, first.size)
                framesQueued += queuePcm(first, first.size, framesQueued)
                while (running) {
                    val n = readChunk(buf)
                    updateAmplitude(buf, n)
                    XBridge.recordInterception(ctx, pkg, n, spec.sampleRate, spec.channels)
                    if (paused || maxReached) continue
                    framesQueued += queuePcm(buf, n, framesQueued)
                    drain(endOfStream = false)
                    if (spec.maxDurationMs > 0 && framesQueued * 1000L / spec.sampleRate >= spec.maxDurationMs) {
                        maxReached = true
                        postInfo(MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED)
                    }
                }
                queueEndOfStream(framesQueued)
                drain(endOfStream = true)
            } catch (t: Throwable) {
                failed = true
                Log.w(TAG, "virtual recording failed: ${t.message}", t)
            } finally {
                releaseAll()
            }
            if (failed) postError()
        }

        /** 读满 20ms；管道断开时补舒适噪声并按实时节奏等待，录音不中断。 */
        private fun readChunk(buf: ByteArray): Int {
            val r = reader
            if (r != null) {
                val n = r.read(buf, 0, buf.size)
                if (n >= 0) {
                    if (n < buf.size) java.util.Arrays.fill(buf, n, buf.size, 0.toByte())
                    return buf.size
                }
            }
            ComfortNoise.fillBytes(buf, 0, buf.size)
            Thread.sleep(20)
            return buf.size
        }

        private fun updateAmplitude(buf: ByteArray, n: Int) {
            var peak = 0
            var i = 0
            while (i + 1 < n) {
                val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort().toInt()
                peak = maxOf(peak, abs(s))
                i += 2
            }
            maxAmplitude.accumulateAndGet(peak.coerceAtMost(Short.MAX_VALUE.toInt())) { a, b -> maxOf(a, b) }
        }

        /** 送入编码器，返回送入的帧数。输入缓冲暂满时先取走输出再重试。 */
        private fun queuePcm(buf: ByteArray, n: Int, framesBefore: Long): Long {
            val c = codec ?: return 0
            val frameBytes = 2 * spec.channels
            var offset = 0
            var frames = 0L
            var attempts = 0
            while (offset < n && attempts < 50) {
                val idx = c.dequeueInputBuffer(10_000)
                if (idx < 0) {
                    drain(endOfStream = false)
                    attempts++
                    continue
                }
                val ptsUs = (framesBefore + frames) * 1_000_000L / spec.sampleRate
                val input = c.getInputBuffer(idx)
                val len = if (input == null) 0 else {
                    input.clear()
                    (minOf(n - offset, input.remaining()) / frameBytes * frameBytes).also { input.put(buf, offset, it) }
                }
                // 取到的输入缓冲一定要还回去，哪怕是空的
                c.queueInputBuffer(idx, 0, len, ptsUs, 0)
                if (len == 0) break
                offset += len
                frames += len / frameBytes
            }
            return frames
        }

        private fun queueEndOfStream(frames: Long) {
            val c = codec ?: return
            repeat(50) {
                val idx = c.dequeueInputBuffer(10_000)
                if (idx >= 0) {
                    val ptsUs = frames * 1_000_000L / spec.sampleRate
                    c.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    return
                }
                drain(endOfStream = false)
            }
        }

        private fun drain(endOfStream: Boolean) {
            val c = codec ?: return
            val s = sink ?: return
            val info = MediaCodec.BufferInfo()
            var idle = 0
            while (true) {
                val idx = c.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream || ++idle > 200) return
                    }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> s.onFormat(c.outputFormat)
                    idx >= 0 -> {
                        val out = c.getOutputBuffer(idx)
                        // CSD 由 muxer 从 output format 取得；ADTS 每帧自带头，不需要 CSD
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && out != null) {
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            s.write(out, info)
                        }
                        c.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        private fun releaseAll() {
            sink?.let { runCatching { it.finish() }.onFailure { e -> Log.w(TAG, "finish output: ${e.message}") } }
            sink = null
            codec?.let { c ->
                runCatching { c.stop() }
                runCatching { c.release() }
            }
            codec = null
            reader?.release()
            reader = null
            spec.fd?.let { runCatching { it.close() } }
        }

        private fun postInfo(what: Int) {
            val l = infoListener ?: return
            mainHandler.post { runCatching { l.onInfo(recorder, what, 0) } }
        }

        private fun postError() {
            val l = errorListener ?: return
            mainHandler.post { runCatching { l.onError(recorder, MediaRecorder.MEDIA_RECORDER_ERROR_UNKNOWN, 0) } }
        }
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private const val STOP_TIMEOUT_MS = 3_000L

    private interface EncodedSink {
        fun onFormat(format: MediaFormat)
        fun write(data: ByteBuffer, info: MediaCodec.BufferInfo)
        fun finish()
    }

    private class MuxerSink(private val muxer: MediaMuxer) : EncodedSink {
        private var track = -1
        private var started = false
        private var samples = 0

        override fun onFormat(format: MediaFormat) {
            if (started) return
            track = muxer.addTrack(format)
            muxer.start()
            started = true
        }

        override fun write(data: ByteBuffer, info: MediaCodec.BufferInfo) {
            if (!started) return
            muxer.writeSampleData(track, data, info)
            samples++
        }

        override fun finish() {
            try {
                if (started && samples > 0) muxer.stop()
            } finally {
                muxer.release()
            }
        }
    }

    /** AAC_ADTS 容器：每个 AAC 帧前加 7 字节 ADTS 头。App 传入的 fd 不由这里关闭。 */
    private class AdtsSink(
        private val out: OutputStream,
        private val closeStream: Boolean,
        sampleRate: Int,
        private val channels: Int
    ) : EncodedSink {
        private val freqIndex = ADTS_RATES.indexOf(sampleRate).takeIf { it >= 0 } ?: 4
        private var frame = ByteArray(0)

        override fun onFormat(format: MediaFormat) = Unit

        override fun write(data: ByteBuffer, info: MediaCodec.BufferInfo) {
            val size = info.size
            val total = size + 7
            if (frame.size < total) frame = ByteArray(total)
            // profile = AAC LC（object type 2 → 写入 1）
            frame[0] = 0xFF.toByte()
            frame[1] = 0xF1.toByte()
            frame[2] = ((1 shl 6) or (freqIndex shl 2) or (channels shr 2)).toByte()
            frame[3] = (((channels and 3) shl 6) or (total shr 11)).toByte()
            frame[4] = ((total shr 3) and 0xFF).toByte()
            frame[5] = (((total and 7) shl 5) or 0x1F).toByte()
            frame[6] = 0xFC.toByte()
            data.get(frame, 7, size)
            out.write(frame, 0, total)
        }

        override fun finish() {
            out.flush()
            if (closeStream) out.close()
        }

        private companion object {
            val ADTS_RATES = listOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
        }
    }
}
