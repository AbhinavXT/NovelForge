package com.abhinavxt.novelforge.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The goals on offer, in minutes; 0 turns the goal off. */
val DAILY_GOAL_CHOICES = listOf(0, 10, 15, 20, 30, 45, 60, 90)

/** "12 / 30 min today" with a bar; full once the goal is met. */
@Composable
fun DailyGoalBar(todayMinutes: Int, goalMinutes: Int, modifier: Modifier = Modifier) {
    val met = todayMinutes >= goalMinutes
    Column(modifier) {
        Text(
            text = if (met) "Daily goal met · $todayMinutes min today"
            else "$todayMinutes / $goalMinutes min today",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { (todayMinutes.toFloat() / goalMinutes).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun DailyGoalDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Daily reading goal") },
        text = {
            Column {
                DAILY_GOAL_CHOICES.forEach { minutes ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onPick(minutes) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = minutes == current, onClick = { onPick(minutes) })
                        Text(if (minutes == 0) "No goal" else "$minutes minutes a day")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
