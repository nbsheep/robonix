package com.dji.wang.aircraft.models

import android.content.Context
import androidx.lifecycle.MutableLiveData
import com.dji.wang.aircraft.data.FragmentPageItemList
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.ldm.LDMExemptModule
import dji.v5.manager.SDKManager
import dji.v5.manager.ldm.LDMManager


/**
 * Class Description
 *
 * @author Hoker
 * @date 2021/5/10
 *
 * Copyright (c) 2021, DJI All Rights Reserved.
 */
class MSDKCommonOperateVm : DJIViewModel() {

    val mainPageInfoList = MutableLiveData<LinkedHashSet<FragmentPageItemList>>()

    fun loaderItem(itemList: LinkedHashSet<FragmentPageItemList>) {
        mainPageInfoList.postValue(itemList)
    }

    fun enableLDM(context: Context, callback: CommonCallbacks.CompletionCallback, ldmExemptModuleList: Array<LDMExemptModule?>) {
        LDMManager.getInstance().enableLDM(context, callback, * ldmExemptModuleList)
    }

    fun disableLDM(callback: CommonCallbacks.CompletionCallback) {
        LDMManager.getInstance().disableLDM(callback);
    }

    fun registerApp() {
        SDKManager.getInstance().registerApp()
    }
}