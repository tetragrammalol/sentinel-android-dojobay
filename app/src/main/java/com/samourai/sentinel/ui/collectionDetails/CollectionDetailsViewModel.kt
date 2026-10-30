package com.samourai.sentinel.ui.collectionDetails

import androidx.lifecycle.*
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.data.repository.ExchangeRateRepository
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.util.MonetaryUtil
import com.samourai.sentinel.util.UtxoMetaUtil
import com.samourai.sentinel.util.figuresFor
import org.koin.java.KoinJavaComponent.inject

/**
 * sentinel-android
 *
 * @author Sarath
 */
class CollectionDetailsViewModel(private val pubKeyCollection: PubKeyCollection) : ViewModel() {

    private val collectionRepository: CollectionRepository by inject(CollectionRepository::class.java)
    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java)
    private val exchangeRateRepository: ExchangeRateRepository by inject(ExchangeRateRepository::class.java)


    fun getRepository(): CollectionRepository {
        return collectionRepository
    }

    fun getCollections(): LiveData<ArrayList<PubKeyCollection>> {
        return collectionRepository.collectionsLiveData
    }

    /**
     * #301: spendable balance for the details header. Previously this
     * posted the RAW collection balance and located the live collection
     * by balance VALUE (findLast { it.balance == ... }) - two collections
     * with equal balances made that a coin flip, and do-not-spend coins
     * were never excluded. Now: id match, the one derivation
     * (SpendableBalance.kt), and blockedChanges as a second source so
     * the header repaints the moment a mark changes instead of at the
     * next sync emission.
     */
    fun getBalance(): LiveData<Long> {
        val mediator = MediatorLiveData<Long>()
        val recompute: () -> Unit = {
            val live = collectionRepository.collectionsLiveData.value
                ?.findLast { collection -> collection.id == pubKeyCollection.id }
            if (live != null) {
                mediator.value = figuresFor(live).spendable
            }
        }
        mediator.addSource(collectionRepository.collectionsLiveData) { recompute() }
        mediator.addSource(UtxoMetaUtil.blockedChanges) { recompute() }
        return mediator
    }

    /**
     * #301: fiat line follows the spendable balance above - previously
     * its own raw findLast-by-balance lookup, so the fiat figure could
     * disagree with the BTC figure beside it.
     */
    fun getFiatBalance(): LiveData<String> {
        val balance = getBalance()
        val mediator = MediatorLiveData<String>();
        mediator.addSource(balance) {
            mediator.value = getFiatBalance(it, exchangeRateRepository.getRateLive().value)
        }
        mediator.addSource(exchangeRateRepository.getRateLive()) {
            mediator.value = getFiatBalance(balance.value, it)
        }
        return mediator
    }


    private fun getFiatBalance(balance: Long?, rate: ExchangeRateRepository.Rate?): String {
        if (rate != null) {
            balance?.let {
                return try {
                    val fiatRate = MonetaryUtil.getInstance().getFiatFormat(prefsUtil.selectedCurrency)
                            .format((balance / 1e8) * rate.rate)
                    "$fiatRate ${rate.currency}"
                } catch (e: Exception) {
                    "00.00 ${rate.currency}"
                }
            }
            return "00.00"
        } else {
            return "00.00"
        }
    }

    class CollectionDetailsViewModelFactory(private val pubKeyCollection: PubKeyCollection) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(CollectionDetailsViewModel::class.java)) {
                return CollectionDetailsViewModel(pubKeyCollection) as T
            }
            throw IllegalArgumentException("Unknown ViewMo del class")
        }
    }

    companion object {
        fun getFactory(pubKeyCollection: PubKeyCollection): CollectionDetailsViewModelFactory {
            return CollectionDetailsViewModelFactory(pubKeyCollection)
        }
    }
}
