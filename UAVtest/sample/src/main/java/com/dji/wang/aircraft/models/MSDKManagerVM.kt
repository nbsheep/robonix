package com.dji.wang.aircraft.models

import android.content.Context
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.SDKManager
import dji.v5.manager.interfaces.SDKManagerCallback
import dji.v5.manager.ldm.LDMManager
import dji.v5.network.DJINetworkManager
import dji.v5.utils.common.ContextUtil

class MSDKManagerVM : ViewModel() {
    // The data is held in livedata mode, but you can also save the results of the sdk callbacks any way you like.
    val lvRegisterState = MutableLiveData<Pair<Boolean, IDJIError?>>()
    val lvProductConnectionState = MutableLiveData<Pair<Boolean, Int>>()
    val lvProductChanges = MutableLiveData<Int>()
    val lvInitProcess = MutableLiveData<Pair<DJISDKInitEvent, Int>>()
    val lvDBDownloadProgress = MutableLiveData<Pair<Long, Long>>()
    val lvSdkStatus = MutableLiveData<String>("初始化中...")  // 综合状态文本，带初始值
    var isInit = false
    var sdkInitStarted = false  // 标记init()是否已被调用

    fun initMobileSDK(appContext: Context) {
        sdkInitStarted = true
        lvSdkStatus.postValue("SDK初始化启动中...")
        // Initialize and set the sdk callback, which is held internally by the sdk until destroy() is called
        SDKManager.getInstance().init(appContext, object : SDKManagerCallback {
            override fun onRegisterSuccess() {
                lvRegisterState.postValue(Pair(true, null))
                lvSdkStatus.postValue("✓ SDK已激活")
            }

            override fun onRegisterFailure(error: IDJIError) {
                lvRegisterState.postValue(Pair(false, error))
                lvSdkStatus.postValue("✗ SDK注册失败: ${error.description()}")
            }

            override fun onProductDisconnect(productId: Int) {
                lvProductConnectionState.postValue(Pair(false, productId))
            }

            override fun onProductConnect(productId: Int) {
                lvProductConnectionState.postValue(Pair(true, productId))
            }

            override fun onProductChanged(productId: Int) {
                lvProductChanges.postValue(productId)
            }

            override fun onInitProcess(event: DJISDKInitEvent, totalProcess: Int) {
                lvInitProcess.postValue(Pair(event, totalProcess))
                lvSdkStatus.postValue("初始化: ${event.name}")
                // Don't forget to call the registerApp()
                if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) {
                    isInit = true
                    lvSdkStatus.postValue("正在注册SDK...")
                    SDKManager.getInstance().registerApp()
                }
            }

            override fun onDatabaseDownloadProgress(current: Long, total: Long) {
                lvDBDownloadProgress.postValue(Pair(current, total))
            }
        })

//        LDMManager.getInstance().enableLDM(ContextUtil.getContext(),null)

        DJINetworkManager.getInstance().addNetworkStatusListener { isAvailable ->
            if (isInit && isAvailable && !SDKManager.getInstance().isRegistered) {
                lvSdkStatus.postValue("网络已连接，重新注册SDK...")
                SDKManager.getInstance().registerApp()
            }
        }
    }

    fun destroyMobileSDK() {
        SDKManager.getInstance().destroy()
    }

}