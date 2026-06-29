package com.example.mutterboard

import java.util.Locale

/**
 * Fuzzy custom-word correction for transcription output, used on the on-device
 * (Parakeet) path where the model itself can't be biased toward the user's
 * vocabulary. After the model produces text, each word — and each 2- and 3-word
 * group — is compared against the user's custom words and swapped in when it's a
 * close enough match.
 *
 * This is a port of Handy's `apply_custom_words` (audio_toolkit/text.rs): it
 * combines Levenshtein edit distance with Soundex phonetic matching, and greedily
 * matches the longest n-gram first so multi-word artifacts ("Charge B" -> "ChargeBee",
 * "mutter board" -> "Mutterboard") collapse into a single custom word. Purely
 * deterministic — same input always yields the same output, no model involved.
 *
 * The cloud path doesn't use this: there the custom words are fed to Whisper as a
 * prompt, which biases recognition directly (see [GroqWhisperClient]).
 */
object TextCorrector {

    /**
     * Maximum combined-score to accept a match (0.0 = exact, 1.0 = anything).
     * Mirrors Handy's `word_correction_threshold` default; low enough that only
     * genuinely close words are replaced.
     */
    const val DEFAULT_THRESHOLD = 0.18

    /** Longest word group we try to collapse into a single custom word. */
    private const val MAX_NGRAM = 3

    /** Skip absurdly long candidates — they can't be a single vocabulary word. */
    private const val MAX_CANDIDATE_LEN = 50

    /**
     * Returns [text] with custom-word corrections applied. [customWords] is the
     * user's vocabulary (original casing kept for the replacement); blank or empty
     * lists return the text untouched.
     */
    fun apply(text: String, customWords: List<String>, threshold: Double = DEFAULT_THRESHOLD): String {
        if (customWords.isEmpty()) return text

        // Spaces removed + lowercased, so an n-gram like "charge b" can match the
        // single custom word "ChargeBee". Index-aligned with [customWords] so the
        // original casing is available when we substitute.
        val customWordsNospace = customWords.map {
            it.lowercase(Locale.ROOT).replace(" ", "")
        }

        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val result = ArrayList<String>(words.size)
        var i = 0

        while (i < words.size) {
            var matched = false

            // Greedy: try the longest n-gram first so multi-word matches win.
            for (n in MAX_NGRAM downTo 1) {
                if (i + n > words.size) continue

                val ngramWords = words.subList(i, i + n)
                val ngram = buildNgram(ngramWords)
                val match = findBestMatch(ngram, customWords, customWordsNospace, threshold)
                    ?: continue

                // Guard against absorbing a neighbouring word: a short adjacent
                // word ("a dilly bean") barely changes the joined string, so the
                // full group can score better than it should. If dropping the
                // first or last word matches just as well or better, that boundary
                // word isn't part of the phrase — fall through to a smaller n.
                if (n >= 2) {
                    val withoutFirst = findBestMatch(
                        buildNgram(words.subList(i + 1, i + n)),
                        customWords, customWordsNospace, threshold
                    )
                    val withoutLast = findBestMatch(
                        buildNgram(words.subList(i, i + n - 1)),
                        customWords, customWordsNospace, threshold
                    )
                    if (withoutFirst != null && withoutFirst.score <= match.score) continue
                    if (withoutLast != null && withoutLast.score <= match.score) continue
                }

                val (prefix, _) = extractPunctuation(ngramWords.first())
                val (_, suffix) = extractPunctuation(ngramWords.last())
                val corrected = preserveCasePattern(ngramWords.first(), match.word)
                result.add("$prefix$corrected$suffix")
                i += n
                matched = true
                break
            }

            if (!matched) {
                result.add(words[i])
                i++
            }
        }

        return result.joinToString(" ")
    }

    /** Strips punctuation from each word, lowercases, and concatenates without spaces. */
    private fun buildNgram(words: List<String>): String =
        words.joinToString("") { word ->
            word.trim { !it.isLetterOrDigit() }.lowercase(Locale.ROOT)
        }

    /** A matched custom word and the (lower-is-better) score it matched at. */
    private data class Match(val word: String, val score: Double)

