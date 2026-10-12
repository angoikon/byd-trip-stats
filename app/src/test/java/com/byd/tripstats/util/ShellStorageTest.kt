package com.byd.tripstats.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [shouldTestShellStorage] — when a dispatch's output makes the app check whether
 * the shell can write /data/local/tmp, which only a Shark 6 has ever failed.
 */
class ShellStorageTest {

    /** What a Shark 6 printed on every dispatch (diag.log 2026-08-31 … 10-05, untruncated). */
    private val sharkOutput =
        "OK\n/system/bin/sh: can't create /data/local/tmp/.supd.log: Permission denied"

    @Test
    fun `a DiLink 5 dispatch that couldn't create its file is tested`() {
        assertTrue(shouldTestShellStorage(isDiLink5 = true, relocated = false, dispatchOutput = sharkOutput))
    }

    @Test
    fun `a healthy dispatch is never tested`() {
        // Every Sealion 7 and DiLink-3 dispatch prints just OK.
        assertFalse(shouldTestShellStorage(isDiLink5 = true, relocated = false, dispatchOutput = "OK"))
        assertFalse(shouldTestShellStorage(isDiLink5 = true, relocated = false, dispatchOutput = ""))
    }

    @Test
    fun `DiLink 3 is never tested, whatever its dispatch says`() {
        assertFalse(shouldTestShellStorage(isDiLink5 = false, relocated = false, dispatchOutput = sharkOutput))
    }

    @Test
    fun `once moved, it is never tested again`() {
        assertFalse(shouldTestShellStorage(isDiLink5 = true, relocated = true, dispatchOutput = sharkOutput))
    }

    @Test
    fun `a failure anywhere else is not this one`() {
        val other = "OK\n/system/bin/sh: can't create /sdcard/x.log: Permission denied"
        assertFalse(shouldTestShellStorage(isDiLink5 = true, relocated = false, dispatchOutput = other))
    }

    @Test
    fun `the default folder is unchanged`() {
        assertEquals("/data/local/tmp", ShellStorage.LEGACY_DIR)
    }

    @Test
    fun `the moved folder is in the shell user's own data folder`() {
        // shell_data_file there, verified writable and executable on the Shark (2026-10-12).
        assertTrue(ShellStorage.RELOCATED_DIR.startsWith("/data/user_de/0/com.android.shell/"))
    }

    @Test
    fun `the write test only moves on an explicit answer`() {
        // ShellStorage moves only when the test prints exactly RELOCATE; it must be able to.
        assertTrue(ShellStorage.WRITE_TEST.contains("echo RELOCATE"))
        assertTrue(ShellStorage.WRITE_TEST.contains("echo LEGACY-OK"))
        // Its probe file never outlives the test, in either folder.
        assertTrue(ShellStorage.WRITE_TEST.contains("rm -f /data/local/tmp/.bts_w"))
        assertTrue(ShellStorage.WRITE_TEST.contains("rm -f ${ShellStorage.RELOCATED_DIR}/.bts_w"))
    }
}
