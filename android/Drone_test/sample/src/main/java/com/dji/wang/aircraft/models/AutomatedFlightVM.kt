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
 * 用于STANDBY模式下的基本飞行任务配置
 */
data class MissionParams(
    val climbHeight: Double = 1.0,    // 爬升高度（米）
    val moveDistance: Double = 0.5,   // 水平移动距离（米）
    val yawAngle: Double = 0.0        // 旋转角度（度，正值=右转，负值=左转）
)

/**
 * 操作模式枚举
 * 定义无人机的三种工作模式
 */
enum class OperationMode {
    STANDBY,      // 待命模式：执行简单的爬升/旋转/平移任务
    AUTO_CRUISE,  // 自动巡航模式：按GPS航点依次飞行
    MANUAL        // 手动操控模式：悬停后可手动控制单步移动
}

/**
 * 自动飞行任务ViewModel
 *
 * 核心功能：
 * 1. 管理无人机的飞行状态和任务执行
 * 2. 使用虚拟摇杆（Virtual Stick）控制飞行
 * 3. 通过传感器闭环反馈实现精确控制
 *
 * 三种操作模式：
 *   STANDBY  — 默认待命，可执行简单参数任务（爬升/旋转/平移→悬停）
 *   AUTO_CRUISE — GPS航点巡航，依次飞行到达后自动返航
 *   MANUAL  — 悬停后手动单步操控
 *
 * 技术特点：
 * - 虚拟摇杆控制：通过DJI MSDK的VirtualStick API发送飞行指令
 * - 闭环反馈：实时读取高度、位置、航向等传感器数据，动态调整飞行参数
 * - 状态机管理：通过MissionState跟踪当前飞行阶段
 * - 超时保护：每个飞行阶段都有超时机制，防止卡死
 */
class AutomatedFlightVM : DJIViewModel() {

    /**
     * 任务状态枚举
     * 定义飞行过程中的所有可能状态
     */
    enum class MissionState {
        IDLE,           // 空闲：就绪状态，可以开始新任务
        TAKEOFF,        // 起飞中：执行起飞指令
        CLIMBING,       // 爬升中：向目标高度爬升
        YAW_ROTATE,     // 旋转中：调整航向角度
        MOVE_LEFT,      // 左移中：向左平移
        MOVE_RIGHT,     // 右移中：向右平移
        HOVERING,       // 悬停中：稳定悬停，等待下一步指令
        LANDING,        // 降落中：执行降落或返航
        COMPLETED,      // 已完成：任务成功完成
        ERROR,          // 错误：任务执行失败
        // 巡航专用状态
        CRUISE_TAKEOFF, // 巡航起飞：巡航任务的起飞阶段
        WAYPOINT_YAW,   // 航点对准：旋转对准下一个航点方向
        WAYPOINT_FLY    // 航点飞行：飞向当前目标航点
    }

    // ==================== 公开状态变量（UI可观察）====================

    /** 当前任务状态 */
    val missionState = MutableLiveData(MissionState.IDLE)

    /** 状态消息（显示给用户） */
    val statusMessage = MutableLiveData("待命 - 选择一个模式开始")

    /** 当前高度（米） */
    val currentAltitude = MutableLiveData(0.0)

    /** 虚拟摇杆是否已启用 */
    val isVirtualStickEnabled = MutableLiveData(false)

    /** 当前任务参数 */
    val currentParams = MutableLiveData(MissionParams())

    /** 当前航向角（度，0-360，0=正北） */
    val currentHeading = MutableLiveData(0.0)

    /** 当前GPS位置 */
    val currentPosition = MutableLiveData<LocationCoordinate2D>()

    /** 是否有手动操作正在执行 */
    val isManualOpActive = MutableLiveData(false)

    /** 当前操作模式 */
    val operationMode = MutableLiveData(OperationMode.STANDBY)

    /** 航点列表 */
    val waypoints = MutableLiveData<List<Waypoint>>(emptyList())

    /** 巡航中当前航点索引（0-based，-1表示未在巡航） */
    val cruiseWaypointIndex = MutableLiveData(-1)

    // ==================== 内部私有变量 ====================

    /** 主线程Handler，用于定时任务和延迟操作 */
    private val handler = Handler(Looper.getMainLooper())

