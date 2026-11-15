package com.v2ray.ang.ui.main

import android.app.Application
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class MainViewModelAicpCleanupTest {
    @Test
    fun cleanupRemovesOnlyEmptyAicpFinderBanksAndKeepsUserAndNonEmptyGroups() = runBlocking {
        val source = mock<MainDataSource>()
        whenever(source.mainServiceEvent).thenReturn(emptyFlow())
        whenever(source.getSelectedSubscriptionId()).thenReturn("user-sub")
        whenever(source.getSelectServer()).thenReturn(null)
        whenever(source.getConfirmRemove()).thenReturn(false)
        whenever(source.getDoubleColumnDisplay()).thenReturn(false)
        whenever(source.getSubscriptions()).thenReturn(
            listOf(
                SubscriptionCache("aicp-finder-de", SubscriptionItem(remarks = "AICP Finder Germany")),
                SubscriptionCache("aicp-finder-us", SubscriptionItem(remarks = "AICP Finder United States")),
                SubscriptionCache("user-sub", SubscriptionItem(remarks = "My subscription")),
            )
        )
        whenever(source.getServerGuidList("aicp-finder-de")).thenReturn(emptyList())
        whenever(source.getServerGuidList("aicp-finder-us")).thenReturn(listOf("us-server"))
        whenever(source.getServerGuidList("user-sub")).thenReturn(emptyList())
        whenever(source.removeSubscription("aicp-finder-de")).thenReturn(true)

        val viewModel = MainViewModel(mock<Application>(), source)
        val removed = viewModel.pruneEmptyAicpFinderGroups(refreshUi = false)

        assertEquals(1, removed)
        verify(source).removeSubscription("aicp-finder-de")
        verify(source, never()).removeSubscription("aicp-finder-us")
        verify(source, never()).removeSubscription("user-sub")
        Unit
    }
}
