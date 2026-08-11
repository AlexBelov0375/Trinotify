package com.trinotify.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import com.trinotify.app.logic.Modes
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import com.trinotify.app.data.Prefs
import com.trinotify.app.ui.MainActivity

/**
 * Озвучка разрешённых уведомлений, пока действует режим тишины.
 * SOUND — проигрываем звук уведомления через медиапоток (DND его не глушит) + вибрация.
 * MIRROR — публикуем краткое зеркальное уведомление в канале с обходом DND.
 */
object Sounder {
    const val MIRROR_CHANNEL = "trinotify_mirror_v2"
    const val MIRROR_MUTED_CHANNEL = "trinotify_mirror_muted_v1"
    const val STATUS_CHANNEL = "trinotify_status_v1"
    const val STATUS_ID = 1
    private val handler = Handler(Looper.getMainLooper())
    private var mirrorId = 7000
    // Последний показанный текст статуса — чтобы не перепубликовывать одно и то же.
    @Volatile private var lastStatusText: String? = null

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(STATUS_CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    STATUS_CHANNEL, "Статус Trinotify", NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = "Постоянное уведомление о том, что перехват активен"
                    setShowBadge(false)
                }
            )
        }
        nm.deleteNotificationChannel("trinotify_mirror_v1")
        if (nm.getNotificationChannel(MIRROR_MUTED_CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    MIRROR_MUTED_CHANNEL, "Разрешённые (без звука)", NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Зеркала уведомлений из пачки — видны, но без повторного звука"
                    setBypassDnd(true)
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
        if (nm.getNotificationChannel(MIRROR_CHANNEL) != null) return
        val ch = NotificationChannel(
            MIRROR_CHANNEL, "Разрешённые уведомления", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Озвучка уведомлений, пропущенных Trinotify"
            setBypassDnd(true)
            enableVibration(true)
            setSound(
                builtinUri(context, ring = false),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
        nm.createNotificationChannel(ch)
    }

    /** Постоянное уведомление «сервис активен» с кнопками быстрых режимов. */
    fun showStatus(context: Context) {
        ensureChannel(context)
        val prefs = Prefs.get(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val effective = Modes.effective(prefs)
        val night = Modes.isNightNow(prefs)
        val manual = night && Modes.overrideActive(prefs)
        val text = buildString {
            append("Режим: ${Modes.label(effective)}")
            when {
                manual -> append(" · вручную до ${Modes.formatTime(prefs.nightEnd)}")
                night -> append(" · ночь до ${Modes.formatTime(prefs.nightEnd)}")
                prefs.nightEnabled -> append(" · ночь с ${Modes.formatTime(prefs.nightStart)}")
            }
        }
        // Ничего не изменилось — не дёргаем систему (статус проверяется раз в минуту).
        if (text == lastStatusText) return
        lastStatusText = text
        val builder = Notification.Builder(context, STATUS_CHANNEL)
            .setSmallIcon(com.trinotify.app.R.drawable.ic_stat_bell)
            .setContentTitle("Trinotify активен")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setShowWhen(false)
        listOf(Modes.NORMAL, Modes.VIBRATE, Modes.SILENT).forEachIndexed { i, mode ->
            val label = (if (mode == effective) "● " else "") + Modes.label(mode)
            // Отдельный action и requestCode на режим — исключает коллизии PendingIntent.
            val actionPi = PendingIntent.getBroadcast(
                context, 100 + i,
                Intent(context, QuickModeReceiver::class.java)
                    .setAction(QuickModeReceiver.ACTION_PREFIX + mode)
                    .setPackage(context.packageName)
                    .putExtra("mode", mode),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(Notification.Action.Builder(null, label, actionPi).build())
        }
        nm.notify(STATUS_ID, builder.build())
    }

    fun hideStatus(context: Context) {
        lastStatusText = null
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(STATUS_ID)
    }

    /** Пересоздаёт канал: флаг bypassDnd применяется только при наличии доступа к DND. */
    fun recreateChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.deleteNotificationChannel(MIRROR_CHANNEL)
        ensureChannel(context)
    }

    // Пишется из фонового потока, читается из колбэков на главном — только @Volatile.
    @Volatile private var player: MediaPlayer? = null
    private var ringVibrator: Vibrator? = null
    @Volatile private var ringing = false
    private val stopRingRunnable = Runnable { stopRingingInternal() }

    /** Встроенные звуки по умолчанию (res/raw). */
    fun builtinUri(context: Context, ring: Boolean): Uri = Uri.parse(
        "android.resource://${context.packageName}/raw/" +
                if (ring) "hotline_miami_ii_wrong_number_blizzard" else "sims_3_opportunity"
    )

    /**
     * Кандидаты на воспроизведение: выбранный звук → встроенный → системный по
     * умолчанию → первые стандартные из списка. Фолбэк обязателен: мелодии тем MIUI
     * лежат в /data/system/theme_magic и не читаются другими приложениями (SELinux).
     */
    private fun candidates(context: Context, prefUri: String, ring: Boolean): List<Uri> {
        val type = if (ring) RingtoneManager.TYPE_RINGTONE else RingtoneManager.TYPE_NOTIFICATION
        val out = mutableListOf<Uri>()
        if (prefUri.isNotBlank()) runCatching { out.add(Uri.parse(prefUri)) }
        out.add(builtinUri(context, ring))
        runCatching { RingtoneManager.getActualDefaultRingtoneUri(context, type)?.let { out.add(it) } }
        runCatching {
            val rm = RingtoneManager(context)
            rm.setType(type)
            val cursor = rm.cursor
            var added = 0
            while (cursor.moveToNext() && added < 3) {
                runCatching { out.add(rm.getRingtoneUri(cursor.position)); added++ }
            }
        }
        return out
    }

    private var focusRequest: AudioFocusRequest? = null
    private var focusContext: Context? = null
    private var playbackGen = 0L
    // Пустой слушатель: системе нужен явный получатель изменений фокуса.
    private val focusListener = AudioManager.OnAudioFocusChangeListener { }
    // Страховка: фокус обязан вернуться чужому плееру, даже если трек не доиграл.
    private val releaseFocusRunnable = Runnable { abandonFocus() }

    @Synchronized
    private fun requestFocus(context: Context, attrs: AudioAttributes, exclusive: Boolean) {
        abandonFocus()
        try {
            val appCtx = context.applicationContext
            val am = appCtx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val req = AudioFocusRequest.Builder(
                if (exclusive) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusListener, handler)
                .setWillPauseWhenDucked(false)
                .build()
            // Сохраняем ДО запроса: иначе при исключении внутри запроса фокус
            // остался бы захваченным, а отпустить его было бы нечем.
            focusContext = appCtx
            focusRequest = req
            am.requestAudioFocus(req)
        } catch (_: Exception) {
        }
    }

    @Synchronized
    private fun abandonFocus() {
        handler.removeCallbacks(releaseFocusRunnable)
        val req = focusRequest ?: return
        focusRequest = null
        try {
            val am = focusContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.abandonAudioFocusRequest(req)
        } catch (_: Exception) {
        }
    }

    /** Играет первый доступный uri; MediaPlayer честно кидает ошибку на нечитаемых файлах. */
    @Synchronized
    private fun startPlayer(context: Context, uris: List<Uri>, loop: Boolean, volume: Float, usage: Int): Boolean {
        stopPlayer()
        val gen = ++playbackGen
        val attrs = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        for (uri in uris) {
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(attrs)
                mp.setDataSource(context, uri)
                mp.isLooping = loop
                mp.setVolume(volume, volume)
                mp.prepare()
                mp.start()
                player = mp
                if (!loop) {
                    mp.setOnCompletionListener { finished ->
                        runCatching { finished.release() }
                        onPlaybackFinished(gen, finished)
                    }
                    mp.setOnErrorListener { failed, _, _ ->
                        runCatching { failed.release() }
                        onPlaybackFinished(gen, failed)
                        true
                    }
                }
                // Звонок получает фокус целиком, уведомление — приглушает чужую музыку.
                requestFocus(context, attrs, exclusive = loop)
                if (!loop) {
                    val durationMs = runCatching { mp.duration }.getOrDefault(-1)
                    val guardMs = if (durationMs in 1..60_000) durationMs + 1_000L else 10_000L
                    handler.postDelayed(releaseFocusRunnable, guardMs)
                }
                return true
            } catch (_: Exception) {
                runCatching { mp.release() }
            }
        }
        // Ни один источник не заиграл — фокус захватывать не за чем.
        abandonFocus()
        return false
    }

    /** Трек доиграл (или упал): отпускаем фокус, если за это время не начался новый. */
    @Synchronized
    private fun onPlaybackFinished(gen: Long, mp: MediaPlayer) {
        if (gen != playbackGen) return // играет что-то новее — фокус принадлежит ему
        if (player === mp) player = null
        abandonFocus()
    }

    @Synchronized
    private fun stopPlayer() {
        playbackGen++
        player?.let { p ->
            runCatching { p.stop() }
            runCatching { p.release() }
        }
        player = null
        abandonFocus()
    }

    /**
     * Собственный звонок в режиме тишины: MIUI при DND глушит системный рингтон
     * независимо от политики. Звук — на потоке будильника (его DND не трогает).
     */
    fun startRinging(context: Context) {
        stopRingingInternal()
        val prefs = Prefs.get(context)
        val quick = Modes.effective(prefs)
        if (quick == Modes.SILENT) return
        ringing = true
        val mode = prefs.callAlertMode
        val soundOn = quick == Modes.NORMAL && mode != "VIBRATE"
        val vibeOn = quick == Modes.VIBRATE || mode != "SOUND"
        if (soundOn) {
            startPlayer(
                context, candidates(context, prefs.callRingUri, ring = true),
                loop = true, volume = prefs.callVolume, usage = AudioAttributes.USAGE_ALARM,
            )
        }
        if (vibeOn) {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            runCatching {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 800), 0))
            }
            ringVibrator = vibrator
        }
        // Страховка: не звенеть дольше минуты, что бы ни случилось.
        handler.postDelayed(stopRingRunnable, 60_000)
    }

    fun stopRinging(@Suppress("UNUSED_PARAMETER") context: Context) = stopRingingInternal()

    private fun stopRingingInternal() {
        ringing = false
        handler.removeCallbacks(stopRingRunnable)
        stopPlayer()
        runCatching { ringVibrator?.cancel() }
        ringVibrator = null
    }

    private val stopTestRunnable = Runnable { stopPlayer() }

    /** Проверка звука из настроек. Повторное нажатие останавливает; автостоп через 15 с. */
    fun testCall(context: Context): Boolean {
        if (stopIfPlaying()) return true
        val prefs = Prefs.get(context)
        val ok = startPlayer(
            context, candidates(context, prefs.callRingUri, ring = true),
            loop = false, volume = prefs.callVolume, usage = AudioAttributes.USAGE_ALARM,
        )
        if (ok) handler.postDelayed(stopTestRunnable, 15_000)
        return ok
    }

    fun testNotif(context: Context): Boolean {
        if (stopIfPlaying()) return true
        val prefs = Prefs.get(context)
        val ok = startPlayer(
            context, candidates(context, prefs.notifSoundUri, ring = false),
            loop = false, volume = prefs.notifVolume, usage = AudioAttributes.USAGE_ALARM,
        )
        if (ok) handler.postDelayed(stopTestRunnable, 15_000)
        return ok
    }

    private fun stopIfPlaying(): Boolean {
        if (ringing) return false
        val playing = runCatching { player?.isPlaying == true }.getOrDefault(false)
        if (playing) {
            handler.removeCallbacks(stopTestRunnable)
            stopPlayer()
        }
        return playing
    }

    // Окно объединения звуков: пачка уведомлений подряд должна дать один звук.
    private const val ALERT_COOLDOWN_MS = 1200L
    @Volatile private var lastAlertAt = 0L
    @Volatile private var testBypass = false
    // Время последней озвучки по packageName (uptime); сбрасывается при рестарте процесса.
    private val lastAlertByPkg = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * true, если можно озвучить: не чаще ALERT_COOLDOWN_MS глобально и не чаще
     * appAlertTimeoutMinutes от того же пакета (если таймаут включён).
     */
    @Synchronized
    private fun shouldAlertNow(pkg: String, timeoutMinutes: Int): Boolean {
        if (testBypass) return true
        val now = android.os.SystemClock.uptimeMillis()
        if (timeoutMinutes > 0 && pkg.isNotBlank()) {
            val lastPkg = lastAlertByPkg[pkg] ?: 0L
            if (now - lastPkg < timeoutMinutes * 60_000L) return false
        }
        if (now - lastAlertAt < ALERT_COOLDOWN_MS) return false
        lastAlertAt = now
        if (timeoutMinutes > 0 && pkg.isNotBlank()) lastAlertByPkg[pkg] = now
        return true
    }

    fun notifyAllowed(
        context: Context,
        pkg: String,
        appLabel: String,
        title: String,
        text: String,
    ) {
        val prefs = Prefs.get(context)
        val quick = Modes.effective(prefs)
        if (quick == Modes.SILENT) return
        if (ringing) return // звонок важнее — не перебиваем
        val fresh = shouldAlertNow(pkg, prefs.appAlertTimeoutMinutes)
        if (quick == Modes.NORMAL && prefs.soundMode == "MIRROR") {
            // Зеркало показываем всегда, но озвучиваем только первое в окне.
            postMirror(context, appLabel, title, text, silent = !fresh)
            return
        }
        if (fresh) playSound(context, quick)
    }

    private fun playSound(context: Context, quick: String) {
        val prefs = Prefs.get(context)
        val mode = prefs.notifAlertMode
        val soundOn = quick == Modes.NORMAL && mode != "VIBRATE"
        val vibeOn = quick == Modes.VIBRATE || mode != "SOUND"
        if (soundOn) {
            // Поток будильника: громкий и не зависит от медиа-громкости и DND.
            startPlayer(
                context, candidates(context, prefs.notifSoundUri, ring = false),
                loop = false, volume = prefs.notifVolume, usage = AudioAttributes.USAGE_ALARM,
            )
        }
        if (vibeOn) {
            try {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE))
            } catch (_: Exception) {
            }
        }
    }

    /** Полный тест: прогоняет уведомление тем же путём, что и настоящее разрешённое. */
    fun testFullNotification(context: Context) {
        testBypass = true
        try {
            notifyAllowed(
                context, context.packageName, "Trinotify", "Тест",
                "Так звучит разрешённое уведомление",
            )
        } finally {
            testBypass = false
        }
    }

    private fun postMirror(context: Context, appLabel: String, title: String, text: String, silent: Boolean) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Публичная версия для локскрина не раскрывает текст скрытых уведомлений.
        val publicVersion = Notification.Builder(context, MIRROR_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Trinotify")
            .setContentText("Новое уведомление")
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        val n = Notification.Builder(context, if (silent) MIRROR_MUTED_CHANNEL else MIRROR_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(if (title.isNotBlank()) "$appLabel: $title" else appLabel)
            .setContentText(text.take(120))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()
        val id = ++mirrorId
        nm.notify(id, n)
        // Зеркало нужно только чтобы прозвучать — убираем через 8 секунд.
        handler.postDelayed({ nm.cancel(id) }, 8_000)
    }
}
