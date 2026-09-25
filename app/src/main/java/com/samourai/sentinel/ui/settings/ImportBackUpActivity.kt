package com.samourai.sentinel.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.transition.TransitionManager
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialSharedAxis
import com.samourai.sentinel.R
import com.samourai.sentinel.api.APIConfig
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.core.access.AccessFactory
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.databinding.ActivityImportBackUpBinding
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.ui.SentinelActivity
import com.samourai.sentinel.ui.home.HomeActivity
import com.samourai.sentinel.ui.utils.AndroidUtil
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.utils.showFloatingSnackBar
import com.samourai.sentinel.ui.views.LockScreenDialog
import com.samourai.sentinel.util.ExportImportUtil
import com.samourai.sentinel.util.apiScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import java.io.BufferedReader
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStreamReader

class ImportBackUpActivity : SentinelActivity() {

    enum class ImportType {
        SAMOURAI,
        SENTINEL,
        SENTINEL_LEGACY
    }

    class ImportBackUpViewModel : ViewModel()

    private var payloadObject: JSONObject? = null
    private var importType = ImportType.SENTINEL
    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java)
    private var requireRestart = false
    private val viewModel: ImportBackUpViewModel by viewModels()
    private lateinit var binding: ActivityImportBackUpBinding
    private val repository: CollectionRepository by inject(CollectionRepository::class.java)
    private val apiService: ApiService by inject(ApiService::class.java)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImportBackUpBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)
        setSupportActionBar(binding.toolbarImportActivity)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        window.navigationBarColor = ContextCompat.getColor(this, R.color.v3_background)


        binding.importChoosePayloadBtn.setOnClickListener {
            var intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "*/*"
            intent = Intent.createChooser(intent, "Choose a file")
            startActivityForResult(intent, REQUEST_FILE_CODE)
            binding.importPayloadTextView.text = ""
        }

        binding.importPastePayloadBtn.setOnClickListener {
            if (AndroidUtil.getClipBoardString(applicationContext) != null) {
                AndroidUtil.getClipBoardString(applicationContext)?.let {
                    binding.importPayloadTextView.text = ""
                    validatePayload(it)
                }
            }
        }

        binding.importStartBtn.isEnabled = false

        binding.importStartBtn.setOnClickListener {
            // #48: empty password is NO LONGER refused. Backups exported with
            // an empty password (before the export-side guard existed) are
            // valid AES payloads keyed on the empty string; refusing empty
            // here made them permanently unimportable. A wrong password on a
            // protected backup still fails honestly in decryptPayload().
            decryptPayload()
        }
        showImportButton(true)
    }

    /**
     * Registers every xpub with the configured server (#48).
     *
     * Per-item containment: one failing registration must NOT cancel the
     * rest (this was the 2-of-3 partial import). Awaited by the caller so
     * the completion handler reports the real outcome. Returns
     * (imported, failed).
     */
    private suspend fun importAllXpubs(): Pair<Int, Int> {
        var imported = 0
        var failed = 0
        val pubKeyCollectionsCopy = ArrayList(repository.pubKeyCollections)
        pubKeyCollectionsCopy.forEach { collection ->
            collection.pubs.forEach { pub ->
                try {
                    apiService.importXpub(pub.pubKey, "bip${pub.getPurpose()}")
                    imported++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                    Timber.e(e, "Failed to register xpub (bip${pub.getPurpose()})")
                }
            }
        }
        return imported to failed
    }

    /**
     * Dojo pairing leg (#48). DojoUtil.setDojo persists creds + endpoints
     * LOCALLY first, then makes ONE network auth call - which is what dies
     * with "unable to resolve host" while the Tor circuit is still
     * bootstrapping. So: wait for Tor readiness (bootstrap is legitimately
     * slow), retry the auth call boundedly, and report failure as a
     * PARTIAL (collections + prefs + creds already imported) instead of
     * letting one auth exception fail the whole import's report.
     *
     * Returns true when pairing succeeded (or there was nothing to do).
     */
    private suspend fun importDojoWithRetry(dojo: JSONObject?): Boolean {
        if (dojo == null) return true
        if (SentinelTorManager.getTorState().state != EnumTorState.ON) {
            SentinelTorManager.start()
            prefsUtil.enableTor = true
            val deadline = System.currentTimeMillis() + 60_000L
            while (SentinelTorManager.getTorState().state != EnumTorState.ON &&
                System.currentTimeMillis() < deadline
            ) {
                delay(500L)
            }
            if (SentinelTorManager.getTorState().state != EnumTorState.ON) {
                Timber.e("dojo import: Tor did not bootstrap within 60s")
                return false
            }
        }
        repeat(3) { attempt ->
            try {
                ExportImportUtil().importDojo(dojo)
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "dojo import auth attempt ${attempt + 1}/3 failed")
            }
            if (attempt < 2) delay(3_000L)
        }
        return false
    }

    /**
     * The import runs long (Tor wait up to 60s + network legs) with no
     * other feedback; without this the app reads as hung (#48 QA).
     * The start button doubles as the progress cue.
     */
    private fun setImportBusy(busy: Boolean) {
        binding.importStartBtn.text = if (busy) "Importing\u2026" else "Import Sentinel Backup"
        binding.importStartBtn.isEnabled = !busy
    }

    private fun showImportButton(hide: Boolean) {
        val sharedAxis = MaterialSharedAxis(MaterialSharedAxis.Y, !hide)
        TransitionManager.beginDelayedTransition(binding.importStartBtn.rootView as ViewGroup, sharedAxis)
        binding.importStartBtn.isEnabled = !hide
        binding.importStartBtn.visibility = if (hide) View.GONE else View.VISIBLE
    }

    private fun decryptPayload() {
        setImportBusy(true)
        when (importType) {
            ImportType.SENTINEL -> {
                var payload: Triple<ArrayList<PubKeyCollection>?, JSONObject, JSONObject?>? = null
                try {
                    payload = ExportImportUtil().decryptSentinel(
                        payloadObject.toString(),
                        binding.importPasswordInput.text.toString()
                    )
                } catch (e: Exception) {
                    this@ImportBackUpActivity.showFloatingSnackBar(
                        binding.importPayloadTextView.parent as ViewGroup,
                        text = "Incorrect password, please try again.",
                        duration = Snackbar.LENGTH_SHORT
                    )
                }

                if (payload == null) {
                    setImportBusy(false)
                    return
                }

                if (payload.second.get("pinEnabled").equals(true)) {
                    val fragmentManager = supportFragmentManager
                    val lockScreenDialog = LockScreenDialog(lockScreenMessage = "Enter pin code")
                    val transaction = fragmentManager.beginTransaction()
                    transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                    transaction.add(android.R.id.content, lockScreenDialog).commit()
                    lockScreenDialog.setOnPinEntered {
                        if (AccessFactory.getInstance(this).validateHash(it,
                                payload.second.get("pinHash").toString()
                            )) {
                            AccessFactory.getInstance(this).pin = it
                            lockScreenDialog.dismiss()

                            // Hoisted OUT of the launch body: the
                            // invokeOnCompletion lambda below is outside
                            // that scope and must close over it (#48).
                            var xpubImport: Pair<Int, Int>? = null
                            var dojoPairingFailed = false
                            viewModel.viewModelScope.launch(Dispatchers.IO) {
                                try {
                                    withContext(Dispatchers.Main) {
                                        binding.importPasswordInputLayout.visibility = View.INVISIBLE
                                        binding.importSentinelBackUpLayout.visibility = View.VISIBLE
                                        binding.importCollections.text =
                                            binding.importCollections.text.toString().substringBefore(" (") +
                                                " (${payload.first?.size})"
                                    }

                                    if (binding.importPrefs.isChecked) {
                                        payload.second.let { ExportImportUtil().importPrefs(it) }
                                    }

                                    if (binding.importCollections.isChecked) {
                                        payload.first?.let {
                                            ExportImportUtil().startImportCollections(
                                                it,
                                                binding.importClearExisting.isChecked
                                            )
                                        }
                                    }
                                    if (binding.importDojo.isChecked) {
                                        if ((payload.second.getString("apiEndPointTor").equals(APIConfig.SAMOURAI_API_TOR)
                                                    && payload.second.getString("apiEndPoint").equals(APIConfig.SAMOURAI_API))
                                            ||
                                            (payload.second.getString("apiEndPointTor").equals(APIConfig.SAMOURAI_API_TOR_TESTNET)
                                                    && payload.second.getString("apiEndPoint").equals(APIConfig.SAMOURAI_API_TESTNET))) {

                                        }
                                        else {
                                            if (importDojoWithRetry(payload.third)) {
                                                prefsUtil.apiEndPointTor = payload.second.getString("apiEndPointTor")
                                                prefsUtil.apiEndPoint = payload.second.getString("apiEndPoint")
                                            } else {
                                                dojoPairingFailed = true
                                            }
                                        }
                                    }
                                    else {
                                        xpubImport = importAllXpubs()
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    throw CancellationException(e.message)
                                }
                            }.invokeOnCompletion {
                                if (it == null) {
                                    binding.importStartBtn.text = "Imported \u2713"
                                    requireRestart = true
                                    val xpubNote = xpubImport?.let { (ok, bad) ->
                                        if (bad > 0)
                                            " ($ok of ${ok + bad} keys registered - retry from Network screen)"
                                        else ""
                                    } ?: ""
                                    val dojoNote = if (dojoPairingFailed)
                                        " Dojo pairing failed - retry from Network screen."
                                    else ""
                                    val note = "$xpubNote$dojoNote"
                                    showFloatingSnackBar(
                                        binding.importPastePayloadBtn, "Successfully imported$note",
                                        anchorView = binding.importStartBtn.id,
                                        actionText = "restart",
                                        actionClick = { restart() }
                                    )
                                } else {
                                    setImportBusy(false)
                                    Timber.e(it)
                                    showFloatingSnackBar(
                                        binding.importPastePayloadBtn,
                                        "Error: ${it.message}",
                                        anchorView = binding.importStartBtn.id
                                    )
                                }
                            }
                        }
                        else {
                            lockScreenDialog.showError()
                        }
                    }
                } else {
                    // Hoisted OUT of the launch body (see branch above).
                    var xpubImport: Pair<Int, Int>? = null
                    var dojoPairingFailed = false
                    viewModel.viewModelScope.launch(Dispatchers.IO) {
                        try {
                            withContext(Dispatchers.Main) {
                                binding.importPasswordInputLayout.visibility = View.INVISIBLE
                                binding.importSentinelBackUpLayout.visibility = View.VISIBLE
                                binding.importCollections.text =
                                    binding.importCollections.text.toString().substringBefore(" (") +
                                        " (${payload.first?.size})"
                            }

                            if (binding.importPrefs.isChecked) {
                                payload.second.let { ExportImportUtil().importPrefs(it) }
                            }

                            if (binding.importCollections.isChecked) {
                                payload.first?.let {
                                    ExportImportUtil().startImportCollections(
                                        it,
                                        binding.importClearExisting.isChecked
                                    )
                                }
                            }
                            if (binding.importDojo.isChecked) {
                                if ((payload.second.getString("apiEndPointTor").equals(APIConfig.SAMOURAI_API_TOR)
                                            && payload.second.getString("apiEndPoint").equals(APIConfig.SAMOURAI_API))
                                    ||
                                    (payload.second.getString("apiEndPointTor").equals(APIConfig.SAMOURAI_API_TOR_TESTNET)
                                            && payload.second.getString("apiEndPoint").equals(APIConfig.SAMOURAI_API_TESTNET))) {

                                }
                                else {
                                    if (importDojoWithRetry(payload.third)) {
                                        prefsUtil.apiEndPointTor = payload.second.getString("apiEndPointTor")
                                        prefsUtil.apiEndPoint = payload.second.getString("apiEndPoint")
                                    } else {
                                        dojoPairingFailed = true
                                    }
                                }
                            }
                            else {
                                xpubImport = importAllXpubs()
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            throw CancellationException(e.message)
                        }
                    }.invokeOnCompletion {
                        if (it == null) {
                            binding.importStartBtn.text = "Imported \u2713"
                            requireRestart = true
                            val xpubNote = xpubImport?.let { (ok, bad) ->
                                if (bad > 0)
                                    " ($ok of ${ok + bad} keys registered - retry from Network screen)"
                                else ""
                            } ?: ""
                            val dojoNote = if (dojoPairingFailed)
                                " Dojo pairing failed - retry from Network screen."
                            else ""
                            val note = "$xpubNote$dojoNote"
                            showFloatingSnackBar(
                                binding.importPastePayloadBtn, "Successfully imported$note",
                                anchorView = binding.importStartBtn.id,
                                actionText = "restart",
                                actionClick = { restart() }
                            )
                        } else {
                            setImportBusy(false)
                            Timber.e(it)
                            showFloatingSnackBar(
                                binding.importPastePayloadBtn,
                                "Error: ${it.message}",
                                anchorView = binding.importStartBtn.id
                            )
                        }
                    }
                }
            }
            ImportType.SENTINEL_LEGACY -> {
                viewModel.viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val payload = ExportImportUtil().decryptSentinelLegacy(
                                payloadObject.toString(),
                                binding.importPasswordInput.text.toString()
                        )
                        val pubKeys = payload.first
                        if (pubKeys.isNotEmpty()) {
                            val collection = PubKeyCollection()
                            collection.pubs = pubKeys
                            collection.collectionLabel = "Sentinel Import"
                            ExportImportUtil().startImportCollections(
                                    arrayListOf(collection),
                                    false
                            )
                        } else {
                            throw  CancellationException("0 public keys found")
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        throw CancellationException(e.message)
                    }
                }
                        .invokeOnCompletion {
                            if (it == null) {
                                binding.importStartBtn.text = "Imported \u2713"
                                requireRestart = true
                                showFloatingSnackBar(
                                        binding.importPastePayloadBtn, "Successfully imported",
                                        anchorView = binding.importStartBtn.id,
                                        actionText = "restart",
                                        actionClick = { restart() }
                                )
                            } else {
                                setImportBusy(false)
                                showFloatingSnackBar(
                                        binding.importPastePayloadBtn,
                                        "Error: ${it.message}",
                                        anchorView = binding.importStartBtn.id
                                )
                            }
                        }
            }
            else -> {
                setImportBusy(false)
                showFloatingSnackBar(binding.importPastePayloadBtn, "Please choose a valid Sentinel backup file")
            }
        }
    }

    private fun restart() {
        startActivity(Intent(this, HomeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
        overridePendingTransition(R.anim.fade_in, R.anim.bottom_sheet_slide_out)
        finish()
    }

    /**
     * Validates backup payloads
     * Method uses coroutines to parse json
     */
    private fun validatePayload(string: String) {
        viewModel.viewModelScope.launch(Dispatchers.Default) {
            try {
                val json = JSONObject(string)
                withContext(Dispatchers.Main) {
                    binding.importPayloadTextView.text = "${binding.importPayloadTextView.text}${json.toString(2)}"
                    if (json.has("external") && json.has("payload")) {
                        payloadObject = json
                        importType = ImportType.SAMOURAI
                        showImportButton(false)
                    } else if (json.has("time") && json.has("payload")) {
                        payloadObject = json
                        importType = ImportType.SENTINEL
                        showImportButton(false)
                        binding.importSentinelBackUpLayout.visibility = View.VISIBLE
                    } else if (json.has("payload")) {
                        payloadObject = json
                        importType = ImportType.SENTINEL_LEGACY
                        showImportButton(false)
                    } else {
                        showImportButton(false)
                        showFloatingSnackBar(binding.importStartBtn, text = "Invalid payload")
                    }
                }
            } catch (e: Exception) {
                throw  CancellationException((e.message))
            }
        }.invokeOnCompletion {
            if (it != null) {
                Timber.e(it)
            }
        }
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (data != null && data.data != null && data.data!!.path != null && requestCode == REQUEST_FILE_CODE) {
            val job =
                    viewModel.viewModelScope.launch(Dispatchers.Main) {
                        try {
                            val inputStream = contentResolver.openInputStream(data.data!!)
                            val reader = BufferedReader(InputStreamReader(inputStream))
                            val size = inputStream?.available()
                            if (size != null) {
                                if (size > 5e+6) {
                                    throw  IOException("File size is too large to open")
                                }
                            }
                            var string = ""
                            string = reader.buffered().readText()
                            withContext(Dispatchers.Main) {
                                validatePayload(string)
                            }
                        } catch (fn: FileNotFoundException) {
                            fn.printStackTrace()
                            throw CancellationException((fn.message))
                        } catch (ioe: IOException) {
                            ioe.printStackTrace()
                            throw CancellationException((ioe.message))
                        } catch (ex: Exception) {
                            ex.printStackTrace()
                            throw CancellationException((ex.message))
                        }
                    }
            job.invokeOnCompletion {
                if (it != null) {
                    this.showFloatingSnackBar(binding.importPastePayloadBtn, "Error ${it.message}")
                }
            }
        }
    }

    override fun onBackPressed() {
        if (requireRestart) {
            startActivity(Intent(this, HomeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            })
            overridePendingTransition(R.anim.fade_in, R.anim.bottom_sheet_slide_out)
            finish()
        } else
            super.onBackPressed()
    }

    companion object {
        const val REQUEST_FILE_CODE = 44
    }

}