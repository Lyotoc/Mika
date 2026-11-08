package com.wangzan101.mika.cap.phone.setup

import android.content.Context
import com.wangzan101.mika.core.shell.HelperHandoff
import com.wangzan101.mika.core.shell.RootGrant
import com.wangzan101.mika.core.shell.RootStart
import com.wangzan101.mika.core.shell.SuAnswer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 已 root 的手机上的两个开关,都默认关、只在找得到 su 时显示:
 * 「用 root 启动手机助手」(看门狗经 su 以 shell 身份起助手,见 [com.wangzan101.mika.core.shell.BuiltinShellEngine])、
 * 「允许 Mika 执行 root 命令」(Agent 的 root 工具)。打开时问 su 一次;su 的答复记下来,拒绝了就不再自己去问,
 * 等用户点重试。问 su 都在 IO 线程上,不堵界面。[changed] 在「用 root 启动」能不能用变了时调(让看门狗按新的情况再试)。
 */
class RootControl(context: Context, private val scope: CoroutineScope, private val changed: () -> Unit) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 设置页看的样子。[suPresent]:找得到 su(找不到就整个不显示);[start]、[commands]:两个开关;
     * [startGrant]、[commandsGrant]:su 对这两件事的答复(问的时候是 [RootGrant.Pending])。
     */
    data class State(
        val suPresent: Boolean = false,
        val start: Boolean = false,
        val startGrant: RootGrant = RootGrant.Unknown,
        val commands: Boolean = false,
        val commandsGrant: RootGrant = RootGrant.Unknown,
    )

    private val _state = MutableStateFlow(
        State(
            suPresent = false,
            start = prefs.getBoolean(KEY_START, false),
            startGrant = RootGrant.of(answer(KEY_START_ANSWER)),
            commands = prefs.getBoolean(KEY_COMMANDS, false),
            commandsGrant = RootGrant.of(answer(KEY_COMMANDS_ANSWER)),
        ),
    )
    val state: StateFlow<State> = _state.asStateFlow()

    /** su 在不在:第一次用时当场看一眼(只是几个文件在不在),之后由 [refresh] 更新。 */
    @Volatile private var suFound: Boolean? = null

    private fun suPresent(): Boolean = suFound ?: (RootShell.su() != null).also { found ->
        suFound = found
        _state.update { it.copy(suPresent = found) }
    }

    /** 重新找一次 su(App 启动、打开设置页时);变了就让看门狗按新的情况再试。 */
    fun refresh() {
        scope.launch(Dispatchers.IO) {
            val before = suFound
            val found = RootShell.su() != null
            suFound = found
            _state.update { it.copy(suPresent = found) }
            if (before != null && before != found && _state.value.start) changed()
        }
    }

    /** 看门狗看的:「用 root 启动」此刻能不能用。 */
    fun rootStart(): RootStart = RootStart.of(_state.value.start, suPresent(), answer(KEY_START_ANSWER))

    /** 看门狗起助手时 su 的答复;null 是忘掉上一次的拒绝(用户点了重试)。 */
    fun setStartAnswer(a: SuAnswer?) {
        prefs.edit().putString(KEY_START_ANSWER, a?.name).apply()
        _state.update { it.copy(startGrant = RootGrant.of(a)) }
    }

    /** 经 su 起助手时发现 su 没了。 */
    fun suGone() {
        if (suFound == false) return
        suFound = false
        _state.update { it.copy(suPresent = false) }
    }

    /**
     * 打开或关掉「用 root 启动」。打开时以 shell 身份问 su 一次(第一次会弹授权框):答应了看门狗马上经 su 起助手;
     * 拒绝、不回话或切不到 shell 身份都照实记下,开关留着,设置里说明并给重试。
     */
    fun setStart(on: Boolean) {
        prefs.edit().putBoolean(KEY_START, on).apply()
        _state.update { it.copy(start = on) }
        if (!on) {
            changed()
            return
        }
        askForStart()
    }

    /** 重新问 su(用户点了重试)。 */
    fun retryStart() {
        if (_state.value.start) askForStart()
    }

    private fun askForStart() {
        _state.update { it.copy(startGrant = RootGrant.Pending) }
        scope.launch {
            val a = withContext(Dispatchers.IO) { RootShell.ask(HelperHandoff.SHELL_UID, ASK_TIMEOUT_MS) }
            if (a == null) suGone() else setStartAnswer(a)
            changed()
        }
    }

    /** 解除配对:停止手机操作,「用 root 启动」也关掉(看门狗不再经 su 把助手起回来)。 */
    fun turnOffStart() {
        prefs.edit().putBoolean(KEY_START, false).apply()
        _state.update { it.copy(start = false) }
    }

    /** Agent 能用 root 工具:用户打开了、su 答应过、su 还在。 */
    fun commandsAllowed(): Boolean = _state.value.commands && answer(KEY_COMMANDS_ANSWER) == SuAnswer.Granted && suPresent()

    /** 打开或关掉「允许 Mika 执行 root 命令」。打开时以 root 问 su 一次,答应了才打开。 */
    fun setCommands(on: Boolean) {
        if (!on) {
            prefs.edit().putBoolean(KEY_COMMANDS, false).apply()
            _state.update { it.copy(commands = false) }
            return
        }
        _state.update { it.copy(commandsGrant = RootGrant.Pending) }
        scope.launch {
            val a = withContext(Dispatchers.IO) { RootShell.ask(0, ASK_TIMEOUT_MS) }
            if (a == null) suGone()
            val ok = a == SuAnswer.Granted
            prefs.edit().putBoolean(KEY_COMMANDS, ok).putString(KEY_COMMANDS_ANSWER, a?.name).apply()
            _state.update { it.copy(commands = ok, commandsGrant = RootGrant.of(a)) }
        }
    }

    /** 执行 root 命令时 su 拒绝了:关掉这一项并记下(下次打开时再问)。 */
    fun commandsDenied(a: SuAnswer) {
        prefs.edit().putBoolean(KEY_COMMANDS, false).putString(KEY_COMMANDS_ANSWER, a.name).apply()
        _state.update { it.copy(commands = false, commandsGrant = RootGrant.of(a)) }
    }

    private fun answer(key: String): SuAnswer? = prefs.getString(key, null)?.let { n -> SuAnswer.entries.firstOrNull { it.name == n } }

    private companion object {
        const val PREFS = "root"
        const val KEY_START = "start"
        const val KEY_START_ANSWER = "startAnswer"
        const val KEY_COMMANDS = "commands"
        const val KEY_COMMANDS_ANSWER = "commandsAnswer"
        /** 用户打开开关时问 su:授权框要给人看清、点一下的时间。 */
        const val ASK_TIMEOUT_MS = 60_000L
    }
}
