package com.trinotify.app.ui

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.trinotify.app.data.Action
import com.trinotify.app.data.Db
import com.trinotify.app.data.Prefs
import com.trinotify.app.logic.DataIO
import com.trinotify.app.logic.Modes
import com.trinotify.app.ml.Ml
import com.trinotify.app.service.Dnd
import com.trinotify.app.service.NotifierListener
import com.trinotify.app.service.Sounder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

// ---------- Каркас: корень с категориями и подэкраны ----------

private enum class Section(val title: String, val subtitle: String, val icon: ImageVector) {
    GENERAL("Основное", "Режим тишины, действие по умолчанию, быстрый режим", Icons.Outlined.Tune),
    SOUND("Звук и вибрация", "Мелодии, громкость, способ озвучки", Icons.Outlined.NotificationsActive),
    NIGHT("Ночной режим", "Автотишина по расписанию", Icons.Outlined.DarkMode),
    ML("Машинное обучение", "Классификатор и обучение", Icons.Outlined.Psychology),
    DATA("Данные и приватность", "Архив, бэкап, шифрование, защита входа", Icons.Outlined.Storage),
    PERMS("Разрешения", "Доступы, которые нужны приложению", Icons.Outlined.Security),
}

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    var sectionName by rememberSaveable { mutableStateOf<String?>(null) }
    val section = sectionName?.let { runCatching { Section.valueOf(it) }.getOrNull() }
    BackHandler(enabled = section != null) { sectionName = null }

    if (section == null) {
        SettingsRoot(modifier) { sectionName = it.name }
    } else {
        Column(modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { sectionName = null }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад")
                }
                Text(section.title, style = MaterialTheme.typography.titleLarge)
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (section) {
                    Section.GENERAL -> GeneralSection()
                    Section.SOUND -> SoundSection()
                    Section.NIGHT -> NightSection()
                    Section.ML -> MlSection()
                    Section.DATA -> DataSection()
                    Section.PERMS -> PermsSection()
                }
                Spacer(Modifier.padding(bottom = 24.dp))
            }
        }
    }
}

@Composable
private fun SettingsRoot(modifier: Modifier, onOpen: (Section) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs.get(context) }
    var enabled by remember { mutableStateOf(prefs.enabled) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Настройки",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 14.dp),
        )
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Trinotify", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (enabled) "Фильтрация активна" else "Фильтрация выключена",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it; prefs.enabled = it
                    Dnd.apply(context)
                    NotifierListener.instance?.updateStatusNote()
                })
            }
        }
        Section.entries.forEach { s ->
            Card(
                onClick = { onOpen(s) },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(s.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.title, fontWeight = FontWeight.SemiBold)
                        Text(
                            s.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.padding(bottom = 12.dp))
    }
}

