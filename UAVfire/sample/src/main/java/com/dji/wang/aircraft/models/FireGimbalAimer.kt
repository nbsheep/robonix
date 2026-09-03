package com.dji.wang.aircraft.models

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 云台对准：把检测框中心相对画面中心的偏移换算成云台步进角度。
 * 与 fire_patrol.py 的 gimbal_aim.GimbalAimer 语义一致。
 */
class FireGimbalAimer(
    var frameW: Int,
    var frameH: Int,
    private val hfov: Double = 60.0,
    private val vfov: Double = 40.0,
    private val deadzone: Double = 0.12,
    private val maxStep: Double = 15.0
) {
    /** 返回需要执行的云台动作列表，每项为 (动作名, 步进角度)。 */
    fun aim(box: FireDetector.Detection): List<Pair<String, Double>> {
        val cx = (box.x1 + box.x2) / 2.0
        val cy = (box.y1 + box.y2) / 2.0
        val dxNorm = (cx - frameW / 2.0) / (frameW / 2.0)  // + = 目标在画面右侧
        val dyNorm = (cy - frameH / 2.0) / (frameH / 2.0)  // + = 目标在画面下方
        val actions = ArrayList<Pair<String, Double>>()
        if (abs(dxNorm) > deadzone) {
            val step = min(maxStep, max(0.5, abs(dxNorm) * hfov))
            actions.add((if (dxNorm > 0) "yaw_right" else "yaw_left") to step)
        }
        if (abs(dyNorm) > deadzone) {
            val step = min(maxStep, max(0.5, abs(dyNorm) * vfov))
            actions.add((if (dyNorm > 0) "pitch_down" else "pitch_up") to step)
        }
        return actions
    }
}