    /** 爬升速度（米/秒） */
    private val climbSpeed = 1.0

    /** 移动角度（度，用于roll/pitch控制，8度约等于中等速度） */
    private val moveAngle = 8.0

    /** 旋转角速度（度/秒） */
    private val yawAngularSpeed = 30.0

    /** 悬停稳定时间（毫秒，阶段切换时的缓冲时间） */
    private val hoverStabilizeMs = 300L

    /** 高度监听器是否已注册 */
    private var altitudeListenerRegistered = false

    /** 位置监听器是否已注册 */
    private var positionListenerRegistered = false

    /** 航向监听器是否已注册 */
    private var headingListenerRegistered = false

    /** 起飞是否已确认（飞机是否已离地） */
    private var takeoffConfirmed = false

    /** 目标高度（米，爬升阶段的目标值） */
    private var targetAltitude = 0.0

    /** 目标方位角（度，巡航时指向下一个航点的方向） */
    private var targetBearing = 0.0

    // ---- 闭环基准值（用于计算相对变化量）----

    /** 阶段开始时的航向角（度） */
    private var phaseStartHeading = 0.0

    /** 阶段开始时的纬度 */
    private var phaseStartLat = 0.0

    /** 阶段开始时的经度 */
    private var phaseStartLng = 0.0

    /** 当前任务参数 */
    private var params = MissionParams()

    // ---- 手动操作追踪变量 ----

    /** 手动操作是否正在进行 */
    private var manualOpActive = false

    /** 手动操作类型（"climb"=爬升, "move"=移动, "rotate"=旋转） */
    private var manualOpType = ""

    /** 手动操作目标值（高度/距离/角度） */
    private var manualTargetValue = 0.0

    /** 手动操作方向（1.0=正向, -1.0=负向） */
    private var manualOpDirection = 0.0

    /** 手动操作基准航向（旋转操作的起始航向） */
    private var manualOpBaseHeading = 0.0

    /** 手动操作基准纬度（移动操作的起始纬度） */
    private var manualOpBaseLat = 0.0

    /** 手动操作基准经度（移动操作的起始经度） */
    private var manualOpBaseLng = 0.0

    // ---- 巡航追踪变量 ----

    /** 巡航是否激活 */
    private var cruiseActive = false

    /** 当前巡航航点索引（-1表示未开始） */
    private var cruiseWpIdx = -1

    /** GPS丢失重试计数器（防止无限重试） */
    private var gpsLostRetryCount = 0

    // ==================== 超时常量（毫秒）====================

    /** GPS丢失最大重试次数（6次 × 5秒 = 30秒） */
    private val MAX_GPS_RETRY = 6

    /** 爬升阶段超时时间（15秒） */
    private val CLIMB_TIMEOUT_MS = 15_000L

    /** 旋转阶段超时时间（10秒） */
    private val YAW_TIMEOUT_MS = 10_000L

    /** 水平移动超时时间（10秒） */
    private val MOVE_TIMEOUT_MS = 10_000L

    /** 整个任务超时时间（90秒） */
    private val MISSION_TIMEOUT_MS = 90_000L

    /** 起飞确认超时时间（15秒，等待飞机离地） */
    private val TAKEOFF_CONFIRM_TIMEOUT_MS = 15_000L

    /** 手动操作超时时间（12秒） */
    private val MANUAL_OP_TIMEOUT_MS = 12_000L

    /** 航点飞行超时时间（30秒） */
    private val WAYPOINT_FLY_TIMEOUT_MS = 30_000L

    /** 虚拟摇杆刷新间隔（200毫秒 = 5Hz，符合DJI建议的5-20Hz） */
    private val VS_REFRESH_INTERVAL_MS = 200L

    // ==================== 虚拟摇杆控制 ====================

