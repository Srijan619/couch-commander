package app.couchcommander.data

import app.couchcommander.domain.LauncherConfig
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** Settings as one small JSON file. Reads once at startup, writes atomically on every change. */
class ConfigRepository(dir: File, private val scope: CoroutineScope) {
    private val file = File(dir, "config.json")
    private val writeLock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _config = MutableStateFlow(load())
    val config: StateFlow<LauncherConfig> = _config.asStateFlow()

    fun update(transform: (LauncherConfig) -> LauncherConfig) {
        val before = _config.value
        _config.update(transform)
        if (_config.value != before) persist(_config.value)
    }

    private fun load(): LauncherConfig =
        runCatching { json.decodeFromString<LauncherConfig>(file.readText()) }.getOrElse { LauncherConfig() }

    private fun persist(config: LauncherConfig) {
        scope.launch(Dispatchers.IO) {
            writeLock.withLock {
                // Skip stale writes: a newer value may have been set while this one waited.
                if (config != _config.value) return@withLock
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeText(json.encodeToString(LauncherConfig.serializer(), config))
                tmp.renameTo(file)
            }
        }
    }
}
