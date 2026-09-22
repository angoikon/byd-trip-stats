package com.byd.tripstats.server

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * Tests for the file browser's containment check. Pure JVM — no Android context required.
 *
 * This is the security boundary of the whole feature: the web companion hands the server a
 * path string from the URL, and [FileBrowser.resolveWithin] is the only thing standing between
 * that string and the rest of the head unit's filesystem. The cases below are the ones an
 * attacker would reach for — "..", an absolute path, a symlink out, and a sibling directory
 * whose name merely starts with the root's name.
 */
class FileBrowserTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File

    @Before
    fun setUp() {
        root = temp.newFolder("root")
        File(root, "sub").mkdirs()
        File(root, "sub/report.csv").writeText("a,b\n")
    }

    // ── Paths that should resolve ─────────────────────────────────────────────

    @Test
    fun `empty relative path is the root itself`() {
        assertEquals(root.canonicalFile, FileBrowser.resolveWithin(root, ""))
    }

    @Test
    fun `a file directly inside the root resolves`() {
        val resolved = FileBrowser.resolveWithin(root, "sub")
        assertEquals(File(root, "sub").canonicalFile, resolved)
    }

    @Test
    fun `a nested path resolves`() {
        val resolved = FileBrowser.resolveWithin(root, "sub/report.csv")
        assertEquals(File(root, "sub/report.csv").canonicalFile, resolved)
    }

    @Test
    fun `a path that climbs and comes back stays inside`() {
        val resolved = FileBrowser.resolveWithin(root, "sub/../sub/report.csv")
        assertEquals(File(root, "sub/report.csv").canonicalFile, resolved)
    }

    @Test
    fun `resolving a name that does not exist yet is allowed`() {
        // Uploads resolve their destination before creating it.
        val resolved = FileBrowser.resolveWithin(root, "new-backup.db")
        assertEquals(File(root, "new-backup.db").canonicalFile, resolved)
    }

    // ── Paths that must be refused ────────────────────────────────────────────

    @Test
    fun `a parent traversal is refused`() {
        assertNull(FileBrowser.resolveWithin(root, ".."))
    }

    @Test
    fun `a deep parent traversal is refused`() {
        assertNull(FileBrowser.resolveWithin(root, "sub/../../../etc/passwd"))
    }

    @Test
    fun `an absolute path is treated as relative, never as an escape`() {
        // File(parent, "/etc/passwd") joins rather than replaces, so this lands harmlessly
        // inside the root instead of reaching the real /etc/passwd.
        val resolved = FileBrowser.resolveWithin(root, "/etc/passwd")
        assertNotNull(resolved)
        assertTrue(resolved!!.path.startsWith(root.canonicalPath + File.separator))
    }

    @Test
    fun `a symlink pointing outside the root is refused`() {
        val outside = temp.newFolder("outside")
        File(outside, "secret.txt").writeText("no")
        Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath())

        assertNull(FileBrowser.resolveWithin(root, "escape/secret.txt"))
    }

    @Test
    fun `a sibling directory sharing the root's name prefix is refused`() {
        // The classic startsWith() bug: "/tmp/root" must not match "/tmp/rootsecrets".
        val sibling = temp.newFolder("rootsecrets")
        File(sibling, "secret.txt").writeText("no")

        assertNull(FileBrowser.resolveWithin(root, "../rootsecrets/secret.txt"))
    }

    // ── Viewability ───────────────────────────────────────────────────────────

    @Test
    fun `a log file is viewable in the browser`() {
        val log = File(root, "diag.log").apply { writeText("event\n") }
        assertTrue(FileBrowser.isViewable(log))
    }

    @Test
    fun `a database backup is not viewable and must be downloaded`() {
        val db = File(root, "byd_stats_backup.db").apply { writeText("binary") }
        assertFalse(FileBrowser.isViewable(db))
    }

    @Test
    fun `a directory is never viewable`() {
        assertFalse(FileBrowser.isViewable(File(root, "sub")))
    }

    @Test
    fun `a screenshot is viewable as an image`() {
        val shot = File(root, "Screenshot_20260918.png").apply { writeText("not really a png") }
        assertEquals("image", FileBrowser.viewKind(shot))
        assertEquals("image/png", FileBrowser.imageMimeType(shot))
    }

    @Test
    fun `a log is viewable as text, not as an image`() {
        val log = File(root, "diag.log").apply { writeText("event\n") }
        assertEquals("text", FileBrowser.viewKind(log))
        assertNull(FileBrowser.imageMimeType(log))
    }

    @Test
    fun `a database backup has no view kind at all`() {
        val db = File(root, "byd_stats_backup.db").apply { writeText("binary") }
        assertNull(FileBrowser.viewKind(db))
    }

    @Test
    fun `a rotated log is viewable even though its extension is not log`() {
        // diag.log.prev is the file people actually go looking for after a crash.
        val rotated = File(root, "diag.log.prev").apply { writeText("event\n") }
        assertTrue(FileBrowser.isViewable(rotated))
    }

    @Test
    fun `an empty file is not viewable`() {
        assertFalse(FileBrowser.isViewable(File(root, "empty.log").apply { createNewFile() }))
    }

    @Test
    fun `a large log stays viewable and is served from the end`() {
        // Refusing a multi-MB diag.log would defeat the point of the viewer.
        val big = File(root, "huge.log")
        big.writeText((1..40_000).joinToString("\n") { "line $it" } + "\n")
        assertTrue(big.length() > FileBrowser.VIEW_TAIL_BYTES)
        assertTrue(FileBrowser.isViewable(big))

        val tail = FileBrowser.readTail(big)
        assertTrue("keeps the end", tail.endsWith("line 40000\n"))
        assertFalse("drops the start", tail.contains("line 1\n"))
        assertTrue("says what was skipped", tail.startsWith("… showing the last "))
    }

    @Test
    fun `a tail never begins mid-line`() {
        val big = File(root, "wide.log")
        big.writeText((1..40_000).joinToString("\n") { "line $it" } + "\n")

        // The marker line, a blank line, then whole log lines — no truncated first entry.
        val firstLogLine = FileBrowser.readTail(big).lines()[2]
        assertTrue("got '$firstLogLine'", firstLogLine.matches(Regex("""line \d+""")))
    }

    @Test
    fun `a file shorter than the tail is returned whole`() {
        val small = File(root, "small.log").apply { writeText("one\ntwo\n") }
        assertEquals("one\ntwo\n", FileBrowser.readTail(small))
    }
}
