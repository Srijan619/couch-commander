package app.couchcommander.system

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import app.couchcommander.dream.ArtDream

/**
 * Which screensaver the system runs. Google TV's settings only offer Google's own Ambient mode, so the launcher sets
 * Couch Commander's itself. Writing it needs WRITE_SECURE_SETTINGS, which only adb can grant (scripts/set-home.sh).
 */
object Screensaver {
    // Hidden Settings.Secure keys.
    private const val COMPONENTS = "screensaver_components"
    private const val ENABLED = "screensaver_enabled"

    fun isOurs(context: Context): Boolean {
        val current = Settings.Secure.getString(context.contentResolver, COMPONENTS).orEmpty().split(',').firstOrNull()
        return current?.let(ComponentName::unflattenFromString) == ComponentName(context, ArtDream::class.java)
    }

    /** Makes the art screensaver the system's. False when the adb grant is missing. */
    fun useOurs(context: Context): Boolean {
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return false
        return runCatching {
            Settings.Secure.putString(context.contentResolver, COMPONENTS, ComponentName(context, ArtDream::class.java).flattenToString())
            Settings.Secure.putInt(context.contentResolver, ENABLED, 1)
        }.isSuccess
    }
}
