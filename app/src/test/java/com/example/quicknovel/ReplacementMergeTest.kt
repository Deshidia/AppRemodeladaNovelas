package com.example.quicknovel

import com.lagradost.quicknovel.util.ReplaceTiming
import com.lagradost.quicknovel.util.TextReplacement
import com.lagradost.quicknovel.util.mergeReplacementRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplacementMergeTest {
    private fun rule(
        original: String,
        replacement: String,
        timing: ReplaceTiming = ReplaceTiming.AFTER_TRANSLATION
    ) = TextReplacement(original, replacement, timing)

    @Test
    fun addsNewRulesAtTheEndKeepingExistingOrder() {
        val base = listOf(rule("Bell", "Bell Cranel"))
        val incoming = listOf(rule("Ais", "Aiz"), rule("Hestia", "Hestia-sama"))

        val result = mergeReplacementRules(base, incoming)

        assertEquals(base + incoming, result.rules)
        assertEquals(2, result.added)
        assertEquals(0, result.updated)
        assertTrue(result.changed)
    }

    @Test
    fun copyingIntoAnEmptyNovelGivesTheSameRules() {
        val incoming = listOf(rule("Ais", "Aiz"), rule("Rodrgo", "Rodrigo", ReplaceTiming.BEFORE_TRANSLATION))

        val result = mergeReplacementRules(emptyList(), incoming)

        assertEquals(incoming, result.rules)
        assertEquals(2, result.added)
    }

    @Test
    fun updatesInPlaceWhenSameTermAndTimingHaveAnotherReplacement() {
        val base = listOf(rule("Ais", "Ais"), rule("Bell", "Bell Cranel"))

        val result = mergeReplacementRules(base, listOf(rule("Ais", "Aiz")))

        assertEquals(listOf(rule("Ais", "Aiz"), rule("Bell", "Bell Cranel")), result.rules)
        assertEquals(0, result.added)
        assertEquals(1, result.updated)
    }

    @Test
    fun identicalRulesAreNotDuplicatedNorCountedAsChanges() {
        val base = listOf(rule("Ais", "Aiz"))

        val result = mergeReplacementRules(base, listOf(rule("Ais", "Aiz")))

        assertEquals(base, result.rules)
        assertFalse(result.changed)
    }

    @Test
    fun sameTermWithOtherTimingIsADifferentRule() {
        val base = listOf(rule("Ais", "Aiz", ReplaceTiming.AFTER_TRANSLATION))

        val result = mergeReplacementRules(base, listOf(rule("Ais", "Aiz", ReplaceTiming.BEFORE_TRANSLATION)))

        assertEquals(2, result.rules.size)
        assertEquals(1, result.added)
    }

    @Test
    fun repeatedRuleInIncomingOnlyCountsTheFirstOne() {
        // al aplicarse en orden, la segunda ya no encontraría el término original
        val result = mergeReplacementRules(
            emptyList(),
            listOf(rule("Ais", "Aiz"), rule("Ais", "Otra"))
        )

        assertEquals(listOf(rule("Ais", "Aiz")), result.rules)
        assertEquals(1, result.added)
    }

    @Test
    fun rulesWithEmptyOriginalAreIgnored() {
        val result = mergeReplacementRules(emptyList(), listOf(rule("", "x")))

        assertTrue(result.rules.isEmpty())
        assertFalse(result.changed)
    }

    @Test
    fun doesNotModifyTheInputLists() {
        val base = listOf(rule("Bell", "Bell Cranel"))
        val incoming = listOf(rule("Ais", "Aiz"))

        mergeReplacementRules(base, incoming)

        assertEquals(listOf(rule("Bell", "Bell Cranel")), base)
        assertEquals(listOf(rule("Ais", "Aiz")), incoming)
    }
}
