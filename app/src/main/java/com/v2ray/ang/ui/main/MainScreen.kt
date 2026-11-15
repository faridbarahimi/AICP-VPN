package com.v2ray.ang.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.ui.compose.LocalDarkTheme
import com.v2ray.ang.ui.compose.QRCodeDialog
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    mainViewModel: MainViewModel,
    onAction: (MainAction) -> Unit,
    onNavigate: (MainDestination) -> Unit,
) {
    val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val trafficHistory by NotificationManager.trafficHistory.collectAsStateWithLifecycle()
    val groups = uiState.groups
    val isLoading by mainViewModel.isLoading.collectAsStateWithLifecycle()
    val isRunning = uiState.isRunning
    val egressStatusText = when (uiState.egressCheckStatus) {
        AicpEgressCheckStatus.Unknown -> ""
        AicpEgressCheckStatus.Checking -> stringResource(R.string.identity_auto_checking, uiState.egressTargetCountry)
        AicpEgressCheckStatus.Verified -> stringResource(
            R.string.identity_auto_verified,
            uiState.egressObservedCountry.orEmpty(),
            uiState.egressObservedRegion.orEmpty(),
            uiState.egressObservedCity.orEmpty(),
        )
        AicpEgressCheckStatus.Mismatch -> stringResource(
            R.string.identity_auto_mismatch,
            uiState.egressTargetCountry,
            uiState.egressTargetRegion,
            uiState.egressTargetCity,
            uiState.egressObservedCountry.orEmpty(),
            uiState.egressObservedRegion.orEmpty(),
            uiState.egressObservedCity.orEmpty(),
        )
        AicpEgressCheckStatus.Failed -> stringResource(R.string.identity_auto_failed)
    }
    val displayText = buildString {
        append(mainViewModel.formatStatus(uiState.status))
        if (uiState.isRunning && egressStatusText.isNotBlank()) append("\n").append(egressStatusText)
    }
    val selectedGuid = uiState.selectedGuid
    val doubleColumnDisplay = uiState.doubleColumnDisplay
    val confirmRemove = uiState.confirmRemove
    val shareQRCodeBitmap = uiState.shareQRCodeBitmap

    // AICP: a move of a profile the storage refused is told once, each refusal by its number, so that one set again
    // right after the last was told is told too; the groups are shown anew, as stored.
    val context = LocalContext.current
    val failureText = stringResource(R.string.toast_failure)
    val moveRefusal = uiState.moveRefusal
    LaunchedEffect(moveRefusal) {
        if (moveRefusal != null) {
            context.toastError(failureText)
            onAction(MainAction.MoveRefusalShown(moveRefusal))
        }
    }

    val isDarkTheme = LocalDarkTheme.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showDelAllConfirm by remember { mutableStateOf(false) }
    var showCountryPicker by remember { mutableStateOf(false) }
    var showIdentityDialog by remember { mutableStateOf(false) }
    var showDelDuplicateConfirm by remember { mutableStateOf(false) }
    var showDelInvalidConfirm by remember { mutableStateOf(false) }
    var deleteCountryGroup by remember { mutableStateOf<com.v2ray.ang.dto.GroupMapItem?>(null) }
    var showRemoveConfirm by rememberSaveable(stateSaver = ServerDeleteTarget.Saver) {
        mutableStateOf<ServerDeleteTarget?>(null)
    }

    var shareTarget by remember { mutableStateOf<Triple<String, ProfileItem, Boolean>?>(null) }
    val removeServer: (String, String) -> Unit = { guid, profileName ->
        if (confirmRemove) {
            showRemoveConfirm = ServerDeleteTarget(guid, profileName)
        } else {
            onAction(MainAction.RemoveServer(guid))
        }
    }

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { groups.size.coerceAtLeast(1) }
    )

    val lazyListStates = remember { mutableStateMapOf<String, LazyListState>() }
    val lazyGridStates = remember { mutableStateMapOf<String, LazyGridState>() }

    LaunchedEffect(groups) {
        val validGroupIds = groups.map { it.id }.toSet()
        lazyListStates.keys.retainAll(validGroupIds)
        lazyGridStates.keys.retainAll(validGroupIds)
    }

    LaunchedEffect(groups, uiState.selectedGroupId) {
        if (groups.isEmpty()) return@LaunchedEffect
        val selectedIndex = groups.indexOfFirst { it.id == uiState.selectedGroupId }
            .takeIf { it >= 0 } ?: 0
        if (!pagerState.isScrollInProgress && pagerState.settledPage != selectedIndex) {
            pagerState.scrollToPage(selectedIndex)
        }
    }

    val latestGroups by rememberUpdatedState(groups)

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                val currentGroups = latestGroups
                if (page in currentGroups.indices) {
                    onAction(MainAction.SelectGroup(currentGroups[page].id))
                }
            }
    }

    if (uiState.multiLocationNeedsContinue) {
        AlertDialog(
            onDismissRequest = { onAction(MainAction.StopMultiLocation) },
            title = { Text(stringResource(R.string.finder_multi_location_continue_title)) },
            text = { Text(stringResource(R.string.finder_multi_location_continue_message)) },
            confirmButton = {
                TextButton(onClick = { onAction(MainAction.ContinueMultiLocation) }) {
                    Text(stringResource(R.string.finder_multi_location_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(MainAction.StopMultiLocation) }) {
                    Text(stringResource(R.string.finder_multi_location_stop))
                }
            }
        )
    }

    MainDialogs(
        showDelAllConfirm = showDelAllConfirm,
        onDismissDelAll = { showDelAllConfirm = false },
        onConfirmDelAll = { showDelAllConfirm = false; onAction(MainAction.RemoveAllServers) },
        showDelDuplicateConfirm = showDelDuplicateConfirm,
        onDismissDelDuplicate = { showDelDuplicateConfirm = false },
        onConfirmDelDuplicate = { showDelDuplicateConfirm = false; onAction(MainAction.RemoveDuplicateServers) },
        showDelInvalidConfirm = showDelInvalidConfirm,
        onDismissDelInvalid = { showDelInvalidConfirm = false },
        onConfirmDelInvalid = { showDelInvalidConfirm = false; onAction(MainAction.RemoveInvalidServers) },
        showRemoveConfirm = showRemoveConfirm,
        onDismissRemove = { showRemoveConfirm = null },
        onConfirmRemove = { guid -> showRemoveConfirm = null; onAction(MainAction.RemoveServer(guid)) }
    )

    if (shareTarget != null) {
        val (guid, profile, more) = shareTarget!!
        ShareMethodDialog(
            guid = guid,
            profile = profile,
            more = more,
            onDismiss = { shareTarget = null },
            onAction = onAction,
            onRemove = removeServer,
        )
    }
    if (showIdentityDialog) {
        AicpIdentityDialog(
            isVpnRunning = isRunning,
            onDismiss = { showIdentityDialog = false }
        )
    }
    if (showCountryPicker) {
        PublicConfigCountryDialog(
            onDismiss = { showCountryPicker = false },
            onSelect = { countryCode, multiLocationRecovery ->
                showCountryPicker = false
                onAction(MainAction.FindPublicConfigs(countryCode, multiLocationRecovery))
            }
        )
    }
    deleteCountryGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { deleteCountryGroup = null },
            title = { Text(stringResource(R.string.country_group_delete_title)) },
            text = { Text(stringResource(R.string.country_group_delete_message, group.remarks)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteCountryGroup = null
                    onAction(MainAction.RemoveCountryGroup(group.id))
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteCountryGroup = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
    if (shareQRCodeBitmap != null) {
        QRCodeDialog(bitmap = shareQRCodeBitmap, onDismiss = { onAction(MainAction.DismissQRCodeDialog) })
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            MainDrawerContent(
                drawerState = drawerState,
                onNavigate = { route ->
                    scope.launch { drawerState.close() }
                    onNavigate(route)
                }
            )
        }
    ) {
        Scaffold(
            contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
            topBar = {
                MainTopBar(
                    isLoading = isLoading,
                    showSearch = showSearch,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { query: String ->
                        searchQuery = query
                        onAction(MainAction.Search(query))
                    },
                    onSearchClose = {
                        searchQuery = ""
                        onAction(MainAction.Search(""))
                        showSearch = false
                    },
                    onSearchToggle = { show: Boolean -> showSearch = show },
                    onMenuClick = { scope.launch { drawerState.open() } },
                    onAction = onAction,
                    onMoreMenuAction = { action ->
                        when (action) {
                            MainMoreMenuAction.RestartService -> onAction(MainAction.RestartService)
                            MainMoreMenuAction.DeleteAll -> showDelAllConfirm = true
                            MainMoreMenuAction.DeleteDuplicate -> showDelDuplicateConfirm = true
                            MainMoreMenuAction.DeleteInvalid -> showDelInvalidConfirm = true
                            MainMoreMenuAction.ExportAll -> onAction(MainAction.ExportAll)
                            MainMoreMenuAction.LocateSelected -> onAction(MainAction.LocateSelectedServer)
                            MainMoreMenuAction.SortByTestResults -> onAction(MainAction.SortByTestResults)
                            MainMoreMenuAction.TestAll -> onAction(MainAction.TestAllServers)
                            MainMoreMenuAction.TestAllRealPing -> onAction(MainAction.TestRealAllServers)
                            MainMoreMenuAction.UpdateSubscriptions -> onAction(MainAction.UpdateSubscriptions)
                            MainMoreMenuAction.AutoFindPublicConfigs -> showCountryPicker = true
                            MainMoreMenuAction.VerifyEgressIdentity -> showIdentityDialog = true
                            MainMoreMenuAction.SmartConnect -> onAction(MainAction.ToggleSmartConnect)
                        }
                    }
                )
            },
            bottomBar = {
                MainBottomBar(
                    displayText = displayText,
                    isRunning = isRunning,
                    smartConnectEnabled = uiState.smartConnectEnabled,
                    isDarkTheme = isDarkTheme,
                    isRefreshingConfigs = uiState.isRefreshingConfigs,
                    isTesting = uiState.isTesting,
                    activeOperation = uiState.activeOperation,
                    operationProgress = uiState.operationProgress,
                    trafficHistory = trafficHistory,
                    onAction = onAction
                )
            },
            floatingActionButton = {},
        ) { innerPadding ->
            val layoutDirection = LocalLayoutDirection.current

            if (groups.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    HomePromotionBanner(modifier = Modifier.fillMaxWidth())
                    val liveFinderProgress = uiState.operationProgress?.takeIf {
                        it.operation == VpnOperation.FIND_PUBLIC_CONFIGS && uiState.selectedGroupId.startsWith("aicp-finder-")
                    }
                    if (liveFinderProgress != null) {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text(
                                text = stringResource(
                                    R.string.aicp_finder_live_counts,
                                    liveFinderProgress.found,
                                    liveFinderProgress.removed,
                                    liveFinderProgress.retained,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                                maxLines = 1,
                            )
                        }
                    }
                    if (groups.size > 1) {
                        GroupTabBar(
                            groups = groups,
                            selectedTabIndex = pagerState.currentPage.coerceIn(0, groups.lastIndex),
                            mainViewModel = mainViewModel,
                            onTabClick = { targetIndex ->
                                scope.launch {
                                    pagerState.navigateToPageOptimized(
                                        targetPage = targetIndex,
                                        animateAdjacentPage = true
                                    )
                                }
                            },
                            onDeleteCountryGroup = { deleteCountryGroup = it }
                        )
                    }

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        userScrollEnabled = true,
                        beyondViewportPageCount = 1,
                        key = { page -> groups.getOrNull(page)?.id ?: "group-page-$page" }
                    ) { page ->
                        val group = groups.getOrNull(page) ?: return@HorizontalPager

                        GroupPagerPage(
                            groupId = group.id,
                            mainViewModel = mainViewModel,
                            selectedGuid = selectedGuid,
                            locateTarget = uiState.locateTarget,
                            doubleColumnDisplay = doubleColumnDisplay,
                            searchQuery = searchQuery,
                            lazyListStates = lazyListStates,
                            lazyGridStates = lazyGridStates,
                            onSelectServer = { guid -> onAction(MainAction.SelectServer(guid)) },
                            onEditServer = { guid, profile -> onAction(MainAction.EditServer(guid, profile)) },
                            onShareServer = { guid, profile ->
                                shareTarget = Triple(guid, profile, false)
                            },
                            onMoreServer = { guid, profile ->
                                shareTarget = Triple(guid, profile, true)
                            },
                            onRemoveServer = removeServer,
                            contentPadding = PaddingValues(
                                start = 0.dp,
                                top = 0.dp,
                                end = 0.dp,
                                bottom = 80.dp
                            )
                        )
                    }
                }
            } else {
                HomePromotions(
                    modifier = Modifier.fillMaxSize().padding(innerPadding)
                )
            }
        }
    }
}
