package com.wangzan101.mika.cap.phone.a11y

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.display.LazyDisplay
import com.wangzan101.mika.cap.phone.display.MainScreenDisplay
import com.wangzan101.mika.cap.phone.display.PhoneDisplay
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.Verification
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 安卓 10 的主屏路径(真实助手 + 主屏无障碍 + screencap),不用模型、不往外发消息。
 *
 * 覆盖两件在 Honor Play20 上实测出过问题的行为:
 * - 预览帧被发布方回收,另一条线程正在 getPixel → native abort(整个进程退出);
 * - 输入框读回晚到,读回「对不上」又写一遍 → 同一段字写了两遍。
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(maxSdkVersion = 29)
class MainScreenDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val shell = DeviceSupport.shell()

    private fun display() = MainScreenDisplay(
        shell, 720, 1600,
        File(context.cacheDir, "main-screen").apply { mkdirs() },
    )

    private fun lazyOf(d: PhoneDisplay) = LazyDisplay<PhoneDisplay>(d.width, d.height) { d }.also { runBlocking { it.get() } }

    /**
     * 安卓 10(Honor MagicUI)上,系统不会在 instrumentation 重启进程后重新绑定无障碍服务:这时只跳过依赖无障碍的用例,
     * 并写明跳过原因(这种情况下输入路径由真机任务跑和 core 的单测覆盖)。
     */
    private fun serviceOrSkip(): ScreenAccessibility? {
        val s = runCatching { DeviceSupport.service() }.getOrNull()
        org.junit.Assume.assumeTrue(
            "Mika 无障碍服务在 instrumentation 进程里没有绑定(安卓 10 不自动重绑)",
            s != null,
        )
        return s
    }

    /** 发布出去的预览帧交给读的人持有:换帧时不能回收,否则正在采样的线程会 native abort。 */
    @Test
    fun publishedPreviewsAreNotRecycledWhileRead() {
        val d = display()
        try {
            assertNotNull("preview frame", DeviceSupport.pollUntil(15_000) { d.frames.value })
            val seen = mutableListOf<Bitmap>()
            val end = SystemClock.uptimeMillis() + 8_000
            while (SystemClock.uptimeMillis() < end) {
                val cur = d.frames.value
                if (cur == null) {
                    Thread.sleep(100)
                    continue
                }
                // 采样走 sampleFrame:回调期间画面有效
                d.sampleFrame { f -> f.pixel(f.width / 2, f.height / 2) }
                assertFalse("current preview was recycled", cur.isRecycled)
                // 之前取到的帧还在测试手里:发布方换帧不能回收它们
                seen.forEach { assertFalse("an earlier preview was recycled", it.isRecycled) }
                if (seen.lastOrNull() !== cur) seen += cur
                Thread.sleep(150)
            }
            assertTrue("expected several published previews, got ${seen.size}", seen.size > 3)
        } finally {
            d.release()
        }
    }

    /** 读回晚到的输入框:等落定后应当一次写成、核对为一致,而不是写两遍。 */
    @Test
    fun typingIntoAFieldThatReportsLateIsNotDuplicated() = runBlocking<Unit> {
        val d = display()
        try {
            DeviceSupport.sh("am start -n ${DeviceSupport.TEST_PACKAGE}/${SlowEchoTestActivity::class.java.name}")
            val service = serviceOrSkip() ?: return@runBlocking
            val screen = AndroidScreen(service, shell, lazyOf(d))
            val snap = DeviceSupport.pollUntil(15_000) {
                runBlocking { screen.snapshot() }.takeIf { s -> s.elements.any { it.editable && it.name == SLOW } }
            }
            assertNotNull("test page readable", snap)
            val field = snap!!.elements.first { it.editable && it.name == SLOW }
            val typed = screen.typeText(field.ref, snap.snapshotId, KEYWORD)
            assertTrue("type result: $typed", typed is ActionResult.Ok)
            assertEquals("readback after the app applied the text", Verification.MATCH, (typed as ActionResult.Ok).verification)
            assertEquals("field content", KEYWORD, actualText(screen))
        } finally {
            d.release()
        }
    }

    /** 读回一直卡住的输入框:核对可以如实报不了一致,但绝不能把同一段字写成两遍。 */
    @Test
    fun retryOnAStuckReadbackDoesNotAppendASecondCopy() = runBlocking<Unit> {
        val d = display()
        try {
            DeviceSupport.sh("am start -n ${DeviceSupport.TEST_PACKAGE}/${SlowEchoTestActivity::class.java.name}")
            val service = serviceOrSkip() ?: return@runBlocking
            val screen = AndroidScreen(service, shell, lazyOf(d))
            val snap = DeviceSupport.pollUntil(15_000) {
                runBlocking { screen.snapshot() }.takeIf { s -> s.elements.any { it.editable && it.name == STUCK } }
            }
            assertNotNull("test page readable", snap)
            val field = snap!!.elements.first { it.editable && it.name == STUCK }
            val typed = screen.typeText(field.ref, snap.snapshotId, KEYWORD)
            assertTrue("type result: $typed", typed is ActionResult.Ok)
            assertNotEquals("stuck readback cannot be reported as a match", Verification.MATCH, (typed as ActionResult.Ok).verification)
            assertEquals("field content must not be appended twice", KEYWORD, actualText(screen))
        } finally {
            d.release()
        }
    }

    /** 页面上「实际:」那行:输入框里真正的字(读回卡住时也照常更新)。 */
    private fun actualText(screen: AndroidScreen): String {
        val shown = DeviceSupport.pollUntil(10_000) {
            runBlocking { screen.snapshot() }.elements.map { it.name }.firstOrNull { it.startsWith(SlowEchoTestActivity.LABEL) }
        }
        assertNotNull("actual-text line on the test page", shown)
        return shown!!.removePrefix(SlowEchoTestActivity.LABEL)
    }

    private companion object {
        const val KEYWORD = "情侣游戏 升温"
        const val SLOW = "慢回显输入框"
        const val STUCK = "回显卡住的框"
    }
}
