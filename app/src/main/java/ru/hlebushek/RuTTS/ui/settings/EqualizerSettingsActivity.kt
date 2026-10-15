package ru.hlebushek.RuTTS.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import com.google.android.material.appbar.MaterialToolbar
import ru.hlebushek.RuTTS.R
import ru.hlebushek.RuTTS.ui.SystemBars

class EqualizerSettingsActivity : AppCompatActivity(R.layout.activity_engine_settings) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SystemBars.applyInsets(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.equalizer_settings_title)
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        toolbar.navigationContentDescription = getString(R.string.navigation_back)
        toolbar.setNavigationOnClickListener { finish() }

        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(R.id.settingsContainer, EqualizerSettingsFragment())
            }
        }
    }

    companion object {
        fun createIntent(context: Context): Intent {
            return Intent(context, EqualizerSettingsActivity::class.java)
        }
    }
}
