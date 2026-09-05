package com.nuvio.app.features.updater

import com.nuvio.app.core.build.PackagedBuild
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateChannelTest {
    @Test
    fun `stable takes the newest published release and never the nightly`() {
        assertTrue(stableRelease("v1.13").matchesChannel(UpdateChannel.Stable))
        assertFalse(nightlyRelease().matchesChannel(UpdateChannel.Stable))
        assertFalse(stableRelease("v1.13").copy(draft = true).matchesChannel(UpdateChannel.Stable))
    }

    @Test
    fun `nightly is matched by its tag even if the prerelease flag is dropped`() {
        assertTrue(nightlyRelease().matchesChannel(UpdateChannel.Nightly))
        // Un-ticking "set as a pre-release" must not silently move the nightly into stable.
        val flagless = nightlyRelease().copy(prerelease = false)
        assertTrue(flagless.matchesChannel(UpdateChannel.Nightly))
        assertFalse(flagless.matchesChannel(UpdateChannel.Stable))
    }

    @Test
    fun `releases cut from another branch belong to no channel`() {
        val foreign = stableRelease("v1.13").copy(targetCommitish = "some-other-branch")
        assertFalse(foreign.matchesChannel(UpdateChannel.Stable))
        assertFalse(foreign.matchesChannel(UpdateChannel.Nightly))
    }

    @Test
    fun `nightly is offered only when its build differs from the installed one`() {
        val nightly = update(UpdateChannel.Nightly, tag = "Nightly", sha = "a".repeat(64))

        // Same build already installed: the rolling tag and unchanged version name would both say
        // "newer" forever, so the asset identity is what has to answer.
        assertFalse(offered(nightly, installedNightlyId = "a".repeat(64)))
        assertTrue(offered(nightly, installedNightlyId = "b".repeat(64)))
        // No nightly installed at all — the user just switched channels.
        assertTrue(offered(nightly, installedNightlyId = null))
    }

    @Test
    fun `nightly re-upload under the same tag is offered again`() {
        val installed = update(UpdateChannel.Nightly, tag = "Nightly", sha = "a".repeat(64))
        val reupload = installed.copy(assetSha256 = "c".repeat(64))

        assertFalse(offered(installed, installedNightlyId = installed.buildId))
        assertTrue(offered(reupload, installedNightlyId = installed.buildId))
    }

    @Test
    fun `stable uses version comparison for stable installs`() {
        val stable = update(UpdateChannel.Stable, tag = "v1.13")

        assertTrue(offered(stable, localVersion = "1.12", installedNightlyId = null))
        assertFalse(offered(stable, localVersion = "1.13.0", installedNightlyId = null))
        assertFalse(offered(stable, localVersion = "1.14.0", installedNightlyId = null))
    }

    @Test
    fun `a nightly user can always get back to stable`() {
        // The nightly is ahead of stable, so no version comparison would ever offer the way back.
        val stable = update(UpdateChannel.Stable, tag = "v1.13")

        assertTrue(offered(stable, localVersion = "1.14.0", installedNightlyId = "a".repeat(64)))
        assertTrue(UpdateAvailability.isChannelSwitch(stable, installedNightlyId = "a".repeat(64)))
        assertFalse(UpdateAvailability.isChannelSwitch(stable, installedNightlyId = null))
    }

    @Test
    fun `moving onto nightly counts as a channel switch`() {
        val nightly = update(UpdateChannel.Nightly, tag = "Nightly", sha = "a".repeat(64))

        assertTrue(UpdateAvailability.isChannelSwitch(nightly, installedNightlyId = null))
        assertFalse(UpdateAvailability.isChannelSwitch(nightly, installedNightlyId = "b".repeat(64)))
    }

    @Test
    fun `ignoring one nightly does not mute the tag`() {
        val first = update(UpdateChannel.Nightly, tag = "Nightly", sha = "a".repeat(64))
        val second = first.copy(assetSha256 = "b".repeat(64))

        assertEquals("v1.13", update(UpdateChannel.Stable, tag = "v1.13").ignoreKey())
        assertTrue(first.ignoreKey() != second.ignoreKey())
    }

    @Test
    fun `build identity falls back to the upload time when no digest is published`() {
        val undigested = update(UpdateChannel.Nightly, tag = "Nightly", sha = null)
            .copy(publishedAt = "2026-08-22T07:26:13Z")

        assertEquals("2026-08-22T07:26:13Z", undigested.buildId)
        assertEquals("2026-08-22", InstalledNightlyBuild(id = "x", publishedAt = "2026-08-22T07:26:13Z").label)
    }

    @Test
    fun `a self-built nightly is not offered the published one it is already ahead of`() {
        // No marker: only the updater writes one, and the launch-time reconciliation drops any a
        // hand-installed image inherited. Without the build stamp this was re-offered every launch.
        val nightly = publishedNightly(at = "2026-08-22T07:26:13Z")

        assertFalse(offered(nightly, installedNightlyId = null, runningBuild = selfBuilt("2026-08-28T10:15:00Z")))
        // Level with it — the same build, packaged here — counts as already having it.
        assertFalse(offered(nightly, installedNightlyId = null, runningBuild = selfBuilt("2026-08-22T07:26:13Z")))
        // A second behind, and the published nightly really is the newer build.
        assertTrue(offered(nightly, installedNightlyId = null, runningBuild = selfBuilt("2026-08-22T07:26:12Z")))
    }

    @Test
    fun `only a nightly-stamped image can outrank the published nightly`() {
        val nightly = publishedNightly(at = "2026-08-22T07:26:13Z")
        val builtLater = "2026-08-28T10:15:00Z"

        // Packaged for stable, so switching onto the nightly channel is still an offer worth making.
        assertTrue(offered(nightly, installedNightlyId = null, runningBuild = selfBuilt(builtLater, channel = "stable")))
        assertTrue(offered(nightly, installedNightlyId = null, runningBuild = selfBuilt(builtLater, channel = null)))
        // `gradlew run`: no app image, so no stamp to rank anything by.
        assertTrue(offered(nightly, installedNightlyId = null, runningBuild = null))
    }

    @Test
    fun `an unreadable timestamp on either side leaves the old behaviour alone`() {
        val nightly = publishedNightly(at = "2026-08-22T07:26:13Z")

        assertTrue(offered(nightly, installedNightlyId = null, runningBuild = selfBuilt(null)))
        assertTrue(offered(nightly, installedNightlyId = null, runningBuild = selfBuilt("not a timestamp")))
        // A local offset can't be compared against a UTC one without a timezone library.
        assertTrue(offered(nightly, installedNightlyId = null, runningBuild = selfBuilt("2026-08-28T10:15:00+02:00")))
        assertTrue(
            offered(
                nightly.copy(publishedAt = null),
                installedNightlyId = null,
                runningBuild = selfBuilt("2026-08-28T10:15:00Z"),
            ),
        )
    }

    @Test
    fun `the build stamp never talks an updater-installed nightly out of an update`() {
        // Marked installs keep the asset-identity rule: a newer nightly published after this image
        // was packaged is still offered, and the stamp gets no say in it.
        val nightly = publishedNightly(at = "2026-08-22T07:26:13Z")

        assertTrue(
            offered(nightly, installedNightlyId = "b".repeat(64), runningBuild = selfBuilt("2026-08-28T10:15:00Z")),
        )
        assertFalse(
            offered(nightly, installedNightlyId = nightly.buildId, runningBuild = selfBuilt("2026-08-28T10:15:00Z")),
        )
    }

    @Test
    fun `a self-built nightly being updated is not labelled a channel switch`() {
        val nightly = publishedNightly(at = "2026-08-22T07:26:13Z")

        assertFalse(UpdateAvailability.isChannelSwitch(nightly, installedNightlyId = null, runningBuild = selfBuilt("2026-08-22T07:26:12Z")))
        // Packaged for stable, so this offer really does move channels.
        assertTrue(
            UpdateAvailability.isChannelSwitch(
                nightly,
                installedNightlyId = null,
                runningBuild = selfBuilt("2026-08-22T07:26:12Z", channel = "stable"),
            ),
        )
    }

    @Test
    fun `utc timestamps are recognised by shape and compared at second precision`() {
        assertEquals("2026-08-22T07:26:13", utcInstantOrNull("2026-08-22T07:26:13Z"))
        assertEquals("2026-08-22T07:26:13", utcInstantOrNull("  2026-08-22T07:26:13.482Z  "))
        assertNull(utcInstantOrNull(null))
        assertNull(utcInstantOrNull("2026-08-22 07:26:13"))
        assertNull(utcInstantOrNull("2026-08-22T07:26:13"))
        assertNull(utcInstantOrNull("2026-08-22T07:26:13+00:00"))
    }

    private fun offered(
        update: AppUpdate,
        localVersion: String = "1.14.0",
        installedNightlyId: String?,
        runningBuild: PackagedBuild? = null,
    ) = UpdateAvailability.isOffered(update, localVersion, installedNightlyId, runningBuild)

    private fun selfBuilt(
        buildTime: String?,
        channel: String? = "nightly",
    ) = PackagedBuild(id = "20260828-101500", buildTime = buildTime, channel = channel)

    private fun update(channel: UpdateChannel, tag: String, sha: String? = null) = AppUpdate(
        channel = channel,
        tag = tag,
        title = tag,
        notes = "",
        releaseUrl = null,
        assetName = "Nuvio.zip",
        assetUrl = "https://example.test/Nuvio.zip",
        assetSizeBytes = null,
        assetSha256 = sha,
    )

    private fun stableRelease(tag: String) = GitHubReleaseDto(
        tagName = tag,
        name = tag,
        prerelease = false,
        targetCommitish = "windows-tv-adaptive",
    )

    private fun publishedNightly(at: String) =
        update(UpdateChannel.Nightly, tag = "Nightly", sha = "a".repeat(64)).copy(publishedAt = at)

    private fun nightlyRelease() = GitHubReleaseDto(
        tagName = "Nightly",
        name = "Nightly Testing",
        prerelease = true,
        targetCommitish = "windows-tv-adaptive",
    )
}
