package app.couchcommander.domain

/** The home row's picked apps and their order, kept free of Android types so it can be unit tested. */
object AppOrder {
    /** The installed apps among [picked], in picked order. Picked apps that are no longer installed are skipped. */
    fun <T> shown(apps: List<T>, picked: List<String>, key: (T) -> String): List<T> {
        val byKey = apps.associateBy(key)
        return picked.distinct().mapNotNull { byKey[it] }
    }

    /** Adds [key] to the end of the row, or takes it off if it is already there. */
    fun toggle(picked: List<String>, key: String): List<String> = if (key in picked) picked - key else picked + key

    /**
     * The new row after moving [moved] by [delta] places among the [visible] keys (the row as shown). Keys of
     * uninstalled apps drop out here, which is fine: they weren't shown.
     */
    fun move(visible: List<String>, moved: String, delta: Int): List<String> {
        val from = visible.indexOf(moved)
        if (from < 0) return visible
        val to = (from + delta).coerceIn(0, visible.lastIndex)
        if (to == from) return visible
        return visible.toMutableList().apply { add(to, removeAt(from)) }
    }
}
