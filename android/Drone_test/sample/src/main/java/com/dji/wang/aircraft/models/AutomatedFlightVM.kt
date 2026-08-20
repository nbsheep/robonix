package com.dji.wang.aircraft.models

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.MutableLiveData
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.flightcontroller.*
import dji.sdk.keyvalue.value.camera.CameraShootPhotoMode
import dji.sdk.keyvalue.value.common.Attitude
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.action
import dji.v5.et.create
import dji.v5.et.listen
import dji.v5.et.set
import dji.v5.manager.KeyManager
import dji.v5.manager.SDKManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import dji.v5.utils.common.LogUtils
import kotlin.math.*

/**
 * 飞行任务参数（简单任务模式）
 */
data class MissionParams(
    val climbHeight: Double = 1.0,
    val moveDistance: Double = 0.5,
    val yawAngle: Double = 0.0
)

/**
 * 操作模式
 */
enum class OperationMode {
    STANDBY,      // 待命（默认）
    AUTO_CRUISE,  // 自动巡航（GPS航点）
    MANUAL        // 手动操控
}

/**
 * 自动飞行任务ViewModel
 *
 * 三种操作模式：
 *   STANDBY  — 默认待命，可执行简单参数任务（爬升/旋转/平移→悬停）
 *   AUTO_CRUISE — GPS航点巡航，依次飞行到达后自动返航
 *   MANUAL  — 悬停后手动单步操控
 *
 * 所有飞行均使用虚拟摇杆 + 传感器闭环反馈。
 */
class AutomatedFlightVM : DJIViewModel() {

    enum class MissionState {
        IDLE, TAKEOFF, CLIMBING, YAW_ROTATE, MOVE_LEFT, MOVE_RIGHT,
        HOVERING, LANDING, COMPLETED, ERROR,
        // 巡航专用状态
        CRUISE_TAKEOFF, WAYPOINT_YAW, WAYPOINT_FLY
    }

    // ---- 公开状态 ----
    val missionState = MutableLiveData(MissionState.IDLE)
    val statusMessage = MutableLiveData("待命 - 选择一个模式开始")
    val currentAltitude = MutableLiveData(0.0)
    val isVirtualStickEnabled = MutableLiveData(false)
    val currentParams = MutableLiveData(MissionParams())
    val currentHeading = MutableLiveData(0.0)
    val currentPosition = MutableLiveData<LocationCoordinate2D>()
    val isManualOpActive = MutableLiveData(false)
    val operationMode = MutableLiveData(OperationMode.STANDBY)
    val waypoints = MutableLiveData<List<Waypoint>>(emptyList())
    /** 巡航中当前航点索引（0-based，-1=未巡航） */
    val cruiseWaypointIndex = MutableLiveData(-1)

    // ---- 内部 ----
    private val handler = Handler(Looper.getMainLooper())
    private val climbSpeed = 1.0
    private val moveAngle = 8.0
    private val yawAngularSpeed = 30.0
    private val hoverStabilizeMs = 300L
    private var altitudeListenerRegistered = false
    private var positionListenerRegistered = false
    private var headingListenerRegistered = false
    private var takeoffConfirmed = false
    private var targetAltitude = 0.0
    private var targetBearing = 0.0       // 巡航：目标方位角

    // 闭环基准值
    private var phaseStartHeading = 0.0
    private var phaseStartLat = 0.0
    private var phaseStartLng = 0.0

    private var params = MissionParams()

    // 手动操作追踪
    private var manualOpActive = false
    private var manualOpType = ""
    private var manualTargetValue = 0.0
    private var manualOpDirection = 0.0
    private var manualOpBaseHeading = 0.0
    private var manualOpBaseLat = 0.0
    private var manualOpBaseLng = 0.0

    // 巡航追踪
    private var cruiseActive = false
    private var cruiseWpIdx = -1

    // 超时常量
    private val CLIMB_TIMEOUT_MS = 15_000L
    private val YAW_TIMEOUT_MS = 10_000L
    private val MOVE_TIMEOUT_MS = 10_000L
    private val MISSION_TIMEOUT_MS = 90_000L
    private val TAKEOFF_CONFIRM_TIMEOUT_MS = 15_000L
    private val MANUAL_OP_TIMEOUT_MS = 12_000L
    private val WAYPOINT_FLY_TIMEOUT_MS = 30_000L
    private val VS_REFRESH_INTERVAL_MS = 800L

    // ==================== 虚拟摇杆 ====================

    private fun buildParam(
        roll: Double = 0.0, pitch: Double = 0.0,
        yaw: Double = 0.0, verticalThrottle: Double = 0.0
    ): VirtualStickFlightControlParam {
        return VirtualStickFlightControlParam().apply {
            rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
            verticalControlMode = VerticalControlMode.VELOCITY
            yawControlMode = YawControlMode.ANGULAR_VELOCITY
            rollPitchControlMode = RollPitchControlMode.ANGLE
            this.roll = roll; this.pitch = pitch
            this.yaw = yaw; this.verticalThrottle = verticalThrottle
        }
    }

