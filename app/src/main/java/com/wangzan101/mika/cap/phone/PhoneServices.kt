package com.wangzan101.mika.cap.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.display.MainScreenDisplay
import com.wangzan101.mika.cap.phone.setup.BuiltinShellManager
import com.wangzan101.mika.cap.phone.setup.BuiltinShellRecovery
import com.wangzan101.mika.cap.phone.setup.PairingCoordinator
import com.wangzan101.mika.cap.phone.setup.PhoneSetupPrompt
import com.wangzan101.mika.cap.phone.shell.BuiltinShellConnection
import com.wangzan101.mika.cap.phone.shell.HelperHub
import com.wangzan101.mika.cap.phone.shell.SharedShell
import com.wangzan101.mika.core.shell.BuiltinShellControl
import com.wangzan101.mika.settings.RecoverySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 手机能力在 Mika 进程里的服务,由 App 建一份、经 [Owner] 给出:内置助手(交接落点、配对与看门狗、
 * 自动恢复与授权)、任务和启动恢复共用的那条 shell 连接、配对流程、「去设置」提示。
 * 助手进程(HelperMain、ShellService)跑在 shell 身份下,没有 App 对象,不经这里。
 */
class PhoneServices(context: Context) {
    private val app = context.applicationContext

    init {
        // 安卓 10 没有无障碍截图接口:用户屏幕的截图交给内置助手的 screencap(见 ScreenAccessibility.shellCapture)
        ScreenAccessibility.shellCapture = { mainScreenCapture() }
    }

    /** 截一张主屏,解成位图;助手还没连上、或截不到时返回 null。 */
    private suspend fun mainScreenCapture(): Bitmap? = withContext(Dispatchers.IO) {
        val shell = hub.shell.value ?: return@withContext null
        val dir = app.getExternalFilesDir(null) ?: return@withContext null
        val shots = File(dir, "screen").also { it.mkdirs() }
        val bytes = MainScreenDisplay.capture(shell, File(shots, "capture.png")) ?: return@withContext null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    /** 助手交来 Binder 的落点:助手只认一个进程里的一个落点。 */
    val hub = HelperHub(app)

    /** 配对、经 adbd 起助手、看门狗。 */
    val shellManager = BuiltinShellManager(app, hub) { recoverySettings.consent.value }

    /** 内置助手本身(状态、配对、重试、解除配对)。 */
    val shellControl: BuiltinShellControl get() = shellManager.engine

    /** 自动恢复与授权开关的设置。 */
    val recoverySettings: RecoverySettings by lazy { RecoverySettings(RecoverySettings.prefs(app)) }

    /** 自动恢复与那几项授权开关。 */
    val recovery: BuiltinShellRecovery by lazy { BuiltinShellRecovery(app, shellManager.commandRunner, recoverySettings, shellControl) }

    /** 任务与启动恢复共用的那条 shell 连接。 */
    val sharedShell = SharedShell { BuiltinShellConnection(hub) { shellManager.need() } }

    /** 一次配对的全过程。 */
    val pairing: PairingCoordinator by lazy { PairingCoordinator(app, shellControl) }

    /** 任务要用手机、助手却没在运行时的提示。 */
    val setupPrompt = PhoneSetupPrompt(app)

    /** 给出进程里那一份的 Application。 */
    interface Owner {
        val phone: PhoneServices
    }

    companion object {
        fun of(context: Context): PhoneServices = (context.applicationContext as? Owner)?.phone
            ?: error("the Application (${context.applicationContext.javaClass.name}) must implement PhoneServices.Owner to give the phone's shared services")
    }
}
