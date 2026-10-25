package com.layerbit.abhyas.data

import android.content.Context

/**
 * Small per-install UI choices. SharedPreferences for the same reason as the reminder settings:
 * a couple of booleans read once each are not worth DataStore and a coroutine.
 */
class AppPreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("app", Context.MODE_PRIVATE)

    /** Whether the one-screen introduction has been seen and dismissed. Shown once, ever. */
    var introSeen: Boolean
        get() = prefs.getBoolean(KEY_INTRO_SEEN, false)
        set(value) = prefs.edit().putBoolean(KEY_INTRO_SEEN, value).apply()

    /**
     * Whether capture stays on the camera after each page, collecting suggestions until the user
     * asks to review them all. Remembered, because the student photographing a whole chapter does
     * it every time.
     */
    var batchCapture: Boolean
        get() = prefs.getBoolean(KEY_BATCH, false)
        set(value) = prefs.edit().putBoolean(KEY_BATCH, value).apply()

    private companion object {
        const val KEY_INTRO_SEEN = "intro_seen"
        const val KEY_BATCH = "batch_capture"
    }
}