    /**
     * 构建虚拟摇杆参数
     *
     * 虚拟摇杆（Virtual Stick）是DJI SDK提供的底层飞行控制接口，
     * 可以像操作真实遥控器摇杆一样控制无人机。
     *
     * @param roll 横滚角度（度）：负值=左倾（左移），正值=右倾（右移）
     * @param pitch 俯仰角度（度）：负值=后倾（后退），正值=前倾（前进）
     * @param yaw 偏航角速度（度/秒）：负值=左转，正值=右转
     * @param verticalThrottle 垂直速度（米/秒）：负值=下降，正值=上升
     *
     * @return 虚拟摇杆参数对象
     */
    private fun buildParam(
        roll: Double = 0.0,
        pitch: Double = 0.0,
        yaw: Double = 0.0,
        verticalThrottle: Double = 0.0
    ): VirtualStickFlightControlParam {
        return VirtualStickFlightControlParam().apply {
            // 使用机体坐标系（前后左右相对于飞机本身，而非地面）
            rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
            // 垂直方向使用速度控制模式（单位：米/秒）
            verticalControlMode = VerticalControlMode.VELOCITY
            // 偏航使用角速度控制模式（单位：度/秒）
            yawControlMode = YawControlMode.ANGULAR_VELOCITY
            // 横滚/俯仰使用角度控制模式（单位：度）
            rollPitchControlMode = RollPitchControlMode.ANGLE
            // 设置各轴参数
            this.roll = roll
            this.pitch = pitch
            this.yaw = yaw
            this.verticalThrottle = verticalThrottle
        }
    }

    /** 当前激活的虚拟摇杆参数（使用@Volatile保证线程安全） */
    @Volatile private var activeParam: VirtualStickFlightControlParam = buildParam()

    /** 当前阶段的超时回调（用于取消超时任务） */
    private var activePhaseTimeout: Runnable? = null

    /**
     * 虚拟摇杆刷新任务
     *
     * 虚拟摇杆需要持续发送指令，否则飞机会停止响应。
     * 这个Runnable每200ms执行一次，重复发送当前的飞行指令。
     *
     * 工作原理：
     * 1. 检查当前任务状态，判断是否需要刷新
     * 2. 如果需要刷新，发送虚拟摇杆指令
     * 3. 调度下一次刷新（200ms后）
     * 4. 如果不需要刷新，停止调度（任务自然结束）
     */
    private val vsRefreshRunnable = object : Runnable {
        override fun run() {
            val state = missionState.value ?: MissionState.IDLE
            // 判断当前状态是否需要持续刷新虚拟摇杆
            val needRefresh = when (state) {
                // 这些状态需要持续控制，所以需要刷新
                MissionState.CLIMBING,       // 爬升中
                MissionState.YAW_ROTATE,     // 旋转中
                MissionState.MOVE_LEFT,      // 左移中
                MissionState.MOVE_RIGHT,     // 右移中
                MissionState.WAYPOINT_YAW,   // 航点对准中
                MissionState.WAYPOINT_FLY -> true  // 航点飞行中
                // 悬停状态：只有在执行手动操作时才需要刷新
                MissionState.HOVERING -> manualOpActive
                // 其他状态不需要刷新
                else -> false
            }
            if (needRefresh) {
                // 发送虚拟摇杆指令
                VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(activeParam)
                // 调度下一次刷新（200ms后）
                handler.postDelayed(this, VS_REFRESH_INTERVAL_MS)
            }
            // 如果needRefresh=false，不再调度，刷新任务自然结束
        }
    }

    /**
     * 任务超时保护任务
     *
     * 整个任务的总超时保护，防止任务执行时间过长。
     * 如果任务执行超过90秒还未完成，自动触发降落。
     *
     * 安全机制：
     * - 只对进行中的任务生效（排除IDLE、COMPLETED、ERROR、HOVERING）
     * - 超时后自动取消巡航、停止手动操作
     * - 执行自动降落，确保飞机安全
     */
    private val missionTimeoutRunnable = Runnable {
        val s = missionState.value
        // 检查是否是进行中的任务状态
        if (s != MissionState.IDLE && s != MissionState.COMPLETED &&
            s != MissionState.ERROR && s != MissionState.HOVERING) {
            LogUtils.w(logTag, "⚠ 任务总超时！自动降落")
            statusMessage.value = "安全保护：任务超时，启动降落..."
            cancelCruise()              // 取消巡航
            manualOpActive = false       // 停止手动操作
            stopVSRefresh()              // 停止虚拟摇杆刷新
            disableVS()                  // 禁用虚拟摇杆
            startAutoLanding()           // 启动自动降落
        }
    }