    @Volatile private var activeParam: VirtualStickFlightControlParam = buildParam()
    private var activePhaseTimeout: Runnable? = null

    private val vsRefreshRunnable = object : Runnable {
        override fun run() {
            val state = missionState.value ?: MissionState.IDLE
            val needRefresh = when (state) {
                MissionState.CLIMBING, MissionState.YAW_ROTATE,
                MissionState.MOVE_LEFT, MissionState.MOVE_RIGHT,
                MissionState.WAYPOINT_YAW, MissionState.WAYPOINT_FLY -> true
                MissionState.HOVERING -> manualOpActive
                else -> false
            }
            if (needRefresh) {
                VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(activeParam)
                handler.postDelayed(this, VS_REFRESH_INTERVAL_MS)
            }
        }
    }

    private val missionTimeoutRunnable = Runnable {
        val s = missionState.value
        if (s != MissionState.IDLE && s != MissionState.COMPLETED &&
            s != MissionState.ERROR && s != MissionState.HOVERING) {
            LogUtils.w(logTag, "⚠ 任务总超时！自动降落")
            statusMessage.value = "安全保护：任务超时，启动降落..."
            cancelCruise()
            manualOpActive = false
            stopVSRefresh()
            disableVS()
            startAutoLanding()
        }
    }

    private val takeoffConfirmTimeoutRunnable = Runnable {
        if (!takeoffConfirmed && (missionState.value == MissionState.TAKEOFF ||
                missionState.value == MissionState.CRUISE_TAKEOFF)) {
            LogUtils.e(logTag, "⚠ 起飞确认超时！")
            statusMessage.value = "起飞确认超时：飞行器未离地，自动降落"
            missionState.value = MissionState.LANDING
            startAutoLanding()
        }
    }

    private fun startSafetyTimers() {
        handler.postDelayed(missionTimeoutRunnable, MISSION_TIMEOUT_MS)
        handler.post(vsRefreshRunnable)
    }

    private fun cancelAllTimers() {
        stopVSRefresh()
        handler.removeCallbacks(missionTimeoutRunnable)
        handler.removeCallbacks(takeoffConfirmTimeoutRunnable)
        activePhaseTimeout?.let { handler.removeCallbacks(it) }
        activePhaseTimeout = null
    }

    private fun stopVSRefresh() {
        handler.removeCallbacks(vsRefreshRunnable)
    }

    private fun disableVS() {
        try {
            VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(false)
            VirtualStickManager.getInstance().disableVirtualStick(null)
        } catch (_: Exception) {}
        isVirtualStickEnabled.postValue(false)
    }

    // ========================================
    //  模式切换
    // ========================================

    fun switchMode(mode: OperationMode) {
        if (missionState.value != MissionState.IDLE) {
            statusMessage.value = "只能在待命状态下切换模式"
            return
        }
        operationMode.value = mode
        when (mode) {
            OperationMode.STANDBY -> statusMessage.value = "待命 - 设置参数后点击「开始任务」"
            OperationMode.AUTO_CRUISE -> statusMessage.value = "自动巡航 - 添加航点后点击「开始巡航」"
            OperationMode.MANUAL -> statusMessage.value = "手动操控 - 先起飞进入悬停后可操控"
        }
        LogUtils.i(logTag, "模式切换: $mode")
    }

    // ========================================
    //  航点管理
    // ========================================

    fun addWaypoint(wp: Waypoint) {
        val list = waypoints.value?.toMutableList() ?: mutableListOf()
        list.add(wp)
        waypoints.value = list
        LogUtils.i(logTag, "添加航点 #${list.size}: (${wp.latitude}, ${wp.longitude}, ${wp.altitude}m)")
    }

    fun removeWaypoint(index: Int) {
        val list = waypoints.value?.toMutableList() ?: return
        if (index in list.indices) {
            list.removeAt(index)
            waypoints.value = list
        }
    }

    fun clearWaypoints() {
        waypoints.value = emptyList()
    }

    fun setWaypoints(list: List<Waypoint>) {
        waypoints.value = list
    }

    // ========================================
    //  简单任务（STANDBY模式）
    // ========================================

    fun startMission(climbHeight: Double, moveDistance: Double, yawAngle: Double) {
        val p = MissionParams(climbHeight, moveDistance, yawAngle)
        startMission(p)
    }

    fun startMission() {
        startMission(currentParams.value ?: MissionParams())
    }

    private fun startMission(p: MissionParams) {
        if (missionState.value != MissionState.IDLE) return
        if (!SDKManager.getInstance().isRegistered) {
            statusMessage.value = "错误：SDK未激活"
            missionState.value = MissionState.ERROR
            return
        }
        params = p
        currentParams.value = p
        manualOpActive = false
        cruiseActive = false
        LogUtils.i(logTag, "任务(独立阶段): 爬${p.climbHeight}m 转${p.yawAngle}° 移${p.moveDistance}m")

        takeoffConfirmed = false
        missionState.value = MissionState.TAKEOFF
        statusMessage.value = "正在起飞..."
        startTakeOff()
    }

