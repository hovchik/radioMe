package com.radiome.feature.transceiver

import com.radiome.feature.transceiver.i18n.I18n
import com.radiome.feature.transceiver.i18n.tr
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class I18nTest {
    @After
    fun reset() {
        I18n.languageOverride = null
    }

    private val placeholder = Regex("%(\\d+\\$)?[-#+0,(]*\\d*(\\.\\d+)?[a-zA-Z%]")

    @Test
    fun everyTranslationKeepsThePlaceholders() {
        for ((lang, table) in I18n.all) {
            for ((en, translated) in table) {
                assertEquals(
                    "$lang: \"$en\"",
                    placeholder.findAll(en).map { it.value }.toList(),
                    placeholder.findAll(translated).map { it.value }.toList(),
                )
                assertTrue("$lang: \"$en\" is blank", translated.isNotBlank())
            }
        }
    }

    @Test
    fun languagesFallBackToEnglish() {
        I18n.languageOverride = "ru"
        assertEquals("Очередь (3)", tr("Queue (%d)", 3))
        assertEquals("Not in any table", tr("Not in any table"))
        I18n.languageOverride = "hy"
        assertEquals("Ռադիո", tr("Radio"))
        I18n.languageOverride = "de"
        assertEquals("Radio", tr("Radio"))
        assertEquals("Queue (3)", tr("Queue (%d)", 3))
    }
}
