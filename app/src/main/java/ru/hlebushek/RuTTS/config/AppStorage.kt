package ru.hlebushek.RuTTS.config

import android.content.Context
import android.os.Build
import android.os.UserManager
import androidx.preference.PreferenceManager

// Единый источник хранилища настроек для всего приложения.
//
// В Direct Boot (после включения устройства, до первой разблокировки)
// доступно ТОЛЬКО device-protected хранилище — credential-encrypted ещё
// заблокировано. Чтобы движок мог говорить на локскрине сразу после
// перезагрузки, все настройки и кэши держим в device-protected. UI и сервис
// обязаны читать одно и то же хранилище, иначе правки из настроек не
// применятся к синтезу — поэтому доступ к SharedPreferences идёт только
// через preferenceContext(), а PreferenceFragment'ы включают
// setStorageDeviceProtected().
object AppStorage {
    private const val MIGRATED_FLAG = "storage_migrated_to_device_protected"

    // Контекст, чьи SharedPreferences и файловые каталоги лежат в
    // device-protected хранилище (API 24+). До API 24 Direct Boot не
    // существует — возвращаем обычный контекст приложения.
    fun preferenceContext(context: Context): Context {
        val app = context.applicationContext
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            app.createDeviceProtectedStorageContext() ?: app
        } else {
            app
        }
    }

    // Однократный перенос уже сохранённых настроек из credential-хранилища в
    // device-protected. Вызывать из Application.onCreate ДО первого чтения
    // настроек. moveSharedPreferencesFrom сначала УДАЛЯЕТ файл-приёмник, потом
    // переносит источник, поэтому запускать строго один раз — иначе повторный
    // вызов (когда источник уже пуст) сотрёт перенесённые настройки. Признак
    // выполнения храним в самом device-protected хранилище.
    //
    // В Direct Boot (пользователь ещё не разблокировал) credential-хранилище
    // недоступно — тогда пропускаем и повторим при следующем запуске после
    // разблокировки. Существующие установки всегда открывались хотя бы раз
    // после обновления (разблокированными), так что миграция успевает
    // отработать до того, как сервис впервые стартует на локскрине.
    fun migrateToDeviceProtected(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return
        }
        val app = context.applicationContext
        val deContext = app.createDeviceProtectedStorageContext() ?: return
        if (PreferenceManager.getDefaultSharedPreferences(deContext).getBoolean(MIGRATED_FLAG, false)) {
            return
        }
        val userManager = app.getSystemService(Context.USER_SERVICE) as? UserManager
        if (userManager != null && !userManager.isUserUnlocked) {
            return
        }
        deContext.moveSharedPreferencesFrom(app, defaultPreferencesName(app))
        PreferenceManager.getDefaultSharedPreferences(deContext)
            .edit()
            .putBoolean(MIGRATED_FLAG, true)
            .apply()
    }

    private fun defaultPreferencesName(context: Context): String {
        return context.packageName + "_preferences"
    }
}
