package com.v2ray.ang.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.root.RootProxyManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.lang.ref.SoftReference

/**
 * Foreground service for the root (system-wide) run modes. Unlike [CoreVpnService] it
 * does not use Android VpnService — traffic is routed by iptables instead
 * (see [RootProxyManager]).
 *
 * The in-process core is started first (so its listener is up and the foreground
 * notification is posted promptly), then the root routing rules are installed off the
 * main thread. On teardown the rules are removed before the core stops.
 */
class CoreRootService : Service(), ServiceControl {

    private var setupJob: Job? = null
    private val shutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val shutdownStarted = AtomicBoolean(false)
    private val shutdownCompleted = AtomicBoolean(false)
    @Volatile private var shutdownJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        LogUtil.i(AppConfig.TAG, "StartCore-Root: Service created")
        CoreServiceManager.serviceControl = SoftReference(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationManager.ensureForeground()
        LogUtil.i(AppConfig.TAG, "StartCore-Root: command received")

        // A reload has Xray stopped for a moment; a start on top of it would run beside the reload and
        // take the root rules and the tunnel down to install them again, with the traffic unrouted meanwhile.
        if (CoreServiceManager.isServiceRunning()) {
            LogUtil.i(AppConfig.TAG, "StartCore-Root: Core is already running")
            return START_STICKY
        }

        // Start the in-process core first (this also posts the foreground notification),
        // then install the root routing off the main thread.
        if (!CoreServiceManager.startCoreLoop(null)) {
            LogUtil.e(AppConfig.TAG, "StartCore-Root: core failed to start")
            stopService()
            return START_NOT_STICKY
        }

        setupJob = shutdownScope.launch {
            if (!RootProxyManager.start(this@CoreRootService)) {
                LogUtil.e(AppConfig.TAG, "StartCore-Root: failed to start root mode, stopping")
                stopService()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        if (!shutdownCompleted.get()) requestShutdown(stopSelfWhenDone = false)
        super.onDestroy()
    }

    private fun requestShutdown(stopSelfWhenDone: Boolean) {
        if (!shutdownStarted.compareAndSet(false, true)) return
        shutdownJob = shutdownScope.launch {
            try {
                setupJob?.cancelAndJoin()
                runCatching { RootProxyManager.stop(this@CoreRootService) }
                    .onFailure { LogUtil.e(AppConfig.TAG, "StartCore-Root: root cleanup failed (${it.javaClass.simpleName})") }
                runCatching { CoreServiceManager.stopCoreLoopAndJoin() }
                    .onFailure { LogUtil.e(AppConfig.TAG, "StartCore-Root: core shutdown failed (${it.javaClass.simpleName})") }
            } finally {
                shutdownCompleted.set(true)
                if (stopSelfWhenDone) withContext(Dispatchers.Main.immediate) { stopSelf() }
            }
        }
    }

    override fun getService(): Service = this

    override fun startService() {
        // do nothing
    }

    override fun stopService() {
        requestShutdown(stopSelfWhenDone = true)
    }

    override fun vpnProtect(socket: Int): Boolean = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let(AppLocaleManager::localizedContext)
        super.attachBaseContext(context)
    }
}
