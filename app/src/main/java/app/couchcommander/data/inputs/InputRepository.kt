package app.couchcommander.data.inputs

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "CouchInputs"

/** A physical input with something plugged in. [standby]: connected but the device is asleep (HDMI-CEC). */
data class TvInput(val id: String, val label: String, val standby: Boolean)

/**
 * The TV's physical inputs (HDMI and the like) that have a device plugged in, from the TV Input Framework. Tuners
 * and app-provided inputs are left out, as are inputs the user hid in the TV's settings. Where the TV lists an
 * HDMI-CEC device as its own input under a port (named after the device, e.g. "Xbox One"), that entry replaces the
 * bare port. Kept up to date as cables and devices come and go. Empty on devices without TV inputs.
 */
class InputRepository(private val context: Context) {
    private val manager = context.getSystemService(TvInputManager::class.java)

    private val _inputs = MutableStateFlow<List<TvInput>>(emptyList())
    val inputs: StateFlow<List<TvInput>> = _inputs.asStateFlow()

    /**
     * Listing inputs is a round of calls to the TV's input service (slow on a Bravia with HDMI-CEC devices), so it
     * runs on its own thread: on the main thread it held up the launcher's first frame on every return to it.
     */
    private val worker = Handler(HandlerThread("tv-inputs").apply { start() }.looper)
    private val load = Runnable {
        val m = manager ?: return@Runnable
        _inputs.value = runCatching { load(m) }.onFailure { Log.w(TAG, "couldn't list inputs", it) }.getOrDefault(emptyList())
    }

    private val callback = object : TvInputManager.TvInputCallback() {
        override fun onInputStateChanged(inputId: String, state: Int) = refresh()
        override fun onInputAdded(inputId: String) = refresh()
        override fun onInputRemoved(inputId: String) = refresh()
        override fun onInputUpdated(inputId: String) = refresh()
        override fun onTvInputInfoUpdated(inputInfo: TvInputInfo) = refresh()
    }

    init {
        manager?.registerCallback(callback, worker)
        refresh()
    }

    /** Re-reads the inputs in the background; requests that pile up while one is waiting collapse into it. */
    fun refresh() {
        if (manager == null) return
        worker.removeCallbacks(load)
        worker.post(load)
    }

    private fun load(m: TvInputManager): List<TvInput> {
        val physical = m.tvInputList.filter { it.isPassthroughInput && it.type != TvInputInfo.TYPE_TUNER && !it.isHidden(context) }
        val parents = physical.mapNotNullTo(HashSet()) { it.parentId }
        return physical
            // A port with a named CEC device under it shows as that device instead.
            .filter { it.id !in parents }
            .mapNotNull { info ->
                val state = m.getInputState(info.id)
                if (state == TvInputManager.INPUT_STATE_DISCONNECTED) return@mapNotNull null
                val label = (info.loadCustomLabel(context) ?: info.loadLabel(context))?.toString()?.trim().orEmpty()
                TvInput(info.id, label.ifEmpty { "Input" }, standby = state == TvInputManager.INPUT_STATE_CONNECTED_STANDBY)
            }
            .sortedBy { it.label.lowercase() }
    }

    /** Switches the TV to [input], through the TV's own live TV app. */
    fun open(input: TvInput) {
        val intent = Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(input.id))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure { Log.w(TAG, "couldn't open ${input.id}", it) }
    }
}
