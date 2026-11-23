package com.celestiallemon.lagrange.friction

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/**
 * Requests transient audio focus (`AUDIOFOCUS_GAIN_TRANSIENT`) while a blocking friction overlay is on screen
 * so YouTube, Reels, or TikTok pause their audio behind the card, and abandons it immediately on dismiss (spec §5.3).
 */
class AudioFocusController(context: Context) {
    private val audioManager: AudioManager? = context.getSystemService(AudioManager::class.java)
    private var activeRequest: AudioFocusRequest? = null

    fun requestTransient() {
        val manager = audioManager ?: return
        if (activeRequest != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { /* Pause overlay never plays audio of its own. */ }
            .build()
        try {
            val result = manager.requestAudioFocus(request)
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED || result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED) {
                activeRequest = request
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "transient audio focus request failed", e)
        }
    }

    fun abandon() {
        val request = activeRequest ?: return
        activeRequest = null
        try {
            audioManager?.abandonAudioFocusRequest(request)
        } catch (e: RuntimeException) {
            Log.w(TAG, "abandoning audio focus failed", e)
        }
    }

    private companion object {
        const val TAG = "LagrangeAudioFocus"
    }
}
