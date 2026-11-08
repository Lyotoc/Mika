package com.wangzan101.mika.cap.phone.display

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import android.view.Display
import com.wangzan101.mika.shell.IMikaShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/**
 * 安卓 10 上任务的「屏」:用户的主屏(屏号 0)。
 *
 * 安卓 10 的无障碍服务只看得到默认屏的窗口(getWindowsOnAllDisplays 是安卓 11 才有的),所以那时 Mika 没法
 * 在虚拟屏上读控件树;任务改为在主屏上用无障碍读界面、用内置助手注入触摸,截图由助手以 shell 身份跑 `screencap`
 * (无障碍的 takeScreenshot 也是安卓 11 起才有)。
 *
 * 截图落在 Mika 自己的外部文件目录(助手以 shell 身份写、App 自己读),所以这里要一个能写的目录。
 * 画面轮询给任务界面的预览用;真正的截图([latestFramePng])每次现截。
 */
class MainScreenDisplay(
    private val shell: IMikaShell,
    width: Int,
    height: Int,
    /** Mika 的外部文件目录(助手以 shell 身份能写、App 自己能读)。 */
    private val dir: File,
) : PhoneDisplay {
    override val displayId: Int = Display.DEFAULT_DISPLAY
    override val width: Int = width
    override val height: Int = height

    private val _frames = MutableStateFlow<Bitmap?>(null)
    override val frames: StateFlow<Bitmap?> = _frames.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var watch: Job? = null

    /** 最近一张整幅画面(OCR 用);[latestFrameBitmap] 给它一份拷贝。 */
    private var lastFull: Bitmap? = null

    init {
        // 主屏上没有 Mika 自己的虚拟屏:让助手放行主屏上的启动、注入和命令(见 ShellService.mainScreen)
        runCatching { shell.setMainScreenMode(true) }
            .onFailure { Log.w(TAG, "cannot switch the helper to main-screen mode", it) }
        watch = scope.launch {
            while (isActive) {
                if (capturePng() != null) publish()
                delay(FRAME_INTERVAL_MS)
            }
        }
    }

    /**
     * 预览画面交给读的人持有:发布新的一帧时不回收旧的。
     *
     * 界面预览、判定画面变化([com.wangzan101.mika.cap.phone.a11y.AndroidScreen.frameGrid] 等)和自检都会
     * 从 [frames] 里取同一份位图,而且随时可能正在读它;在这里 recycle 旧的,读的一方就会在 getPixel /
     * sameAs 里撞上「cannot access an invalid/free'd bitmap」的 native abort(捕获不到,进程直接退出)。
     * 所以这里只换引用,不回收;没有引用的旧帧交给 GC(和虚拟屏 [AgentDisplay] 的做法一致)。
     */
    override fun <T> sampleFrame(block: (FrameView) -> T): T? = synchronized(lock) {
        val bmp = _frames.value ?: return@synchronized null
        block(FrameView(bmp.width, bmp.height, bmp::getPixel))
    }

    override fun latestFramePng(): ByteArray? = capturePng()

    override fun latestFrameBitmap(): Bitmap? {
        // 判空和拷贝要在同一把锁里:否则可能在上面的判空之后、拷贝之前被 [capturePng] 换掉并回收
        synchronized(lock) { lastFull?.copy(Bitmap.Config.ARGB_8888, false) }?.let { return it }
        capturePng()
        return synchronized(lock) { lastFull?.copy(Bitmap.Config.ARGB_8888, false) }
    }

    override fun release() {
        runCatching { shell.setMainScreenMode(false) }
        watch?.cancel()
        scope.cancel()
        // lastFull 只在本类的锁里被拷出去,没有别人拿着,可以回收;发布出去的预览帧不回收(见 [sampleFrame]),
        // 正在画它的界面手里还有那一帧。
        synchronized(lock) {
            lastFull?.recycle()
            lastFull = null
            _frames.value = null
        }
        runCatching { captureFile.delete() }
    }

    /** 一张整幅 PNG:助手跑 `screencap -p <文件>`,再读回来。截不到(命令失败、文件空)返回 null。 */
    private fun capturePng(): ByteArray? {
        val bytes = capture(shell, captureFile) ?: return null
        val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val old = synchronized(lock) { lastFull.also { lastFull = full } }
        if (old !== full) old?.recycle()
        return bytes
    }

    /** 把最近一张整幅画面缩成预览放进 [frames]。旧帧不回收:读的人可能正拿着它(见 [sampleFrame])。 */
    private fun publish() {
        val full = synchronized(lock) { lastFull?.copy(Bitmap.Config.ARGB_8888, false) } ?: return
        try {
            val short = minOf(full.width, full.height)
            if (short <= 0) return
            val scale = PREVIEW_SHORT_SIDE.toFloat() / short
            val preview = if (scale >= 1f) full.copy(Bitmap.Config.ARGB_8888, false) else Bitmap.createScaledBitmap(
                full,
                (full.width * scale).roundToInt().coerceAtLeast(1),
                (full.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
            // 只换引用。上一帧谁在画、谁在采样都不由这里知道,回收它就可能在对方的 getPixel 里 native abort。
            _frames.value = preview
        } finally {
            // full 是这里自己拷出来的一份,没有别人拿到,可以放掉
            full.recycle()
        }
    }

    private val captureFile: File get() = File(dir, "main-screen.png")

    companion object {
        private const val TAG = "MikaMainScreen"
        /** 预览轮询间隔:主屏是用户正在看的屏,截得别太勤(每次 screencap 都要走一遍合成)。 */
        private const val FRAME_INTERVAL_MS = 700L
        private const val PREVIEW_SHORT_SIDE = 360
        private const val PNG_MIN_BYTES = 512

        /** 安卓 10(以及更早):无障碍看不到第二块屏,只能操作主屏。 */
        val supported: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.R

        /**
         * 以 shell 身份截一张主屏到 [file](Mika 自己的外部目录),读回 PNG 字节。
         * 截图接口(takeScreenshot 和屏号)都是安卓 11 起才有的,所以更早的系统统一走 [capture] 里的 screencap。
         */
        fun capture(shell: IMikaShell, file: File = File("/data/local/tmp", "mika-screencap.png")): ByteArray? {
            // 安卓 10 下助手以 shell 身份运行,没有 sdcard_rw 附加组,对应用外部私有目录无写权限;
            // 而 /data/local/tmp 对 shell 始终可写,且应用 UID 对其有全局读权限。
            // 因此始终落地到 /data/local/tmp/mika-screencap.png,写完 chmod 644 让应用能读。
            val target = File("/data/local/tmp", "mika-screencap.png")
            val path = target.absolutePath
            val quoted = "'" + path.replace("'", "'\\''") + "'"
            return runCatching {
                val r = shell.runCommand("screencap -p $quoted && chmod 644 $quoted")
                if (r.getInt("exitCode", -1) != 0) {
                    Log.w(TAG, "screencap failed: ${r.getString("output")?.trim()}")
                    null
                } else {
                    target.readBytes().takeIf { it.size > PNG_MIN_BYTES }
                }
            }.getOrNull()
        }
    }
}
