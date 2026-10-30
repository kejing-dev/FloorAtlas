package io.embodit.flooratlas.domain

import java.util.PriorityQueue
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max

/** Terrain tile (design `terrain.tiling`): level [z] has 2^(z+1) x 2^z tiles. */
data class TileId(val z: Int, val i: Int, val j: Int) {
    val widthDeg: Float
        get() = 180f / (1 shl z)

    val heightDeg: Float
        get() = MapGeo.HEIGHT_DEG / (1 shl z)

    val x0: Float
        get() = i * widthDeg

    val latTop: Float
        get() = MapGeo.LAT_TOP - j * heightDeg

    val children: List<TileId>
        get() = listOf(TileId(z + 1, 2 * i, 2 * j), TileId(z + 1, 2 * i + 1, 2 * j), TileId(z + 1, 2 * i, 2 * j + 1), TileId(z + 1, 2 * i + 1, 2 * j + 1))

    val parent: TileId?
        get() = if (z == 0) null else TileId(z - 1, i / 2, j / 2)

    val path: String
        get() = "$z/${i}_$j"

    /** Map-degree distance from [p] to this tile's box (0 inside). */
    fun distanceDeg(p: MapPt): Float {
        val dx = max(0f, max(x0 - p.x, p.x - (x0 + widthDeg)))
        val dy = max(0f, max((latTop - heightDeg) - p.lat, p.lat - latTop))
        return hypot(dx, dy)
    }

    override fun toString() = path

    companion object {
        val ROOTS = listOf(TileId(0, 0, 0), TileId(0, 1, 0))
        const val GLOBAL_MAX = 4
        const val MAX_LEVEL = 6
    }
}

/** What to draw and what to load next (design `terrain.budget`: <= 30 visible, no holes). */
data class TilePlan(val draw: List<TileId>, val want: List<TileId>)

object TileSelect {
    const val MAX_VISIBLE = 30

    /** The level whose pixel is about 1.5 mm on the floor at map size [sizeM] (design `terrain.tiling`). */
    fun targetLevel(sizeM: Float): Int = floor(ln(max(sizeM, 1e-3f) / 1.5f) / ln(2f)).toInt().coerceIn(0, TileId.MAX_LEVEL)

    /** One level coarser per ring away from the viewer: < 3 m, < 7 m, < 15 m, beyond. */
    fun ringDrop(distM: Float): Int = when {
        distM < 3f -> 0
        distM < 7f -> 1
        distM < 15f -> 2
        else -> 3
    }

    /**
     * Refines from the two roots, nearest first. A tile is split only when all four children exist and are
     * loaded (otherwise the missing ones are wanted and the tile itself stays: no holes).
     */
    fun plan(viewer: MapPt, s: Float, exists: (TileId) -> Boolean, loaded: (TileId) -> Boolean, maxVisible: Int = MAX_VISIBLE): TilePlan {
        val target = targetLevel(s * MapGeo.WIDTH_DEG)
        val draw = ArrayList<TileId>()
        val want = LinkedHashSet<TileId>()
        val queue = PriorityQueue<Pair<Float, TileId>>(compareBy { it.first })
        for (r in TileId.ROOTS) {
            if (!loaded(r)) want += r
            queue += r.distanceDeg(viewer) * s to r
        }
        var count = queue.size
        while (queue.isNotEmpty()) {
            val (d, t) = queue.poll()!!
            val desired = target - ringDrop(d)
            val kids = if (t.z < desired) t.children.filter(exists) else emptyList()
            if (kids.size == 4 && count + 3 <= maxVisible) {
                val missing = kids.filterNot(loaded)
                if (missing.isEmpty()) {
                    count += 3
                    for (k in kids) queue += k.distanceDeg(viewer) * s to k
                    continue
                }
                want += missing
            }
            draw += t
        }
        return TilePlan(draw, want.sortedBy { it.distanceDeg(viewer) })
    }
}