// ---------- Основное ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GeneralSection() {
    val context = LocalContext.current
    val prefs = remember { Prefs.get(context) }
    var dndMode by remember { mutableStateOf(prefs.dndMode) }
    var defaultAction by remember { mutableStateOf(prefs.defaultAction) }
    var quickMode by remember { mutableStateOf(prefs.quickMode) }
    var appAlertTimeout by remember { mutableIntStateOf(prefs.appAlertTimeoutMinutes) }

    SwitchRow("Режим тишины", dndMode) {
        dndMode = it; prefs.dndMode = it; Dnd.apply(context)
    }
    Hint(
        "Включает «Не беспокоить»: чужие уведомления видны, но не звучат; звонки и " +
                "будильники проходят. Разрешённые уведомления озвучивает Trinotify."
    )

    SubTitle("Действие по умолчанию")
    Hint("Применяется, когда нет ни правила, ни уверенного ответа ML.")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(Action.ALLOW, Action.SILENCE, Action.BLOCK).forEach { a ->
            FilterChip(selected = defaultAction == a, onClick = {
                defaultAction = a; prefs.defaultAction = a
            }, label = { Text(actionName(a)) })
        }
    }

    SubTitle("Таймаут от приложения")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Не повторять звук от одного приложения", modifier = Modifier.weight(1f))
        Switch(checked = appAlertTimeout > 0, onCheckedChange = {
            appAlertTimeout = if (it) 5 else 0
            prefs.appAlertTimeoutMinutes = appAlertTimeout
        })
    }
    if (appAlertTimeout > 0) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1, 2, 5, 10, 15, 30).forEach { m ->
                FilterChip(selected = appAlertTimeout == m, onClick = {
                    appAlertTimeout = m; prefs.appAlertTimeoutMinutes = m
                }, label = { Text("$m мин") })
            }
        }
    }
    Hint(
        "Если от приложения приходит пачка уведомлений, звучит только первое. " +
                "Следующее оповещение от него — не раньше чем через выбранный интервал."
    )

    SubTitle("Быстрый режим")
    Hint("Дублируется кнопками в постоянном уведомлении в шторке.")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(Modes.NORMAL, Modes.VIBRATE, Modes.SILENT).forEach { m ->
            FilterChip(selected = quickMode == m, onClick = {
                quickMode = m
                Modes.applyManual(prefs, m)
                Sounder.showStatus(context)
            }, label = { Text(Modes.label(m)) })
        }
    }
    Hint(
        "Обычный — звуки как настроено. Вибро — уведомления и звонки только вибрацией. " +
                "Тихий — полная тишина (уведомления видны, звонки не звенят). " +
                "Выбор во время ночного режима действует до конца ночного окна."
    )
}