    // ========================================
    //  巡航任务（AUTO_CRUISE模式）
    // ========================================

    fun startCruiseMission() {
        if (missionState.value != MissionState.IDLE) return
        if (!SDKManager.getInstance().isRegistered) {
            statusMessage.value = "错误：SDK未激活"
            missionState.value = MissionState.ERROR
            return
        }
        val wps = waypoints.value ?: emptyList()
        if (wps.isEmpty()) {
            statusMessage.value = "错误：请至少添加一个航点"
            return
        }
        cruiseActive = true
        cruiseWpIdx = -1
        cruiseWaypointIndex.value = -1
        manualOpActive = false
        LogUtils.i(logTag, "巡航任务: ${wps.size}个航点")

        takeoffConfirmed = false
        missionState.value = MissionState.CRUISE_TAKEOFF
        statusMessage.value = "巡航起飞中..."
        startTakeOff()
    }

    // ========================================
    //  起飞 & 传感器监听
    // ========================================

    private fun startTakeOff() {
        FlightControllerKey.KeyStartTakeoff.create().action({
            LogUtils.i(logTag, "起飞指令成功")
            statusMessage.value = "起飞成功，等待飞行器离地..."
            startAllSensors()
            handler.postDelayed(takeoffConfirmTimeoutRunnable, TAKEOFF_CONFIRM_TIMEOUT_MS)
        }, { error -> handleError("起飞失败", error) })
    }

    private fun startAllSensors() {
        if (!altitudeListenerRegistered) {
            altitudeListenerRegistered = true
            FlightControllerKey.KeyAltitude.create().listen(this) { alt ->
                alt?.let {
                    currentAltitude.postValue(it.toDouble())
                    checkTakeoffAndClimb(it.toDouble())
                }
            }
        }
        if (!positionListenerRegistered) {
            positionListenerRegistered = true
            FlightControllerKey.KeyAircraftLocation.create().listen(this) { loc ->
                loc?.let {
                    currentPosition.postValue(it)
                    checkHorizontalProgress(it)
                }
            }
        }
        if (!headingListenerRegistered) {
            headingListenerRegistered = true
            FlightControllerKey.KeyCompassHeading.create().listen(this) { hdg ->
                hdg?.let {
                    currentHeading.postValue(it)
                    checkYawProgress(it)
                }
            }
        }
    }

    // ========================================
    //  闭环检查
    // ========================================

    /** 起飞确认 + 爬升/巡航起飞 */
    private fun checkTakeoffAndClimb(alt: Double) {
        // 起飞确认（普通任务 + 巡航）
        if (!takeoffConfirmed && alt >= 0.5) {
            takeoffConfirmed = true
            handler.removeCallbacks(takeoffConfirmTimeoutRunnable)
            LogUtils.i(logTag, "飞行器已离地(${alt}m)")
            statusMessage.value = "飞行器已离地(${alt}m)，启用虚拟摇杆..."
            handler.postDelayed({ vsRetryCount = 0; tryEnableVirtualStick() }, 1000L)
        }
        // 手动爬升完成
        if (manualOpActive && manualOpType == "climb") {
            val reached = if (manualOpDirection > 0) alt >= manualTargetValue
                          else alt <= manualTargetValue
            if (reached) {
                LogUtils.i(logTag, "✓ 手动爬升完成: ${String.format("%.1f", alt)}m")
                finishManualOperation()
            }
            return
        }
        // 巡航：WAYPOINT_FLY 阶段检查高度到达
        if (missionState.value == MissionState.WAYPOINT_FLY && cruiseActive) {
            checkWaypointArrival()
            return
        }
        // 任务爬升完成
        if (missionState.value == MissionState.CLIMBING && alt >= targetAltitude) {
            LogUtils.i(logTag, "✓ 爬升完成: ${alt}m >= ${targetAltitude}m")
            finishClimbAndNext()
        }
    }

    /** 旋转 + 巡航YAW + 手动旋转 */
    private fun checkYawProgress(currentHdg: Double) {
        // 手动旋转
        if (manualOpActive && manualOpType == "rotate") {
            val delta = headingDelta(currentHdg, manualOpBaseHeading)
            if (abs(delta) >= abs(manualTargetValue) - 2.0) {
                LogUtils.i(logTag, "✓ 手动旋转完成: delta=${String.format("%.1f", delta)}°")
                finishManualOperation()
            }
            return
        }
        // 巡航YAW：旋转到目标方位角
        if (missionState.value == MissionState.WAYPOINT_YAW && cruiseActive) {
            val delta = headingDelta(currentHdg, targetBearing)
            if (abs(delta) < 5.0) {  // 5°容差
                LogUtils.i(logTag, "✓ 巡航对准: bearing=${String.format("%.1f", targetBearing)}°")
                onCruiseYawComplete()
            }
            return
        }
        // 任务旋转
        if (missionState.value != MissionState.YAW_ROTATE) return
        val delta = headingDelta(currentHdg, phaseStartHeading)
        if (abs(delta) >= abs(params.yawAngle) - 2.0) {
            LogUtils.i(logTag, "✓ 旋转完成: delta=${String.format("%.1f", delta)}°")
            onYawComplete()
        }
    }

