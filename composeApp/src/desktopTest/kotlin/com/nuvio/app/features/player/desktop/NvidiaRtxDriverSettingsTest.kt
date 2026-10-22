package com.nuvio.app.features.player.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class NvidiaRtxDriverSettingsTest {
    // reg.exe output as captured on a 5070 Ti with both NVIDIA App switches on.
    private val bothOn = """

HKEY_LOCAL_MACHINE\SYSTEM\CurrentControlSet\Control\Class\{4d36e968-e325-11ce-bfc1-08002be10318}\0000
    _User_Global_VAL_TrueHDR    REG_DWORD    0x1
    _User_Global_VAL_RTXVideoFlags    REG_DWORD    0x1
    _User_Global_VAL_SuperResolution    REG_DWORD    0x5
    _User_Global_VAL_SuperResolution_Auto    REG_DWORD    0x2
    _User_Global_VAL_TrueHDR_P    REG_DWORD    0x28a

End of search: 5 match(es) found.
"""

    @Test
    fun readsBothSwitches() {
        assertEquals(NvidiaRtxDriverSettings(superResolutionEnabled = true, trueHdrEnabled = true), parseNvidiaRtxDriverSettings(bothOn))
    }

    @Test
    fun zeroIsOffAndSimilarlyNamedValuesAreIgnored() {
        val vsrOff = bothOn.replace("SuperResolution    REG_DWORD    0x5", "SuperResolution    REG_DWORD    0x0")
        assertEquals(NvidiaRtxDriverSettings(superResolutionEnabled = false, trueHdrEnabled = true), parseNvidiaRtxDriverSettings(vsrOff))
    }

    @Test
    fun missingValuesAreUnknown() {
        assertEquals(NvidiaRtxDriverSettings(null, null), parseNvidiaRtxDriverSettings("End of search: 0 match(es) found."))
    }

    @Test
    fun anyAdapterWithTheSwitchOnCounts() {
        val twoAdapters = """
HKEY_LOCAL_MACHINE\...\0000
    _User_Global_VAL_SuperResolution    REG_DWORD    0x0
HKEY_LOCAL_MACHINE\...\0001
    _User_Global_VAL_SuperResolution    REG_DWORD    0x3
"""
        assertEquals(true, parseNvidiaRtxDriverSettings(twoAdapters).superResolutionEnabled)
    }
}
