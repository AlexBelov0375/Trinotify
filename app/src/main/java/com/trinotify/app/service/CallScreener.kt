package com.trinotify.app.service

import android.telecom.Call
import android.telecom.CallScreeningService
import com.trinotify.app.data.Action
import com.trinotify.app.data.Db
import com.trinotify.app.data.NotifRecord
import com.trinotify.app.data.Prefs
import com.trinotify.app.logic.CallMatcher
import com.trinotify.app.logic.Modes
import com.trinotify.app.logic.RuleCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Фильтр входящих звонков (роль «Определитель номера и защита от спама»). */
class CallScreener : CallScreeningService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onScreenCall(details: Call.Details) {
        if (details.callDirection != Call.Details.DIRECTION_INCOMING) {
            respondToCall(details, CallResponse.Builder().build())
            return
        }
        val prefs = Prefs.get(this)
        val number = details.handle?.schemeSpecificPart.orEmpty()

        val rules = RuleCache.callRulesNow(this)
        val allowRule = rules.firstOrNull { it.action == Action.ALLOW && CallMatcher.matches(it.pattern, number) }
        val blockRule = rules.firstOrNull { it.action == Action.BLOCK && CallMatcher.matches(it.pattern, number) }
        val whitelistMode = prefs.callMode == "WHITELIST" ||
                (prefs.nightCallsWhitelistOnly && Modes.isNightNow(prefs))

        // «Пропускать» всегда сильнее «Блокировать».
        val block = when {
            !prefs.enabled -> false
            allowRule != null -> false
            whitelistMode -> true
            else -> blockRule != null
        }
        val matched = if (block) blockRule else allowRule
        if (matched != null) {
            scope.launch {
                try { Db.get(this@CallScreener).ruleDao().incCallHit(matched.id) } catch (_: Exception) {}
            }
        }

        val response = if (block) {
            CallResponse.Builder()
                .setDisallowCall(true)
                .setRejectCall(true)
                .setSkipNotification(true)
                .build()
        } else {
            CallResponse.Builder().build()
        }
        respondToCall(details, response)

        if (prefs.logCalls) {
            scope.launch {
                Db.get(this@CallScreener).notifDao().insert(
                    NotifRecord(
                        pkg = "call",
                        appLabel = "Звонок",
                        title = number.ifBlank { "Скрытый номер" },
                        text = if (block) "Заблокирован" else "Пропущен",
                        postedAt = System.currentTimeMillis(),
                        verdict = if (block) Action.BLOCK else Action.ALLOW,
                        verdictSource = "Правила звонков (" +
                                (if (prefs.nightCallsWhitelistOnly && Modes.isNightNow(prefs)) "ночь: белый список"
                                else if (prefs.callMode == "WHITELIST") "белый список" else "чёрный список") + ")",
                        isCall = true,
                    )
                )
            }
        }
    }
}
