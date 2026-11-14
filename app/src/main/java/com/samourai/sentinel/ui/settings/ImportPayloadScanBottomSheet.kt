package com.samourai.sentinel.ui.settings

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.samourai.sentinel.R
import com.samourai.sentinel.ui.fragments.ScanPubKeyFragment
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.views.applyScreenshotPolicy
import org.koin.java.KoinJavaComponent

/**
 * Single-shot camera for Settings > Import.
 *
 * Reuses [ScanPubKeyFragment] and hides that scanner's paste button:
 * this screen already has PASTE. The first decode the activity consumes
 * closes the sheet. Blank decodes are ignored and the camera stays up.
 * Animated multi-frame backups (BBQr / UR) are out of scope; a plain
 * QR is the only path this sheet adds.
 *
 * #511: this window follows Disable Screenshots, the same setting as
 * the Import screen. It is not forced secure. The decoded text is
 * delivered to the activity and is never logged.
 */
class ImportPayloadScanBottomSheet : BottomSheetDialogFragment() {

    private val prefsUtil: PrefsUtil by KoinJavaComponent.inject(PrefsUtil::class.java)
    private val scanFragment = ScanPubKeyFragment()

    override fun getTheme(): Int = R.style.AppTheme_BottomSheet_Theme

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).also { created ->
            applySheetScreenshotPolicy(created)
        }
    }

    override fun onStart() {
        super.onStart()
        applySheetScreenshotPolicy(dialog)
        expandToContent()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.import_payload_scan, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        scanFragment.setPasteVisible(false)
        scanFragment.setOnScanListener { payload ->
            val host = activity as? ImportBackUpActivity
            if (host == null) {
                dismissAllowingStateLoss()
                return@setOnScanListener true
            }
            val consumed = host.onScannedImportPayload(payload)
            if (consumed) dismissAllowingStateLoss()
            consumed
        }
        childFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(
                    fm: FragmentManager,
                    f: Fragment,
                    v: View,
                    savedInstanceState: Bundle?,
                ) {
                    if (f !== scanFragment) return
                    fm.unregisterFragmentLifecycleCallbacks(this)
                    v.findViewById<TextView>(R.id.scanInstructions)?.let { instructions ->
                        instructions.text = SCAN_INSTRUCTION
                        instructions.textAlignment = View.TEXT_ALIGNMENT_CENTER
                    }
                }
            },
            false,
        )
        childFragmentManager.beginTransaction()
            .replace(R.id.importPayloadScanContainer, scanFragment)
            .commit()
    }

    /**
     * The Import screen's window is a different window. Only this
     * dialog follows the setting, and closing it does not clear or
     * set the flag on the host.
     */
    private fun applySheetScreenshotPolicy(target: Dialog?) {
        target?.window?.applyScreenshotPolicy(prefsUtil.displaySecure == true)
    }

    private fun expandToContent() {
        val sheetView = view ?: return
        sheetView.post {
            val parent = sheetView.parent as? View ?: return@post
            val params = parent.layoutParams as? CoordinatorLayout.LayoutParams ?: return@post
            val behavior = params.behavior as? BottomSheetBehavior<*> ?: return@post
            val height = sheetView.measuredHeight
            if (height > 0) behavior.peekHeight = height
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    companion object {
        const val TAG = "import_payload_scan"
        const val SCAN_INSTRUCTION = "Scan a Sentinel backup or an Ashigaru pairing QR"
    }
}
