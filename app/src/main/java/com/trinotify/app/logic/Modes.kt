package com.trinotify.app.logic

import com.trinotify.app.data.Prefs
import java.util.Calendar

/** Быстрые режимы оповещения (шторка + ночное расписание). */
object Modes {
    const val NORMAL = "NORMAL"   // как настроено
    const val VIBRATE = "VIBRATE" // только вибрация
    const val SILENT = "SILENT"   // полная тишина

    fun label(mode: String): String = when (mode) {
        VIBRATE -> "Вибро"
        SILENT -> "Тихий"
        else -> "Обычный"
    }

    // ---------- чистая логика (покрыта юнит-тестами) ----------

    /** Попадает ли момент суток в ночное окно; окно может проходить через полночь. */
    fun isNight(enabled: Boolean, startMinutes: Int, endMinutes: Int, nowMinutes: Int): Boolean {
        if (!enabled || startMinutes == endMinutes) return false
        return if (startMinutes < endMinutes) nowMinutes >= startMinutes && nowMinutes < endMinutes
        else nowMinutes >= startMinutes || nowMinutes < endMinutes
    }

    /** Ночь диктует режим, но ручной выбор пользователя её перебивает. */
    fun effectiveOf(night: Boolean, manualOverride: Boolean, nightMode: String, quickMode: String): String =
        if (night && !manualOverride) nightMode else quickMode

    /** Ближайший момент окончания ночного окна в миллисекундах. */
    fun nightEndAt(endMinutes: Int, now: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        cal.set(Calendar.HOUR_OF_DAY, endMinutes / 60)
        cal.set(Calendar.MINUTE, endMinutes % 60)
        if (endMinutes <= nowMinutes) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    fun minutesOfDay(now: Long = System.currentTimeMillis()): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    fun formatTime(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

    // ---------- обёртки над настройками ----------

    fun isNightNow(prefs: Prefs): Boolean =
        isNight(prefs.nightEnabled, prefs.nightStart, prefs.nightEnd, minutesOfDay())

    /** Действует ли ручной выбор, сделанный во время ночного окна. */
    fun overrideActive(prefs: Prefs): Boolean =
        prefs.nightOverrideUntil > System.currentTimeMillis()

    fun effective(prefs: Prefs): String =
        effectiveOf(isNightNow(prefs), overrideActive(prefs), prefs.nightMode, prefs.quickMode)

    /**
     * Ручной выбор режима (шторка или настройки). Сделанный ночью — действует
     * до конца текущего ночного окна, дальше расписание снова берёт своё.
     */
    fun applyManual(prefs: Prefs, mode: String) {
        prefs.quickMode = mode
        prefs.nightOverrideUntil =
            if (isNightNow(prefs)) nightEndAt(prefs.nightEnd, System.currentTimeMillis()) else 0L
    }

    /** Сброс ручного приоритета — при изменении самого расписания. */
    fun clearOverride(prefs: Prefs) {
        prefs.nightOverrideUntil = 0L
    }
}
