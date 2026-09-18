package com.samourai.sentinel.tor

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import io.matthewnelson.kmp.file.resolve
import io.matthewnelson.kmp.tor.resource.noexec.tor.ResourceLoaderTorNoExec
import io.matthewnelson.kmp.tor.runtime.RuntimeEvent
import io.matthewnelson.kmp.tor.runtime.Action.Companion.startDaemonAsync
import io.matthewnelson.kmp.tor.runtime.Action.Companion.stopDaemonAsync
import io.matthewnelson.kmp.tor.runtime.TorRuntime
import io.matthewnelson.kmp.tor.runtime.core.OnEvent
import io.matthewnelson.kmp.tor.runtime.core.config.IntervalUnit
import io.matthewnelson.kmp.tor.runtime.core.config.TorOption
import io.matthewnelson.kmp.tor.runtime.core.ctrl.TorCmd
import io.matthewnelson.kmp.tor.runtime.core.util.executeAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * kmp-tor 2.x wrapper. Frozen public surface (all call sites unchanged):
 * setUp / start / stop / newIdentity / getTorState / getTorStateLiveData / getProxy
 */
object SentinelTorManager {

    private const val TAG = "SentinelTorManager"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var appContext: Application? = null
    @Volatile private var runtime: TorRuntime? = null
    @Volatile private var proxy: Proxy? = null

    private val torStateLiveDataPrivate = MutableLiveData(makeState(EnumTorState.OFF, 0))

    fun getTorStateLiveData(): LiveData<TorState> = torStateLiveDataPrivate

    @Synchronized
    fun setUp(app: Application) {
        if (runtime != null) return // idempotent: bottom sheets re-call setUp
        Log.i(TAG, "setUp() building runtime")
        appContext = app

        val env = TorRuntime.Environment.Builder(
            app.getDir("tor", Application.MODE_PRIVATE),
            app.getDir("tor_cache", Application.MODE_PRIVATE),
            ResourceLoaderTorNoExec::getOrCreate,
        )

        runtime = TorRuntime.Builder(env) {

            // kmp-tor contract: register an ERROR observer or it rethrows
            observerStatic(RuntimeEvent.ERROR) { t ->
                Log.e(TAG, "kmp-tor error", t)
            }

            observerStatic(RuntimeEvent.STATE, OnEvent.Executor.Immediate) { s ->
                val st = when {
                    s.daemon.isOn -> EnumTorState.ON
                    s.daemon.isStarting -> EnumTorState.STARTING
                    s.daemon.isStopping -> EnumTorState.STOPPING
                    else -> EnumTorState.OFF
                }
                Log.i(TAG, "state -> $st boot=${s.daemon.bootstrap.toInt()}")
                publish(st, s.daemon.bootstrap.toInt())
            }

            observerStatic(RuntimeEvent.LISTENERS, OnEvent.Executor.Immediate) { l ->
                proxy = l.socks.firstOrNull()?.let { s ->
                    Proxy(
                        Proxy.Type.SOCKS,
                        InetSocketAddress(s.address.value, s.port.value),
                    )
                }
            }

            config { environment ->
                TorOption.__SocksPort.configure { auto() }
                TorOption.ConnectionPadding.configure { disable() }
                TorOption.ReducedConnectionPadding.configure(true)
                TorOption.DormantClientTimeout.configure(10, IntervalUnit.MINUTES)
                TorOption.DormantCanceledByStartup.configure(true)
                TorOption.ClientOnionAuthDir.configure(
                    directory = environment.workDirectory
                        .resolve("auth_private_files"),
                )
            }
        }

        // Toast tor's NEWNYM reply (parity with 1.x wrapper).
        // Rate-limit notices are shown verbatim (tor's own text);
        // the 1.x library's kmp_tor_newnym_* res strings no longer
        // exist, so success uses a local literal.
        with(RuntimeEvent.EXECUTE.CMD) {
            runtime?.observeSignalNewNym(TAG, OnEvent.Executor.Main) { notice ->
                val app = appContext ?: return@observeSignalNewNym
                val msg = notice ?: "New Tor identity"
                Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun start() {
        val r = runtime
        if (r == null) {
            // DIAGNOSTIC: start() with no runtime is a silent no-op today;
            // make it visible in logcat.
            Log.w(TAG, "start() called but runtime is null (setUp never ran)")
            return
        }
        Log.i(TAG, "start() called")
        publish(EnumTorState.STARTING, 0)
        scope.launch {
            try {
                r.startDaemonAsync()
                Log.i(TAG, "startDaemonAsync returned")
            } catch (t: Throwable) {
                Log.e(TAG, "startDaemonAsync threw", t)
                publish(EnumTorState.OFF, 0)
            }
        }
    }

    fun stop() {
        Log.i(TAG, "stop() called")
        val r = runtime ?: return
        publish(EnumTorState.STOPPING, 0)
        scope.launch { r.stopDaemonAsync() }
    }

    fun newIdentity() {
        val r = runtime ?: return
        scope.launch {
            try {
                r.executeAsync(TorCmd.Signal.NewNym)
            } catch (t: Throwable) {
                Log.e(TAG, "newIdentity failed", t)
                appContext?.let {
                    Toast.makeText(it, "Tor identity change failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun getTorState(): TorState =
        torStateLiveDataPrivate.value ?: makeState(EnumTorState.OFF, 0)

    fun getProxy(): Proxy? = proxy

    private fun makeState(state: EnumTorState, progress: Int): TorState =
        TorState().apply {
            this.state = state
            this.progressIndicator = progress
        }

    private fun publish(state: EnumTorState, progress: Int) {
        torStateLiveDataPrivate.postValue(makeState(state, progress))
    }
}
