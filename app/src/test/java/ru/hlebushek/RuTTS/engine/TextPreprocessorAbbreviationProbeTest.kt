package ru.hlebushek.RuTTS.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class TextPreprocessorAbbreviationProbeTest {
    @Test
    fun splitsCamelCaseSoInnerAbbreviationIsSpelled() {
        assertEquals("chat GPT", TextPreprocessor.splitCamelCase("chatGPT"))
        assertEquals("Ru TTS", TextPreprocessor.splitCamelCase("RuTTS"))
        assertEquals("my PC", TextPreprocessor.splitCamelCase("myPC"))
        // ЗАГЛАВНЫЕ -> Заглавная+строчная тоже граница.
        assertEquals("TTS Engine", TextPreprocessor.splitCamelCase("TTSEngine"))
        // Разрезаем только внутри слова.
        assertEquals("открой chat GPT", TextPreprocessor.splitCamelCase("открой chatGPT"))
    }

    @Test
    fun leavesOrdinaryWordsAndBrandsUntouched() {
        // Бренды и обычный текст не должны попадать в пайплайн.
        assertUntouched("POCO", "OZON", "Store", "GIF", "gif")
        assertUntouched("ВНИМАНИЕ", "МОСКВА", "ОШИБКА", "НАТО", "ВУЗ", "ЗАГС", "ГОСТ")
        assertUntouched("ОК", "ДА", "НЕТ", "ВСЕ", "ЕЩЁ", "США")
        assertUntouched("Здравствуйте", "привет", "Москва")
        assertUntouched("try", "sky", "why", "gym")
    }

    @Test
    fun spellsConsonantOnlyRuns() {
        assertSpelled("TTS", "GPT", "ФСБ", "МВД", "СНГ", "СССР", "ЖКХ", "HTML", "PNG", "JPG")
    }

    private fun assertUntouched(vararg runs: String) {
        runs.forEach { run ->
            assertEquals("$run не должно попадать в пайплайн", run, TextPreprocessor.splitCamelCase(run))
            assertEquals("$run должно читаться словом", false, TextPreprocessor.shouldSpellRun(run))
        }
    }

    private fun assertSpelled(vararg runs: String) {
        runs.forEach { run ->
            assertEquals("$run должно читаться по буквам", true, TextPreprocessor.shouldSpellRun(run))
        }
    }
}
