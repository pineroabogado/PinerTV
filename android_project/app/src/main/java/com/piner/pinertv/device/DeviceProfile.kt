package com.piner.pinertv.device

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object DeviceProfile {
    fun isTv(context: Context): Boolean {
        val pm = context.packageManager
        return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            pm.hasSystemFeature("amazon.hardware.fire_tv")
    }

    fun isAmazonDevice(): Boolean {
        val manu = Build.MANUFACTURER.orEmpty().lowercase()
        val model = Build.MODEL.orEmpty().lowercase()
        return manu.contains("amazon") || model.contains("aft")
    }

    fun deviceLabel(): String = "${Build.MANUFACTURER} ${Build.MODEL}"
}
