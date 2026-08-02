package com.trinotify.app

import com.trinotify.app.logic.CallMatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallMatcherTest {

    @Test
    fun `полный номер с плюсом совпадает с номером без плюса`() {
        assertTrue(CallMatcher.matches("+375291234567", "375291234567"))
        assertTrue(CallMatcher.matches("375291234567", "+375291234567"))
    }

    @Test
    fun `формат с пробелами и дефисами не мешает`() {
        assertTrue(CallMatcher.matches("+375 29 123-45-67", "tel:+375291234567".removePrefix("tel:")))
        assertTrue(CallMatcher.matches("8 (900) 123-45-67", "+79001234567"))
    }

    @Test
    fun `российская восьмёрка равна плюс семь`() {
        assertTrue(CallMatcher.matches("89001234567", "+79001234567"))
        assertTrue(CallMatcher.matches("+79001234567", "89001234567"))
    }

    @Test
    fun `иностранные номера сравниваются по хвосту`() {
        assertTrue(CallMatcher.matches("901234567", "+998901234567")) // Узбекистан без кода страны
        assertTrue(CallMatcher.matches("+998901234567", "901234567"))
    }

    @Test
    fun `разные номера не совпадают`() {
        assertFalse(CallMatcher.matches("+375291234567", "+375291112233"))
        assertFalse(CallMatcher.matches("89001234567", "89001234568"))
    }

    @Test
    fun `короткий шаблон работает как подстрока`() {
        assertTrue(CallMatcher.matches("8800", "88005553535"))
        assertFalse(CallMatcher.matches("8800", "+79001234567"))
    }

    @Test
    fun `звёздочка означает любые цифры`() {
        assertTrue(CallMatcher.matches("+7900*", "+79001234567"))
        assertTrue(CallMatcher.matches("*0000", "+79000000000"))
        assertFalse(CallMatcher.matches("+7900*", "+79991234567"))
    }

    @Test
    fun `пустые значения не совпадают`() {
        assertFalse(CallMatcher.matches("", "+79001234567"))
        assertFalse(CallMatcher.matches("+79001234567", ""))
    }
}
