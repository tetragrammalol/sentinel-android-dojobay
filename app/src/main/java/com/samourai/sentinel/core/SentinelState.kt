package com.samourai.sentinel.core

import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.samourai.sentinel.data.LatestBlock
import com.samourai.sentinel.data.Tx
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.data.repository.ExchangeRateRepository
import com.samourai.sentinel.data.repository.TransactionsRepository
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.utils.Preferences
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.util.apiScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.params.TestNet3Params
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import java.math.BigInteger
import java.net.Proxy
import kotlin.reflect.KProperty

/**
 * Utility class for handling basic app states
 */
class SentinelState {

    companion object {

        private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java)
        private val dojoUtility: DojoUtility by inject(DojoUtility::class.java)
        private val transactionsRepository: TransactionsRepository by inject(TransactionsRepository::class.java)
        private val exchangeRateRepository: ExchangeRateRepository by inject(ExchangeRateRepository::class.java)
        private val collectionRepository: CollectionRepository by inject(CollectionRepository::class.java)
        private var testnetParams: NetworkParameters? = NetworkParameters.fromID(NetworkParameters.ID_TESTNET)
        private var mainNetParams: NetworkParameters? = NetworkParameters.fromID(NetworkParameters.ID_MAINNET)
        private var networkParams: NetworkParameters? = mainNetParams
        var checkedClipBoard: Boolean = false
        var hasAppJustStarted: Boolean = true

        var blockHeight: LatestBlock? = null
        private var isOffline = false

        private var countDownTimer: CountDownTimer? = null
        var torProxy: Proxy? = null

        //Shared field for passing tx object between activities and fragments
        var selectedTx: Tx? = null

        val bDust: BigInteger = BigInteger.valueOf(Coin.parseCoin("0.00000546").longValue())


        fun getNetworkParam(): NetworkParameters? {
            return this.networkParams
        }

        fun isTestNet(): Boolean {
            return getNetworkParam() is TestNet3Params
        }

        init {
            readPrefs()
            prefsUtil.addListener(object : Preferences.SharedPrefsListener {
                override fun onSharedPrefChanged(property: KProperty<*>) {
                    readPrefs()
                }
            })
            // #42: the keychain is the truth source for network state;
            // the first-run dialog is only an accelerator. Derive on
            // every collection change, plus once now to catch the
            // emission that landed before this observer attached.
            // observeForever needs the main thread; this object is a
            // process-lifetime singleton, so the observer never leaks.
            Handler(Looper.getMainLooper()).post {
                collectionRepository.collectionsLiveData.observeForever {
                    deriveNetworkFromKeychain()
                }
                deriveNetworkFromKeychain()
            }
        }

        private fun refreshCollection() {
            if (!isRecentlySynced()) {
                exchangeRateRepository.fetch()
                collectionRepository.pubKeyCollections.forEach {
                    val job = apiScope.launch {
                        try {
                            transactionsRepository.fetchFromServer(it)
                        } catch (e: Exception) {
                            Timber.e(e)
                            throw CancellationException(e.message)
                        }
                    }
                    job.invokeOnCompletion {
                        it?.let {
                            Timber.e(it)
                        }
                        if (it == null) {
                            prefsUtil.lastSynced = System.currentTimeMillis()
                        }
                    }
                }
            }

        }

        private fun readPrefs() {
            // == true: GenericPrefDelegate.getValue returns T? even though
            // the SharedPreferences getters are non-null at runtime - the
            // !! was a crash-on-null artifact with no null case behind it.
            this.networkParams = if (prefsUtil.testnet == true) testnetParams else mainNetParams
            this.isOffline = prefsUtil.offlineMode == true
        }

        private fun xpubVotes(): List<DerivedNetwork> =
            synchronized(collectionRepository.pubKeyCollections) {
                collectionRepository.pubKeyCollections
                    .flatMap { it.pubs }
                    .map { NetworkClassifier.fromXpub(it.pubKey) }
            }

        /**
         * #42: derive network from the wallet's keys. Unanimous xpub
         * family wins (aggregate's abstain/mixed rules apply - a mixed
         * wallet derives nothing). Writes only when the derived value
         * differs from the pref; the existing listener propagates to
         * networkParams exactly like the dialog's write does.
         */
        fun deriveNetworkFromKeychain() {
            val derived = NetworkClassifier.aggregate(xpubVotes()) ?: return
            val derivedTestnet = (derived == DerivedNetwork.TESTNET)
            if (derivedTestnet != (prefsUtil.testnet == true)) {
                prefsUtil.testnet = derivedTestnet
            }
        }

        /**
         * #42: the network is "established" when the keychain votes
         * unanimously. validate() only rejects cross-network imports
         * on an established wallet - on an empty or garbage-only
         * wallet, the first key in is what establishes the network.
         */
        fun isNetworkEstablished(): Boolean =
            NetworkClassifier.aggregate(xpubVotes()) != null


        fun isTorRequired(): Boolean {
            return true
            //return dojoUtility.isDojoEnabled() || prefsUtil.enableTor!!
        }

        fun isDojoEnabled(): Boolean {
            return dojoUtility.isDojoEnabled();
        }


        fun isRecentlySynced(): Boolean {
            val lastSync = prefsUtil.lastSynced!!
            val currentTime = System.currentTimeMillis()
            return currentTime.minus(lastSync) < 60000
        }
    }
}
