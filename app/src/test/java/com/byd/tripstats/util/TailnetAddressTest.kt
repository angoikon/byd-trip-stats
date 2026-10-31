package com.byd.tripstats.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [isTailnetAddress] — which decides whether MQTT dials its broker through the
 * Tailscale daemon's SOCKS5 listener (issue #34) or directly. A false yes sends a LAN or public
 * broker into the tailnet, where it can't be found; a false no leaves a tailnet broker timing out.
 */
class TailnetAddressTest {

    @Test
    fun `Tailscale IPv4 addresses are tailnet`() {
        assertTrue(isTailnetAddress("100.64.0.1"))
        assertTrue(isTailnetAddress("100.99.138.80"))
        assertTrue(isTailnetAddress("100.127.255.254"))
        assertTrue(isTailnetAddress(" 100.101.102.103 "))
    }

    @Test
    fun `100 addresses outside 100_64 slash 10 are not`() {
        assertFalse(isTailnetAddress("100.63.255.255"))
        assertFalse(isTailnetAddress("100.128.0.1"))
        assertFalse(isTailnetAddress("100.1.2.3"))
    }

    @Test
    fun `LAN and public addresses are not`() {
        assertFalse(isTailnetAddress("192.168.178.20"))
        assertFalse(isTailnetAddress("10.0.0.5"))
        assertFalse(isTailnetAddress("127.0.0.1"))
        assertFalse(isTailnetAddress("8.8.8.8"))
    }

    @Test
    fun `malformed dotted numbers are not`() {
        assertFalse(isTailnetAddress("100.64.0"))
        assertFalse(isTailnetAddress("100.64.0.1.5"))
        assertFalse(isTailnetAddress("100.64.0.256"))
        assertFalse(isTailnetAddress("100.+64.0.1"))
        assertFalse(isTailnetAddress("100..0.1"))
        assertFalse(isTailnetAddress(""))
    }

    @Test
    fun `Tailscale IPv6 addresses are tailnet, bracketed or not`() {
        assertTrue(isTailnetAddress("fd7a:115c:a1e0::1"))
        assertTrue(isTailnetAddress("[fd7a:115c:a1e0:ab12:4843:cd96:6258:b240]"))
        assertTrue(isTailnetAddress("FD7A:115C:A1E0::53"))
        assertFalse(isTailnetAddress("fd7a:115c:a1e1::1"))
        assertFalse(isTailnetAddress("::1"))
    }

    @Test
    fun `full MagicDNS names are tailnet`() {
        assertTrue(isTailnetAddress("homeassistant.tail1234.ts.net"))
        assertTrue(isTailnetAddress("HomeAssistant.Tail1234.TS.NET"))
        assertTrue(isTailnetAddress("homeassistant.tail1234.ts.net."))
    }

    @Test
    fun `short names and other domains are not`() {
        assertFalse(isTailnetAddress("homeassistant"))
        assertFalse(isTailnetAddress("homeassistant.local"))
        assertFalse(isTailnetAddress("abc123.s1.eu.hivemq.cloud"))
        assertFalse(isTailnetAddress("notts.net"))
        assertFalse(isTailnetAddress("ts.net.example.com"))
    }
}