// ---------- Звук и вибрация ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SoundSection() {
    val context = LocalContext.current
    val prefs = remember { Prefs.get(context) }
    val scope = rememberCoroutineScope()
    var ringInDnd by remember { mutableStateOf(prefs.ringInDnd) }
    var soundMode by remember { mutableStateOf(prefs.soundMode) }
    var callAlertMode by remember { mutableStateOf(prefs.callAlertMode) }
    var notifAlertMode by remember { mutableStateOf(prefs.notifAlertMode) }
    var callVolume by remember { mutableFloatStateOf(prefs.callVolume) }
    var notifVolume by remember { mutableFloatStateOf(prefs.notifVolume) }
    var callRingName by remember { mutableStateOf(ringName(context, prefs.callRingUri, ring = true)) }
    var notifSoundName by remember { mutableStateOf(ringName(context, prefs.notifSoundUri, ring = false)) }

    val callPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri = res.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            prefs.callRingUri = uri?.toString().orEmpty()
            callRingName = ringName(context, prefs.callRingUri, ring = true)
        }
    }
    val notifPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri = res.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            prefs.notifSoundUri = uri?.toString().orEmpty()
            notifSoundName = ringName(context, prefs.notifSoundUri, ring = false)
        }
    }
    val callFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            scope.launch {
                val dest = copySoundFile(context, it)
                if (dest != null) {
                    prefs.callRingUri = dest
                    callRingName = ringName(context, dest, ring = true)
                } else Toast.makeText(context, "Не удалось прочитать файл", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val notifFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            scope.launch {
                val dest = copySoundFile(context, it)
                if (dest != null) {
                    prefs.notifSoundUri = dest
                    notifSoundName = ringName(context, dest, ring = false)
                } else Toast.makeText(context, "Не удалось прочитать файл", Toast.LENGTH_SHORT).show()
            }
        }
    }

    SwitchRow("Рингтон Trinotify при входящем звонке", ringInDnd) {
        ringInDnd = it; prefs.ringInDnd = it
    }
    Hint(
        "MIUI в режиме тишины глушит системный рингтон даже для разрешённых звонков, " +
                "поэтому Trinotify играет мелодию сам через поток будильника."
    )

    SubTitle("Входящий звонок")
    AlertModeChips(callAlertMode) { callAlertMode = it; prefs.callAlertMode = it }
    Hint("Мелодия: $callRingName")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            callPicker.launch(ringPickerIntent(RingtoneManager.TYPE_RINGTONE, prefs.callRingUri, "Мелодия звонка"))
        }) { Text("Системная") }
        OutlinedButton(onClick = { callFilePicker.launch(arrayOf("audio/*")) }) { Text("Свой файл") }
        OutlinedButton(onClick = {
            prefs.callRingUri = ""; callRingName = ringName(context, "", ring = true)
        }) { Text("Встроенная") }
        OutlinedButton(onClick = {
            if (!Sounder.testCall(context)) {
                Toast.makeText(context, "Не удалось проиграть мелодию", Toast.LENGTH_SHORT).show()
            }
        }) { Text("Проверить") }
    }
    Text("Громкость звонка: ${(callVolume * 100).toInt()}%")
    Slider(
        value = callVolume,
        onValueChange = { callVolume = it },
        onValueChangeFinished = { prefs.callVolume = callVolume },
        valueRange = 0.1f..1f,
    )

    SubTitle("Разрешённые уведомления")
    AlertModeChips(notifAlertMode) { notifAlertMode = it; prefs.notifAlertMode = it }
    Hint("Звук: $notifSoundName")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            notifPicker.launch(ringPickerIntent(RingtoneManager.TYPE_NOTIFICATION, prefs.notifSoundUri, "Звук уведомления"))
        }) { Text("Системный") }
        OutlinedButton(onClick = { notifFilePicker.launch(arrayOf("audio/*")) }) { Text("Свой файл") }
        OutlinedButton(onClick = {
            prefs.notifSoundUri = ""; notifSoundName = ringName(context, "", ring = false)
        }) { Text("Встроенный") }
        OutlinedButton(onClick = {
            if (!Sounder.testNotif(context)) {
                Toast.makeText(context, "Не удалось проиграть звук", Toast.LENGTH_SHORT).show()
            }
        }) { Text("Проверить") }
    }
    Text("Громкость уведомлений: ${(notifVolume * 100).toInt()}%")
    Slider(
        value = notifVolume,
        onValueChange = { notifVolume = it },
        onValueChangeFinished = { prefs.notifVolume = notifVolume },
        valueRange = 0.1f..1f,
    )

    SubTitle("Способ озвучки уведомлений")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = soundMode == "SOUND", onClick = {
            soundMode = "SOUND"; prefs.soundMode = "SOUND"
        }, label = { Text("Звук напрямую") })
        FilterChip(selected = soundMode == "MIRROR", onClick = {
            soundMode = "MIRROR"; prefs.soundMode = "MIRROR"; Sounder.recreateChannel(context)
        }, label = { Text("Зеркальное уведомление") })
    }
    OutlinedButton(onClick = { Sounder.testFullNotification(context) }) {
        Text("Тестовое уведомление (как настоящее)")
    }
    Hint(
        "Громкость зависит от громкости будильника на телефоне — ползунки задают долю от неё. " +
                "Мелодии из тем MIUI недоступны приложениям: в этом случае играет встроенная."
    )
}

