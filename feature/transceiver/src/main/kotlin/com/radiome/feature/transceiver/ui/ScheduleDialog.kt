package com.radiome.feature.transceiver.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat as AndroidDateFormat
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.radiome.feature.transceiver.History
import com.radiome.feature.transceiver.i18n.tr
import com.radiome.feature.transceiver.link.Repeat
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** When a draft from the composer should go out. */
@Composable
fun ScheduleDraftDialog(onDismiss: () -> Unit, onSchedule: (at: Long, repeat: Repeat) -> Unit) {
    var at by rememberSaveable { mutableLongStateOf(System.currentTimeMillis() + 15 * 60_000L) }
    var repeat by rememberSaveable { mutableStateOf(Repeat.ONCE) }
    val inFuture = at > System.currentTimeMillis()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Schedule for later")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeChooser(at, { at = it }, repeat, { repeat = it })
            }
        },
        confirmButton = { TextButton(onClick = { onSchedule(at, repeat) }, enabled = inFuture) { Text(tr("Schedule")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

/** A new scheduled text or position, made from the History tab. */
@Composable
fun NewScheduleDialog(
    initialTo: String,
    onDismiss: () -> Unit,
    onScheduleText: (text: String, to: String, at: Long, repeat: Repeat) -> Unit,
    onSchedulePosition: (at: Long, repeat: Repeat) -> Unit,
) {
    var position by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    var to by rememberSaveable { mutableStateOf(initialTo) }
    var at by rememberSaveable { mutableLongStateOf(System.currentTimeMillis() + 15 * 60_000L) }
    var repeat by rememberSaveable { mutableStateOf(Repeat.ONCE) }
    val ready = at > System.currentTimeMillis() && (position || text.isNotBlank())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Schedule a message")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !position, onClick = { position = false }, label = { Text(tr("Message")) })
                    FilterChip(selected = position, onClick = { position = true }, label = { Text(tr("My position")) })
                }
                if (position) {
                    Text(
                        tr("Your GPS position is read when it is due and sent encrypted to every station."),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    OutlinedTextField(
                        value = to,
                        onValueChange = { to = it.uppercase().take(10) },
                        label = { Text(tr("To (callsign)")) },
                        placeholder = { Text(tr("All stations")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text(tr("Message")) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                    )
                }
                TimeChooser(at, { at = it }, repeat, { repeat = it })
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (position) onSchedulePosition(at, repeat) else onScheduleText(text, to, at, repeat) },
                enabled = ready,
            ) { Text(tr("Schedule")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

/** Quick picks, a date and time picker, and how often it repeats. */
@Composable
private fun TimeChooser(at: Long, onAt: (Long) -> Unit, repeat: Repeat, onRepeat: (Repeat) -> Unit) {
    val context = LocalContext.current
    val label = remember(at) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(at)) }
    val now = System.currentTimeMillis()
    Text(tr("Goes out %s", label), style = MaterialTheme.typography.titleSmall)
    Text(
        if (at > now) History.dueIn(at, now) else tr("That time has passed. Pick a later one."),
        style = MaterialTheme.typography.bodySmall,
        color = if (at > now) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
    )
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(15 to tr("In 15 min"), 60 to tr("In 1 h"), 3 * 60 to tr("In 3 h")).forEach { (min, text) ->
            FilterChip(selected = false, onClick = { onAt(System.currentTimeMillis() + min * 60_000L) }, label = { Text(text) })
        }
        FilterChip(selected = false, onClick = { onAt(tomorrowAt(8)) }, label = { Text(tr("Tomorrow 08:00")) })
    }
    OutlinedButton(
        onClick = {
            val cal = Calendar.getInstance().apply { timeInMillis = at }
            DatePickerDialog(context, { _, y, m, d ->
                TimePickerDialog(context, { _, h, min ->
                    val picked = Calendar.getInstance().apply {
                        set(y, m, d, h, min, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    onAt(picked.timeInMillis)
                }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), AndroidDateFormat.is24HourFormat(context)).show()
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).apply {
                datePicker.minDate = System.currentTimeMillis() - 1_000
            }.show()
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(tr("Pick date and time")) }
    Text(tr("Repeat"), style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Repeat.entries.forEach { r ->
            FilterChip(selected = repeat == r, onClick = { onRepeat(r) }, label = { Text(History.repeatLabel(r)) })
        }
    }
    Text(
        tr("If the radio is off at that time, it goes out as soon as you turn it on."),
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun tomorrowAt(hour: Int): Long = Calendar.getInstance().apply {
    add(Calendar.DAY_OF_YEAR, 1)
    set(Calendar.HOUR_OF_DAY, hour)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis
