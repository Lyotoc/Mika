package com.wangzan101.mika.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.setup.RootControl
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme

/** 设置 › 手机操作里 root 那一组,接上真实状态:只在找得到 su 时显示。[helperRow] 为假时(安卓 13 以下)不列「用 root 启动」。 */
@Composable
fun RootSwitches(helperRow: Boolean) {
    val context = LocalContext.current
    val root = remember { PhoneServices.of(context).shellManager.root }
    // 打开这一页时重新找一次 su(用户可能刚装上或去掉了 root)
    LaunchedEffect(Unit) { root.refresh() }
    val state by root.state.collectAsState()
    if (!state.suPresent) return
    RootSection(
        state, helperRow,
        onStart = root::setStart, onRetryStart = root::retryStart, onCommands = root::setCommands,
    )
}

/**
 * root 一组:「用 root 启动手机助手」和「允许 Mika 执行 root 命令」两个开关,都默认关。
 * 前一个上次被拒或没回应时,点这一行再问一次 su;后一个打开前先弹警告,确认了才去问 su。
 */
@Composable
fun RootSection(
    state: RootControl.State,
    helperRow: Boolean,
    onStart: (Boolean) -> Unit,
    onRetryStart: () -> Unit,
    onCommands: (Boolean) -> Unit,
) {
    var warn by remember { mutableStateOf(false) }
    val commandsTo = { on: Boolean -> if (on) warn = true else onCommands(false) }
    GroupLabel(PhoneSetupCopy.ROOT_GROUP)
    GroupCard {
        if (helperRow) {
            val retries = PhoneSetupCopy.rootStartRetries(state.start, state.startGrant)
            ListRow(
                MikaIcons.Key, PhoneSetupCopy.ROOT_START, PhoneSetupCopy.rootStartLine(state.startGrant), first = true,
                tone = if (retries) TileTone.Warn else TileTone.Neutral,
                onClick = if (retries) onRetryStart else ({ onStart(!state.start) }),
            ) { MikaSwitch(state.start, onStart, Modifier.testTag("root-start")) }
        }
        ListRow(
            MikaIcons.Terminal, PhoneSetupCopy.ROOT_COMMANDS, PhoneSetupCopy.rootCommandsLine(state.commands, state.commandsGrant), first = !helperRow,
            onClick = { commandsTo(!state.commands) },
        ) { MikaSwitch(state.commands, commandsTo, Modifier.testTag("root-commands")) }
    }
    FooterNote(PhoneSetupCopy.ROOT_NOTE)
    if (warn) {
        AlertDialog(
            onDismissRequest = { warn = false },
            title = { Text(PhoneSetupCopy.ROOT_COMMANDS_TITLE) },
            text = { Text(PhoneSetupCopy.ROOT_COMMANDS_WARNING) },
            confirmButton = {
                TextButton({ warn = false; onCommands(true) }) { Text(PhoneSetupCopy.ROOT_COMMANDS_CONFIRM, color = MikaTheme.colors.bad) }
            },
            dismissButton = { TextButton({ warn = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
