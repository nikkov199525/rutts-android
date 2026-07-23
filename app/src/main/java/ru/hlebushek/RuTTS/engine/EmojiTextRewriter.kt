package ru.hlebushek.RuTTS.engine

import android.content.Context

class EmojiTextRewriter(context: Context?) {
    private val appContext: Context? = context?.applicationContext ?: context

    @Volatile
    private var trie: EmojiTrie? = null

    fun rewrite(text: String, enabled: Boolean): String {
        if (text.isEmpty() || !enabled) {
            return text
        }

        val local = trie ?: synchronized(this) {
            trie ?: EmojiTrie(EmojiAssetLoader.load(appContext)).also { trie = it }
        }
        return local.rewrite(text)
    }

    private class EmojiTrie(names: Map<String, String>) {
        private val root = Node()

        init {
            names.forEach { (key, value) -> add(key, value) }
        }

        fun rewrite(text: String): String {
            val out = StringBuilder(text.length)
            var index = 0
            while (index < text.length) {
                val match = find(text, index)
                if (match != null) {
                    appendReadable(out, match.value)
                    index = match.end
                    continue
                }

                val cp = text.codePointAt(index)
                if (isEmojiLike(cp)) {
                    appendSeparatedCodePoint(out, cp)
                } else {
                    out.appendCodePoint(cp)
                }
                index += Character.charCount(cp)
            }
            return out.toString()
        }

        private fun add(key: String, value: String) {
            if (key.isEmpty() || value.isBlank()) {
                return
            }

            var node = root
            var index = 0
            while (index < key.length) {
                val cp = key.codePointAt(index)
                node = node.children.getOrPut(cp) { Node() }
                index += Character.charCount(cp)
            }
            node.value = value.trim()
        }

        private fun find(text: String, start: Int): Match? {
            var node = root
            var bestValue: String? = null
            var bestEnd = -1
            var index = start
            while (index < text.length) {
                val cp = text.codePointAt(index)
                node = node.children[cp] ?: break
                index += Character.charCount(cp)
                if (node.value != null) {
                    bestValue = node.value
                    bestEnd = index
                }
            }
            return bestValue?.let { Match(bestEnd, it) }
        }
    }

    private class Node {
        val children = HashMap<Int, Node>()
        var value: String? = null
    }

    private data class Match(val end: Int, val value: String)

    private companion object {
        fun appendReadable(out: StringBuilder, value: String) {
            if (out.isNotEmpty() && !Character.isWhitespace(out[out.length - 1])) {
                out.append(' ')
            }
            out.append(value).append(' ')
        }

        fun appendSeparatedCodePoint(out: StringBuilder, cp: Int) {
            if (out.isNotEmpty() && !Character.isWhitespace(out[out.length - 1])) {
                out.append(' ')
            }
            out.appendCodePoint(cp).append(' ')
        }

        fun isEmojiLike(cp: Int): Boolean {
            return cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF
        }
    }
}
