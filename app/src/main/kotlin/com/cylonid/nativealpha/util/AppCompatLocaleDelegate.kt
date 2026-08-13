package com.cylonid.nativealpha.util

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import java.util.Locale

object AppCompatLocaleDelegate {
    private const val PREFS_NAME = "com.cylonid.nativealpha.LOCALE"
    private const val KEY_LANGUAGE = "language"
    const val LANGUAGE_SYSTEM = "system"
    const val LANGUAGE_ENGLISH = "en"
    const val LANGUAGE_CHINESE_SIMPLIFIED = "zh"

    fun wrap(context: Context): Context {
        val language = getLanguage(context)
        if (language == LANGUAGE_SYSTEM) return context

        val locale = when (language) {
            LANGUAGE_CHINESE_SIMPLIFIED -> Locale.SIMPLIFIED_CHINESE
            else -> Locale.ENGLISH
        }
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    fun getLanguage(context: Context): String {
        return prefs(context).getString(KEY_LANGUAGE, LANGUAGE_SYSTEM) ?: LANGUAGE_SYSTEM
    }

    fun setLanguage(context: Context, language: String) {
        prefs(context).edit().putString(KEY_LANGUAGE, language).apply()
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