    /** 水平移动 + 巡航飞行 + 手动移动 */
    private fun checkHorizontalProgress(loc: LocationCoordinate2D) {
        // 手动移动
        if (manualOpActive && manualOpType == "move") {
            val dist = haversineDistance(manualOpBaseLat, manualOpBaseLng, loc.latitude, loc.longitude)
            if (dist >= manualTargetValue) {
                LogUtils.i(logTag, "✓ 手动移动完成: ${String.format("%.2f", dist)}m")
                finishManualOperation()
            }
            return
        }
        // 巡航飞行：检查是否到达航点
        if (missionState.value == MissionState.WAYPOINT_FLY && cruiseActive) {
            checkWaypointArrival()
            return
        }
        // 任务移动
        val state = missionState.value ?: return
        if (state != MissionState.MOVE_LEFT && state != MissionState.MOVE_RIGHT) return
        val dist = haversineDistance(phaseStartLat, phaseStartLng, loc.latitude, loc.longitude)
        if (dist >= abs(params.moveDistance)) {
            LogUtils.i(logTag, "✓ 水平移动完成: ${String.format("%.2f", dist)}m >= ${abs(params.moveDistance)}m")
            if (state == MissionState.MOVE_LEFT) onMoveLeftComplete()
            else onMoveRightComplete()
        }
    }

    /** 巡航：检查是否到达当前航点 */
    private fun checkWaypointArrival() {
        val wps = waypoints.value ?: return
        if (cruiseWpIdx !in wps.indices) return
        val wp = wps[cruiseWpIdx]
        val pos = currentPosition.value ?: return
        val alt = currentAltitude.value ?: 0.0

        val dist = haversineDistance(pos.latitude, pos.longitude, wp.latitude, wp.longitude)
        val altOk = abs(alt - wp.altitude) < 0.5

        if (dist < 2.0 && altOk) {
            LogUtils.i(logTag, "✓ 到达航点 #${cruiseWpIdx + 1}: dist=${String.format("%.1f", dist)}m alt=${String.format("%.1f", alt)}m")
            onWaypointArrived()
        }
    }

    // ========================================
    //  虚拟摇杆启用
    // ========================================

    private var vsRetryCount = 0
    private val MAX_VS_RETRIES = 10
    private val VS_RETRY_DELAY_MS = 1500L

