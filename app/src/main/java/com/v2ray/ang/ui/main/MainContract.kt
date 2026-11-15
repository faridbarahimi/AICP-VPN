package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.LocateTarget

/** Locale-neutral state formatted only when it reaches the main UI. */
sealed interface MainStatus {
    data object Disconnected : MainStatus
    data object Connected : MainStatus

    /** Running, but the profile is still bringing its tunnel up; the daemon supplies the text. */
    data class Connecting(val message: String) : MainStatus
    data object Testing : MainStatus
    data class TestProgress(val progress: String) : MainStatus
    data class ConnectionTest(val result: ConnectionTestResult) : MainStatus
}

/**
 * Main UI state
 */
enum class AicpEgressCheckStatus { Unknown, Checking, Verified, Mismatch, Failed }

enum class VpnOperation { SORT_AND_COLLECT, TEST_REAL_DELAYS, SELECTED_CONFIG_PING, FIND_PUBLIC_CONFIGS, VERIFY_EXIT, SMART_CONNECT }

/** Live progress for one bottom-bar operation; a zero total means the current phase is indeterminate. */
data class OperationProgress(
    val operation: VpnOperation,
    val stage: String,
    val completed: Int = 0,
    val total: Int = 0,
    val found: Int = 0,
    val removed: Int = 0,
    val retained: Int = 0,
) {
    val fraction: Float get() = if (total > 0) (completed.toFloat() / total).coerceIn(0f, 1f) else 0f
}

internal object AicpVpnOperationPolicy {
    const val SMART_CONNECT_PING_INTERVAL_MS = 60_000L
    fun canStart(active: VpnOperation?): Boolean = active == null
    fun canStop(active: VpnOperation?, requested: VpnOperation): Boolean = active == requested
    /** Smart Connect must qualify the selected list before the first manual connection. */
    fun shouldBenchmarkBeforeConnect(isRunning: Boolean): Boolean = !isRunning
}

data class MainUiState(
    val groups: List<GroupMapItem> = emptyList(),
    val selectedGroupId: String = "",
    val selectedGuid: String? = null,
    val isRunning: Boolean = false,
    val isTesting: Boolean = false,
    val isRefreshingConfigs: Boolean = false,
    val activeOperation: VpnOperation? = null,
    val egressCheckStatus: AicpEgressCheckStatus = AicpEgressCheckStatus.Unknown,
    val egressTargetCountry: String = "DE",
    val egressTargetRegion: String = "Hesse",
    val egressTargetCity: String = "Frankfurt am Main",
    val egressObservedCountry: String? = null,
    val egressObservedRegion: String? = null,
    val egressObservedCity: String? = null,
    val egressCheckedAtMillis: Long? = null,
    val status: MainStatus = MainStatus.Disconnected,
    val locateTarget: LocateTarget? = null,
    val confirmRemove: Boolean = false,
    val doubleColumnDisplay: Boolean = false,
    val shareQRCodeBitmap: android.graphics.Bitmap? = null,
    /**
     * AICP: a move of a profile the storage refused, still to be told, by a number of its own, so that a refusal set
     * again right after the last one was told is told too; null when none, see [MainAction.MoveRefusalShown].
     */
    val moveRefusal: Int? = null,
    /** True when sequential Multi-Location qualification reaches its 50-config checkpoint. */
    val multiLocationNeedsContinue: Boolean = false,
    /** Smart Connect monitors direct/tunnel graph speed gaps and tests the selected tab on sustained degradation. */
    val smartConnectEnabled: Boolean = true,
    val operationProgress: OperationProgress? = null
)

/**
 * All possible user interaction intents
 */
sealed interface MainAction {
    data object Initialize : MainAction

    /** The screen became visible: what it shows of the service may be out of date. */
    data object RefreshServiceState : MainAction
    data object RefreshGroups : MainAction
    /** Refresh and test public configs for the currently selected AICP target country. */
    data object RefreshAicpConfigs : MainAction
    data object ToggleService : MainAction
    data object TestCurrentServer : MainAction
    data object TestAllServers : MainAction
    data object TestRealAllServers : MainAction
    data object CancelTesting : MainAction
    data object RemoveAllServers : MainAction
    data object RemoveDuplicateServers : MainAction
    data object RemoveInvalidServers : MainAction
    data object SortByTestResults : MainAction
    data object ContinueMultiLocation : MainAction
    data object StopMultiLocation : MainAction
    data class StartVpnOperation(val operation: VpnOperation) : MainAction
    data class StopVpnOperation(val operation: VpnOperation) : MainAction
    data object ToggleSmartConnect : MainAction
    data object UpdateSubscriptions : MainAction
    data class FindPublicConfigs(val countryCode: String, val multiLocationRecovery: Boolean = false) : MainAction
    data object ExportAll : MainAction

    data object ImportQRcode : MainAction
    data object ImportClipboard : MainAction
    data object ImportConfigLocal : MainAction
    data class ImportManually(val type: Int) : MainAction
    data object RestartService : MainAction
    data object LocateSelectedServer : MainAction

    data class SelectGroup(val groupId: String) : MainAction
    data class RemoveCountryGroup(val groupId: String) : MainAction
    data class SelectServer(val guid: String) : MainAction
    data class RemoveServer(val guid: String) : MainAction

    /** AICP: a drag moved the profile [fromGuid] names to where the one [toGuid] names stands in [groupId]. */
    data class MoveServer(val groupId: String, val fromGuid: String, val toGuid: String) : MainAction

    data class EditServer(val guid: String, val profile: com.v2ray.ang.dto.entities.ProfileItem) : MainAction
    data class Search(val query: String) : MainAction
    data class ShareQRCode(val guid: String) : MainAction
    data class ShareClipboard(val guid: String) : MainAction
    data class ShareFullContent(val guid: String) : MainAction
    data object DismissQRCodeDialog : MainAction

    data class ImportBatchConfig(val configText: String) : MainAction

    data object LocateHandled : MainAction

    /** AICP: the refusal of a move numbered [refusal], see [MainUiState.moveRefusal], was told. */
    data class MoveRefusalShown(val refusal: Int) : MainAction
}
