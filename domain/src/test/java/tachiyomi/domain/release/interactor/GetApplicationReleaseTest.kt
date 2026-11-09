package tachiyomi.domain.release.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.ReleaseService

class GetApplicationReleaseTest {

    private lateinit var getApplicationRelease: GetApplicationRelease
    private lateinit var releaseService: ReleaseService

    @BeforeEach
    fun beforeEach() {
        releaseService = mockk()

        getApplicationRelease = GetApplicationRelease(releaseService)
    }

    @Test
    fun `When has update but is nightly expect new update`() = runTest {
        val release = Release(
            "r2000",
            "info",
            "http://example.com/release_link",
            "http://example.com/release_link.apk",
        )

        coEvery { releaseService.latest(any()) } returns release

        val result = getApplicationRelease.await(
            GetApplicationRelease.Arguments(
                isFoss = false,
                isNightly = true,
                commitCount = 1000,
                versionName = "",
                repository = "test",
            ),
        )

        (result as GetApplicationRelease.Result.NewUpdate).release shouldBe GetApplicationRelease.Result.NewUpdate(
            release,
        ).release
    }

    @Test
    fun `When has update expect new update`() = runTest {
        val release = Release(
            "v2.0.0",
            "info",
            "http://example.com/release_link",
            "http://example.com/release_link.apk",
        )

        coEvery { releaseService.latest(any()) } returns release

        val result = getApplicationRelease.await(
            GetApplicationRelease.Arguments(
                isFoss = false,
                isNightly = false,
                commitCount = 0,
                versionName = "v1.0.0",
                repository = "test",
            ),
        )

        (result as GetApplicationRelease.Result.NewUpdate).release shouldBe GetApplicationRelease.Result.NewUpdate(
            release,
        ).release
    }

    @Test
    fun `When has no update expect no new update`() = runTest {
        val release = Release(
            "v1.0.0",
            "info",
            "http://example.com/release_link",
            "http://example.com/release_link.apk",
        )

        coEvery { releaseService.latest(any()) } returns release

        val result = getApplicationRelease.await(
            GetApplicationRelease.Arguments(
                isFoss = false,
                isNightly = false,
                commitCount = 0,
                versionName = "v2.0.0",
                repository = "test",
            ),
        )

        result shouldBe GetApplicationRelease.Result.NoNewUpdate
    }
    @Test
    fun `Yomira preview with greater build number is a new update`() = runTest {
        coEvery { releaseService.latest(any()) } returns Release(
            "v0.21.3-yomira-preview.51",
            "info",
            "https://example.com/release",
            "https://example.com/update.apk",
        )

        val result = getApplicationRelease.await(
            GetApplicationRelease.Arguments(
                isFoss = false,
                isNightly = false,
                commitCount = 0,
                versionName = "0.21.3-yomira-preview.50",
                repository = "EnterTG/mihon",
            ),
        )

        (result is GetApplicationRelease.Result.NewUpdate) shouldBe true
    }

    @Test
    fun `Installed Yomira preview does not update to itself or an older preview`() = runTest {
        for (tag in listOf("v0.21.3-yomira-preview.50", "v0.21.3-yomira-preview.49")) {
            coEvery { releaseService.latest(any()) } returns Release(tag, "info", "link", "apk")

            val result = getApplicationRelease.await(
                GetApplicationRelease.Arguments(
                    isFoss = false,
                    isNightly = false,
                    commitCount = 0,
                    versionName = "0.21.3-yomira-preview.50",
                    repository = "EnterTG/mihon",
                ),
            )

            result shouldBe GetApplicationRelease.Result.NoNewUpdate
        }
    }

    @Test
    fun `Version precedence compares major before minor and patch`() = runTest {
        coEvery { releaseService.latest(any()) } returns Release("v1.99.99", "info", "link", "apk")

        val result = getApplicationRelease.await(
            GetApplicationRelease.Arguments(
                isFoss = false,
                isNightly = false,
                commitCount = 0,
                versionName = "2.0.0",
                repository = "test",
            ),
        )

        result shouldBe GetApplicationRelease.Result.NoNewUpdate
    }

}
