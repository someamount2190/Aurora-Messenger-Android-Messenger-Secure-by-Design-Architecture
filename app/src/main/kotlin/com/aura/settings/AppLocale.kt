package com.aura.settings

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import com.aura.R
import java.util.Locale

/** In-app language choice. SYSTEM follows the phone's language. */
enum class AppLanguage(val tag: String, @StringRes val labelRes: Int) {
    SYSTEM ("",   R.string.lang_system),
    ENGLISH("en", R.string.lang_native_en),
    RUSSIAN("ru", R.string.lang_native_ru);

    companion object {
        fun fromTag(tag: String?): AppLanguage {
            val lang = tag.orEmpty().substringBefore('-').substringBefore('_')
            return entries.firstOrNull { it.tag == lang } ?: SYSTEM
        }
    }
}

/**
 * Per-app language. Android 13+ keeps the choice in the system ([LocaleManager]), which
 * also lists Aurora under the phone's Settings → Apps → Language and recreates the UI
 * itself. Older versions keep it in prefs and apply it through [wrap] in
 * `attachBaseContext`; there the Activity is recreated on change, and app-context text
 * (notifications) follows on the next launch.
 */
object AppLocale {
    private const val PREFS = "aura_settings"
    private const val KEY_LANGUAGE = "app_language"

    fun current(context: Context): AppLanguage =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
            AppLanguage.fromTag(locales?.takeIf { !it.isEmpty }?.get(0)?.toLanguageTag())
        } else {
            AppLanguage.fromTag(prefs(context).getString(KEY_LANGUAGE, null))
        }

    fun set(activity: Activity, language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (language == AppLanguage.SYSTEM) LocaleList.getEmptyLocaleList()
                else LocaleList.forLanguageTags(language.tag)
        } else {
            prefs(activity).edit().putString(KEY_LANGUAGE, language.tag).apply()
            activity.recreate()
        }
    }

    /** Pre-13 only: a context whose resources use the chosen language. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val language = AppLanguage.fromTag(prefs(base).getString(KEY_LANGUAGE, null))
        if (language == AppLanguage.SYSTEM) return base
        val locale = Locale.forLanguageTag(language.tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(config)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
