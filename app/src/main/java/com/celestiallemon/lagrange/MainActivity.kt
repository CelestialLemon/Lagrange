package com.celestiallemon.lagrange

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
        // App-open catch-up: every time Lagrange comes to the foreground it harvests and refreshes permission health.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = main.onAppForeground()
            override fun onResume(owner: LifecycleOwner) = main.refreshHealth()
        })
        setContent { LagrangeRoot(main) }
    }
}
