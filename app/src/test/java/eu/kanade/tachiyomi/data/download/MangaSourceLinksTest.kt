package eu.kanade.tachiyomi.data.download

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class MangaSourceLinksTest {
    @Test
    fun matchRecognizedNumbersAcrossDifferentTitles() {
        val first = Chapter.create().copy(chapterNumber = 12.5, name = "Chapter 12.5")
        val second = Chapter.create().copy(chapterNumber = 12.5, name = "Rozdział 12.5")
        chaptersMatch(first, second) shouldBe true
        chaptersMatch(first, second.copy(chapterNumber = 13.0)) shouldBe false
    }

    @Test
    fun doNotConfuseNumberedAndUnnumberedChapters() {
        val numbered = Chapter.create().copy(chapterNumber = 12.0, name = "Extra")
        val extra = Chapter.create().copy(chapterNumber = -1.0, name = "Extra")
        chaptersMatch(numbered, extra) shouldBe false
    }

    @Test
    fun matchUnnumberedNamesWithoutCaseOrPunctuation() {
        val one = Chapter.create().copy(chapterNumber = -1.0, name = "Special: Episode!")
        val two = Chapter.create().copy(chapterNumber = -1.0, name = "special episode")
        chaptersMatch(one, two) shouldBe true
        chaptersMatch(one, two.copy(name = "Extra chapter")) shouldBe false
    }

    @Test
    fun emptyNamesCannotMatch() {
        val one = Chapter.create().copy(chapterNumber = -1.0, name = "???")
        val two = Chapter.create().copy(chapterNumber = -1.0, name = "")
        chaptersMatch(one, two) shouldBe false
    }
}
