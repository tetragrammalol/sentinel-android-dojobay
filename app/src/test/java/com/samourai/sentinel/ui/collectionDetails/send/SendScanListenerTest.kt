package com.samourai.sentinel.ui.collectionDetails.send

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #350. The payment-scan listener handed the decoded amount straight to
 * java.lang.Double.valueOf: an amount-less bech32 URI - which
 * isBitcoinUri accepts - parsed to null and killed the scan at
 * SendFragment.kt:124, and the scan moment said nothing while every
 * other scanner consumer (#247/#248) already gated and advised. Wiring
 * pins in the ScannerResultTest shape: the listener contract is
 * regression surface, not detail.
 */
class SendScanListenerTest {

    @Test
    fun `the scanned amount is null-safe and the crash line is gone`() {
        val send = source("app/src/main/java/com/samourai/sentinel/ui/collectionDetails/send/SendFragment.kt")
        assertFalse(send.contains("java.lang.Double.valueOf"))
        assertTrue(send.contains("getBitcoinAmount(decoded)?.toDoubleOrNull() ?: 0.0"))
    }

    @Test
    fun `both scan listeners carry the shared scan gate`() {
        val send = source("app/src/main/java/com/samourai/sentinel/ui/collectionDetails/send/SendFragment.kt")
        assertTrue(send.contains("import android.os.SystemClock"))
        assertTrue(send.contains("import com.samourai.sentinel.ui.utils.shouldAdmitScan"))
        assertEquals(2, occurrences(send, "shouldAdmitScan(lastDecodePayload, lastDecodeAtMs, decoded, now)"))
        assertEquals(2, occurrences(send, "lastDecodeAtMs = now"))
    }

    @Test
    fun `every admitted scan arm advises at scan time`() {
        val send = source("app/src/main/java/com/samourai/sentinel/ui/collectionDetails/send/SendFragment.kt")
        assertTrue(send.contains("private fun adviseScannedPayment(scanned: String?)"))
        assertEquals(1, occurrences(send, "adviseScannedPayment(scannedAddress)"))
        assertEquals(2, occurrences(send, "adviseScannedPayment(decoded)"))
        assertTrue(send.contains("viewModel.isWrongNetworkAddress(scanned)"))
    }

    @Test
    fun `the wrong-network classifier is shared, not duplicated`() {
        val vm = source("app/src/main/java/com/samourai/sentinel/ui/collectionDetails/send/SendViewModel.kt")
        assertTrue(vm.contains("internal fun isWrongNetworkAddress(address: String): Boolean {"))
        assertFalse(vm.contains("private fun isWrongNetworkAddress"))
    }

    @Test
    fun `the amount parse never returns null for a URI it accepted`() {
        val fug = source("extlibj/src/main/java/com/samourai/wallet/util/FormatsUtilGeneric.java")
        // three pre-existing defaults plus the amount-less fallback arm
        assertEquals(4, occurrences(fug, "ret = \"0.0000\";"))
        assertTrue(fug.contains("if(uri.getAddress() != null)"))
    }

    private fun occurrences(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1

    private fun source(relative: String): String {
        // Three candidates, the LegacyPbkdf2IterationsTest (#278) shape:
        // repo root, module dir for app/ paths, and ../ for sibling
        // modules (extlibj) when the test stands in the module dir.
        val file = sequenceOf(
            File(relative),
            File(relative.removePrefix("app/")),
            File("../$relative"),
        ).firstOrNull { it.isFile }
            ?: error("missing $relative from ${File(".").absolutePath}")
        return file.readText()
    }
}