    /**
     * 起飞确认超时任务
     *
     * 起飞指令发送后，等待飞机实际离地（高度>0.5米）。
     * 如果15秒内飞机未离地，认为起飞失败，执行降落。
     *
     * 作用：
     * - 检测起飞是否成功（飞机可能因为低电量、螺旋桨卡住等原因无法起飞）
     * - 防止长时间等待，及时发现问题
     */
    private val takeoffConfirmTimeoutRunnable = Runnable {
        if (!takeoffConfirmed && (missionState.value == MissionState.TAKEOFF ||
                missionState.value == MissionState.CRUISE_TAKEOFF)) {
            LogUtils.e(logTag, "⚠ 起飞确认超时！")
            statusMessage.value = "起飞确认超时：飞行器未离地，自动降落"
            missionState.value = MissionState.LANDING
            startAutoLanding()
        }
    }

    /**
     * 启动安全定时器
     *
     * 在任务开始时调用，启动两个安全保护机制：
     * 1. 任务总超时保护（90秒）
     * 2. 虚拟摇杆持续刷新
     */
    private fun startSafetyTimers() {
        handler.postDelayed(missionTimeoutRunnable, MISSION_TIMEOUT_MS)
        handler.post(vsRefreshRunnable)
    }

    /**
     * 取消所有定时器
     *
     * 在任务结束、出错或手动停止时调用，清理所有定时任务。
     */
    private fun cancelAllTimers() {
        stopVSRefresh()
        handler.removeCallbacks(missionTimeoutRunnable)
        handler.removeCallbacks(takeoffConfirmTimeoutRunnable)
        activePhaseTimeout?.let { handler.removeCallbacks(it) }
        activePhaseTimeout = null
    }

    /**
     * 停止虚拟摇杆刷新
     */
    private fun stopVSRefresh() {
        handler.removeCallbacks(vsRefreshRunnable)
    }

    /**
     * 禁用虚拟摇杆
     *
     * 退出虚拟摇杆模式，飞机恢复到可以手动控制的状态。
     */
    private fun disableVS() {
        try {
            VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(false)
            VirtualStickManager.getInstance().disableVirtualStick(null)
        } catch (_: Exception) {}
        isVirtualStickEnabled.postValue(false)
    }

    // ==================== 模式切换 ====================

    /**
     * 切换操作模式
     *
     * 在三种模式间切换：STANDBY（待命）、AUTO_CRUISE（自动巡航）、MANUAL（手动操控）
     *
     * @param mode 目标模式
     *
     * 注意：只能在IDLE（空闲）状态下切换模式，飞行中无法切换
     */
    fun switchMode(mode: OperationMode) {
        if (missionState.value != MissionState.IDLE) {
            statusMessage.value = "只能在待命状态下切换模式"
            return
        }
        operationMode.value = mode
        // 根据模式设置相应的提示信息
        when (mode) {
            OperationMode.STANDBY -> statusMessage.value = "待命 - 设置参数后点击「开始任务」"
            OperationMode.AUTO_CRUISE -> statusMessage.value = "自动巡航 - 添加航点后点击「开始巡航」"
            OperationMode.MANUAL -> statusMessage.value = "手动操控 - 先起飞进入悬停后可操控"
        }
        LogUtils.i(logTag, "模式切换: $mode")
    }

    // ==================== 航点管理 ====================

