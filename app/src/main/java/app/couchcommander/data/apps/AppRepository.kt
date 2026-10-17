package app.couchcommander.data.apps

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ResolveInfo
import android.os.UserHandle
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "CouchApps"

/**
 * An installed app. TV apps ([isTv]) come with a 16:9 [banner]; phone apps sideloaded onto the TV usually only have
 * an [icon] and are drawn as coloured tiles.
 */
class TvApp(
    val key: String,
    val packageName: String,
    val activity: String,
    val label: String,
    val isTv: Boolean,
    val banner: ImageBitmap?,
    val icon: ImageBitmap?,
)

/** Launchable apps, reloaded whenever a package is installed, updated or removed. */
class AppRepository(private val context: Context, private val scope: CoroutineScope) {
    private val pm = context.packageManager

    private val _apps = MutableStateFlow<List<TvApp>>(emptyList())
    val apps: StateFlow<List<TvApp>> = _apps.asStateFlow()

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = refresh()
        override fun onPackageAdded(packageName: String, user: UserHandle) = refresh()
        override fun onPackageChanged(packageName: String, user: UserHandle) = refresh()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = refresh()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = refresh()
    }

    init {
        context.getSystemService(LauncherApps::class.java)?.registerCallback(callback)
        refresh()
    }

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            runCatching { load() }.onSuccess { _apps.value = it }.onFailure { Log.w(TAG, "couldn't list apps", it) }
        }
    }

    private fun load(): List<TvApp> {
        val tv = query(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val tvPackages = tv.mapTo(HashSet()) { it.activityInfo.packageName }
        val phone = query(Intent.CATEGORY_LAUNCHER).filter { it.activityInfo.packageName !in tvPackages }
        return (tv.map { entry(it, isTv = true) } + phone.map { entry(it, isTv = false) })
            .filter { it.packageName != context.packageName }
            .distinctBy { it.key }
    }

    private fun query(category: String): List<ResolveInfo> =
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)

    private fun entry(info: ResolveInfo, isTv: Boolean): TvApp {
        val activity = info.activityInfo
        // ComponentInfo.loadBanner falls back to the app's own banner.
        val banner = if (isTv) runCatching { activity.loadBanner(pm)?.toBitmap(BANNER_W, BANNER_H) }.getOrNull() else null
        val icon = if (banner == null) runCatching { info.loadIcon(pm).toBitmap(ICON_PX, ICON_PX) }.getOrNull() else null
        return TvApp(
            key = "${activity.packageName}/${activity.name}",
            packageName = activity.packageName,
            activity = activity.name,
            label = info.loadLabel(pm).toString(),
            isTv = isTv,
            banner = banner?.asImageBitmap(),
            icon = icon?.asImageBitmap(),
        )
    }

    fun launch(app: TvApp) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(if (app.isTv) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
            .setClassName(app.packageName, app.activity)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { context.startActivity(intent) }.onFailure { Log.w(TAG, "couldn't open ${app.key}", it) }
    }

    private companion object {
        const val BANNER_W = 320
        const val BANNER_H = 180
        const val ICON_PX = 144
    }
}
