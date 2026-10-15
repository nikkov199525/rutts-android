package ru.hlebushek.RuTTS.ui

import android.app.Activity
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding

// С targetSdk 35+ окно всегда рисуется edge-to-edge: android:statusBarColor и
// navigationBarColor игнорируются, а содержимое уходит под системные панели
// (начиная с targetSdk 36 отказаться от этого через тему уже нельзя).
// Возвращаем прежний вид: корневой layout отодвигается от статус-бара
// внешним отступом — сквозь него видна подложка окна цвета
// android:statusBarColor из темы, — а от навигационной панели и выреза
// экрана внутренним отступом, на своём фоне.
object SystemBars {
    fun applyInsets(activity: Activity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) ?: return
        // Атрибут объявлен устаревшим в API 35, но в теме он остаётся единственным
        // источником цвета статус-бара — его же система применяет на API < 35.
        @Suppress("DEPRECATION")
        val attributes = activity.obtainStyledAttributes(intArrayOf(android.R.attr.statusBarColor))
        val statusBarColor = attributes.getColor(0, 0)
        attributes.recycle()
        activity.window.decorView.setBackgroundColor(statusBarColor)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = bars.top }
            view.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }
}
