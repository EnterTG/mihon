package eu.kanade.tachiyomi.data.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.domain.chapter.model.Chapter

class MangaSourceLinksTest {
    @Test
    fun matchRecognizedNumbersAcrossDifferentTitles() {
        val first = Chapter.create().copy(chapterNumber = 12.5, name = "Chapter 12.5")
        val second = Chapter.create().copy(chapterNumber = 12.5, name = "Rozdział 12.5")
        assertTrue(chaptersMatch(first, second))
        assertFalse(chaptersMatch(first, second.copy(chapterNumber = 13.0)))
    }

    @Test
    fun doNotConfuseNumberedAndUnnumberedChapters() {
        val numbered = Chapter.create().copy(chapterNumber = 12.0, name = "Extra")
        val extra = Chapter.create().copy(chapterNumber = -1.0, name = "Extra")
        assertFalse(chaptersMatch(numbered, extra))
    }

    @Test
    fun matchUnnumberedNamesWithoutCaseOrPunctuation() {
        val one = Chapter.create().copy(chapterNumber = -1.0, name = "Special: Episode!")
        val two = Chapter.create().copy(chapterNumber = -1.0, name = "special episode")
        assertTrue(chaptersMatch(one, two))
        assertFalse(chaptersMatch(one, two.copy(name = "Extra chapter")))
    }

    @Test
    fun emptyNamesCannotMatch() {
        val one = Chapter.create().copy(chapterNumber = -1.0, name = "???")
        val two = Chapter.create().copy(chapterNumber = -1.0, name = "")
        assertFalse(chaptersMatch(one, two))
    }
}
