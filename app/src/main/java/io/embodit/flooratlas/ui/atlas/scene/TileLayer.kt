package io.embodit.flooratlas.ui.atlas.scene

import android.graphics.Bitmap
import android.os.SystemClock
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.TextureResource
import io.embodit.flooratlas.domain.MapPt
import io.embodit.flooratlas.domain.TileId
import io.embodit.flooratlas.domain.TilePlan
import io.embodit.flooratlas.domain.TileSelect
import io.embodit.flooratlas.platform.AtlasLog
import io.embodit.flooratlas.platform.TileStore

/**
 * The terrain tiles under the relief node (design `terrain`): picks what to draw every frame
 * ([TileSelect.plan], <= 30 tiles), asks the store for what is missing (<= 4 decoding), uploads at most one
 * decoded tile per frame (texture + 33 x 33 mesh + material), keeps <= 48 resident (least recently drawn goes
 * first; the two roots stay). A tile is replaced by its children only when all four are resident: no holes.
 */
internal class TileLayer(private val parent: Entity, private val materials: AtlasMaterials, private val store: TileStore) {
    private class View(val id: TileId, val entity: Entity, val mesh: MeshResource, val texture: TextureResource, val material: Material, val bitmap: Bitmap, val maxH: Int) {
        var on = true
        var lastDrawn = 0L
    }

    private val views = HashMap<TileId, View>()
    private val requested = HashSet<TileId>()
    private var plan = TilePlan(emptyList(), emptyList())
    var uploads = 0
        private set

    /** Highest terrain among the drawn tiles (metres): the relief exaggeration keeps it under its cap. */
    var highestDrawn = 0
        private set

    val drawn: List<TileId>
        get() = plan.draw

    val resident: Int
        get() = views.size

    fun has(id: TileId) = views.containsKey(id)

    fun step(viewer: MapPt, s: Float) {
        val now = SystemClock.elapsedRealtime()
        // one upload per frame (texture + mesh cost about a frame each)
        store.ready.poll()?.let { d ->
            requested.remove(d.id)
            if (views.containsKey(d.id)) d.bitmap.recycle() else upload(d.id, d.bitmap, d.heights, d.maxHeight)
        }
        plan = TileSelect.plan(viewer, s, store::exists, ::has)
        var inFlight = requested.size
        for (w in plan.want) {
            if (inFlight >= MAX_DECODING) break
            if (w in requested || views.containsKey(w)) continue
            requested += w
            store.request(w)
            inFlight++
        }
        val drawSet = plan.draw.toHashSet()
        var high = 0
        for ((id, v) in views) {
            val on = id in drawSet
            if (on) {
                v.lastDrawn = now
                if (v.maxH > high) high = v.maxH
            }
            if (on != v.on) {
                v.entity.enabled = on
                v.on = on
            }
        }
        highestDrawn = high
        if (views.size > MAX_RESIDENT) evict(drawSet)
    }

    private fun upload(id: TileId, bitmap: Bitmap, heights: ShortArray, maxH: Int) {
        try {
            val texture = TextureResource.create(bitmap)
            val material = materials.tile(texture)
            val mesh = MeshFactory.terrain("tile${id.z}_${id.i}_${id.j}", id.x0, id.latTop, id.widthDeg, id.heightDeg, heights, GRID)
            val e = ModelEntity(mesh, material)
            runCatching { e.setName("tile:${id.path}") }
            e.enabled = false
            parent.addChild(e)
            views[id] = View(id, e, mesh, texture, material, bitmap, maxH).also { it.on = false }
            uploads++
            if (uploads <= 12 || uploads % 50 == 0) AtlasLog.i("TILE up ${id.path} n=${views.size} uploads=$uploads")
        } catch (t: Throwable) {
            AtlasLog.e("TILE upload failed ${id.path}: $t", t)
        }
    }

    private fun evict(drawSet: Set<TileId>) {
        val order = views.values.filter { it.id !in drawSet && it.id.z > 0 }.sortedBy { it.lastDrawn }
        for (v in order) {
            if (views.size <= MAX_RESIDENT) break
            release(v)
            views.remove(v.id)
        }
    }

    private fun release(v: View) {
        runCatching { v.entity.destroy(true) }
        runCatching { v.material.close() }
        runCatching { v.texture.close() }
        runCatching { v.mesh.close() }
        runCatching { v.bitmap.recycle() }
    }

    fun clear() {
        views.values.forEach(::release)
        views.clear()
        requested.clear()
        store.drain()
    }

    /** Debug `tiles`: one line. */
    fun line(): String = "TILES draw=${plan.draw.size} levels=${plan.draw.groupingBy { it.z }.eachCount().toSortedMap()} want=${plan.want.size} resident=${views.size} decoding=${requested.size} uploads=$uploads highest=$highestDrawn"

    companion object {
        const val GRID = 33
        const val MAX_RESIDENT = 48
        const val MAX_DECODING = 4
    }
}
