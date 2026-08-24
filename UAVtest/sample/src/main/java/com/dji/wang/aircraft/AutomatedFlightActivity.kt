package com.dji.wang.aircraft

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.dji.wang.aircraft.databinding.ActivityAutomatedFlightBinding
import com.dji.wang.aircraft.models.*
import dji.v5.manager.SDKManager
import dji.v5.ux.core.util.ToastUtils

class AutomatedFlightActivity : AppCompatActivity() {

    private val automatedFlightVM: AutomatedFlightVM by viewModels()
    private val msdkManagerVM: MSDKManagerVM by globalViewModels()
    private lateinit var binding: ActivityAutomatedFlightBinding
    private val handler = Handler(Looper.getMainLooper())
    private var sdkRegistered = false
    private var productConnected = false
    private var webServer: WebServer? = null
    private var lastFrameBitmap: Bitmap? = null

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
        startVideoLoop()
    }

    // ---- 视频流显示（读取 VideoFrameProvider 最新 JPEG 帧） ----

    private val videoRunnable = object : Runnable {
        override fun run() {
            val jpeg = VideoFrameProvider.latestJpeg
            if (jpeg != null && jpeg.isNotEmpty()) {
                try {
                    val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                    if (bmp != null) {
                        binding.imageVideo.setImageBitmap(bmp)
                        lastFrameBitmap?.recycle()
                        lastFrameBitmap = bmp
                    }
                } catch (_: Exception) {}
            }
            handler.postDelayed(this, 100)
        }
    }

    private fun startVideoLoop() {
        handler.post(videoRunnable)
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

    /** 云台单步转动角度（默认 7.5°） */
    private fun readGimbalStep(): Double {
        val deg = binding.editGimbalStep.text.toString().toDoubleOrNull() ?: 7.5
        return deg.coerceIn(0.5, 180.0)
    }

    /** 手动模式云台单步转动角度（默认 7.5°） */
    private fun readManualGimbalStep(): Double {
        val deg = binding.editManualGimbalStep.text.toString().toDoubleOrNull() ?: 7.5
        return deg.coerceIn(0.5, 180.0)
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

        // 巡航：获取当前GPS填入经纬度输入框（高度由用户设置后用 + 添加）
        binding.btnGetCurrentGps.setOnClickListener {
            automatedFlightVM.ensurePositionListening()
            val pos = automatedFlightVM.getCurrentGps()
            if (pos != null) {
                binding.editWpLat.setText(String.format("%.6f", pos.latitude))
                binding.editWpLng.setText(String.format("%.6f", pos.longitude))
                ToastUtils.showToast("已填入当前GPS坐标，请设置高度后用 + 添加航点")
            } else {
                ToastUtils.showToast("未获取到GPS，请确认无人机已连接并已定位")
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

        // 巡航：紧急悬停 / 重启巡航
        binding.btnPauseCruise.setOnClickListener {
            if (automatedFlightVM.isCruisePaused.value == true) {
                automatedFlightVM.resumeCruise()
                ToastUtils.showToast("重启巡航中...")
            } else {
                automatedFlightVM.pauseCruise()
                ToastUtils.showToast("紧急悬停：巡航已暂停")
            }
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

        // 云台控制（巡航中）—— 单步角度由输入框决定
        binding.btnGimbalPitchUp.setOnClickListener { automatedFlightVM.gimbalPitchUp(readGimbalStep()) }
        binding.btnGimbalPitchDown.setOnClickListener { automatedFlightVM.gimbalPitchDown(readGimbalStep()) }
        binding.btnGimbalYawLeft.setOnClickListener { automatedFlightVM.gimbalYawLeft(readGimbalStep()) }
        binding.btnGimbalYawRight.setOnClickListener { automatedFlightVM.gimbalYawRight(readGimbalStep()) }
        binding.btnGimbalLookDown.setOnClickListener { automatedFlightVM.gimbalLookDown() }
        binding.btnGimbalLevel.setOnClickListener { automatedFlightVM.gimbalLevel() }

        // 相机控制（巡航中）
        binding.btnCameraTakePhoto.setOnClickListener { automatedFlightVM.cameraTakePhoto() }
        binding.btnCameraStartRecord.setOnClickListener { automatedFlightVM.cameraStartRecord() }
        binding.btnCameraStopRecord.setOnClickListener { automatedFlightVM.cameraStopRecord() }
        binding.btnCameraZoomIn.setOnClickListener { automatedFlightVM.cameraZoomIn() }
        binding.btnCameraZoomOut.setOnClickListener { automatedFlightVM.cameraZoomOut() }

        // 云台 + 相机控制（手动操控模式，与自动巡航逻辑一致）
        binding.btnManualGimbalPitchUp.setOnClickListener { automatedFlightVM.gimbalPitchUp(readManualGimbalStep()) }
        binding.btnManualGimbalPitchDown.setOnClickListener { automatedFlightVM.gimbalPitchDown(readManualGimbalStep()) }
        binding.btnManualGimbalYawLeft.setOnClickListener { automatedFlightVM.gimbalYawLeft(readManualGimbalStep()) }
        binding.btnManualGimbalYawRight.setOnClickListener { automatedFlightVM.gimbalYawRight(readManualGimbalStep()) }
        binding.btnManualGimbalLookDown.setOnClickListener { automatedFlightVM.gimbalLookDown() }
        binding.btnManualGimbalLevel.setOnClickListener { automatedFlightVM.gimbalLevel() }
        binding.btnManualCameraTakePhoto.setOnClickListener { automatedFlightVM.cameraTakePhoto() }
        binding.btnManualCameraStartRecord.setOnClickListener { automatedFlightVM.cameraStartRecord() }
        binding.btnManualCameraStopRecord.setOnClickListener { automatedFlightVM.cameraStopRecord() }
        binding.btnManualCameraZoomIn.setOnClickListener { automatedFlightVM.cameraZoomIn() }
        binding.btnManualCameraZoomOut.setOnClickListener { automatedFlightVM.cameraZoomOut() }

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
        binding.waypointListContainer.removeAllViews()
        if (wps.isEmpty()) {
            val empty = TextView(this).apply {
                text = "尚未添加航点"
                setTextColor(0xFF666666.toInt())
                textSize = 12f
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }
            binding.waypointListContainer.addView(empty)
            return
        }
        wps.forEachIndexed { i, wp ->
            val ns = if (wp.latitude >= 0) "N" else "S"
            val ew = if (wp.longitude >= 0) "E" else "W"
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
            }
            val label = TextView(this).apply {
                text = "#${i + 1}: ${String.format("%.6f", Math.abs(wp.latitude))}°$ns  ${String.format("%.6f", Math.abs(wp.longitude))}°$ew  @ ${String.format("%.1f", wp.altitude)}m"
                setTextColor(0xFFCCCCCC.toInt())
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(label)
            row.addView(makeWaypointBtn("↑", 0xFF00897B.toInt()) {
                automatedFlightVM.moveWaypointUp(i); updateWaypointListDisplay()
            })
            row.addView(makeWaypointBtn("↓", 0xFF00897B.toInt()) {
                automatedFlightVM.moveWaypointDown(i); updateWaypointListDisplay()
            })
            row.addView(makeWaypointBtn("✕", 0xFFF44336.toInt()) {
                automatedFlightVM.removeWaypoint(i); updateWaypointListDisplay()
            })
            binding.waypointListContainer.addView(row)
        }
    }

    private fun makeWaypointBtn(text: String, color: Int, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            this.textSize = 11f
            this.setTextColor(0xFFFFFFFF.toInt())
            this.setBackgroundColor(color)
            this.setPadding(0, 0, 0, 0)
            this.layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                marginStart = dp(4)
            }
            this.setOnClickListener { onClick() }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

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
            if (resultPair.first) ToastUtils.showToast("SDK注册成功")
            updateStartButtonState()
        }
        msdkManagerVM.lvProductConnectionState.observe(this) { resultPair ->
            productConnected = resultPair.first
            if (resultPair.first) {
                binding.textProductStatus.text = "无人机: 已连接"
                binding.textProductStatus.setTextColor(0xFF4CAF50.toInt())
                automatedFlightVM.ensurePositionListening()
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
            }
            if (!productConnected) {
                binding.textProductStatus.text = "无人机: 等待连接..."
                binding.textProductStatus.setTextColor(0xFFFFCC00.toInt())
            }
        } catch (_: Exception) {}
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

        // 巡航进行中：显示云台控制区、相机控制区与巡航反馈
        automatedFlightVM.isCruiseActive.observe(this) { active ->
            binding.sectionGimbalControl.visibility =
                if (active) android.view.View.VISIBLE else android.view.View.GONE
            binding.sectionCameraControl.visibility =
                if (active) android.view.View.VISIBLE else android.view.View.GONE
            binding.textCruiseFeedback.visibility =
                if (active) android.view.View.VISIBLE else android.view.View.GONE
            binding.btnPauseCruise.visibility =
                if (active) android.view.View.VISIBLE else android.view.View.GONE
        }
        // 紧急悬停 / 重启巡航按钮状态
        automatedFlightVM.isCruisePaused.observe(this) { paused ->
            if (paused) {
                binding.btnPauseCruise.text = "▶ 重启巡航"
                binding.btnPauseCruise.setBackgroundColor(0xFF00C853.toInt())
            } else {
                binding.btnPauseCruise.text = "⏸ 紧急悬停"
                binding.btnPauseCruise.setBackgroundColor(0xFFF44336.toInt())
            }
        }
        automatedFlightVM.cruiseFeedback.observe(this) { binding.textCruiseFeedback.text = it }
        automatedFlightVM.cameraFeedback.observe(this) {
            binding.textCameraFeedback.text = it
            binding.textManualCameraFeedback.text = it
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
            binding.btnGetCurrentGps.isEnabled = isIdle
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
                    if (automatedFlightVM.missionState.value != AutomatedFlightVM.MissionState.IDLE)
                        return mapOf("success" to false, "message" to "只能在待命状态下切换模式")
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
                    if (automatedFlightVM.missionState.value != AutomatedFlightVM.MissionState.IDLE)
                        return mapOf("success" to false, "message" to "飞行中忽略航点操作")
                    handler.post { automatedFlightVM.addWaypoint(Waypoint(lat, lng, alt)) }
                    val size = (automatedFlightVM.waypoints.value?.size ?: 0) + 1
                    return mapOf("success" to true, "message" to "航点已添加", "total" to size)
                }

                override fun onCaptureCurrentGps(): Map<String, Any> {
                    val pos = automatedFlightVM.currentPosition.value
                    if (pos == null) {
                        return mapOf("success" to false, "message" to "未获取到GPS，请确认无人机已连接并已定位")
                    }
                    // 返回当前坐标，由客户端填入输入框、设置高度后再用 /api/add_waypoint 添加
                    return mapOf(
                        "success" to true,
                        "message" to "已获取当前GPS坐标",
                        "latitude" to pos.latitude,
                        "longitude" to pos.longitude
                    )
                }

                override fun onClearWaypoints(): Map<String, Any> {
                    if (automatedFlightVM.missionState.value != AutomatedFlightVM.MissionState.IDLE)
                        return mapOf("success" to false, "message" to "飞行中忽略航点操作")
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

                override fun onPauseCruise(): Map<String, Any> {
                    if (automatedFlightVM.isCruiseActive.value != true)
                        return mapOf("success" to false, "message" to "仅巡航中可用")
                    if (automatedFlightVM.isCruisePaused.value == true)
                        return mapOf("success" to false, "message" to "巡航已处于暂停状态")
                    handler.post { automatedFlightVM.pauseCruise() }
                    return mapOf("success" to true, "message" to "紧急悬停：巡航已暂停")
                }

                override fun onResumeCruise(): Map<String, Any> {
                    if (automatedFlightVM.isCruiseActive.value != true)
                        return mapOf("success" to false, "message" to "仅巡航中可用")
                    if (automatedFlightVM.isCruisePaused.value != true)
                        return mapOf("success" to false, "message" to "巡航未处于暂停状态")
                    handler.post { automatedFlightVM.resumeCruise() }
                    return mapOf("success" to true, "message" to "重启巡航中...")
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

                override fun onTakeoffHover(): Map<String, Any> {
                    if (automatedFlightVM.missionState.value != AutomatedFlightVM.MissionState.IDLE)
                        return mapOf("success" to false, "message" to "只能在就绪状态下起飞")
                    if (!SDKManager.getInstance().isRegistered)
                        return mapOf("success" to false, "message" to "SDK未激活")
                    handler.post { automatedFlightVM.takeoffAndHover() }
                    return mapOf("success" to true, "message" to "起飞悬停中...")
                }

                override fun onGimbal(action: String, step: Double): Map<String, Any> {
                    if (automatedFlightVM.isCruiseActive.value != true)
                        return mapOf("success" to false, "message" to "仅巡航中可用")
                    val s = step.coerceIn(0.5, 180.0)
                    val known = action in setOf("pitch_up", "pitch_down", "yaw_left", "yaw_right", "look_down", "level")
                    if (!known) return mapOf("success" to false, "message" to "未知云台操作: $action")
                    handler.post {
                        when (action) {
                            "pitch_up" -> automatedFlightVM.gimbalPitchUp(s)
                            "pitch_down" -> automatedFlightVM.gimbalPitchDown(s)
                            "yaw_left" -> automatedFlightVM.gimbalYawLeft(s)
                            "yaw_right" -> automatedFlightVM.gimbalYawRight(s)
                            "look_down" -> automatedFlightVM.gimbalLookDown()
                            "level" -> automatedFlightVM.gimbalLevel()
                        }
                    }
                    return mapOf("success" to true, "message" to "云台已执行: $action")
                }

                override fun onCamera(action: String): Map<String, Any> {
                    if (automatedFlightVM.isCruiseActive.value != true)
                        return mapOf("success" to false, "message" to "仅巡航中可用")
                    val known = action in setOf("photo", "start_record", "stop_record", "zoom_in", "zoom_out")
                    if (!known) return mapOf("success" to false, "message" to "未知相机操作: $action")
                    handler.post {
                        when (action) {
                            "photo" -> automatedFlightVM.cameraTakePhoto(fromWeb = true)
                            "start_record" -> automatedFlightVM.cameraStartRecord(fromWeb = true)
                            "stop_record" -> automatedFlightVM.cameraStopRecord(fromWeb = true)
                            "zoom_in" -> automatedFlightVM.cameraZoomIn()
                            "zoom_out" -> automatedFlightVM.cameraZoomOut()
                        }
                    }
                    return mapOf("success" to true, "message" to "相机已执行: $action")
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
        val cruiseActive = automatedFlightVM.isCruiseActive.value ?: false
        val cruisePaused = automatedFlightVM.isCruisePaused.value ?: false
        val cruiseFeedback = automatedFlightVM.cruiseFeedback.value ?: ""
        val cameraFeedback = automatedFlightVM.cameraFeedback.value ?: ""

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
            "cruiseActive" to cruiseActive,
            "cruisePaused" to cruisePaused,
            "cruiseFeedback" to cruiseFeedback,
            "cameraFeedback" to cameraFeedback,
            "climbHeight" to p.climbHeight,
            "moveDistance" to p.moveDistance,
            "yawAngle" to p.yawAngle,
            "sdkStatusText" to (msdkManagerVM.lvSdkStatus.value ?: "未知"),
            "sdkInitProcess" to (msdkManagerVM.lvInitProcess.value?.let { "${it.first.name}" } ?: "未知"),
            "sdkInitComplete" to msdkManagerVM.isInit,
            "sdkInitStarted" to msdkManagerVM.sdkInitStarted
        )
    }
}
