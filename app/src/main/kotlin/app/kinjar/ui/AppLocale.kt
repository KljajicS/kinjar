package app.kinjar.ui

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * Serbian strings exist only in values-b+sr+Latn. Android treats script-less "sr" and "sr-RS" as Cyrillic
 * and never falls back across scripts, so those phones would otherwise get English.
 */
fun serbianLatin(locales: List<Locale>): List<Locale> = locales.map { locale ->
    if (locale.language == "sr" && locale.script != "Latn") Locale.Builder().setLocale(locale).setScript("Latn").build() else locale
}.distinct()

fun withSerbianLatin(base: Context): Context {
    val current = base.resources.configuration.locales
    val locales = List(current.size()) { current[it] }
    val normalized = serbianLatin(locales)
    if (normalized == locales) return base
    return base.createConfigurationContext(Configuration().apply { setLocales(LocaleList(*normalized.toTypedArray())) })
}
