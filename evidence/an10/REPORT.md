# Honor(Android 10)「输入重复 + 任务中断」修复报告

日期:2026-11-06 · 分支 `feat/an_10_support`(脏工作区保留,未 reset / 未 commit / 未 push)
设备:Honor Play20,Android 10(SDK 29),root 助手 + 主屏模式
交付 APK:`app/build/outputs/apk/debug/app-debug.apk`,versionCode 36 / 0.1.35 / minSdk 29
SHA-256:`7a6ff6646c8dfb89fe8d90c7445569e2739a8f019f25a67ed8ebe3a97d2e7e50`(与设备上已安装的 base.apk 一致,见 `installed-apk-sha256.txt`)

## 一、根因

### 1. 任务中断 = 读已回收的 Bitmap,native abort

`cap/phone/display/MainScreenDisplay.kt` 的 `publish()` 把新预览写进 `frames` 后立刻 `old.recycle()`。
`frames` 是 `StateFlow<Bitmap?>`,同一张位图同时被以下读者持有:

- `AndroidScreen.frameGrid()` / `frameSignature()` / `frameBlack()`(取像素判定画面变化,`awaitChange`、读屏前后比对都走它)
- 任务界面预览(TaskRun / Compose)
- `CapsulePreview.sameFrame`(sameAs,带 isRecycled 判断但有 TOCTOU)
- `AndroidSelfCheck.frameHash`(getPixels)

发布方在另一条线程 `recycle` 时,读者正在 `Bitmap.getPixel` → `Error, cannot access an invalid/free'd bitmap here!`,SIGABRT,**不可捕获**,进程直接退出,任务显示「The phone process stopped before this task finished.」

证据:
- `honor-crash-buffer-2324.txt`:`11-05 23:06:02` SIGABRT,栈 `Bitmap_getPixel -> AndroidScreen$frameGrid$2$1$1 -> FrameGrid.Companion.sample -> AndroidScreen.frameGrid`
- 同一天 `09:02` 同一路径:`Bitmap_getPixel -> AndroidScreen.frameSignature -> FrameMath.lumaGrid -> awaitChange`
- 原始失败 run:`20261105-230409-itl034`、`20261105-085930-2e9oj5`(均已拉回 events/run.json)

### 2. 重复输入 = 读回早于 App 应用,重试走了「追加式粘贴」

失败 run `20261105-230409-itl034` 的事件(step 4 / step 7):

- `phone_type_text` 返回 `verification=mismatch`(「The text did not appear in the field after a retry」)
- 紧接着的 observe 却是 `搜索, 情侣游戏 升温情侣游戏 升温` —— 一段字写了两遍

机理:小红书搜索框的 `ACTION_SET_TEXT` 是异步应用的。原代码写完立刻读回,读到的还是空 → 判为 mismatch →
`typeAndVerify` 用 `retry()` 换通道,`inputText()` 依赖 `ACTION_SET_SELECTION` 全选后 `ACTION_PASTE`;
框里字没被选中(节点文本为空/选择未生效)时,粘贴是**插入**,于是接在后面成了两段。

## 二、改动

所有权 / 同步(`cap/phone/display`):

- `PhoneDisplay` 新增 `FrameView` + `sampleFrame {}`:采样期间画面有效,读像素一律走它。
- `MainScreenDisplay.publish()`:**不再回收已发布的预览帧**(只换引用,交给 GC),和虚拟屏 `AgentDisplay` 的做法一致;
  界面绘制、`sameAs`、`getPixels` 这些读者因此都安全。`lastFull` 是私有拷贝,仍在锁内回收。
- `MainScreenDisplay.sampleFrame()`:在发布锁内提供像素视图,采样不会跨过换帧。
- `latestFrameBitmap()`:判空与拷贝放进同一把锁(原来判空在锁外)。
- `AndroidScreen.frameGrid/frameSignature/frameBlack`:改为 `sampleFrame` 采样。

输入(`cap/phone/a11y/AndroidScreen.kt` + `core/uistate/SettlingField.kt`):

- 新增 `SettlingField`:写入后**等 App 把字应用**(读回与刚写的字一致,最多 1.2s)再让调用方核对;
  `retry()` **先清空并确认真的空了**,才换通道粘贴;清不空(读不回/选不中)则退回整段设字(SET_TEXT 本身是替换)。
  这样第二遍永远不可能追加。密码框不等(waitMs=0)。
