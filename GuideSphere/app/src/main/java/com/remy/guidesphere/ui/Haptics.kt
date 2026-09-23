package com.remy.guidesphere.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * 触觉反馈。
 *
 * 引导球是个「盲操作」场景 —— 用户举着手机绕物体转圈，眼睛大多盯在真实物体上，
 * 而不是屏幕。三档震动让用户不看屏幕也知道自己做到哪一步了：
 *
 *  - [tick]          轻点：按钮被按下，确认输入已被接收；
 *  - [faceCaptured]  中震：镜头正对的那个面整片点亮了（= 拍下了一面）；
 *  - [allDone]       庆祝节奏：8 个面全部采集完成。
 *
 * 设备没有马达（部分模拟器）时全部静默降级为空操作，不抛异常、不需要调用方判断。
 */
class Haptics(context: Context) {

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    /** 是否有可用的振动马达 */
    val available: Boolean = runCatching { vibrator?.hasVibrator() == true }.getOrDefault(false)

    /** 轻点：按钮确认 */
    fun tick() = oneShot(14L, 70)

    /** 一整个面被采集到了 */
    fun faceCaptured() = waveform(longArrayOf(0L, 18L, 45L, 30L), intArrayOf(0, 120, 0, 190))

    /** 8 个面全部完成 */
    fun allDone() =
        waveform(longArrayOf(0L, 26L, 70L, 26L, 70L, 46L), intArrayOf(0, 150, 0, 150, 0, 255))

    private fun oneShot(durationMs: Long, amplitude: Int) {
        if (!available) return
        runCatching {
            vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
        }.onFailure { Log.w(TAG, "震动失败: $it") }
    }

    private fun waveform(timings: LongArray, amplitudes: IntArray) {
        if (!available) return
        runCatching {
            vibrator?.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        }.onFailure { Log.w(TAG, "震动失败: $it") }
    }

    private companion object {
        const val TAG = "Haptics"
    }
}
