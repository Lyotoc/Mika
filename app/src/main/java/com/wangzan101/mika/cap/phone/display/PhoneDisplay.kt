package com.wangzan101.mika.cap.phone.display

import android.graphics.Bitmap
import kotlinx.coroutines.flow.StateFlow

/**
 * 一次采样用的像素来源。拿它在 [PhoneDisplay.sampleFrame] 的回调里读像素时,画面由发布方持有,
 * 不会被换掉或回收;出了回调就不能再用它。
 */
class FrameView(val width: Int, val height: Int, private val reader: (Int, Int) -> Int) {
    fun pixel(x: Int, y: Int): Int = reader(x, y)
}

/**
 * 任务操作的那块屏。安卓 11 起是 Mika 自己建的虚拟屏([AgentDisplay]);安卓 10 上没有能看第二块屏的无障碍,
 * 任务改用用户的主屏([MainScreenDisplay])。两者对 [com.wangzan101.mika.cap.phone.a11y.AndroidScreen] 长得一样:
 * 屏号、尺寸、实时画面、截图。
 */
interface PhoneDisplay {
    /** 安卓的屏号;主屏是 0。 */
    val displayId: Int

    /** 逻辑宽高(随屏旋转)。 */
    val width: Int
    val height: Int

    /** 实时画面(缩到短边 ≤360,限速):给任务界面上的预览用。 */
    val frames: StateFlow<Bitmap?>

    /**
     * 在画面不会被换掉、也不会被回收的时候读它的像素;当前没有画面时为 null。
     *
     * [frames] 里的位图是共享的:界面预览会一直拿着它画,判定画面变化的代码也会拿它取像素。所以发布方不能
     * 假设「换帧时没人正在读」——安卓 10 的主屏预览曾经在发布新帧后立刻回收旧帧,另一条线程正在 getPixel,
     * 直接 native abort(不可捕获,整个进程退出)。采样一律走这里:发布方保证回调期间画面有效。
     */
    fun <T> sampleFrame(block: (FrameView) -> T): T? = frames.value?.let { bmp ->
        block(FrameView(bmp.width, bmp.height, bmp::getPixel))
    }

    /** 整幅 PNG;截不到返回 null。 */
    fun latestFramePng(): ByteArray?

    /** 整幅位图(OCR 用),调用方负责 recycle;截不到返回 null。 */
    fun latestFrameBitmap(): Bitmap?

    /** 任务结束了:放掉这块屏(虚拟屏销毁;主屏只是不再轮询截图)。 */
    fun release()
}
