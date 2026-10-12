package com.lagradost.quicknovel

import com.lagradost.quicknovel.ui.result.remapReadMarks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadMarksRemapTest {
    private val folder = "reader_epub_position_read"

    private val all: Map<String, Any?> = mapOf(
        "$folder/Foo/0" to "111",
        "$folder/Foo/5" to "555",
        "$folder/Foo/120" to "1200",
        "$folder/Foo/2/7" to "otra-novela", // novela llamada "Foo/2", capítulo 7
        "$folder/Foo/abc" to "no-indice",
        "$folder/Foo/" to "vacio",
        "$folder/Bar/1" to "bar",
        "reader_epub_position/Foo" to "3", // otra carpeta
        "$folder/Foo/9" to 42L, // valor que no es String
    )

    @Test
    fun copiesOnlyChapterIndexesOfTheSourceNovel() {
        val result = remapReadMarks(all, "$folder/Foo/", "$folder/Baz/")
        assertEquals(
            setOf("$folder/Baz/0", "$folder/Baz/5", "$folder/Baz/120"),
            result.keys
        )
        // el valor JSON ya guardado se copia tal cual
        assertEquals("555", result["$folder/Baz/5"])
    }

    @Test
    fun doesNotPickUpANovelWhoseNameStartsWithTheSameText() {
        val result = remapReadMarks(all, "$folder/Foo/", "$folder/Baz/")
        assertTrue(result.keys.none { it.endsWith("/7") })
    }

    @Test
    fun sameNovelOrNoMarksCopiesNothing() {
        assertTrue(remapReadMarks(all, "$folder/Foo/", "$folder/Foo/").isEmpty())
        assertTrue(remapReadMarks(emptyMap<String, Any?>(), "$folder/Foo/", "$folder/Baz/").isEmpty())
    }
}
