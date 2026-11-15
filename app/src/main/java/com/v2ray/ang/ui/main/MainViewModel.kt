package com.v2ray.ang.ui.main

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.LocateTarget
import com.v2ray.ang.dto.RealPingResult
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.core.AicpEgressVerifier
import com.v2ray.ang.discovery.AicpAutoTestPolicy
import com.v2ray.ang.discovery.AicpMultiLocationPolicy
import com.v2ray.ang.discovery.AicpGeneratedGroupPolicy
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.extension.delay
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.matchesPattern
import com.v2ray.ang.extension.moveItem
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.PatternSyntaxException

private fun applyTestDelayResults(
    servers: List<ServersCache>,
    updates: Map<String, Long>,
): List<ServersCache> = servers.map { server ->
    val delayMillis = updates[server.guid]
    if (delayMillis == null || delayMillis == server.testDelayMillis) {
        server
    } else {
        server.copy(testDelayMillis = delayMillis)
    }
}

private fun applyTestDelayResultsToRows(
    rows: List<ServerRowUiModel>,
    updates: Map<String, Long>,
): List<ServerRowUiModel> = rows.map { row ->
    val delayMillis = updates[row.guid]
    if (delayMillis == null || delayMillis == row.testDelayMillis) {
        row
    } else {
        row.copy(testDelayMillis = delayMillis)
    }
}

