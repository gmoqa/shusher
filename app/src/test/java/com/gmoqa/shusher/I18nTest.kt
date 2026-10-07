package com.gmoqa.shusher

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class I18nTest {
    // Claves de un JSON plano, sin depender de org.json (en tests locales es un stub de Android).
    private fun keys(f: File) = Regex("\"(\\w+)\"\\s*:").findAll(f.readText()).map { it.groupValues[1] }.toSet()

    @Test
    fun allLanguagesHaveSameKeys() {
        val dir = File("src/main/assets/lang")
        val base = keys(File(dir, "es.json"))
        dir.listFiles()!!.forEach { assertEquals("claves de ${it.name}", base, keys(it)) }
    }
}
