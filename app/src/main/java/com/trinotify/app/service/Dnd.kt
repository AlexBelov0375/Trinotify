package com.trinotify.app.service

import android.app.NotificationManager
import android.app.NotificationManager.Policy
import android.content.Context
import com.trinotify.app.data.Prefs

/**
 * Режим тишины: включаем DND-приоритет, в котором звонки, будильники и медиа
 * проходят, а звуки уведомлений всех приложений глушатся. Разрешённые уведомления
 * Trinotify озвучивает сам (см. Sounder).
 */
object Dnd {
    fun hasAccess(context: Context): Boolean =
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .isNotificationPolicyAccessGranted

    fun apply(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) return
        val prefs = Prefs.get(context)
        if (prefs.enabled && prefs.dndMode) {
            nm.notificationPolicy = Policy(
                Policy.PRIORITY_CATEGORY_ALARMS
                        or Policy.PRIORITY_CATEGORY_MEDIA
                        or Policy.PRIORITY_CATEGORY_SYSTEM
                        or Policy.PRIORITY_CATEGORY_CALLS
                        or Policy.PRIORITY_CATEGORY_REPEAT_CALLERS,
                Policy.PRIORITY_SENDERS_ANY,
                Policy.PRIORITY_SENDERS_ANY,
                0, // ничего не скрывать визуально
            )
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        } else {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        }
    }

    fun isActive(context: Context): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }
}
