package com.v2ray.ang.ui.main

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.discovery.AicpMultiLocationPolicy
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.service.RealPingWorkerService
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ensureActive
import java.util.concurrent.TimeUnit

/**
 * Refreshes the selected tab every 19 minutes. AICP country/Multi-Location tabs use public discovery;
 * ordinary subscription tabs use the app's standard subscription updater. Discovery and native latency
 * tests run in WorkManager's isolated :bg process and never restart the live daemon tunnel.
 */
internal class AicpPublicConfigRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val selectedGroup = MmkvManager.decodeSettingsString(AppConfig.CACHE_SUBSCRIPTION_ID, "").orEmpty()
        val country = AicpSelectedRefreshTargetPolicy.resolveCountry(
            selectedGroup, AicpSharedState.targetCountry(applicationContext),
        )
        if (country == null) {
            val subscription = MmkvManager.decodeSubscription(selectedGroup)
            if (selectedGroup.isBlank() || subscription == null) {
                LogUtil.i("AICP-VPN", "19m refresh skipped: selected tab '$selectedGroup' has no refreshable subscription")
                return Result.success()
            }
            return runCatching {
                val result = AngConfigManager.updateConfigViaSub(SubscriptionCache(selectedGroup, subscription))
                MessageHelper.sendMsg2UI(applicationContext, AppConfig.MSG_SERVERS_CHANGED, selectedGroup)
                LogUtil.i("AICP-VPN", "19m selected subscription refresh complete: group=$selectedGroup configs=${result.configCount} updated=${result.successCount} failed=${result.failureCount} skipped=${result.skipCount}; no active tunnel restart")
                if (result.failureCount > 0 && result.successCount == 0 && result.skipCount == 0) Result.retry()
                else Result.success()
            }.getOrElse { error ->
                LogUtil.w("AICP-VPN", "19m selected subscription refresh failed for $selectedGroup", error)
                if (error is CancellationException) throw error
                Result.retry()
            }
        }
        val activeGuidAtWorkStart = MmkvManager.getSelectServer()
        val network = underlyingPhysicalNetwork() ?: return Result.retry()

        return try {
            currentCoroutineContext().ensureActive()
            val lastSuccess = AicpSharedState.lastRefreshSuccess(applicationContext, country)
            val sinceLastSuccess = System.currentTimeMillis() - lastSuccess
            if (lastSuccess > 0L && sinceLastSuccess in 0 until COLLISION_WINDOW_MS) {
                LogUtil.i("AICP-VPN", "19m refresh skipped: $country was refreshed by another app path in the last 2 minutes")
                return Result.success()
            }
            val fetched = PublicConfigFinder.fetch(country, network)
            if (fetched.successfulSources == 0) {
                LogUtil.w("AICP-VPN", "19m refresh sources all failed for $country; failed=${fetched.failedSources}")
                return Result.retry()
            }

            val groupId = "aicp-finder-${country.lowercase()}"
            if (MmkvManager.decodeSubscription(groupId) == null) {
                if (fetched.configs.isEmpty()) {
                    AicpSharedState.recordRefreshSuccess(applicationContext, country)
                    LogUtil.i("AICP-VPN", "19m refresh complete country=$country: feeds responded but yielded no importable configs")
                    return Result.success()
                }
                val created = MmkvManager.tryEncodeSubscription(
                    groupId,
                    SubscriptionItem(remarks = "AICP Finder · $country", url = "", enabled = true, autoUpdate = false),
                )
                if (created == null) return Result.retry()
            }

            if (fetched.configs.isNotEmpty()) {
                AngConfigManager.importBatchConfig(fetched.configs.joinToString("\n"), groupId, true)
            }
            val seen = HashSet<Any>()
            val activeGuidAtImport = MmkvManager.getSelectServer()
            val allGuids = MmkvManager.decodeServerList(groupId).toList()
            // Keep both the profile active when this batch began and any profile selected during import.
            allGuids.sortedBy { if (it == activeGuidAtImport) 0 else 1 }.forEach { guid ->
                val profile = MmkvManager.decodeServerConfig(guid)
                val activeGuidNow = MmkvManager.getSelectServer()
                if (guid == activeGuidAtWorkStart || guid == activeGuidAtImport || guid == activeGuidNow) {
                    profile?.let { seen.add(it.duplicateIdentity()) }
                } else if (profile == null || !seen.add(profile.duplicateIdentity())) {
                    // The scope is limited to duplicate/malformed profiles in this AICP-owned bank.
                    MmkvManager.removeServer(guid)
                }
            }
            // Mark feed/import success before potentially long candidate tests so the 19m cadence
            // remains anchored to refresh completion rather than test-batch duration.
            AicpSharedState.recordRefreshSuccess(applicationContext, country)
            val tested = try {
                testCountryBank(groupId, network, activeGuidAtWorkStart, activeGuidAtImport)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.w("AICP-VPN", "country bank imported but candidate tests did not finish; retry on next cadence", error)
                0
            }
            // A failed/aborted global latency batch must never erase the aggregate based on zeroed results.
            val countryHasReachableProfile = MmkvManager.decodeServerList(groupId).any { guid ->
                (MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L) > 0L
            }
            val multiSynced = if (tested > 0 && countryHasReachableProfile) {
                runCatching { syncMultiLocationFromCountry(groupId, country) }
                    .onFailure { error -> LogUtil.w("AICP-VPN", "country bank tested but Multi-Location reconciliation failed", error) }
                    .getOrDefault(0)
            } else 0
            runCatching {
                MessageHelper.sendMsg2UI(applicationContext, AppConfig.MSG_SERVERS_CHANGED, groupId)
                MessageHelper.sendMsg2UI(applicationContext, AppConfig.MSG_SERVERS_CHANGED, "aicp-multilocation")
            }.onFailure { error -> LogUtil.w("AICP-VPN", "country bank refreshed but UI notification failed", error) }
            val sourceSummary = fetched.sourceOutcomes.joinToString { "${it.sourceId}:${it.candidateCount}" }
            LogUtil.i("AICP-VPN", "19m refresh complete selectedTab=$selectedGroup country=$country sources=${fetched.successfulSources}/${fetched.sourceOutcomes.size} configs=${fetched.configs.size} latencyTested=$tested multiLocationSynced=$multiSynced feeds=[$sourceSummary]; no active tunnel restart")
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LogUtil.e("AICP-VPN", "19m public config refresh failed for $country", error)
            Result.retry()
        }
    }

    /**
     * Run latency/reachability tests in WorkManager's isolated :bg process, never in the daemon process
     * that owns the live tunnel. CoreVpnService's per-app policy always excludes this app's own package
     * from the VPN; binding this process to the selected physical Network also makes the test egress
     * deterministic without changing the daemon's sockets or active tunnel.
     */
    private suspend fun testCountryBank(
        groupId: String,
        network: Network,
        activeGuidAtWorkStart: String?,
        activeGuidAtImport: String?,
    ): Int {
        val guids = MmkvManager.decodeServerList(groupId).toList()
        if (guids.isEmpty()) return 0

        val connectivity = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val previousNetwork = connectivity.boundNetworkForProcess
        if (!connectivity.bindProcessToNetwork(network)) {
            LogUtil.w("AICP-VPN", "19m refresh imported configs but skipped latency tests: process could not bind to physical Network")
            return 0
        }

        try {
            currentCoroutineContext().ensureActive()
            CoreNativeManager.initCoreEnv(applicationContext)
            MmkvManager.clearAllTestDelayResults(guids)
            val finished = CompletableDeferred<Unit>()
            val tester = RealPingWorkerService(
                context = applicationContext,
                guids = guids,
                onlyTcp = false,
            ) { event ->
                when (event) {
                    is RealPingEvent.Result -> MmkvManager.encodeServerTestDelayMillis(event.guid, event.delayMillis)
                    is RealPingEvent.Finish -> finished.complete(Unit)
                    is RealPingEvent.Progress -> Unit
                }
            }
            try {
                tester.start()
                withTimeout(TEST_BATCH_TIMEOUT_MS) { finished.await() }
            } catch (timeout: TimeoutCancellationException) {
                tester.cancel()
                throw IllegalStateException("AICP country latency batch exceeded its time budget", timeout)
            } catch (cancelled: CancellationException) {
                tester.cancel()
                throw cancelled
            } catch (error: Throwable) {
                tester.cancel()
                throw error
            }

            val activeGuidAtPruneStart = MmkvManager.getSelectServer()
            val successful = guids.count { (MmkvManager.decodeServerAffiliationInfo(it)?.testDelayMillis ?: 0L) > 0L }
            val networkStillValidated = isPhysicalNetworkValidated(network)
            var removed = 0
            if (successful > 0 && networkStillValidated) {
                guids.forEach { guid ->
                    currentCoroutineContext().ensureActive()
                    val delay = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L
                    // Zero means unmeasured; only a measured negative failure is eligible for pruning.
                    val activeGuidNow = MmkvManager.getSelectServer()
                    if (guid != activeGuidAtWorkStart && guid != activeGuidAtImport &&
                        guid != activeGuidAtPruneStart && guid != activeGuidNow &&
                        delay < 0L && MmkvManager.tryRemoveServer(guid)) removed++
                }
            } else {
                LogUtil.w("AICP-VPN", "19m pruning suppressed: successfulTests=$successful physicalNetworkValidated=$networkStillValidated; preserve candidates on a global probe/network failure")
            }
            val throughputUpdated = if (successful > 0 && networkStillValidated) {
                AicpThroughputTester.measureForGroup(applicationContext, groupId, network, force = true)
            } else 0
            AicpThroughputTester.sortGroupByThroughput(applicationContext, groupId)
            LogUtil.i("AICP-VPN", "19m physical-network tests complete group=$groupId latencyTested=${guids.size} latencySuccessful=$successful throughputRecordsUpdated=$throughputUpdated removed=$removed activeProfilesProtected=${listOfNotNull(activeGuidAtWorkStart, activeGuidAtImport, activeGuidAtPruneStart).distinct().size}")
            return guids.size
        } finally {
            // Restore only this worker process; the :daemon VPN process and its sockets are untouched.
            connectivity.bindProcessToNetwork(previousNetwork)
        }
    }

    private fun syncMultiLocationFromCountry(countryGroupId: String, country: String): Int {
        val multiGroupId = "aicp-multilocation"
        if (MmkvManager.decodeSubscription(multiGroupId) == null) {
            if (MmkvManager.tryEncodeSubscription(
                    multiGroupId,
                    SubscriptionItem(remarks = "AICP Multi-Location", url = "", enabled = true, autoUpdate = false),
                ) == null) return 0
        }
        val prefs = applicationContext.getSharedPreferences("aicp_multilocation_origin", Context.MODE_PRIVATE)
        val multiGuids = MmkvManager.decodeServerList(multiGroupId).toMutableList()
        var synced = 0
        MmkvManager.decodeServerList(countryGroupId).forEach { sourceGuid ->
            val sourceInfo = MmkvManager.decodeServerAffiliationInfo(sourceGuid) ?: return@forEach
            if (!AicpMultiLocationPolicy.qualifiesRealPing(sourceInfo.testDelayMillis)) return@forEach
            val profile = MmkvManager.decodeServerConfig(sourceGuid) ?: return@forEach
            val identity = profile.duplicateIdentity()
            val destinationGuid = multiGuids.firstOrNull { guid ->
                MmkvManager.decodeServerConfig(guid)?.duplicateIdentity() == identity
            } ?: runCatching {
                MmkvManager.encodeServerConfig("", profile.copy(subscriptionId = multiGroupId)).also { multiGuids += it }
            }.getOrNull() ?: return@forEach
            prefs.edit().putString(destinationGuid, country).apply()
            MmkvManager.encodeServerTestDelayMillis(destinationGuid, sourceInfo.testDelayMillis)
            val destinationInfo = MmkvManager.decodeServerAffiliationInfo(destinationGuid)
            if (sourceInfo.throughputMeasuredAtMillis > 0L &&
                sourceInfo.throughputMeasuredAtMillis >= (destinationInfo?.throughputMeasuredAtMillis ?: 0L)) {
                MmkvManager.encodeServerThroughput(
                    guid = destinationGuid,
                    downloadBitsPerSecond = sourceInfo.downloadBitsPerSecond,
                    uploadBitsPerSecond = sourceInfo.uploadBitsPerSecond,
                    measuredAtMillis = sourceInfo.throughputMeasuredAtMillis,
                    downloadBytes = sourceInfo.throughputDownloadBytes,
                    uploadBytes = sourceInfo.throughputUploadBytes,
                    downloadDurationMillis = sourceInfo.throughputDownloadDurationMillis,
                    uploadDurationMillis = sourceInfo.throughputUploadDurationMillis,
                    sampleCount = sourceInfo.throughputSampleCount,
                    downloadVariationPercent = sourceInfo.throughputDownloadVariationPercent,
                    uploadVariationPercent = sourceInfo.throughputUploadVariationPercent,
                    loadedLatencyMillis = sourceInfo.throughputLoadedLatencyMillis,
                    probeLossPercent = sourceInfo.throughputProbeLossPercent,
                    error = sourceInfo.throughputError,
                )
            }
            synced++
        }
        // Remove stale/duplicate generated entries only when no country bank still owns that identity.
        val validIdentities = MmkvManager.decodeSubsList()
            .filter { it.startsWith("aicp-finder-") && it.length == "aicp-finder-".length + 2 }
            .flatMap { bank -> MmkvManager.decodeServerList(bank).mapNotNull { guid ->
                val info = MmkvManager.decodeServerAffiliationInfo(guid) ?: return@mapNotNull null
                if (!AicpMultiLocationPolicy.qualifiesRealPing(info.testDelayMillis)) return@mapNotNull null
                MmkvManager.decodeServerConfig(guid)?.duplicateIdentity()
            } }.toHashSet()
        val activeGuid = MmkvManager.getSelectServer()
        val seen = HashSet<Any>()
        multiGuids.forEach { guid ->
            val identity = MmkvManager.decodeServerConfig(guid)?.duplicateIdentity()
            if (guid == activeGuid) {
                if (identity != null) seen.add(identity)
            } else if (identity == null || identity !in validIdentities || !seen.add(identity)) {
                MmkvManager.tryRemoveServer(guid)
            }
        }
        AngConfigManager.sortByThroughputForSub(multiGroupId)
        LogUtil.i("AICP-VPN", "19m Multi-Location sync country=$country source=$countryGroupId synced=$synced total=${MmkvManager.decodeServerList(multiGroupId).size}")
        return synced
    }

    private companion object {
        const val COLLISION_WINDOW_MS = 2L * 60L * 1000L
        const val TEST_BATCH_TIMEOUT_MS = 6L * 60L * 1000L
    }

    private fun isPhysicalNetworkValidated(network: Network): Boolean = runCatching {
        val manager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = manager.getNetworkCapabilities(network) ?: return@runCatching false
        !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)

    private fun underlyingPhysicalNetwork(): Network? = runCatching {
        val manager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = manager.activeNetwork
        val activeCapabilities = active?.let(manager::getNetworkCapabilities)
        if (active != null && activeCapabilities != null &&
            !activeCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            activeCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            activeCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return@runCatching active
        val physical = manager.allNetworks.mapNotNull { candidate ->
            val capabilities = manager.getNetworkCapabilities(candidate) ?: return@mapNotNull null
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
            candidate to capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        physical.firstOrNull { it.second }?.first ?: physical.firstOrNull()?.first
    }.getOrNull()

}

/** WorkManager's periodic interval is inexact under Doze/battery policy; 19 minutes is the requested cadence. */
internal object AicpPublicConfigRefreshScheduler {
    private const val UNIQUE_WORK_NAME = "aicp-public-config-refresh-19m"
    private const val INTERVAL_MINUTES = 19L

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<AicpPublicConfigRefreshWorker>(
            INTERVAL_MINUTES, TimeUnit.MINUTES,
        ).setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
        ).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS).build()

        RemoteWorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
