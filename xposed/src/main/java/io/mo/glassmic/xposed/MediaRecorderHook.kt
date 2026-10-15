package io.mo.glassmic.xposed

import android.content.Context
import android.hardware.Camera
import android.media.MediaRecorder
import android.util.Log
import android.view.Surface
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.libxposed.api.XposedInterface
import java.io.File
import java.io.FileDescriptor
import java.lang.reflect.Method

/**
 * 把 [MediaRecorderInterceptor] 挂到 MediaRecorder 的公开方法上。API 101 与 legacy API 82 共用同一份方法表；
 * [XposedHookGate.tryMarkMediaRecorderHookInstalled] 保证同一进程只装一次。
 */
object MediaRecorderHook {

    private const val TAG = "GlassMic-MediaRecHook"

    @Volatile private var installed = false

    private val INT = Int::class.javaPrimitiveType!!

    private fun sig(vararg types: Class<*>): Array<Class<*>> = arrayOf(*types)

    @Suppress("DEPRECATION")
    private val METHODS: List<Pair<String, Array<Class<*>>>> = listOf(
        "setAudioSource" to sig(INT),
        "setVideoSource" to sig(INT),
        "setCamera" to sig(Camera::class.java),
        "setInputSurface" to sig(Surface::class.java),
        "setOutputFormat" to sig(INT),
        "setAudioEncoder" to sig(INT),
        "setAudioSamplingRate" to sig(INT),
        "setAudioChannels" to sig(INT),
        "setAudioEncodingBitRate" to sig(INT),
        "setMaxDuration" to sig(INT),
        "setOutputFile" to sig(String::class.java),
        "setOutputFile" to sig(File::class.java),
        "setOutputFile" to sig(FileDescriptor::class.java),
        "setOnInfoListener" to sig(MediaRecorder.OnInfoListener::class.java),
        "setOnErrorListener" to sig(MediaRecorder.OnErrorListener::class.java),
        "prepare" to sig(),
        "start" to sig(),
        "stop" to sig(),
        "pause" to sig(),
        "resume" to sig(),
        "getMaxAmplitude" to sig(),
        "reset" to sig(),
        "release" to sig()
    )

    private fun claim(): Boolean {
        if (installed) return false
        synchronized(this) {
            if (installed) return false
            installed = true
        }
        return XposedHookGate.tryMarkMediaRecorderHookInstalled()
    }

    private fun resolve(): List<Pair<String, Method>> = METHODS.mapNotNull { (name, types) ->
        runCatching { MediaRecorder::class.java.getDeclaredMethod(name, *types) }
            .getOrNull()?.let { name to it }
    }

    /** libxposed API 101。 */
    fun install(api: XposedInterface, appCtx: Context, callerPkg: String) {
        if (!claim()) return
        var count = 0
        for ((name, method) in resolve()) {
            val argc = method.parameterCount
            runCatching {
                api.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val recorder = chain.getThisObject() as? MediaRecorder
                            ?: return@intercept chain.proceed()
                        val args = Array<Any?>(argc) { chain.getArg(it) }
                        val decision = try {
                            MediaRecorderInterceptor.handle(appCtx, callerPkg, recorder, name, args)
                        } catch (t: Throwable) {
                            api.log(Log.WARN, TAG, "$name hook error: ${t.message}", t)
                            MediaRecorderInterceptor.Decision.Proceed
                        }
                        when (decision) {
                            MediaRecorderInterceptor.Decision.Proceed -> chain.proceed()
                            is MediaRecorderInterceptor.Decision.Replace -> decision.value
                        }
                    }
                count++
            }.onFailure { api.log(Log.WARN, TAG, "hook $name failed: ${it.message}") }
        }
        api.log(Log.INFO, TAG, "installed $count hooks in $callerPkg")
    }

    /** legacy API 82。 */
    fun installLegacy(appCtx: Context, callerPkg: String) {
        if (!claim()) return
        var count = 0
        for ((name, method) in resolve()) {
            runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val recorder = param.thisObject as? MediaRecorder ?: return
                        val decision = try {
                            MediaRecorderInterceptor.handle(
                                appCtx, callerPkg, recorder, name,
                                Array<Any?>(param.args?.size ?: 0) { param.args[it] }
                            )
                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG: $name hook error: ${t.message}")
                            return
                        }
                        if (decision is MediaRecorderInterceptor.Decision.Replace) param.result = decision.value
                    }
                })
                count++
            }.onFailure { XposedBridge.log("$TAG: hook $name failed: ${it.message}") }
        }
        XposedBridge.log("$TAG: installed $count hooks in $callerPkg")
    }
}
