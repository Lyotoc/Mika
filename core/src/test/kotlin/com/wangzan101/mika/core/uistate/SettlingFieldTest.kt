package com.wangzan101.mika.core.uistate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 写入落定与重试的语义:写进去的字要等 App 应用后读回才算数,重试只做整段替换。
 * 安卓 10 小红书搜索框上出现过「情侣游戏 升温情侣游戏 升温」:第一遍已经写进去,读回还没更新,
 * 第二遍走粘贴而旧内容没选中,就追加成了两段。
 */
class SettlingFieldTest {
    /**
     * 假输入框:[real] 是真正写进去的字,[shown] 是对读回报告的字。
     * 一次写入要等到之后再读 [applyAfterReads] 次,[shown] 才变成 [real] —— 这就是「写入是异步的」。
     * 原通道(设字)是替换,[retry] 走的是粘贴:只往光标处插,没清空就接在后面。
     */
    private class FakeField(private val applyAfterReads: Int = 0) : TextField {
        var real = ""
        private var shown = ""
        private var pending: String? = null
        private var due = 0
        private var reads = 0
        var retries = 0

        fun seed(text: String) {
            real = text
            shown = text
            pending = null
        }

        override fun read(): String? {
            reads++
            val p = pending
            if (p != null && reads >= due) {
                shown = p
                pending = null
            }
            return shown
        }

        override fun write(text: String): Boolean {
            real = text
            schedule()
            return true
        }

        override fun retry(text: String): Boolean {
            retries++
            real = real + text
            schedule()
            return true
        }

        private fun schedule() {
            if (applyAfterReads == 0) {
                shown = real
                pending = null
            } else {
                pending = real
                due = reads + applyAfterReads
            }
        }
    }

    private var clock = 0L
    private val sleep: (Long) -> Unit = { ms -> clock += ms }

    private fun settling(f: FakeField, waitMs: Long = 400, pollMs: Long = 50) =
        SettlingField(f, { clock }, sleep, clear = { f.write("") }, waitMs = waitMs, pollMs = pollMs)

    @Test fun writeWaitsUntilTheAppShowsTheText() {
        val f = FakeField(applyAfterReads = 3)
        val io = settling(f)
        assertTrue(io.write("情侣游戏 升温"))
        assertEquals("读回要等 App 应用之后再看", "情侣游戏 升温", io.read())
    }

    @Test fun aWriteThatNeverShowsUpStillReturnsAcceptedButReadsOldText() {
        val f = FakeField(applyAfterReads = 1_000)
        val io = settling(f, waitMs = 300)
        assertTrue(io.write("情侣游戏 升温"))
        assertEquals("读回还是旧的", "", io.read())
        assertEquals("字其实写进去了", "情侣游戏 升温", f.real)
    }

    @Test fun retryClearsBeforePastingSoTheTextIsNotDuplicated() {
        // 读回永远报空(卡住的框),而第一次写入的字其实已经进去了
        val real = FakeField()
        val stuck = object : TextField {
            override fun read(): String? = ""
            override fun write(text: String): Boolean = real.write(text)
            override fun retry(text: String): Boolean = real.retry(text)
        }
        val io = SettlingField(stuck, { clock }, sleep, clear = { real.write("") }, waitMs = 100, pollMs = 20)
        assertTrue(io.write("情侣游戏 升温"))
        assertTrue(io.retry("情侣游戏 升温"))
        assertEquals("第二遍必须整段替换,不能接在后面", "情侣游戏 升温", real.real)
        assertEquals("第二遍走的是换通道(粘贴)", 1, real.retries)
    }

    @Test fun retryFallsBackToReplacingWhenTheFieldCannotBeCleared() {
        // 清不掉(设空被拒)时不能换通道粘贴,否则会追加;退回原通道设字(替换)
        val f = FakeField().apply { seed("旧内容") }
        val noClear = object : TextField {
            override fun read(): String? = "旧内容"
            override fun write(text: String): Boolean = f.write(text)
            override fun retry(text: String): Boolean = f.retry(text)
        }
        val io = SettlingField(noClear, { clock }, sleep, clear = { false }, waitMs = 100, pollMs = 20)
        assertTrue(io.retry("新内容"))
        assertEquals("新内容", f.real)
        assertEquals("没清空就不许走粘贴通道", 0, f.retries)
    }

    @Test fun awaitGivesUpAfterTheDeadline() {
        val f = FakeField(applyAfterReads = 1_000)
        val io = settling(f, waitMs = 250, pollMs = 50)
        assertFalse(io.await("不会出现"))
        assertTrue("等满 waitMs 就返回", clock >= 250)
    }
}
