package com.trinotify.app.service

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telephony.TelephonyManager
import com.trinotify.app.data.Action
import com.trinotify.app.data.Db
import com.trinotify.app.data.NotifRecord
import com.trinotify.app.data.Prefs
import com.trinotify.app.logic.Decision
import com.trinotify.app.logic.RuleCache
import com.trinotify.app.logic.RuleEngine
import com.trinotify.app.ml.Ml
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Главный перехватчик: видит каждое уведомление и применяет вердикт. */
class NotifierListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var engine: RuleEngine
    private val lastContent = ConcurrentHashMap<String, String>()
    private val labelCache = ConcurrentHashMap<String, String>()
    // Ключи уведомлений, которые снузили мы сами: только их разрешено «размораживать».
    private val ourSnoozes = ConcurrentHashMap.newKeySet<String>()
    // Отложенные удаления из lastContent: перепост того же содержимого в течение
    // 20 секунд после отмены — служебный цикл приложения, а не новое событие.
    private val pendingForgets = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    companion object {
        // Снятие/установка снуза на ~10 лет — способ «закрыть» несмахиваемое уведомление.
        private const val SNOOZE_LONG_MS = 10L * 365 * 24 * 60 * 60 * 1000
        // Только телефонная инфраструктура: гашение её уведомлений ломает звонки.
        // Прочую систему (android, systemui) пользователь вправе блокировать.
        private val PROTECTED_PKGS = setOf(
            "com.android.server.telecom", "com.android.phone",
        )
        @Volatile var connected = false
            private set
        @Volatile var instance: NotifierListener? = null
            private set

        /** Просит сервис заново пройтись по активным уведомлениям (после смены правил). */
        fun requestSweep() {
            instance?.sweepActive()
        }
    }

    override fun onCreate() {
        super.onCreate()
        engine = RuleEngine(this)
    }

    /**
     * MIUI при DND глушит системный рингтон, игнорируя политику «звонки от всех».
     * Поэтому в режиме тишины звоним сами: заблокированные фильтром номера
     * до состояния RINGING не доходят — звенит только разрешённое.
     */
    private val callStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val prefs = Prefs.get(ctx)
            when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                TelephonyManager.EXTRA_STATE_RINGING ->
                    if (prefs.enabled && prefs.dndMode && prefs.ringInDnd && Dnd.isActive(ctx)) {
                        Sounder.startRinging(ctx)
                    }
                else -> Sounder.stopRinging(ctx)
            }
        }
    }
    private var receiverRegistered = false

    /**
     * Тик системных часов (раз в минуту) — перерисовываем статус, чтобы вход в
     * ночное окно и выход из него отражались в шторке без задержки.
     * Sounder сам пропускает перепубликацию, если текст не изменился.
     */
    private val timeTickReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (Prefs.get(ctx).enabled) Sounder.showStatus(ctx)
        }
    }
    private var tickRegistered = false

    override fun onListenerConnected() {
        connected = true
        instance = this
        Dnd.apply(this)
        updateStatusNote()
        if (!receiverRegistered) {
            val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(callStateReceiver, filter, RECEIVER_EXPORTED)
            } else {
                registerReceiver(callStateReceiver, filter)
            }
            receiverRegistered = true
        }
        if (!tickRegistered) {
            val tickFilter = IntentFilter(Intent.ACTION_TIME_TICK).apply {
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(timeTickReceiver, tickFilter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(timeTickReceiver, tickFilter)
            }
            tickRegistered = true
        }
        scope.launch {
            val db = Db.get(this@NotifierListener)
            Ml.ensureLoaded(db)
            val days = Prefs.get(this@NotifierListener).autoPruneDays
            if (days > 0) {
                try {
                    db.notifDao().pruneUnlabeled(System.currentTimeMillis() - days * 24L * 60 * 60 * 1000)
                } catch (_: Exception) {
                }
            }
        }
        sweepActive()
    }

    override fun onListenerDisconnected() {
        connected = false
        instance = null
        if (receiverRegistered) {
            try { unregisterReceiver(callStateReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        if (tickRegistered) {
            try { unregisterReceiver(timeTickReceiver) } catch (_: Exception) {}
            tickRegistered = false
        }
        Sounder.stopRinging(this)
    }

    /** Показывает или убирает постоянное уведомление «сервис активен». */
    fun updateStatusNote() {
        if (Prefs.get(this).enabled) Sounder.showStatus(this)
        else Sounder.hideStatus(this)
    }

    /**
     * Применяет правила к уже висящим уведомлениям: закрывает те, что попали под
     * «Блокировать» (в т.ч. несмахиваемые демоны других приложений). В архив не пишет.
     */
    fun sweepActive() {
        if (!Prefs.get(this).enabled) return
        scope.launch {
            val active = try { activeNotifications } catch (_: Exception) { null } ?: return@launch
            for (sbn in active) {
                if (sbn.packageName == packageName || isProtected(sbn)) continue
                // Медиаплеер глушится только явным правилом приложения.
                if (isMediaPlayback(sbn) && !explicitBlockRule(sbn.packageName)) continue
                if (decideFor(sbn).action == Action.BLOCK) {
                    try {
                        if (sbn.isClearable) cancelNotification(sbn.key)
                        else {
                            snoozeNotification(sbn.key, SNOOZE_LONG_MS)
                            ourSnoozes.add(sbn.key)
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            // Обратный ход: возвращаем из снуза ТОЛЬКО то, что снузили мы сами и что
            // больше не должно блокироваться. Пользовательские снузы из шторки не трогаем.
            val snoozed = try { snoozedNotifications } catch (_: Exception) { null } ?: return@launch
            for (sbn in snoozed) {
                if (sbn.packageName == packageName) continue
                if (sbn.key !in ourSnoozes) continue
                val shouldStayBlocked =
                    if (isMediaPlayback(sbn)) explicitBlockRule(sbn.packageName)
                    else !isProtected(sbn) && decideFor(sbn).action == Action.BLOCK
                if (!shouldStayBlocked) {
                    try {
                        snoozeNotification(sbn.key, 1_000)
                        ourSnoozes.remove(sbn.key)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private suspend fun decideFor(sbn: StatusBarNotification): Decision {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString().orEmpty()
        return engine.decide(sbn.packageName, title, text)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Уведомления, которые нельзя блокировать: входящие/активные звонки
     * (их судьбу решают правила звонков, а гашение уведомления ломает звонок)
     * и пакеты телефонии.
     */
    private fun isProtected(sbn: StatusBarNotification): Boolean {
        if (sbn.notification.category == Notification.CATEGORY_CALL) return true
        return sbn.packageName in PROTECTED_PKGS
    }

    /**
     * Медиаплеер (музыка, голосовые, видео): категория transport или токен
     * MediaSession. Это транспортные контролы, а не сообщения — их не озвучиваем,
     * не архивируем и не глушим действием по умолчанию/ML. Работает только
     * явное правило «Блокировать» для приложения.
     */
    private fun isMediaPlayback(sbn: StatusBarNotification): Boolean {
        if (sbn.notification.category == Notification.CATEGORY_TRANSPORT) return true
        return sbn.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
    }

    private suspend fun explicitBlockRule(pkg: String): Boolean =
        RuleCache.appRule(this, pkg)?.action == Action.BLOCK

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val prefs = Prefs.get(this)
        if (!prefs.enabled) return
        if (sbn.packageName == packageName) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString().orEmpty()
        val pkg = sbn.packageName
        val key = sbn.key
        val clearable = sbn.isClearable
        val isSummary = (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        // Приложение просит не тревожить повторно при обновлениях (прогресс, статусы) —
        // система звук не повторяет, и мы не должны.
        val onlyAlertOnce = (sbn.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0
        pendingForgets.remove(key)?.cancel()

        val protected = isProtected(sbn)

        if (!protected && isMediaPlayback(sbn)) {
            scope.launch {
                try {
                    if (explicitBlockRule(pkg)) {
                        try {
                            if (clearable) cancelNotification(key)
                            else {
                                snoozeNotification(key, SNOOZE_LONG_MS)
                                ourSnoozes.add(key)
                            }
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: Exception) {
                }
            }
            return
        }

        scope.launch {
            // Вся работа обёрнута: при зашифрованной БД до первой разблокировки Keystore
            // закрыт и Room бросает — без catch падал бы весь процесс слушателя в петле.
            try {
                val base =
                    if (protected) Decision(Action.ALLOW, "Звонок/система — не блокируется")
                    else engine.decide(pkg, title, text)

                // Архив/звук: пропускаем повторные апдейты того же содержимого, тихие
                // обновления (only-alert-once) и пустые сводки групп.
                val content = "$pkg|$title|$text"
                val prev = lastContent.put(key, content)
                val isDuplicate = prev == content
                val silentUpdate = onlyAlertOnce && prev != null
                val skipArchive = isDuplicate || silentUpdate ||
                    (isSummary && title.isBlank() && text.isBlank())

                // Таймаут только на новое событие: иначе «Блокировать» сносит
                // апдейт того уведомления, которое только что разрешили.
                var decision = base
                if (!protected && !skipArchive && base.action == Action.ALLOW) {
                    val timeoutMin = prefs.appAlertTimeoutMinutes
                    if (timeoutMin > 0 && Sounder.isAppInTimeout(pkg, timeoutMin)) {
                        val act = prefs.appAlertTimeoutAction
                        decision = Decision(
                            if (act == Action.BLOCK) Action.BLOCK else Action.SILENCE,
                            "Таймаут приложения",
                        )
                    }
                }

                if (decision.action == Action.BLOCK) {
                    try {
                        if (clearable) cancelNotification(key)
                        else {
                            snoozeNotification(key, SNOOZE_LONG_MS)
                            ourSnoozes.add(key)
                        }
                    } catch (_: Exception) {
                    }
                }

                if (!skipArchive) {
                    try {
                        Db.get(this@NotifierListener).notifDao().insert(
                            NotifRecord(
                                pkg = pkg,
                                appLabel = appLabel(pkg),
                                title = title,
                                text = text,
                                postedAt = System.currentTimeMillis(),
                                verdict = decision.action,
                                verdictSource = decision.source,
                            )
                        )
                    } catch (_: Exception) {
                    }

                    if (decision.action == Action.ALLOW && !protected &&
                        prefs.dndMode && Dnd.isActive(this@NotifierListener)
                    ) {
                        Sounder.notifyAllowed(
                            this@NotifierListener, pkg, appLabel(pkg), title, text,
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val key = sbn.key
        ourSnoozes.remove(key)
        // Не забываем содержимое сразу: цикл «отмена + перепост того же» не должен
        // звучать повторно. Настоящее новое сообщение придёт позже 20 секунд.
        pendingForgets.remove(key)?.cancel()
        pendingForgets[key] = scope.launch {
            kotlinx.coroutines.delay(20_000)
            lastContent.remove(key)
            pendingForgets.remove(key)
        }
    }

    private fun appLabel(pkg: String): String = labelCache.getOrPut(pkg) {
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
    }
}