    /**
     * 添加航点
     *
     * 添加一个GPS航点到航点列表，用于自动巡航。
     *
     * @param wp 航点对象（包含经纬度和高度）
     *
     * 验证机制：
     * 1. GPS坐标合法性检查（纬度-90~90，经度-180~180）
     * 2. 高度合理性检查（1-120米）
     * 3. 与前一航点距离检查（2-500米）
     *
     * 设计原因：
     * - 防止无效坐标导致飞行错误
     * - 防止高度过低（撞地）或过高（超出限制）
     * - 防止航点过近（GPS精度不足）或过远（超出视距）
     */
    fun addWaypoint(wp: Waypoint) {
        // 验证1: GPS坐标合法性
        if (abs(wp.latitude) > 90.0 || abs(wp.longitude) > 180.0) {
            statusMessage.value = "❌ 无效的GPS坐标"
            LogUtils.e(logTag, "无效航点: lat=${wp.latitude}, lng=${wp.longitude}")
            return
        }

        // 验证2: 高度合理性（1-120米，M3E理论最高500米，但实际限制120米保证安全）
        if (wp.altitude < 1.0 || wp.altitude > 120.0) {
            statusMessage.value = "⚠ 航点高度应在1-120米之间（当前${String.format("%.1f", wp.altitude)}m）"
            return
        }

        // 验证3: 与前一航点距离检查
        val lastWp = waypoints.value?.lastOrNull()
        if (lastWp != null) {
            val dist = haversineDistance(lastWp.latitude, lastWp.longitude, wp.latitude, wp.longitude)
            // 距离过远：超出视距范围，可能飞丢
            if (dist > 500.0) {
                statusMessage.value = "⚠ 航点距离过远（${String.format("%.0f", dist)}m），建议<500m"
                return
            }
            // 距离过近：GPS精度通常2-5米，航点太近可能无法区分
            if (dist < 2.0) {
                statusMessage.value = "⚠ 航点距离过近（${String.format("%.1f", dist)}m），建议>5m"
                return
            }
        }

        // 验证通过，添加到列表
        val list = waypoints.value?.toMutableList() ?: mutableListOf()
        list.add(wp)
        waypoints.value = list
        LogUtils.i(logTag, "添加航点 #${list.size}: (${wp.latitude}, ${wp.longitude}, ${wp.altitude}m)")
    }

    /**
     * 删除航点
     *
     * @param index 航点索引（0-based）
     */
    fun removeWaypoint(index: Int) {
        val list = waypoints.value?.toMutableList() ?: return
        if (index in list.indices) {
            list.removeAt(index)
            waypoints.value = list
        }
    }

    /**
     * 清空所有航点
     */
    fun clearWaypoints() {
        waypoints.value = emptyList()
    }

    /**
     * 批量设置航点
     *
     * @param list 航点列表
     */
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
        val altOk = abs(alt - wp.altitude) < 1.0

