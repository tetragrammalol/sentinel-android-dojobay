package com.samourai.sentinel.ui.home

import com.samourai.sentinel.ui.home.FirstRunRecovery.LaunchStep
import com.samourai.sentinel.ui.home.FirstRunRecovery.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #223: first-run setup completes on a successful pairing, and until then
 * Home re-offers pairing and the network choice instead of dead-ending.
 */
class FirstRunRecoveryTest {

    @Test
    fun aPairedDojoNeedsNothing() {
        assertEquals(LaunchStep.NOTHING, FirstRunRecovery.onLaunch(endpointEnabled = true, setupComplete = false, pairRequested = false))
        assertEquals(LaunchStep.NOTHING, FirstRunRecovery.onLaunch(endpointEnabled = true, setupComplete = true, pairRequested = true))
    }

    @Test
    fun incompleteSetupOffersRecoveryOnLaunch() {
        assertEquals(LaunchStep.OFFER_RECOVERY, FirstRunRecovery.onLaunch(endpointEnabled = false, setupComplete = false, pairRequested = false))
    }

    @Test
    fun aRequestedPairingAfterANetworkSwitchGoesStraightToTheWizard() {
        assertEquals(LaunchStep.PAIR, FirstRunRecovery.onLaunch(endpointEnabled = false, setupComplete = false, pairRequested = true))
    }

    @Test
    fun aCompletedSetupThatLostItsDojoReopensTheWizardAsBefore() {
        assertEquals(LaunchStep.PAIR, FirstRunRecovery.onLaunch(endpointEnabled = false, setupComplete = true, pairRequested = false))
    }

    @Test
    fun afterAFailedPairingOfferTryAgainAndTheOtherNetwork() {
        val plan = FirstRunRecovery.plan(Trigger.PAIRING_FAILED, currentIsTestnet = false, networkEstablished = false)
        assertEquals("Dojo not connected", plan.label)
        assertEquals("Try again", plan.positiveText)
        assertEquals("Switch to Testnet", plan.negativeText)
        assertEquals(true, plan.switchTarget)
        assertTrue(plan.message.contains("You're on Mainnet"))
    }

    @Test
    fun onTestnetTheSwitchGoesToMainnet() {
        val plan = FirstRunRecovery.plan(Trigger.LAUNCH, currentIsTestnet = true, networkEstablished = false)
        assertEquals("Finish setting up", plan.label)
        assertEquals("Pair a Dojo", plan.positiveText)
        assertEquals("Switch to Mainnet", plan.negativeText)
        assertEquals(false, plan.switchTarget)
    }

    @Test
    fun keysThatPinTheNetworkOfferNoSwitch() {
        // #208's rule: a unanimous keychain owns the network.
        val plan = FirstRunRecovery.plan(Trigger.PAIRING_FAILED, currentIsTestnet = false, networkEstablished = true)
        assertNull(plan.switchTarget)
        assertEquals("Not now", plan.negativeText)
        assertTrue(plan.message.contains("set by your public keys"))
        assertFalse(plan.message.contains("Switch below"))
    }

    @Test
    fun homeAndTheDojoSheetAreWiredForRecovery() {
        val sheet = source("ui/dojo/DojoConfigureBottomSheet.kt")
        val report = sheet.substringAfter("private fun reportNotPaired() {").substringBefore("\n    }\n")
        assertTrue("never after success, at most once", report.contains("if (listenerFired || notPairedReported) return"))
        assertTrue("opt-in only", report.contains("ARG_REPORT_FAILURE, false) != true) return"))
        assertTrue("posts not-paired", report.contains("putBoolean(RESULT_PAIRED, false)"))
        val onCancel = sheet.substringAfter("override fun onCancel(dialog: DialogInterface) {").substringBefore("\n    }\n")
        assertTrue(onCancel.contains("reportNotPaired()"))
        val failure = sheet.substringAfter("fun dismissAllOrToast(wasResponseSuccessful: Boolean) {")
            .substringAfter("else {").substringBefore("dismiss()")
        assertTrue("reported before dismiss", failure.contains("reportNotPaired()"))

        val home = source("ui/home/HomeActivity.kt")
        assertTrue(home.contains("reportFailure = prefsUtil.setupComplete != true"))
        val finished = home.substringAfter("private fun onDojoSetupFinished() {").substringBefore("private fun offerRescanPublicKeys")
        assertTrue("setup completes on a pairing that worked", finished.contains("prefsUtil.setupComplete = true"))
        assertTrue(home.contains("showSetupRecoverySheet(FirstRunRecovery.Trigger.PAIRING_FAILED)"))
        val choice = home.substringAfter("private fun onSetupRecoveryChoice(pair: Boolean) {").substringBefore("\n    }\n")
        assertTrue("the switch is re-derived at answer time", choice.contains("NetworkSwitch.rowState("))
        assertTrue(choice.contains("switchNetworkAndRelaunch(prefsUtil, dojoUtility, row.targetIsTestnet)"))

        val network = source("ui/settings/NetworkActivity.kt")
        assertTrue("one switch recipe", network.contains("switchNetworkAndRelaunch(prefsUtil, dojoUtility, targetIsTestnet)"))
    }

    @Test
    fun setupCompleteNeverRidesABackup() {
        val prefs = source("ui/utils/PrefsUtil.kt")
        val omitted = prefs.substringAfter("val PREFS_OMITTED_FROM_BACKUP = setOf(").substringBefore(")")
        assertTrue(omitted.contains("\"setupComplete\""))
    }

    private fun source(name: String): String {
        val relative = "app/src/main/java/com/samourai/sentinel/$name"
        val file = sequenceOf(File(relative), File(relative.removePrefix("app/"))).firstOrNull { it.isFile }
            ?: error("missing $relative from ${File(".").absolutePath}")
        return file.readText()
    }
}