- 读回认「输入后框变高/变矮」:新增 `sameFieldForRead`(同类、同资源 ID、原中心点仍在其范围内),
  只用于决定读哪个框,解决小红书搜索框一有字就撑高、被严格大小判断判成另一个框、读回变 null 的问题。
- `reacted` 判定改为最多等 1.5s(有的 App 把字画在输入框外层,树里要晚一点才出现)。

## 三、实际测试(真机)

1) `:core:test --tests ...SettlingFieldTest`:5/5 通过(`test-core-settling.log`)
   - 写入后等落定才算数;永远不显示时如实读回旧值;
   - 卡住读回时 retry 先清空 → 结果是单份;清不空 → 退回设字,不走粘贴。

2) 设备测试 `MainScreenDeviceTest`(Honor Android 10,`instrument-final2.log`):
   - `publishedPreviewsAreNotRecycledWhileRead`:**通过**。连续 8 秒、跨十余次发布,采样时不出现已回收帧,
     之前拿到的帧也始终有效。
   - 两个输入用例被**跳过**(`INSTRUMENTATION_STATUS_CODE: -4`,`instrument-typing-status.log`),
     原因写明:`Mika 无障碍服务在 instrumentation 进程里没有绑定(安卓 10 不自动重绑)`。
     这台 MagicUI 在 instrumentation 重启进程后不重绑无障碍服务,输入路径改由真机任务跑覆盖。

3) 真机 AI 任务(只读搜索,用户给定指令,模型 `remote-magpie/trae-cn/qwen3.8-flash`):

| run | 开始 | 结局 | 输入框最终内容 | 崩溃 |
|---|---|---|---|---|
| `20261106-005031-zg9ti5` | 00:50 | succeeded,17 步 | 清空后单份(修复前遗留的两份被清掉) | 无 |
| `20261106-013312-mxkw5t` | 01:33 | succeeded,28 步,2 次输入 | `搜索, 情侣游戏 升温` 单份 | 无 |
| `20261106-015619-yjtze3` | 01:56 | succeeded,17 步,1 次输入 | `搜索, 情侣游戏 升温` 单份 | 无 |

- 每次任务前后进程 pid 不变(`29527` → `16481` → `3026`),没有进程退出;
- `logcat -b crash` 在 01:33、01:56 两次任务前后均为空,`invalid/free'd bitmap` 计数 0(`crash-after-retest.log`、`crash-final.log`);
- 修复前的 `情侣游戏 升温情侣游戏 升温` 在修复后三轮里没有再出现。

## 四、限制与未解决(如实)

1. **`type_text` 在小红书搜索框上仍报 `verification=mismatch`**。字确实写进去了、且只有一份(上面三轮证据),
   但这个 App 的搜索条是自绘的,输入框节点在等待窗口内读不到新字,所以结论只能报「对不上」。
   模型据此会多 observe 一次再继续(三轮任务都正常完成)。这是**准确度报告**的问题,不是重复输入,也不是崩溃;
   修复前同样的 mismatch 会伴随重复粘贴,现在不会。
2. instrumentation 里跑不了依赖无障碍的用例(安卓 10 + MagicUI 不重绑),因此输入路径的设备级回归目前由
   core 单测 + 真机任务覆盖;`MainScreenDeviceTest` 已写明跳过原因,不是静默跳过。
3. Android 12+ 的虚拟屏路径未改(`AgentDisplay` 未动),`frames` 的语义对它是兼容的(它本来就不回收预览帧)。
4. 真机任务只在 Honor 上跑过;Redmi(Android 11?)未复测。

## 五、证据文件(`evidence/an10/`)

- `honor-crash-buffer-2324.txt` — 修复前的 native abort 栈
- `20261105-230409-itl034/`、`20261105-085930-2e9oj5/` — 修复前失败 run 的 events/run.json
- `20261106-005031-zg9ti5/`、`20261106-013312-mxkw5t/`、`20261106-015619-yjtze3/` — 修复后 run
- `app-debug.apk` 同级:`app/build/outputs/apk/debug/app-debug.apk`;`apk-sha256.txt`、`installed-apk-sha256.txt`
- `test-core-settling.log`、`instrument-final2.log`、`instrument-typing-status.log`
- `crash-after-retest.log`、`crash-final.log`(均 0 字节;缓冲区在每次任务前清空)
- `install_confirm.py` — Honor 安装确认自动化(风险提示 → 继续安装 → 通过应用市场提示 → 继续安装)
