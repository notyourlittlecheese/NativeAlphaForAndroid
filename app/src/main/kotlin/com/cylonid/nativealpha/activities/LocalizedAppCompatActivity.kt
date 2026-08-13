package com.cylonid.nativealpha.activities

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.cylonid.nativealpha.util.AppCompatLocaleDelegate

open class LocalizedAppCompatActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppCompatLocaleDelegate.wrap(newBase))
    }
}
