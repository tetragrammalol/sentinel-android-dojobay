package com.samourai.sentinel.tor

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * kmp-tor 2.x wrapper. Frozen public surface (all call sites unchanged):
 * setUp / start / stop / newIdentity / getTorState / getTorStateLiveData / getProxy
 */
object SentinelTorManager {

    private const val TAG = "SentinelTorManager"

    // ---- #11 watchdog: silent boot=0 stall after force-stop cycles ----
    private const val BOOTSTRAP_STALL_MS = 150_000L // 2.5 min with zero boot progress
    private const val MAX_RECOVERIES = 2
    private const val STOP_DEADLINE_MS = 15_000L    // wedged daemon must die within this
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var lastBootstrapProgressAt = 0L
    @Volatile private var recoveries = 0
    @Volatile private var watchdogJob: Job? = null

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

    /** Public entry: user-directed start. Resets the retry budget. */
    fun start() {
        Log.i(TAG, "start() (manual)")
        recoveries = 0
        startInternal()
    }

    /** Recovery-safe start: never touches the retry budget. */
    private fun startInternal() {
        val r = runtime
        if (r == null) {
            Log.w(TAG, "start() called but runtime is null (setUp never ran)")
            return
        }
        Log.i(TAG, "start() called")
        lastBootstrapProgressAt = SystemClock.elapsedRealtime()
        publish(EnumTorState.STARTING, 0)
        armWatchdog()
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
        watchdogJob?.cancel()
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
        if (progress > 0 || state == EnumTorState.ON) {
            lastBootstrapProgressAt = SystemClock.elapsedRealtime()
        }
        torStateLiveDataPrivate.postValue(makeState(state, progress))
    }

    private fun armWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (true) {
                delay(10_000L)
                val st = getTorState()
                if (st.state == EnumTorState.ON || st.state == EnumTorState.OFF) return@launch
                val stalledMs = SystemClock.elapsedRealtime() - lastBootstrapProgressAt
                if (st.state == EnumTorState.STARTING &&
                    st.progressIndicator == 0 &&
                    stalledMs > BOOTSTRAP_STALL_MS
                ) {
                    attemptRecovery()
                    return@launch
                }
            }
        }
    }

    private fun attemptRecovery() {
        val app = appContext
        if (app == null || recoveries >= MAX_RECOVERIES) {
            Log.e(
                TAG,
                "watchdog: still wedged after $recoveries recovery attempt(s) — publishing OFF",
            )
            toast("Tor failed to bootstrap — retry Tor from settings")
            publish(EnumTorState.OFF, 0) // UI offers retryable error, not eternal "initializing"
            return
        }
        recoveries++
        Log.w(
            TAG,
            "watchdog: boot=0 stall > ${BOOTSTRAP_STALL_MS / 1000}s — " +
                "wiping tor data, recovery $recoveries/$MAX_RECOVERIES",
        )
        toast("Tor bootstrap stalled — auto-recovering ($recoveries/$MAX_RECOVERIES)")
        scope.launch {
            val wedged = runtime
            try {
                // stop FIRST: startDaemonAsync is deduped while the wedged daemon
                // is still "starting" — observed as a no-op in the #11 captures
                withTimeoutOrNull(STOP_DEADLINE_MS) { wedged?.stopDaemonAsync() }
            } catch (t: Throwable) {
                Log.w(TAG, "stopDaemonAsync during recovery threw (tolerated)", t)
            }
            runtime = null // release the wedged runtime
            app.getDir("tor", Application.MODE_PRIVATE).deleteRecursively()
            app.getDir("tor_cache", Application.MODE_PRIVATE).deleteRecursively()
            setUp(app)      // rebuild against fresh dirs (idempotent guard passes on null)
            startInternal() // re-arms the watchdog; budget preserved
        }
    }

    private fun toast(msg: String) {
        mainHandler.post {
            appContext?.let { Toast.makeText(it, msg, Toast.LENGTH_LONG).show() }
        }
    }
}
