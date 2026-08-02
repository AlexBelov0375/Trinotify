package com.trinotify.app.logic

import android.content.Context
import com.trinotify.app.data.Action
import com.trinotify.app.data.Db
import com.trinotify.app.data.Prefs
import com.trinotify.app.ml.Ml
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

data class Decision(val action: String, val source: String)

/**
 * Решение по уведомлению. Приоритет:
 * 1) правило отправителя (самое точное),
 * 2) правило приложения,
 * 3) ML (если включён глобально или назначен приложению) при достаточной уверенности,
 * 4) действие по умолчанию.
 * Правила читаются из RuleCache; счётчики срабатываний пишутся в фоне.
 */
class RuleEngine(context: Context) {
    private val appCtx = context.applicationContext
    private val prefs = Prefs.get(context)
    private val hitScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun hit(block: suspend () -> Unit) {
        hitScope.launch { try { block() } catch (_: Exception) {} }
    }

    suspend fun decide(pkg: String, title: String, text: String): Decision {
        for (r in RuleCache.senderRulesNow(appCtx)) {
            if (r.pkg != null && r.pkg != pkg) continue
            val haystack = if (r.inText) "$title\n$text" else title
            if (r.pattern.isNotBlank() && haystack.contains(r.pattern, ignoreCase = true)) {
                hit { Db.get(appCtx).ruleDao().incSenderHit(r.id) }
                return Decision(r.action, "Правило отправителя «${r.pattern}»")
            }
        }

        var mlForced = false
        RuleCache.appRule(appCtx, pkg)?.let { rule ->
            if (rule.action == Action.ML) mlForced = true
            else {
                hit { Db.get(appCtx).ruleDao().incAppHit(pkg) }
                return Decision(rule.action, "Правило приложения")
            }
        }

        if (prefs.mlEnabled || mlForced) {
            Ml.ensureLoaded(Db.get(appCtx))
            Ml.classify(pkg, title, text)?.let { (label, conf) ->
                if (conf >= prefs.mlThreshold) {
                    if (mlForced) hit { Db.get(appCtx).ruleDao().incAppHit(pkg) }
                    return Decision(label, "ML (${(conf * 100).toInt()}%)")
                }
            }
        }

        return Decision(prefs.defaultAction, "По умолчанию")
    }
}

/** Сопоставление номера с шаблоном: '*' — любые цифры, иначе совпадение по цифрам. */
object CallMatcher {
    /** Только цифры; российские 11-значные с ведущей 8 приводятся к 7. */
    fun normalize(number: String): String {
        var d = number.filter { it.isDigit() }
        if (d.length == 11 && d.startsWith("8")) d = "7" + d.substring(1)
        return d
    }

    fun matches(pattern: String, number: String): Boolean {
        val raw = number.filter { it.isDigit() } // без нормализации 8→7
        val n = normalize(number)
        if (pattern.contains('*')) {
            val regex = Regex(pattern.split('*').joinToString(".*") {
                Regex.escape(it.filter { ch -> ch.isDigit() })
            })
            return regex.containsMatchIn(n) || regex.containsMatchIn(raw)
        }
        val p = normalize(pattern)
        if (p.isEmpty() || n.isEmpty()) return false
        // Полные номера любой страны: сравниваем общий хвост (7–10 цифр),
        // чтобы не зависеть от кода страны, ведущих 8/+7/0 и формата записи.
        if (p.length >= 7 && n.length >= 7) {
            val k = minOf(p.length, n.length, 10)
            return p.takeLast(k) == n.takeLast(k)
        }
        // Короткие шаблоны (например 8800) — подстрока в любой из форм записи.
        return n.contains(p) || raw.contains(p)
    }
}
