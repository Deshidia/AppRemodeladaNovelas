package com.example.quicknovel

import com.lagradost.quicknovel.ui.result.filterNovelNames
import org.junit.Assert.assertEquals
import org.junit.Test

class NovelPickerFilterTest {
    private val library = listOf(
        "DanMachi",
        "Sword Oratoria",
        "Re:Zero kara Hajimeru Isekai Seikatsu",
        "Órfeo y Eurídice",
    )

    @Test
    fun emptyQueryReturnsEverything() {
        assertEquals(library, filterNovelNames(library, ""))
        assertEquals(library, filterNovelNames(library, "   "))
    }

    @Test
    fun ignoresCaseAndPartialMatches() {
        assertEquals(listOf("Sword Oratoria"), filterNovelNames(library, "oRATor"))
    }

    @Test
    fun ignoresAccentsInBothDirections() {
        assertEquals(listOf("Órfeo y Eurídice"), filterNovelNames(library, "orfeo"))
        assertEquals(listOf("Órfeo y Eurídice"), filterNovelNames(library, "euridice"))
        assertEquals(listOf("Órfeo y Eurídice"), filterNovelNames(library, "Órfeo"))
    }

    @Test
    fun everyWordMustAppearInAnyOrder() {
        assertEquals(listOf("Sword Oratoria"), filterNovelNames(library, "oratoria sword"))
        assertEquals(emptyList<String>(), filterNovelNames(library, "sword machi"))
    }

    @Test
    fun noMatchGivesEmptyList() {
        assertEquals(emptyList<String>(), filterNovelNames(library, "naruto"))
    }

    @Test
    fun keepsTheOriginalOrder() {
        assertEquals(
            listOf("DanMachi", "Sword Oratoria", "Re:Zero kara Hajimeru Isekai Seikatsu"),
            filterNovelNames(library, "a")
        )
    }
}
