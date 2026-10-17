package app.couchcommander.system

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import app.couchcommander.MainActivity

/**
 * Makes Couch Commander the Home screen on TVs that don't let you choose one (Google TV). Disabling the stock
 * launcher isn't an option there: Google's setup app then takes over Home and loops. Instead, once the user turns
 * this service on (Settings › Accessibility), it opens Couch Commander whenever the stock launcher comes to the
 * front: after Home, or backing out of an app. The stock launcher shows for a moment first.
 *
 * It doesn't filter keys: on Google TV (tested on a Bravia, Android 12) the system handles Home before
 * accessibility services see it, and filtering would route every key press in every app through this service.
 * Activity starts from here are allowed in the background because the system binds accessibility services.
 */
class HomeButtonService : AccessibilityService() {
    private var openedAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // The service config only delivers window changes from the stock launchers.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.packageName?.toString() in STOCK_LAUNCHERS) {
            openLauncher()
        }
    }

    override fun onInterrupt() = Unit

    private fun openLauncher() {
        // The stock launcher reports several window changes as it appears; one switch is enough.
        val now = SystemClock.uptimeMillis()
        if (now - openedAt < DEBOUNCE_MS) return
        openedAt = now
        startActivity(
            Intent(this, MainActivity::class.java)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
        )
    }

    companion object {
        private const val DEBOUNCE_MS = 1_000L

        /** Keep in sync with android:packageNames in res/xml/home_button_service.xml. */
        private val STOCK_LAUNCHERS = setOf(
            "com.google.android.apps.tv.launcherx",
            "com.google.android.tvlauncher",
            "com.google.android.leanbacklauncher",
        )

        fun isEnabled(context: Context): Boolean {
            val ours = ComponentName(context, HomeButtonService::class.java)
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == ours }
        }
    }
}
