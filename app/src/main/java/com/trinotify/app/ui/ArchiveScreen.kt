package com.trinotify.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trinotify.app.data.Action
import com.trinotify.app.data.Db
import com.trinotify.app.data.NotifRecord
import com.trinotify.app.ml.Ml
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFmt = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArchiveScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val db = remember { Db.get(context) }
    val scope = rememberCoroutineScope()
    val records by db.notifDao().recent().collectAsState(initial = emptyList())
    val appRules by db.ruleDao().appRules().collectAsState(initial = emptyList())
    val ruleMap = appRules.associate { it.pkg to it.action }

    var query by remember { mutableStateOf("") }
    var onlyCalls by remember { mutableStateOf(false) }
    var onlyLabeled by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<NotifRecord?>(null) }

    val filtered = records.filter { r ->
        (!onlyCalls || r.isCall) &&
        (!onlyLabeled || r.userLabel != null) &&
        (query.isBlank() || listOf(r.appLabel, r.title, r.text, r.pkg).any { it.contains(query, true) })
    }

    Column(modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Поиск по архиву") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            singleLine = true,
        )
        Row(
            Modifier.padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = onlyCalls, onClick = { onlyCalls = !onlyCalls }, label = { Text("Звонки") })
            FilterChip(selected = onlyLabeled, onClick = { onlyLabeled = !onlyLabeled }, label = { Text("Размеченные") })
            Text(
                "${filtered.size} записей",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(filtered, key = { it.id }) { r ->
                Column(
                    Modifier.fillMaxWidth()
                        .clickable { selected = r }
                        .padding(vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!r.isCall) AppIcon(r.pkg, context, 28)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${r.appLabel}${if (r.title.isNotBlank()) " · ${r.title}" else ""}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                            if (r.text.isNotBlank()) Text(
                                r.text,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                            )
                            Text(
                                timeFmt.format(Date(r.postedAt)),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            ActionChip(r.verdict)
                            r.userLabel?.let {
                                Spacer(Modifier.padding(1.dp))
                                Text("метка: ${actionName(it)}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Divider(Modifier.padding(top = 6.dp))
                }
            }
        }
    }

    selected?.let { r ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(r.appLabel) },
            text = {
                Column {
                    // Текст уведомления — в ограниченной прокручиваемой области,
                    // чтобы длинные сообщения не выталкивали кнопки за экран.
                    Column(
                        Modifier
                            .heightIn(max = 150.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (r.title.isNotBlank()) Text(r.title, fontWeight = FontWeight.SemiBold)
                        if (r.text.isNotBlank()) Text(r.text)
                    }
                    Spacer(Modifier.padding(4.dp))
                    Text("Пакет: ${r.pkg}", style = MaterialTheme.typography.bodySmall)
                    Text("Время: ${timeFmt.format(Date(r.postedAt))}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Вердикт: ${actionName(r.verdict)} (${r.verdictSource})",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                    )
                    Spacer(Modifier.padding(6.dp))
                    Text("Разметка для обучения ML:", style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        (listOf(Action.ALLOW, Action.SILENCE, Action.BLOCK).map { it to actionName(it) } +
                                (null to "Без метки")).forEach { (a, name) ->
                            FilterChip(
                                selected = r.userLabel == a,
                                onClick = {
                                    scope.launch {
                                        db.notifDao().setLabel(r.id, a)
                                        Ml.rebuildAsync(db)
                                    }
                                    selected = null
                                },
                                label = { Text(name) },
                            )
                        }
                    }
                    if (!r.isCall) {
                        Spacer(Modifier.padding(4.dp))
                        Text("Правило для «${r.appLabel}»:", style = MaterialTheme.typography.labelLarge)
                        val current = ruleMap[r.pkg] ?: Action.DEFAULT
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            listOf(Action.DEFAULT, Action.ALLOW, Action.SILENCE, Action.BLOCK, Action.ML).forEach { a ->
                                FilterChip(
                                    selected = current == a,
                                    onClick = {
                                        scope.launch {
                                            if (a == Action.DEFAULT) db.ruleDao().deleteAppRule(r.pkg)
                                            else db.ruleDao().upsertAppRule(
                                                com.trinotify.app.data.AppRule(r.pkg, r.appLabel, a)
                                            )
                                            com.trinotify.app.service.NotifierListener.requestSweep()
                                        }
                                        selected = null
                                    },
                                    label = { Text(actionName(a)) },
                                )
                            }
                        }
                        if (r.title.isNotBlank()) {
                            Spacer(Modifier.padding(4.dp))
                            Text(
                                "Правило для отправителя «${r.title}» в этом приложении:",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(top = 4.dp),
                            ) {
                                listOf(Action.ALLOW, Action.SILENCE, Action.BLOCK).forEach { a ->
                                    FilterChip(
                                        selected = false,
                                        onClick = {
                                            scope.launch {
                                                // Правило привязано к пакету — защищено от подделки имени.
                                                db.ruleDao().insertSenderRule(
                                                    com.trinotify.app.data.SenderRule(
                                                        pkg = r.pkg, pattern = r.title,
                                                        inText = false, action = a,
                                                    )
                                                )
                                                com.trinotify.app.service.NotifierListener.requestSweep()
                                            }
                                            selected = null
                                        },
                                        label = { Text(actionName(a)) },
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selected = null }) { Text("Закрыть") }
            },
        )
    }
}
