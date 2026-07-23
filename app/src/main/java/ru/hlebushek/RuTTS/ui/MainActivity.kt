package ru.hlebushek.RuTTS.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import ru.hlebushek.RuTTS.R
import ru.hlebushek.RuTTS.ui.settings.RuttsSettingsFragment

class MainActivity : AppCompatActivity(R.layout.activity_engine_settings) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.engine_settings_title)
        toolbar.navigationIcon = null

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settingsContainer, RuttsSettingsFragment())
                .commit()
        }
    }
}
