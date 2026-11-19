package com.whale.hand.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.whale.hand.agent.AgentConfig

class QuickTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val on = !AgentConfig.floatingInput
        AgentConfig.floatingInput = on
        OverlayManager.setBarEnabled(on)
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        tile.state = if (AgentConfig.floatingInput) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "WhaleHand 悬浮条"
        tile.updateTile()
    }
}
