package com.spacexjr.part2dump

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.spacexjr.part2dump.core.P2DLog

class Part2DumpApp : Application() {
    override fun onCreate() {
        super.onCreate()
        P2DLog.attach(this)
        P2DLog.i("Part2Dump iniciado")
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}