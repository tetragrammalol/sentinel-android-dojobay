package com.samourai.sentinel.ui.dojo

import com.samourai.sentinel.ui.webview.ExplorerBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The paired Dojo is named the way the pre-pair dialog names it.
 * The shortened onion is the next line, and a pairing with no
 * directory snapshot falls back to that onion alone.
 */
class PairedDojoLinesTest {

    private val pairingUrl = "http://community.example"
    private val onion = "http://" + ("abcd" + "q".repeat(48) + "wxyz") + ".onion"
    private val short = "abcd…wxyz.onion"
    private val unnamed = "Unnamed Dojo"
    private val snapshot = DojoDisplaySnapshot(
        url = pairingUrl,
        name = "Directory Dojo",
        flag = "SG",
        jurisdiction = "Singapore",
    )

    @Test
    fun `a community pairing shows the dialog header and the shortened onion`() {
        val lines = pairedDojoLines(pairingUrl, snapshot, onion, unnamed)
        assertEquals(pairingIdentityLine(pairingUrl, snapshot, unnamed), lines.primary)
        assertEquals("SG Directory Dojo", lines.primary)
        assertEquals(short, lines.onion)
        assertEquals(ExplorerBinding.truncateExplorerLabel(onion), lines.onion)
        assertEquals(short, pairedDojoDetail(lines.primary, lines))
        assertEquals(lines.primary, pairedDojoDetail("Registering derivation paths with Dojo… 1 of 4", lines))
        assertEquals(short, explorerIdentityDetail("Bound to ${lines.primary}", lines))
        assertEquals(
            "${lines.primary}\n$short",
            explorerIdentityDetail("Checking…", lines),
        )
    }

    @Test
    fun `a nameless directory node uses the unnamed Dojo label`() {
        val lines = pairedDojoLines(pairingUrl, snapshot.copy(name = null), onion, unnamed)
        assertEquals("SG Unnamed Dojo", lines.primary)
        assertEquals(short, lines.onion)
    }

    @Test
    fun `a manual pairing a stale snapshot and missing metadata fall back to the onion`() {
        val manual = pairedDojoLines(pairingUrl, null, onion, unnamed)
        assertEquals(short, manual.primary)
        assertEquals(short, manual.onion)
        assertNull(pairedDojoDetail(short, manual))
        assertNull(explorerIdentityDetail("Bound to $short", manual))
        assertEquals(short, explorerIdentityDetail("Checking…", manual))

        val stale = pairedDojoLines(pairingUrl, snapshot.copy(url = "http://elsewhere.example"), onion, unnamed)
        assertEquals(short, stale.primary)
        assertNull(pairedDojoDetail(stale.primary, stale))

        val blank = DojoDisplaySnapshot(url = pairingUrl, name = " ", flag = "", jurisdiction = "  ")
        val missing = pairedDojoLines(pairingUrl, blank, onion, unnamed)
        assertEquals(short, missing.primary)
        assertEquals(short, missing.onion)
    }

    @Test
    fun `no address at all uses the unnamed label and has no second line`() {
        val lines = pairedDojoLines(null, null, null, unnamed)
        assertEquals(unnamed, lines.primary)
        assertNull(lines.onion)
        assertNull(pairedDojoDetail(unnamed, lines))
        assertNull(explorerIdentityDetail("Checking…", lines))
        assertNull(explorerIdentityDetail("Checking…", null))
    }
}
