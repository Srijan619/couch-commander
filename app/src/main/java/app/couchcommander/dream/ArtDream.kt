package app.couchcommander.dream

import android.content.Intent
import android.service.dreams.DreamService
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.couchcommander.LauncherApp
import app.couchcommander.MainActivity
import app.couchcommander.ui.ambient.AmbientScreen
import app.couchcommander.ui.rememberFootballLines
import app.couchcommander.ui.theme.CouchTheme

/**
 * The ambient-art screensaver. DreamService isn't a LifecycleOwner, so it provides the lifecycle and saved-state
 * owners Compose needs itself.
 */
class ArtDream : DreamService(), LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Interactive, so a key press shows the painting's details instead of ending the screensaver.
        isInteractive = true
        isFullscreen = true
        val container = (application as LauncherApp).container
        window.decorView.setViewTreeLifecycleOwner(this)
        window.decorView.setViewTreeSavedStateRegistryOwner(this)
        setContentView(
            ComposeView(this).apply {
                setContent {
                    CouchTheme {
                        val frame by container.art.frame.collectAsStateWithLifecycle()
                        val config by container.config.config.collectAsStateWithLifecycle()
                        val matches by container.football.matches.collectAsStateWithLifecycle()
                        val football = rememberFootballLines(matches, config.footballTeams)
                        AmbientScreen(
                            frame = frame,
                            showClock = config.ambientInfo,
                            football = football.takeIf { config.ambientFootball },
                            onNext = container.art::next,
                            onHome = ::goHome,
                        )
                    }
                }
            },
        )
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onDreamingStopped() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDetachedFromWindow()
    }

    private fun goHome() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }
}
