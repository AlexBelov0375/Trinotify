package com.trinotify.app

import com.trinotify.app.logic.AlertGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertGateTest {

    @Test
    fun `первое уведомление звучит даже если now меньше окна таймаута`() {
        val gate = AlertGate()
        // Раньше отсутствующий пакет приравнивался к моменту 0: при now < 5 мин
        // звук не играл никогда (типично после сна в режиме тишины).
        assertFalse(gate.inAppTimeout("org.telegram.messenger", 5, now = 30_000L))
        assertTrue(gate.shouldAlert("org.telegram.messenger", timeoutMinutes = 5, now = 30_000L))
        assertTrue(gate.inAppTimeout("org.telegram.messenger", 5, now = 30_000L))
    }

    @Test
    fun `повтор от того же пакета внутри окна глушится`() {
        val gate = AlertGate()
        assertTrue(gate.shouldAlert("org.telegram.messenger", 5, now = 10_000L))
        assertFalse(gate.shouldAlert("org.telegram.messenger", 5, now = 10_000L + 60_000L))
        assertFalse(gate.shouldAlert("org.telegram.messenger", 5, now = 10_000L + 5 * 60_000L - 1))
    }

    @Test
    fun `после истечения окна снова звучит`() {
        val gate = AlertGate()
        assertTrue(gate.shouldAlert("org.telegram.messenger", 5, now = 10_000L))
        assertTrue(gate.shouldAlert("org.telegram.messenger", 5, now = 10_000L + 5 * 60_000L))
    }

    @Test
    fun `другое приложение не попадает под чужой таймаут`() {
        val gate = AlertGate()
        assertTrue(gate.shouldAlert("org.telegram.messenger", 5, now = 10_000L))
        assertTrue(gate.shouldAlert("com.whatsapp", 5, now = 10_000L + 100L))
    }

    @Test
    fun `таймаут выключен — пакет не запоминается`() {
        val gate = AlertGate()
        assertTrue(gate.shouldAlert("org.telegram.messenger", timeoutMinutes = 0, now = 10_000L))
        assertFalse(gate.inAppTimeout("org.telegram.messenger", 0, now = 11_000L))
        assertTrue(gate.shouldAlert("org.telegram.messenger", timeoutMinutes = 0, now = 12_000L))
    }

    @Test
    fun `глобальный coalesce пачки даёт один звук`() {
        val gate = AlertGate(globalCooldownMs = 1_200L)
        assertTrue(gate.shouldAlert("a.b", timeoutMinutes = 0, now = 50_000L))
        assertFalse(gate.shouldAlert("c.d", timeoutMinutes = 0, now = 50_500L))
        assertTrue(gate.shouldAlert("c.d", timeoutMinutes = 0, now = 51_300L))
    }
}
