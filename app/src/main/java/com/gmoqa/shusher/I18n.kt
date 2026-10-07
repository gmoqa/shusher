package com.gmoqa.shusher

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * Textos de la app desde assets/lang/<código>.json. Agregar un idioma = agregar un JSON
 * con las mismas claves que es.json (I18nTest lo verifica).
 */
object I18n {
    private const val KEY = "lang"
    private const val FALLBACK = "en"
    private var cache: Pair<String, JSONObject>? = null

    /** Códigos disponibles, p. ej. [en, es, fr, pt]. */
    fun languages(ctx: Context): List<String> =
        ctx.assets.list("lang").orEmpty().map { it.removeSuffix(".json") }.sorted()

    /** null hasta que se elige en el onboarding. */
    fun chosen(ctx: Context): String? = prefs(ctx).getString(KEY, null)

    fun choose(ctx: Context, lang: String) = prefs(ctx).edit().putString(KEY, lang).apply()

    fun clear(ctx: Context) = prefs(ctx).edit().remove(KEY).apply()

    /** El idioma del teléfono si está soportado; si no, inglés. */
    fun deviceDefault(ctx: Context): String =
        Locale.getDefault().language.takeIf { it in languages(ctx) } ?: FALLBACK

    fun current(ctx: Context): String = chosen(ctx) ?: deviceDefault(ctx)

    fun json(ctx: Context, lang: String): JSONObject = cache?.takeIf { it.first == lang }?.second
        ?: JSONObject(ctx.assets.open("lang/$lang.json").bufferedReader().use { it.readText() })
            .also { cache = lang to it }

    /** Texto `key` en el idioma `lang` (por defecto el actual); `{n}` se reemplaza por n. */
    fun t(ctx: Context, key: String, n: Int? = null, lang: String = current(ctx)): String =
        json(ctx, lang).getString(key).let { if (n == null) it else it.replace("{n}", n.toString()) }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(ShushService.PREFS, Context.MODE_PRIVATE)
}
