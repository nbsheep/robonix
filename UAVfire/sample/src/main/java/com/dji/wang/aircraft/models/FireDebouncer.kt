package com.dji.wang.aircraft.models

/**
 * 火情去抖：连续命中 trigger 帧判定为火情，连续 miss release 帧解除。
 * 与 fire_patrol.py 的 alarm.Debouncer 语义一致。
 */
class FireDebouncer(private val trigger: Int = 5, private val release: Int = 15) {
    private var hit = 0
    private var miss = 0
    private var alarmed = false

    val isAlarmed: Boolean get() = alarmed

    fun update(detected: Boolean): Boolean {
        if (detected) {
            hit++
            miss = 0
        } else {
            miss++
            hit = 0
        }
        when {
            !alarmed && hit >= trigger -> alarmed = true
            alarmed && miss >= release -> alarmed = false
        }
        return alarmed
    }
}
