package com.v2ray.ang.ui.perappproxy

import android.app.Application
import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.routing.PerAppRoutingPolicy
import com.v2ray.ang.dto.AppInfo
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.AppSelection
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.AppManagerUtil
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.text.Collator

/**
 * ViewModel for PerAppProxy screen.
 * Holds all UI state and business logic.
 */
class PerAppProxyViewModel(application: Application) : BaseViewModel(application) {

    // Blacklist (apps to be proxied or bypassed)
    private val _blacklist = MutableStateFlow(loadBlacklist())
    val blacklist: StateFlow<Set<String>> = _blacklist.asStateFlow()

    // UI states
    private val _displayedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val displayedApps: StateFlow<List<AppInfo>> = _displayedApps.asStateFlow()

    private val _perAppProxyEnabled = MutableStateFlow(
        MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY, false)
    )
    val perAppProxyEnabled: StateFlow<Boolean> = _perAppProxyEnabled.asStateFlow()

    private val _rootMode = MutableStateFlow(SettingsManager.isRootMode())
    val rootMode: StateFlow<Boolean> = _rootMode.asStateFlow()

    private val _bypassApps = MutableStateFlow(
        MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS, false)
    )
    val bypassApps: StateFlow<Boolean> = _bypassApps.asStateFlow()

    // Cached full list for filtering
    private var appsAll: List<AppInfo>? = null
    private var currentQuery = ""
    private var isAppListLoading = false

    // Blacklist operations
    fun toggle(packageName: String) {
        val allPackages = appsAll.orEmpty().map { it.packageName }.toSet()
        if (packageName !in allPackages) return
        val newSelection = PerAppRoutingPolicy.selectionAfterToggle(
            packageName = packageName,
            installedPackages = allPackages,
            selectedVpnPackages = _blacklist.value,
            perAppEnabled = _perAppProxyEnabled.value,
        )
        val selectionChanged = replaceBlacklist(newSelection)
        // If per-app routing was disabled, the first checkbox action creates a normal
        // VPN allow-list; do not reinterpret that materialized list as a bypass list.
        val modeChanged = persistRoutingMode(
            perAppEnabled = true,
            bypassApps = _perAppProxyEnabled.value && _bypassApps.value,
            restartIfChanged = false,
        )
        if (selectionChanged || modeChanged) SettingsChangeManager.makeRestartService()
    }

    private fun loadBlacklist(): Set<String> {
        return MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)?.toSet() ?: emptySet()
    }

    private fun replaceBlacklist(newBlacklist: Set<String>): Boolean {
        if (newBlacklist == _blacklist.value) return false

        _blacklist.value = newBlacklist
        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY_SET, newBlacklist.toMutableSet())
        return true
    }

    // Per‑app proxy switch
    fun setPerAppProxyEnabled(enabled: Boolean) {
        persistRoutingMode(perAppEnabled = enabled, bypassApps = _bypassApps.value)
    }

    fun setBypassAppsEnabled(enabled: Boolean) {
        // Bypass has no effect while per-app routing is off. Enabling it therefore
        // atomically enables per-app routing as well, starting with an empty bypass set.
        persistRoutingMode(perAppEnabled = _perAppProxyEnabled.value || enabled, bypassApps = enabled)
    }

    private fun persistRoutingMode(
        perAppEnabled: Boolean,
        bypassApps: Boolean,
        restartIfChanged: Boolean = true,
    ): Boolean {
        val changed = _perAppProxyEnabled.value != perAppEnabled || _bypassApps.value != bypassApps
        _perAppProxyEnabled.value = perAppEnabled
        _bypassApps.value = bypassApps
        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY, perAppEnabled)
        MmkvManager.encodeSettings(AppConfig.PREF_BYPASS_APPS, bypassApps)
        if (changed && restartIfChanged) SettingsChangeManager.makeRestartService()
        return changed
    }

    // Load and filter apps
    fun loadApps(context: Context) {
        if (appsAll != null || isAppListLoading) return

        val applicationContext = context.applicationContext
        isAppListLoading = true
        launchLoading {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    AppManagerUtil.loadNetworkAppList(applicationContext)
                }
                normalizeLegacySettings(loaded.map { it.packageName })
                val apps = withContext(Dispatchers.Default) { sortApps(loaded) }
                appsAll = apps
                _displayedApps.value = applyFilter(currentQuery)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtil.e(AppConfig.ANG_PACKAGE, "Error loading apps", e)
            } finally {
                isAppListLoading = false
            }
        }
    }

    private fun normalizeLegacySettings(installedPackages: Collection<String>) {
        val oldSelection = _blacklist.value
        val oldBypass = _bypassApps.value
        val normalized = PerAppRoutingPolicy.normalize(
            installedPackages = installedPackages,
            storedSelection = oldSelection,
            perAppEnabled = _perAppProxyEnabled.value,
            bypassApps = oldBypass,
        )
        val changed = normalized.selectedVpnPackages != oldSelection || normalized.bypassApps != oldBypass
        _blacklist.value = normalized.selectedVpnPackages
        _bypassApps.value = normalized.bypassApps
        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY_SET, normalized.selectedVpnPackages.toMutableSet())
        MmkvManager.encodeSettings(AppConfig.PREF_BYPASS_APPS, normalized.bypassApps)
        if (changed && normalized.perAppEnabled) SettingsChangeManager.makeRestartService()
    }

    fun filterApps(query: String) {
        currentQuery = query
        _displayedApps.value = applyFilter(query)
    }

    private fun applyFilter(query: String): List<AppInfo> {
        val apps = appsAll ?: return emptyList()
        if (query.isEmpty()) return apps

        return apps.filter {
            it.appName.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
        }
    }

    private fun sortApps(apps: List<AppInfo>): List<AppInfo> {
        val collator = Collator.getInstance()
        val selectedPackages = _blacklist.value
        val perAppEnabled = _perAppProxyEnabled.value
        val bypassApps = _bypassApps.value
        return apps.sortedWith { p1, p2 ->
            val s1 = PerAppRoutingPolicy.isVpnSelected(p1.packageName, perAppEnabled, selectedPackages, bypassApps)
            val s2 = PerAppRoutingPolicy.isVpnSelected(p2.packageName, perAppEnabled, selectedPackages, bypassApps)
            when {
                s1 && !s2 -> -1
                !s1 && s2 -> 1
                p1.isSystemApp && !p2.isSystemApp -> 1
                !p1.isSystemApp && p2.isSystemApp -> -1
                else -> collator.compare(p1.appName, p2.appName)
            }
        }
    }

    // Bulk actions
    fun selectAll() {
        val visible = _displayedApps.value.map { it.packageName }.toSet()
        val next = when {
            !_perAppProxyEnabled.value -> appsAll.orEmpty().map { it.packageName }.toSet()
            _bypassApps.value -> _blacklist.value - visible
            else -> _blacklist.value + visible
        }
        applyUnifiedSelection(next, bypassMode = _perAppProxyEnabled.value && _bypassApps.value)
    }

    fun invertSelection() {
        val allPackages = appsAll.orEmpty().map { it.packageName }.toSet()
        val effective = if (_perAppProxyEnabled.value) _blacklist.value else allPackages
        val next = AppSelection.invert(effective, _displayedApps.value.map { it.packageName })
        applyUnifiedSelection(next, bypassMode = _perAppProxyEnabled.value && _bypassApps.value)
    }

    private fun applyUnifiedSelection(selection: Set<String>, bypassMode: Boolean) {
        val selectionChanged = replaceBlacklist(selection)
        val modeChanged = persistRoutingMode(perAppEnabled = true, bypassApps = bypassMode, restartIfChanged = false)
        if (selectionChanged || modeChanged) SettingsChangeManager.makeRestartService()
    }

    fun selectProxyAppAuto(context: Context) {
        val applicationContext = context.applicationContext
        launchLoading {
            val url = AppConfig.ANDROID_PACKAGE_NAME_LIST_URL
            var content = withContext(Dispatchers.IO) {
                HttpUtil.getUrlContent(
                    UrlContentRequest(
                        url = url,
                        timeout = 5000
                    )
                )
            }
            if (content.isNullOrEmpty()) {
                val proxyUsername = SettingsManager.getSocksUsername()
                val proxyPassword = SettingsManager.getSocksPassword()
                val httpPort = SettingsManager.getHttpPort()
                content = withContext(Dispatchers.IO) {
                    HttpUtil.getUrlContent(
                        UrlContentRequest(
                            url = url,
                            timeout = 5000,
                            httpPort = httpPort,
                            proxyUsername = proxyUsername,
                            proxyPassword = proxyPassword
                        )
                    )
                } ?: ""
            }
            val success = applyProxyAppList(
                content = content,
                context = applicationContext,
                forceGoogleApps = true
            )
            if (success) {
                enablePerAppProxyAndRestart()
            }
        }
    }

    fun importProxyApp(content: String?, context: Context) {
        if (content.isNullOrEmpty()) return

        val applicationContext = context.applicationContext
        launchLoading {
            val success = applyProxyAppList(
                content = content,
                context = applicationContext,
                forceGoogleApps = false
            )
            if (success) {
                enablePerAppProxyAndRestart()
            }
        }
    }

    fun exportProxyApp(): String = AppSelection.exportSelection(_bypassApps.value, _blacklist.value)

    private suspend fun applyProxyAppList(content: String, context: Context, forceGoogleApps: Boolean): Boolean {
        val installedApps = appsAll ?: return false

        try {
            val rawList = if (content.isEmpty()) {
                withContext(Dispatchers.IO) {
                    Utils.readTextFromAssets(context, "proxy_package_name")
                }
            } else content
            if (rawList.isNullOrEmpty()) return false

            val exportedSelection = AppSelection.parseExportedSelection(
                rawList,
                installedApps.map { it.packageName },
            )
            val targetBypassMode = exportedSelection?.bypassApps ?: _bypassApps.value
            val newBlacklist = withContext(Dispatchers.Default) {
                exportedSelection?.selectedPackages ?: AppSelection.fromProxyList(
                    packageNames = installedApps.map { it.packageName },
                    proxyAppList = rawList,
                    bypassApps = targetBypassMode,
                    forceGoogleApps = forceGoogleApps,
                )
            }
            replaceBlacklist(newBlacklist)
            if (exportedSelection != null) _bypassApps.value = targetBypassMode
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Error selecting proxy app", e)
            return false
        }
        return true
    }

    private fun enablePerAppProxyAndRestart() {
        persistRoutingMode(perAppEnabled = true, bypassApps = _bypassApps.value, restartIfChanged = false)
        SettingsChangeManager.makeRestartService()
    }
}
