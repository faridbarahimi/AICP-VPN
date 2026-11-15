package com.v2ray.ang.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Network
import android.net.ProxyInfo
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.v2ray.ang.AppConfig
import com.v2ray.ang.routing.PerAppRoutingPolicy
import com.v2ray.ang.AppConfig.LOOPBACK
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.contracts.Tun2SocksControl
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.root.RootLanSharing
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import java.lang.ref.SoftReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("VpnServicePolicy")
class CoreVpnService : VpnService(), ServiceControl {
    private lateinit var mInterface: ParcelFileDescriptor
    private var isRunning = false
    private var tun2SocksService: Tun2SocksControl? = null
    private val isStartingLock = AtomicBoolean(false)
    private val shutdownStarted = AtomicBoolean(false)
    private val shutdownCompleted = AtomicBoolean(false)
    private val shutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var shutdownJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service created")
        CoreServiceManager.serviceControl = SoftReference(this)
    }

    override fun onRevoke() {
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: Permission revoked")
        stopAllService()
    }

//    override fun onLowMemory() {
//        stopV2Ray()
//        super.onLowMemory()
//    }

    override fun onDestroy() {
        // A normal stop reaches here only after the awaited shutdown job has completed. If Android
        // destroys the service independently, schedule best-effort cleanup without blocking main.
        if (!shutdownCompleted.get()) stopAllService(isForced = false)
        unlockStart()
        super.onDestroy()
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service destroyed")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationManager.ensureForeground()
        // Always-on VPN restarts from OS deliver intent.action == SERVICE_INTERFACE or null intent.
        // Reset any stuck start lock left by a killed process to allow setupVpnService() to run.
        val isSystemVpnStart = intent == null || intent.action == SERVICE_INTERFACE
        if (isSystemVpnStart) {
            unlockStart()
        }
        if (!tryLockStart()) {
            LogUtil.w(AppConfig.TAG, "StartCore-VPN: Start already in progress")
            return START_NOT_STICKY
        }
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service command received, systemVpnStart=$isSystemVpnStart")
        if (!setupVpnService()) {
            unlockStart()
            // Stop service if setup fails to avoid infinite restart loops (START_STICKY)
            stopSelf()
            return START_NOT_STICKY
        }
        startService()
        return START_STICKY
    }

    override fun getService(): Service {
        return this
    }

    override fun startService() {
        if (!::mInterface.isInitialized) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Interface not initialized")
            return
        }
        if (!CoreServiceManager.startCoreLoop(mInterface)) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to start core loop")
            stopAllService()
            return
        }
        // tun2socks sets itself up on its own thread while Xray starts and ends without a word when
        // that fails. This one look, at the end of the start, is what tells; nothing looks again.
        if (!Tun2SocksControl.startMayGoOn(tun2SocksService) { it.isTun2SocksRunning() }) {
            failTun2Socks("gave up while setting itself up")
            return
        }

        // Start LAN sharing if enabled in settings
        RootLanSharing.startClientSharing(this)
    }

    override fun stopService() {
        stopAllService(true)
    }

    override fun vpnProtect(socket: Int): Boolean {
        return protect(socket)
    }

    override fun setUnderlyingNetworks(networks: Array<Network>?): Boolean {
        return super<VpnService>.setUnderlyingNetworks(networks)
    }

    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let(AppLocaleManager::localizedContext)
        super.attachBaseContext(context)
    }

    /**
     * Sets up the VPN service.
     * Prepares the VPN and configures it if preparation is successful.
     */
    private fun setupVpnService(): Boolean {
        val prepare = prepare(this)
        if (prepare != null) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Permission not granted")
            return false
        }

        if (configureVpnService() != true) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Configuration failed")
            return false
        }

        if (!runTun2socks()) {
            failTun2Socks("could not be started")
            return false
        }
        return true
    }

    /**
     * Ends a start whose tun2socks is not there. The interface is up by then and takes every packet
     * of the device, so a start that went on would leave Xray, the Aether core and the main screen
     * connected over a tunnel nothing reads.
     */
    private fun failTun2Socks(what: String) {
        LogUtil.e(AppConfig.TAG, "StartCore-VPN: tun2socks $what, stopping the service, guid=${MmkvManager.getSelectServer()}")
        MessageHelper.sendMsg2UI(this, AppConfig.MSG_STATE_START_FAILURE, "")
        stopAllService()
    }

    /**
     * Configures the VPN service.
     * @return True if the VPN service was configured successfully, false otherwise.
     */
    private fun configureVpnService(): Boolean {
        val builder = Builder()

        // Configure network settings (addresses, routing and DNS)
        configureNetworkSettings(builder)

        // Configure app-specific settings (session name and per-app proxy).
        configurePerAppProxy(builder)

        // Close the old interface since the parameters have been changed
        try {
            if (::mInterface.isInitialized) {
                mInterface.close()
            }
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Failed to close old interface", e)
        }

        // Configure platform-specific features
        configurePlatformFeatures(builder)

        // Create a new interface using the builder and save the parameters
        try {
            mInterface = builder.establish()!!
            isRunning = true
            return true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to establish VPN interface", e)
            stopAllService()
        }
        return false
    }

    /**
     * Configures the basic network settings for the VPN.
     * This includes IP addresses, routing rules, and DNS servers.
     *
     * @param builder The VPN Builder to configure
     */
    private fun configureNetworkSettings(builder: Builder) {
        val vpnConfig = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        val webRtcProtection = SettingsManager.isWebRtcLeakProtectionEnabled()
        val bypassLan = com.v2ray.ang.routing.WebRtcLeakProtectionPolicy.bypassLan(
            SettingsManager.routingRulesetsBypassLan(), webRtcProtection
        )
        val ipv6Enabled = com.v2ray.ang.routing.WebRtcLeakProtectionPolicy.ipv6Enabled(
            MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED) == true, webRtcProtection
        )
        if (webRtcProtection) {
            LogUtil.i(AppConfig.TAG, "WebRTC leak protection active: full IPv4/IPv6 tunnel, LAN bypass disabled")
        }

        // Configure IPv4 settings
        builder.setMtu(SettingsManager.getVpnMtu())
        builder.addAddress(vpnConfig.ipv4Client, 30)

        // Configure routing rules
        if (bypassLan) {
            AppConfig.ROUTED_IP_LIST.forEach {
                val addr = it.split('/')
                builder.addRoute(addr[0], addr[1].toInt())
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
        }

        // Configure IPv6 if enabled
        if (ipv6Enabled) {
            builder.addAddress(vpnConfig.ipv6Client, 126)
            if (com.v2ray.ang.routing.WebRtcLeakProtectionPolicy.routeAllIpv6(
                    SettingsManager.routingRulesetsBypassLan(), webRtcProtection
                )) {
                builder.addRoute("::", 0)
            } else {
                builder.addRoute("2000::", 3) // Preserve legacy global-unicast-only routing when LAN bypass is requested
            }
        }

        // Configure DNS servers
        //if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED) == true) {
        //  builder.addDnsServer(PRIVATE_VLAN4_ROUTER)
        //} else {
        com.v2ray.ang.routing.VpnDnsFamilyPolicy
            .filterDnsServers(SettingsManager.getVpnDnsServers(), ipv6Enabled)
            .filter { Utils.isPureIpAddress(it) }
            .forEach(builder::addDnsServer)

        //builder.setSession(V2RayServiceManager.getRunningServerName())
    }

    /**
     * Configures the VPN's metering and HTTP proxy.
     *
     * @param builder The VPN Builder to configure
     */
    private fun configurePlatformFeatures(builder: Builder) {
        builder.setMetered(false)
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_APPEND_HTTP_PROXY)) {
            builder.setHttpProxy(ProxyInfo.buildDirectProxy(LOOPBACK, SettingsManager.getHttpPort()))
        }
    }

    /**
     * Configures per-app proxy rules for the VPN builder.
     * - With per-app routing disabled, all apps except this VPN app use the tunnel.
     * - In unified mode, checked apps use the tunnel and unchecked apps connect directly.
     * - An empty allow-list is explicitly converted to deny-all, never Android's implicit allow-all.
     */
    private fun configurePerAppProxy(builder: Builder) {
        val selfPackageName = BuildConfig.APPLICATION_ID
        val installed = packageManager.getInstalledApplications(0).map { it.packageName }.toSet()
        val selected = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)
            ?.toSet().orEmpty()
        val plan = PerAppRoutingPolicy.builderPlan(
            installedPackages = installed,
            selfPackageName = selfPackageName,
            perAppEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY),
            bypassApps = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS),
            selectedPackages = selected,
            forceAllApps = SettingsManager.isWebRtcLeakProtectionEnabled(),
        )
        when (plan) {
            is PerAppRoutingPolicy.BuilderPlan.Disallow -> plan.packages.forEach { packageName ->
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (e: PackageManager.NameNotFoundException) {
                    LogUtil.w(AppConfig.TAG, "Per-app disallow package disappeared during setup: $packageName")
                }
            }
            is PerAppRoutingPolicy.BuilderPlan.AllowOnly -> {
                var addedAllowedApps = 0
                plan.packages.forEach { packageName ->
                    try {
                        builder.addAllowedApplication(packageName)
                        addedAllowedApps++
                    } catch (e: PackageManager.NameNotFoundException) {
                        LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to allow selected app $packageName", e)
                    }
                }
                if (addedAllowedApps == 0) {
                    // If all allow calls failed, Android would otherwise treat the allow-list as absent.
                    (installed + selfPackageName).forEach { packageName ->
                        try {
                            builder.addDisallowedApplication(packageName)
                        } catch (e: PackageManager.NameNotFoundException) {
                            LogUtil.w(AppConfig.TAG, "Per-app deny-all package disappeared during setup: $packageName")
                        }
                    }
                }
            }
        }
    }

    /**
     * Runs the tun2socks process.
     * Starts the tun2socks process with the appropriate parameters.
     */
    private fun runTun2socks(): Boolean {
        tun2SocksService = Tun2SocksControl.createHevOrFallback(
            enabled = SettingsManager.isUsingHevTun(),
            create = {
                TProxyService(
                    context = applicationContext,
                    vpnInterface = mInterface,
                    isRunningProvider = { isRunning },
                    restartCallback = { runTun2socks() }
                )
            },
            onUnavailable = { error ->
                LogUtil.e(
                    AppConfig.TAG,
                    "StartCore-VPN: HEV JNI library unavailable; disabling HEV and falling back to Xray TUN",
                    error
                )
                // This runs before CoreServiceManager builds the core config, so Xray can configure
                // its own TUN path on this start. Persist the fallback to avoid repeating the crash.
                MmkvManager.encodeSettings(AppConfig.PREF_USE_HEV_TUNNEL, false)
            }
        )

        return Tun2SocksControl.startMayGoOn(tun2SocksService) { it.startTun2Socks() }
    }

    private fun stopAllService(isForced: Boolean = true) {
        unlockStart()
        isRunning = false
        if (!shutdownStarted.compareAndSet(false, true)) return

        shutdownJob = shutdownScope.launch {
            try {
                val tunControl = tun2SocksService
                tun2SocksService = null
                runCatching { tunControl?.stopTun2Socks() }
                    .onFailure { LogUtil.w(AppConfig.TAG, "StartCore-VPN: tun2socks stop failed (${it.javaClass.simpleName})") }
                runCatching { RootLanSharing.stopClientSharing(this@CoreVpnService) }
                    .onFailure { LogUtil.w(AppConfig.TAG, "StartCore-VPN: LAN sharing cleanup failed (${it.javaClass.simpleName})") }
                runCatching { CoreServiceManager.stopCoreLoopAndJoin() }
                    .onFailure { LogUtil.e(AppConfig.TAG, "StartCore-VPN: core shutdown failed (${it.javaClass.simpleName})") }
                runCatching {
                    if (::mInterface.isInitialized) {
                        mInterface.close()
                        LogUtil.i(AppConfig.TAG, "StartCore-VPN: VPN interface closed after core shutdown")
                    }
                }.onFailure { LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to close interface (${it.javaClass.simpleName})") }
            } finally {
                shutdownCompleted.set(true)
                if (isForced) withContext(Dispatchers.Main.immediate) { stopSelf() }
            }
        }
    }


    fun tryLockStart(): Boolean {
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: tryLockStart: ${isStartingLock.get()}")
        return isStartingLock.compareAndSet(false, true)
    }

    fun unlockStart() {
        isStartingLock.set(false)
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: unlockStart")
    }
}
