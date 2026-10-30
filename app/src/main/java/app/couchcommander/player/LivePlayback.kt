package app.couchcommander.player

import app.iptv.VodPlay

/**
 * What the player has on screen right now, for a phone's `GET /now`. The shared player writes it on the main
 * thread whenever the player changes; the remote server reads it from its own, so it's a snapshot, never the player.
 */
object LivePlayback {
    /**
     * [positionMs] as of [atElapsed] (elapsedRealtime); while [advancing] it moves on from there at normal speed.
     * [playing] is what the viewer chose (false once paused); [advancing] is false while it buffers, too.
     */
    data class Snapshot(
        val owner: Any,
        val play: VodPlay,
        val positionMs: Long,
        val durationMs: Long,
        val playing: Boolean,
        val advancing: Boolean,
        val atElapsed: Long,
    ) {
        fun positionAt(nowElapsed: Long): Long {
            val moved = if (advancing && nowElapsed > atElapsed) positionMs + (nowElapsed - atElapsed) else positionMs
            return if (durationMs > 0) moved.coerceIn(0, durationMs) else moved.coerceAtLeast(0)
        }
    }

    @Volatile var current: Snapshot? = null
        private set

    fun publish(snapshot: Snapshot) { current = snapshot }

    /** Only [owner]'s: a title started over the one playing opens the new player before the old one stops. */
    fun clear(owner: Any) {
        if (current?.owner === owner) current = null
    }
}
