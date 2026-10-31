package com.zyke.message.wear

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture

/** A one-glance tile: what is left of today's budget. Reads the copy the listener service keeps; no network, no waiting. */
class TodayTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { completer ->
            val snapshot = SnapshotStore.latest(this)
            val layout = if (snapshot == null) {
                column(text("Open Zyke Message", 16f, 0xFFFFFFFF), text("on your phone", 16f, 0xFFFFFFFF))
            } else {
                val left = snapshot.todayLeft
                val colour = when { snapshot.masked || left == null || Format.isStale(snapshot) -> 0xFFB0B6BF; left < 0 -> 0xFFFF8A80; else -> 0xFF7BD88F }
                column(
                    text(if (Format.isStale(snapshot)) "Yesterday's left" else "Today left", 14f, 0xFFB0B6BF),
                    text(Format.rupees(left, snapshot.masked), 34f, colour)
                )
            }
            completer.set(
                TileBuilders.Tile.Builder()
                    .setResourcesVersion("1")
                    .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
                    .build()
            )
            "tile"
        }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(ResourceBuilders.Resources.Builder().setVersion("1").build())
            "resources"
        }

    private fun text(value: String, size: Float, colour: Long): LayoutElementBuilders.LayoutElement =
        Text.Builder().setText(value).setFontStyle(FontStyle.Builder().setSize(sp(size)).setColor(argb(colour.toInt())).build()).build()

    private fun column(vararg items: LayoutElementBuilders.LayoutElement): LayoutElementBuilders.LayoutElement =
        Column.Builder().apply { items.forEach { addContent(it) } }.build()
}
