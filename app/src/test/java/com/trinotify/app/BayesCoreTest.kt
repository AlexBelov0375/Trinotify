package com.trinotify.app

import com.trinotify.app.data.BayesToken
import com.trinotify.app.ml.BayesCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BayesCoreTest {

    private fun trainRows(docs: Map<String, List<List<String>>>): List<BayesToken> {
        val rows = ArrayList<BayesToken>()
        for ((label, texts) in docs) {
            rows.add(BayesToken(BayesCore.DOC, label, texts.size))
            val counts = HashMap<String, Int>()
            for (tokens in texts) for (t in tokens) counts[t] = (counts[t] ?: 0) + 1
            for ((t, c) in counts) rows.add(BayesToken(t, label, c))
        }
        return rows
    }

    @Test
    fun `пустая модель не создаётся`() {
        assertNull(BayesCore.fromRows(emptyList()))
    }

    @Test
    fun `один класс не классифицируется`() {
        val core = BayesCore.fromRows(trainRows(mapOf("BLOCK" to listOf(listOf("спам", "скидка")))))
        assertNotNull(core)
        assertNull(core!!.classify(listOf("спам")))
    }

    @Test
    fun `очевидный спам уходит в BLOCK с высокой уверенностью`() {
        val rows = trainRows(
            mapOf(
                "BLOCK" to listOf(
                    listOf("pkg:spamapp", "скидка", "купи", "акция"),
                    listOf("pkg:spamapp", "скидка", "промокод"),
                    listOf("pkg:spamapp", "акция", "распродажа"),
                ),
                "ALLOW" to listOf(
                    listOf("pkg:messenger", "привет", "встретимся"),
                    listOf("pkg:messenger", "позвони", "мама"),
                ),
            )
        )
        val core = BayesCore.fromRows(rows)!!
        val (label, conf) = core.classify(listOf("pkg:spamapp", "скидка", "акция"))!!
        assertEquals("BLOCK", label)
        assertTrue("уверенность $conf должна быть > 0.9", conf > 0.9)
    }

    @Test
    fun `пакет приложения перевешивает при пустом тексте`() {
        val rows = trainRows(
            mapOf(
                "BLOCK" to listOf(
                    listOf("pkg:ads", "pkg:ads", "pkg:ads", "реклама"),
                    listOf("pkg:ads", "pkg:ads", "pkg:ads", "бонус"),
                ),
                "ALLOW" to listOf(
                    listOf("pkg:bank", "pkg:bank", "pkg:bank", "перевод"),
                    listOf("pkg:bank", "pkg:bank", "pkg:bank", "зачислен"),
                ),
            )
        )
        val core = BayesCore.fromRows(rows)!!
        assertEquals("BLOCK", core.classify(BayesCore.tokenize("ads", "", ""))!!.first)
        assertEquals("ALLOW", core.classify(BayesCore.tokenize("bank", "", ""))!!.first)
    }

    @Test
    fun `токенизация даёт пакету тройной вес и режет мусор`() {
        val tokens = BayesCore.tokenize("com.app", "Привет, мир!", "a б длинноеслово")
        assertEquals(3, tokens.count { it == "pkg:com.app" })
        assertTrue("привет" in tokens)
        assertTrue("мир" in tokens)
        assertTrue("длинноеслово" in tokens)
        // односимвольные отбрасываются
        assertTrue(tokens.none { it == "a" || it == "б" })
    }

    @Test
    fun `уверенность симметрична и в диапазоне`() {
        val rows = trainRows(
            mapOf(
                "BLOCK" to listOf(listOf("х", "спам")),
                "ALLOW" to listOf(listOf("х", "друг")),
            )
        )
        val core = BayesCore.fromRows(rows)!!
        val (_, conf) = core.classify(listOf("неизвестное"))!!
        assertTrue(conf in 0.4..0.6) // незнакомый токен — почти равные шансы
    }
}
