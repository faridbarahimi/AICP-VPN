package com.v2ray.ang.service

import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.AppLocaleManager

/** Single-action tiles; Android requires the user to add each tile from the QS editor. */
abstract class AicpControlTileService : TileService() {
    protected abstract val controlAction: String
    protected abstract val tileLabelRes: Int

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase?.let(AppLocaleManager::localizedContext))
    }

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        AicpControlActionReceiver.perform(this, controlAction)
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        tile.icon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stat_aicp)
        tile.label = getString(tileLabelRes)
        val paused = AicpControlActionReceiver.isPaused(this)
        val running = CoreServiceManager.isRunning()
        tile.state = when (controlAction) {
            AicpControlActionReceiver.ACTION_PAUSE -> if (running && !paused) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            AicpControlActionReceiver.ACTION_PLAY -> if (paused || !running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            else -> if (running || paused) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }
}

class AicpPauseTileService : AicpControlTileService() {
    override val controlAction = AicpControlActionReceiver.ACTION_PAUSE
    override val tileLabelRes = R.string.aicp_vpn_pause
}

class AicpPlayTileService : AicpControlTileService() {
    override val controlAction = AicpControlActionReceiver.ACTION_PLAY
    override val tileLabelRes = R.string.aicp_vpn_play
}

class AicpStopTileService : AicpControlTileService() {
    override val controlAction = AicpControlActionReceiver.ACTION_STOP
    override val tileLabelRes = R.string.aicp_vpn_stop
}
