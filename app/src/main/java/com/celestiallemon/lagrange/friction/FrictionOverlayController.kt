package com.celestiallemon.lagrange.friction

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.celestiallemon.lagrange.data.db.IntentionTag
import com.celestiallemon.lagrange.domain.friction.ActiveOverlay
import com.celestiallemon.lagrange.ui.theme.LagrangeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Callbacks from the overlay UI back into [LagrangeAccessibilityService] and its [FrictionEngine].
 */
interface OverlayCallbacks {
    fun onSelectIntention(tag: IntentionTag)
    fun onSelectTimer(minutes: Int?)
    fun onPauseGoHome()
    fun onPauseRedirect()
    fun onPauseContinue()
    fun onPauseSnooze1h()
    fun onPauseBackPressed()
    fun onTimerGoHome()
    fun onTimerBackPressed()
    fun onTimerExtend5m()
    fun onTimerDismissSession()
    fun onNudgeDismissed(userTapped: Boolean)
    fun onOverlayUnexpectedlyDetached()
}

/**
 * Draws calm Material 3 friction cards via `WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY` owned by
 * [AccessibilityService] (zero `SYSTEM_ALERT_WINDOW` permission required).
 *
 * Blocking overlays (`LaunchPause`, `TimerExpired`) request best-effort transient audio focus after attachment
 * and register `OnBackInvokedCallback` inside `onAttachedToWindow()` (when the window's `ViewRootImpl` and
 * `OnBackInvokedDispatcher` exist on Android 13–17). Non-blocking nudges (`TimeBlindnessNudge`) use
 * `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL` with trusted touch passthrough and auto-dismiss after
 * [NUDGE_AUTO_DISMISS_MS].
 */
