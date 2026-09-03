package com.dji.wang.aircraft

import androidx.fragment.app.commit
import com.dji.wang.aircraft.data.AircraftFragmentPageInfoFactory
import com.dji.wang.aircraft.data.CommonFragmentPageInfoFactory
import com.dji.wang.aircraft.data.FragmentPageItemList

/**
 * Class Description
 *
 * @author Hoker
 * @date 2021/3/9
 *
 * Copyright (c) 2021, DJI All Rights Reserved.
 */
class AircraftTestingToolsActivity : TestingToolsActivity() {

    override fun loadPages() {
        msdkCommonOperateVm.apply {
            val itemList = LinkedHashSet<FragmentPageItemList>().also {
                it.add(CommonFragmentPageInfoFactory().createPageInfo())
                it.add(AircraftFragmentPageInfoFactory().createPageInfo())
            }
            loaderItem(itemList)
        }
    }

    override fun loadTitleView() {
        supportFragmentManager.commit {
            replace(R.id.main_info_fragment_container, AircraftMSDKInfoFragment())
        }
    }
}