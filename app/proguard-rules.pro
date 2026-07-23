-keep class ru.hlebushek.RuTTS.engine.RuttsTextToSpeechService { *; }
-keep class ru.hlebushek.RuTTS.ui.settings.EngineDataActivity { *; }
-keep class **$NativeAudioSink { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
