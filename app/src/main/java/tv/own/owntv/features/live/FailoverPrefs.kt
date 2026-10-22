package tv.own.owntv.features.live

import android.content.Context

/**
 * The two cross-playlist failover settings, ported from the mobile app's `FailoverPrefs`:
 * whether a hop happens at all ("Switch server on interruption", default on) and how many failed
 * engine attempts the same channel is given on its own stream before it hops.
 *
 * App-local [android.content.SharedPreferences] backs the two settings on purpose: the shared
 * settings store lives in the external core artifact, which this fork does not change, so
 * failover tuning stays additive here.
 */
object FailoverPrefs {
    const val DEFAULT_RETRIES = 3
    const val MAX_RETRIES = 10
    val RETRY_CHOICES: IntRange = 0..MAX_RETRIES

    private const val PREFS = "owntv_server_failover"
    private const val KEY_RETRIES = "stream_retries"
    private const val KEY_ENABLED = "hop_enabled"

    fun getRetries(context: Context): Int =
        prefs(context).getInt(KEY_RETRIES, DEFAULT_RETRIES).coerceIn(RETRY_CHOICES)

    fun setRetries(context: Context, retries: Int) {
        prefs(context).edit().putInt(KEY_RETRIES, retries.coerceIn(RETRY_CHOICES)).apply()
    }

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
