package com.spydermahn.nuvioremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {
    private fun release(name: String, assets: String) = """{"tag_name":"NuvioRemote","name":"$name","assets":[$assets]}"""
    private val apk = """{"name":"NuvioRemote.apk","browser_download_url":"https://github.com/o/r/releases/download/NuvioRemote/NuvioRemote.apk"}"""

    @Test
    fun theBuildNumberAndApkAreReadFromTheRelease() {
        val update = AppUpdate.parseRelease(release("Nuvio Remote build 12 (windows-tv-adaptive 1a2b3c4)", apk))
        assertEquals(RemoteUpdate(12, "https://github.com/o/r/releases/download/NuvioRemote/NuvioRemote.apk"), update)
    }

    @Test
    fun aReleaseWithoutABuildNumberOffersNothing() {
        // The title an older workflow (and Spyder's, until it merges this) gives the release.
        assertNull(AppUpdate.parseRelease(release("Nuvio Remote (Spyder a7c9514)", apk)))
    }

    @Test
    fun aReleaseWithoutTheApkOrWithAPlainHttpLinkOffersNothing() {
        assertNull(AppUpdate.parseRelease(release("Nuvio Remote build 3 (x y)", """{"name":"other.zip","browser_download_url":"https://x/y"}""")))
        assertNull(
            AppUpdate.parseRelease(
                release("Nuvio Remote build 3 (x y)", """{"name":"NuvioRemote.apk","browser_download_url":"http://x/NuvioRemote.apk"}"""),
            ),
        )
        assertNull(AppUpdate.parseRelease("not json"))
    }

    @Test
    fun onlyANewerBuildThatWasNotPutOffIsOffered() {
        val update = RemoteUpdate(12, "https://x/NuvioRemote.apk")
        assertTrue(AppUpdate.isWanted(update, installedBuild = 11))
        assertFalse(AppUpdate.isWanted(update, installedBuild = 12))
        assertFalse(AppUpdate.isWanted(update, installedBuild = 13))
        assertFalse(AppUpdate.isWanted(update, installedBuild = 11, dismissed = 12))
        assertTrue(AppUpdate.isWanted(RemoteUpdate(13, update.apkUrl), installedBuild = 11, dismissed = 12))
        assertFalse(AppUpdate.isWanted(null, installedBuild = 1))
    }
}
