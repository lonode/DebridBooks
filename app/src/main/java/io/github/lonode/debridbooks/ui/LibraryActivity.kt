package io.github.lonode.debridbooks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lonode.debridbooks.data.ListeningEntry
import io.github.lonode.debridbooks.playback.listeningSummary
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ListeningOverview(entries: List<ListeningEntry>, books: List<ShelfBook>, goal: Int, onGoal: (Int) -> Unit) {
    val today = LocalDate.now()
    val daily = remember(entries) { entries.groupBy { it.day }.mapValues { it.value.sumOf { row -> row.milliseconds } } }
    val summary = remember(daily,today) { listeningSummary(daily,today) }
    var editGoal by remember { mutableStateOf(false) }
    val perBook = books.map { book -> book to entries.filter { it.accountId==book.book.accountId && it.itemId==book.book.itemId }.sumOf { it.milliseconds } }
        .filter { it.second>0 }.sortedByDescending { it.second }
    LazyColumn(verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item {
            Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.secondaryContainer) {
                Column(Modifier.fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("TIME WELL SPENT",style=MaterialTheme.typography.labelSmall)
                    Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text("${summary.today/60000}",fontFamily=FontFamily.Serif,fontSize=52.sp,lineHeight=56.sp)
                        Text("minutes today",Modifier.padding(bottom=7.dp),style=MaterialTheme.typography.bodyMedium)
                    }
                    if(goal>0) {
                        LinearProgressIndicator(progress={ (summary.today/(goal*60000f)).coerceIn(0f,1f) },modifier=Modifier.fillMaxWidth())
                        Text(if(summary.today>=goal*60000L) "Daily goal reached" else "${goal-summary.today/60000} minutes to your daily goal",style=MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick={editGoal=true},contentPadding=PaddingValues(0.dp)) { Text(if(goal>0) "Daily goal · $goal min" else "Set a daily listening goal") }
                }
            }
        }
        item {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("${summary.streak}" to "day streak", "${summary.best}" to "best streak",
                    "${entries.sumOf { it.milliseconds }/60000}" to "total minutes").forEach { (value,label) ->
                    Surface(Modifier.weight(1f),shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text(value,fontFamily=FontFamily.Serif,fontSize=28.sp);Text(label,style=MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
                ShelfHeading("This week", "${summary.week/60000} minutes this week · ${summary.month/60000} in the last 30 days")
                val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
                val max = days.maxOf { daily[it.toString()] ?: 0L }.coerceAtLeast(60000)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.Bottom) {
                    days.forEach { day ->
                        val ms=daily[day.toString()] ?: 0L
                        Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text("${ms/60000}",style=MaterialTheme.typography.labelSmall)
                            Box(Modifier.height(76.dp).fillMaxWidth(),contentAlignment=Alignment.BottomCenter) {
                                Box(Modifier.width(20.dp).height((4+72f*ms/max).dp).background(
                                    if(day==today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha=.35f),RoundedCornerShape(8.dp)))
                            }
                            Text(day.dayOfWeek.getDisplayName(TextStyle.NARROW,Locale.getDefault()),style=MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        item { ShelfHeading("Stories you've spent time with") }
        if(perBook.isEmpty()) item { Text("Your listening time will appear here as you enjoy your books.",style=MaterialTheme.typography.bodyMedium) }
        items(perBook,key={it.first.book.accountId+it.first.book.itemId}) { (book,ms) ->
            Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(book.book.title,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
                Text("${ms/60000} min",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
            }
        }
    }
    if(editGoal) AlertDialog(onDismissRequest={editGoal=false},title={Text("A little time each day")},text={
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf(0,15,30,45,60,90).forEach { minutes -> FilterChip(goal==minutes,{onGoal(minutes)},label={Text(if(minutes==0) "No goal" else "$minutes min")}) }
        }
    },confirmButton={TextButton(onClick={editGoal=false}){Text("Done")}})
}
