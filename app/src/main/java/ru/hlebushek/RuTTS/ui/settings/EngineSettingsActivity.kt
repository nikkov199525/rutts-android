package ru.hlebushek.RuTTS.ui.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import ru.hlebushek.RuTTS.R

class EngineSettingsActivity : AppCompatActivity(R.layout.activity_engine_settings) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.engine_settings_title)
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        toolbar.navigationContentDescription = getString(R.string.navigation_back)
        toolbar.setNavigationOnClickListener { finish() }

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settingsContainer, RuttsSettingsFragment())
                .commit()
        }
    }
}
