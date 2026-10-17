package app.couchcommander.ui

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

/** The clock and date strings, updated on each minute boundary. */
class ClockText(val time: String, val date: String)

@Composable
fun rememberClock(): ClockText {
    val context = LocalContext.current
    val is24Hour = remember { DateFormat.is24HourFormat(context) }
    val timeFormat = remember(is24Hour) { DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm", Locale.getDefault()) }
    val dateFormat = remember { DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()) }
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(60_000L - System.currentTimeMillis() % 60_000L)
        }
    }
    return ClockText(timeFormat.format(now), dateFormat.format(now))
}