        if (dist < 5.0 && altOk) {
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

    // ==================== 航点巡航导航 ====================

    /**
     * 开始飞向指定航点
     *
     * 这是巡航系统的核心函数，负责导航到指定的航点。
     *
     * @param index 航点索引（0-based）
     *
     * 工作流程：
     * 1. 检查索引有效性，如果超出范围说明所有航点完成，执行返航
     * 2. 检查GPS信号，如果丢失则等待恢复（1秒后重试）
     * 3. 计算当前位置到目标航点的方位角（bearing）
     * 4. 旋转飞机对准目标方向（进入WAYPOINT_YAW状态）
     * 5. 对准完成后，开始飞向航点（由onCruiseYawComplete触发）
     *
     * 关键技术点：
     * - GPS丢失保护：不使用默认方位角0°，而是等待GPS恢复
     * - 超时保护：10秒内如果没对准方向，强制进入飞行阶段
     * - 方位角计算：使用Haversine公式计算两点间的初始方位角
     */
    private fun startCruiseWaypoint(index: Int) {
        val wps = waypoints.value ?: return
        if (index !in wps.indices) {
            // 所有航点已完成 → 执行返航降落
            LogUtils.i(logTag, "巡航完成：全部${wps.size}个航点已到达，返航")
            statusMessage.value = "全部航点已完成，开始返航降落..."
            cancelCruise()
            stopVSRefresh()
            sendCommand(buildParam())  // 停止所有指令
            disableVS()
            missionState.value = MissionState.LANDING
            startAutoLanding()
            return
        }
        cruiseWpIdx = index
        cruiseWaypointIndex.value = index
        val wp = wps[index]
        val pos = currentPosition.value

        // GPS丢失处理：等待GPS恢复而不是使用错误的默认值
        if (pos == null) {
            LogUtils.w(logTag, "GPS丢失，无法计算航点方位角，等待GPS恢复...")
            statusMessage.value = "GPS信号丢失，等待恢复..."
            handler.postDelayed({
                startCruiseWaypoint(index)  // 1秒后重试
            }, 1000L)
            return
        }

        LogUtils.i(logTag, "巡航 → 航点 #${index + 1}/${wps.size}: (${wp.latitude}, ${wp.longitude}, ${wp.altitude}m)")

        // 计算目标方位角（从当前位置到航点的方向，0-360度，0=正北）
        targetBearing = bearing(pos.latitude, pos.longitude, wp.latitude, wp.longitude)
        // 记录当前位置作为基准点（用于计算移动距离）
        phaseStartLat = pos.latitude
        phaseStartLng = pos.longitude

        // 第一步：旋转对准目标方向
        missionState.value = MissionState.WAYPOINT_YAW
        statusMessage.value = "航点 #${index + 1}/${wps.size}: 对准方向..."
        val hdg = currentHeading.value ?: 0.0
        // 计算需要旋转的角度（考虑360度循环）
        val yawDelta = headingDelta(hdg, targetBearing)
        // 确定旋转方向：正值=右转，负值=左转
        val yawSign = if (yawDelta > 0) 1.0 else -1.0
        sendCommand(buildParam(yaw = yawSign * yawAngularSpeed))

        // 添加超时保护：10秒后如果还没对准，强制进入飞行阶段
        // 这样可以避免磁干扰或指南针异常导致的无限旋转
        setPhaseTimeout(YAW_TIMEOUT_MS) {
            LogUtils.w(logTag, "航点对准超时，强制进入飞行阶段")
            onCruiseYawComplete()
        }
    }

    /**
     * 航点对准完成回调
     *
     * 当飞机成功对准航点方向后（或超时），调用此函数。
     * 停止旋转，稍作稳定（300ms），然后开始飞向航点。
     */
    private fun onCruiseYawComplete() {
        sendCommand(buildParam())  // 停止旋转
        clearPhaseTimeout()
        // 稍作稳定后进入飞行阶段
        scheduleNextPhase("巡航飞行", hoverStabilizeMs) { startCruiseFly() }
    }

    /**
     * 巡航飞行主控制函数
     *
     * 这是航点巡航的核心函数，负责控制飞机飞向当前目标航点。
     * 每200ms由vsRefreshRunnable调用一次，持续更新飞行指令。
     *
     * 核心功能：
     * 1. GPS丢失保护：限制重试次数，30秒后自动悬停
     * 2. 距离自适应减速：根据到航点的距离动态调整速度
     * 3. 高度自动调整：爬升或下降到目标高度
     * 4. 航向实时纠偏：持续修正航向偏差，保持直线飞行
     *
     * 技术细节：
     * - 速度控制：通过pitch角度（0-8度）控制前进速度
     *   - 远距离(>20m): 全速(8度)
     *   - 中距离(10-20m): 70%速度(5.6度)
     *   - 接近(5-10m): 40%速度(3.2度)
     *   - 很近(<5m): 20%速度(1.6度)，慢慢接近
     *
     * - 航向纠偏：通过yaw角速度修正偏航
     *   - 大偏差(>20度): 快速纠偏(±15度/秒)
     *   - 中偏差(10-20度): 中速纠偏(±10度/秒)
     *   - 小偏差(5-10度): 轻微纠偏(±5度/秒)
     *   - 偏差<5度: 不纠偏，保持当前航向
     *
     * GPS丢失处理：
     * - 第1-6次(30秒内): 保持30%速度前进，等待GPS恢复
     * - 超过6次: 紧急悬停，防止飞丢
     */
    private fun startCruiseFly() {
        val wps = waypoints.value ?: return
        if (cruiseWpIdx !in wps.indices) return
        val wp = wps[cruiseWpIdx]
        val pos = currentPosition.value

        // GPS信号丢失处理：限制重试次数防止无限等待
        if (pos == null) {
            gpsLostRetryCount++
            if (gpsLostRetryCount > MAX_GPS_RETRY) {
                // 超过6次重试（30秒），放弃等待，紧急悬停
                LogUtils.e(logTag, "GPS持续丢失超过30秒，紧急悬停")
                statusMessage.value = "GPS信号丢失，紧急悬停..."
                sendCommand(buildParam())  // 停止所有指令
                gpsLostRetryCount = 0      // 重置计数器
                emergencyStop()             // 紧急悬停
                return
            }
            // 还在重试范围内，显示进度，保持低速前进
            LogUtils.w(logTag, "GPS信号丢失(重试${gpsLostRetryCount}/${MAX_GPS_RETRY})，保持低速前进等待恢复")
            statusMessage.value = "GPS信号弱(${gpsLostRetryCount}/${MAX_GPS_RETRY})，保持低速前进..."
            sendCommand(buildParam(pitch = moveAngle * 0.3, verticalThrottle = 0.0))
            setPhaseTimeout(5000L) {
                LogUtils.i(logTag, "GPS丢失5秒后重试")
                startCruiseFly()
            }
            return
        }

        // GPS正常，重置重试计数器
        gpsLostRetryCount = 0
        val alt = currentAltitude.value ?: 0.0

        missionState.value = MissionState.WAYPOINT_FLY
        statusMessage.value = "航点 #${cruiseWpIdx + 1}/${wps.size}: 飞行中..."

        // 计算到目标点的水平距离（米）
        val distToTarget = haversineDistance(pos.latitude, pos.longitude, wp.latitude, wp.longitude)

        // 根据距离自适应调整pitch角度（速度）
        // 距离越近，速度越慢，避免飞过头
        val pitchAngle = when {
            distToTarget > 20.0 -> moveAngle           // 远距离：全速前进（8°）
            distToTarget > 10.0 -> moveAngle * 0.7     // 中距离：70%速度（5.6°）
            distToTarget > 5.0 -> moveAngle * 0.4      // 接近：40%速度（3.2°）
            else -> moveAngle * 0.2                     // 很近：20%速度（1.6°）缓慢接近
        }

        // 高度调整：根据当前高度和目标高度的差值决定上升或下降
        // 使用1.0米容差，避免频繁调整
        val vertThrottle = when {
            alt < wp.altitude - 1.0 -> climbSpeed      // 当前低于目标1米以上 → 上升
            alt > wp.altitude + 1.0 -> -climbSpeed     // 当前高于目标1米以上 → 下降
            else -> 0.0                                 // 高度差在±1米内 → 保持当前高度
        }

        // 航向保持：实时纠正偏航（这是防止偏离航线的关键）
        // 每次刷新都重新计算当前位置到目标的方位角，与飞机航向对比
        val currentBearing = bearing(pos.latitude, pos.longitude, wp.latitude, wp.longitude)
        val currentHdg = currentHeading.value ?: 0.0
        val headingError = headingDelta(currentHdg, currentBearing)

        // 根据航向偏差大小，动态调整纠偏力度
        val yawCorrection = when {
            abs(headingError) > 20.0 -> if (headingError > 0) -15.0 else 15.0  // 大偏差：快速纠偏
            abs(headingError) > 10.0 -> if (headingError > 0) -10.0 else 10.0  // 中偏差：中速纠偏
            abs(headingError) > 5.0 -> if (headingError > 0) -5.0 else 5.0     // 小偏差：轻微纠偏
            else -> 0.0                                                          // 航向正确：不纠偏
        }

        // 调试日志：接近航点时（<20米）打印详细飞行参数
        if (distToTarget < 20.0) {
            LogUtils.d(logTag, "巡航: dist=${String.format("%.1f", distToTarget)}m " +
                    "pitch=${String.format("%.1f", pitchAngle)}° " +
                    "hdgErr=${String.format("%.1f", headingError)}° " +
                    "yaw=${String.format("%.1f", yawCorrection)}°")
        }

        // 发送综合飞行指令：前进 + 高度调整 + 航向纠偏
        sendCommand(buildParam(pitch = pitchAngle, verticalThrottle = vertThrottle, yaw = yawCorrection))

        // 设置超时保护：30秒内必须到达航点，否则跳过
        setPhaseTimeout(WAYPOINT_FLY_TIMEOUT_MS) {
            LogUtils.w(logTag, "航点 #${cruiseWpIdx + 1} 超时，跳到下一航点")
            onWaypointArrived()
        }
    }

    /**
     * 航点到达回调
     *
     * 当checkWaypointArrival()检测到已到达航点时调用。
     * 停止飞行，稍作稳定，然后前往下一个航点。
     */
    private fun onWaypointArrived() {
        sendCommand(buildParam())  // 停止所有飞行指令
        clearPhaseTimeout()
        // 稍作稳定（300ms）后进入下一个航点
        scheduleNextPhase("下一航点", hoverStabilizeMs) {
            startCruiseWaypoint(cruiseWpIdx + 1)
        }
    }

    /**
     * 取消巡航
     *
     * 停止巡航任务，清除相关状态变量。
     */
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

    // ==================== 数学工具函数 ====================

    companion object {
        /**
         * 计算航向角差值（考虑360度循环）
         *
         * 航向角是0-360度的循环值，直接相减会出现问题。
         * 例如：从350度转到10度，直接相减是-340度，但实际只需右转20度。
         *
         * @param current 当前航向角（度，0-360）
         * @param start 起始航向角（度，0-360）
         * @return 航向差值（度，-180到180），正值=顺时针，负值=逆时针
         *
         * 示例：
         * - headingDelta(10, 350) = 20  （从350度转到10度，顺时针20度）
         * - headingDelta(350, 10) = -20 （从10度转到350度，逆时针20度）
         * - headingDelta(180, 0) = 180  （从0度转到180度，可顺可逆）
         */
        fun headingDelta(current: Double, start: Double): Double {
            var d = current - start
            // 处理360度循环：如果差值>180度，说明反方向更近
            if (d > 180) d -= 360   // 例如：350度到10度，差值370度->10度
            if (d < -180) d += 360  // 例如：10度到350度，差值-340度->20度
            return d
        }

        /**
         * 计算两个GPS坐标点之间的距离（Haversine公式）
         *
         * Haversine公式是计算球面上两点间最短距离的标准方法。
         * 考虑了地球曲率，适用于任意距离（短距离到长距离都准确）。
         *
         * @param lat1 起点纬度（度，-90到90）
         * @param lng1 起点经度（度，-180到180）
         * @param lat2 终点纬度（度，-90到90）
         * @param lng2 终点经度（度，-180到180）
         * @return 两点间的直线距离（米）
         *
         * 公式原理：
         * 1. 将经纬度转换为弧度
         * 2. 计算两点间的角距离（使用Haversine公式）
         * 3. 乘以地球半径得到实际距离
         *
         * 精度：
         * - 短距离（<1km）：误差<1米
         * - 中距离（1-100km）：误差<0.5%
         * - 长距离（>100km）：误差<1%
         *
         * 注意：
         * - 假设地球是完美球体（实际是椭球体），对无人机应用足够精确
         * - 计算的是直线距离，不是地面距离
         */
        fun haversineDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val R = 6371000.0  // 地球半径（米）
            // 计算纬度和经度差值（转换为弧度）
            val dLat = (lat2 - lat1) * PI / 180.0
            val dLng = (lng2 - lng1) * PI / 180.0
            // Haversine公式核心计算
            val a = sin(dLat / 2).pow(2) +
                    cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) * sin(dLng / 2).pow(2)
            // 计算角距离，然后转换为实际距离
            return R * 2 * atan2(sqrt(a), sqrt(1 - a))
        }

