package com.byd.tripstats.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Tailscale daemon's command line ([daemonCommandLine]) and the watch that starts
 * it again ([daemonWatchShouldRestart]).
 *
 * Overdrive's pre-update cleanup kills every process whose command line contains "tailscaled"
 * (and "zrok", "cloudflared", …); our socket `@tripstats-tailscaled` put that word in ours, so each
 * Overdrive update took the car off the tailnet (2026-11-15).
 */
class TailscaleDaemonWatchTest {

    private val dirs = listOf(
        "${ShellStorage.LEGACY_DIR}/.tripstats-tailscale",
        "${ShellStorage.RELOCATED_DIR}/.tripstats-tailscale",
    )

    @Test
    fun `the daemon's command line matches none of Overdrive's kill patterns`() {
        for (dir in dirs) for (socks in listOf(28201, null)) {
            val line = daemonCommandLine(dir, socks)
            OVERDRIVE_KILL_PATTERNS.forEach { pattern ->
                assertFalse("'$pattern' in: $line", line.contains(pattern))
            }
        }
    }

    @Test
    fun `the command line is the binary in its own folder with the expected flags`() {
        assertEquals(
            "/data/local/tmp/.tripstats-tailscale/tsd --tun=userspace-networking " +
                "--socks5-server=127.0.0.1:28201 --statedir=/data/local/tmp/.tripstats-tailscale " +
                "--socket=@tripstats-ts",
            daemonCommandLine("/data/local/tmp/.tripstats-tailscale", 28201),
        )
        assertFalse(daemonCommandLine("/x", null).contains("--socks5-server"))
    }

    @Test
    fun `one missed probe is not enough to restart`() {
        assertFalse(daemonWatchShouldRestart(misses = 0, sinceLastRestartMs = null))
        assertFalse(daemonWatchShouldRestart(misses = 1, sinceLastRestartMs = null))
        assertTrue(daemonWatchShouldRestart(misses = 2, sinceLastRestartMs = null))
    }

    @Test
    fun `a daemon that keeps dying is restarted at most once per cooldown`() {
        assertFalse(daemonWatchShouldRestart(misses = 5, sinceLastRestartMs = DAEMON_WATCH_RESTART_COOLDOWN_MS - 1))
        assertTrue(daemonWatchShouldRestart(misses = 2, sinceLastRestartMs = DAEMON_WATCH_RESTART_COOLDOWN_MS))
    }
}
