package com.samourai.sentinel.ui.collectionDetails.send

import com.samourai.sentinel.data.AddressTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #635: which pub of a picked collection the fresh walk walks.
 * Pure JVM - the sheet only maps the answer onto its rows.
 */
class DestinationPickerTest {

    @Test
    fun `a wallet import walks its BIP84 deposit label`() {
        val idx = resolveDestinationPubIndex(
            isImportFromWallet = true,
            labels = listOf("Deposit BIP49", "Deposit BIP44", "Deposit BIP84"),
            types = listOf(AddressTypes.BIP49, AddressTypes.BIP44, AddressTypes.BIP84),
        )
        assertEquals(2, idx)
    }

    @Test
    fun `a renamed deposit label still walks - the send screen renames BIP84 in memory`() {
        val idx = resolveDestinationPubIndex(
            isImportFromWallet = true,
            labels = listOf("Deposit", "Deposit BIP49", "Deposit BIP44"),
            types = listOf(AddressTypes.BIP84, AddressTypes.BIP49, AddressTypes.BIP44),
        )
        assertEquals(0, idx)
    }

    @Test
    fun `a wallet import without a deposit key fails closed`() {
        assertNull(
            resolveDestinationPubIndex(
                isImportFromWallet = true,
                labels = listOf("Premix", "Postmix"),
                types = listOf(AddressTypes.BIP84, AddressTypes.BIP44),
            )
        )
    }

    @Test
    fun `a plain collection walks the first non-address pub`() {
        val idx = resolveDestinationPubIndex(
            isImportFromWallet = false,
            labels = listOf("an address", "Deposit", "Bad Bank"),
            types = listOf(AddressTypes.ADDRESS, AddressTypes.BIP84, AddressTypes.BIP44),
        )
        assertEquals(1, idx)
    }

    @Test
    fun `a collection of standalone addresses fails closed`() {
        assertNull(
            resolveDestinationPubIndex(
                isImportFromWallet = false,
                labels = listOf("addr one", "addr two"),
                types = listOf(AddressTypes.ADDRESS, AddressTypes.ADDRESS),
            )
        )
    }

    @Test
    fun `an empty collection fails closed`() {
        assertNull(
            resolveDestinationPubIndex(
                isImportFromWallet = false,
                labels = emptyList(),
                types = emptyList(),
            )
        )
    }
}
