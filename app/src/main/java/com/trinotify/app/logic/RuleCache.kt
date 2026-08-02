package com.trinotify.app.logic

import android.content.Context
import com.trinotify.app.data.AppRule
import com.trinotify.app.data.CallRule
import com.trinotify.app.data.Db
import com.trinotify.app.data.SenderRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Правила в памяти: движок и фильтр звонков читают их синхронно, без похода в БД
 * на каждое событие. Обновляются реактивно из Room-Flow.
 */
object RuleCache {
    @Volatile var appRules: Map<String, AppRule> = emptyMap(); private set
    @Volatile var senderRules: List<SenderRule> = emptyList(); private set
    @Volatile var callRules: List<CallRule> = emptyList(); private set

    @Volatile private var appLoaded = false
    @Volatile private var senderLoaded = false
    @Volatile private var callLoaded = false
    @Volatile private var started = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        val appCtx = context.applicationContext
        // При зашифрованной БД до первой разблокировки устройства Room может падать —
        // повторяем с паузой, пока не получится.
        scope.launch {
            while (true) {
                try {
                    Db.get(appCtx).ruleDao().appRules().collect {
                        appRules = it.associateBy { r -> r.pkg }; appLoaded = true
                    }
                } catch (_: Exception) {
                }
                delay(5_000)
            }
        }
        scope.launch {
            while (true) {
                try {
                    Db.get(appCtx).ruleDao().senderRules().collect {
                        senderRules = it; senderLoaded = true
                    }
                } catch (_: Exception) {
                }
                delay(5_000)
            }
        }
        scope.launch {
            while (true) {
                try {
                    Db.get(appCtx).ruleDao().callRules().collect {
                        callRules = it; callLoaded = true
                    }
                } catch (_: Exception) {
                }
                delay(5_000)
            }
        }
    }

    /** Синхронный снапшот правил звонков; при холодном старте — одно блокирующее чтение. */
    fun callRulesNow(context: Context): List<CallRule> {
        if (callLoaded) return callRules
        return try {
            runBlocking { Db.get(context).ruleDao().callRulesSnapshot() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun appRule(context: Context, pkg: String): AppRule? =
        if (appLoaded) appRules[pkg] else Db.get(context).ruleDao().appRule(pkg)

    suspend fun senderRulesNow(context: Context): List<SenderRule> =
        if (senderLoaded) senderRules else Db.get(context).ruleDao().senderRulesSnapshot()
}
