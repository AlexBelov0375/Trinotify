package com.trinotify.app.ui

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.trinotify.app.data.AppRule
import com.trinotify.app.data.Db
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class AppEntry(val pkg: String, val label: String, val system: Boolean)

/** Список приложений почти не меняется — грузим один раз за процесс. */
private object AppListCache {
    @Volatile var apps: List<AppEntry>? = null
}

@Composable
fun AppsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val db = remember { Db.get(context) }
    val scope = rememberCoroutineScope()
    val rules by db.ruleDao().appRules().collectAsState(initial = emptyList())
    val ruleMap = rules.associateBy { it.pkg }

    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        apps = AppListCache.apps ?: withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.getInstalledApplications(0)
                .filter { it.packageName != context.packageName }
                .map {
                    AppEntry(
                        it.packageName,
                        pm.getApplicationLabel(it).toString(),
                        (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    )
                }
                .sortedBy { it.label.lowercase() }
        }.also { AppListCache.apps = it }
    }

    // Приложения с правилами и обычные пользовательские — сверху, системные без правил — ниже.
    val visible = apps.filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
        .sortedWith(
            compareByDescending<AppEntry> { ruleMap.containsKey(it.pkg) }
                .thenBy { it.system }
                .thenBy { it.label.lowercase() }
        )

    Column(modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Поиск приложения") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            singleLine = true,
        )
        Text(
            "Нажмите на приложение, чтобы выбрать действие для его уведомлений",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(visible, key = { it.pkg }) { app ->
                val action = ruleMap[app.pkg]?.action ?: Action.DEFAULT
                Box {
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { menuFor = app.pkg }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(app.pkg, context, 36)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(app.pkg, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                        ActionChip(action)
                    }
                    DropdownMenu(expanded = menuFor == app.pkg, onDismissRequest = { menuFor = null }) {
                        listOf(Action.DEFAULT, Action.ALLOW, Action.SILENCE, Action.BLOCK, Action.ML).forEach { a ->
                            DropdownMenuItem(
                                text = { Text(actionName(a)) },
                                onClick = {
                                    menuFor = null
                                    scope.launch {
                                        if (a == Action.DEFAULT) db.ruleDao().deleteAppRule(app.pkg)
                                        else db.ruleDao().upsertAppRule(AppRule(app.pkg, app.label, a))
                                        com.trinotify.app.service.NotifierListener.requestSweep()
                                    }
                                },
                            )
                        }
                    }
                }
                Divider()
            }
        }
    }
}
