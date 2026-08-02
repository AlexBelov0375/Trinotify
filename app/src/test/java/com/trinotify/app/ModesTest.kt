package com.trinotify.app

import com.trinotify.app.logic.Modes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ModesTest {

    private fun at(hour: Int, minute: Int = 0) = hour * 60 + minute

    // ---------- ночное окно ----------

    @Test
    fun `окно через полночь захватывает вечер и утро`() {
        val start = at(23); val end = at(8)
        assertTrue(Modes.isNight(true, start, end, at(23, 0)))  // ровно начало
        assertTrue(Modes.isNight(true, start, end, at(23, 30)))
        assertTrue(Modes.isNight(true, start, end, at(0, 0)))   // полночь
        assertTrue(Modes.isNight(true, start, end, at(7, 59)))  // последняя минута
        assertFalse(Modes.isNight(true, start, end, at(8, 0)))  // ровно конец — уже день
        assertFalse(Modes.isNight(true, start, end, at(12, 0)))
        assertFalse(Modes.isNight(true, start, end, at(22, 59)))
    }

    @Test
    fun `окно с полуночи до утра — случай пользователя 00-08`() {
        val start = at(0); val end = at(8)
        assertTrue(Modes.isNight(true, start, end, at(0, 0)))   // первая минута
        assertTrue(Modes.isNight(true, start, end, at(0, 1)))
        assertTrue(Modes.isNight(true, start, end, at(7, 59)))  // последняя минута
        assertFalse(Modes.isNight(true, start, end, at(8, 0)))
        assertFalse(Modes.isNight(true, start, end, at(23, 59)))
    }

    @Test
    fun `выключенное расписание и вырожденное окно не считаются ночью`() {
        assertFalse(Modes.isNight(false, at(0), at(8), at(3)))
        assertFalse(Modes.isNight(true, at(8), at(8), at(8)))
    }

    // ---------- приоритет режимов ----------

    @Test
    fun `ночью действует ночной режим`() {
        assertEquals(
            Modes.SILENT,
            Modes.effectiveOf(night = true, manualOverride = false, nightMode = Modes.SILENT, quickMode = Modes.NORMAL)
        )
    }

    @Test
    fun `ручной выбор перебивает ночь`() {
        assertEquals(
            Modes.NORMAL,
            Modes.effectiveOf(night = true, manualOverride = true, nightMode = Modes.SILENT, quickMode = Modes.NORMAL)
        )
    }

    @Test
    fun `днём всегда действует ручной режим`() {
        assertEquals(
            Modes.VIBRATE,
            Modes.effectiveOf(night = false, manualOverride = false, nightMode = Modes.SILENT, quickMode = Modes.VIBRATE)
        )
    }

    // ---------- момент окончания ночи ----------

    private fun millisAt(hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun hourOf(millis: Long): Int =
        Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.HOUR_OF_DAY)

    @Test
    fun `окончание ночи в тот же день, если конец ещё впереди`() {
        val now = millisAt(2, 0)
        val end = Modes.nightEndAt(at(8), now)
        assertTrue("окончание должно быть в будущем", end > now)
        assertEquals(8, hourOf(end))
        assertEquals(6 * 60 * 60 * 1000L, end - now) // ровно 6 часов
    }

    @Test
    fun `окончание ночи переносится на завтра, если конец уже прошёл`() {
        val now = millisAt(23, 30)
        val end = Modes.nightEndAt(at(8), now)
        assertTrue(end > now)
        assertEquals(8, hourOf(end))
        assertEquals(8 * 60 * 60 * 1000L + 30 * 60 * 1000L, end - now) // 8ч30м
    }

    @Test
    fun `ручной приоритет истекает не позже конца ночи`() {
        val now = millisAt(0, 30)
        val end = Modes.nightEndAt(at(8), now)
        assertTrue(end - now <= 8 * 60 * 60 * 1000L)
    }

    @Test
    fun `формат времени двузначный`() {
        assertEquals("00:00", Modes.formatTime(0))
        assertEquals("08:05", Modes.formatTime(at(8, 5)))
        assertEquals("23:59", Modes.formatTime(at(23, 59)))
    }
}
