package io.embodit.flooratlas.platform

import android.content.Context
import io.embodit.flooratlas.platform.sense.PlaneTracker
import io.embodit.flooratlas.platform.tracking.ControllerStick
import io.embodit.flooratlas.platform.tracking.HeadTracker

/**
 * Process-wide platform services. The scene starts and stops the trackers (every start has a stop:
 * `AtlasScene.attach` / `pause` / `detach`); the ViewModel only sees the stores.
 */
object AtlasGraph {
    val planes = PlaneTracker()
    val head = HeadTracker()
    val stick = ControllerStick()

    @Volatile private var prefs: Prefs? = null
    @Volatile private var tiles: TileStore? = null

    fun prefs(context: Context): Prefs = prefs ?: synchronized(this) { prefs ?: Prefs(context.applicationContext).also { prefs = it } }

    fun tiles(context: Context): TileStore = tiles ?: synchronized(this) { tiles ?: TileStore(context.applicationContext).also { tiles = it } }
}
