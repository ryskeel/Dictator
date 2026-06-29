package com.example.mutterboard

import org.junit.Assert.assertEquals
import org.junit.Test

class TextCorrectorTest {

    @Test
    fun emptyVocabularyLeavesTextUntouched() {
        val text = "push this to the mutter board tonight"
        assertEquals(text, TextCorrector.apply(text, emptyList()))
    }

    @Test
    fun correctsMisheardSingleWord() {
        // "Claud" -> "Claude" (one edit, also a phonetic match).
        val out = TextCorrector.apply("ask claud about it", listOf("Claude"))
        assertEquals("ask Claude about it", out)
    }

    @Test
    fun collapsesMultiWordArtifactIntoOneCustomWord() {
        // The headline n-gram case: two spoken words become one vocabulary word.
        val out = TextCorrector.apply("push it to the mutter board", listOf("Mutterboard"))
        assertEquals("push it to the Mutterboard", out)
    }

    @Test
    fun preservesSurroundingPunctuation() {
        val out = TextCorrector.apply("thanks claud!", listOf("Claude"))
        assertEquals("thanks Claude!", out)
    }

    @Test
    fun preservesAllCapsPattern() {
        val out = TextCorrector.apply("i use GROK daily", listOf("Groq"))
        assertEquals("i use GROQ daily", out)
    }

    @Test
    fun leavesUnrelatedWordsAlone() {
        // "the" must not get pulled toward an unrelated short custom word.
        val out = TextCorrector.apply("the cat sat", listOf("Claude"))
        assertEquals("the cat sat", out)
    }

    @Test
    fun recognizesMultiWordPhraseSpokenAsTwoWords() {
        // "Dilly Bean" stored with a space; spoken as two words, corrected back
        // to the original (space preserved).
        val out = TextCorrector.apply("i got a dilly bean today", listOf("Dilly Bean"))
        assertEquals("i got a Dilly Bean today", out)
    }

    @Test
    fun correctsMisheardMultiWordPhrase() {
        // Phonetic + edit-distance match across the 2-word group.
        val out = TextCorrector.apply("grab a dily been", listOf("Dilly Bean"))
        assertEquals("grab a Dilly Bean", out)
    }

    @Test
    fun doesNotOvermatchOnLengthMismatch() {
        // A long n-gram should not collapse into a much shorter custom word.
        val out = TextCorrector.apply("openai gpt model", listOf("openai"))
        assertEquals("openai gpt model", out)
    }

    @Test
    fun doesNotSnapPhoneticallyCloseNgramThatLooksNothingAlike() {
        // Real-world false positive: "floats on" joins to "floatson", which shares
        // the coarse Soundex skeleton (F432) of "Fleetio Go" but differs in ~56% of
        // its characters. The phonetic boost must not rewrite it.
        val out = TextCorrector.apply("floats on your screen", listOf("Fleetio Go"))
        assertEquals("floats on your screen", out)
    }

    @Test
    fun stillCollapsesGenuineMultiWordArtifact() {
        // Guard the fix doesn't over-correct: a close 2-word match still collapses.
        val out = TextCorrector.apply("push it to charge b now", listOf("ChargeBee"))
        assertEquals("push it to ChargeBee now", out)
    }
}