class FrictionOverlayController(
    private val service: AccessibilityService,
    private val mainScope: CoroutineScope,
    private val callbacks: OverlayCallbacks,
    private val useDynamicColor: () -> Boolean,
) {
    private val windowManager: WindowManager? = service.getSystemService(WindowManager::class.java)
    private val audioFocus = AudioFocusController(service)

    private var rootView: OverlayContainerView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var attachedKind: OverlayWindowKind? = null
    private var overlayState by mutableStateOf<ActiveOverlay?>(null)
    private var nudgeAutoDismissJob: Job? = null
    private var intentionalDetach: Boolean = false

    /**
     * Displays [overlay]. Returns `true` if the overlay window is attached and visible; returns `false` (and
     * releases audio focus cleanly) if the window could not be attached so the caller can recover engine state.
     */
    fun show(overlay: ActiveOverlay): Boolean {
        val desiredKind = when (overlay) {
            is ActiveOverlay.LaunchPause, is ActiveOverlay.TimerExpired -> OverlayWindowKind.BLOCKING
            is ActiveOverlay.TimeBlindnessNudge -> OverlayWindowKind.NUDGE_BANNER
        }
        overlayState = overlay
        nudgeAutoDismissJob?.cancel()
        nudgeAutoDismissJob = null

        if (rootView != null && attachedKind != desiredKind) {
            detachWindowOnly()
        }

        if (rootView == null) {
            if (!attachWindow(desiredKind)) {
                overlayState = null
                audioFocus.abandon()
                return false
            }
        }

        if (desiredKind == OverlayWindowKind.BLOCKING) {
            audioFocus.requestTransient()
        } else {
            audioFocus.abandon()
        }

        if (overlay is ActiveOverlay.TimeBlindnessNudge) {
            nudgeAutoDismissJob = mainScope.launch {
                delay(NUDGE_AUTO_DISMISS_MS)
                callbacks.onNudgeDismissed(userTapped = false)
            }
        }
        return true
    }

    fun update(overlay: ActiveOverlay) {
        if (rootView == null) {
            show(overlay)
        } else {
            overlayState = overlay
        }
    }

    fun dismiss() {
        nudgeAutoDismissJob?.cancel()
        nudgeAutoDismissJob = null
        overlayState = null
        audioFocus.abandon()
        detachWindowOnly()
    }

    private fun attachWindow(kind: OverlayWindowKind): Boolean {
        val wm = windowManager ?: return false
        val owner = OverlayLifecycleOwner().also { it.moveToResumed() }
        val handleBack: () -> Boolean = {
            when (overlayState) {
                is ActiveOverlay.LaunchPause -> {
                    callbacks.onPauseBackPressed()
                    true
                }
                is ActiveOverlay.TimerExpired -> {
                    callbacks.onTimerBackPressed()
                    true
                }
                else -> false
            }
        }

        lateinit var container: OverlayContainerView
        container = OverlayContainerView(
            context = service,
            registerBackCallback = kind == OverlayWindowKind.BLOCKING,
            onBack = handleBack,
            onUnexpectedDetach = {
                if (!intentionalDetach && rootView === container) {
                    rootView = null
                    lifecycleOwner = null
                    attachedKind = null
                    overlayState = null
                    nudgeAutoDismissJob?.cancel()
                    nudgeAutoDismissJob = null
                    audioFocus.abandon()
                    owner.moveToDestroyed()
                    callbacks.onOverlayUnexpectedlyDetached()
                }
            },
        )
        container.setViewTreeLifecycleOwner(owner)
        container.setViewTreeSavedStateRegistryOwner(owner)
        container.setViewTreeViewModelStoreOwner(owner)

        val compose = ComposeView(service).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                LagrangeTheme(dynamicColor = useDynamicColor()) {
                    when (val current = overlayState) {
                        is ActiveOverlay.LaunchPause -> LaunchPauseOverlayContent(
                            overlay = current,
                            onSelectIntention = callbacks::onSelectIntention,
                            onSelectTimer = callbacks::onSelectTimer,
                            onGoHome = callbacks::onPauseGoHome,
                            onRedirect = callbacks::onPauseRedirect,
                            onContinue = callbacks::onPauseContinue,
                            onSnooze1h = callbacks::onPauseSnooze1h,
                        )
                        is ActiveOverlay.TimerExpired -> TimerExpiredOverlayContent(
                            overlay = current,
                            onGoHome = callbacks::onTimerGoHome,
                            onExtend5m = callbacks::onTimerExtend5m,
                            onDismissSession = callbacks::onTimerDismissSession,
                        )
                        is ActiveOverlay.TimeBlindnessNudge -> TimeBlindnessNudgeContent(
                            overlay = current,
                            onDismiss = { callbacks.onNudgeDismissed(userTapped = true) },
                        )
                        null -> Unit
                    }
                }
            }
        }
        container.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val params = buildLayoutParams(kind)
        return try {
            wm.addView(container, params)
            rootView = container
            lifecycleOwner = owner
            attachedKind = kind
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "failed to attach TYPE_ACCESSIBILITY_OVERLAY window", e)
            owner.moveToDestroyed()
            audioFocus.abandon()
            false
        }
    }

    private fun detachWindowOnly() {
        val view = rootView
        val owner = lifecycleOwner
        rootView = null
        lifecycleOwner = null
        attachedKind = null
        if (view != null) {
            intentionalDetach = true
            try {
                view.unregisterBackCallbackNow()
                runCatching { windowManager?.removeViewImmediate(view) }
            } finally {
                intentionalDetach = false
            }
        }
        owner?.moveToDestroyed()
    }

    private fun buildLayoutParams(kind: OverlayWindowKind): WindowManager.LayoutParams {
        val flags = when (kind) {
            OverlayWindowKind.BLOCKING -> WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            OverlayWindowKind.NUDGE_BANNER ->
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }
        val height = if (kind == OverlayWindowKind.BLOCKING) {
            WindowManager.LayoutParams.MATCH_PARENT
        } else {
            WindowManager.LayoutParams.WRAP_CONTENT
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = if (kind == OverlayWindowKind.BLOCKING) Gravity.CENTER else (Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    private enum class OverlayWindowKind { BLOCKING, NUDGE_BANNER }

    private class OverlayContainerView(
        context: Context,
        private val registerBackCallback: Boolean,
        private val onBack: () -> Boolean,
        private val onUnexpectedDetach: () -> Unit,
    ) : FrameLayout(context) {
        private var backCallback: OnBackInvokedCallback? = null

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (registerBackCallback && backCallback == null) {
                val cb = OnBackInvokedCallback { onBack() }
                backCallback = cb
                runCatching {
                    findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(
                        OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                        cb,
                    )
                }
            }
        }

        override fun onDetachedFromWindow() {
            unregisterBackCallbackNow()
            super.onDetachedFromWindow()
            onUnexpectedDetach()
        }

        fun unregisterBackCallbackNow() {
            val cb = backCallback ?: return
            backCallback = null
            runCatching { findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(cb) }
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                if (onBack()) return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    private class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)
        override val viewModelStore: ViewModelStore = ViewModelStore()
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

        fun moveToResumed() {
            savedStateController.performAttach()
            savedStateController.performRestore(null)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun moveToDestroyed() {
            if (registry.currentState != Lifecycle.State.DESTROYED) {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }
            viewModelStore.clear()
        }
    }

    companion object {
        private const val TAG = "LagrangeOverlay"
        const val NUDGE_AUTO_DISMISS_MS = 6_000L
    }
}
