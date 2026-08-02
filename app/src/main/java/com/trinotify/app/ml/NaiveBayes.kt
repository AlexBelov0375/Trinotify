package com.trinotify.app.ml

import com.trinotify.app.data.BayesToken
import com.trinotify.app.data.Db
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.ln

/**
 * Чистая математика мультиномиального наивного Байеса — без Android и Room,
 * полностью покрывается юнит-тестами.
 */
class BayesCore private constructor(
    private val docCount: Map<String, Int>,
    private val tokenCount: Map<String, Map<String, Int>>,
    private val totalTokens: Map<String, Int>,
    private val vocabSize: Int,
) {
    companion object {
        const val DOC = "__doc__"

        fun fromRows(rows: List<BayesToken>): BayesCore? {
            if (rows.isEmpty()) return null
            val docCount = HashMap<String, Int>()
            val tokenCount = HashMap<String, HashMap<String, Int>>()
            val vocab = HashSet<String>()
            for (r in rows) {
                if (r.token == DOC) docCount[r.label] = r.count
                else {
                    tokenCount.getOrPut(r.label) { HashMap() }[r.token] = r.count
                    vocab.add(r.token)
                }
            }
            if (docCount.isEmpty()) return null
            val totals = tokenCount.mapValues { (_, m) -> m.values.sum() }
            return BayesCore(docCount, tokenCount, totals, vocab.size.coerceAtLeast(1))
        }

        fun tokenize(pkg: String, title: String, text: String): List<String> {
            val words = Regex("[\\p{L}\\p{N}]+").findAll("$title $text".lowercase())
                .map { it.value }
                .filter { it.length in 2..24 }
                .take(80)
                .toList()
            return List(3) { "pkg:$pkg" } + words
        }
    }

    val classes: Set<String> get() = docCount.keys
    val vocabulary: Int get() = vocabSize
    fun docs(label: String): Int = docCount[label] ?: 0

    /** Возвращает (метка, уверенность 0..1) или null, если классов меньше двух. */
    fun classify(tokens: List<String>): Pair<String, Double>? {
        if (docCount.size < 2) return null
        val totalDocs = docCount.values.sum().toDouble()
        val logScores = docCount.mapValues { (label, docs) ->
            var s = ln(docs / totalDocs)
            val counts = tokenCount[label] ?: emptyMap()
            val denom = (totalTokens[label] ?: 0) + vocabSize
            for (t in tokens) {
                s += ln(((counts[t] ?: 0) + 1.0) / denom)
            }
            s
        }
        val max = logScores.values.max()
        val expScores = logScores.mapValues { exp(it.value - max) }
        val sum = expScores.values.sum()
        val best = expScores.maxByOrNull { it.value }!!
        return best.key to (best.value / sum)
    }
}

/**
 * Обёртка модели для приложения: хранение счётчиков в Room, обучение на размеченном
 * архиве. Переобучение идёт в собственном скоупе — уход с экрана его не оборвёт.
 */
object Ml {
    @Volatile private var core: BayesCore? = null
    @Volatile private var loaded = false
    private val trainScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun tokenize(pkg: String, title: String, text: String): List<String> =
        BayesCore.tokenize(pkg, title, text)

    suspend fun ensureLoaded(db: Db) {
        if (loaded) return
        core = BayesCore.fromRows(db.bayesDao().all())
        loaded = true
    }

    /** Полное переобучение на всех размеченных записях. Возвращает размер выборки. */
    suspend fun rebuild(db: Db): Int {
        val labeled = db.notifDao().labeled()
        val docs = HashMap<String, Int>()
        val tokens = HashMap<String, HashMap<String, Int>>()
        for (r in labeled) {
            val label = r.userLabel ?: continue
            docs[label] = (docs[label] ?: 0) + 1
            val map = tokens.getOrPut(label) { HashMap() }
            for (t in tokenize(r.pkg, r.title, r.text)) {
                map[t] = (map[t] ?: 0) + 1
            }
        }
        val rows = ArrayList<BayesToken>()
        for ((label, n) in docs) rows.add(BayesToken(BayesCore.DOC, label, n))
        for ((label, map) in tokens) for ((t, c) in map) rows.add(BayesToken(t, label, c))
        db.bayesDao().clear()
        if (rows.isNotEmpty()) db.bayesDao().insertAll(rows)
        core = BayesCore.fromRows(rows)
        loaded = true
        return labeled.size
    }

    /** Фоновое переобучение, не привязанное к вызывающему экрану (не отменится). */
    fun rebuildAsync(db: Db) {
        trainScope.launch { try { rebuild(db) } catch (_: Exception) {} }
    }

    fun classify(pkg: String, title: String, text: String): Pair<String, Double>? =
        core?.classify(tokenize(pkg, title, text))

    fun stats(): String {
        val m = core ?: return "модель не обучена"
        val docs = m.classes.joinToString(", ") { "${actionRu(it)}: ${m.docs(it)}" }
        return "выборка [$docs], словарь: ${m.vocabulary}"
    }

    private fun actionRu(a: String) = when (a) {
        "ALLOW" -> "звук"; "SILENCE" -> "тихо"; "BLOCK" -> "блок"; else -> a
    }
}
