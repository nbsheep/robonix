package com.dji.wang.aircraft.models

import android.media.MediaScannerConnection
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.MutableLiveData
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.value.flightcontroller.*
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.CameraLensType
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode
import dji.sdk.keyvalue.value.gimbal.GimbalMode
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.action
import dji.v5.et.create
import dji.v5.et.createCamera
import dji.v5.et.get
import dji.v5.et.listen
import dji.v5.et.set
import dji.v5.manager.KeyManager
import dji.v5.manager.SDKManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.media.*
import dji.v5.utils.common.ContextUtil
import dji.v5.utils.common.LogUtils
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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
    /** 巡航是否进行中（用于UI展示云台控制与巡航反馈） */
    val isCruiseActive = MutableLiveData(false)
    /** 巡航是否已暂停（紧急悬停，用于UI切换「暂停/重启」按钮） */
    val isCruisePaused = MutableLiveData(false)
    /** 巡航执行反馈（清晰显示在界面） */
    val cruiseFeedback = MutableLiveData("")
    /** 相机操作反馈（拍照/录像/变焦，巡航中解锁） */
    val cameraFeedback = MutableLiveData("")

    // ---- 内部 ----
    private val handler = Handler(Looper.getMainLooper())
    private val climbSpeed = 1.0
    private val moveAngle = 8.0             // 水平平移 roll 角（左右移动，正值=右移）
    private val forwardPitchAngle = -8.0    // 前飞 pitch 角：实测 DJI MSDK V5 pitch 正值=后退，前进用负值
    private val yawAngularSpeed = 30.0
    private val hoverStabilizeMs = 0L
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
    // 巡航：航段拆解状态
    private var cruiseLegStartLat = 0.0
    private var cruiseLegStartLng = 0.0
    private var cruiseLegTotalM = 0.0
    private var cruiseSegmentTargetM = 0.0
    private var cruiseSegmentStartDist = 0.0   // 本步开始时的到目标水平距离（用于方向错误检测）
    private var cruiseYawCompleted = false
    private var cruiseTransitioning = false    // 巡航阶段切换防重入
    private var cruiseYawRetryCount = 0        // 对准超时后重新校准次数

    // 超时常量
    private val CLIMB_TIMEOUT_MS = 15_000L
    private val YAW_TIMEOUT_MS = 10_000L
    private val MOVE_TIMEOUT_MS = 10_000L
    private val MISSION_TIMEOUT_MS = 90_000L
    private val TAKEOFF_CONFIRM_TIMEOUT_MS = 15_000L
    private val MANUAL_OP_TIMEOUT_MS = 12_000L
    private val WAYPOINT_FLY_TIMEOUT_MS = 30_000L
    private val VS_REFRESH_INTERVAL_MS = 100L
    // 巡航：航段拆解（将两航点间GPS距离拆成多个短距移动指令）
    private val cruiseSegmentLengthM = 2.0       // 每个移动段落的长度（米）
    private val cruiseArrivalRadiusM = 0.5       // 到达航点判定半径（米，水平0.5m）
    private val CRUISE_SEGMENT_TIMEOUT_MS = 10_000L
    private val CRUISE_YAW_TIMEOUT_MS = 5_000L   // 巡航对准方向最小超时（动态：按转向角延长）
    private val CRUISE_YAW_ALIGN_TOLERANCE = 5.0 // 对准容差（度）
    private val MAX_CRUISE_YAW_RETRY = 3         // 对准超时后最多重新校准次数
    // 云台：巡航默认俯视角度
    private val gimbalDownPitchDeg = -60.0      // 俯视（地面/水平线以下）默认俯仰角
    // 相机：混合变焦（Mavic 3E 变焦镜头 CAMERA_LENS_ZOOM，光学+数字全程）
    private val HYBRID_ZOOM_MULTIPLIER = 1.5    // 每次点击的变焦倍率（1.5x）
    private val HYBRID_MIN_FOCAL_FALLBACK = 104    // 兜底最广焦距 0.1mm（约 10.4mm）
    private val HYBRID_MAX_FOCAL_FALLBACK = 6240   // 兜底最长焦距 0.1mm（约 624mm ≈ 60x）
    private var hybridMinFocal = HYBRID_MIN_FOCAL_FALLBACK   // 变焦镜头最广焦距（0.1mm）
    private var hybridMaxFocal = HYBRID_MAX_FOCAL_FALLBACK   // 变焦镜头最长焦距（0.1mm，含数字变焦）
    private var currentFocal = HYBRID_MIN_FOCAL_FALLBACK     // 当前焦距（0.1mm）

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
        if (missionState.value != MissionState.IDLE) {
            LogUtils.w(logTag, "飞行中忽略航点添加")
            return
        }
        val list = waypoints.value?.toMutableList() ?: mutableListOf()
        list.add(wp)
        waypoints.value = list
        LogUtils.i(logTag, "添加航点 #${list.size}: (${wp.latitude}, ${wp.longitude}, ${wp.altitude}m)")
    }

    fun removeWaypoint(index: Int) {
        if (missionState.value != MissionState.IDLE) return
        val list = waypoints.value?.toMutableList() ?: return
        if (index in list.indices) {
            list.removeAt(index)
            waypoints.value = list
        }
    }

    fun clearWaypoints() {
        if (missionState.value != MissionState.IDLE) return
        waypoints.value = emptyList()
    }

    fun setWaypoints(list: List<Waypoint>) {
        if (missionState.value != MissionState.IDLE) return
        waypoints.value = list
    }

    /** 航点上移（调整顺序） */
    fun moveWaypointUp(index: Int) {
        if (missionState.value != MissionState.IDLE) return
        val list = waypoints.value?.toMutableList() ?: return
        if (index <= 0 || index >= list.size) return
        val tmp = list[index]; list[index] = list[index - 1]; list[index - 1] = tmp
        waypoints.value = list
        LogUtils.i(logTag, "航点 #${index + 1} 上移到 #$index")
    }

    /** 航点下移（调整顺序） */
    fun moveWaypointDown(index: Int) {
        if (missionState.value != MissionState.IDLE) return
        val list = waypoints.value?.toMutableList() ?: return
        if (index < 0 || index >= list.size - 1) return
        val tmp = list[index]; list[index] = list[index + 1]; list[index + 1] = tmp
        waypoints.value = list
        LogUtils.i(logTag, "航点 #${index + 1} 下移到 #${index + 2}")
    }

    /**
     * 提前启动位置监听，保证起飞前即可获取当前GPS坐标。
     * （原 startAllSensors 只在起飞后注册，地面阶段无法取到定位）
     */
    fun ensurePositionListening() {
        if (positionListenerRegistered) return
        positionListenerRegistered = true
        try {
            FlightControllerKey.KeyAircraftLocation.create().listen(this) { loc ->
                loc?.let {
                    currentPosition.postValue(it)
                    checkHorizontalProgress(it)
                }
            }
            LogUtils.i(logTag, "位置监听已启动（起飞前）")
        } catch (e: Exception) {
            positionListenerRegistered = false
            LogUtils.e(logTag, "位置监听启动失败: ${e.message}")
        }
    }

    /**
     * 获取当前GPS坐标（不自动添加航点）。
     * 由界面填入经纬度输入框，用户设置高度后通过「+」按钮添加航点。
     * @return 当前坐标，未获取到GPS返回 null
     */
    fun getCurrentGps(): LocationCoordinate2D? {
        val pos = currentPosition.value
        if (pos == null) {
            statusMessage.value = "未获取到GPS位置，请确认无人机已连接并完成定位"
        }
        return pos
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
        isCruiseActive.value = false
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
        isCruiseActive.value = true
        isCruisePaused.value = false
        cruiseWpIdx = -1
        cruiseWaypointIndex.value = -1
        manualOpActive = false
        hybridMinFocal = HYBRID_MIN_FOCAL_FALLBACK
        hybridMaxFocal = HYBRID_MAX_FOCAL_FALLBACK
        currentFocal = HYBRID_MIN_FOCAL_FALLBACK
        cameraFeedback.value = ""
        cruiseFeedback.value = "巡航启动 · ${wps.size}个航点 · 准备起飞..."
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
        // 起飞确认（普通任务 + 巡航）—— 仅当明确下达了起飞指令（TAKEOFF/CRUISE_TAKEOFF）时才触发，
        // 避免手持抬升无人机到一定高度后自动进入巡航/飞行。
        val st = missionState.value
        val expectingTakeoff = st == MissionState.TAKEOFF || st == MissionState.CRUISE_TAKEOFF
        if (expectingTakeoff && !takeoffConfirmed && alt >= 0.5) {
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
            if (abs(delta) < CRUISE_YAW_ALIGN_TOLERANCE) {
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

    /** 巡航：检查是否到达当前航点 / 当前航段是否完成 */
    private fun checkWaypointArrival() {
        val wps = waypoints.value ?: return
        if (cruiseWpIdx !in wps.indices) return
        val wp = wps[cruiseWpIdx]
        val pos = currentPosition.value ?: return
        val alt = currentAltitude.value ?: 0.0

        val dist = haversineDistance(pos.latitude, pos.longitude, wp.latitude, wp.longitude)
        val altOk = abs(alt - wp.altitude) < 0.5

        // 实时反馈：距目标距离与高度（距离取0.5m粒度，避免GPS抖动刷屏）
        cruiseFeedback.value = "航点 ${cruiseWpIdx + 1}/${wps.size} · 距目标 ${String.format("%.1f", (dist * 2).roundToInt() / 2.0)}m · 高度 ${String.format("%.1f", alt)}m"

        // 1) 到达判定（水平 0.5m、高度 0.5m）
        if (dist < cruiseArrivalRadiusM && altOk) {
            LogUtils.i(logTag, "✓ 到达航点 #${cruiseWpIdx + 1}: dist=${String.format("%.1f", dist)}m alt=${String.format("%.1f", alt)}m")
            onWaypointArrived()
            return
        }

        // 2) 方向错误检测：离目标比本步开始时更远（说明飞偏了），立即停杆重新对准
        if (cruiseSegmentStartDist > 0.0 && dist > cruiseSegmentStartDist + 0.5) {
            LogUtils.w(logTag, "⚠ 离目标变远(${String.format("%.1f", dist)}m > ${String.format("%.1f", cruiseSegmentStartDist)}m)，重新对准")
            cruiseFeedback.value = "航点 ${cruiseWpIdx + 1}/${wps.size} · 方向修正中..."
            onCruiseSegmentComplete()
            return
        }

        // 3) 步进完成判定：到目标距离已减少到本步预期值（朝目标前飞了约 2m）
        if (cruiseSegmentTargetM > 0.0 && dist <= cruiseSegmentTargetM) {
            LogUtils.i(logTag, "✓ 航段完成: 剩余${String.format("%.1f", dist)}m <= 目标${String.format("%.1f", cruiseSegmentTargetM)}m")
            onCruiseSegmentComplete()
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
                    // 巡航模式：云台设为自由模式并向下俯视，读取变焦镜头规格，然后开始飞第一个航点
                    LogUtils.i(logTag, "巡航：开始飞向航点")
                    setupCruiseGimbal()
                    setupCruiseCamera()
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

        // 记录本段航线起点与总距离
        if (pos != null) {
            cruiseLegStartLat = pos.latitude
            cruiseLegStartLng = pos.longitude
            cruiseLegTotalM = haversineDistance(pos.latitude, pos.longitude, wp.latitude, wp.longitude)
        } else {
            cruiseLegTotalM = 0.0
        }
        val segCount = if (cruiseLegTotalM > 0) max(1.0, ceil(cruiseLegTotalM / cruiseSegmentLengthM)).toInt() else 0
        LogUtils.i(logTag, "航点 #${index + 1} 距离 ${String.format("%.1f", cruiseLegTotalM)}m，拆分为 $segCount 段移动")
        cruiseFeedback.value = "航点 ${index + 1}/${wps.size} · 距离 ${String.format("%.1f", cruiseLegTotalM)}m · 目标高度 ${String.format("%.1f", wp.altitude)}m"

        // 第一步：旋转对准目标方向（机头 = 相机云台一侧朝向航点）
        enterCruiseYaw()
    }

    /** 对准当前航点方向（每段移动前都重新对准，修正漂移） */
    private fun enterCruiseYaw() {
        val wps = waypoints.value ?: return
        if (cruiseWpIdx !in wps.indices) return
        val wp = wps[cruiseWpIdx]
        val pos = currentPosition.value
        if (pos != null) {
            targetBearing = bearing(pos.latitude, pos.longitude, wp.latitude, wp.longitude)
        } else {
            targetBearing = 0.0
        }
        cruiseYawCompleted = false
        missionState.value = MissionState.WAYPOINT_YAW
        statusMessage.value = "航点 #${cruiseWpIdx + 1}/${wps.size}: 对准方向..."
        cruiseFeedback.value = "航点 ${cruiseWpIdx + 1}/${wps.size} · 对准方向 ${String.format("%.0f", targetBearing)}°"
        cruiseYawRetryCount = 0
        // 每次对准前云台 yaw 回正朝机头（jointRef=true 相对机身），使「云台头=机头=目标方向」；
        // 飞行中云台可自由转且不影响飞行方向（飞行方向由机头锁定），下一段对准前会再次回正
        sendGimbalRotation(GimbalAngleRotationMode.ABSOLUTE_ANGLE, null, null, 0.0, jointRef = true)
        val hdg = currentHeading.value ?: 0.0
        val yawDelta = headingDelta(targetBearing, hdg)
        val yawSign = if (yawDelta > 0) 1.0 else -1.0
        sendCommand(buildParam(yaw = yawSign * yawAngularSpeed))
        // 动态超时：按转向角估算时间；超时后重新校准（对准了才飞），避免带偏差起飞
        setPhaseTimeout(yawAlignTimeoutMs(abs(yawDelta))) { onCruiseYawTimeout() }
    }

    /** 对准超时：重新读航向继续校准，对准了才进入飞行；多次仍不对准则降级进入（靠方向纠错兜底） */
    private fun onCruiseYawTimeout() {
        if (cruiseYawCompleted) return
        val hdg = currentHeading.value ?: 0.0
        val delta = headingDelta(targetBearing, hdg)
        // 已对准（闭环判定可能延迟，超时瞬间其实已到位）→ 直接进入飞行
        if (abs(delta) < CRUISE_YAW_ALIGN_TOLERANCE) {
            LogUtils.i(logTag, "超时时已对准: delta=${String.format("%.1f", delta)}°")
            onCruiseYawComplete()
            return
        }
        cruiseYawRetryCount++
        if (cruiseYawRetryCount >= MAX_CRUISE_YAW_RETRY) {
            // 多次校准仍未到位：停止空转，进入飞行（此时靠方向纠错兜底，避免无限旋转）
            LogUtils.w(logTag, "对准重试${cruiseYawRetryCount}次未到位，进入飞行依赖方向纠错")
            onCruiseYawComplete()
            return
        }
        val yawSign = if (delta > 0) 1.0 else -1.0
        sendCommand(buildParam(yaw = yawSign * yawAngularSpeed))
        LogUtils.w(logTag, "对准超时，重新校准第${cruiseYawRetryCount}次: 还需转${String.format("%.1f", delta)}°")
        setPhaseTimeout(yawAlignTimeoutMs(abs(delta))) { onCruiseYawTimeout() }
    }

    /** 按转向角动态估算对准超时：|delta|/角速度 + 启动余量，下限 5s */
    private fun yawAlignTimeoutMs(yawDeltaDeg: Double): Long {
        val needMs = abs(yawDeltaDeg) / yawAngularSpeed * 1000.0
        return max(CRUISE_YAW_TIMEOUT_MS.toDouble(), needMs + 3000.0).toLong()
    }

    private fun onCruiseYawComplete() {
        if (cruiseYawCompleted) return
        cruiseYawCompleted = true
        sendCommand(buildParam())
        clearPhaseTimeout()
        scheduleNextPhase("巡航分段飞行", hoverStabilizeMs) { startCruiseSegment() }
    }

    /** 执行一段短距移动：先对准方向后，朝航点前飞约 2m，通过「到目标距离」判定步进 */
    private fun startCruiseSegment() {
        val wps = waypoints.value ?: return
        if (cruiseWpIdx !in wps.indices) return
        val wp = wps[cruiseWpIdx]
        val pos = currentPosition.value
        val alt = currentAltitude.value ?: 0.0

        if (pos == null) {
            // 无定位：原地等待，稍后重新对准
            cruiseSegmentTargetM = 0.0
            cruiseSegmentStartDist = 0.0
            missionState.value = MissionState.WAYPOINT_FLY
            statusMessage.value = "航点 #${cruiseWpIdx + 1}/${wps.size}: 等待GPS定位..."
            sendCommand(buildParam())
            setPhaseTimeout(CRUISE_SEGMENT_TIMEOUT_MS) { onCruiseSegmentComplete() }
            return
        }

        val remaining = haversineDistance(pos.latitude, pos.longitude, wp.latitude, wp.longitude)
        val altDiff = alt - wp.altitude
        val altOk = abs(altDiff) < 0.5

        // 到达判定（水平 0.5m、高度 0.5m）
        if (remaining < cruiseArrivalRadiusM && altOk) {
            LogUtils.i(logTag, "✓ 到达航点 #${cruiseWpIdx + 1}: 剩余${String.format("%.1f", remaining)}m")
            onWaypointArrived()
            return
        }

        // 记录本步开始的到目标水平距离，用于方向错误检测与步进判定
        cruiseSegmentStartDist = remaining

        // 是否已水平就位（仅差高度）
        val nearHorizontal = remaining < cruiseArrivalRadiusM

        if (nearHorizontal) {
            // 已在正上方：原地调高度，不再水平前飞
            cruiseSegmentTargetM = 0.0
            missionState.value = MissionState.WAYPOINT_FLY
            statusMessage.value = "航点 #${cruiseWpIdx + 1}/${wps.size}: 调整高度至 ${String.format("%.1f", wp.altitude)}m ..."
            cruiseFeedback.value = "航点 ${cruiseWpIdx + 1}/${wps.size} · 调整高度至 ${String.format("%.1f", wp.altitude)}m"
        } else {
            // 本步完成后的预期剩余距离 = 当前剩余 - 2m（即朝目标飞约 2m）
            cruiseSegmentTargetM = max(0.0, remaining - cruiseSegmentLengthM)
            missionState.value = MissionState.WAYPOINT_FLY
            statusMessage.value = "航点 #${cruiseWpIdx + 1}/${wps.size}: 前飞 ${String.format("%.1f", min(remaining, cruiseSegmentLengthM))}m ..."
            cruiseFeedback.value = "航点 ${cruiseWpIdx + 1}/${wps.size} · 前飞 2m · 剩余 ${String.format("%.1f", remaining)}m"
        }

        val vertThrottle = when {
            altDiff < -0.5 -> climbSpeed
            altDiff > 0.5 -> -climbSpeed
            else -> 0.0
        }
        val pitch = if (nearHorizontal) 0.0 else forwardPitchAngle
        sendCommand(buildParam(pitch = pitch, verticalThrottle = vertThrottle))

        setPhaseTimeout(CRUISE_SEGMENT_TIMEOUT_MS) {
            LogUtils.w(logTag, "航段超时，重新对准")
            onCruiseSegmentComplete()
        }
    }

    /** 一个航段完成后：停止→重新对准→下一段，直到到达航点 */
    private fun onCruiseSegmentComplete() {
        if (cruiseTransitioning) return
        cruiseTransitioning = true
        cruiseSegmentTargetM = 0.0
        cruiseSegmentStartDist = 0.0
        sendCommand(buildParam())
        clearPhaseTimeout()
        scheduleNextPhase("重新对准", hoverStabilizeMs) {
            cruiseTransitioning = false
            enterCruiseYaw()
        }
    }

    private fun onWaypointArrived() {
        if (cruiseTransitioning) return
        cruiseTransitioning = true
        cruiseFeedback.value = "✓ 到达航点 #${cruiseWpIdx + 1}"
        sendCommand(buildParam())
        clearPhaseTimeout()
        scheduleNextPhase("下一航点", hoverStabilizeMs) {
            cruiseTransitioning = false
            startCruiseWaypoint(cruiseWpIdx + 1)
        }
    }

    private fun cancelCruise() {
        cruiseActive = false
        isCruiseActive.value = false
        isCruisePaused.value = false
        cruiseWpIdx = -1
        cruiseWaypointIndex.value = -1
    }

    /** 紧急悬停：中止当前巡航任务，在当前位置悬停（保留巡航进度与航点列表） */
    fun pauseCruise() {
        if (!cruiseActive) return
        if (isCruisePaused.value == true) return
        cruiseTransitioning = false
        cruiseSegmentTargetM = 0.0
        cruiseSegmentStartDist = 0.0
        sendCommand(buildParam())
        clearPhaseTimeout()
        stopVSRefresh()
        isCruisePaused.value = true
        missionState.value = MissionState.HOVERING
        cruiseFeedback.value = "⏸ 巡航已暂停 · 当前位置悬停"
        statusMessage.value = "巡航已暂停 - 点击「重启巡航」继续"
        LogUtils.i(logTag, "紧急悬停：巡航暂停于航点 #${cruiseWpIdx + 1} 附近")
    }

    /** 重启巡航：把当前GPS作为新航点「插入」到上一航点之后（保留原航线不覆写），再继续飞行 */
    fun resumeCruise() {
        if (!cruiseActive) return
        if (isCruisePaused.value != true) return
        val pos = currentPosition.value
        if (pos == null) {
            cruiseFeedback.value = "⚠ 等待GPS定位，稍后再重启..."
            statusMessage.value = "等待GPS定位..."
            return
        }
        // 紧急悬停后：把当前GPS位置「插入」为航点（插在上一航点之后、当前航点之前），
        // 覆写当前航点会导致原航点丢失、多次暂停后航线提前结束
        val list = waypoints.value?.toMutableList() ?: mutableListOf()
        val alt = currentAltitude.value ?: 0.0
        if (cruiseWpIdx in list.indices) {
            list.add(cruiseWpIdx, Waypoint(pos.latitude, pos.longitude, alt))
            waypoints.value = list
            cruiseWpIdx++                 // 指向原当前航点（已后移一位），继续飞原航线
            cruiseWaypointIndex.value = cruiseWpIdx
            LogUtils.i(logTag, "紧急悬停恢复：插入当前点为航点 #${cruiseWpIdx}，原航点后移为 #${cruiseWpIdx + 1}")
        }
        isCruisePaused.value = false
        cruiseTransitioning = false
        cruiseFeedback.value = "▶ 重启巡航 · 从当前位置飞向航点 ${cruiseWpIdx + 1}"
        statusMessage.value = "重启巡航中..."
        // 重新启动虚拟摇杆刷新，然后以当前GPS为新起点重新对准当前航点
        handler.post(vsRefreshRunnable)
        enterCruiseYaw()
        LogUtils.i(logTag, "重启巡航：从 (${pos.latitude}, ${pos.longitude}) 重新对准航点 #${cruiseWpIdx + 1}")
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
        isCruiseActive.value = false
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
        isCruiseActive.value = false
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
    //  云台控制（巡航）
    // ========================================

    /** 发送云台角度旋转指令 */
    private fun sendGimbalRotation(mode: GimbalAngleRotationMode, pitch: Double?, roll: Double?, yaw: Double?, jointRef: Boolean = false) {
        val rotation = GimbalAngleRotation().apply {
            setMode(mode)
            setPitch(pitch)
            setRoll(roll)
            setYaw(yaw)
            setPitchIgnored(pitch == null)
            setRollIgnored(roll == null)
            setYawIgnored(yaw == null)
            setDuration(0.8)
            setJointReferenceUsed(jointRef)
            setTimeout(2)
        }
        GimbalKey.KeyRotateByAngle.create().action(rotation,
            { LogUtils.i(logTag, "云台旋转指令已发送") },
            { error -> LogUtils.w(logTag, "云台旋转失败: ${error.description()}") })
    }

    /** 巡航开始：云台切换为自由(Free)模式并向下俯视，获取地面/水平线以下视角 */
    private fun setupCruiseGimbal() {
        GimbalKey.KeyGimbalMode.create().set(GimbalMode.FREE,
            { LogUtils.i(logTag, "云台已切换为自由(Free)模式") },
            { error -> LogUtils.w(logTag, "云台模式设置失败: ${error.description()}") })
        // 俯仰-60°（向下俯视），roll/yaw 相对机身归零（朝机头正前方）
        sendGimbalRotation(GimbalAngleRotationMode.ABSOLUTE_ANGLE, gimbalDownPitchDeg, 0.0, 0.0, jointRef = true)
    }

    // 飞行阶段云台控制：jointRef=false（地面/世界坐标系）——云台 yaw/pitch 只转相机、独立于机身，
    // 不随机身偏航联动；对准阶段（enterCruiseYaw）才用 jointRef=true 把云台锁定到机头。
    // 注意：Mavic 3E 云台 yaw 有机械限位，超出限位飞控会「辅助」旋转机身来补偿，导致机身被连带转动，
    // 故把单次 yaw 步进限制在 ±45° 内，避免触发机身联动偏航。
    private val gimbalYawStepLimitDeg = 45.0
    fun gimbalPitchUp(stepDeg: Double)   { sendGimbalRotation(GimbalAngleRotationMode.RELATIVE_ANGLE,  stepDeg, null, null, jointRef = false) }
    fun gimbalPitchDown(stepDeg: Double) { sendGimbalRotation(GimbalAngleRotationMode.RELATIVE_ANGLE, -stepDeg, null, null, jointRef = false) }
    fun gimbalYawLeft(stepDeg: Double)   { sendGimbalRotation(GimbalAngleRotationMode.RELATIVE_ANGLE, null, null, -stepDeg.coerceAtMost(gimbalYawStepLimitDeg), jointRef = false) }
    fun gimbalYawRight(stepDeg: Double)  { sendGimbalRotation(GimbalAngleRotationMode.RELATIVE_ANGLE, null, null,  stepDeg.coerceAtMost(gimbalYawStepLimitDeg), jointRef = false) }
    /** 恢复默认俯视视角 */
    fun gimbalLookDown()  { sendGimbalRotation(GimbalAngleRotationMode.ABSOLUTE_ANGLE, gimbalDownPitchDeg, null, null, jointRef = false) }
    /** 云台水平（平视） */
    fun gimbalLevel()     { sendGimbalRotation(GimbalAngleRotationMode.ABSOLUTE_ANGLE, 0.0, null, null, jointRef = false) }

    // ========================================
    //  相机控制（巡航）
    // ========================================

    // ========================================
    //  拍照/录像结果下载与保存
    //  遥控器端(APP按钮) → 系统图库(DCIM + 媒体扫描)
    //  web端(浏览器)      → UAV-PIC 文件夹（外部存储根目录，即用户所说的“D盘”对应位置）
    // ========================================

    private enum class MediaSaveTarget { GALLERY, UAV_PIC }
    /** 等待照片/视频写入相机SD卡后再拉取列表（毫秒） */
    private val mediaSaveDelayMs = 3000L
    private var pendingRecordTarget: MediaSaveTarget? = null
    private var mediaDownloading = false

    /** 拍摄单张照片 */
    fun cameraTakePhoto(fromWeb: Boolean = false) {
        val target = if (fromWeb) MediaSaveTarget.UAV_PIC else MediaSaveTarget.GALLERY
        CameraKey.KeyStartShootPhoto.create().action({
            LogUtils.i(logTag, "拍照指令已发送")
            cameraFeedback.value = "📷 已拍摄照片"
            scheduleMediaDownload(target)
        }, { error ->
            LogUtils.w(logTag, "拍照失败: ${error.description()}")
            cameraFeedback.value = "拍照失败: ${error.description()}"
        })
    }

    /** 开始录像 */
    fun cameraStartRecord(fromWeb: Boolean = false) {
        pendingRecordTarget = if (fromWeb) MediaSaveTarget.UAV_PIC else MediaSaveTarget.GALLERY
        CameraKey.KeyStartRecord.create().action({
            LogUtils.i(logTag, "录像开始")
            cameraFeedback.value = "🎥 录像中..."
        }, { error ->
            LogUtils.w(logTag, "开始录像失败: ${error.description()}")
            cameraFeedback.value = "开始录像失败: ${error.description()}"
        })
    }

    /** 停止录像 */
    fun cameraStopRecord(fromWeb: Boolean = false) {
        // 保存目标以「开始录像」时的来源为准；缺失则退回本次来源
        val target = pendingRecordTarget ?: if (fromWeb) MediaSaveTarget.UAV_PIC else MediaSaveTarget.GALLERY
        CameraKey.KeyStopRecord.create().action({
            LogUtils.i(logTag, "录像停止")
            cameraFeedback.value = "⏹ 录像已停止"
            scheduleMediaDownload(target)
            pendingRecordTarget = null
        }, { error ->
            LogUtils.w(logTag, "停止录像失败: ${error.description()}")
            cameraFeedback.value = "停止录像失败: ${error.description()}"
        })
    }

    /** 延时后拉取相机媒体列表并下载最新文件（给SD卡写入留时间） */
    private fun scheduleMediaDownload(target: MediaSaveTarget) {
        handler.postDelayed({ pullLatestMediaAndDownload(target) }, mediaSaveDelayMs)
    }

    /** 拉取最新媒体文件并下载保存 */
    private fun pullLatestMediaAndDownload(target: MediaSaveTarget) {
        if (mediaDownloading) return
        mediaDownloading = true
        try {
            MediaDataCenter.getInstance().mediaManager.pullMediaFileListFromCamera(
                PullMediaFileListParam.Builder().mediaFileIndex(-1).count(5).build(),
                object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        val latest = MediaDataCenter.getInstance().mediaManager.mediaFileListData.data.firstOrNull()
                        if (latest == null) {
                            mediaDownloading = false
                            cameraFeedback.value = "⚠ 保存失败：媒体列表为空"
                            return
                        }
                        downloadAndSave(latest, target)
                    }
                    override fun onFailure(error: IDJIError) {
                        mediaDownloading = false
                        LogUtils.e(logTag, "拉取媒体列表失败: ${error.description()}")
                        cameraFeedback.value = "保存失败: ${error.description()}"
                    }
                })
        } catch (e: Exception) {
            mediaDownloading = false
            LogUtils.e(logTag, "媒体下载异常: ${e.message}")
            cameraFeedback.value = "保存失败: ${e.message}"
        }
    }

    /** 下载单个媒体文件到目标位置（图库或 UAV-PIC 文件夹） */
    private fun downloadAndSave(mediaFile: MediaFile, target: MediaSaveTarget) {
        val context = ContextUtil.getContext()
        val destDir: File
        val toGallery: Boolean
        when (target) {
            MediaSaveTarget.GALLERY -> {
                destDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                toGallery = true
            }
            MediaSaveTarget.UAV_PIC -> {
                destDir = File(Environment.getExternalStorageDirectory(), "UAV-PIC")
                toGallery = false
            }
        }
        if (!destDir.exists()) destDir.mkdirs()
        val destFile = File(destDir, mediaFile.fileName)
        val outputStream = FileOutputStream(destFile, false)
        val bos = BufferedOutputStream(outputStream)
        mediaFile.pullOriginalMediaFileFromCamera(0L, object : MediaFileDownloadListener {
            override fun onStart() { LogUtils.i(logTag, "开始下载媒体: ${mediaFile.fileName}") }
            override fun onProgress(total: Long, current: Long) {}
            override fun onRealtimeDataUpdate(data: ByteArray, position: Long) {
                try { bos.write(data); bos.flush() } catch (e: IOException) {
                    LogUtils.e(logTag, "写文件失败: ${e.message}")
                }
            }
            override fun onFinish() {
                try { outputStream.close(); bos.close() } catch (e: IOException) {
                    LogUtils.e(logTag, "关闭文件失败: ${e.message}")
                }
                mediaDownloading = false
                if (toGallery) {
                    MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), null, null)
                    cameraFeedback.value = "✅ 已保存到系统图库: ${mediaFile.fileName}"
                } else {
                    cameraFeedback.value = "✅ 已保存到 UAV-PIC: ${mediaFile.fileName}"
                }
                LogUtils.i(logTag, "媒体保存完成: ${destFile.absolutePath}")
            }
            override fun onFailure(error: IDJIError?) {
                mediaDownloading = false
                try { outputStream.close(); bos.close() } catch (_: IOException) {}
                if (destFile.exists()) destFile.delete()
                LogUtils.e(logTag, "媒体下载失败: ${error?.description()}")
                cameraFeedback.value = "保存失败: 下载出错"
            }
        })
    }

    /** 画面放大（混合变焦：光学 + 数字） */
    fun cameraZoomIn() {
        currentFocal = (currentFocal * HYBRID_ZOOM_MULTIPLIER).roundToInt().coerceIn(hybridMinFocal, hybridMaxFocal)
        setHybridZoom()
    }

    /** 画面缩小（混合变焦） */
    fun cameraZoomOut() {
        currentFocal = (currentFocal / HYBRID_ZOOM_MULTIPLIER).roundToInt().coerceIn(hybridMinFocal, hybridMaxFocal)
        setHybridZoom()
    }

    /** 变焦镜头 key 工厂：Mavic 3E 的变焦镜头（非广角主摄） */
    private fun <T> zoomLensKey(info: DJIKeyInfo<T>) =
        info.createCamera(ComponentIndexType.LEFT_OR_MAIN, CameraLensType.CAMERA_LENS_ZOOM)

    /** 当前变焦倍率（相对变焦镜头最广端），用于界面显示 */
    private fun currentZoomFactor(): Double =
        if (hybridMinFocal > 0) currentFocal.toDouble() / hybridMinFocal.toDouble() else 1.0

    /** 设置混合变焦焦距（作用于变焦镜头） */
    private fun setHybridZoom() {
        zoomLensKey(CameraKey.KeyCameraHybridZoomFocalLength).set(currentFocal,
            {
                LogUtils.i(logTag, "混合变焦至 ${String.format("%.1f", currentZoomFactor())}x（${String.format("%.1f", currentFocal / 10.0)}mm）")
                cameraFeedback.value = "画面缩放至 ${String.format("%.1f", currentZoomFactor())}x"
            },
            { error ->
                LogUtils.w(logTag, "变焦失败: ${error.description()}")
                cameraFeedback.value = "变焦失败: ${error.description()}"
            })
    }

    /** 巡航开始：读取 Mavic 3E 变焦镜头混合变焦规格并同步当前焦距 */
    private fun setupCruiseCamera() {
        zoomLensKey(CameraKey.KeyCameraHybridZoomSpec).get({ spec ->
            spec?.let {
                val min = it.minFocalLength ?: 0
                val max = it.maxFocalLength ?: 0
                if (min > 0 && max > min) {
                    hybridMinFocal = min
                    hybridMaxFocal = max
                }
                LogUtils.i(logTag, "混合变焦焦距 ${min}-${max}(0.1mm)，最大倍率 ${String.format("%.1f", max.toDouble() / min.toDouble())}x")
            }
            syncCameraZoomFactor()
        }, { error ->
            LogUtils.w(logTag, "读取混合变焦规格失败: ${error.description()}，使用默认范围")
            syncCameraZoomFactor()
        })
    }

    /** 同步当前变焦焦距 */
    private fun syncCameraZoomFactor() {
        zoomLensKey(CameraKey.KeyCameraHybridZoomFocalLength).get({ focal ->
            focal?.let {
                currentFocal = it.coerceIn(hybridMinFocal, hybridMaxFocal)
                LogUtils.i(logTag, "当前混合变焦焦距 ${it}(0.1mm)，倍率 ${String.format("%.1f", currentZoomFactor())}x")
            }
        }, { error ->
            LogUtils.w(logTag, "读取当前焦距失败: ${error.description()}")
        })
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
        isCruiseActive.value = false
        isCruisePaused.value = false
        cruiseWpIdx = -1
        cruiseFeedback.value = ""
        cameraFeedback.value = ""
        hybridMinFocal = HYBRID_MIN_FOCAL_FALLBACK
        hybridMaxFocal = HYBRID_MAX_FOCAL_FALLBACK
        currentFocal = HYBRID_MIN_FOCAL_FALLBACK
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