    /**
     * Finds the closest custom word to [candidate], or null if none beats
     * [threshold]. Phonetic (Soundex) matches are favoured heavily, so a word
     * that *sounds* like a custom word wins over one that merely looks similar.
     */
    private fun findBestMatch(
        candidate: String,
        customWords: List<String>,
        customWordsNospace: List<String>,
        threshold: Double,
    ): Match? {
        if (candidate.isEmpty() || candidate.length > MAX_CANDIDATE_LEN) return null

        var best: String? = null
        var bestScore = Double.MAX_VALUE

        for (idx in customWordsNospace.indices) {
            val target = customWordsNospace[idx]

            // Length guard: at most 25% (min 2 chars) difference, so a short
            // custom word ("openai") isn't pulled in by a longer n-gram ("openaigpt").
            val lenDiff = kotlin.math.abs(candidate.length - target.length).toDouble()
            val maxLen = maxOf(candidate.length, target.length).toDouble()
            val maxAllowedDiff = maxOf(maxLen * 0.25, 2.0)
            if (lenDiff > maxAllowedDiff) continue

            val levenshteinScore = if (maxLen > 0.0) {
                levenshtein(candidate, target) / maxLen
            } else 1.0

            // A phonetic match cuts the score to 30%, strongly favouring it.
            val combined = if (soundexEquals(candidate, target)) {
                levenshteinScore * 0.3
            } else {
                levenshteinScore
            }

            if (combined < threshold && combined < bestScore) {
                best = customWords[idx]
                bestScore = combined
            }
        }

        return best?.let { Match(it, bestScore) }
    }

    /** Applies the original word's case pattern (ALL CAPS / Capitalized) to [replacement]. */
    private fun preserveCasePattern(original: String, replacement: String): String {
        val letters = original.filter { it.isLetter() }
        return when {
            letters.isNotEmpty() && letters.all { it.isUpperCase() } ->
                replacement.uppercase(Locale.ROOT)
            original.firstOrNull { it.isLetter() }?.isUpperCase() == true ->
                replacement.replaceFirstChar { it.uppercaseChar() }
            else -> replacement
        }
    }

    /** Splits a word into its leading and trailing non-alphanumeric runs. */
    private fun extractPunctuation(word: String): Pair<String, String> {
        val prefixEnd = word.indexOfFirst { it.isLetterOrDigit() }
        if (prefixEnd == -1) return "" to "" // no alphanumerics at all
        val suffixStart = word.indexOfLast { it.isLetterOrDigit() } + 1
        return word.substring(0, prefixEnd) to word.substring(suffixStart)
    }

    /** Standard edit distance (insert/delete/substitute), two-row rolling cost. */
    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }

    /** True when two strings share the same Soundex code (i.e. sound alike). */
    private fun soundexEquals(a: String, b: String): Boolean {
        val ca = soundex(a)
        val cb = soundex(b)
        return ca.isNotEmpty() && ca == cb
    }

    /** American Soundex: first letter + 3 consonant-class digits, zero-padded. */
    private fun soundex(input: String): String {
        val s = input.uppercase(Locale.ROOT).filter { it in 'A'..'Z' }
        if (s.isEmpty()) return ""

        val sb = StringBuilder().append(s[0])
        var prev = code(s[0])
        for (i in 1 until s.length) {
            val c = s[i]
            val d = code(c)
            if (d != '0' && d != prev) sb.append(d)
            // 'H'/'W' are transparent (a repeated code across them is still dropped);
            // vowels reset prev to '0' so the same code on either side counts twice.
            if (c != 'H' && c != 'W') prev = d
            if (sb.length >= 4) break
        }
        while (sb.length < 4) sb.append('0')
        return sb.substring(0, 4)
    }

    private fun code(c: Char): Char = when (c) {
        'B', 'F', 'P', 'V' -> '1'
        'C', 'G', 'J', 'K', 'Q', 'S', 'X', 'Z' -> '2'
        'D', 'T' -> '3'
        'L' -> '4'
        'M', 'N' -> '5'
        'R' -> '6'
        else -> '0' // vowels, plus H/W/Y
    }
}