// ---------- Ночной режим ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NightSection() {
    val context = LocalContext.current
    val prefs = remember { Prefs.get(context) }
    var nightEnabled by remember { mutableStateOf(prefs.nightEnabled) }
    var nightStart by remember { mutableIntStateOf(prefs.nightStart) }
    var nightEnd by remember { mutableIntStateOf(prefs.nightEnd) }
    var nightMode by remember { mutableStateOf(prefs.nightMode) }
    var callsWhitelist by remember { mutableStateOf(prefs.nightCallsWhitelistOnly) }
    var pickStart by remember { mutableStateOf(false) }
    var pickEnd by remember { mutableStateOf(false) }

    SwitchRow("Ночной режим по расписанию", nightEnabled) {
        nightEnabled = it; prefs.nightEnabled = it
        Modes.clearOverride(prefs)
        Sounder.showStatus(context)
    }
    Hint(
        "В заданное время автоматически действует выбранный тихий режим. " +
                "Кнопки в шторке всё равно работают: выбранный вручную режим держится " +
                "до конца ночного окна, дальше расписание снова берёт своё."
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { pickStart = true }) { Text("С ${Modes.formatTime(nightStart)}") }
        OutlinedButton(onClick = { pickEnd = true }) { Text("До ${Modes.formatTime(nightEnd)}") }
    }

    SubTitle("Режим ночью")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(Modes.VIBRATE, Modes.SILENT).forEach { m ->
            FilterChip(selected = nightMode == m, onClick = {
                nightMode = m; prefs.nightMode = m
                Modes.clearOverride(prefs); Sounder.showStatus(context)
            }, label = { Text(Modes.label(m)) })
        }
    }

    SwitchRow("Ночью звонки только из белого списка", callsWhitelist) {
        callsWhitelist = it; prefs.nightCallsWhitelistOnly = it
    }
    Hint("Независимо от дневного режима звонков: ночью дозвонятся только номера с правилом «Пропускать».")

    if (pickStart) TimePickDialog(nightStart, onDismiss = { pickStart = false }) {
        nightStart = it; prefs.nightStart = it; pickStart = false
        Modes.clearOverride(prefs); Sounder.showStatus(context)
    }
    if (pickEnd) TimePickDialog(nightEnd, onDismiss = { pickEnd = false }) {
        nightEnd = it; prefs.nightEnd = it; pickEnd = false
        Modes.clearOverride(prefs); Sounder.showStatus(context)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickDialog(initialMinutes: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val state = rememberTimePickerState(
        initialHour = initialMinutes / 60,
        initialMinute = initialMinutes % 60,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("ОК") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        text = { TimePicker(state = state) },
    )
}

// ---------- Машинное обучение ----------

@Composable
private fun MlSection() {
    val context = LocalContext.current
    val prefs = remember { Prefs.get(context) }
    val db = remember { Db.get(context) }
    val scope = rememberCoroutineScope()
    var mlEnabled by remember { mutableStateOf(prefs.mlEnabled) }
    var mlThreshold by remember { mutableFloatStateOf(prefs.mlThreshold) }
    var mlStats by remember { mutableStateOf(Ml.stats()) }
    var training by remember { mutableStateOf(false) }
    val labeledCount by db.notifDao().labeledCount().collectAsState(initial = 0)

    SwitchRow("ML для всех приложений без правил", mlEnabled) {
        mlEnabled = it; prefs.mlEnabled = it
    }
    Hint(
        "Наивный Байес обучается на вашей разметке в архиве (приложение — ключевой " +
                "признак + текст). Отдельным приложениям можно назначить «Решает ML» " +
                "на вкладке Приложения."
    )
    Text("Порог уверенности: ${(mlThreshold * 100).toInt()}%")
    Slider(
        value = mlThreshold,
        onValueChange = { mlThreshold = it },
        onValueChangeFinished = { prefs.mlThreshold = mlThreshold },
        valueRange = 0.5f..0.99f,
    )
    Text("Размечено записей: $labeledCount", style = MaterialTheme.typography.bodySmall)
    Text("Модель: $mlStats", style = MaterialTheme.typography.bodySmall)
    OutlinedButton(
        enabled = !training,
        onClick = {
            scope.launch {
                training = true
                Toast.makeText(context, "Обучение начато…", Toast.LENGTH_SHORT).show()
                val n = withContext(NonCancellable + Dispatchers.IO) { Ml.rebuild(db) }
                mlStats = Ml.stats()
                training = false
                Toast.makeText(
                    context,
                    if (n > 0) "Обучение завершено: $n размеченных записей"
                    else "Нет размеченных записей — разметьте уведомления в архиве",
                    Toast.LENGTH_LONG,
                ).show()
            }
        },
    ) { Text(if (training) "Обучение…" else "Переобучить модель") }
}

// ---------- Данные и приватность ----------

@Composable
private fun DataSection() {
    val context = LocalContext.current
    val prefs = remember { Prefs.get(context) }
    val db = remember { Db.get(context) }
    val scope = rememberCoroutineScope()
    var autoPruneDays by remember { mutableIntStateOf(prefs.autoPruneDays) }
    var encrypted by remember { mutableStateOf(prefs.dbEncrypted) }
    var appLock by remember { mutableStateOf(prefs.appLock) }
    var confirmClearAll by remember { mutableStateOf(false) }
    var confirmClearUnlabeled by remember { mutableStateOf(false) }
    var confirmEncrypt by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }

    val dateSuffix = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            scope.launch {
                val ok = DataIO.exportFullBackup(context, it)
                Toast.makeText(context, if (ok) "Бэкап сохранён" else "Ошибка бэкапа", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            scope.launch {
                val ok = DataIO.exportRulesAndDataset(context, it)
                Toast.makeText(context, if (ok) "Экспорт готов" else "Ошибка экспорта", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            scope.launch {
                val res = withContext(NonCancellable) { DataIO.importAll(context, it) }
                Toast.makeText(
                    context,
                    if (res != null) "Импортировано: правил ${res.rules}, записей ${res.records}"
                    else "Файл не распознан",
                    Toast.LENGTH_LONG,
                ).show()
                NotifierListener.requestSweep()
            }
        }
    }

    SubTitle("Архив")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Авточистка неразмеченных", modifier = Modifier.weight(1f))
        Switch(checked = autoPruneDays > 0, onCheckedChange = {
            autoPruneDays = if (it) 30 else 0; prefs.autoPruneDays = autoPruneDays
        })
    }
    if (autoPruneDays > 0) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(7, 30, 90).forEach { d ->
                FilterChip(selected = autoPruneDays == d, onClick = {
                    autoPruneDays = d; prefs.autoPruneDays = d
                }, label = { Text("$d дней") })
            }
        }
    }
    Hint("Размеченные записи (датасет для ML) не удаляются никогда.")

    OutlinedButton(onClick = { backupLauncher.launch("trinotify-backup-$dateSuffix.json") }) {
        Text("Бэкап архива (всё в JSON)")
    }
    OutlinedButton(onClick = { exportLauncher.launch("trinotify-rules-$dateSuffix.json") }) {
        Text("Экспорт правил и датасета")
    }
    OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) {
        Text("Импорт из файла")
    }
    OutlinedButton(onClick = { confirmClearUnlabeled = true }) { Text("Очистить неразмеченные") }
    OutlinedButton(
        onClick = { confirmClearAll = true },
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error
        ),
    ) { Text("Очистить весь архив") }

    SubTitle("Приватность")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Шифрование архива")
            Text(
                "SQLCipher, ключ в аппаратном Keystore",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = encrypted, enabled = !busy, onCheckedChange = { confirmEncrypt = it })
    }
    Hint(
        "После перезагрузки телефона до первой разблокировки экрана база недоступна — " +
                "в это окно фильтрация работает по действию по умолчанию, без архивации."
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Вход по биометрии / пину")
            Text(
                "Отпечаток, лицо или код блокировки устройства",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = appLock, onCheckedChange = { want ->
            if (want) {
                val can = BiometricManager.from(context).canAuthenticate(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                ) == BiometricManager.BIOMETRIC_SUCCESS
                if (!can) {
                    Toast.makeText(context, "На устройстве нет настроенной блокировки", Toast.LENGTH_LONG).show()
                    return@Switch
                }
            }
            appLock = want; prefs.appLock = want
        })
    }

    if (confirmClearUnlabeled) ConfirmDialog(
        "Удалить все неразмеченные записи?",
        "Датасет (размеченные записи) останется нетронутым.",
        onDismiss = { confirmClearUnlabeled = false },
    ) {
        scope.launch { db.notifDao().clearUnlabeled() }
        confirmClearUnlabeled = false
    }
    if (confirmClearAll) ConfirmDialog(
        "Очистить весь архив?",
        "Будут удалены все записи, включая размеченные. Модель ML при этом сохранится до следующего переобучения.",
        onDismiss = { confirmClearAll = false },
    ) {
        scope.launch { db.notifDao().clearAll() }
        confirmClearAll = false
    }
    confirmEncrypt?.let { enable ->
        ConfirmDialog(
            if (enable) "Включить шифрование?" else "Выключить шифрование?",
            "Данные будут перенесены, затем приложение перезапустится (сервис поднимется автоматически).",
            onDismiss = { confirmEncrypt = null },
        ) {
            confirmEncrypt = null
            busy = true
            scope.launch {
                val ok = withContext(NonCancellable + Dispatchers.IO) {
                    Db.switchEncryption(context, enable)
                }
                if (ok) {
                    Toast.makeText(context, "Готово, перезапуск…", Toast.LENGTH_SHORT).show()
                    delay(800)
                    exitProcess(0)
                } else {
                    busy = false
                    Toast.makeText(context, "Не удалось переключить шифрование", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

@Composable
private fun ConfirmDialog(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { Button(onClick = onConfirm) { Text("Да") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

// ---------- Разрешения ----------

@Composable
private fun PermsSection() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val nlsGranted = remember(refresh) {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
    val dndGranted = remember(refresh) { Dnd.hasAccess(context) }
    val roleManager = context.getSystemService(Context.ROLE_SERVICE) as RoleManager
    val callRoleHeld = remember(refresh) { roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) }
    val batteryIgnored = remember(refresh) {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(context.packageName)
    }

    PermissionRow("Доступ к уведомлениям", nlsGranted) {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
    PermissionRow("Управление «Не беспокоить»", dndGranted) {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    }
    PermissionRow("Фильтрация звонков (роль)", callRoleHeld) {
        context.startActivity(roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
    }
    PermissionRow("Батарея: без ограничений", batteryIgnored) {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        )
    }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Hint(
        "На MIUI также включите «Автозапуск» для Trinotify в настройках приложений — " +
                "иначе система может выгружать сервис."
    )
}

// ---------- Общие элементы ----------

@Composable
private fun SubTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 10.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun AlertModeChips(mode: String, onChange: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("BOTH" to "Звук и вибрация", "SOUND" to "Звук", "VIBRATE" to "Вибрация").forEach { (v, label) ->
            FilterChip(selected = mode == v, onClick = { onChange(v) }, label = { Text(label) })
        }
    }
}

@Composable
private fun PermissionRow(title: String, granted: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(
                if (granted) "выдано" else "не выдано",
                style = MaterialTheme.typography.labelSmall,
                color = if (granted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
            )
        }
        if (!granted) Button(onClick = onClick) { Text("Выдать") }
    }
}

private fun ringName(context: Context, uriStr: String, ring: Boolean): String = when {
    uriStr.isBlank() -> if (ring) "Hotline Miami (встроенная)" else "Sims 3 (встроенный)"
    uriStr.startsWith("file:") -> Uri.parse(uriStr).lastPathSegment ?: "Файл"
    else -> runCatching {
        RingtoneManager.getRingtone(context, Uri.parse(uriStr))?.getTitle(context)
    }.getOrNull() ?: "Выбранная"
}

private fun ringPickerIntent(type: Int, current: String, title: String): Intent =
    Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, type)
        putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, title)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
        if (current.isNotBlank()) {
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(current))
        }
    }

/** Копирует выбранный аудиофайл во внутреннее хранилище — оттуда он читается всегда. */
private suspend fun copySoundFile(context: Context, uri: Uri): String? =
    withContext(Dispatchers.IO) {
        try {
            var name = "custom_sound.mp3"
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx)?.let { name = it }
            }
            name = name.replace(Regex("[^A-Za-z0-9а-яА-ЯёЁ._-]"), "_")
            val dir = File(context.filesDir, "sounds").apply { mkdirs() }
            val dest = File(dir, name)
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            } ?: return@withContext null
            Uri.fromFile(dest).toString()
        } catch (_: Exception) {
            null
        }
    }
