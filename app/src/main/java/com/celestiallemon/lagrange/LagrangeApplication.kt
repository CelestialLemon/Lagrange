package com.celestiallemon.lagrange

import android.app.Application
import com.celestiallemon.lagrange.harvest.WorkScheduler

class LagrangeApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // A pending reset wipe and restore swap/rollback run inside graph.start(), on IO, behind the startup gate.
        graph = AppGraph(this)
        graph.start()
        WorkScheduler.schedulePeriodicHarvest(this)
    }
}
