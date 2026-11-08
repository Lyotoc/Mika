package com.wangzan101.mika.core.uistate

/**
 * 写入之后等输入框「落定」再读回,重试只做整段替换。
 *
 * 输入框的写入(ACTION_SET_TEXT、粘贴)都是异步的:App 可能过一会儿才把字显示出来。不等待就读回,
 * 会把「还没应用」当成「没写进去」,于是再写一遍;第二遍如果是粘贴而旧内容没被选全,同一段字就成了两遍。
 * 安卓 10 的小红书搜索框上实测过这个后果:「情侣游戏 升温」变成「情侣游戏 升温情侣游戏 升温」。
 *
 * 所以这里做两件事:
 * - 每次写入后等读回与刚写的字一致(最多 [waitMs]),让调用方的核对看到的是 App 应用之后的状态;
 * - 重试(换通道再写一遍)之前先清空并确认真的空了:只有清空成功才换通道写,否则退回原通道——
 *   原通道是整段设字,本来就是替换,不会追加。清不空又换通道粘贴,就是重复输入的来源。
 *
 * [now]、[sleep] 由调用方给(真机用 SystemClock/Thread.sleep,测试用假时钟)。
 */
class SettlingField(
    private val field: TextField,
    private val now: () -> Long,
    private val sleep: (Long) -> Unit,
    /** 把输入框设成空串(替换语义);返回动作是否被接受。 */
    private val clear: () -> Boolean,
    private val waitMs: Long = 1_200L,
    private val pollMs: Long = 80L,
) : TextField {
    override fun read(): String? = field.read()

    override fun write(text: String): Boolean {
        val accepted = field.write(text)
        if (accepted) await(text)
        return accepted
    }

    override fun retry(text: String): Boolean {
        // 第二遍只允许整段替换:先清空并确认框真的空了,再换通道写。
        // 清不空(读不回、选不中)时退回原通道再写一遍——SET_TEXT 本身就是替换,不会把字接在后面。
        if (!cleared()) return replace(text)
        val accepted = field.retry(text)
        if (accepted) await(text)
        return accepted
    }

    /** 等读回与 [want] 一致(比较时去掉空白);等到了返回 true,超时返回 false。 */
    fun await(want: String): Boolean {
        val target = want.filterNot { it.isWhitespace() }
        val deadline = now() + waitMs
        while (true) {
            if (field.read()?.filterNot { it.isWhitespace() } == target) return true
            if (now() >= deadline) return false
            sleep(pollMs)
        }
    }

    private fun cleared(): Boolean = clear() && await("")

    private fun replace(text: String): Boolean {
        val accepted = field.write(text)
        if (accepted) await(text)
        return accepted
    }
}
