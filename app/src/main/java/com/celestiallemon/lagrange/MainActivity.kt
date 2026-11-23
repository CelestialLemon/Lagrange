package com.celestiallemon.lagrange

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.celestiallemon.lagrange.ui.LagrangeRoot
import com.celestiallemon.lagrange.ui.MainViewModel

class MainActivity : ComponentActivity() {
    private val main: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A widget, tile or digest tap names the tab to open; a recreated activity keeps the tab it was on.
        if (savedInstanceState == null) Destination.fromIntent(intent)?.let(main::openDestination)
        // App-open catch-up: every time Lagrange comes to the foreground it harvests and refreshes permission health.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = main.onAppForeground()
            override fun onResume(owner: LifecycleOwner) = main.refreshHealth()
        })
        setContent { LagrangeRoot(main) }
    }

    /** singleTask: an open Lagrange brought forward by a widget, tile or digest tap is re-routed here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Destination.fromIntent(intent)?.let(main::openDestination)
    }
}
