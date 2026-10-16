package com.piner.pinertv

import android.app.Application
import com.piner.pinertv.data.PersonIndex

class PinerTvApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PersonIndex.init(this)
    }
}
