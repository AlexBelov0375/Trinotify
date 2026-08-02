package com.trinotify.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import com.trinotify.app.data.CallRule
import com.trinotify.app.data.Db
import com.trinotify.app.data.Prefs
import com.trinotify.app.data.SenderRule
import kotlinx.coroutines.launch

@Composable
fun RulesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val db = remember { Db.get(context) }
    val prefs = remember { Prefs.get(context) }
    val scope = rememberCoroutineScope()

    val senderRules by db.ruleDao().senderRules().collectAsState(initial = emptyList())
    val callRules by db.ruleDao().callRules().collectAsState(initial = emptyList())

    var showSenderDialog by remember { mutableStateOf(false) }
    var showCallDialog by remember { mutableStateOf(false) }
    var callMode by remember { mutableStateOf(prefs.callMode) }

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            Text(
                "Правила отправителей",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            Text(
                "Срабатывают раньше правил приложений. Ищут подстроку в заголовке " +
                        "(обычно это имя отправителя) или в тексте.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        items(senderRules, key = { "s${it.id}" }) { r ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("«${r.pattern}»", fontWeight = FontWeight.SemiBold)
                    Text(
                        (r.pkg ?: "любое приложение") +
                                (if (r.inText) " · заголовок и текст" else " · только заголовок") +
                                " · срабатываний: ${r.hits}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                ActionChip(r.action)
                IconButton(onClick = { scope.launch { db.ruleDao().deleteSenderRule(r.id) } }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Удалить")
                }
            }
            Divider()
        }
        item {
            OutlinedButton(onClick = { showSenderDialog = true }, modifier = Modifier.padding(vertical = 8.dp)) {
                Text("Добавить правило отправителя")
            }
        }

        item {
            Text(
                "Звонки",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = callMode == "ALL", onClick = {
                        callMode = "ALL"; prefs.callMode = "ALL"
                    })
                    Text("Пропускать все, кроме чёрного списка")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = callMode == "WHITELIST", onClick = {
                        callMode = "WHITELIST"; prefs.callMode = "WHITELIST"
                    })
                    Text("Блокировать все, кроме белого списка")
                }
            }
        }
        items(callRules, key = { "c${it.id}" }) { r ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(r.pattern, fontWeight = FontWeight.SemiBold)
                    Text("срабатываний: ${r.hits}", style = MaterialTheme.typography.labelSmall)
                }
                ActionChip(r.action)
                IconButton(onClick = { scope.launch { db.ruleDao().deleteCallRule(r.id) } }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Удалить")
                }
            }
            Divider()
        }
        item {
            OutlinedButton(onClick = { showCallDialog = true }, modifier = Modifier.padding(vertical = 8.dp)) {
                Text("Добавить номер")
            }
            Text(
                "Номер в любом формате и любой страны (+7 900..., 8 900..., +998 90...) — " +
                        "сравниваются последние цифры. Короткая подстрока (8800) или «*» — любые цифры (+7900*).",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }

    if (showSenderDialog) {
        var pattern by remember { mutableStateOf("") }
        var pkg by remember { mutableStateOf("") }
        var inText by remember { mutableStateOf(false) }
        var action by remember { mutableStateOf(Action.BLOCK) }
        AlertDialog(
            onDismissRequest = { showSenderDialog = false },
            title = { Text("Правило отправителя") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = pattern, onValueChange = { pattern = it },
                        label = { Text("Текст для поиска (имя отправителя)") }, singleLine = true)
                    OutlinedTextField(value = pkg, onValueChange = { pkg = it },
                        label = { Text("Пакет приложения (пусто = любое)") }, singleLine = true)
                    if (pkg.isBlank()) {
                        Text(
                            "⚠ Без указания приложения правило сработает на любое приложение, " +
                                    "поставившее уведомление с таким текстом. Любое приложение может " +
                                    "подделать имя отправителя. Укажите пакет — надёжнее.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = inText, onCheckedChange = { inText = it })
                        Text("Искать и в тексте сообщения")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(Action.ALLOW, Action.SILENCE, Action.BLOCK).forEach { a ->
                            FilterChip(selected = action == a, onClick = { action = a },
                                label = { Text(actionName(a)) })
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = pattern.isNotBlank(),
                    onClick = {
                        scope.launch {
                            db.ruleDao().insertSenderRule(
                                SenderRule(pkg = pkg.trim().ifBlank { null }, pattern = pattern.trim(),
                                    inText = inText, action = action)
                            )
                            com.trinotify.app.service.NotifierListener.requestSweep()
                        }
                        showSenderDialog = false
                    },
                ) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { showSenderDialog = false }) { Text("Отмена") } },
        )
    }

    if (showCallDialog) {
        var pattern by remember { mutableStateOf("") }
        var action by remember { mutableStateOf(if (callMode == "WHITELIST") Action.ALLOW else Action.BLOCK) }
        AlertDialog(
            onDismissRequest = { showCallDialog = false },
            title = { Text("Правило для номера") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = pattern, onValueChange = { pattern = it },
                        label = { Text("Номер или шаблон (+7900*)") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(Action.ALLOW, Action.BLOCK).forEach { a ->
                            FilterChip(selected = action == a, onClick = { action = a },
                                label = { Text(if (a == Action.ALLOW) "Пропускать" else "Блокировать") })
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = pattern.isNotBlank(),
                    onClick = {
                        scope.launch { db.ruleDao().insertCallRule(CallRule(pattern = pattern.trim(), action = action)) }
                        showCallDialog = false
                    },
                ) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { showCallDialog = false }) { Text("Отмена") } },
        )
    }
}