    private fun tryEnableVirtualStick() {
        VirtualStickManager.getInstance().enableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                LogUtils.i(logTag, "✅ VS启用成功")
                VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(true)
                isVirtualStickEnabled.value = true

                val baseAlt = currentAltitude.value ?: 0.0
                startSafetyTimers()

                if (cruiseActive) {
                    // 巡航模式：开始飞第一个航点
                    LogUtils.i(logTag, "巡航：开始飞向航点")
                    startCruiseWaypoint(0)
                } else {
                    // 简单任务：按参数决定首个阶段
                    if (params.climbHeight > 0) {
                        targetAltitude = baseAlt + params.climbHeight
                        missionState.value = MissionState.CLIMBING
                        statusMessage.value = "正在上升至 ${String.format("%.1f", targetAltitude)} 米..."
                        sendCommand(buildParam(verticalThrottle = climbSpeed))
                    } else if (params.yawAngle != 0.0) {
                        scheduleNextPhase("旋转", hoverStabilizeMs) { startYawRotation() }
                    } else if (params.moveDistance != 0.0) {
                        scheduleHorizontalMove()
                    } else {
                        scheduleNextPhase("悬停", hoverStabilizeMs) { startHovering() }
                    }
                }
            }
            override fun onFailure(error: IDJIError) {
                if (vsRetryCount < MAX_VS_RETRIES) {
                    vsRetryCount++
                    statusMessage.value = "VS启用失败(${error.errorCode()})，重试$vsRetryCount/$MAX_VS_RETRIES..."
                    handler.postDelayed({ tryEnableVirtualStick() }, VS_RETRY_DELAY_MS)
                } else {
                    LogUtils.e(logTag, "VS彻底失败")
                    statusMessage.value = "虚拟摇杆不可用，自动降落..."
                    missionState.value = MissionState.LANDING
                    cancelAllTimers()
                    cancelCruise()
                    startAutoLanding()
                }
            }
        })
    }

    // ========================================
    //  巡航航点导航
    // ========================================

    private fun startCruiseWaypoint(index: Int) {
        val wps = waypoints.value ?: return
        if (index !in wps.indices) {
            // 全部航点完成 → 返航
            LogUtils.i(logTag, "巡航完成：全部${wps.size}个航点已到达，返航")
            statusMessage.value = "全部航点已完成，开始返航降落..."
            cancelCruise()
            stopVSRefresh()
            sendCommand(buildParam())
            disableVS()
            missionState.value = MissionState.LANDING
            startAutoLanding()
            return
        }
        cruiseWpIdx = index
        cruiseWaypointIndex.value = index
        val wp = wps[index]
        val pos = currentPosition.value
        LogUtils.i(logTag, "巡航 → 航点 #${index + 1}/${wps.size}: (${wp.latitude}, ${wp.longitude}, ${wp.altitude}m)")

        if (pos != null) {
            targetBearing = bearing(pos.latitude, pos.longitude, wp.latitude, wp.longitude)
            phaseStartLat = pos.latitude
            phaseStartLng = pos.longitude
        } else {
            targetBearing = 0.0
        }

        // 第一步：旋转对准目标方向
        missionState.value = MissionState.WAYPOINT_YAW
        statusMessage.value = "航点 #${index + 1}/${wps.size}: 对准方向..."
        val hdg = currentHeading.value ?: 0.0
        val yawDelta = headingDelta(targetBearing, hdg)
        val yawSign = if (yawDelta > 0) 1.0 else -1.0
        sendCommand(buildParam(yaw = yawSign * yawAngularSpeed))
    }

    private fun onCruiseYawComplete() {
        sendCommand(buildParam())
        clearPhaseTimeout()
        scheduleNextPhase("巡航飞行", hoverStabilizeMs) { startCruiseFly() }
    }

    private fun startCruiseFly() {
        val wps = waypoints.value ?: return
        if (cruiseWpIdx !in wps.indices) return
        val wp = wps[cruiseWpIdx]
        val alt = currentAltitude.value ?: 0.0

        missionState.value = MissionState.WAYPOINT_FLY
        statusMessage.value = "航点 #${cruiseWpIdx + 1}/${wps.size}: 飞行中..."

        // 前飞 + 高度调整
        val vertThrottle = when {
            alt < wp.altitude - 0.5 -> climbSpeed
            alt > wp.altitude + 0.5 -> -climbSpeed
            else -> 0.0
        }
        sendCommand(buildParam(pitch = moveAngle, verticalThrottle = vertThrottle))

        setPhaseTimeout(WAYPOINT_FLY_TIMEOUT_MS) {
            LogUtils.w(logTag, "航点 #${cruiseWpIdx + 1} 超时，跳到下一航点")
            onWaypointArrived()
        }
    }

    private fun onWaypointArrived() {
        sendCommand(buildParam())
        clearPhaseTimeout()
        scheduleNextPhase("下一航点", hoverStabilizeMs) {
            startCruiseWaypoint(cruiseWpIdx + 1)
        }
    }

    private fun cancelCruise() {
        cruiseActive = false
        cruiseWpIdx = -1
        cruiseWaypointIndex.value = -1
    }

    // ========================================
    //  阶段调度（简单任务）
    // ========================================

    private fun finishClimbAndNext() {
        sendCommand(buildParam())
        clearPhaseTimeout()
        if (params.yawAngle != 0.0) {
            scheduleNextPhase("旋转", hoverStabilizeMs) { startYawRotation() }
        } else if (params.moveDistance != 0.0) {
            scheduleHorizontalMove()
        } else {
            LogUtils.i(logTag, "爬升完成，无后续阶段，进入悬停")
            scheduleNextPhase("悬停", hoverStabilizeMs) { startHovering() }
        }
    }

    private fun startYawRotation() {
        phaseStartHeading = currentHeading.value ?: 0.0
        val dir = if (params.yawAngle > 0) "右" else "左"
        missionState.value = MissionState.YAW_ROTATE
        statusMessage.value = "旋转${dir} ${String.format("%.0f", abs(params.yawAngle))}° (闭环)..."
        val yawSign = if (params.yawAngle > 0) 1.0 else -1.0
        sendCommand(buildParam(yaw = yawSign * yawAngularSpeed))
        setPhaseTimeout(YAW_TIMEOUT_MS) { onYawComplete() }
    }

    private fun onYawComplete() {
        val delta = headingDelta(currentHeading.value ?: 0.0, phaseStartHeading)
        LogUtils.i(logTag, "旋转结束: 实际转了${String.format("%.1f", delta)}°")
        sendCommand(buildParam())
        clearPhaseTimeout()
        if (params.moveDistance != 0.0) {
            scheduleHorizontalMove()
        } else {
            scheduleNextPhase("悬停", hoverStabilizeMs) { startHovering() }
        }
    }

    private fun scheduleHorizontalMove() {
        if (params.moveDistance < 0) {
            scheduleNextPhase("左移", hoverStabilizeMs) { startMoveLeft() }
        } else {
            scheduleNextPhase("右移", hoverStabilizeMs) { startMoveRight() }
        }
    }

    private fun startMoveLeft() {
        val pos = currentPosition.value
        if (pos != null) { phaseStartLat = pos.latitude; phaseStartLng = pos.longitude }
        missionState.value = MissionState.MOVE_LEFT
        statusMessage.value = "← 向左平移 ${abs(params.moveDistance)}m (闭环)..."
        sendCommand(buildParam(roll = -moveAngle))
        setPhaseTimeout(MOVE_TIMEOUT_MS) { onMoveLeftComplete() }
    }

    private fun onMoveLeftComplete() {
        val dist = computeMoveDist()
        LogUtils.i(logTag, "左移结束: 实际${String.format("%.2f", dist)}m → 进入悬停")
        startHovering()
    }

    private fun startMoveRight() {
        val pos = currentPosition.value
        if (pos != null) { phaseStartLat = pos.latitude; phaseStartLng = pos.longitude }
        missionState.value = MissionState.MOVE_RIGHT
        statusMessage.value = "→ 向右平移 ${abs(params.moveDistance)}m (闭环)..."
        sendCommand(buildParam(roll = moveAngle))
        setPhaseTimeout(MOVE_TIMEOUT_MS) { onMoveRightComplete() }
    }

    private fun onMoveRightComplete() {
        val dist = computeMoveDist()
        LogUtils.i(logTag, "右移结束: 实际${String.format("%.2f", dist)}m → 进入悬停")
        startHovering()
    }

    private fun computeMoveDist(): Double {
        val pos = currentPosition.value
        return if (pos != null) haversineDistance(phaseStartLat, phaseStartLng, pos.latitude, pos.longitude) else abs(params.moveDistance)
    }

    // ========================================
    //  悬停
    // ========================================

    private fun startHovering() {
        cancelAllTimers()
        cruiseActive = false
        sendCommand(buildParam())
        missionState.value = MissionState.HOVERING
        statusMessage.value = "悬停中 - 可执行手动操控或点击返航降落"
        LogUtils.i(logTag, "进入悬停状态")
    }

    // ========================================
    //  起飞悬停（手动操控入口）
    // ========================================

    /** 一键起飞并进入悬停状态，不执行任何任务参数 */
    fun takeoffAndHover() {
        if (missionState.value != MissionState.IDLE) {
            statusMessage.value = "只能在就绪状态下起飞"
            return
        }
        if (!SDKManager.getInstance().isRegistered) {
            statusMessage.value = "错误：SDK未激活"
            missionState.value = MissionState.ERROR
            return
        }
        // 全部参数置零，起飞后直接进入HOVERING
        params = MissionParams(0.0, 0.0, 0.0)
        currentParams.value = params
        manualOpActive = false
        cruiseActive = false
        takeoffConfirmed = false
        LogUtils.i(logTag, "起飞悬停")
        missionState.value = MissionState.TAKEOFF
        statusMessage.value = "起飞悬停中..."
        startTakeOff()
    }

    // ========================================
    //  手动操控
    // ========================================

    fun manualClimb(deltaHeight: Double) {
        if (missionState.value != MissionState.HOVERING) {
            statusMessage.value = "只能在悬停状态下执行手动操控"
            return
        }
        if (abs(deltaHeight) < 0.1) { statusMessage.value = "爬升/下降高度至少 0.1 米"; return }
        val currentAlt = currentAltitude.value ?: 0.0
        manualTargetValue = currentAlt + deltaHeight
        manualOpDirection = if (deltaHeight > 0) 1.0 else -1.0
        manualOpType = "climb"
        manualOpActive = true
        isManualOpActive.value = true
        val dirLabel = if (deltaHeight > 0) "上升" else "下降"
        statusMessage.value = "手动${dirLabel} ${String.format("%.1f", abs(deltaHeight))}m (闭环)..."
        val throttle = if (deltaHeight > 0) climbSpeed else -climbSpeed
        sendCommand(buildParam(verticalThrottle = throttle))
        handler.post(vsRefreshRunnable)
        setPhaseTimeout(MANUAL_OP_TIMEOUT_MS) { finishManualOperation() }
    }

    fun manualMoveLeft(distance: Double) {
        if (missionState.value != MissionState.HOVERING) { statusMessage.value = "只能在悬停状态下执行手动操控"; return }
        startManualMove(distance, rollSign = -1.0, label = "左移")
    }

    fun manualMoveRight(distance: Double) {
        if (missionState.value != MissionState.HOVERING) { statusMessage.value = "只能在悬停状态下执行手动操控"; return }
        startManualMove(distance, rollSign = 1.0, label = "右移")
    }

    private fun startManualMove(distance: Double, rollSign: Double, label: String) {
        if (distance < 0.1) { statusMessage.value = "平移距离至少 0.1 米"; return }
        val pos = currentPosition.value
        if (pos != null) { manualOpBaseLat = pos.latitude; manualOpBaseLng = pos.longitude }
        manualTargetValue = distance
        manualOpType = "move"
        manualOpActive = true
        isManualOpActive.value = true
        statusMessage.value = "${label} ${String.format("%.1f", distance)}m (闭环)..."
        sendCommand(buildParam(roll = rollSign * moveAngle))
        handler.post(vsRefreshRunnable)
        setPhaseTimeout(MANUAL_OP_TIMEOUT_MS) { finishManualOperation() }
    }

    fun manualMoveForward(distance: Double) {
        if (missionState.value != MissionState.HOVERING) { statusMessage.value = "只能在悬停状态下执行手动操控"; return }
        startManualMovePitch(distance, pitchSign = 1.0, label = "前进")
    }

    fun manualMoveBackward(distance: Double) {
        if (missionState.value != MissionState.HOVERING) { statusMessage.value = "只能在悬停状态下执行手动操控"; return }
        startManualMovePitch(distance, pitchSign = -1.0, label = "后退")
    }

    private fun startManualMovePitch(distance: Double, pitchSign: Double, label: String) {
        if (distance < 0.1) { statusMessage.value = "平移距离至少 0.1 米"; return }
        val pos = currentPosition.value
        if (pos != null) { manualOpBaseLat = pos.latitude; manualOpBaseLng = pos.longitude }
        manualTargetValue = distance
        manualOpType = "move"
        manualOpActive = true
        isManualOpActive.value = true
        statusMessage.value = "${label} ${String.format("%.1f", distance)}m (闭环)..."
        sendCommand(buildParam(pitch = pitchSign * moveAngle))
        handler.post(vsRefreshRunnable)
        setPhaseTimeout(MANUAL_OP_TIMEOUT_MS) { finishManualOperation() }
    }

    fun manualRotate(degrees: Double) {
        if (missionState.value != MissionState.HOVERING) { statusMessage.value = "只能在悬停状态下执行手动操控"; return }
        if (abs(degrees) < 1.0) { statusMessage.value = "旋转角度至少 1°"; return }
        manualOpBaseHeading = currentHeading.value ?: 0.0
        manualTargetValue = degrees
        manualOpType = "rotate"
        manualOpActive = true
        isManualOpActive.value = true
        val dir = if (degrees > 0) "右转" else "左转"
        statusMessage.value = "${dir} ${String.format("%.0f", abs(degrees))}° (闭环)..."
        val yawSign = if (degrees > 0) 1.0 else -1.0
        sendCommand(buildParam(yaw = yawSign * yawAngularSpeed))
        handler.post(vsRefreshRunnable)
        setPhaseTimeout(MANUAL_OP_TIMEOUT_MS) { finishManualOperation() }
    }

    private fun finishManualOperation() {
        manualOpActive = false
        manualOpType = ""
        isManualOpActive.value = false
        sendCommand(buildParam())
        clearPhaseTimeout()
        stopVSRefresh()
        missionState.value = MissionState.HOVERING
        statusMessage.value = "操作完成 — 悬停中，可继续操控或点击返航"
    }

    // ========================================
    //  云台 / 相机（v1 精简原语）
    // ========================================

    /** 设置云台姿态（绝对角度，度）。pitch 俯仰 / roll 横滚 / yaw 偏航。 */
    fun setGimbalAttitude(pitch: Double, roll: Double, yaw: Double) {
        try {
            val key = KeyTools.createKey(GimbalKey.KeyGimbalAttitude, ComponentIndexType.LEFT_OR_MAIN)
            key.set(Attitude(pitch, roll, yaw))
            statusMessage.value = String.format("云台姿态 → pitch=%.1f° roll=%.1f° yaw=%.1f°", pitch, roll, yaw)
            LogUtils.i(logTag, "setGimbalAttitude(pitch=$pitch, roll=$roll, yaw=$yaw)")
        } catch (e: Exception) {
            LogUtils.w(logTag, "云台设置失败: ${e.message}")
            statusMessage.value = "云台设置失败: ${e.message}"
        }
    }

    /** 触发单张拍照。 */
    fun takePhoto() {
        try {
            CameraKey.KeyShootPhotoMode.create().set(CameraShootPhotoMode.NORMAL)
            CameraKey.KeyStartShootPhoto.create().action({
                LogUtils.i(logTag, "拍照指令已发送")
                statusMessage.value = "已触发拍照 ✓"
            }, { error ->
                LogUtils.w(logTag, "拍照失败: ${error.description()}")
                statusMessage.value = "拍照失败: ${error.description()}"
            })
        } catch (e: Exception) {
            LogUtils.w(logTag, "拍照异常: ${e.message}")
            statusMessage.value = "拍照异常: ${e.message}"
        }
    }

    /** 设置相机混合变焦倍率（如 1.0~28.0）。 */
    fun setCameraZoom(factor: Double) {
        try {
            CameraKey.KeyCameraZoomRatios.create().set(factor)
            statusMessage.value = String.format("变焦倍率 → %.1fx", factor)
            LogUtils.i(logTag, "setCameraZoom(factor=$factor)")
        } catch (e: Exception) {
            LogUtils.w(logTag, "变焦设置失败: ${e.message}")
            statusMessage.value = "变焦设置失败: ${e.message}"
        }
    }

    // ========================================
    //  返航 / 重置
    // ========================================

    fun goHome() {
        LogUtils.i(logTag, "手动触发归航降落")
        cancelAllTimers()
        cancelCruise()
        manualOpActive = false
        stopVSRefresh()
        sendCommand(buildParam())
        disableVS()
        missionState.value = MissionState.LANDING
        statusMessage.value = "正在归航降落..."
        startAutoLanding()
    }

    fun resetUI() {
        val alt = currentAltitude.value ?: 0.0
        if (alt > 0.1) {
            statusMessage.value = "⚠ 无人机仍在空中(${String.format("%.1f", alt)}m)，请先降落再重置"
            return
        }
        cancelAllTimers()
        disableVS()
        takeoffConfirmed = false
        manualOpActive = false
        cruiseActive = false
        cruiseWpIdx = -1
        missionState.value = MissionState.IDLE
        statusMessage.value = when (operationMode.value) {
            OperationMode.STANDBY -> "待命 - 设置参数后点击「开始任务」"
            OperationMode.AUTO_CRUISE -> "自动巡航 - 添加航点后点击「开始巡航」"
            OperationMode.MANUAL -> "手动操控 - 先起飞进入悬停后可操控"
            else -> "就绪"
        }
        LogUtils.i(logTag, "UI已重置")
    }

    private fun startAutoLanding() {
        FlightControllerKey.KeyStartAutoLanding.create().action({
            LogUtils.i(logTag, "降落成功！")
            statusMessage.value = "降落成功，任务完成！✓"
            missionState.value = MissionState.COMPLETED
            cancelCruise()
        }, { error ->
            LogUtils.w(logTag, "降落失败: ${error.description()}，尝试返航")
            statusMessage.value = "降落失败，启动智能返航..."
            startGoHome()
        })
    }

    private fun startGoHome() {
        FlightControllerKey.KeyStartGoHome.create().action({
            statusMessage.value = "智能返航已启动！✓"
            missionState.value = MissionState.COMPLETED
            cancelCruise()
        }, { error -> handleError("返航失败", error) })
    }

    // ========================================
    //  辅助
    // ========================================

    private fun sendCommand(param: VirtualStickFlightControlParam) {
        activeParam = param
        VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
    }

    private fun setPhaseTimeout(timeoutMs: Long, onTimeout: () -> Unit) {
        activePhaseTimeout?.let { handler.removeCallbacks(it) }
        activePhaseTimeout = Runnable { onTimeout() }
        handler.postDelayed(activePhaseTimeout!!, timeoutMs)
    }

    private fun clearPhaseTimeout() {
        activePhaseTimeout?.let { handler.removeCallbacks(it) }
        activePhaseTimeout = null
    }

    private fun scheduleNextPhase(name: String, delayMs: Long, action: () -> Unit) {
        LogUtils.i(logTag, "悬停${delayMs}ms后进入: $name")
        handler.postDelayed(action, delayMs)
    }

    private fun handleError(message: String, error: IDJIError) {
        LogUtils.e(logTag, "$message: ${error.description()} (${error.errorCode()})")
        statusMessage.value = "$message: ${error.description()}"
        missionState.value = MissionState.ERROR
        cancelAllTimers()
        cancelCruise()
        manualOpActive = false
        sendCommand(buildParam())
    }

    fun emergencyStop() {
        cancelAllTimers()
        sendCommand(buildParam())
        takeoffConfirmed = false
        manualOpActive = false
        cancelCruise()
        missionState.value = MissionState.ERROR
        statusMessage.value = "紧急停止！飞行器已悬停"
    }

    fun resetMission() {
        val alt = currentAltitude.value ?: 0.0
        if (alt > 0.1) { goHome(); return }
        resetUI()
    }

    override fun onCleared() {
        super.onCleared()
        cancelAllTimers()
        manualOpActive = false
        cancelCruise()
        KeyManager.getInstance().cancelListen(this)
    }

    // ========================================
    //  数学工具
    // ========================================

    companion object {
        fun headingDelta(current: Double, start: Double): Double {
            var d = current - start
            if (d > 180) d -= 360
            if (d < -180) d += 360
            return d
        }

        fun haversineDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val R = 6371000.0
            val dLat = (lat2 - lat1) * PI / 180.0
            val dLng = (lng2 - lng1) * PI / 180.0
            val a = sin(dLat / 2).pow(2) +
                    cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) * sin(dLng / 2).pow(2)
            return R * 2 * atan2(sqrt(a), sqrt(1 - a))
        }

        /** 初始方位角（度） */
        fun bearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val dLng = (lng2 - lng1) * PI / 180.0
            val y = sin(dLng) * cos(lat2 * PI / 180.0)
            val x = cos(lat1 * PI / 180.0) * sin(lat2 * PI / 180.0) -
                    sin(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) * cos(dLng)
            return (atan2(y, x) * 180.0 / PI + 360) % 360
        }
    }
}
