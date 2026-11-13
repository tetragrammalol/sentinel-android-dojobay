package com.samourai.sentinel.ui.collectionDetails.transactions

import android.app.Application
import androidx.lifecycle.*
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.repository.ExchangeRateRepository
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.util.MonetaryUtil
import org.bitcoinj.core.Coin
import org.koin.java.KoinJavaComponent.inject


class TransactionsViewModel(application: Application) : AndroidViewModel(application) {


    private val exchangeRateRepository: ExchangeRateRepository by inject(ExchangeRateRepository::class.java)
    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java)

    private val message = MutableLiveData<String>()
    private val balance = MutableLiveData<Long>(0L)
    private lateinit var collection: PubKeyCollection

    fun setCollection(collection: PubKeyCollection) {
        this.collection = collection
    }

    fun getMessage(): LiveData<String> {
        return message
    }

    private fun updateBalance() {
        balance.postValue(collection.balance)
    }

    /**
     * Get balance live data for fragments
     * balance will be updated when transactions updated @see #getTransactions()
     */
    fun getBalance(): LiveData<Long> {
        return balance
    }

    fun getFiatBalance(): LiveData<String> {
        val mediatorLiveData = MediatorLiveData<String>();
        mediatorLiveData.addSource(exchangeRateRepository.getRateLive()) {
            mediatorLiveData.value = getFiatBalance(balance.value, it)
        }
        mediatorLiveData.addSource(balance) {
            mediatorLiveData.value = getFiatBalance(it, exchangeRateRepository.getRateLive().value)
        }
        return mediatorLiveData
    }


    private fun getFiatBalance(balance: Long?, rate: ExchangeRateRepository.Rate?): String {
        if (rate != null) {
            balance?.let {
                return try {
                    val fiatRate = MonetaryUtil.getInstance().getFiatFormat(prefsUtil.selectedCurrency)
                            .format((balance/1e8) * rate.rate)
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


    fun getBTCDisplayAmount(value: Long): String? {
        return Coin.valueOf(value).toPlainString()
    }


}