class MainViewModel(
    application: Application,
    private val dataSource: MainDataSource
) : BaseViewModel(application) {

    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
    private val preloadDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
    private var autoPruneGuidsAfterTest: Set<String> = emptySet()
    private var autoPruneGroupId: String? = null
    private var autoPruneAnyReachableAfterTest: Boolean = false
    /** Background startup tests rank a country bank but must not replace the user's selected server. */
    private var autoSelectFastestAfterTest: Boolean = true
    private var forceThroughputAfterTest: Boolean = false
    private var autoSelectGuidsAfterTest: Set<String> = emptySet()
    private var smartConnectCycleRunning: Boolean = false
    /** Captures the source profile/list for the duration of a recovery batch, even if the user stops or disables Smart Connect mid-test. */
    private var smartConnectCycleSourceGuid: String? = null
    private var smartConnectCycleSourceGroupId: String? = null
    /** Only this request may trigger automatic failover; manual ping results must never start recovery. */
    private var smartConnectProbeRequestId: String? = null
    private var smartConnectProbeGuid: String? = null
    private var smartConnectPendingFailoverGuid: String? = null
    /** Recovery hysteresis exits only after consecutive healthy active-tunnel probes. */
    private var smartConnectRecoveryMode: Boolean = false
    private var smartConnectHealthyProbeCount: Int = 0
    private var smartConnectUnhealthySinceElapsedMillis: Long? = null
    private var smartConnectUnhealthyProbeGuid: String? = null
    private var smartConnectUnhealthyDelayMillis: Long? = null
    private var smartConnectDegradedSamples: Int = 0
    private val smartConnectRecoveryGuard = AicpSmartConnectRecoveryGuard { SystemClock.elapsedRealtime() }
    private var smartConnectLastTrafficSampleAt: Long = 0L
    private var smartConnectActiveObservedSpeedBitsPerSecond: Long = 0L
    private var manualServiceStopRequested: Boolean = false
    /** Tab-scoped disconnect recovery intent; retained until the selected-list ping batch has settled. */
    private var pendingUnexpectedDisconnectGroupId: String? = null
    private var autoReconnectJob: Job? = null
    private val _autoReconnectRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val autoReconnectRequests: SharedFlow<Unit> = _autoReconnectRequests.asSharedFlow()
    private var egressVerificationJob: Job? = null
    /** Bounded global recovery queue; every country is qualified independently without switching the active server. */
    private val multiLocationTestQueue = ArrayDeque<Pair<String, List<String>>>()
    private val multiLocationCountryQueue = ArrayDeque<String>()
    private var multiLocationInFlightBatch: Pair<String, List<String>>? = null
    private var multiLocationRecoveryActive: Boolean = false
    private var multiLocationSuccessfulCount: Int = 0
    private var multiLocationScannedLocations: Int = 0
    private var multiLocationCurrentCountryGroupId: String? = null
    private var multiLocationCountryFinalizing: Boolean = false
    private var multiLocationAwaitingContinue: Boolean = false
    private var multiLocationFinalRefreshPending: Boolean = false
    private var multiLocationScanJob: Job? = null
    private var publicConfigFinderJob: Job? = null

    // ---------- UI state ----------
    private val _uiState = MutableStateFlow(
        MainUiState(
            selectedGroupId = dataSource.getSelectedSubscriptionId(),
            selectedGuid = dataSource.getSelectServer(),
            confirmRemove = dataSource.getConfirmRemove(),
            doubleColumnDisplay = dataSource.getDoubleColumnDisplay(),
            smartConnectEnabled = runCatching {
                getApplication<Application>()
                    .getSharedPreferences("aicp_smart_connect", Context.MODE_PRIVATE)
                    .getBoolean("enabled", true)
            }.getOrDefault(true)
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // ---------- Keyword filtering ----------
    @Volatile
    private var keywordFilter: String = ""
    private var filterJob: Job? = null

    // ---------- Groups & cache ----------
    private val cacheMutex = Mutex()
    /** Serializes public config fetch/import/empty-bank cleanup across startup and user refreshes. */
    private val aicpRefreshMutex = Mutex()
    private val groupDataCache = mutableMapOf<String, List<ServersCache>>()
    private val groupUiFlows = ConcurrentHashMap<String, MutableStateFlow<ServerGroupUiState>>()
    private val groupServerFlows = ConcurrentHashMap<String, StateFlow<List<ServersCache>>>()
    private val groupLoadMutexes = ConcurrentHashMap<String, Mutex>()
    private val serverOrderPersistenceJobs = mutableMapOf<String, Job>()

    /**
     * AICP: per group, the moves asked for and not stored yet, and the groups one of whose moves the storage refused,
     * see [moveServer]. Concurrent, as a move's job may end on the thread its store ended on, as under an unconfined
     * dispatcher.
     */
    private val unstoredMoves = ConcurrentHashMap<String, Int>()
    private val refusedMoveGroups: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** AICP: the number the last refusal of a move to be told got, see [MainUiState.moveRefusal]. */
    private val moveRefusals = AtomicInteger()

    /**
     * AICP: per group, the moves shown, and those settled, stored or refused, see [storeMove]. A reading of a group
     * taken before every move shown was settled is held back, see [updateGroupUi], and the group is shown anew once its
     * moves are, see [moveServer]; [groupsReadEarly] names the groups waiting for that. A move is shown, and a reading
     * shown or held back, under [groupShowLock], one at a time.
     */
    private val shownMoves = ConcurrentHashMap<String, Int>()
    private val settledMoves = ConcurrentHashMap<String, Int>()
    private val groupsReadEarly: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val groupShowLock = Any()

    /**
     * AICP: per group, the number the last reading got, in the order the readings were taken, which the cached list,
     * written with it, is too, and the number of the reading shown last: a reading older than the one shown is not
     * shown over it, see [updateGroupUi].
     */
    private val readingNumbers = ConcurrentHashMap<String, Int>()
    private val shownReadings = ConcurrentHashMap<String, Int>()

    private var setupGroupJob: Job? = null
    private var preloadJob: Job? = null
    private var selectedGroupLoadJob: Job? = null
    private var reloadJob: Job? = null
    private var testResultFlushJob: Job? = null
    private val pendingTestResults = linkedMapOf<String, Long>()

    private val testRequests = MainTestRequests()
    private var bulkTestJob: Job? = null
    private var testCompletionJob: Job? = null
    private var smartConnectProbeJob: Job? = null
    private var smartConnectRealPingJob: Job? = null

    private val initialPageReady = CompletableDeferred<Unit>()

    /**
     * AICP: the names the Aether editor gives the WARP protocols, which the rows of Aether profiles show. Declared
     * before init, whose work off the main thread reads it: a delegate declared after it may not be there yet.
     */
    private val aetherProtocolLabel by lazy {
        aetherProtocolLabels(
            dataSource.getStringArray(R.array.aether_protocol_entries),
            dataSource.getStringArray(R.array.aether_protocol_values),
        )
    }

    // ---------- Service events ----------
    init {
        collectServiceEvents()
        setupGroupTab()
        startSmartConnectLoop()
    }

    private fun collectServiceEvents() {
        viewModelScope.launch {
            dataSource.mainServiceEvent.collect { event ->
                handleServiceEvent(event)
            }
        }
    }

    private fun handleServiceEvent(event: MainServiceEvent) {
        when (event) {
            MainServiceEvent.StateRunning -> {
                autoReconnectJob?.cancel()
                updateRunningState(true, clearTestingText = false)
            }
            MainServiceEvent.StateNotRunning -> {
                val wasRunning = _uiState.value.isRunning
                val wasManualStop = manualServiceStopRequested
                manualServiceStopRequested = false
                updateRunningState(false, clearTestingText = false)
                if (AicpAutoReconnectPolicy.shouldSchedule(
                        wasRunning = wasRunning,
                        manualStopRequested = wasManualStop,
                        smartConnectEnabled = _uiState.value.smartConnectEnabled,
                    )) scheduleUnexpectedDisconnectReconnect()
            }
            MainServiceEvent.StateStartSuccess -> {
                autoReconnectJob?.cancel()
                toastSuccess(R.string.toast_services_success)
                updateRunningState(true)
            }

            is MainServiceEvent.StateStartFailure -> {
                // The daemon attaches a reason only when it is a localized resource string, e.g.
                // the Aether core stopping or missing on this ABI; the generic text is the fallback.
                if (!event.message.isNullOrBlank()) {
                    toastError(event.message)
                } else {
                    toastError(R.string.toast_services_failure)
                }
                updateRunningState(false)
            }

            is MainServiceEvent.StateConnecting -> {
                autoReconnectJob?.cancel()
                if (uiState.value.isRunning) {
                    _uiState.update { it.copy(status = MainStatus.Connecting(event.message)) }
                }
            }

            MainServiceEvent.StateStopSuccess -> {
                manualServiceStopRequested = false
                autoReconnectJob?.cancel()
                updateRunningState(false)
            }
            is MainServiceEvent.MeasureDelayResult -> {
                if (smartConnectProbeRequestId == event.requestId) {
                    handleSmartConnectProbeResult(event.requestId, event.result.delayMillis, event.result)
                } else {
                    if (!uiState.value.isRunning || !testRequests.completeCurrent(event.requestId)) return
                    _uiState.value.activeOperation?.takeIf { it == VpnOperation.SELECTED_CONFIG_PING }?.let { operation ->
                        updateOperationProgress(operation) { it.copy(stage = "Complete", completed = 1, total = 1, found = 1, retained = 1) }
                    }
                    _uiState.update { it.copy(isTesting = testRequests.isTesting, status = MainStatus.ConnectionTest(event.result)) }
                }
            }

            is MainServiceEvent.MeasureConfigSuccess -> {
                val request = testRequests.bulk?.takeIf { it.id == event.requestId } ?: return
                queueTestResult(event.result, request)
            }

            is MainServiceEvent.MeasureConfigNotify -> {
                if (event.requestId == testRequests.bulk?.id) {
                    _uiState.update { it.copy(status = MainStatus.TestProgress(event.progress)) }
                    val counts = Regex("""(\d+)\s*/\s*(\d+)""").find(event.progress)
                    val remaining = counts?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val total = counts?.groupValues?.getOrNull(2)?.toIntOrNull()
                    val operation = _uiState.value.activeOperation
                    if (operation != null && remaining != null && total != null) {
                        val completed = (total - remaining).coerceIn(0, total)
                        updateOperationProgress(operation) { it.copy(stage = "Testing", completed = maxOf(it.completed, completed), total = total, found = it.found, retained = if (operation == VpnOperation.FIND_PUBLIC_CONFIGS) it.retained else maxOf(it.retained, total)) }
                    }
                }
            }

            is MainServiceEvent.MeasureConfigFinish -> {
                val request = testRequests.bulk?.takeIf { it.id == event.requestId } ?: return
                val scheduledFlush = testResultFlushJob
                testResultFlushJob = viewModelScope.launch {
                    scheduledFlush?.cancelAndJoin()
                    if (testRequests.bulk?.id != request.id) return@launch
                    flushPendingTestResults(request)
                    onTestsFinished(request.id)
                }
            }

            is MainServiceEvent.MeasureDelayCancelled -> {
                if (smartConnectProbeRequestId == event.requestId) {
                    smartConnectProbeRequestId = null
                    smartConnectProbeGuid = null
                }
                if (testRequests.completeCurrent(event.requestId)) {
                    resetTestStatus()
                    launchPendingSmartConnectFailoverIfReady()
                }
            }

            is MainServiceEvent.MeasureConfigCancelled -> {
                val cancelledRequest = testRequests.completeBulk(event.requestId)
                if (cancelledRequest != null) {
                    forceThroughputAfterTest = false
                    cancelPendingTestResults()
                    resetTestStatus()
                    if (pendingUnexpectedDisconnectGroupId == cancelledRequest.groupId &&
                        _uiState.value.activeOperation == VpnOperation.SMART_CONNECT) {
                        clearPendingUnexpectedDisconnectRecovery()
                        finishVpnOperation(VpnOperation.SMART_CONNECT)
                    }
                    launchPendingSmartConnectFailoverIfReady()
                }
            }

            MainServiceEvent.ServersChanged -> setupGroupTab(forceRefresh = true)
        }
    }

    private fun queueTestResult(result: RealPingResult, request: MainTestRequests.Bulk) {
        pendingTestResults[result.guid] = result.delayMillis
        if (testResultFlushJob?.isActive == true) return

        testResultFlushJob = viewModelScope.launch {
            while (pendingTestResults.isNotEmpty()) {
                delay(TEST_RESULT_FLUSH_INTERVAL_MS)
                flushPendingTestResults(request)
            }
        }
    }

    private suspend fun flushPendingTestResults(request: MainTestRequests.Bulk) {
        if (pendingTestResults.isEmpty()) return
        if (testRequests.bulk?.id != request.id) return

        val updates = cacheMutex.withLock {
            val drained = pendingTestResults.toMap()
            pendingTestResults.clear()
            groupDataCache[request.groupId]?.let { cached ->
                groupDataCache[request.groupId] = applyTestDelayResults(cached, drained)
            }
            drained
        }
        if (updates.isEmpty()) return
        if (testRequests.bulk?.id != request.id) return
        mutableServerGroupState(request.groupId).update { current ->
            current.copy(
                servers = applyTestDelayResults(current.servers, updates),
                rows = applyTestDelayResultsToRows(current.rows, updates),
            )
        }
    }

    internal fun formatStatus(status: MainStatus): String = when (status) {
        MainStatus.Disconnected -> dataSource.getString(R.string.connection_not_connected)
        MainStatus.Connected -> dataSource.getString(R.string.connection_connected)
        is MainStatus.Connecting -> status.message
        MainStatus.Testing -> dataSource.getString(R.string.connection_test_testing)
        is MainStatus.TestProgress -> dataSource.getString(
            R.string.connection_running_task_left,
            status.progress
        )

        is MainStatus.ConnectionTest -> formatConnectionTestResult(status.result)
    }

    private fun formatConnectionTestResult(result: ConnectionTestResult): String {
        val status = if (result.delayMillis >= 0) {
            val delay = dataSource.getString(R.string.server_test_delay_value, result.delayMillis)
            dataSource.getString(R.string.connection_test_available, delay)
        } else {
            val detail = result.errorMessage.ifBlank {
                dataSource.getString(R.string.connection_test_empty_message)
            }
            dataSource.getString(R.string.connection_test_error, detail)
        }

        if (result.delayMillis < 0 || (result.country == null && result.ipAddress == null)) {
            return status
        }

        val unknown = dataSource.getString(R.string.value_unknown)
        return "$status\n(${result.country ?: unknown}) ${result.ipAddress ?: unknown}"
    }

    // ---------- Public state accessors ----------
    fun serversForGroup(groupId: String): StateFlow<List<ServersCache>> =
        groupServerFlows.computeIfAbsent(groupId) {
            val groupState = mutableServerGroupState(groupId)
            groupState
                .map { it.servers }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                    initialValue = groupState.value.servers,
                )
        }

    internal fun serverGroupState(groupId: String): StateFlow<ServerGroupUiState> =
        mutableServerGroupState(groupId).asStateFlow()

    private fun mutableServerGroupState(groupId: String): MutableStateFlow<ServerGroupUiState> =
        groupUiFlows.computeIfAbsent(groupId) { MutableStateFlow(ServerGroupUiState()) }

    private fun currentServers(): List<ServersCache> =
        mutableServerGroupState(uiState.value.selectedGroupId).value.servers

    // ---------- Action handler ----------
    fun onAction(action: MainAction) {
        when (action) {
            MainAction.Initialize -> initialize()
            MainAction.RefreshServiceState -> dataSource.queryServiceState()
            MainAction.RefreshGroups -> setupGroupTab(forceRefresh = true)
            MainAction.RefreshAicpConfigs -> refreshExistingSelectedList()
            MainAction.TestAllServers -> testAllRealPing(true)
            MainAction.TestRealAllServers -> testAllRealPing()
            MainAction.CancelTesting -> {
                clearPendingUnexpectedDisconnectRecovery()
                val active = _uiState.value.activeOperation
                if (active != null) stopVpnOperation(active)
                else {
                    publicConfigFinderJob?.cancel()
                    publicConfigFinderJob = null
                    stopMultiLocationWork(cancelPing = false)
                    cancelAllPing()
                }
            }
            MainAction.ContinueMultiLocation -> {
                multiLocationAwaitingContinue = false
                _uiState.update { it.copy(multiLocationNeedsContinue = false) }
                if (multiLocationRecoveryActive) scanNextMultiLocationCountry()
            }
            MainAction.ToggleSmartConnect -> toggleSmartConnect()
            MainAction.StopMultiLocation -> {
                stopMultiLocationWork(cancelPing = true)
                finishVpnOperation(VpnOperation.FIND_PUBLIC_CONFIGS)
                toast(R.string.finder_multi_location_done)
            }
            is MainAction.StartVpnOperation -> startVpnOperation(action.operation)
            is MainAction.StopVpnOperation -> stopVpnOperation(action.operation)
            MainAction.RemoveAllServers -> removeAllServerAsync()
            MainAction.RemoveDuplicateServers -> removeDuplicateServerAsync()
            MainAction.RemoveInvalidServers -> removeInvalidServerAsync()
            MainAction.SortByTestResults -> sortByTestResultsAsync()
            MainAction.UpdateSubscriptions -> {
                val selectedGroup = uiState.value.selectedGroupId
                if (selectedGroup.startsWith("aicp-finder-")) {
                    findPublicConfigs(selectedGroup.removePrefix("aicp-finder-").uppercase())
                } else {
                    importConfigViaSub()
                }
            }
            is MainAction.FindPublicConfigs -> if (action.multiLocationRecovery) findPublicConfigsAcrossLocations(action.countryCode) else findPublicConfigs(action.countryCode)
            MainAction.ExportAll -> exportAllAsync()
            is MainAction.SelectGroup -> subscriptionIdChanged(action.groupId)
            is MainAction.RemoveCountryGroup -> removeCountryGroup(action.groupId)
            is MainAction.SelectServer -> {
                clearPendingUnexpectedDisconnectRecovery()
                updateSelectedGuid(action.guid)
            }
            is MainAction.RemoveServer -> removeServerAndRefresh(action.guid)
            is MainAction.MoveServer -> moveServer(action.groupId, action.fromGuid, action.toGuid)
            is MainAction.Search -> filterConfig(action.query)
            is MainAction.ImportBatchConfig -> importBatchConfig(action.configText)
            MainAction.LocateHandled -> consumeLocateTarget()
            is MainAction.MoveRefusalShown -> _uiState.update {
                if (it.moveRefusal == action.refusal) it.copy(moveRefusal = null) else it
            }
            is MainAction.ShareQRCode -> {
                val bitmap = dataSource.share2QRCode(action.guid)
                _uiState.update { it.copy(shareQRCodeBitmap = bitmap) }
            }

            MainAction.DismissQRCodeDialog -> {
                _uiState.update { it.copy(shareQRCodeBitmap = null) }
            }

            MainAction.ToggleService,
            MainAction.TestCurrentServer,
            MainAction.ImportQRcode,
            MainAction.ImportClipboard,
            MainAction.ImportConfigLocal,
            is MainAction.ImportManually,
            MainAction.RestartService,
            MainAction.LocateSelectedServer,
            is MainAction.EditServer,
            is MainAction.ShareClipboard,
            is MainAction.ShareFullContent -> {
                // Handled by Activity via its onAction lambda
            }
        }
    }

    private fun beginOperationProgress(operation: VpnOperation, stage: String, total: Int = 0, found: Int = 0) {
        _uiState.update { it.copy(operationProgress = OperationProgress(operation, stage, total = total, found = found)) }
    }

    private fun updateOperationProgress(operation: VpnOperation, transform: (OperationProgress) -> OperationProgress) {
        _uiState.update { state ->
            val current = state.operationProgress?.takeIf { it.operation == operation }
                ?: return@update state
            state.copy(operationProgress = transform(current))
        }
    }

    /** Publish a removed row immediately; the next canonical load still reconciles the backing cache. */
    private fun publishRemovedGuidUi(groupId: String, guid: String) {
        mutableServerGroupState(groupId).update { current ->
            if (current.servers.none { it.guid == guid } && current.rows.none { it.guid == guid }) current
            else current.copy(
                servers = current.servers.filterNot { it.guid == guid },
                rows = current.rows.filterNot { it.guid == guid },
            )
        }
    }

    private suspend fun publishRemovedProfile(groupId: String, guid: String) {
        cacheMutex.withLock {
            groupDataCache[groupId]?.let { cached -> groupDataCache[groupId] = cached.filterNot { it.guid == guid } }
        }
        publishRemovedGuidUi(groupId, guid)
    }

    private fun finishVpnOperation(operation: VpnOperation) {
        _uiState.update { state ->
            if (state.activeOperation == operation || state.operationProgress?.operation == operation) {
                state.copy(
                    activeOperation = if (state.activeOperation == operation) null else state.activeOperation,
                    operationProgress = if (state.operationProgress?.operation == operation) null else state.operationProgress,
                )
            } else state
        }
    }

    private fun startVpnOperation(operation: VpnOperation) {
        if (smartConnectProbeRequestId != null) cancelSmartConnectProbe()
        val state = _uiState.value
        if (!AicpVpnOperationPolicy.canStart(state.activeOperation) || state.isTesting || state.isRefreshingConfigs) return
        _uiState.update { it.copy(
            activeOperation = operation,
            operationProgress = OperationProgress(operation, "Starting"),
        ) }
        when (operation) {
            VpnOperation.SORT_AND_COLLECT -> {
                val groupId = state.selectedGroupId
                val guids = dataSource.getServerGuidList(groupId).distinct()
                if (guids.isEmpty()) { finishVpnOperation(operation); toast(R.string.aicp_op_no_configs); return }
                testAllRealPing(onlyTcp = false, specificGuids = guids, pruneTestedConfigs = true,
                    groupIdOverride = groupId, selectFastestAfterTest = false)
            }
            VpnOperation.TEST_REAL_DELAYS -> {
                val groupId = state.selectedGroupId
                val guids = dataSource.getServerGuidList(groupId).distinct()
                if (guids.isEmpty()) { finishVpnOperation(operation); toast(R.string.aicp_op_no_configs); return }
                testAllRealPing(onlyTcp = false, specificGuids = guids, groupIdOverride = groupId)
            }
            VpnOperation.SELECTED_CONFIG_PING -> {
                if (state.selectedGuid == null) { finishVpnOperation(operation); toast(R.string.aicp_op_no_selected_config); return }
                testSelectedServerRealPing()
                if (!_uiState.value.isTesting) finishVpnOperation(operation)
            }
            VpnOperation.FIND_PUBLIC_CONFIGS -> {
                val country = when {
                    state.selectedGroupId.startsWith("aicp-finder-") -> state.selectedGroupId.removePrefix("aicp-finder-").uppercase()
                    state.egressTargetCountry.isNotBlank() -> state.egressTargetCountry.uppercase()
                    else -> AicpSharedState.targetCountry(getApplication<Application>())
                }
                if (state.selectedGroupId == "aicp-multilocation") findPublicConfigsAcrossLocations(country)
                else findPublicConfigs(country)
            }
            VpnOperation.VERIFY_EXIT -> {
                if (!state.isRunning) { finishVpnOperation(operation); toast(R.string.aicp_op_requires_vpn); return }
                verifySelectedEgressAsync()
            }
            VpnOperation.SMART_CONNECT -> {
                if (!_uiState.value.smartConnectEnabled) toggleSmartConnect()
                if (AicpVpnOperationPolicy.shouldBenchmarkBeforeConnect(state.isRunning)) {
                    // Smart Connect is also the initial-connect path. Probe only the selected bank,
                    // select a positively measured reachable profile, and never prune user configs
                    // merely because a temporary probe timed out or failed.
                    cancelPendingAutoReconnect()
                    val groupId = state.selectedGroupId
                    val candidates = dataSource.getServerGuidList(groupId).distinct()
                    if (candidates.isEmpty()) {
                        finishVpnOperation(operation)
                        toast(R.string.aicp_op_no_configs)
                        return
                    }
                    pendingUnexpectedDisconnectGroupId = groupId
                    testAllRealPing(
                        onlyTcp = false,
                        specificGuids = candidates,
                        groupIdOverride = groupId,
                        selectFastestAfterTest = true,
                    )
                } else {
                    ensureSmartConnectProbeLoop()
                    finishVpnOperation(operation)
                }
            }
        }
    }

    private fun stopVpnOperation(operation: VpnOperation) {
        if (!AicpVpnOperationPolicy.canStop(_uiState.value.activeOperation, operation)) return
        when (operation) {
            VpnOperation.SORT_AND_COLLECT, VpnOperation.TEST_REAL_DELAYS, VpnOperation.SELECTED_CONFIG_PING -> cancelAllPing()
            VpnOperation.FIND_PUBLIC_CONFIGS -> {
                publicConfigFinderJob?.cancel()
                publicConfigFinderJob = null
                stopMultiLocationWork(cancelPing = true)
            }
            VpnOperation.VERIFY_EXIT -> {
                egressVerificationJob?.cancel()
                _uiState.update { it.copy(egressCheckStatus = AicpEgressCheckStatus.Unknown,
                    egressObservedCountry = null, egressObservedRegion = null, egressObservedCity = null, egressCheckedAtMillis = null) }
            }
            VpnOperation.SMART_CONNECT -> {
                clearPendingUnexpectedDisconnectRecovery()
                smartConnectRealPingJob?.cancel(); smartConnectRealPingJob = null
                cancelSmartConnectProbe()
                smartConnectProbeJob?.cancel(); smartConnectProbeJob = null
                cancelAllPing()
                if (_uiState.value.smartConnectEnabled) toggleSmartConnect()
            }
        }
        finishVpnOperation(operation)
    }

    /** Cancel the in-flight country fetch, current ping batch, queued countries and aggregate pass. */
    private fun stopMultiLocationWork(cancelPing: Boolean) {
        multiLocationRecoveryActive = false
        multiLocationAwaitingContinue = false
        multiLocationTestQueue.clear()
        multiLocationCountryQueue.clear()
        multiLocationInFlightBatch = null
        multiLocationCurrentCountryGroupId = null
        multiLocationCountryFinalizing = false
        multiLocationFinalRefreshPending = false
        multiLocationScanJob?.cancel(); multiLocationScanJob = null
        testCompletionJob?.cancel(); testCompletionJob = null
        _uiState.update { it.copy(multiLocationNeedsContinue = false, isRefreshingConfigs = false) }
        if (cancelPing) cancelAllPing()
    }

    /** Called before an explicit user stop so it can never be mistaken for an unexpected disconnect. */
    fun onManualServiceStopRequested() {
        manualServiceStopRequested = true
        autoReconnectJob?.cancel()
        clearPendingUnexpectedDisconnectRecovery()
    }

    /** A user-initiated start takes precedence over a queued automatic retry. */
    fun cancelPendingAutoReconnect() {
        autoReconnectJob?.cancel()
        clearPendingUnexpectedDisconnectRecovery()
    }

    private fun clearPendingUnexpectedDisconnectRecovery() {
        pendingUnexpectedDisconnectGroupId = null
    }

    private fun scheduleUnexpectedDisconnectReconnect() {
        val initial = _uiState.value
        val expectedGuid = initial.selectedGuid ?: return
        val expectedGroupId = initial.selectedGroupId
        autoReconnectJob?.cancel()
        autoReconnectJob = viewModelScope.launch {
            delay(AicpAutoReconnectPolicy.DISCONNECT_RECONNECT_DELAY_MS)
            // Do not discard recovery just because a list refresh or ping is already running.
            // Wait for the current operation to reach a safe handoff, then benchmark this tab.
            while (isActive) {
                val current = _uiState.value
                if (!current.smartConnectEnabled || current.isRunning || current.selectedGuid != expectedGuid ||
                    current.selectedGroupId != expectedGroupId) return@launch
                if (!AicpAutoReconnectPolicy.shouldWaitForIdle(
                        isTesting = current.isTesting,
                        isRefreshingConfigs = current.isRefreshingConfigs,
                        multiLocationActive = multiLocationRecoveryActive || testCompletionJob?.isActive == true,
                    )) break
                delay(AicpAutoReconnectPolicy.RECOVERY_IDLE_POLL_MS)
            }
            val current = _uiState.value
            if (!current.smartConnectEnabled || current.isRunning || current.selectedGuid != expectedGuid ||
                current.selectedGroupId != expectedGroupId) return@launch

            pendingUnexpectedDisconnectGroupId = expectedGroupId
            LogUtil.w(AppConfig.TAG, "Smart Connect: unexpected disconnect; benchmarking the current tab before reconnect")
            refreshExistingSelectedList(forceSelectFastest = true)

            // The refresh runs asynchronously and starts its ping batch on the main dispatcher.
            // Allow its local/network preparation time; onTestsFinished owns final selection + reconnect.
            var idlePolls = 0
            while (isActive && pendingUnexpectedDisconnectGroupId == expectedGroupId) {
                val latest = _uiState.value
                if (!latest.smartConnectEnabled || latest.isRunning || latest.selectedGroupId != expectedGroupId) {
                    clearPendingUnexpectedDisconnectRecovery()
                    return@launch
                }
                if (latest.isTesting || latest.isRefreshingConfigs || testCompletionJob?.isActive == true) {
                    idlePolls = 0
                } else if (++idlePolls >= AicpAutoReconnectPolicy.RECOVERY_START_TIMEOUT_POLLS) {
                    clearPendingUnexpectedDisconnectRecovery()
                    LogUtil.w(AppConfig.TAG, "Smart Connect: selected-tab refresh did not start a ping batch within the bounded wait; automatic reconnect skipped")
                    return@launch
                }
                delay(AicpAutoReconnectPolicy.RECOVERY_IDLE_POLL_MS)
            }
        }
    }

    private fun toggleSmartConnect() {
        val enabled = !_uiState.value.smartConnectEnabled
        runCatching {
            getApplication<Application>().getSharedPreferences("aicp_smart_connect", Context.MODE_PRIVATE)
                .edit().putBoolean("enabled", enabled).apply()
        }.onFailure { error ->
            LogUtil.e(AppConfig.TAG, "Unable to persist Smart Connect preference", error)
        }
        _uiState.update { it.copy(smartConnectEnabled = enabled) }
        syncSmartConnectProbeLoop()
        if (!enabled) {
            autoReconnectJob?.cancel()
            clearPendingUnexpectedDisconnectRecovery()
            if (_uiState.value.activeOperation == VpnOperation.SMART_CONNECT) finishVpnOperation(VpnOperation.SMART_CONNECT)
            smartConnectPendingFailoverGuid = null
            smartConnectCycleRunning = false
            smartConnectUnhealthySinceElapsedMillis = null
            smartConnectUnhealthyProbeGuid = null
            smartConnectUnhealthyDelayMillis = null
            smartConnectDegradedSamples = 0
            smartConnectLastTrafficSampleAt = 0L
            smartConnectRecoveryGuard.reset()
            smartConnectRecoveryMode = false
            smartConnectHealthyProbeCount = 0
        }
        toast(if (enabled) R.string.smart_connect_enabled else R.string.smart_connect_disabled)
    }

    private fun syncSmartConnectProbeLoop() {
        val state = _uiState.value
        if (state.smartConnectEnabled && state.isRunning) ensureSmartConnectProbeLoop()
        else stopSmartConnectProbeLoop()
    }

    private fun stopSmartConnectProbeLoop() {
        smartConnectRealPingJob?.cancel()
        smartConnectRealPingJob = null
        cancelSmartConnectProbe()
    }

    /** One bounded active-tunnel HTTP probe at a time: 60s when healthy, 5s during recovery. */
    private fun ensureSmartConnectProbeLoop() {
        if (!_uiState.value.smartConnectEnabled || !_uiState.value.isRunning || smartConnectRealPingJob?.isActive == true) return
        smartConnectRealPingJob = viewModelScope.launch {
            while (isActive && _uiState.value.smartConnectEnabled && _uiState.value.isRunning) {
                val state = _uiState.value
                if (!state.isRunning || state.isTesting || state.isRefreshingConfigs || testRequests.hasCurrent ||
                    multiLocationRecoveryActive || smartConnectCycleRunning || smartConnectPendingFailoverGuid != null) {
                    delay(1_000L)
                    continue
                }
                testCurrentServerRealPing()
                smartConnectProbeJob?.join()
                if (isActive && _uiState.value.smartConnectEnabled) {
                    delay(AicpSmartConnectPolicy.probeIntervalMs(smartConnectRecoveryMode))
                }
            }
        }
    }

    /** Cancel a background probe and release its request lease without leaving the UI busy. */
    private fun cancelSmartConnectProbe() {
        val requestId = smartConnectProbeRequestId
        smartConnectProbeJob?.cancel()
        smartConnectProbeJob = null
        if (requestId != null) {
            smartConnectProbeRequestId = null
            smartConnectProbeGuid = null
            testRequests.completeCurrent(requestId)
            _uiState.update { it.copy(isTesting = testRequests.isTesting) }
        }
    }

    /** Apply hysteresis: enter recovery state on a bad sample and leave after two healthy samples. */
    private fun updateSmartConnectRecoveryState(delayMillis: Long?, probedGuid: String?) {
        if (AicpSmartConnectPolicy.isUnhealthy(delayMillis)) {
            smartConnectRecoveryMode = true
            smartConnectHealthyProbeCount = 0
            smartConnectUnhealthyDelayMillis = delayMillis
            if (probedGuid != smartConnectUnhealthyProbeGuid) {
                smartConnectUnhealthyProbeGuid = probedGuid
                smartConnectUnhealthySinceElapsedMillis = SystemClock.elapsedRealtime()
            }
            return
        }
        smartConnectUnhealthyProbeGuid = null
        smartConnectUnhealthySinceElapsedMillis = null
        smartConnectUnhealthyDelayMillis = null
        smartConnectHealthyProbeCount = AicpSmartConnectPolicy.nextHealthyProbeCount(
            recoveryMode = smartConnectRecoveryMode,
            currentCount = smartConnectHealthyProbeCount,
            delayMillis = delayMillis,
        )
        if (smartConnectRecoveryMode && AicpSmartConnectPolicy.shouldExitRecovery(smartConnectHealthyProbeCount)) {
            smartConnectRecoveryMode = false
            smartConnectHealthyProbeCount = 0
            LogUtil.i(AppConfig.TAG, "Smart Connect recovered; returning to direct/tunnel graph monitoring")
        }
    }

    /** Compare the same-direction direct and tunneled rates already shown in the two UI graphs. */
    private fun startSmartConnectLoop() {
        // The graph producer emits one sample every 5 seconds; collect samples directly instead of
        // running a second timer or probing the tunnel every three seconds.
        viewModelScope.launch {
            NotificationManager.trafficHistory.collect {
                if (!AicpSmartConnectPolicy.shouldProbe(
                        enabled = _uiState.value.smartConnectEnabled,
                        connected = _uiState.value.isRunning,
                        currentTestInFlight = testRequests.hasCurrent,
                        probeInFlight = smartConnectProbeRequestId != null,
                        recoveryInFlight = smartConnectCycleRunning || smartConnectPendingFailoverGuid != null,
                        selectedGroupExists = _uiState.value.groups.any { it.id == _uiState.value.selectedGroupId },
                    ) || multiLocationRecoveryActive || _uiState.value.isRefreshingConfigs) {
                    smartConnectDegradedSamples = 0
                } else {
                    observeSmartConnectTrafficSample()
                }
            }
        }
    }

    private fun observeSmartConnectTrafficSample() {
        val sample = NotificationManager.trafficHistory.value.lastOrNull() ?: return
        if (sample.timestampMillis <= smartConnectLastTrafficSampleAt) return
        smartConnectLastTrafficSampleAt = sample.timestampMillis
        val degraded = AicpSmartConnectPolicy.isTunnelMuchSlower(
            directDownloadBytesPerSecond = sample.directDownloadBytesPerSecond,
            tunnelDownloadBytesPerSecond = sample.tunnelDownloadBytesPerSecond,
            directUploadBytesPerSecond = sample.directUploadBytesPerSecond,
            tunnelUploadBytesPerSecond = sample.tunnelUploadBytesPerSecond,
        )
        smartConnectActiveObservedSpeedBitsPerSecond =
            (maxOf(sample.tunnelDownloadBytesPerSecond, sample.tunnelUploadBytesPerSecond).coerceAtLeast(0.0) * 8.0)
                .toLong().coerceAtLeast(0L)
        smartConnectDegradedSamples = if (degraded) smartConnectDegradedSamples + 1 else 0
        if (smartConnectDegradedSamples < AicpSmartConnectPolicy.DEGRADED_SAMPLES_TO_FAILOVER) return
        if (!smartConnectRecoveryGuard.canRecover()) return
        startSmartConnectSpeedRecovery()
    }

    private fun startSmartConnectSpeedRecovery() {
        val current = _uiState.value
        if (!current.smartConnectEnabled || !current.isRunning || current.isTesting || current.isRefreshingConfigs ||
            multiLocationRecoveryActive || smartConnectCycleRunning || smartConnectPendingFailoverGuid != null) return
        val activeGuid = current.selectedGuid ?: dataSource.getSelectServer() ?: return
        val activeProfile = dataSource.decodeServerConfig(activeGuid) ?: return
        // On the synthetic All-servers page, keep recovery scoped to the active profile's owning bank.
        val recoveryGroup = current.selectedGroupId.ifBlank { activeProfile.subscriptionId }
        smartConnectCycleRunning = true
        smartConnectCycleSourceGuid = activeGuid
        smartConnectCycleSourceGroupId = recoveryGroup
        smartConnectUnhealthyDelayMillis = null
        smartConnectRecoveryGuard.recordRecovery()
        smartConnectDegradedSamples = 0
        LogUtil.w(AppConfig.TAG, "Smart Connect: direct/tunnel speed gap persisted; refreshing every profile in selected bank=$recoveryGroup")
        refreshExistingSelectedList(forceSelectFastest = true)
    }

    /** Recover strictly within the user's selected list; Multi-Location intentionally spans its whole aggregate. */
    private suspend fun failOverWithinSameCountry(activeGuid: String) {
        if (!_uiState.value.smartConnectEnabled || !_uiState.value.isRunning || _uiState.value.isTesting) {
            smartConnectCycleRunning = false
            smartConnectCycleSourceGuid = null
            smartConnectCycleSourceGroupId = null
            if (multiLocationRecoveryActive) startNextMultiLocationTest()
            return
        }
        val selectedGroup = _uiState.value.selectedGroupId
        val active = dataSource.decodeServerConfig(activeGuid) ?: run {
            smartConnectCycleRunning = false
            smartConnectCycleSourceGuid = null
            smartConnectCycleSourceGroupId = null
            if (multiLocationRecoveryActive) startNextMultiLocationTest()
            return
        }
        // Failover is strictly scoped to the list the user selected. A country tab therefore
        // stays in that country, while Multi-Location may choose the best profile from every
        // country already present in its own aggregate list. Never infer a narrower country scope.
        val candidateGuids = AicpFailoverScopePolicy.candidates(
            selectedGroupId = selectedGroup,
            activeGuid = activeGuid,
            selectedGroupGuids = dataSource.getServerGuidList(selectedGroup),
        )
        if (candidateGuids.isEmpty()) {
            LogUtil.w(AppConfig.TAG, "Smart Connect: no eligible fallback candidates in selected group $selectedGroup")
            smartConnectCycleRunning = false
            smartConnectCycleSourceGuid = null
            smartConnectCycleSourceGroupId = null
            if (multiLocationRecoveryActive) startNextMultiLocationTest()
            return
        }
        val scopeLabel = if (selectedGroup == "aicp-multilocation") "selected-multilocation-list" else "selected-list"
        LogUtil.i(AppConfig.TAG, "Smart Connect: active profile failed; testing ${candidateGuids.size} $scopeLabel candidates")
        smartConnectCycleSourceGuid = activeGuid
        smartConnectCycleSourceGroupId = selectedGroup
        smartConnectCycleRunning = true
        testAllRealPing(onlyTcp = false, specificGuids = candidateGuids)
    }

    // ---------- Initialization ----------
    fun initialize() {
        viewModelScope.launch(preloadDispatcher) {
            try {
                initialPageReady.await()
                delay(32)
                dataSource.initAssets()
                dataSource.syncSubscriptions()
                initializeAicpFrankfurtDefaults()
                autoRunFinderAtStartup()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Main background initialization failed", error)
            }
        }
    }


    /**
     * Establish Frankfurt as the canonical fallback on every app initialization, repairing blank or
     * stale legacy defaults without overriding a deliberate selection of another country.
     * A requested target is not a claim about the live public exit IP.
     */
    private fun initializeAicpFrankfurtDefaults() {
        AicpDestinationDefaults.ensure(getApplication<Application>())
    }

    /** Refresh the selected country's bank on first successful run / 19-minute cooldown. Retry briefly
     * when Android has not yet marked the network validated during cold start. */
    private suspend fun autoRunFinderAtStartup() {
        val context = getApplication<Application>()
        aicpRefreshMutex.withLock { pruneEmptyAicpFinderGroups() }
        repeat(6) {
            val now = System.currentTimeMillis()
            val country = AicpSharedState.targetCountry(context)
            val lastSuccess = AicpSharedState.lastRefreshSuccess(context, country)
            if (lastSuccess > 0L && !AicpRefreshPolicy.shouldRefresh(true, lastSuccess, now, force = false)) return
            if (hasUsableInternet() && refreshAicpBankIfDue(force = false)) return
            delay(2_500)
        }
    }

    private fun removeCountryGroup(groupId: String) {
        if (!AicpGeneratedGroupPolicy.isRemovableGroup(groupId)) return
        if (uiState.value.isRunning) {
            toastError(R.string.country_group_disconnect_before_delete)
            return
        }
        if (multiLocationRecoveryActive || _uiState.value.isTesting || _uiState.value.isRefreshingConfigs) {
            toast(R.string.finder_multi_location_busy)
            return
        }
        viewModelScope.launch(ioDispatcher) {
            val currentGroups = dataSource.getSubscriptions().map { GroupMapItem(it.guid, it.subscription.remarks) }
            val remaining = currentGroups.filterNot { it.id == groupId }
            val isMultiLocation = groupId == AicpGeneratedGroupPolicy.MULTI_LOCATION_GROUP
            val identitiesBeforeDelete = if (isMultiLocation) emptySet() else dataSource.getServerGuidList(groupId)
                .mapNotNull { guid -> dataSource.decodeServerConfig(guid)?.duplicateIdentity() }.toSet()
            if (!dataSource.removeSubscription(groupId)) {
                toastError(R.string.toast_failure)
                return@launch
            }
            if (isMultiLocation) {
                // The consolidated bank is app-owned; deleting it must not touch any country source bank.
                getApplication<Application>().getSharedPreferences(
                    "aicp_multilocation_origin", Context.MODE_PRIVATE,
                ).edit().clear().apply()
            } else {
                // Remove only Multi-Location copies whose origin was this bank and which no other source bank retains.
                reconcileMultiLocationRemovedProfiles(mapOf(groupId to identitiesBeforeDelete))
            }
            val selectedId = _uiState.value.selectedGroupId
            val nextId = if (selectedId == groupId) remaining.firstOrNull()?.id.orEmpty() else selectedId
            dataSource.setSelectedSubscriptionId(nextId)
            cacheMutex.withLock { groupDataCache.remove(groupId) }
            groupUiFlows.remove(groupId)
            groupServerFlows.remove(groupId)
            groupLoadMutexes.remove(groupId)
            setupGroupTab(forceRefresh = true).join()
            toast(dataSource.getString(R.string.country_group_deleted))
        }
    }

    fun refreshUiSettings() {
        _uiState.update {
            it.copy(
                confirmRemove = dataSource.getConfirmRemove(),
                doubleColumnDisplay = dataSource.getDoubleColumnDisplay()
            )
        }
    }

    // ---------- Group & server loading ----------
    private suspend fun buildServersCache(guids: List<String>): List<ServersCache> =
        guids.mapNotNull { guid ->
            currentCoroutineContext().ensureActive()
            val profile = dataSource.decodeServerConfig(guid) ?: return@mapNotNull null
            val affiliation = dataSource.decodeAffiliationInfo(guid)
            ServersCache(
                guid = guid,
                profile = profile.copy(),
                testDelayMillis = affiliation?.testDelayMillis ?: 0L,
                downloadBitsPerSecond = affiliation?.downloadBitsPerSecond ?: 0L,
                uploadBitsPerSecond = affiliation?.uploadBitsPerSecond ?: 0L,
            )
        }

    /**
     * AICP: the servers of a group as read, with how many of its moves were settled then, and the number of the reading,
     * see [updateGroupUi].
     */
    private class GroupReading(val servers: List<ServersCache>, val settledMoves: Int, val number: Int)

    private suspend fun loadGroup(
        groupId: String,
        forceRefresh: Boolean = false
    ): GroupReading {
        val loadMutex = groupLoadMutexes.computeIfAbsent(groupId) { Mutex() }
        return loadMutex.withLock {
            if (!forceRefresh) {
                cacheMutex.withLock { groupDataCache[groupId]?.let { return@withLock it } }
            }
            val servers = buildServersCache(dataSource.getServerGuidList(groupId))
            currentCoroutineContext().ensureActive()
            // AICP: under the group's load lock, which a move is stored under too, see [storeMove], and numbered in turn.
            cacheMutex.withLock {
                groupDataCache[groupId] = servers
                val number = readingNumbers.merge(groupId, 1, Int::plus)!!
                GroupReading(servers, settledMoves.getOrDefault(groupId, 0), number)
            }
        }
    }

    private fun applyKeywordFilter(servers: List<ServersCache>, filter: String): List<ServersCache> {
        val keyword = filter.trim()
        if (keyword.isEmpty()) return servers
        val regex = try {
            Regex(keyword, RegexOption.IGNORE_CASE)
        } catch (_: PatternSyntaxException) {
            return servers
        }
        return servers.filter { cache ->
            val profile = cache.profile
            profile.remarks.matchesPattern(regex, keyword) ||
                    profile.description.orEmpty().matchesPattern(regex, keyword) ||
                    profile.server.orEmpty().matchesPattern(regex, keyword) ||
                    profile.configType.name.matchesPattern(regex, keyword)
        }
    }

    /**
     * Shows [reading] of the group [groupId]. AICP: as [readingFate] decides: not over a newer reading shown, and not
     * when a move of the group was shown that was not settled when the group was read, which the reading would undo on
     * the screen; the group is then shown anew once its moves are settled, see [moveServer], or at once when they are
     * by now. Nor when the search changed while it was filtered: the group is then read and filtered anew.
     */
    private fun updateGroupUi(groupId: String, reading: GroupReading) {
        val filter = keywordFilter
        val filteredServers = applyKeywordFilter(reading.servers, filter)
        val state = ServerGroupUiState(
            servers = filteredServers,
            rows = buildServerRows(groupId, filteredServers)
        )
        val readAgain = synchronized(groupShowLock) {
            val fate = readingFate(
                number = reading.number,
                lastShown = shownReadings.getOrDefault(groupId, 0),
                settledWhenRead = reading.settledMoves,
                shownMoves = shownMoves.getOrDefault(groupId, 0),
                unsettledMoves = unstoredMoves.getOrDefault(groupId, 0),
                filteredAsSearched = filter == keywordFilter,
            )
            when (fate) {
                ReadingFate.SHOW -> {
                    shownReadings[groupId] = reading.number
                    mutableServerGroupState(groupId).value = state
                }
                ReadingFate.HOLD_UNTIL_SETTLED -> groupsReadEarly += groupId
                ReadingFate.OVERTAKEN, ReadingFate.READ_AGAIN -> Unit
            }
            fate == ReadingFate.READ_AGAIN
        }
        if (readAgain) viewModelScope.launch { showGroupAnew(groupId) }
    }

    /** AICP: reads the group [groupId] anew and shows it, see [updateGroupUi]. */
    private suspend fun showGroupAnew(groupId: String) {
        val reading = withContext(ioDispatcher) { loadGroup(groupId) }
        withContext(defaultDispatcher) { updateGroupUi(groupId, reading) }
    }

    private fun buildServerRows(groupId: String, servers: List<ServersCache>): List<ServerRowUiModel> {
        val subscriptionRemarks = if (groupId.isEmpty()) {
            servers.asSequence()
                .map { it.profile.subscriptionId }
                .filter { it.isNotEmpty() }
                .distinct()
                .associateWith { subscriptionId ->
                    dataSource.getSubscriptionItem(subscriptionId)?.remarks.orEmpty()
                }
        } else {
            emptyMap()
        }
        return servers.map { server ->
            buildServerRowUiModel(
                server = server,
                subscriptionRemarks = subscriptionRemarks[server.profile.subscriptionId].orEmpty(),
                aetherProtocolLabel = aetherProtocolLabel,
            )
        }
    }

    fun getSubscriptions(): List<SubscriptionCache> = dataSource.getSubscriptions()

    private fun resolveSelectedGroup(groups: List<GroupMapItem>): String {
        val current = uiState.value.selectedGroupId
        val resolved = when {
            groups.isEmpty() -> ""
            groups.any { it.id == current } -> current
            else -> groups.first().id
        }
        if (resolved != current) {
            dataSource.setSelectedSubscriptionId(resolved)
        }
        return resolved
    }

    private fun radialPreloadOrder(groups: List<GroupMapItem>, selectedIndex: Int): List<String> {
        if (groups.isEmpty()) return emptyList()
        val result = ArrayList<String>((groups.size - 1).coerceAtLeast(0))
        for (distance in 1 until groups.size) {
            val right = selectedIndex + distance
            val left = selectedIndex - distance
            if (right in groups.indices) result += groups[right].id
            if (left in groups.indices) result += groups[left].id
        }
        return result
    }

    fun setupGroupTab(forceRefresh: Boolean = false): Job {
        setupGroupJob?.cancel()
        preloadJob?.cancel()
        selectedGroupLoadJob?.cancel()

        return viewModelScope.launch(ioDispatcher) {
            try {
                if (forceRefresh) {
                    cacheMutex.withLock { groupDataCache.clear() }
                }
                val groups = dataSource.getSubscriptions().map {
                    GroupMapItem(id = it.guid, remarks = it.subscription.remarks)
                }
                val selectedGroup = resolveSelectedGroup(groups)
                val validIds = groups.mapTo(HashSet()) { it.id }
                groupUiFlows.keys.removeAll { it !in validIds }
                groupServerFlows.keys.removeAll { it !in validIds }
                groupLoadMutexes.keys.removeAll { it !in validIds }

                _uiState.update {
                    it.copy(
                        groups = groups,
                        selectedGroupId = selectedGroup,
                        selectedGuid = dataSource.getSelectServer(),
                    )
                }
                groups.forEach { mutableServerGroupState(it.id) }

                if (groups.isEmpty()) {
                    cacheMutex.withLock { groupDataCache.clear() }
                    return@launch
                }

                val selectedServers = loadGroup(selectedGroup, forceRefresh)
                updateGroupUi(selectedGroup, selectedServers)

                if (!initialPageReady.isCompleted) {
                    initialPageReady.complete(Unit)
                }

                val selectedIndex =
                    groups.indexOfFirst { it.id == selectedGroup }.coerceAtLeast(0)
                val preloadOrder = radialPreloadOrder(groups, selectedIndex)
                preloadJob = viewModelScope.launch(preloadDispatcher) {
                    preloadOrder.forEach { groupId ->
                        ensureActive()
                        delay(32)
                        val servers = loadGroup(groupId, forceRefresh)
                        updateGroupUi(groupId, servers)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to set up group tabs", error)
            } finally {
                if (!initialPageReady.isCompleted) {
                    initialPageReady.complete(Unit)
                }
            }
        }.also { setupGroupJob = it }
    }

    // ---------- Business actions (coroutine-based) ----------
    private fun importBatchConfig(configText: String) {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val (count, countSub) = dataSource.importBatchConfig(
                        configText, uiState.value.selectedGroupId, true
                    )
                    when {
                        count > 0 -> {
                            toast(dataSource.getString(R.string.title_import_config_count, count))
                            setupGroupTab(forceRefresh = true)
                        }

                        countSub > 0 -> setupGroupTab(forceRefresh = true)
                        else -> toastError(R.string.toast_failure)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Failed to import batch config", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun claimFinderOperation(): Boolean {
        val state = _uiState.value
        if (state.activeOperation != null && state.activeOperation != VpnOperation.FIND_PUBLIC_CONFIGS) return false
        if (state.isTesting || state.isRefreshingConfigs || multiLocationRecoveryActive) return false
        if (state.activeOperation == null) _uiState.update {
            it.copy(
                activeOperation = VpnOperation.FIND_PUBLIC_CONFIGS,
                operationProgress = OperationProgress(VpnOperation.FIND_PUBLIC_CONFIGS, "Starting"),
            )
        }
        return true
    }

    private fun findPublicConfigs(countryCode: String) {
        if (!claimFinderOperation()) {
            toast(R.string.finder_multi_location_busy)
            return
        }
        launchLoading {
            if (_uiState.value.activeOperation != VpnOperation.FIND_PUBLIC_CONFIGS) return@launchLoading
            val finderJob = currentCoroutineContext()[Job]
            publicConfigFinderJob = finderJob
            var pingStarted = false
            try {
            aicpRefreshMutex.withLock {
                _uiState.update { it.copy(isRefreshingConfigs = true) }
                beginOperationProgress(VpnOperation.FIND_PUBLIC_CONFIGS, "Fetching", total = PublicConfigCatalog.sourceUrls(countryCode).size)
                try {
                    withContext(ioDispatcher) {
                        try {
                            pruneEmptyAicpFinderGroups()
                            if (_uiState.value.isRunning) verifySelectedEgressAsync()
                            // Ask the daemon for a fresh state snapshot before reading current service state.
                            dataSource.queryServiceState()
                            kotlinx.coroutines.delay(500L)
                            if (!hasUsableInternet()) {
                                toast(R.string.finder_requires_internet)
                                return@withContext
                            }
                            currentCoroutineContext().ensureActive()
                            val fetched = PublicConfigFinder.fetchCancellable(countryCode, underlyingInternetNetwork()) { completed, total, found ->
                                updateOperationProgress(VpnOperation.FIND_PUBLIC_CONFIGS) {
                                    it.copy(stage = "Fetching", completed = completed, total = total, found = found)
                                }
                            }
                            currentCoroutineContext().ensureActive()
                            if (fetched.configs.isEmpty()) {
                                pruneEmptyAicpFinderGroups()
                                toast(dataSource.getString(R.string.finder_no_candidates, countryCode, fetched.successfulSources, fetched.failedSources))
                                return@withContext
                            }
                            AicpSharedState.recordRefreshSuccess(getApplication<Application>(), countryCode)
                            // Keep a separate persistent bank per country; never overwrite user subscriptions.
                            val groupId = "aicp-finder-${countryCode.lowercase()}"
                            if (dataSource.getSubscriptionItem(groupId) == null) {
                                val stored = MmkvManager.tryEncodeSubscription(
                                    groupId,
                                    SubscriptionItem(remarks = "AICP Finder · $countryCode", url = "", enabled = true, autoUpdate = false)
                                )
                                if (stored == null) throw IllegalStateException("Could not create country bank")
                            }
                            val before = dataSource.getServerGuidList(groupId).toSet()
                            dataSource.importBatchConfig(fetched.configs.joinToString("\n"), groupId, true)
                            dataSource.setSelectedSubscriptionId(groupId)
                            cacheMutex.withLock { groupDataCache.remove(groupId) }
                            // Publish newly imported profiles before dedupe/ping begins, not after the whole job.
                            updateGroupUi(groupId, loadGroup(groupId, forceRefresh = true))
                            setupGroupTab(forceRefresh = true).join()
                            val after = dataSource.getServerGuidList(groupId)
                            beginOperationProgress(VpnOperation.FIND_PUBLIC_CONFIGS, "Deduplicating", total = after.size, found = fetched.configs.size)
                            val newlyImported = after.count { it !in before }
                            // Dedupe the entire country bank before testing it.
                            val seenIdentities = HashSet<ProfileItem>()
                            val retained = ArrayList<String>(after.size)
                            var removedDuplicates = 0
                            after.forEachIndexed { index, guid ->
                                val profile = dataSource.decodeServerConfig(guid)
                                if (profile == null || !seenIdentities.add(profile.duplicateIdentity())) {
                                    if (dataSource.removeServer(guid)) {
                                        removedDuplicates++
                                        publishRemovedProfile(groupId, guid)
                                    } else retained += guid
                                } else {
                                    retained += guid
                                }
                                updateOperationProgress(VpnOperation.FIND_PUBLIC_CONFIGS) {
                                    it.copy(stage = "Deduplicating", completed = index + 1, total = after.size, found = fetched.configs.size, removed = removedDuplicates, retained = retained.size)
                                }
                            }
                            cacheMutex.withLock { groupDataCache.remove(groupId) }
                            updateGroupUi(groupId, loadGroup(groupId, forceRefresh = true))
                            if (retained.isEmpty()) {
                                pruneEmptyAicpFinderGroups()
                                toast(dataSource.getString(R.string.finder_no_new_configs, countryCode, fetched.configs.size))
                                return@withContext
                            }
                            // Measure and rank candidates without stopping/restarting the user's active tunnel.
                            autoPruneGuidsAfterTest = retained.toSet()
                            autoPruneGroupId = groupId
                            updateOperationProgress(VpnOperation.FIND_PUBLIC_CONFIGS) {
                                it.copy(stage = "Testing", completed = 0, total = retained.size, found = fetched.configs.size, removed = removedDuplicates, retained = retained.size)
                            }
                            toast(dataSource.getString(R.string.finder_testing, countryCode, newlyImported, retained.size))
                            pingStarted = true
                            testAllRealPing(onlyTcp = false, specificGuids = retained, forceThroughput = true)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (e: Exception) {
                            LogUtil.e(AppConfig.TAG, "Public config finder failed for $countryCode", e)
                            toastError(R.string.finder_failed)
                        }
                    }
                } finally {
                    _uiState.update { it.copy(isRefreshingConfigs = false) }
                }
            }
            } finally {
                if (publicConfigFinderJob === finderJob) publicConfigFinderJob = null
                if (!pingStarted) finishVpnOperation(VpnOperation.FIND_PUBLIC_CONFIGS)
            }
        }
    }

    /** Starts sequential country discovery. Only one country's configs are fetched and real-ping tested at a time. */
    private fun findPublicConfigsAcrossLocations(preferredCountry: String) {
        if (!claimFinderOperation()) {
            toast(R.string.finder_multi_location_busy)
            return
        }
        val countries = runCatching {
            AicpMultiLocationPolicy.candidateCountries(preferredCountry, PublicConfigCatalog.countries.map { it.code })
        }.getOrElse {
            finishVpnOperation(VpnOperation.FIND_PUBLIC_CONFIGS)
            toastError(R.string.finder_failed)
            return
        }
        if (countries.isEmpty()) {
            finishVpnOperation(VpnOperation.FIND_PUBLIC_CONFIGS)
            toast(R.string.finder_multi_location_no_results)
            return
        }
        multiLocationTestQueue.clear()
        multiLocationCountryQueue.clear()
        multiLocationInFlightBatch = null
        multiLocationCurrentCountryGroupId = null
        multiLocationCountryFinalizing = false
        multiLocationCountryQueue.addAll(countries)
        multiLocationSuccessfulCount = dataSource.getServerGuidList("aicp-multilocation").size
        multiLocationScannedLocations = 0
        multiLocationAwaitingContinue = false
        multiLocationRecoveryActive = true
        beginOperationProgress(VpnOperation.FIND_PUBLIC_CONFIGS, "Countries", total = countries.size)
        _uiState.update { it.copy(multiLocationNeedsContinue = false) }
        scanNextMultiLocationCountry()
    }

    /** Fetch one country, deduplicate it, then stop discovery until its real-ping batch has completed. */
    private fun scanNextMultiLocationCountry() {
        if (!multiLocationRecoveryActive || multiLocationAwaitingContinue || multiLocationCountryFinalizing ||
            _uiState.value.isTesting || smartConnectCycleRunning || smartConnectPendingFailoverGuid != null) return
        multiLocationCurrentCountryGroupId?.let { completedGroupId ->
            multiLocationCurrentCountryGroupId = null
            finalizeMultiLocationCountry(completedGroupId)
            return
        }
        val countryCode = multiLocationCountryQueue.removeFirstOrNull()
        if (countryCode == null) {
            multiLocationRecoveryActive = false
            multiLocationScanJob = viewModelScope.launch(ioDispatcher) {
                try {
                val groupId = "aicp-multilocation"
                if (dataSource.getSubscriptionItem(groupId) != null) {
                    // The final aggregate goes through the exact same full-list pipeline as the Refresh button:
                    // dedupe, real-ping every GUID, prune unreachable entries, throughput-test every survivor, sort.
                    val duplicatesRemoved = removeDuplicateProfilesForRefresh(groupId)
                    val guids = dataSource.getServerGuidList(groupId).distinct()
                    dataSource.setSelectedSubscriptionId(groupId)
                    cacheMutex.withLock { groupDataCache.remove(groupId) }
                    withContext(Dispatchers.Main.immediate) {
                        _uiState.update { it.copy(selectedGroupId = groupId) }
                    }
                    setupGroupTab(forceRefresh = true).join()
                    if (guids.isNotEmpty()) {
                        withContext(Dispatchers.Main.immediate) {
                            testAicpBankCandidates(
                                groupId, guids,
                                selectFastestWhenDisconnected = true,
                                forceThroughput = true,
                            )
                            multiLocationFinalRefreshPending = true
                        }
                        LogUtil.i(AppConfig.TAG, "Multi-location country scan complete; aggregate final refresh started: profiles=${guids.size} duplicatesRemoved=$duplicatesRemoved")
                        return@launch
                    }
                }
                setupGroupTab(forceRefresh = true).join()
                withContext(Dispatchers.Main.immediate) {
                    toast(R.string.finder_multi_location_done)
                    finishVpnOperation(VpnOperation.FIND_PUBLIC_CONFIGS)
                }
                launchPendingSmartConnectFailoverIfReady()
                LogUtil.i(AppConfig.TAG, "Multi-location finished: qualified=${multiLocationSuccessfulCount}, scannedCountries=${multiLocationScannedLocations}, aggregateRefresh=empty")
                } finally {
                    if (multiLocationScanJob === currentCoroutineContext()[Job]) multiLocationScanJob = null
                }
            }
            return
        }
        launchLoading {
            if (!multiLocationRecoveryActive) return@launchLoading
            val scanJob = currentCoroutineContext()[Job]
            multiLocationScanJob = scanJob
            try {
                aicpRefreshMutex.withLock {
                    _uiState.update { it.copy(isRefreshingConfigs = true) }
                    try {
                        withContext(ioDispatcher) {
                            currentCoroutineContext().ensureActive()
                            dataSource.queryServiceState()
                            if (!hasUsableInternet()) {
                                multiLocationRecoveryActive = false
                                multiLocationCountryQueue.clear()
                                toast(R.string.finder_requires_internet)
                                finishVpnOperation(VpnOperation.FIND_PUBLIC_CONFIGS)
                                return@withContext
                            }
                            multiLocationScannedLocations++
                            val fetched = try {
                                PublicConfigFinder.fetchCancellable(countryCode, underlyingInternetNetwork()) { completed, total, found ->
                                    updateOperationProgress(VpnOperation.FIND_PUBLIC_CONFIGS) {
                                        it.copy(stage = "$countryCode · sources", completed = completed, total = total, found = found)
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                LogUtil.w(AppConfig.TAG, "Multi-location source discovery failed for $countryCode", error)
                                return@withContext
                            }
                            currentCoroutineContext().ensureActive()
                            if (fetched.successfulSources == 0) return@withContext
                            val groupId = "aicp-finder-${countryCode.lowercase()}"
                            multiLocationCurrentCountryGroupId = groupId
                            if (dataSource.getSubscriptionItem(groupId) == null) {
                                val stored = MmkvManager.tryEncodeSubscription(groupId, SubscriptionItem(
                                    remarks = "AICP Finder · $countryCode", url = "", enabled = true, autoUpdate = false
                                ))
                                if (stored == null) {
                                    LogUtil.w(AppConfig.TAG, "Could not create recovery bank for $countryCode")
                                    return@withContext
                                }
                            }
                            val before = dataSource.getServerGuidList(groupId).toSet()
                            if (fetched.configs.isNotEmpty()) {
                                dataSource.importBatchConfig(fetched.configs.joinToString("\n"), groupId, false)
                            }
                            dataSource.setSelectedSubscriptionId(groupId)
                            withContext(Dispatchers.Main.immediate) { _uiState.update { it.copy(selectedGroupId = groupId) } }
                            cacheMutex.withLock { groupDataCache.remove(groupId) }
                            setupGroupTab(forceRefresh = true).join()
                            // Make this country's just-fetched rows visible before dedupe and qualification start.
                            updateGroupUi(groupId, loadGroup(groupId, forceRefresh = true))
                            updateOperationProgress(VpnOperation.FIND_PUBLIC_CONFIGS) {
                                it.copy(stage = "$countryCode · dedupe", completed = 0, total = dataSource.getServerGuidList(groupId).size, found = fetched.configs.size, retained = dataSource.getServerGuidList(groupId).size)
                            }
                            currentCoroutineContext().ensureActive()
                            // Reuse the Refresh-button deduper so the active GUID is protected and
                            // malformed/duplicate entries are handled identically in every country.
                            val countryDuplicatesRemoved = removeDuplicateProfilesForRefresh(groupId)
                            val after = dataSource.getServerGuidList(groupId)
                            updateOperationProgress(VpnOperation.FIND_PUBLIC_CONFIGS) {
                                it.copy(stage = "$countryCode · testing", completed = 0, total = after.size, found = fetched.configs.size, removed = it.removed, retained = after.size)
                            }
                            if (after.isEmpty()) {
                                pruneEmptyAicpFinderGroups()
                                return@withContext
                            }
                            AicpSharedState.recordRefreshSuccess(getApplication<Application>(), countryCode)
                            val candidates = after
                            sortGroupByBestMetric(groupId)
                            if (candidates.isNotEmpty()) {
                                AicpMultiLocationPolicy.candidateBatches(candidates).forEach { batch ->
                                    multiLocationTestQueue.addLast(groupId to batch)
                                }
                            }
                            LogUtil.i(AppConfig.TAG, "Multi-location country fetched: country=$countryCode sources=${fetched.successfulSources}/${fetched.successfulSources + fetched.failedSources}, fetched=${fetched.configs.size}, newlyImported=${after.count { it !in before }}, fullBankCandidates=${candidates.size}")
                        }
                    } finally {
                        _uiState.update { it.copy(isRefreshingConfigs = false) }
                    }
                }
                // Mirror the normal single-country Finder flow: select the country being scanned
                // so its tab and live result rows are visible while this country's batches run.
                val scannedGroupId = "aicp-finder-${countryCode.lowercase()}"
                dataSource.setSelectedSubscriptionId(scannedGroupId)
                _uiState.update { it.copy(selectedGroupId = scannedGroupId) }
                cacheMutex.withLock { groupDataCache.remove(scannedGroupId) }
                setupGroupTab(forceRefresh = false).join()
                // Keep the country currently being scanned visible in its own tab state even when
                // another tab is selected; result rows then update as real-ping batches complete.
                if (dataSource.getSubscriptionItem(scannedGroupId) != null) {
                    updateGroupUi(scannedGroupId, loadGroup(scannedGroupId, forceRefresh = true))
                }
                // A live-tunnel failure queued during country fetch takes priority over the next candidate batch.
                launchPendingSmartConnectFailoverIfReady()
                if (multiLocationTestQueue.isNotEmpty()) startNextMultiLocationTest() else scanNextMultiLocationCountry()
            } catch (cancelled: CancellationException) {
                multiLocationRecoveryActive = false
                multiLocationTestQueue.clear()
                multiLocationCountryQueue.clear()
                multiLocationInFlightBatch = null
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Multi-location recovery failed for $countryCode", error)
                scanNextMultiLocationCountry()
            } finally {
                if (multiLocationScanJob === scanJob) multiLocationScanJob = null
            }
        }
    }

    /** Finalizes one country as a transaction before the next country is fetched. */
    private fun finalizeMultiLocationCountry(groupId: String) {
        if (multiLocationCountryFinalizing) return
        multiLocationCountryFinalizing = true
        multiLocationScanJob = viewModelScope.launch(ioDispatcher) {
            try {
                if (dataSource.getSubscriptionItem(groupId) != null) {
                    syncMultiLocationFromCountry(groupId)
                    currentCoroutineContext().ensureActive()
                    // The entire country's candidate queue is now drained: perform final ordering,
                    // invalidate both affected caches, and publish fresh rows before advancing.
                    sortGroupByBestMetric(groupId)
                    currentCoroutineContext().ensureActive()
                    val multiLocationGroupId = "aicp-multilocation"
                    cacheMutex.withLock {
                        groupDataCache.remove(groupId)
                        groupDataCache.remove(multiLocationGroupId)
                    }
                    val countryReading = loadGroup(groupId, forceRefresh = true)
                    updateGroupUi(groupId, countryReading)
                    if (dataSource.getSubscriptionItem(multiLocationGroupId) != null) {
                        sortGroupByBestMetric(multiLocationGroupId)
                        updateGroupUi(multiLocationGroupId, loadGroup(multiLocationGroupId, forceRefresh = true))
                    }
                    // Re-read the tab catalogue after the new country and Multi-Location entries
                    // have been committed to storage; selectedGroupId is already synchronized.
                    setupGroupTab(forceRefresh = false).join()
                    LogUtil.i(AppConfig.TAG, "Multi-location country finalized: group=$groupId countryConfigs=${dataSource.getServerGuidList(groupId).size} multiLocation=${dataSource.getServerGuidList(multiLocationGroupId).size}")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Multi-location country finalization failed for $groupId", error)
            } finally {
                multiLocationCountryFinalizing = false
                if (multiLocationScanJob === currentCoroutineContext()[Job]) multiLocationScanJob = null
            }
            if (multiLocationRecoveryActive) scanNextMultiLocationCountry()
        }
    }

    private fun startNextMultiLocationTest() {
        if (_uiState.value.isTesting || !multiLocationRecoveryActive || multiLocationCountryFinalizing || smartConnectCycleRunning ||
            smartConnectPendingFailoverGuid != null) return
        val next = multiLocationTestQueue.removeFirstOrNull()
        if (next == null) {
            scanNextMultiLocationCountry()
            return
        }
        multiLocationInFlightBatch = next
        testAicpBankCandidates(
            next.first,
            next.second,
            selectFastestWhenDisconnected = false,
            forceThroughput = true,
        )
    }

    /** Physical non-VPN network used for direct discovery and Smart Connect recovery checks. */
    private fun underlyingInternetNetwork(): Network? = runCatching {
        val connectivity = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = connectivity.activeNetwork
        val activeCapabilities = active?.let(connectivity::getNetworkCapabilities)
        if (active != null && activeCapabilities != null &&
            !activeCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            activeCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            activeCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return@runCatching active
        val physical = connectivity.allNetworks.mapNotNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
            network to capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        physical.firstOrNull { it.second }?.first ?: physical.firstOrNull()?.first
    }.getOrNull()

    /** Fast capability-only check used by the three-second failover path. */
    private fun hasValidatedUnderlyingInternet(): Boolean = runCatching {
        val connectivity = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = underlyingInternetNetwork() ?: return@runCatching false
        connectivity.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }.getOrDefault(false)

    private fun hasUnderlyingInternet(): Boolean = runCatching {
        val network = underlyingInternetNetwork() ?: return@runCatching false
        hasValidatedUnderlyingInternet() || PublicConfigFinder.hasReachableSourceHost(network)
    }.getOrDefault(false)

    private fun hasUsableInternet(): Boolean = hasUnderlyingInternet()

    /** Remove only empty AICP-generated country banks; user-created subscriptions are never touched. */
    internal suspend fun pruneEmptyAicpFinderGroups(refreshUi: Boolean = true): Int {
        val candidates = dataSource.getSubscriptions()
            .map { it.guid }
            .filter { groupId ->
                AicpGeneratedGroupPolicy.shouldPruneEmptyGroup(groupId, dataSource.getServerGuidList(groupId).size)
            }
        if (candidates.isEmpty()) return 0

        val removedIds = candidates.filter { groupId ->
            // Re-check just before removal so a newly populated bank is not deleted on a stale read.
            dataSource.getServerGuidList(groupId).isEmpty() && dataSource.removeSubscription(groupId)
        }
        if (removedIds.isEmpty()) return 0

        cacheMutex.withLock { removedIds.forEach { groupDataCache.remove(it) } }
        removedIds.forEach { groupId ->
            groupUiFlows.remove(groupId)
            groupServerFlows.remove(groupId)
            groupLoadMutexes.remove(groupId)
        }
        if (refreshUi) setupGroupTab(forceRefresh = true).join()
        return removedIds.size
    }

    /**
     * Updates only the dedicated AICP country bank. User subscriptions and the active server are never overwritten.
     * Auto-refresh imports fresh candidates but does not claim they work until the user runs real proxy tests.
     */
    private suspend fun refreshAicpBankIfDue(force: Boolean): Boolean = aicpRefreshMutex.withLock {
        _uiState.update { it.copy(isRefreshingConfigs = true) }
        try {
            refreshAicpBankIfDueLocked(force)
        } finally {
            _uiState.update { it.copy(isRefreshingConfigs = false) }
        }
    }

    private suspend fun refreshAicpBankIfDueLocked(force: Boolean): Boolean {
        val context = getApplication<Application>()
        val countryCode = AicpSharedState.targetCountry(context)
        val now = System.currentTimeMillis()
        val lastSuccess = AicpSharedState.lastRefreshSuccess(context, countryCode)
        if (!AicpRefreshPolicy.shouldRefresh(hasUsableInternet(), lastSuccess, now, force)) return false

        return try {
            val fetched = PublicConfigFinder.fetch(countryCode, underlyingInternetNetwork())
            if (fetched.successfulSources == 0) {
                pruneEmptyAicpFinderGroups()
                LogUtil.w(AppConfig.TAG, "AICP config refresh skipped: all sources failed for $countryCode; failed=${fetched.failedSources}")
                return false
            }
            val groupId = "aicp-finder-${countryCode.lowercase()}"
            if (dataSource.getSubscriptionItem(groupId) == null) {
                if (fetched.configs.isEmpty()) return false
                val stored = MmkvManager.tryEncodeSubscription(
                    groupId,
                    SubscriptionItem(remarks = "AICP Finder · $countryCode", url = "", enabled = true, autoUpdate = false)
                )
                if (stored == null) throw IllegalStateException("Could not create AICP country bank")
            }
            val before = dataSource.getServerGuidList(groupId).toSet()
            // Snapshot connection-affecting profile identities. Refresh should re-test new or changed
            // endpoints, not every unchanged profile in a large country bank.
            val beforeFingerprints = before.associateWith { guid ->
                dataSource.decodeServerConfig(guid)?.duplicateIdentity()
            }
            val imported = if (fetched.configs.isNotEmpty()) {
                // Append until the new batch is measured; never drop a currently active profile during refresh.
                dataSource.importBatchConfig(fetched.configs.joinToString("\n"), groupId, true)
            } else {
                0 to 0
            }
            val afterImport = dataSource.getServerGuidList(groupId)
            // Keep the bank bounded by unique profile identity even when feeds overlap or rotate labels.
            val seenIdentities = HashSet<ProfileItem>()
            afterImport.forEach { guid ->
                val profile = dataSource.decodeServerConfig(guid)
                if (profile == null || !seenIdentities.add(profile.duplicateIdentity())) {
                    dataSource.removeServer(guid)
                }
            }
            val after = dataSource.getServerGuidList(groupId)
            if (after.isEmpty()) {
                pruneEmptyAicpFinderGroups()
                return false
            }
            val newGuids = after.filter { it !in before }
            val changedGuids = after.filter { guid ->
                beforeFingerprints[guid] != dataSource.decodeServerConfig(guid)?.duplicateIdentity()
            }
            val newCount = newGuids.size
            // Keep unchanged measured profiles ordered while new/changed candidates are tested in the background.
            sortGroupByBestMetric(groupId)
            AicpSharedState.recordRefreshSuccess(context, countryCode)
            // Startup discovery can create this country bank while another group is selected. Refresh the
            // complete group model regardless, otherwise the new bank exists in storage but its tab stays
            // invisible until the user manually runs discovery (which happens to rebuild the tabs).
            setupGroupTab(forceRefresh = true).join()
            if (AicpAutoTestPolicy.shouldTestChangedConfigs(changedGuids.size, _uiState.value.isTesting)) {
                testAicpBankCandidates(groupId, changedGuids)
            }
            LogUtil.i(AppConfig.TAG, "AICP config refresh for $countryCode: sources=${fetched.successfulSources}/${fetched.successfulSources + fetched.failedSources}, fetched=${fetched.configs.size}, new=$newCount, changed=${changedGuids.size}, imported=${imported.first}, rejected=${imported.second}")
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LogUtil.e(AppConfig.TAG, "AICP config refresh failed for $countryCode", error)
            false
        }
    }

    private fun importConfigViaSub() {
        val subId = uiState.value.selectedGroupId
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    // Snapshot profile identities before the update so removed configs can be reconciled
                    // from Multi-Location without depending on volatile GUIDs.
                    val groupsBeforeUpdate = if (subId.isEmpty()) {
                        dataSource.getSubscriptions().map { it.guid }.filter { it != "aicp-multilocation" }
                    } else listOf(subId).filter { it != "aicp-multilocation" }
                    val identitiesBeforeUpdate = groupsBeforeUpdate.associateWith { group ->
                        dataSource.getServerGuidList(group).mapNotNull { guid ->
                            dataSource.decodeServerConfig(guid)?.duplicateIdentity()
                        }.toSet()
                    }
                    // Keep the public country bank fresh whenever the user starts an update cycle.
                    // If internet is not validated, this is skipped and normal subscription updates still run.
                    refreshAicpBankIfDue(force = true)
                    val result = if (subId.isEmpty()) {
                        dataSource.updateConfigViaSubAll()
                    } else {
                        val item = dataSource.getSubscriptionItem(subId) ?: return@withContext
                        dataSource.updateConfigViaSub(SubscriptionCache(subId, item))
                    }
                    reconcileMultiLocationRemovedProfiles(identitiesBeforeUpdate)
                    when {
                        result.successCount + result.failureCount + result.skipCount == 0 ->
                            toast(R.string.title_update_subscription_no_subscription)

                        result.successCount > 0 && result.failureCount + result.skipCount == 0 ->
                            toast(
                                getQuantityString(
                                    R.plurals.title_update_config_count,
                                    result.configCount,
                                    result.configCount,
                                )
                            )

                        else ->
                            toast(dataSource.getString(R.string.title_update_subscription_result, result.configCount, result.successCount, result.failureCount, result.skipCount))
                    }
                    if (result.configCount > 0) {
                        setupGroupTab(forceRefresh = true)
                        refreshSelectedGuid()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Subscription update failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    /**
     * When a subscription update removes profiles, remove their Multi-Location copies only if no other
     * source bank still contains the same profile identity. User subscriptions are never modified here.
     */
    private suspend fun reconcileMultiLocationRemovedProfiles(before: Map<String, Set<ProfileItem>>) {
        if (before.isEmpty()) return
        val removedIdentities = LinkedHashSet<ProfileItem>()
        before.forEach { (groupId, oldIdentities) ->
            val current = dataSource.getServerGuidList(groupId).mapNotNull { guid ->
                dataSource.decodeServerConfig(guid)?.duplicateIdentity()
            }.toSet()
            removedIdentities.addAll(oldIdentities - current)
        }
        if (removedIdentities.isEmpty()) return

        val removedByGroup = before.mapValues { (groupId, oldIdentities) ->
            val current = dataSource.getServerGuidList(groupId).mapNotNull { guid ->
                dataSource.decodeServerConfig(guid)?.duplicateIdentity()
            }.toSet()
            oldIdentities - current
        }
        val sourceGroups = dataSource.getSubscriptions().map { it.guid }
            .filter { it != "aicp-multilocation" }
        val stillAvailable = HashSet<ProfileItem>()
        sourceGroups.forEach { groupId ->
            dataSource.getServerGuidList(groupId).forEach { guid ->
                dataSource.decodeServerConfig(guid)?.duplicateIdentity()?.let(stillAvailable::add)
            }
        }
        val prefs = getApplication<Application>().getSharedPreferences("aicp_multilocation_origin", Context.MODE_PRIVATE)
        val multiId = "aicp-multilocation"
        val multiGuids = dataSource.getServerGuidList(multiId)
        var removed = 0
        multiGuids.forEach { guid ->
            val identity = dataSource.decodeServerConfig(guid)?.duplicateIdentity() ?: return@forEach
            val originCountry = prefs.getString(guid, null)?.uppercase()
            val originGroup = originCountry?.let { "aicp-finder-${it.lowercase()}" }
            val removedFromOrigin = originGroup != null && identity in removedByGroup[originGroup].orEmpty()
            val removedFromAnyBank = removedByGroup.values.any { identity in it }
            val shouldRemove = AicpMultiLocationPolicy.shouldRemoveMultiLocationCopy(
                hasKnownOrigin = originCountry != null,
                removedFromOriginBank = removedFromOrigin,
                identityStillAvailable = identity in stillAvailable,
                removedFromAnyBank = removedFromAnyBank,
            )
            if (shouldRemove && dataSource.removeServer(guid)) {
                prefs.edit().remove(guid).apply()
                removed++
            }
        }
        if (removed > 0) {
            cacheMutex.withLock { groupDataCache.clear() }
            setupGroupTab(forceRefresh = true).join()
            LogUtil.i(AppConfig.TAG, "Multi-Location reconciliation removed $removed profile(s) no longer present in any source bank")
        }
    }

    private fun exportAllAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val groupId = uiState.value.selectedGroupId
                    val list = if (groupId.isEmpty() && keywordFilter.isEmpty()) {
                        dataSource.getServerGuidList("")
                    } else {
                        currentServers().map { it.guid }
                    }
                    val ret = dataSource.shareNonCustomConfigsToClipboard(list)
                    if (ret > 0) {
                        toast(dataSource.getString(R.string.title_export_config_count, ret))
                    } else {
                        toastError(R.string.toast_failure)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Export failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeAllServerAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val count =
                        if (uiState.value.selectedGroupId.isEmpty() && keywordFilter.isEmpty()) {
                            dataSource.removeAllServer()
                        } else {
                            // AICP: what the storage removed, a profile it refused to remove staying.
                            currentServers().count { dataSource.removeServer(it.guid) }
                        }
                    viewModelScope.launch(ioDispatcher) {
                        cacheMutex.withLock { groupDataCache.clear() }
                    }
                    setupGroupTab(forceRefresh = true)
                    toast(dataSource.getString(R.string.title_del_config_count, count))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Delete all failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeDuplicateServerAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val seen = HashSet<ProfileItem>()
                    val duplicates = ArrayList<String>()
                    currentServers().forEach { server ->
                        val profile = server.profile
                        if (!profile.configType.isComplexType()) {
                            val identity = profile.duplicateIdentity()
                            if (!seen.add(identity)) duplicates += server.guid
                        }
                    }
                    // AICP: what the storage removed, a profile it refused to remove staying.
                    val removed = duplicates.count { dataSource.removeServer(it) }
                    setupGroupTab(forceRefresh = true)
                    toast(dataSource.getString(R.string.title_del_duplicate_config_count, removed))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Delete duplicate failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeInvalidServerAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val count = removeInvalidServerInternal()
                    viewModelScope.launch(ioDispatcher) {
                        cacheMutex.withLock { groupDataCache.clear() }
                        setupGroupTab(forceRefresh = true)
                    }
                    toast(dataSource.getString(R.string.title_del_config_count, count))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Delete invalid failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeInvalidServerInternal(): Int {
        val visibleServersOnly =
            uiState.value.selectedGroupId.isNotEmpty() || keywordFilter.isNotBlank()
        return if (visibleServersOnly) {
            currentServers().sumOf { server ->
                dataSource.removeInvalidServerByGuid(server.guid)
            }
        } else {
            dataSource.removeInvalidServersInGroup("")
        }
    }

    /** Stable ascending real-ping order; failed/unmeasured profiles remain at the end. */
    private fun sortGroupsByRealPing(groupId: String) {
        val groups = when {
            groupId.isBlank() -> (dataSource.getSubsList() + listOf("", "aicp-multilocation")).distinct()
            groupId.startsWith("aicp-finder-") -> listOf(groupId, "aicp-multilocation").distinct()
            else -> listOf(groupId)
        }
        groups.forEach(dataSource::sortByTestResultsForSub)
    }

    private fun sortGroupByBestMetric(groupId: String) {
        // Every bank, including the default/all-server backing bank, uses measured bidirectional speed
        // when fresh and falls back to native latency for candidates without a current speed sample.
        AicpThroughputTester.sortGroupByThroughput(getApplication<Application>(), groupId)
    }

    private fun sortByTestResultsAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    sortByTestResultsInternal()
                    cacheMutex.withLock { groupDataCache.clear() }
                    setupGroupTab(forceRefresh = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Sort by test results failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun sortByTestResultsInternal() {
        val subs = if (uiState.value.selectedGroupId.isEmpty()) {
            dataSource.getSubsList()
        } else {
            listOf(uiState.value.selectedGroupId)
        }
        // This menu action is explicitly a real-Ping sort; throughput ranking remains a separate automatic policy.
        sortGroupsByRealPing(uiState.value.selectedGroupId)
        // The manual sort action also offers already measured, unique sub-200 ms profiles to the dedicated tab.
        var added = 0
        subs.forEach { groupId ->
            dataSource.getServerGuidList(groupId).forEach { guid ->
                val delay = dataSource.decodeAffiliationInfo(guid)?.testDelayMillis
                if (AicpMultiLocationPolicy.qualifiesRealPing(delay)) {
                    val profile = dataSource.decodeServerConfig(guid)
                    if (profile != null && delay != null && addToMultiLocationGroup(profile, delay, groupId)) added++
                }
            }
        }
        if (added > 0) toast("Added $added unique real-ping configs under 200 ms to Multi-Location")
    }

    fun subscriptionIdChanged(id: String) {
        if (_uiState.value.groups.none { it.id == id }) return
        if (_uiState.value.selectedGroupId != id) {
            autoReconnectJob?.cancel()
            val wasAwaitingSmartConnect = pendingUnexpectedDisconnectGroupId != null
            clearPendingUnexpectedDisconnectRecovery()
            if (wasAwaitingSmartConnect && _uiState.value.activeOperation == VpnOperation.SMART_CONNECT) {
                finishVpnOperation(VpnOperation.SMART_CONNECT)
            }
        }
        if (id.startsWith("aicp-finder-") && id.length > "aicp-finder-".length) {
            val country = id.removePrefix("aicp-finder-").uppercase()
            if (country.matches(Regex("[A-Z]{2}"))) {
                val context = getApplication<Application>()
                val currentCountry = AicpSharedState.targetCountry(context)
                val region = if (country == "DE") "Hesse" else if (currentCountry != country) "" else context.getSharedPreferences("aicp_network_identity", Context.MODE_PRIVATE).getString("target_region", "").orEmpty()
                val city = if (country == "DE") "Frankfurt am Main" else if (currentCountry != country) "" else context.getSharedPreferences("aicp_network_identity", Context.MODE_PRIVATE).getString("target_city", "").orEmpty()
                AicpSharedState.persistTargetLocation(context, country, region, city)
            }
        }
        mutableServerGroupState(id)
        if (uiState.value.selectedGroupId != id) {
            dataSource.setSelectedSubscriptionId(id)
            _uiState.update { it.copy(selectedGroupId = id) }
        }
        selectedGroupLoadJob?.cancel()
        selectedGroupLoadJob = viewModelScope.launch(ioDispatcher) {
            try {
                updateGroupUi(id, loadGroup(id))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to load selected group: $id", error)
            }
        }
    }

    fun reloadServerList() {
        val groupId = uiState.value.selectedGroupId
        selectedGroupLoadJob?.cancel()
        selectedGroupLoadJob = viewModelScope.launch(ioDispatcher) {
            updateGroupUi(groupId, loadGroup(groupId, forceRefresh = true))
        }
    }

    fun reloadAllGroups(groupIds: List<String>) {
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch(preloadDispatcher) {
            val selected = uiState.value.selectedGroupId
            val order = buildList {
                if (selected in groupIds) add(selected)
                addAll(groupIds.filter { it != selected })
            }
            order.forEachIndexed { index, groupId ->
                ensureActive()
                if (index > 0) delay(32)
                updateGroupUi(groupId, loadGroup(groupId, forceRefresh = true))
            }
        }
    }

    fun filterConfig(keyword: String) {
        if (keyword == keywordFilter) return
        keywordFilter = keyword
        filterJob?.cancel()
        filterJob = viewModelScope.launch(defaultDispatcher) {
            delay(300)
            // AICP: with how many of each group's moves were settled when it was cached, and the number of the reading it
            // is, see [updateGroupUi].
            val snapshot = cacheMutex.withLock {
                groupDataCache.mapValues { (groupId, servers) ->
                    GroupReading(servers, settledMoves.getOrDefault(groupId, 0), readingNumbers.getOrDefault(groupId, 0))
                }
            }
            ensureActive()
            snapshot.forEach { (groupId, reading) ->
                ensureActive()
                updateGroupUi(groupId, reading)
            }
        }
    }

    fun updateSelectedGuid(guid: String) {
        dataSource.setSelectServer(guid)
        _uiState.update { it.copy(selectedGuid = guid) }
    }

    fun refreshSelectedGuid() {
        _uiState.update { it.copy(selectedGuid = dataSource.getSelectServer()) }
    }

    fun removeServerAndRefresh(guid: String) {
        if (guid == uiState.value.selectedGuid) {
            toast(R.string.toast_action_not_allowed)
            return
        }
        viewModelScope.launch(ioDispatcher) {
            // AICP: a removal the storage refused is told; the list, shown anew, keeps the profile.
            if (!dataSource.removeServer(guid)) toastError(R.string.toast_failure)
            cacheMutex.withLock { groupDataCache.clear() }
            setupGroupTab(forceRefresh = true).join()
        }
    }

    /**
     * Shows the profile [fromGuid] names where the one [toGuid] names stands, and stores it there; the job storing it,
     * null when nothing moved. AICP: by the two guids, see [storeMove], even when the screen closes right after. A
     * move the storage refused is told, see [MainUiState.moveRefusal], once, when the group's last move asked for is
     * stored, and the groups are shown anew, as stored, so that the reading comes after every move queued behind the
     * refused one. Not in the list of every group, [groupId] empty, which is no stored list.
     */
    fun moveServer(groupId: String, fromGuid: String, toGuid: String): Job? {
        if (groupId.isEmpty()) return null
        synchronized(groupShowLock) {
            val groupState = mutableServerGroupState(groupId).value
            val servers = groupState.servers.toMutableList()
            val fromPosition = servers.indexOfFirst { it.guid == fromGuid }
            val toPosition = servers.indexOfFirst { it.guid == toGuid }
            if (!servers.moveItem(fromPosition, toPosition)) return null
            val rows = groupState.rows.toMutableList()
            rows.moveItem(fromPosition, toPosition)
            mutableServerGroupState(groupId).value = ServerGroupUiState(servers, rows)
            shownMoves.merge(groupId, 1, Int::plus)
            unstoredMoves.merge(groupId, 1, Int::plus)
        }
        // A drag emits several moves; each is stored once the one before it is, in the order they were made.
        val previousPersistenceJob = serverOrderPersistenceJobs[groupId]
        // AICP: begun at once, on the main thread the move comes on, and the store kept from cancellation, so that a
        // screen closing right after neither stops it nor keeps it from starting.
        return viewModelScope.launch {
            val moved = withContext(NonCancellable + ioDispatcher) {
                previousPersistenceJob?.join()
                storeMove(groupId, fromGuid, toGuid)
            }
            if (!moved) refusedMoveGroups += groupId
            // AICP: once the group's last move asked for is settled: a refusal among them told, and the groups read anew;
            // or else a reading held back for them taken anew, see [updateGroupUi].
            val (refused, readEarly) = synchronized(groupShowLock) {
                if (unstoredMoves.merge(groupId, -1, Int::plus) != 0) {
                    false to false
                } else {
                    refusedMoveGroups.remove(groupId) to groupsReadEarly.remove(groupId)
                }
            }
            if (refused) {
                val refusal = moveRefusals.incrementAndGet()
                _uiState.update { it.copy(moveRefusal = refusal) }
                setupGroupTab(forceRefresh = true)
            } else if (readEarly) {
                showGroupAnew(groupId)
            }
        }.also { serverOrderPersistenceJobs[groupId] = it }
    }

    /**
     * AICP: stores the move of the profile [fromGuid] names to where the one [toGuid] names stands, in the list of
     * [groupId] as stored then, see [MainDataSource.moveServer], so that a profile an update stored, or a removal took
     * away, meanwhile is not undone, and makes it in the group's cached list too, under the group's load lock, see
     * [loadGroup]: a reading of the group comes before the move is stored or after it is cached, so the cached list stays
     * the stored one; and counts it settled, see [settledMoves]. Whether the storage took it.
     */
    private suspend fun storeMove(groupId: String, fromGuid: String, toGuid: String): Boolean =
        groupLoadMutexes.computeIfAbsent(groupId) { Mutex() }.withLock {
            val moved = dataSource.moveServer(groupId, fromGuid, toGuid)
            cacheMutex.withLock {
                if (moved) {
                    groupDataCache[groupId]?.toMutableList()?.let { cached ->
                        if (cached.moveItem(cached.indexOfFirst { it.guid == fromGuid }, cached.indexOfFirst { it.guid == toGuid })) {
                            groupDataCache[groupId] = cached
                        }
                    }
                }
                // AICP: settled, stored or refused: a reading from now on has it as stored, see [updateGroupUi].
                settledMoves.merge(groupId, 1, Int::plus)
            }
            moved
        }

    // ---------- Testing ----------
    fun cancelAllPing(
        clearAutoPrune: Boolean = true,
        clearSmartConnectCycle: Boolean = true,
    ) {
        if (clearSmartConnectCycle) {
            smartConnectCycleRunning = false
            smartConnectCycleSourceGuid = null
            smartConnectCycleSourceGroupId = null
        }
        autoSelectGuidsAfterTest = emptySet()
        forceThroughputAfterTest = false
        if (clearAutoPrune) {
            autoPruneGuidsAfterTest = emptySet()
            autoPruneGroupId = null
            autoPruneAnyReachableAfterTest = false
        }
        bulkTestJob?.cancel()
        bulkTestJob = null
        testCompletionJob?.cancel()
        testCompletionJob = null
        testRequests.cancelBulk()
        testRequests.invalidateCurrent()
        smartConnectProbeRequestId = null
        smartConnectProbeGuid = null
        cancelPendingTestResults()
        resetTestStatus()
        dataSource.cancelAllPing()
    }

    private fun resetTestStatus() {
        _uiState.update {
            it.copy(
                isTesting = testRequests.isTesting,
                status = if (testRequests.isTesting) MainStatus.Testing
                else if (it.isRunning) MainStatus.Connected else MainStatus.Disconnected
            )
        }
    }

    /** Refresh every stored profile in the selected tab, independent of the current search filter. */
    private fun refreshExistingSelectedList(forceSelectFastest: Boolean = false) {
        if (_uiState.value.isTesting || _uiState.value.isRefreshingConfigs || multiLocationRecoveryActive) return
        val groupId = _uiState.value.selectedGroupId
        viewModelScope.launch(ioDispatcher) {
            if (underlyingInternetNetwork() == null) {
                withContext(Dispatchers.Main.immediate) {
                    clearPendingUnexpectedDisconnectRecovery()
                    if (_uiState.value.activeOperation == VpnOperation.SMART_CONNECT) finishVpnOperation(VpnOperation.SMART_CONNECT)
                    if (smartConnectCycleRunning) {
                        smartConnectCycleRunning = false
                        smartConnectCycleSourceGuid = null
                        smartConnectCycleSourceGroupId = null
                    }
                    toast(R.string.finder_requires_internet)
                }
                return@launch
            }
            val duplicatesRemoved = removeDuplicateProfilesForRefresh(groupId)
            val guids = dataSource.getServerGuidList(groupId).distinct()
            if (guids.isEmpty()) {
                withContext(Dispatchers.Main.immediate) {
                    clearPendingUnexpectedDisconnectRecovery()
                    if (_uiState.value.activeOperation == VpnOperation.SMART_CONNECT) finishVpnOperation(VpnOperation.SMART_CONNECT)
                    if (smartConnectCycleRunning) {
                        smartConnectCycleRunning = false
                        smartConnectCycleSourceGuid = null
                        smartConnectCycleSourceGroupId = null
                    }
                    toast(R.string.finder_multi_location_done)
                }
                return@launch
            }
            withContext(Dispatchers.Main.immediate) {
                autoPruneGuidsAfterTest = guids.toSet()
                autoPruneGroupId = groupId
                autoPruneAnyReachableAfterTest = true
                LogUtil.i(AppConfig.TAG, "Selected-list refresh started: group=$groupId profiles=${guids.size} duplicatesRemoved=$duplicatesRemoved")
                testAllRealPing(
                    onlyTcp = false,
                    specificGuids = guids,
                    pruneTestedConfigs = true,
                    groupIdOverride = groupId,
                    selectFastestAfterTest = forceSelectFastest ||
                        AicpExistingListRefreshPolicy.shouldSelectFastestAfterRefresh(groupId, _uiState.value.isRunning),
                    forceThroughput = true,
                )
            }
        }
    }

    /** Remove malformed and duplicate profiles only inside the selected owned list; protect the live GUID. */
    private suspend fun removeDuplicateProfilesForRefresh(groupId: String): Int {
        val activeGuid = dataSource.getSelectServer()
        val targetGroups = if (groupId.isBlank()) dataSource.getSubsList().distinct() else listOf(groupId)
        var removed = 0
        var processed = 0
        val banks = targetGroups.map { ownerGroupId -> ownerGroupId to dataSource.getServerGuidList(ownerGroupId) }.toMutableList()
        if (groupId.isBlank()) banks += "" to MmkvManager.decodeServerList("").toList()
        val total = banks.sumOf { it.second.size }
        banks.forEach { (ownerGroupId, guids) ->
            val seen = HashSet<Any>()
            if (activeGuid != null && activeGuid in guids) {
                dataSource.decodeServerConfig(activeGuid)?.let { seen.add(it.duplicateIdentity()) }
            }
            guids.forEach { guid ->
                if (guid != activeGuid) {
                    val identity = dataSource.decodeServerConfig(guid)?.duplicateIdentity()
                    if (identity == null || !seen.add(identity)) {
                        if (dataSource.removeServer(guid)) {
                            removed++
                            publishRemovedProfile(ownerGroupId, guid)
                            if (ownerGroupId != groupId) publishRemovedProfile(groupId, guid)
                        }
                    }
                }
                processed++
                if (_uiState.value.activeOperation == VpnOperation.FIND_PUBLIC_CONFIGS) {
                    updateOperationProgress(VpnOperation.FIND_PUBLIC_CONFIGS) {
                        it.copy(stage = "Deduplicating", completed = processed, total = total, removed = removed,
                            retained = (total - removed).coerceAtLeast(0))
                    }
                }
            }
        }
        return removed
    }

    fun testAllRealPing(
        onlyTcp: Boolean = false,
        specificGuids: List<String>? = null,
        pruneTestedConfigs: Boolean = false,
        groupIdOverride: String? = null,
        selectFastestAfterTest: Boolean = true,
        forceThroughput: Boolean = false,
    ) {
        if (smartConnectProbeRequestId != null) cancelSmartConnectProbe()
        autoSelectFastestAfterTest = selectFastestAfterTest
        // Keep caller-prepared pruning for specific candidate batches (e.g. newly fetched configs).
        // A normal whole-list ping clears stale pruning state; Multi-Location refresh sets its own below.
        cancelAllPing(
            clearAutoPrune = specificGuids == null,
            clearSmartConnectCycle = !smartConnectCycleRunning,
        )
        forceThroughputAfterTest = forceThroughput
        val groupId = groupIdOverride ?: uiState.value.selectedGroupId
        if (pruneTestedConfigs) {
            autoPruneGuidsAfterTest = (specificGuids ?: currentServers().map { it.guid }).toSet()
            autoPruneGroupId = groupId
        }
        val servers = if (groupIdOverride != null) mutableServerGroupState(groupId).value.servers else currentServers()
        val serverGuids = specificGuids ?: servers.map { it.guid }
        if (serverGuids.isEmpty()) {
            autoPruneGuidsAfterTest = emptySet()
            autoSelectGuidsAfterTest = emptySet()
            forceThroughputAfterTest = false
            smartConnectCycleRunning = false
            smartConnectCycleSourceGuid = null
            smartConnectCycleSourceGroupId = null
            return
        }
        autoSelectGuidsAfterTest = if (!selectFastestAfterTest) emptySet()
            else if (specificGuids == null) serverGuids.toSet() else specificGuids.toSet()
        val resetGuids = serverGuids.toHashSet()
        mutableServerGroupState(groupId).update { current ->
            current.copy(
                servers = current.servers.map { server ->
                    if (server.guid !in resetGuids || server.testDelayMillis == 0L) server
                    else server.copy(testDelayMillis = 0L)
                },
                rows = current.rows.map { row ->
                    if (row.guid !in resetGuids || row.testDelayMillis == 0L) row
                    else row.copy(testDelayMillis = 0L)
                }
            )
        }
        val request = testRequests.beginBulk(groupId)
        _uiState.value.activeOperation?.let { operation ->
            updateOperationProgress(operation) {
                it.copy(stage = "Testing", completed = 0, total = serverGuids.size, found = it.found, retained = if (operation == VpnOperation.FIND_PUBLIC_CONFIGS) it.retained else serverGuids.size)
            }
        }
        val message = TestServiceMessage(
            key = AppConfig.MSG_MEASURE_CONFIG_START,
            subscriptionId = groupId,
            serverGuids = if (specificGuids != null || keywordFilter.isNotEmpty()) serverGuids else emptyList(),
            onlyTcp = onlyTcp,
            // Explicit subsets (single-profile test, existing-list refresh, finder batches and failover)
            // are managed by this ViewModel. Never let the service's subscription-wide preferences
            // mutate profiles outside the exact tested set.
            manageSubscriptionResults = specificGuids == null && keywordFilter.isEmpty()
        )
        _uiState.update {
            it.copy(
                isTesting = true,
                status = MainStatus.Testing
            )
        }
        bulkTestJob = viewModelScope.launch {
            withContext(ioDispatcher) {
                dataSource.clearAllTestDelayResults(serverGuids)
                val resetGuids = serverGuids.toHashSet()
                cacheMutex.withLock {
                    groupDataCache[groupId]?.let { cached ->
                        groupDataCache[groupId] = cached.map { server ->
                            if (server.guid !in resetGuids || server.testDelayMillis == 0L) server
                            else server.copy(testDelayMillis = 0L)
                        }
                    }
                }
            }
            dataSource.sendMsg2TestService(message, request.id)
        }
    }

    private fun cancelPendingTestResults() {
        testResultFlushJob?.cancel()
        testResultFlushJob = null
        pendingTestResults.clear()
    }

    /** Tests an AICP country bank; ordinary refresh selects its fastest profile only while disconnected. */
    private fun testAicpBankCandidates(
        groupId: String,
        candidateGuids: List<String>,
        selectFastestWhenDisconnected: Boolean = true,
        forceThroughput: Boolean = false,
    ) {
        if (candidateGuids.isEmpty() || _uiState.value.isTesting) return
        cancelAllPing(clearAutoPrune = false)
        forceThroughputAfterTest = forceThroughput
        autoPruneGuidsAfterTest = candidateGuids.toSet()
        autoPruneGroupId = groupId
        autoSelectFastestAfterTest = selectFastestWhenDisconnected && !_uiState.value.isRunning &&
            _uiState.value.selectedGroupId == groupId
        mutableServerGroupState(groupId).update { current ->
            current.copy(
                servers = current.servers.map { server ->
                    if (server.guid in candidateGuids) server.copy(testDelayMillis = 0L) else server
                },
                rows = current.rows.map { row ->
                    if (row.guid in candidateGuids) row.copy(testDelayMillis = 0L) else row
                },
            )
        }
        val request = testRequests.beginBulk(groupId)
        _uiState.value.activeOperation?.let { operation ->
            updateOperationProgress(operation) {
                it.copy(stage = "Testing", completed = 0, total = candidateGuids.size, found = it.found, retained = if (operation == VpnOperation.FIND_PUBLIC_CONFIGS) it.retained else candidateGuids.size)
            }
        }
        val message = TestServiceMessage(
            key = AppConfig.MSG_MEASURE_CONFIG_START,
            subscriptionId = groupId,
            serverGuids = candidateGuids,
            onlyTcp = false,
        )
        _uiState.update { it.copy(isTesting = true, status = MainStatus.Testing) }
        bulkTestJob = viewModelScope.launch {
            withContext(ioDispatcher) {
                dataSource.clearAllTestDelayResults(candidateGuids)
                cacheMutex.withLock {
                    groupDataCache[groupId]?.let { cached ->
                        val resetGuids = candidateGuids.toHashSet()
                        groupDataCache[groupId] = cached.map { server ->
                            if (server.guid in resetGuids) server.copy(testDelayMillis = 0L) else server
                        }
                    }
                }
            }
            dataSource.sendMsg2TestService(message, request.id)
        }
    }

    /** Bottom status-bar test measures only the selected profile, regardless of VPN running state. */
    fun testSelectedServerRealPing() {
        if (smartConnectProbeRequestId != null) cancelSmartConnectProbe()
        if (_uiState.value.isTesting || _uiState.value.isRefreshingConfigs || multiLocationRecoveryActive) return
        val groupId = _uiState.value.selectedGroupId
        val guid = _uiState.value.selectedGuid ?: return
        if (dataSource.decodeServerConfig(guid) == null) return
        testAllRealPing(
            onlyTcp = false,
            specificGuids = listOf(guid),
            groupIdOverride = groupId,
            selectFastestAfterTest = false,
        )
    }

    private fun testCurrentServerRealPing() {
        if (!_uiState.value.isRunning || !_uiState.value.smartConnectEnabled ||
            testRequests.hasCurrent || smartConnectProbeRequestId != null || smartConnectCycleRunning ||
            smartConnectPendingFailoverGuid != null) return
        val probedGuid = _uiState.value.selectedGuid ?: return
        val requestId = testRequests.beginCurrent()
        smartConnectProbeRequestId = requestId
        smartConnectProbeGuid = probedGuid
        smartConnectProbeJob = viewModelScope.launch(ioDispatcher) {
            // MainTestRequests and Smart Connect recovery fields are owned by the main thread,
            // just like service events and user actions. Keep network probes on IO, but marshal
            // every state transition (including cancellation cleanup) back to that owner.
            val probeJob = currentCoroutineContext()[Job]
            try {
                val measured = AicpTunnelHealthProbe.measureVpnNetworkPayload(
                    getApplication<Application>(), AicpSmartConnectPolicy.ACTIVE_TUNNEL_PROBE_TIMEOUT_MS
                )
                if (measured <= 0L) {
                    LogUtil.w(AppConfig.TAG, "Smart Connect: no HTTPS payload received through Android VPN Network; tunnel data plane is unhealthy")
                }
                // Real direct reference, only for a bad sample: never switch profiles during a direct-network outage.
                val directReachable = if (AicpSmartConnectPolicy.isUnhealthy(measured)) {
                    underlyingInternetNetwork()?.let { AicpDirectProbe.measure(it) > 0L } ?: false
                } else null
                withContext(Dispatchers.Main.immediate) {
                    handleSmartConnectProbeResult(requestId, measured, directReachable = directReachable)
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    if (smartConnectProbeRequestId == requestId) {
                        smartConnectProbeRequestId = null
                        smartConnectProbeGuid = null
                        testRequests.completeCurrent(requestId)
                        _uiState.update { it.copy(isTesting = testRequests.isTesting) }
                    }
                    if (smartConnectProbeJob === probeJob) smartConnectProbeJob = null
                }
            }
        }
    }

    /** Applies a body-bearing 2xx or valid 204 HTTP-over-SOCKS result; redirects, errors and captive-portal HTML fail. */
    private fun handleSmartConnectProbeResult(
        requestId: String,
        delayMillis: Long,
        suppliedResult: ConnectionTestResult? = null,
        directReachable: Boolean? = null,
    ) {
        val isSmartConnectProbe = smartConnectProbeRequestId == requestId
        val probedGuid = if (isSmartConnectProbe) smartConnectProbeGuid else null
        if (isSmartConnectProbe) {
            smartConnectProbeRequestId = null
            smartConnectProbeGuid = null
            smartConnectProbeJob = null
        }
        val requestCompleted = testRequests.completeCurrent(requestId)
        if (!uiState.value.isRunning || !requestCompleted) {
            _uiState.update { it.copy(isTesting = testRequests.isTesting) }
            return
        }
        val result = suppliedResult ?: ConnectionTestResult(
            delayMillis = delayMillis,
            errorMessage = if (delayMillis <= 0L) dataSource.getString(R.string.smart_connect_http_probe_failed) else "",
        )
        _uiState.update { it.copy(isTesting = testRequests.isTesting, status = MainStatus.ConnectionTest(result)) }
        val underlyingInternetAvailable = if (isSmartConnectProbe && delayMillis <= 0L) (directReachable ?: hasValidatedUnderlyingInternet()) else false
        if (isSmartConnectProbe) updateSmartConnectRecoveryState(delayMillis, probedGuid)
        val unhealthyHasPersisted = isSmartConnectProbe && AicpSmartConnectPolicy.hasSustainedUnhealthy(
            delayMillis = delayMillis,
            unhealthySinceMillis = smartConnectUnhealthySinceElapsedMillis,
            nowMillis = SystemClock.elapsedRealtime(),
        )
        if (unhealthyHasPersisted && probedGuid != null && !smartConnectCycleRunning && _uiState.value.smartConnectEnabled &&
            AicpSmartConnectPolicy.isCurrentProfile(probedGuid, _uiState.value.selectedGuid) &&
            AicpMultiLocationPolicy.shouldFailOver(delayMillis, underlyingInternetAvailable) &&
            AicpSmartConnectPolicy.mayFailOverWithDirect(directReachable) &&
            smartConnectRecoveryGuard.canRecover()) {
            smartConnectRecoveryGuard.recordRecovery()
            val preemptMultiLocationBatch = AicpMultiLocationPolicy.shouldPreemptBatchForSmartConnectFailover(
                multiLocationActive = multiLocationRecoveryActive,
                awaitingContinue = multiLocationAwaitingContinue,
                batchInFlight = testRequests.bulk != null && multiLocationInFlightBatch != null,
            )
            if (preemptMultiLocationBatch) {
                // Preserve the interrupted candidate batch at the head of the queue, then prioritize
                // restoring the failed live tunnel. The batch is re-tested after Smart Connect finishes.
                multiLocationInFlightBatch?.let(multiLocationTestQueue::addFirst)
                multiLocationInFlightBatch = null
                cancelAllPing()
                smartConnectPendingFailoverGuid = null
                smartConnectCycleRunning = true
                LogUtil.w(AppConfig.TAG, "Smart Connect preempted the active Multi-Location test batch; queued batch will resume after recovery")
                viewModelScope.launch(ioDispatcher) {
                    runCatching { failOverWithinSameCountry(probedGuid) }
                        .onFailure { error ->
                            smartConnectCycleRunning = false
                            smartConnectCycleSourceGuid = null
                            smartConnectCycleSourceGroupId = null
                            LogUtil.e(AppConfig.TAG, "Same-country Smart Connect failover failed", error)
                            if (multiLocationRecoveryActive) startNextMultiLocationTest()
                        }
                }
            } else if (testRequests.bulk != null || (multiLocationRecoveryActive && !multiLocationAwaitingContinue)) {
                smartConnectPendingFailoverGuid = probedGuid
                LogUtil.w(AppConfig.TAG, "Smart Connect failover queued until the current config fetch/batch reaches a safe handoff")
            } else {
                smartConnectPendingFailoverGuid = null
                smartConnectCycleRunning = true
                viewModelScope.launch(ioDispatcher) {
                    runCatching { failOverWithinSameCountry(probedGuid) }
                        .onFailure { error ->
                            smartConnectCycleRunning = false
                            smartConnectCycleSourceGuid = null
                            smartConnectCycleSourceGroupId = null
                            LogUtil.e(AppConfig.TAG, "Same-country Smart Connect failover failed", error)
                            if (multiLocationRecoveryActive) startNextMultiLocationTest()
                        }
                }
            }
        }
    }

    private fun launchPendingSmartConnectFailoverIfReady() {
        if (testRequests.bulk != null || testRequests.hasCurrent || _uiState.value.isRefreshingConfigs ||
            smartConnectCycleRunning || !_uiState.value.isRunning || !_uiState.value.smartConnectEnabled) return
        val guid = smartConnectPendingFailoverGuid ?: return
        smartConnectPendingFailoverGuid = null
        // Set synchronously so Multi-Location cannot start another batch while the recovery coroutine is scheduled.
        smartConnectCycleRunning = true
        viewModelScope.launch(ioDispatcher) {
            runCatching { failOverWithinSameCountry(guid) }
                .onFailure { error ->
                    smartConnectCycleRunning = false
                    smartConnectCycleSourceGuid = null
                    smartConnectCycleSourceGroupId = null
                    LogUtil.e(AppConfig.TAG, "Queued same-country Smart Connect failover failed", error)
                    if (multiLocationRecoveryActive) startNextMultiLocationTest()
                }
        }
    }

    private fun onTestsFinished(requestId: String) {
        val completedRequest = testRequests.completeBulk(requestId) ?: return
        if (multiLocationInFlightBatch?.first == completedRequest.groupId) multiLocationInFlightBatch = null
        val selectFastest = autoSelectFastestAfterTest
        autoSelectFastestAfterTest = true
        val forceThroughput = forceThroughputAfterTest
        forceThroughputAfterTest = false
        resetTestStatus()
        _uiState.value.activeOperation?.let { operation ->
            updateOperationProgress(operation) { it.copy(stage = "Finalizing", completed = it.total) }
        }
        val pruneGuids = autoPruneGuidsAfterTest
        autoPruneGuidsAfterTest = emptySet()
        val pruneOnlyUnreachable = autoPruneAnyReachableAfterTest
        autoPruneAnyReachableAfterTest = false
        val selectGuids = autoSelectGuidsAfterTest
        autoSelectGuidsAfterTest = emptySet()
        val wasSmartConnectCycle = smartConnectCycleRunning
        val smartConnectCyclePending = !smartConnectCycleSourceGuid.isNullOrBlank()
        val smartConnectSourceGuid = smartConnectCycleSourceGuid
        val smartConnectSourceGroupId = smartConnectCycleSourceGroupId
        val smartConnectRecoveryWasUnhealthy = AicpSmartConnectPolicy.shouldPreferLatencyFailover(
            smartConnectUnhealthyDelayMillis,
        )
        smartConnectCycleRunning = false
        smartConnectCycleSourceGuid = null
        smartConnectCycleSourceGroupId = null
        val testedGroupId = autoPruneGroupId
        autoPruneGroupId = null
        val realPingSortOnly = _uiState.value.activeOperation == VpnOperation.SORT_AND_COLLECT
        val completionJob = viewModelScope.launch(ioDispatcher, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
            var removedFailed = 0
            // Capture source ownership before deleting failed profiles. The All-servers page spans
            // multiple subscriptions, so group identities by each profile's persisted owner.
            val identitiesBeforePruneByGroup = if (pruneGuids.isNotEmpty()) {
                pruneGuids.mapNotNull { guid ->
                    dataSource.decodeServerConfig(guid)?.let { profile ->
                        val owner = profile.subscriptionId.ifEmpty { AppConfig.DEFAULT_SUBSCRIPTION_ID }
                        owner to profile.duplicateIdentity()
                    }
                }.groupBy({ it.first }, { it.second }).mapValues { (_, identities) -> identities.toSet() }
            } else emptyMap()
            val measuredResults = linkedMapOf<String, Long?>()
            selectGuids.forEach { guid ->
                measuredResults[guid] = dataSource.decodeAffiliationInfo(guid)?.testDelayMillis
            }
            val isCountryDiscoveryBatch = multiLocationRecoveryActive &&
                testedGroupId?.startsWith("aicp-finder-") == true
            val strictMultiLocationPrune = testedGroupId == "aicp-multilocation" &&
                (pruneOnlyUnreachable || multiLocationFinalRefreshPending)
            val physicalNetworkForBatch = underlyingInternetNetwork()
            // Include pruneGuids directly: their results are materialized below, not in measuredResults yet.
            val hasSuccessfulRealPing = (selectGuids + pruneGuids).any { guid ->
                (dataSource.decodeAffiliationInfo(guid)?.testDelayMillis ?: 0L) > 0L
            }
            val requiresPruneGuard = pruneOnlyUnreachable || isCountryDiscoveryBatch || strictMultiLocationPrune
            val allowReachabilityPrune = !requiresPruneGuard ||
                (physicalNetworkForBatch != null && hasSuccessfulRealPing)
            if (!allowReachabilityPrune && requiresPruneGuard) {
                LogUtil.w(AppConfig.TAG, "Refresh pruning suppressed: physicalNetworkValidated=${physicalNetworkForBatch != null} successfulRealPing=$hasSuccessfulRealPing; preserve all candidates")
            }
            pruneGuids.forEachIndexed { pruneIndex, guid ->
                currentCoroutineContext().ensureActive()
                val result = dataSource.decodeAffiliationInfo(guid)?.testDelayMillis
                measuredResults[guid] = result
                // Never remove the profile currently carrying the active tunnel, even after a failed probe.
                if (_uiState.value.isRunning && guid == dataSource.getSelectServer()) return@forEachIndexed
                // Multi-Location eligibility is based on each profile's owning country bank, including
                // when the synthetic All-servers tab is selected and spans several country banks.
                val profile = dataSource.decodeServerConfig(guid)
                val sourceGroupForGuid = profile?.subscriptionId?.ifBlank { testedGroupId.orEmpty() }
                    ?: testedGroupId.orEmpty()
                val refreshOfCountryBank = pruneOnlyUnreachable &&
                    sourceGroupForGuid.startsWith("aicp-finder-")
                val multiLocationEligible = (multiLocationRecoveryActive || refreshOfCountryBank) &&
                    AicpMultiLocationPolicy.qualifiesRealPing(result)
                if (multiLocationEligible && profile != null && result != null &&
                    addToMultiLocationGroup(profile, result, sourceGroupForGuid)) {
                    multiLocationSuccessfulCount++
                }
                // Country banks retain every reachable profile; the <=200ms threshold is only for
                // adding to / pruning the Multi-Location aggregate.
                val shouldRemove = when {
                    !allowReachabilityPrune && requiresPruneGuard -> false
                    strictMultiLocationPrune -> AicpMultiLocationPolicy.shouldPruneMultiLocationCandidate(result)
                    pruneOnlyUnreachable -> AicpExistingListRefreshPolicy.shouldRemove(result)
                    else -> AicpFinderQualification.shouldPrune(result)
                }
                val removedThisProfile = shouldRemove && dataSource.removeServer(guid)
                if (removedThisProfile) {
                    removedFailed++
                    publishRemovedProfile(testedGroupId ?: completedRequest.groupId, guid)
                }
                val activeProgressOperation = _uiState.value.activeOperation
                if (activeProgressOperation != null) {
                    updateOperationProgress(activeProgressOperation) {
                        val removedSoFar = it.removed + if (removedThisProfile) 1 else 0
                        it.copy(stage = "Pruning", completed = (pruneIndex + 1).coerceAtMost(pruneGuids.size),
                            total = maxOf(it.total, pruneGuids.size), removed = removedSoFar,
                            retained = (it.retained - if (removedThisProfile) 1 else 0).coerceAtLeast(0))
                    }
                }
            }
            // Refresh-button pruning must also remove stale copies from Multi-Location, but only
            // when no remaining source bank still owns the same profile identity.
            if (identitiesBeforePruneByGroup.isNotEmpty()) {
                reconcileMultiLocationRemovedProfiles(identitiesBeforePruneByGroup)
            }
            // Explicit Refresh and Smart Connect recovery measure every successfully pinged profile,
            // not a rotating shortlist. For All-servers, benchmark each stored bank independently.
            val aicpGroupCandidate = if (smartConnectCyclePending) smartConnectSourceGroupId else testedGroupId
            val aicpGroup = aicpGroupCandidate?.takeIf { it.isNotBlank() || smartConnectCyclePending }
            var throughputResultsUpdated = false
            val benchmarkGroups = when {
                testedGroupId == null -> emptyList()
                testedGroupId.isBlank() -> (dataSource.getSubsList() + listOf("" )).distinct()
                else -> listOf(testedGroupId)
            }
            if (!realPingSortOnly && benchmarkGroups.isNotEmpty()) {
                _uiState.value.activeOperation?.let { operation ->
                    updateOperationProgress(operation) { it.copy(stage = "Benchmarking", completed = 0, total = 0) }
                }
                val network = physicalNetworkForBatch ?: underlyingInternetNetwork()
                if (network == null) {
                    LogUtil.w(AppConfig.TAG, "Throughput benchmark skipped: no validated physical network; latency order retained")
                    if (!smartConnectCyclePending) {
                        withContext(Dispatchers.Main.immediate) { toast(R.string.throughput_skipped_no_network) }
                    }
                }
                if (network != null) {
                    // The 15-second throughput limit is shared across banks in one Smart Connect recovery cycle.
                    // Recovery is forced too, so do not disable its budget merely because forceThroughput is true.
                    val recoveryBenchmarkStartedNanos = if (smartConnectCyclePending) System.nanoTime() else 0L
                    for ((benchmarkIndex, benchmarkGroup) in benchmarkGroups.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        val remainingRecoveryBudget = if (smartConnectCyclePending) {
                            AicpThroughputTester.remainingRecoveryBudgetMillis(
                                recoveryBenchmarkStartedNanos, System.nanoTime(),
                            )
                        } else 0L
                        if (smartConnectCyclePending && remainingRecoveryBudget <= 0L) {
                            LogUtil.i(AppConfig.TAG, "Smart Connect recovery throughput reached its shared 15-second budget; remaining banks keep latency order")
                            break
                        }
                        val throughputUpdated = try {
                            AicpThroughputTester.measureForGroup(
                                getApplication<Application>(),
                                benchmarkGroup,
                                network,
                                force = forceThroughput || smartConnectCyclePending,
                                eligibleGuids = if (testedGroupId?.isBlank() == true) null else measuredResults.keys.toSet(),
                                budgetMillis = remainingRecoveryBudget,
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            LogUtil.w(AppConfig.TAG, "Throughput test failed for $benchmarkGroup; retain measured latency order", error)
                            0
                        }
                        throughputResultsUpdated = throughputResultsUpdated || throughputUpdated > 0
                        sortGroupByBestMetric(benchmarkGroup)
                        _uiState.value.activeOperation?.let { operation ->
                            updateOperationProgress(operation) { it.copy(stage = "Benchmarking", completed = benchmarkIndex + 1, total = benchmarkGroups.size) }
                        }
                        LogUtil.i(AppConfig.TAG, "Selected-list throughput completed group=$benchmarkGroup recordsUpdated=$throughputUpdated force=${forceThroughput || smartConnectCyclePending}")
                    }
                }
            }
            _uiState.value.activeOperation?.let { operation ->
                updateOperationProgress(operation) { it.copy(stage = "Finalizing", completed = 0, total = 0) }
            }
            val groupToSort = if (smartConnectCyclePending) aicpGroup ?: smartConnectSourceGroupId
                else testedGroupId ?: aicpGroup
            if (!realPingSortOnly) {
                if (testedGroupId != null && testedGroupId.isBlank()) {
                    AicpExistingListRefreshPolicy.groupsToSort(testedGroupId, dataSource.getSubsList())
                        .forEach(::sortGroupByBestMetric)
                    sortGroupByBestMetric("")
                } else if (groupToSort != null) {
                    sortGroupByBestMetric(groupToSort)
                }
            }
            if (pruneOnlyUnreachable) {
                val countryGroupsToSync = if (testedGroupId.isNullOrBlank()) {
                    dataSource.getSubsList().filter { it.startsWith("aicp-finder-") && it.length == "aicp-finder-".length + 2 }
                } else listOf(testedGroupId).filter { it.startsWith("aicp-finder-") && it.length == "aicp-finder-".length + 2 }
                countryGroupsToSync.distinct().forEach { syncMultiLocationFromCountry(it) }
            }
            if (!pruneOnlyUnreachable) {
                aicpRefreshMutex.withLock { pruneEmptyAicpFinderGroups(refreshUi = false) }
            }
            if (realPingSortOnly && testedGroupId != null) sortGroupsByRealPing(testedGroupId)
            val pendingDisconnectRecovery = pendingUnexpectedDisconnectGroupId != null &&
                pendingUnexpectedDisconnectGroupId == completedRequest.groupId &&
                pendingUnexpectedDisconnectGroupId == _uiState.value.selectedGroupId
            // A disconnect recovery is a latency ranking, not a throughput heuristic or a 200 ms filter.
            val failoverLimit = if (smartConnectCyclePending || pendingDisconnectRecovery ||
                (testedGroupId != null && !AicpExistingListRefreshPolicy.isAicpGroup(testedGroupId))) Long.MAX_VALUE
                else AicpFinderQualification.MAX_DELAY_MS
            val bestThroughputCandidate = aicpGroup?.let {
                AicpThroughputTester.fastestCandidate(
                    getApplication<Application>(),
                    it,
                    failoverLimit,
                    eligibleGuids = if (smartConnectCyclePending && smartConnectSourceGuid != null) {
                        measuredResults.keys - smartConnectSourceGuid
                    } else measuredResults.keys.toSet(),
                )
            }
            val latencyFallback = AicpFinderQualification.fastestLatencyCandidate(measuredResults, failoverLimit)
                ?.takeIf { (guid, _) -> guid !in pruneGuids || dataSource.decodeServerConfig(guid) != null }
            val bestCandidate = if (pendingDisconnectRecovery ||
                (smartConnectCyclePending && smartConnectRecoveryWasUnhealthy)) {
                // A failed/degraded live data plane invalidates its cached throughput as a benchmark.
                // Choose the lowest-latency positively measured alternative from the selected list.
                latencyFallback
            } else if (smartConnectCyclePending) {
                val cachedActiveSpeed = smartConnectSourceGuid?.let(AicpThroughputTester::downloadBitsPerSecond) ?: 0L
                val observedActiveSpeed = smartConnectActiveObservedSpeedBitsPerSecond
                val activeSpeed = if (observedActiveSpeed > 0L &&
                    (cachedActiveSpeed <= 0L || observedActiveSpeed < cachedActiveSpeed)) observedActiveSpeed else cachedActiveSpeed
                bestThroughputCandidate?.takeIf { (guid, _) ->
                    guid != smartConnectSourceGuid &&
                        AicpSmartConnectPolicy.isBetterSpeedCandidate(
                            activeSpeed, AicpThroughputTester.downloadBitsPerSecond(guid),
                        )
                }
            } else {
                bestThroughputCandidate ?: latencyFallback
            }
            if (smartConnectCyclePending && bestCandidate == null) {
                if (smartConnectRecoveryWasUnhealthy) {
                    LogUtil.w(AppConfig.TAG, "Smart Connect: no positively measured alternative in the selected list; keeping current profile")
                } else {
                    LogUtil.w(AppConfig.TAG, "Smart Connect: no alternative profile beat the active download speed by the required margin; keeping current profile")
                }
            }
            if (smartConnectCyclePending) {
                smartConnectActiveObservedSpeedBitsPerSecond = 0L
                smartConnectUnhealthyDelayMillis = null
            }
            val canAutoSelectFastest = if (smartConnectCyclePending) {
                AicpSmartConnectPolicy.mayApplyRecoveryResult(
                    smartConnectSourceGuid,
                    _uiState.value.selectedGuid,
                    smartConnectSourceGroupId,
                    _uiState.value.selectedGroupId,
                    recoveryWasActive = wasSmartConnectCycle,
                    tunnelStillRunning = _uiState.value.isRunning,
                    smartConnectStillEnabled = _uiState.value.smartConnectEnabled,
                )
            } else {
                (selectFastest || pendingDisconnectRecovery) && !_uiState.value.isRunning &&
                    (testedGroupId == null || testedGroupId == _uiState.value.selectedGroupId)
            }
            if (canAutoSelectFastest) bestCandidate?.let { (guid, _) ->
                val previousGuid = _uiState.value.selectedGuid
                if (guid != previousGuid) {
                    updateSelectedGuid(guid)
                    if (wasSmartConnectCycle && _uiState.value.isRunning && _uiState.value.smartConnectEnabled) {
                        dataSource.sendMsg2Service(AppConfig.MSG_STATE_RELOAD, "")
                        LogUtil.i(AppConfig.TAG, "Smart Connect selected a faster measured profile in group ${aicpGroup ?: testedGroupId.orEmpty()}; requesting in-place core reload")
                    }
                }
            }
            // A one-profile ping only updates its result row; reloading every subscription here
            // caused visible refresh churn and unnecessary disk work. Reload only after a batch
            // that actually pruned or selected profiles.
            val multiLocationGroupId = "aicp-multilocation"
            if (multiLocationRecoveryActive && dataSource.getSubscriptionItem(multiLocationGroupId) != null) {
                if (_uiState.value.groups.none { it.id == multiLocationGroupId }) {
                    setupGroupTab(forceRefresh = true).join()
                } else {
                    cacheMutex.withLock { groupDataCache.remove(multiLocationGroupId) }
                    updateGroupUi(multiLocationGroupId, loadGroup(multiLocationGroupId, forceRefresh = true))
                }
            }
            if (pruneGuids.isNotEmpty() || selectGuids.isNotEmpty() || throughputResultsUpdated || aicpGroup != null) {
                // Only the tested subscription changed. Reloading every tab caused needless
                // disk reads, state churn and the visible "continuous refresh" symptom.
                val recoveryContextStillCurrent = !smartConnectCyclePending || AicpSmartConnectPolicy.isCurrentRecoveryContext(
                    smartConnectSourceGuid,
                    _uiState.value.selectedGuid,
                    smartConnectSourceGroupId,
                    _uiState.value.selectedGroupId,
                )
                val changedGroup = if (smartConnectCyclePending && recoveryContextStillCurrent) {
                    smartConnectSourceGroupId ?: _uiState.value.selectedGroupId
                } else if (smartConnectCyclePending) {
                    _uiState.value.selectedGroupId
                } else {
                    testedGroupId ?: _uiState.value.selectedGroupId
                }
                val reading = loadGroup(changedGroup, forceRefresh = true)
                updateGroupUi(changedGroup, reading)
                if (_uiState.value.selectedGroupId == changedGroup) {
                    _uiState.update { it.copy(selectedGuid = dataSource.getSelectServer()) }
                }
            }
            if (pendingDisconnectRecovery) {
                val selectedGuid = _uiState.value.selectedGuid
                val selectedDelay = selectedGuid?.let { dataSource.decodeAffiliationInfo(it)?.testDelayMillis }
                if (_uiState.value.smartConnectEnabled && !_uiState.value.isRunning &&
                    _uiState.value.selectedGroupId == pendingUnexpectedDisconnectGroupId &&
                    selectedGuid != null && selectedDelay != null && selectedDelay > 0L) {
                    clearPendingUnexpectedDisconnectRecovery()
                    LogUtil.i(AppConfig.TAG, "Smart Connect: selected fastest reachable profile in current tab (real ping ${selectedDelay}ms); reconnecting")
                    _autoReconnectRequests.emit(Unit)
                } else {
                    clearPendingUnexpectedDisconnectRecovery()
                    LogUtil.w(AppConfig.TAG, "Smart Connect: no positively measured profile available in the current tab; reconnect skipped")
                }
                finishVpnOperation(VpnOperation.SMART_CONNECT)
            }
            if (multiLocationFinalRefreshPending && testedGroupId == "aicp-multilocation") {
                multiLocationFinalRefreshPending = false
                toast(R.string.finder_multi_location_done)
                LogUtil.i(AppConfig.TAG, "Multi-location completed: qualified=$multiLocationSuccessfulCount scannedCountries=$multiLocationScannedLocations finalAggregateProfiles=${dataSource.getServerGuidList("aicp-multilocation").size}")
            }
            if (pruneGuids.isNotEmpty()) {
                val bestDelayText = bestCandidate?.second?.toString() ?: "—"
                toast(dataSource.getString(R.string.finder_completed, pruneGuids.size, removedFailed,
                    pruneGuids.size - removedFailed, bestDelayText))
            }
            launchPendingSmartConnectFailoverIfReady()
            if (multiLocationRecoveryActive) startNextMultiLocationTest()
            when (val active = _uiState.value.activeOperation) {
                VpnOperation.SORT_AND_COLLECT, VpnOperation.TEST_REAL_DELAYS, VpnOperation.SELECTED_CONFIG_PING -> finishVpnOperation(active)
                VpnOperation.FIND_PUBLIC_CONFIGS -> if (!multiLocationRecoveryActive && !multiLocationFinalRefreshPending) finishVpnOperation(active)
                VpnOperation.SMART_CONNECT -> if (!pendingDisconnectRecovery) finishVpnOperation(active)
                else -> Unit
            }
            } finally {
                if (testCompletionJob === currentCoroutineContext()[Job]) testCompletionJob = null
            }
        }
        testCompletionJob = completionJob
        completionJob.start()
    }

    /** Stores each qualifying profile once in the dedicated Multi-Location tab. */
    private fun addToMultiLocationGroup(
        profile: ProfileItem,
        realPingMillis: Long? = null,
        sourceGroupId: String? = null,
    ): Boolean {
        val groupId = "aicp-multilocation"
        val sourceCountry = sourceGroupId?.takeIf {
            it.startsWith("aicp-finder-") && it.length == "aicp-finder-".length + 2
        }?.removePrefix("aicp-finder-")?.uppercase()
        if (dataSource.getSubscriptionItem(groupId) == null) {
            val stored = MmkvManager.tryEncodeSubscription(groupId, SubscriptionItem(
                remarks = "AICP Multi-Location", url = "", enabled = true, autoUpdate = false
            )) ?: return false
        }
        val identity = profile.duplicateIdentity()
        val prefs = getApplication<Application>().getSharedPreferences("aicp_multilocation_origin", Context.MODE_PRIVATE)
        val existingGuid = dataSource.getServerGuidList(groupId).firstOrNull { guid ->
            dataSource.decodeServerConfig(guid)?.duplicateIdentity() == identity
        }
        if (existingGuid != null) {
            if (sourceCountry != null && prefs.getString(existingGuid, null) == null) prefs.edit().putString(existingGuid, sourceCountry).apply()
            return false
        }
        return runCatching {
            val newGuid = MmkvManager.encodeServerConfig("", profile.copy(subscriptionId = groupId))
            if (sourceCountry != null) prefs.edit().putString(newGuid, sourceCountry).apply()
            if (realPingMillis != null && AicpMultiLocationPolicy.qualifiesRealPing(realPingMillis)) {
                MmkvManager.encodeServerTestDelayMillis(newGuid, realPingMillis)
            }
            sortGroupByBestMetric(groupId)
            true
        }.getOrElse { error ->
            LogUtil.e(AppConfig.TAG, "Could not add qualified config to Multi-Location", error)
            false
        }
    }

    private suspend fun syncMultiLocationFromCountry(countryGroupId: String) {
        if (!countryGroupId.startsWith("aicp-finder-") || countryGroupId.length != "aicp-finder-".length + 2) return
        val multiGroupId = "aicp-multilocation"
        if (dataSource.getSubscriptionItem(multiGroupId) == null) {
            if (MmkvManager.tryEncodeSubscription(multiGroupId, SubscriptionItem(
                    remarks = "AICP Multi-Location", url = "", enabled = true, autoUpdate = false,
                )) == null) return
        }
        val country = countryGroupId.removePrefix("aicp-finder-").uppercase()
        val multiGuids = dataSource.getServerGuidList(multiGroupId).toMutableList()
        val prefs = getApplication<Application>().getSharedPreferences("aicp_multilocation_origin", Context.MODE_PRIVATE)
        var addedOrUpdated = 0
        dataSource.getServerGuidList(countryGroupId).forEach { sourceGuid ->
            val info = dataSource.decodeAffiliationInfo(sourceGuid) ?: return@forEach
            if (!AicpMultiLocationPolicy.qualifiesRealPing(info.testDelayMillis)) return@forEach
            val profile = dataSource.decodeServerConfig(sourceGuid) ?: return@forEach
            val identity = profile.duplicateIdentity()
            val destinationGuid = multiGuids.firstOrNull { guid ->
                dataSource.decodeServerConfig(guid)?.duplicateIdentity() == identity
            } ?: runCatching {
                MmkvManager.encodeServerConfig("", profile.copy(subscriptionId = multiGroupId)).also { multiGuids += it }
            }.getOrNull() ?: return@forEach
            prefs.edit().putString(destinationGuid, country).apply()
            MmkvManager.encodeServerTestDelayMillis(destinationGuid, info.testDelayMillis)
            val destinationInfo = dataSource.decodeAffiliationInfo(destinationGuid)
            if (info.throughputMeasuredAtMillis > 0L &&
                info.throughputMeasuredAtMillis >= (destinationInfo?.throughputMeasuredAtMillis ?: 0L)) {
                MmkvManager.encodeServerThroughput(
                    guid = destinationGuid,
                    downloadBitsPerSecond = info.downloadBitsPerSecond,
                    uploadBitsPerSecond = info.uploadBitsPerSecond,
                    measuredAtMillis = info.throughputMeasuredAtMillis,
                    downloadBytes = info.throughputDownloadBytes,
                    uploadBytes = info.throughputUploadBytes,
                    downloadDurationMillis = info.throughputDownloadDurationMillis,
                    uploadDurationMillis = info.throughputUploadDurationMillis,
                    sampleCount = info.throughputSampleCount,
                    downloadVariationPercent = info.throughputDownloadVariationPercent,
                    uploadVariationPercent = info.throughputUploadVariationPercent,
                    loadedLatencyMillis = info.throughputLoadedLatencyMillis,
                    probeLossPercent = info.throughputProbeLossPercent,
                    error = info.throughputError,
                )
            }
            addedOrUpdated++
        }
        // Multi-Location is an AICP-generated aggregate: collapse duplicate identities but never remove the live GUID.
        val activeGuid = dataSource.getSelectServer()
        val seen = HashSet<Any>()
        multiGuids.forEach { guid ->
            val profile = dataSource.decodeServerConfig(guid)
            val identity = profile?.duplicateIdentity()
            if (guid == activeGuid) {
                if (identity != null) seen.add(identity)
            } else if (identity == null || !seen.add(identity)) {
                dataSource.removeServer(guid)
            }
        }
        sortGroupByBestMetric(multiGroupId)
        cacheMutex.withLock { groupDataCache.remove(multiGroupId) }
        if (_uiState.value.groups.any { it.id == multiGroupId }) {
            updateGroupUi(multiGroupId, loadGroup(multiGroupId, forceRefresh = true))
        } else {
            setupGroupTab(forceRefresh = true).join()
        }
        LogUtil.i(AppConfig.TAG, "Multi-Location reconciled from $countryGroupId: copiedOrUpdated=$addedOrUpdated total=${dataSource.getServerGuidList(multiGroupId).size}")
    }

    fun triggerLocateSelectedServer() {
        val selected = dataSource.getSelectServer() ?: return
        val profile = dataSource.decodeServerConfig(selected) ?: return
        val groupId = profile.subscriptionId
        if (_uiState.value.groups.none { it.id == groupId }) return
        viewModelScope.launch(ioDispatcher) {
            updateGroupUi(groupId, loadGroup(groupId))
            if (_uiState.value.selectedGroupId != groupId) {
                dataSource.setSelectedSubscriptionId(groupId)
            }
            val target = LocateTarget(groupId, selected)
            _uiState.update {
                it.copy(selectedGroupId = groupId, locateTarget = target)
            }
        }
    }

    private fun consumeLocateTarget() {
        _uiState.update { it.copy(locateTarget = null) }
    }

    // ---------- Running state ----------
    private fun updateRunningState(running: Boolean, clearTestingText: Boolean = true) {
        val wasRunning = _uiState.value.isRunning
        if (!running || clearTestingText) {
            testRequests.invalidateCurrent()
            smartConnectProbeRequestId = null
            smartConnectProbeGuid = null
        }
        if (!running) {
            smartConnectPendingFailoverGuid = null
            smartConnectUnhealthySinceElapsedMillis = null
            smartConnectUnhealthyProbeGuid = null
            smartConnectRecoveryMode = false
            smartConnectHealthyProbeCount = 0
        }
        if (!running) egressVerificationJob?.cancel()
        _uiState.update { state ->
            state.copy(
                isRunning = running,
                isTesting = testRequests.isTesting,
                status = runningStatus(state.status, state.isRunning, running, clearTestingText),
                egressCheckStatus = if (running) state.egressCheckStatus else AicpEgressCheckStatus.Unknown,
                egressObservedCountry = if (running) state.egressObservedCountry else null,
                egressObservedRegion = if (running) state.egressObservedRegion else null,
                egressObservedCity = if (running) state.egressObservedCity else null,
                egressCheckedAtMillis = if (running) state.egressCheckedAtMillis else null,
            )
        }
        if (running && !wasRunning) verifySelectedEgressAsync()
    }

    /** Verify observed exit IP/country/region/city after the tunnel comes up. A mismatch warns; it never fabricates success. */
    private fun verifySelectedEgressAsync() {
        egressVerificationJob?.cancel()
        val context = getApplication<Application>()
        val prefs = context.getSharedPreferences("aicp_network_identity", Context.MODE_PRIVATE)
        val target = AicpEgressVerifier.Target(
            countryCode = (prefs.getString("target_country", "DE") ?: "DE").uppercase(),
            region = prefs.getString("target_region", "Hesse") ?: "Hesse",
            city = prefs.getString("target_city", "Frankfurt am Main") ?: "Frankfurt am Main",
        )
        _uiState.update { state ->
            state.copy(
                egressCheckStatus = AicpEgressCheckStatus.Checking,
                egressTargetCountry = target.countryCode,
                egressTargetRegion = target.region,
                egressTargetCity = target.city,
                egressObservedCountry = null,
                egressObservedRegion = null,
                egressObservedCity = null,
                egressCheckedAtMillis = null,
            )
        }
        egressVerificationJob = viewModelScope.launch(ioDispatcher) {
            try {
                kotlinx.coroutines.delay(900L)
                if (!_uiState.value.isRunning) return@launch
                val outcome = runCatching { AicpEgressVerifier.verify(context) }
                if (!currentCoroutineContext().isActive || !_uiState.value.isRunning) return@launch
                outcome.fold(
                onSuccess = { snapshot ->
                    val confirmed = AicpEgressVerifier.confirmsTargetLocation(snapshot, target)
                    _uiState.update { state ->
                        if (!state.isRunning) state else state.copy(
                            egressCheckStatus = if (confirmed) AicpEgressCheckStatus.Verified else AicpEgressCheckStatus.Mismatch,
                            egressTargetCountry = target.countryCode,
                            egressTargetRegion = target.region,
                            egressTargetCity = target.city,
                            egressObservedCountry = snapshot.location.countryCode,
                            egressObservedRegion = snapshot.location.region,
                            egressObservedCity = snapshot.location.city,
                            egressCheckedAtMillis = snapshot.observedAtMillis,
                        )
                    }
                    if (!confirmed) LogUtil.w(AppConfig.TAG, "AICP egress does not match selected target ${target.countryCode}/${target.region}/${target.city}")
                },
                onFailure = { error ->
                    LogUtil.e(AppConfig.TAG, "Automatic AICP egress verification failed", error)
                    _uiState.update { state ->
                        if (!state.isRunning) state else state.copy(egressCheckStatus = AicpEgressCheckStatus.Failed)
                    }
                },
                )
            } finally {
                finishVpnOperation(VpnOperation.VERIFY_EXIT)
            }
        }
    }

    override fun onCleared() {
        smartConnectCycleRunning = false
        multiLocationRecoveryActive = false
        multiLocationScanJob?.cancel()
        publicConfigFinderJob?.cancel()
        testCompletionJob?.cancel()
        smartConnectProbeJob?.cancel()
        smartConnectRealPingJob?.cancel()
        multiLocationTestQueue.clear()
        setupGroupJob?.cancel()
        preloadJob?.cancel()
        selectedGroupLoadJob?.cancel()
        reloadJob?.cancel()
        filterJob?.cancel()
        egressVerificationJob?.cancel()
        cancelAllPing()
        dataSource.close()
        super.onCleared()
    }

    /** AICP: what becomes of a reading of a group, see [readingFate]. */
    internal enum class ReadingFate { SHOW, OVERTAKEN, HOLD_UNTIL_SETTLED, READ_AGAIN }

    companion object {
        private const val TEST_RESULT_FLUSH_INTERVAL_MS = 500L

        /**
         * AICP: what becomes of a reading of a group numbered [number] when the one shown last is [lastShown], taken
         * when [settledWhenRead] of the group's moves were settled, now that [shownMoves] were shown and [unsettledMoves]
         * of them are not settled yet, and filtered as the search stands now or not, [filteredAsSearched]: one older than
         * the one shown is not shown over it; one that a move shown since it was taken overtook waits until the group's
         * moves are settled, or, when they are by now, is taken anew, as one filtered for a search changed since is;
         * else it is shown.
         */
        internal fun readingFate(
            number: Int,
            lastShown: Int,
            settledWhenRead: Int,
            shownMoves: Int,
            unsettledMoves: Int,
            filteredAsSearched: Boolean,
        ): ReadingFate =
            when {
                number < lastShown -> ReadingFate.OVERTAKEN
                settledWhenRead < shownMoves && unsettledMoves > 0 -> ReadingFate.HOLD_UNTIL_SETTLED
                settledWhenRead < shownMoves || !filteredAsSearched -> ReadingFate.READ_AGAIN
                else -> ReadingFate.SHOW
            }

        /**
         * The status after a running or stopped signal. A test text survives a repeated signal
         * that changes nothing, but a connecting status does not: the daemon repeats the
         * connecting signal right after a running one whenever the tunnel is still on its way.
         */
        internal fun runningStatus(
            current: MainStatus,
            wasRunning: Boolean,
            running: Boolean,
            clearTestingText: Boolean,
        ): MainStatus =
            if (!clearTestingText && wasRunning == running && current !is MainStatus.Connecting) current
            else if (running) MainStatus.Connected else MainStatus.Disconnected
    }

    // ---------- Factory ----------
    class Factory(private val application: Application, private val dataSource: MainDataSource) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                return MainViewModel(application, dataSource) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
