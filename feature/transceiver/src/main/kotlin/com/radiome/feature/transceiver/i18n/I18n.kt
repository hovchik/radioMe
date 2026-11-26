package com.radiome.feature.transceiver.i18n

import java.util.Locale

/**
 * Translations of the app's text, keyed by the English original (gettext style). A string without an entry for
 * the current language shows in English, so a missing translation never breaks anything.
 *
 * Templates use [String.format] placeholders (`%s`, `%d`, `%1$s`...); a translation must keep the same ones, which
 * the unit tests check. Armenian and Russian are in [Armenian] and [Russian]; corrections from native speakers
 * go straight into those tables.
 */
object I18n {
    private val tables: Map<String, Map<String, String>> = mapOf("hy" to Armenian.table, "ru" to Russian.table)

    /** Overrides the system language, for tests. Null follows [Locale.getDefault]. */
    @Volatile var languageOverride: String? = null

    val language: String get() = languageOverride ?: Locale.getDefault().language

    fun tr(english: String): String = tables[language]?.get(english) ?: english

    fun tr(english: String, vararg args: Any?): String = tr(english).format(*args)

    /** Languages with a table, for the tests. */
    internal val all: Map<String, Map<String, String>> get() = tables
}

/** Short form for call sites: `tr("Start listening")`, `tr("Queue (%d)", n)`. */
fun tr(english: String): String = I18n.tr(english)

fun tr(english: String, vararg args: Any?): String = I18n.tr(english, *args)
