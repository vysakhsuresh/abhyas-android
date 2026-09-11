package com.layerbit.abhyas.data.reminder

import android.content.Context

/**
 * Whether the user asked for a daily reminder, and when.
 *
 * The default is **off**, and that is the whole design. Nothing is scheduled, no notification
 * channel is touched and no permission is requested until someone turns this on themselves in
 * Settings. An app that starts nudging you because it was installed has decided something on
 * your behalf that was never its call.
 *
 * SharedPreferences rather than DataStore: three values, read once on a settings screen and once
 * inside a worker, is not worth a dependency and a coroutine.
 */
class ReminderPreferences(context: Context) {

    private val prefs = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    /** Off until the user says otherwise. Never written by anything but an explicit toggle. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var hour: Int
        get() = prefs.getInt(KEY_HOUR, DEFAULT_HOUR)
        set(value) = prefs.edit().putInt(KEY_HOUR, value.coerceIn(0, 23)).apply()

    var minute: Int
        get() = prefs.getInt(KEY_MINUTE, DEFAULT_MINUTE)
        set(value) = prefs.edit().putInt(KEY_MINUTE, value.coerceIn(0, 59)).apply()

    /**
     * Whether the reminder has already fired today, as a local date string.
     *
     * WorkManager guarantees a worker runs *at least* once per period, not exactly once, and a
     * device that was asleep can run a deferred job the moment it wakes. Without this, coming off
     * a long flight would produce several identical reminders in a row.
     */
    var lastFiredOn: String
        get() = prefs.getString(KEY_LAST_FIRED, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_LAST_FIRED, value).apply()

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_HOUR = "hour"
        const val KEY_MINUTE = "minute"
        const val KEY_LAST_FIRED = "last_fired_on"

        /** Early evening: after school, before the night is written off. */
        const val DEFAULT_HOUR = 19
        const val DEFAULT_MINUTE = 0
    }
}
