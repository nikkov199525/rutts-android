# rutts_android_legacy

Тестовое Android-приложение `RuTTS` с package/application id `ru.hlebushek.RuTTS`, созданное отдельно от исходного SAPI-проекта.

## Что перенесено

- `ru_tts` взят из `https://gitverse.ru/kvark128/ru_tts-for-nvda` и собирается как native-библиотека через CMake.
- данные `rulex` взяты из того же репозитория и лежат в `app/src/main/assets/rulex`.
- логика подготовки текста, посимвольного чтения, параметров скорости/громкости, интерполяции и конфигуратора перенесена из текущего проекта и адаптирована под Android.
- исходный проект в корне рабочей папки не изменяется.
- минимальная версия Android: 11 (`minSdk = 30`).

## Как устроено

- UI: Kotlin + `PreferenceFragmentCompat`
- synth: `app/src/main/java/ru/hlebushek/RuTTS/ui/synth`
- настройки: `app/src/main/java/ru/hlebushek/RuTTS/ui/settings`
- native: `app/src/main/cpp`
- локальный `rulex` coder: `app/src/main/cpp/third_party/rulex`
- upstream `ru_tts`: `app/src/main/cpp/third_party/ru_tts`
- `libsamplerate`: `app/src/main/cpp/third_party/libsamplerate`

## Настройки

- параметры отображаются списком
- одиночный тап открывает диалог изменения значения
- числовые параметры редактируются через numeric-only поле
- параметры выбора открываются списком вариантов
- словари редактируются отдельным экраном со списком записей

## Важное замечание по rulex

Оригинальный `rulex` upstream рассчитан на Berkeley DB (`rulexdb`). В этой Android-версии я использую upstream-данные `rulex` и совместимую логику поиска/правил на стороне Kotlin, чтобы не тащить Berkeley DB в тестовый APK.

## Сборка

Требуется:

- Android Studio Hedgehog+ / Koala+ с Android SDK
- JBR/JDK из Android Studio (проверено на встроенном JBR 21)
- Android NDK, совместимый с AGP 8.5.x

Открыть папку `rutts_android_legacy` как отдельный Android-проект и выполнить обычную сборку `app`.

- `local.properties` в проект специально не хранится: Android Studio должна создать его под SDK конкретной машины.
- зависимости Gradle/Maven подтягиваются обычным способом через Android Studio или стандартный Gradle user home на целевой машине.

## Статус

- проект создан отдельно в `rutts_android_legacy`
- пакет/namespace переведён на `ru.hlebushek.RuTTS`
- Gradle wrapper добавлен
- release-сборка проверена локально
