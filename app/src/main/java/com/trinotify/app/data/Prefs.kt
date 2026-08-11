package com.trinotify.app.data

import android.content.Context
import android.content.SharedPreferences

/** Настройки приложения поверх SharedPreferences. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("trinotify", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    /** Режим тишины: держим DND, чтобы чужие уведомления не звучали. */
    var dndMode: Boolean
        get() = sp.getBoolean("dnd", true)
        set(v) = sp.edit().putBoolean("dnd", v).apply()

    /** Как озвучивать разрешённые уведомления в режиме тишины: SOUND | MIRROR. */
    var soundMode: String
        get() = sp.getString("soundMode", "SOUND")!!
        set(v) = sp.edit().putString("soundMode", v).apply()

    /** Действие, когда нет ни правила, ни уверенного ML: ALLOW | SILENCE | BLOCK. */
    var defaultAction: String
        get() = sp.getString("defaultAction", Action.ALLOW)!!
        set(v) = sp.edit().putString("defaultAction", v).apply()

    var mlEnabled: Boolean
        get() = sp.getBoolean("mlEnabled", false)
        set(v) = sp.edit().putBoolean("mlEnabled", v).apply()

    var mlThreshold: Float
        get() = sp.getFloat("mlThreshold", 0.75f)
        set(v) = sp.edit().putFloat("mlThreshold", v).apply()

    /** Играть рингтон самим при входящем звонке в режиме тишины (MIUI глушит системный). */
    var ringInDnd: Boolean
        get() = sp.getBoolean("ringInDnd", true)
        set(v) = sp.edit().putBoolean("ringInDnd", v).apply()

    /** Оповещение о звонке: BOTH | SOUND | VIBRATE. */
    var callAlertMode: String
        get() = sp.getString("callAlertMode", "BOTH")!!
        set(v) = sp.edit().putString("callAlertMode", v).apply()

    /** Мелодия звонка (uri); пусто = системная по умолчанию. */
    var callRingUri: String
        get() = sp.getString("callRingUri", "")!!
        set(v) = sp.edit().putString("callRingUri", v).apply()

    /** Громкость рингтона 0..1. */
    var callVolume: Float
        get() = sp.getFloat("callVolume", 1f)
        set(v) = sp.edit().putFloat("callVolume", v).apply()

    /** Оповещение об уведомлении: BOTH | SOUND | VIBRATE. */
    var notifAlertMode: String
        get() = sp.getString("notifAlertMode", "BOTH")!!
        set(v) = sp.edit().putString("notifAlertMode", v).apply()

    /**
     * Таймаут озвучки от одного приложения, минуты.
     * После первого звука от пакета следующие N минут от него не озвучиваются; 0 — выкл.
     */
    var appAlertTimeoutMinutes: Int
        get() = sp.getInt("appAlertTimeoutMinutes", 0)
        set(v) = sp.edit().putInt("appAlertTimeoutMinutes", v).apply()

    /** Звук уведомления (uri); пусто = системный по умолчанию. */
    var notifSoundUri: String
        get() = sp.getString("notifSoundUri", "")!!
        set(v) = sp.edit().putString("notifSoundUri", v).apply()

    /** Громкость звука уведомлений 0..1. */
    var notifVolume: Float
        get() = sp.getFloat("notifVolume", 0.8f)
        set(v) = sp.edit().putFloat("notifVolume", v).apply()

    /** Режим звонков: ALL (все, кроме чёрного списка) | WHITELIST (только белый список). */
    var callMode: String
        get() = sp.getString("callMode", "ALL")!!
        set(v) = sp.edit().putString("callMode", v).apply()

    var logCalls: Boolean
        get() = sp.getBoolean("logCalls", true)
        set(v) = sp.edit().putBoolean("logCalls", v).apply()

    /** Быстрый режим из шторки: NORMAL | VIBRATE | SILENT. */
    var quickMode: String
        get() = sp.getString("quickMode", "NORMAL")!!
        set(v) = sp.edit().putString("quickMode", v).apply()

    /** Ночной режим: автоматический быстрый режим по времени. */
    var nightEnabled: Boolean
        get() = sp.getBoolean("nightEnabled", false)
        set(v) = sp.edit().putBoolean("nightEnabled", v).apply()

    /** Начало/конец ночного режима, минуты от полуночи. */
    var nightStart: Int
        get() = sp.getInt("nightStart", 23 * 60)
        set(v) = sp.edit().putInt("nightStart", v).apply()

    var nightEnd: Int
        get() = sp.getInt("nightEnd", 8 * 60)
        set(v) = sp.edit().putInt("nightEnd", v).apply()

    /** Режим ночью: VIBRATE | SILENT. */
    var nightMode: String
        get() = sp.getString("nightMode", "SILENT")!!
        set(v) = sp.edit().putString("nightMode", v).apply()

    /** До какого момента (мс) ручной выбор режима перебивает ночное расписание. */
    var nightOverrideUntil: Long
        get() = sp.getLong("nightOverrideUntil", 0L)
        set(v) = sp.edit().putLong("nightOverrideUntil", v).apply()

    /** Ночью пропускать звонки только из белого списка. */
    var nightCallsWhitelistOnly: Boolean
        get() = sp.getBoolean("nightCallsWhitelistOnly", false)
        set(v) = sp.edit().putBoolean("nightCallsWhitelistOnly", v).apply()

    /** Авточистка неразмеченных записей старше N дней; 0 — выключена. */
    var autoPruneDays: Int
        get() = sp.getInt("autoPruneDays", 0)
        set(v) = sp.edit().putInt("autoPruneDays", v).apply()

    /** База данных зашифрована (SQLCipher, ключ в Android Keystore). */
    var dbEncrypted: Boolean
        get() = sp.getBoolean("dbEncrypted", false)
        set(v) = sp.edit().putBoolean("dbEncrypted", v).apply()

    /** Вход в приложение под биометрией/пином устройства. */
    var appLock: Boolean
        get() = sp.getBoolean("appLock", false)
        set(v) = sp.edit().putBoolean("appLock", v).apply()

    companion object {
        @Volatile private var instance: Prefs? = null
        fun get(context: Context): Prefs =
            instance ?: synchronized(this) { instance ?: Prefs(context).also { instance = it } }
    }
}
