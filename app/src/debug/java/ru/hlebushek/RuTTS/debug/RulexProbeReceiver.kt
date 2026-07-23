package ru.hlebushek.RuTTS.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.nio.charset.Charset
import java.util.Locale
import ru.hlebushek.RuTTS.engine.NativeRulex
import ru.hlebushek.RuTTS.engine.RulexEngine

class RulexProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != actionApply) {
            return
        }

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val encodedText = intent.getStringExtra(extraTextBase64)?.takeIf { it.isNotBlank() }
        val text = encodedText?.let(::decodeBase64Text)
            ?: intent.getStringExtra(extraText)?.takeIf { it.isNotBlank() }
            ?: defaultText
        val mode = intent.getStringExtra(extraMode).orEmpty()
        val returnBase64 = encodedText != null

        scope.launch {
            try {
                val engine = RulexEngine(appContext)
                engine.warmUp()
                val result = if (mode == modeLexbases) {
                    NativeRulex.nativeDebugLexbases(
                        text.lowercase(Locale.ROOT).toByteArray(koi8Charset)
                    ).toString(koi8Charset)
                } else if (mode == modePrefixes) {
                    NativeRulex.nativeDebugPrefixes(
                        text.lowercase(Locale.ROOT).toByteArray(koi8Charset)
                    ).toString(koi8Charset)
                } else {
                    engine.applyToRussianWords(text)
                }
                pendingResult.setResultCode(Activity.RESULT_OK)
                pendingResult.setResultData(
                    if (returnBase64) encodeBase64Text(result) else result
                )
                Log.i(logTag, "text='$text' result='$result'")
            } catch (e: Exception) {
                pendingResult.setResultCode(Activity.RESULT_CANCELED)
                pendingResult.setResultData(e.message ?: e.javaClass.simpleName)
                Log.e(logTag, "Rulex probe failed for text='$text'", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val logTag = "RuTTS.RulexProbe"
        private const val actionApply = "ru.hlebushek.RuTTS.DEBUG_RULEX"
        private const val extraText = "text"
        private const val extraTextBase64 = "text_base64"
        private const val extraMode = "mode"
        private const val defaultText = "Я родился!"
        private const val modeLexbases = "lexbases"
        private const val modePrefixes = "prefixes"
        private val koi8Charset = Charset.forName("KOI8-R")
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private fun decodeBase64Text(value: String): String {
            return Base64.decode(value, Base64.DEFAULT).toString(Charsets.UTF_8)
        }

        private fun encodeBase64Text(value: String): String {
            return Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }
    }
}
