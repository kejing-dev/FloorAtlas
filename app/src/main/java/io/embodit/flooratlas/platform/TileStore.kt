package io.embodit.flooratlas.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.embodit.flooratlas.domain.Atlas
import io.embodit.flooratlas.domain.MapGeo
import io.embodit.flooratlas.domain.MapPt
import io.embodit.flooratlas.domain.TileId
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.math.floor

/** A decoded tile, ready to upload on the main thread: image + 33 x 33 heights (row 0 = north edge). */
class DecodedTile(val id: TileId, val bitmap: Bitmap, val heights: ShortArray) {
    val maxHeight: Int = heights.maxOrNull()?.toInt() ?: 0
}

/**
 * The offline map assets (assets/atlas: sets.json, index.json, elev.h16, tiles/z/i_j.jpg + .h16; tools/make_*.py).
 * Tiles decode on two background threads (design `terrain.budget`) into [ready]; the scene takes at most one per
 * frame. The global height grid answers heights anywhere (draping, crosshair lift).
 */
class TileStore(private val context: Context) {
    private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "atlas-tiles").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
    private val inFlight = ConcurrentHashMap.newKeySet<TileId>()
    val ready = ConcurrentLinkedQueue<DecodedTile>()

    @Volatile private var atlas: Atlas? = null
    @Volatile private var grid: ShortArray? = null
    private var gw = 0
    private var gh = 0

    /** Parses the question data (call off the main thread). */
    fun atlas(): Atlas =
        atlas ?: synchronized(this) {
            atlas ?: Atlas.parse(text("atlas/sets.json"), text("atlas/index.json")).also {
                atlas = it
                loadGrid()
            }
        }

    private fun text(path: String) = context.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun loadGrid() {
        val b = context.assets.open("atlas/elev.h16").use { it.readBytes() }
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        gw = bb.int
        gh = bb.int
        val g = ShortArray(gw * gh)
        bb.asShortBuffer().get(g)
        grid = g
    }

    /** Height in metres above sea level (sea floor 0) at map point [p], bilinear on the 0.35 deg grid. */
    fun heightAt(p: MapPt): Float {
        val g = grid ?: return 0f
        val fx = (p.x / MapGeo.WIDTH_DEG * (gw - 1)).coerceIn(0f, gw - 1f)
        val fy = ((MapGeo.LAT_TOP - p.lat) / MapGeo.HEIGHT_DEG * (gh - 1)).coerceIn(0f, gh - 1f)
        val x0 = floor(fx).toInt().coerceAtMost(gw - 2)
        val y0 = floor(fy).toInt().coerceAtMost(gh - 2)
        val tx = fx - x0
        val ty = fy - y0
        fun h(x: Int, y: Int) = g[y * gw + x].toFloat()
        val top = h(x0, y0) * (1 - tx) + h(x0 + 1, y0) * tx
        val bot = h(x0, y0 + 1) * (1 - tx) + h(x0 + 1, y0 + 1) * tx
        return top * (1 - ty) + bot * ty
    }

    fun exists(id: TileId): Boolean {
        if (id.z < 0 || id.i < 0 || id.j < 0 || id.i >= (2 shl id.z) || id.j >= (1 shl id.z)) return false
        if (id.z <= TileId.GLOBAL_MAX) return true
        return atlas?.extraTiles?.get(id.z)?.contains(Atlas.tileKey(id.i, id.j)) == true
    }

    /** Starts decoding [id] unless it is already on its way. */
    fun request(id: TileId) {
        if (!inFlight.add(id)) return
        pool.execute {
            try {
                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                val bmp = context.assets.open("atlas/tiles/${id.path}.jpg").use { BitmapFactory.decodeStream(it, null, opts) }
                val hb = context.assets.open("atlas/tiles/${id.path}.h16").use { it.readBytes() }
                val hs = ShortArray(hb.size / 2)
                ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(hs)
                if (bmp != null) ready.add(DecodedTile(id, bmp, hs))
            } catch (t: Throwable) {
                AtlasLog.w("TILE decode failed ${id.path}: $t")
            } finally {
                inFlight.remove(id)
            }
        }
    }

    fun pending(id: TileId) = id in inFlight

    /** Drops decoded tiles nobody took (pause / detach). */
    fun drain() {
        while (true) ready.poll()?.bitmap?.recycle() ?: break
    }
}
