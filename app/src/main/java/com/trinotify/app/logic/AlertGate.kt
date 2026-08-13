package com.trinotify.app.logic

import java.util.concurrent.ConcurrentHashMap

/**
 * Когда можно озвучить уведомление: короткий глобальный coalesce пачки
 * и таймаут «не повторять от одного приложения».
 *
 * [now] — монотонные миллисекунды (elapsedRealtime): сон устройства входит
 * в интервал, как ожидает пользователь («через 5 минут»).
 * Отсутствующий пакет никогда не приравнивается к моменту 0 — иначе при малом
 * uptime после сна первое уведомление глушилось бы целиком.
 */
class AlertGate(
    private val globalCooldownMs: Long = 1_200L,
) {
    @Volatile private var lastAlertAt = 0L
    private val lastByPkg = ConcurrentHashMap<String, Long>()

    fun inAppTimeout(pkg: String, timeoutMinutes: Int, now: Long): Boolean {
        if (timeoutMinutes <= 0 || pkg.isBlank()) return false
        val last = lastByPkg[pkg] ?: return false
        return now - last < timeoutMinutes * 60_000L
    }

    /**
     * true, если сейчас можно озвучить. Момент записывается только при разрешении.
     */
    @Synchronized
    fun shouldAlert(pkg: String, timeoutMinutes: Int, now: Long): Boolean {
        if (inAppTimeout(pkg, timeoutMinutes, now)) return false
        if (lastAlertAt != 0L && now - lastAlertAt < globalCooldownMs) return false
        lastAlertAt = now
        if (timeoutMinutes > 0 && pkg.isNotBlank()) lastByPkg[pkg] = now
        return true
    }
}
