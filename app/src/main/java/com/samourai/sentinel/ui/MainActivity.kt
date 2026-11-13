package com.samourai.sentinel.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.samourai.sentinel.BuildConfig
import com.samourai.sentinel.R
import com.samourai.sentinel.core.access.AccessFactory
import com.samourai.sentinel.core.access.PinVault
import com.samourai.sentinel.core.access.UnlockResult
import com.samourai.sentinel.data.db.appWalletPayloads
import com.samourai.sentinel.data.repository.FeeRepository
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.home.HomeActivity
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.views.KdfProgressOverlay
import com.samourai.sentinel.ui.views.LockScreenDialog
import kotlinx.coroutines.*
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import java.io.File

/**
 * sentinel-android
 *
 */

class MainActivity : AppCompatActivity() {

    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java);
    private val dojoUtil: DojoUtility by inject(DojoUtility::class.java);
    private val accessFactory: AccessFactory by inject(AccessFactory::class.java);
    private val feeRepository: FeeRepository by inject(FeeRepository::class.java);

    private var dontDeleteFile = false;
    private var unlockBusy = false

    /** #355: non-cancelable waiting room for the PIN-unlock KDF run. */
    private val kdfOverlay by lazy { KdfProgressOverlay(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // #220: re-registered on every create. The restored pad posts the
        // pin through REQUEST_KEY_UNLOCK_PIN; the hash it is checked
        // against already lives in prefs. Digits typed before the
        // recreation are gone on purpose.
        supportFragmentManager.setFragmentResultListener(
            REQUEST_KEY_UNLOCK_PIN,
            this
        ) { _, bundle ->
            supportFragmentManager.clearFragmentResult(REQUEST_KEY_UNLOCK_PIN)
            onUnlockPin(bundle.getString(LockScreenDialog.RESULT_PIN).orEmpty())
        }
        if (prefsUtil.isPinConfigured()) {
            // Recreation restores the pad under TAG_UNLOCK_PIN. Adding
            // another one here is how a font change used to stack two.
            if (supportFragmentManager.findFragmentByTag(TAG_UNLOCK_PIN) == null) {
                val lockScreenDialog = LockScreenDialog.newInstance(
                    message = "Enter pin code",
                    requestKey = REQUEST_KEY_UNLOCK_PIN,
                    purpose = LockScreenDialog.PURPOSE_UNLOCK
                )
                val transaction = supportFragmentManager.beginTransaction()
                transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                transaction.add(android.R.id.content, lockScreenDialog, TAG_UNLOCK_PIN).commit()
            }
        } else {
             navigate()
        }
    }

    private fun onUnlockPin(pin: String) {
        val dialog = supportFragmentManager.findFragmentByTag(TAG_UNLOCK_PIN) as? LockScreenDialog
        if (!prefsUtil.isPinConfigured() || dialog == null) {
            dialog?.dismiss()
            return
        }
        if (unlockBusy) {
            return
        }
        unlockBusy = true
        // #355: the Argon2id wait reads as a frozen app without this.
        kdfOverlay.show("Unlocking\u2026")
        val filesDir = applicationContext.filesDir
        // KDF work stays off the UI thread. A correct PIN also rewrites
        // any payload still in the previous format before navigation.
        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    PinVault(prefsUtil).unlock(pin, appWalletPayloads(filesDir))
                }
            } catch (e: Exception) {
                Timber.e(e, "Unlock failed")
                UnlockResult.Unavailable("Could not open saved unlock data. Try again.")
            }
            // #528: dismiss, the debug line, and every verdict arm sit
            // inside one finally. Busy drops when this screen stays
            // (wrong PIN, unavailable vault, migration, or a throw).
            // A successful unlock that finishes the activity may keep it.
            deliverUnlockResult(
                result = result,
                clearBusy = { unlockBusy = false },
                prepare = {
                    if (BuildConfig.DEBUG) {
                        Timber.d("%s", unlockResultLogLine(result))
                    }
                    // #355: one dismiss before the when covers all three arms.
                    kdfOverlay.dismiss()
                },
                onWrongPin = { dialog.showError() },
                onUnavailable = { message ->
                    // The PIN was never judged. The pad says so in red
                    // and the toast repeats the same words.
                    dialog.resetPad(message)
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                },
                onSuccess = {
                    delay(100)
                    dojoUtil.read()
                    navigate()
                },
            )
        }
    }

    /**
     * @return true when Home is started and this activity finishes.
     * False when a migration is still in front: this screen stays,
     * so the unlock latch must drop.
     */
    private fun navigate(): Boolean {
        // #300: the only admit point. PIN success and the no-PIN path
        // both come through here, and they come through here only
        // after the lock (when there is one). Restored activities
        // bounce back until this runs.
        accessFactory.admitSession()

        val leaves = unlockLeavesScreen(
            UnlockResult.Success,
            migrationPending = checkMigration(),
        )
        if (leaves) {
            feeRepository.init()
            startActivity(Intent(this, HomeActivity::class.java).apply {
                // #528: CLEAR_TASK is ignored unless NEW_TASK is set too.
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            })
            this.overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
            finish()
        } else {
            startActivityForResult(Intent(this, MigrationActivity::class.java), 20)
            this.overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        }
        return leaves
    }


    private fun checkMigration(): Boolean {
        if(dontDeleteFile){
            return false
        }
        val dir: File = applicationContext.getDir("wallet", Context.MODE_PRIVATE)
        val file = File(dir, "sentinel.dat")
        Timber.i(file.exists().toString());
        return file.exists()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 20) {
            dontDeleteFile = true;
            navigate()
        }
    }

    override fun onDestroy() {
        // #355: the unlock launch dies with this scope; the overlay
        // must not outlive the window (rotation mid-KDF leaks it).
        kdfOverlay.dismiss()
        super.onDestroy()
    }

    override fun onBackPressed() {
        MaterialAlertDialogBuilder(this)
                .setTitle(resources.getString(R.string.confirm_exit))
                .setMessage(resources.getString(R.string.ask_you_sure_exit))
                .setNegativeButton(resources.getString(R.string.no)) { _, _ ->
                }
                .setPositiveButton(resources.getString(R.string.yes)) { _, _ ->
                    // #528: closing only this activity would uncover
                    // whatever this PIN screen was stacked on. Removing
                    // the task exits to the launcher and leaves Recents empty.
                    finishAndRemoveTask()
                }
                .show()
    }

    companion object {
        const val TAG_UNLOCK_PIN = "lock_screen_unlock"
        const val REQUEST_KEY_UNLOCK_PIN = "lock_screen_unlock_result"
    }
}