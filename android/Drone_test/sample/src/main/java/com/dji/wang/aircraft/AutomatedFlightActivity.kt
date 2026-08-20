package com.dji.wang.aircraft

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.dji.wang.aircraft.databinding.ActivityAutomatedFlightBinding
import com.dji.wang.aircraft.models.*
import dji.v5.manager.SDKManager
import dji.v5.ux.core.util.ToastUtils
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.KeyTools
import dji.v5.manager.KeyManager

class AutomatedFlightActivity : AppCompatActivity() {

    private val automatedFlightVM: AutomatedFlightVM by viewModels()
    private val msdkManagerVM: MSDKManagerVM by globalViewModels()
    private lateinit var binding: ActivityAutomatedFlightBinding
    private val handler = Handler(Looper.getMainLooper())
    private var sdkRegistered = false
    private var productConnected = false
    private var webServer: WebServer? = null
    @Volatile private var batteryPercent: Int = 0
    @Volatile private var batteryVoltage: Int = 0

    private val permissionArray = arrayListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ).apply {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAutomatedFlightBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initViews()
        observeViewModel()
        observeSDKState()
        checkPermissionAndRequest()
        startWebServer()
    }

    // ---- 参数读取 ----

    private fun readParams(): Triple<Double, Double, Double> {
        val climbH = binding.editClimbHeight.text.toString().toDoubleOrNull() ?: 1.0
        val moveD = binding.editMoveDistance.text.toString().toDoubleOrNull() ?: 0.5
        val yawA = binding.editYawAngle.text.toString().toDoubleOrNull() ?: 0.0
        return Triple(climbH.coerceIn(0.1, 50.0), moveD.coerceIn(-10.0, 10.0), yawA.coerceIn(-360.0, 360.0))
    }

    private fun readManualParams(): Triple<Double, Double, Double> {
        val climb = binding.editManualClimb.text.toString().toDoubleOrNull() ?: 1.0
        val move = binding.editManualMove.text.toString().toDoubleOrNull() ?: 1.0
        val rotate = binding.editManualRotate.text.toString().toDoubleOrNull() ?: 90.0
        return Triple(climb.coerceIn(-10.0, 10.0), move.coerceIn(0.1, 10.0), rotate.coerceIn(-360.0, 360.0))
    }

    private fun readWaypointInput(): Waypoint? {
        val lat = binding.editWpLat.text.toString().toDoubleOrNull() ?: return null
        val lng = binding.editWpLng.text.toString().toDoubleOrNull() ?: return null
        val alt = binding.editWpAlt.text.toString().toDoubleOrNull() ?: 5.0
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) return null
        return Waypoint(lat, lng, alt.coerceIn(1.0, 100.0))
    }

    // ---- 初始化 ----

    private fun initViews() {
        // 模式切换
        binding.btnModeStandby.setOnClickListener {
            automatedFlightVM.switchMode(OperationMode.STANDBY)
            updateModeButtons(OperationMode.STANDBY)
        }
        binding.btnModeCruise.setOnClickListener {
            automatedFlightVM.switchMode(OperationMode.AUTO_CRUISE)
            updateModeButtons(OperationMode.AUTO_CRUISE)
        }
        binding.btnModeManual.setOnClickListener {
            automatedFlightVM.switchMode(OperationMode.MANUAL)
            updateModeButtons(OperationMode.MANUAL)
        }

        // 待命模式：开始任务
        binding.btnStartMission.setOnClickListener {
            val (climb, move, yaw) = readParams()
            automatedFlightVM.startMission(climb, move, yaw)
            ToastUtils.showToast("任务已启动: 爬${climb}m 移${move}m 转${yaw}°")
        }

        // 巡航：添加航点
        binding.btnAddWaypoint.setOnClickListener {
            val wp = readWaypointInput()
            if (wp != null) {
                automatedFlightVM.addWaypoint(wp)
                binding.editWpLat.text.clear()
                binding.editWpLng.text.clear()
                updateWaypointListDisplay()
                val ns = if (wp.latitude >= 0) "N" else "S"
                val ew = if (wp.longitude >= 0) "E" else "W"
                ToastUtils.showToast("航点已添加 ${Math.abs(wp.latitude)}°$ns ${Math.abs(wp.longitude)}°$ew")
            } else {
                ToastUtils.showToast("请输入有效的经纬度")
            }
        }

        // 巡航：清空航点
        binding.btnClearWaypoints.setOnClickListener {
            automatedFlightVM.clearWaypoints()
            updateWaypointListDisplay()
            ToastUtils.showToast("航点已清空")
        }

        // 巡航：保存路线
        binding.btnSaveRoute.setOnClickListener {
            val wps = automatedFlightVM.waypoints.value ?: emptyList()
            if (wps.isEmpty()) { ToastUtils.showToast("请先添加航点"); return@setOnClickListener }
            showSaveRouteDialog(wps)
        }

        // 巡航：加载路线
        binding.btnLoadRoute.setOnClickListener {
            showLoadRouteDialog()
        }

        // 巡航：开始巡航
        binding.btnStartCruise.setOnClickListener {
            val wps = automatedFlightVM.waypoints.value ?: emptyList()
            if (wps.isEmpty()) { ToastUtils.showToast("请至少添加一个航点"); return@setOnClickListener }
            automatedFlightVM.startCruiseMission()
            ToastUtils.showToast("巡航任务已启动: ${wps.size}个航点")
        }

        // 手动模式：起飞悬停
        binding.btnTakeoffHover.setOnClickListener {
            automatedFlightVM.takeoffAndHover()
            ToastUtils.showToast("起飞悬停中...")
        }

        // 手动操控
        binding.btnManualClimb.setOnClickListener {
            val (climb, _, _) = readManualParams()
            automatedFlightVM.manualClimb(climb)
            val label = if (climb > 0) "上升" else "下降"
            ToastUtils.showToast("手动${label}: ${Math.abs(climb)}m")
        }
        binding.btnManualMoveLeft.setOnClickListener {
            val (_, move, _) = readManualParams()
            automatedFlightVM.manualMoveLeft(move)
            ToastUtils.showToast("手动左移: ${move}m")
        }
        binding.btnManualMoveRight.setOnClickListener {
            val (_, move, _) = readManualParams()
            automatedFlightVM.manualMoveRight(move)
            ToastUtils.showToast("手动右移: ${move}m")
        }
        binding.btnManualRotate.setOnClickListener {
            val (_, _, rotate) = readManualParams()
            automatedFlightVM.manualRotate(rotate)
            val dir = if (rotate > 0) "右转" else "左转"
            ToastUtils.showToast("手动${dir}: ${Math.abs(rotate)}°")
        }

        // 返航
        binding.btnGoHome.setOnClickListener {
            automatedFlightVM.goHome()
            ToastUtils.showToast("正在归航降落...")
        }

        // 重置
        binding.btnResetUi.setOnClickListener {
            automatedFlightVM.resetUI()
            ToastUtils.showToast("UI已重置")
        }
    }

    // ---- 模式按钮高亮 ----

    private fun updateModeButtons(mode: OperationMode) {
        val activeBg = 0xFF00e5ff.toInt()
        val activeText = 0xFF1a1a2e.toInt()
        val inactiveBg = 0xFF333344.toInt()
        val inactiveText = 0xFF888888.toInt()

        binding.btnModeStandby.setBackgroundColor(if (mode == OperationMode.STANDBY) activeBg else inactiveBg)
        binding.btnModeStandby.setTextColor(if (mode == OperationMode.STANDBY) activeText else inactiveText)

        binding.btnModeCruise.setBackgroundColor(if (mode == OperationMode.AUTO_CRUISE) activeBg else inactiveBg)
        binding.btnModeCruise.setTextColor(if (mode == OperationMode.AUTO_CRUISE) activeText else inactiveText)

        binding.btnModeManual.setBackgroundColor(if (mode == OperationMode.MANUAL) activeBg else inactiveBg)
        binding.btnModeManual.setTextColor(if (mode == OperationMode.MANUAL) activeText else inactiveText)
    }

    private fun updateWaypointListDisplay() {
        val wps = automatedFlightVM.waypoints.value ?: emptyList()
        if (wps.isEmpty()) {
            binding.textWaypointList.text = "尚未添加航点"
        } else {
            binding.textWaypointList.text = wps.mapIndexed { i, wp ->
                val ns = if (wp.latitude >= 0) "N" else "S"
                val ew = if (wp.longitude >= 0) "E" else "W"
                "#${i + 1}: ${String.format("%.6f", Math.abs(wp.latitude))}°$ns  ${String.format("%.6f", Math.abs(wp.longitude))}°$ew  @ ${String.format("%.1f", wp.altitude)}m"
            }.joinToString("\n")
        }
    }

    // ---- 路线保存/加载对话框 ----

    private fun showSaveRouteDialog(wps: List<Waypoint>) {
        val input = EditText(this).apply {
            hint = "路线名称"
            setText("路线_${System.currentTimeMillis() % 100000}")
        }
        AlertDialog.Builder(this)
            .setTitle("保存路线")
            .setMessage("将保存 ${wps.size} 个航点")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) { ToastUtils.showToast("名称不能为空"); return@setPositiveButton }
                val route = Route(name = name, waypoints = wps)
                if (RouteStorage.save(this, route)) {
                    ToastUtils.showToast("路线「$name」已保存")
                } else {
                    ToastUtils.showToast("保存失败")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showLoadRouteDialog() {
        val routes = RouteStorage.listRoutes(this)
        if (routes.isEmpty()) {
            ToastUtils.showToast("没有已保存的路线")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("加载路线")
            .setItems(routes.toTypedArray()) { _, which ->
                val name = routes[which]
                val route = RouteStorage.load(this, name)
                if (route != null) {
                    automatedFlightVM.setWaypoints(route.waypoints)
                    updateWaypointListDisplay()
                    ToastUtils.showToast("已加载「$name」(${route.waypoints.size}个航点)")
                } else {
                    ToastUtils.showToast("加载失败")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---- SDK观测 ----

    private fun observeSDKState() {
        msdkManagerVM.lvSdkStatus.observe(this) { status -> binding.textSdkStatus.text = status }
        msdkManagerVM.lvRegisterState.observe(this) { resultPair ->
            sdkRegistered = resultPair.first
            binding.textSdkStatus.setTextColor(if (resultPair.first) 0xFF4CAF50.toInt() else 0xFFF44336.toInt())
            if (resultPair.first) {
                ToastUtils.showToast("SDK注册成功")
                startBatteryListener()
            }
            updateStartButtonState()
        }
        msdkManagerVM.lvProductConnectionState.observe(this) { resultPair ->
            productConnected = resultPair.first
            if (resultPair.first) {
                binding.textProductStatus.text = "无人机: 已连接"
                binding.textProductStatus.setTextColor(0xFF4CAF50.toInt())
                startBatteryListener()
            } else {
                binding.textProductStatus.text = "无人机: 等待连接..."
                binding.textProductStatus.setTextColor(0xFFFFCC00.toInt())
            }
            updateStartButtonState()
        }
        msdkManagerVM.lvInitProcess.observe(this) { processPair ->
            binding.textSdkStatus.text = "SDK: ${processPair.first.name}"
        }
        handler.postDelayed({ checkCurrentSDKState() }, 2000)
    }

    private fun checkCurrentSDKState() {
        try {
            if (SDKManager.getInstance().isRegistered) {
                sdkRegistered = true
                binding.textSdkStatus.text = "✓ SDK已激活"
                binding.textSdkStatus.setTextColor(0xFF4CAF50.toInt())
                updateStartButtonState()
                startBatteryListener()
            }
            if (!productConnected) {
                binding.textProductStatus.text = "无人机: 等待连接..."
                binding.textProductStatus.setTextColor(0xFFFFCC00.toInt())
            }
        } catch (_: Exception) {}
    }

    /** 周期性读取电池电量/电压（DJI MSDK KeyManager 同步 getValue）。
     *  注意：仅在飞机开机并与 RC Pro 对频后，BatteryKey 才会返回有效值；
     *  未连接时 getValue 返回 null，电量保持 0，属正常现象。 */
    private fun startBatteryListener() {
        try {
            val percentKey = KeyTools.createKey(BatteryKey.KeyChargeRemainingInPercent)
            val voltageKey = KeyTools.createKey(BatteryKey.KeyVoltage)
            // 立即读一次
            val p = KeyManager.getInstance().getValue(percentKey)
            if (p is Int) batteryPercent = p
            val v = KeyManager.getInstance().getValue(voltageKey)
            if (v is Int) batteryVoltage = v
            Log.i("DroneBattery", "电池: $batteryPercent% / ${batteryVoltage}mV")
            // 每 5 秒更新一次
            handler.postDelayed(object : Runnable {
                override fun run() {
                    try {
                        val pv = KeyManager.getInstance().getValue(percentKey)
                        if (pv is Int) batteryPercent = pv
                        val vv = KeyManager.getInstance().getValue(voltageKey)
                        if (vv is Int) batteryVoltage = vv
                    } catch (_: Exception) {}
                    handler.postDelayed(this, 5000)
                }
            }, 5000)
        } catch (_: Exception) {
            // 电池读取失败不影响主功能
        }
    }

    private fun updateStartButtonState() {
        val ready = sdkRegistered && productConnected
        val isIdle = automatedFlightVM.missionState.value == AutomatedFlightVM.MissionState.IDLE
        if (ready && isIdle) {
            binding.btnStartMission.isEnabled = true
            binding.btnStartMission.alpha = 1.0f
        } else if (!ready && isIdle) {
            binding.btnStartMission.isEnabled = false
            binding.btnStartMission.alpha = 0.5f
        }
    }

    // ---- ViewModel观测 ----

    private fun observeViewModel() {
        // 模式切换 → 更新UI可见性
        automatedFlightVM.operationMode.observe(this) { mode ->
            updateModeButtons(mode)
            val isIdle = automatedFlightVM.missionState.value == AutomatedFlightVM.MissionState.IDLE
            binding.sectionStandby.visibility =
                if (mode == OperationMode.STANDBY && isIdle) android.view.View.VISIBLE else android.view.View.GONE
            binding.sectionCruise.visibility =
                if (mode == OperationMode.AUTO_CRUISE && isIdle) android.view.View.VISIBLE else android.view.View.GONE
            binding.sectionManual.visibility =
                if (mode == OperationMode.MANUAL) android.view.View.VISIBLE else android.view.View.GONE
        }

        // 航点列表变化
        automatedFlightVM.waypoints.observe(this) {
            updateWaypointListDisplay()
            val hasWps = it.isNotEmpty()
            val isIdle = automatedFlightVM.missionState.value == AutomatedFlightVM.MissionState.IDLE
            binding.btnStartCruise.isEnabled = hasWps && sdkRegistered && productConnected && isIdle
            binding.btnStartCruise.alpha = if (binding.btnStartCruise.isEnabled) 1.0f else 0.5f
        }

        // 巡航航点索引
        automatedFlightVM.cruiseWaypointIndex.observe(this) { idx ->
            if (idx >= 0) {
                val wps = automatedFlightVM.waypoints.value ?: emptyList()
                if (idx < wps.size) {
                    binding.textStatusMessage.text = "巡航中: 航点 #${idx + 1}/${wps.size}"
                }
            }
        }

        // 任务状态
        automatedFlightVM.missionState.observe(this) { state ->
            binding.textMissionState.text = when (state) {
                AutomatedFlightVM.MissionState.IDLE -> "就绪"
                AutomatedFlightVM.MissionState.TAKEOFF -> "正在起飞"
                AutomatedFlightVM.MissionState.CLIMBING -> "正在上升"
                AutomatedFlightVM.MissionState.YAW_ROTATE -> "正在旋转"
                AutomatedFlightVM.MissionState.MOVE_LEFT -> "向左平移"
                AutomatedFlightVM.MissionState.MOVE_RIGHT -> "向右平移"
                AutomatedFlightVM.MissionState.HOVERING -> "悬停中"
                AutomatedFlightVM.MissionState.LANDING -> "正在降落"
                AutomatedFlightVM.MissionState.COMPLETED -> "任务完成"
                AutomatedFlightVM.MissionState.ERROR -> "发生错误"
                AutomatedFlightVM.MissionState.CRUISE_TAKEOFF -> "巡航起飞"
                AutomatedFlightVM.MissionState.WAYPOINT_YAW -> "对准航点"
                AutomatedFlightVM.MissionState.WAYPOINT_FLY -> "飞向航点"
            }

            val isIdle = state == AutomatedFlightVM.MissionState.IDLE
            val isRunning = state != AutomatedFlightVM.MissionState.IDLE &&
                    state != AutomatedFlightVM.MissionState.COMPLETED &&
                    state != AutomatedFlightVM.MissionState.ERROR &&
                    state != AutomatedFlightVM.MissionState.HOVERING
            val isHovering = state == AutomatedFlightVM.MissionState.HOVERING
            val isFinished = state == AutomatedFlightVM.MissionState.COMPLETED ||
                    state == AutomatedFlightVM.MissionState.ERROR
            val alt = automatedFlightVM.currentAltitude.value ?: 0.0
            val mode = automatedFlightVM.operationMode.value ?: OperationMode.STANDBY

            // 模式区域可见性
            binding.sectionStandby.visibility =
                if (mode == OperationMode.STANDBY && isIdle) android.view.View.VISIBLE else android.view.View.GONE
            binding.sectionCruise.visibility =
                if (mode == OperationMode.AUTO_CRUISE && isIdle) android.view.View.VISIBLE else android.view.View.GONE
            binding.sectionManual.visibility =
                if (mode == OperationMode.MANUAL) android.view.View.VISIBLE else android.view.View.GONE

            // 开始任务按钮
            binding.btnStartMission.isEnabled = sdkRegistered && productConnected && isIdle
            binding.btnStartMission.alpha = if (binding.btnStartMission.isEnabled) 1.0f else 0.5f

            // 开始巡航按钮
            val hasWps = (automatedFlightVM.waypoints.value?.size ?: 0) > 0
            binding.btnStartCruise.isEnabled = hasWps && sdkRegistered && productConnected && isIdle
            binding.btnStartCruise.alpha = if (binding.btnStartCruise.isEnabled) 1.0f else 0.5f

            // 返航按钮
            val canGoHome = isRunning || isHovering || alt > 0.5
            binding.btnGoHome.visibility = if (canGoHome) android.view.View.VISIBLE else android.view.View.GONE
            binding.btnGoHome.isEnabled = canGoHome
            binding.btnGoHome.alpha = if (canGoHome) 1.0f else 0.5f

            // 重置按钮
            val canReset = isIdle || isFinished || alt <= 0.1
            binding.btnResetUi.isEnabled = canReset
            binding.btnResetUi.alpha = if (canReset) 1.0f else 0.5f
            binding.btnResetUi.visibility = if (canReset) android.view.View.VISIBLE else android.view.View.GONE

            // 模式切换仅在IDLE时可用
            binding.btnModeStandby.isEnabled = isIdle
            binding.btnModeCruise.isEnabled = isIdle
            binding.btnModeManual.isEnabled = isIdle

            // 航点编辑仅IDLE时可用
            binding.btnAddWaypoint.isEnabled = isIdle
            binding.btnClearWaypoints.isEnabled = isIdle
            binding.btnSaveRoute.isEnabled = isIdle
            binding.btnLoadRoute.isEnabled = isIdle
        }

        // 手动操作状态
        automatedFlightVM.isManualOpActive.observe(this) { opActive ->
            val isHovering = automatedFlightVM.missionState.value == AutomatedFlightVM.MissionState.HOVERING
            val manualEnabled = isHovering && !opActive
            binding.btnManualClimb.isEnabled = manualEnabled
            binding.btnManualMoveLeft.isEnabled = manualEnabled
            binding.btnManualMoveRight.isEnabled = manualEnabled
            binding.btnManualRotate.isEnabled = manualEnabled
            // 起飞悬停：仅IDLE可用，悬停后隐藏
            val isIdle = automatedFlightVM.missionState.value == AutomatedFlightVM.MissionState.IDLE
            binding.btnTakeoffHover.isEnabled = sdkRegistered && productConnected && isIdle
            binding.btnTakeoffHover.alpha = if (binding.btnTakeoffHover.isEnabled) 1.0f else 0.5f
            binding.btnTakeoffHover.visibility = if (isIdle) android.view.View.VISIBLE else android.view.View.GONE
        }

        automatedFlightVM.statusMessage.observe(this) { binding.textStatusMessage.text = it }
        automatedFlightVM.currentAltitude.observe(this) { binding.textAltitude.text = String.format("%.1f 米", it) }
        automatedFlightVM.isVirtualStickEnabled.observe(this) { enabled ->
            binding.textVsStatus.text = if (enabled) "虚拟摇杆: 已启用" else ""
        }
        automatedFlightVM.currentParams.observe(this) { params ->
            binding.editClimbHeight.setText(String.format("%.1f", params.climbHeight))
            binding.editMoveDistance.setText(String.format("%.1f", params.moveDistance))
            binding.editYawAngle.setText(String.format("%.0f", params.yawAngle))
        }

        // GPS定位自动填充航点输入框（仅当字段为空时，避免覆盖用户输入）
        automatedFlightVM.currentPosition.observe(this) { pos ->
            pos?.let {
                if (binding.editWpLat.text.isNullOrBlank()) {
                    binding.editWpLat.setText(String.format("%.6f", it.latitude))
                }
                if (binding.editWpLng.text.isNullOrBlank()) {
                    binding.editWpLng.setText(String.format("%.6f", it.longitude))
                }
            }
        }
    }

    // ---- 权限 ----

    private fun checkPermissionAndRequest() {
        val missing = permissionArray.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissionLauncher.launch(missing.toTypedArray())
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (!result.values.all { it }) ToastUtils.showToast("需要授予权限才能使用")
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        stopWebServer()
        if (automatedFlightVM.missionState.value != AutomatedFlightVM.MissionState.IDLE &&
            automatedFlightVM.missionState.value != AutomatedFlightVM.MissionState.COMPLETED) {
            automatedFlightVM.emergencyStop()
        }
    }

    // ---- Web服务器 ----

    private fun startWebServer() {
        webServer = WebServer(8080).apply {
            actionHandler = object : WebServer.ActionHandler {
                override fun onStart(params: Map<String, Double>): Map<String, Any> {
                    val state = automatedFlightVM.missionState.value
                    if (state != AutomatedFlightVM.MissionState.IDLE) return mapOf("success" to false, "message" to "状态不允许: $state")
                    if (!SDKManager.getInstance().isRegistered) return mapOf("success" to false, "message" to "SDK未激活")
                    val climb = params["climbHeight"] ?: 1.0
                    val move = params["moveDistance"] ?: 0.5
                    val yaw = params["yawAngle"] ?: 0.0
                    handler.post { automatedFlightVM.startMission(climb, move, yaw) }
                    return mapOf("success" to true, "message" to "任务已启动")
                }

                override fun onStop(): Map<String, Any> {
                    handler.post { automatedFlightVM.emergencyStop() }
                    return mapOf("success" to true, "message" to "紧急停止已执行")
                }

                override fun onReset(): Map<String, Any> {
                    handler.post { automatedFlightVM.resetUI() }
                    return mapOf("success" to true, "message" to "UI已重置")
                }

                override fun onGoHome(): Map<String, Any> {
                    handler.post { automatedFlightVM.goHome() }
                    return mapOf("success" to true, "message" to "正在归航降落")
                }

                override fun onSwitchMode(mode: String): Map<String, Any> {
                    val opMode = when (mode.lowercase()) {
                        "standby" -> OperationMode.STANDBY
                        "cruise" -> OperationMode.AUTO_CRUISE
                        "manual" -> OperationMode.MANUAL
                        else -> null
                    }
                    if (opMode == null) return mapOf("success" to false, "message" to "未知模式: $mode")
                    handler.post { automatedFlightVM.switchMode(opMode) }
                    return mapOf("success" to true, "message" to "已切换到: $mode")
                }

                override fun onAddWaypoint(lat: Double, lng: Double, alt: Double): Map<String, Any> {
                    handler.post { automatedFlightVM.addWaypoint(Waypoint(lat, lng, alt)) }
                    val size = (automatedFlightVM.waypoints.value?.size ?: 0) + 1
                    return mapOf("success" to true, "message" to "航点已添加", "total" to size)
                }

                override fun onClearWaypoints(): Map<String, Any> {
                    handler.post { automatedFlightVM.clearWaypoints() }
                    return mapOf("success" to true, "message" to "航点已清空")
                }

                override fun onStartCruise(): Map<String, Any> {
                    val state = automatedFlightVM.missionState.value
                    if (state != AutomatedFlightVM.MissionState.IDLE) return mapOf("success" to false, "message" to "状态不允许: $state")
                    val wps = automatedFlightVM.waypoints.value ?: emptyList()
                    if (wps.isEmpty()) return mapOf("success" to false, "message" to "请先添加航点")
                    handler.post { automatedFlightVM.startCruiseMission() }
                    return mapOf("success" to true, "message" to "巡航已启动 (${wps.size}个航点)")
                }

                override fun onManualClimb(delta: Double): Map<String, Any> {
                    if (automatedFlightVM.missionState.value != AutomatedFlightVM.MissionState.HOVERING)
                        return mapOf("success" to false, "message" to "只能在悬停状态下操控")
                    handler.post { automatedFlightVM.manualClimb(delta) }
                    return mapOf("success" to true, "message" to "手动爬升: ${delta}m")
                }

                override fun onManualMoveLeft(distance: Double): Map<String, Any> {
                    if (automatedFlightVM.missionState.value != AutomatedFlightVM.MissionState.HOVERING)
                        return mapOf("success" to false, "message" to "只能在悬停状态下操控")
                    handler.post { automatedFlightVM.manualMoveLeft(distance) }
                    return mapOf("success" to true, "message" to "手动左移: ${distance}m")
                }

                override fun onManualMoveRight(distance: Double): Map<String, Any> {
                    if (automatedFlightVM.missionState.value != AutomatedFlightVM.MissionState.HOVERING)
                        return mapOf("success" to false, "message" to "只能在悬停状态下操控")
                    handler.post { automatedFlightVM.manualMoveRight(distance) }
                    return mapOf("success" to true, "message" to "手动右移: ${distance}m")
                }

                override fun onManualRotate(degrees: Double): Map<String, Any> {
                    if (automatedFlightVM.missionState.value != AutomatedFlightVM.MissionState.HOVERING)
                        return mapOf("success" to false, "message" to "只能在悬停状态下操控")
                    handler.post { automatedFlightVM.manualRotate(degrees) }
                    return mapOf("success" to true, "message" to "手动旋转: ${degrees}°")
                }

                override fun onManualMoveForward(distance: Double): Map<String, Any> {
                    if (automatedFlightVM.missionState.value != AutomatedFlightVM.MissionState.HOVERING)
                        return mapOf("success" to false, "message" to "只能在悬停状态下操控")
                    handler.post { automatedFlightVM.manualMoveForward(distance) }
                    return mapOf("success" to true, "message" to "手动前进: ${distance}m")
                }

                override fun onManualMoveBackward(distance: Double): Map<String, Any> {
                    if (automatedFlightVM.missionState.value != AutomatedFlightVM.MissionState.HOVERING)
                        return mapOf("success" to false, "message" to "只能在悬停状态下操控")
                    handler.post { automatedFlightVM.manualMoveBackward(distance) }
                    return mapOf("success" to true, "message" to "手动后退: ${distance}m")
                }

                override fun onGimbalRotate(pitch: Double, roll: Double, yaw: Double): Map<String, Any> {
                    handler.post { automatedFlightVM.setGimbalAttitude(pitch, roll, yaw) }
                    return mapOf("success" to true, "message" to "云台: pitch=$pitch roll=$roll yaw=$yaw")
                }

                override fun onCameraCapture(): Map<String, Any> {
                    handler.post { automatedFlightVM.takePhoto() }
                    return mapOf("success" to true, "message" to "已触发拍照")
                }

                override fun onCameraZoom(factor: Double): Map<String, Any> {
                    handler.post { automatedFlightVM.setCameraZoom(factor) }
                    return mapOf("success" to true, "message" to "变焦: ${factor}x")
                }

                override fun onTakeoffHover(): Map<String, Any> {
                    if (automatedFlightVM.missionState.value != AutomatedFlightVM.MissionState.IDLE)
                        return mapOf("success" to false, "message" to "只能在就绪状态下起飞")
                    if (!SDKManager.getInstance().isRegistered)
                        return mapOf("success" to false, "message" to "SDK未激活")
                    handler.post { automatedFlightVM.takeoffAndHover() }
                    return mapOf("success" to true, "message" to "起飞悬停中...")
                }
            }

            statusProvider = WebServer.StatusProvider { buildStatusMap() }
        }

        if (webServer?.start() == true) {
            VideoFrameProvider.start()
            val (wifiIps, otherIps) = webServer?.getAllLocalIps() ?: (emptyList<Pair<String, String>>() to emptyList())
            val mainIp = (wifiIps.firstOrNull() ?: otherIps.firstOrNull())?.second
            if (mainIp != null) {
                binding.textWebUrl.text = "Web控制: http://$mainIp:8080"
                ToastUtils.showToast("Web服务器已启动: http://$mainIp:8080")
            } else {
                binding.textWebUrl.text = "⚠ 未检测到网络"
            }
            binding.textWebUrl.visibility = android.view.View.VISIBLE
        } else {
            binding.textWebUrl.text = "Web控制: 启动失败"
            binding.textWebUrl.visibility = android.view.View.VISIBLE
        }
    }

    private fun stopWebServer() {
        webServer?.stop()
        webServer = null
        VideoFrameProvider.stop()
    }

    private fun buildStatusMap(): Map<String, Any> {
        val state = automatedFlightVM.missionState.value?.name ?: "IDLE"
        val altitude = automatedFlightVM.currentAltitude.value ?: 0.0
        val msg = automatedFlightVM.statusMessage.value ?: ""
        val vs = automatedFlightVM.isVirtualStickEnabled.value ?: false
        val mode = automatedFlightVM.operationMode.value?.name ?: "STANDBY"
        val wps = automatedFlightVM.waypoints.value ?: emptyList()
        val p = automatedFlightVM.currentParams.value ?: MissionParams()
        val regState = msdkManagerVM.lvRegisterState.value?.first ?: false
        val connState = msdkManagerVM.lvProductConnectionState.value?.first ?: false
        val cruiseIdx = automatedFlightVM.cruiseWaypointIndex.value ?: -1

        // 航点列表（含具体坐标）
        val wpList = wps.mapIndexed { i, wp ->
            val ns = if (wp.latitude >= 0) "N" else "S"
            val ew = if (wp.longitude >= 0) "E" else "W"
            mapOf(
                "index" to i,
                "latitude" to wp.latitude,
                "longitude" to wp.longitude,
                "altitude" to wp.altitude,
                "label" to "#${i + 1}: ${String.format("%.6f", Math.abs(wp.latitude))}°$ns  ${String.format("%.6f", Math.abs(wp.longitude))}°$ew  @ ${String.format("%.1f", wp.altitude)}m"
            )
        }

        return mapOf(
            "missionState" to state,
            "altitude" to altitude,
            "sdkRegistered" to regState,
            "productConnected" to connState,
            "statusMessage" to msg,
            "vsEnabled" to vs,
            "operationMode" to mode,
            "waypointCount" to wps.size,
            "waypoints" to wpList,
            "cruiseWaypointIndex" to cruiseIdx,
            "climbHeight" to p.climbHeight,
            "moveDistance" to p.moveDistance,
            "yawAngle" to p.yawAngle,
            "sdkStatusText" to (msdkManagerVM.lvSdkStatus.value ?: "未知"),
            "sdkInitProcess" to (msdkManagerVM.lvInitProcess.value?.let { "${it.first.name}" } ?: "未知"),
            "sdkInitComplete" to msdkManagerVM.isInit,
            "sdkInitStarted" to msdkManagerVM.sdkInitStarted,
            "batteryPercent" to batteryPercent,
            "batteryVoltage" to batteryVoltage
        )
    }
}