        /**
         * 计算从点1到点2的初始方位角（bearing）
         *
         * 方位角是从正北方向顺时针测量的角度，用于导航。
         * 告诉我们"要到达目标点，应该朝哪个方向飞"。
         *
         * @param lat1 起点纬度（度）
         * @param lng1 起点经度（度）
         * @param lat2 终点纬度（度）
         * @param lng2 终点经度（度）
         * @return 初始方位角（度，0-360），0=正北，90=正东，180=正南，270=正西
         *
         * 公式原理：
         * 1. 使用球面三角学计算两点间的方位角
         * 2. 考虑地球曲率（不是简单的平面几何）
         * 3. 结果范围调整到0-360度
         *
         * 示例：
         * - bearing(0, 0, 1, 0) ≈ 0    （向北飞）
         * - bearing(0, 0, 0, 1) ≈ 90   （向东飞）
         * - bearing(0, 0, -1, 0) ≈ 180 （向南飞）
         * - bearing(0, 0, 0, -1) ≈ 270 （向西飞）
         *
         * 注意：
         * - 这是"初始方位角"：沿大圆路径飞行时，方位角会逐渐变化
         * - 对于短距离（<100km），方位角变化很小，可以忽略
         * - 对于无人机应用（通常<1km），可以视为恒定方位角
         */
        fun bearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            // 经度差（弧度）
            val dLng = (lng2 - lng1) * PI / 180.0
            // 计算方位角的y和x分量
            val y = sin(dLng) * cos(lat2 * PI / 180.0)
            val x = cos(lat1 * PI / 180.0) * sin(lat2 * PI / 180.0) -
                    sin(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) * cos(dLng)
            // 使用atan2计算角度，并转换到0-360度范围
            return (atan2(y, x) * 180.0 / PI + 360) % 360
        }
    }
}
