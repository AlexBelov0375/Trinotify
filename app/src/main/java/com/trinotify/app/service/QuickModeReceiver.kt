package com.trinotify.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.trinotify.app.data.Prefs
import com.trinotify.app.logic.Modes

/** Кнопки быстрых режимов в статус-уведомлении. */
class QuickModeReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_PREFIX = "com.trinotify.app.QUICK_MODE."
    }

    override fun onReceive(context: Context, intent: Intent) {
        val mode = intent.getStringExtra("mode") ?: return
        if (mode !in setOf(Modes.NORMAL, Modes.VIBRATE, Modes.SILENT)) return
        // Ночью ручной выбор перебивает расписание до конца ночного окна.
        Modes.applyManual(Prefs.get(context), mode)
        Sounder.showStatus(context) // перерисовать подсветку
    }
}
