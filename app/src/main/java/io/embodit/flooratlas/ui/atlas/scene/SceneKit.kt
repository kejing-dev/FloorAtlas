package io.embodit.flooratlas.ui.atlas.scene

import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.OpacityControllerComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import io.embodit.flooratlas.domain.Quat4
import io.embodit.flooratlas.domain.Vec3
import io.embodit.flooratlas.ui.theme.AtlasColors
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// domain <-> SDK types. The scene works in Stage metres below the calibrated stage root; the map's
// own entities hang below the map root, whose local unit is one map degree (x east, z = -lat,
// y = metres of height, scaled by the relief node).
// ---------------------------------------------------------------------------------------------

internal fun Vec3.sdk(): Vector3 = Vector3(x, y, z)

internal fun Vector3.domain(): Vec3 = Vec3(x, y, z)

internal fun Quat4.sdk(): Quat = Quat(x, y, z, w)

internal fun Quat.domain(): Quat4 = Quat4(x, y, z, w)

internal fun Color.color4(): Color4 = Color4(red, green, blue, alpha)

internal val Entity.transformOrNull: TransformComponent?
    get() = components[TransformComponent::class.java]

internal fun Entity.setLocal(position: Vec3? = null, rotation: Quat4? = null, scale: Vec3? = null) {
    val t = transformOrNull ?: return
    if (position != null) t.setPosition(position.sdk())
    if (rotation != null) t.setQuaternion(rotation.sdk())
    if (scale != null) t.setScaleVector(scale.sdk())
}

internal fun Entity.setMaterial(material: Material) {
    components[ModelComponent::class.java]?.materials?.set(0, material)
}

/** Overall opacity of an entity and its children. */
internal fun Entity.setOpacity(opacity: Float) {
    val existing = components[OpacityControllerComponent::class.java]
    if (existing != null) existing.opacity = opacity.coerceIn(0f, 1f) else components.set(OpacityControllerComponent(opacity.coerceIn(0f, 1f)))
}

/** Metres per dp of an attachment panel at scale 1 (Spatial Ruler feasibility spike). */
internal const val DP_M = 0.0008f

/**
 * Resources shared by entities that come and go. The SDK releases a resource when its last user is
 * destroyed (Spatial Ruler: deleting one pin closed the materials of all pins), so shared ones are made
 * global and closed when the scene goes away.
 */
internal class SharedResources {
    private val owned = ArrayList<Resource>()

    fun <T : Resource> keep(resource: T): T {
        resource.toGlobal()
        owned += resource
        return resource
    }

    fun close() {
        owned.forEach { runCatching { it.close() } }
        owned.clear()
    }
}

/**
 * Scene-lifetime materials, one per look, shared by every entity. Unlit colours are exact only with tone
 * mapping off (Spatial Ruler). Everything on the floor is unlit: the imagery carries its own shading.
 */
internal class AtlasMaterials(private val shared: SharedResources) {
    val white: Material = unlit(AtlasColors.White)
    val yellow: Material = unlit(AtlasColors.Interaction)
    val dim: Material = sheer(AtlasColors.White, 0.28f)
    val green: Material = unlit(AtlasColors.Right)
    val red: Material = unlit(AtlasColors.Wrong)
    val orange: Material = unlit(AtlasColors.Reveal)
    val border: Material = sheer(AtlasColors.White, 0.6f)

    // target outlines (design palette.inWorld 30 / 45 %, lowered on the emulator: a continent-sized sheet at those
    // shares washed the imagery out): explore 22 % white, selected 38 %, outcomes 40 %
    val outline: Material = sheer(AtlasColors.White, 0.22f)
    val outlineSelected: Material = sheer(AtlasColors.White, 0.38f)
    val tries1: Material = sheer(AtlasColors.White, 0.40f)
    val tries2: Material = sheer(AtlasColors.Interaction, 0.40f)
    val tries3: Material = sheer(AtlasColors.Reveal, 0.40f)
    val revealed: Material = sheer(AtlasColors.Wrong, 0.40f)
    val rightFlash: Material = sheer(AtlasColors.Right, 0.70f)
    val revealFlash: Material = sheer(AtlasColors.Reveal, 0.75f)
    val whiteFlash: Material = sheer(AtlasColors.White, 0.85f)

    /** A tile's look: its own texture, unlit, both sides. The caller closes it with the tile. */
    fun tile(texture: TextureResource): Material =
        UnlitMaterial.create(BlendingMode.OPAQUE).apply {
            setBaseColor(Color4(1f, 1f, 1f, 1f))
            setBaseColorTexture(texture)
            runCatching { setApplyToneMapping(false) }
            setCullingMode(MaterialCullingMode.NONE)
        }

    private fun unlit(color: Color): Material =
        shared.keep(
            UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color.color4())
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
            }
        )

    /** See-through: the colour is opaque, the material's opacity carries the share (Spatial Ruler). */
    private fun sheer(color: Color, opacity: Float): Material =
        shared.keep(
            UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColor(color.copy(alpha = 1f).color4())
                setOpacity(opacity)
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
                setDepthWrite(false)
            }
        )
}

/** Programmatic meshes. Creating a mesh costs about one rendered frame: callers spread them over frames. */
internal object MeshFactory {
    private val UP = Vector3(0f, 1f, 0f)
    private const val EDGE = 0.5f / 512f

    /** A unit cube centred on the origin (scaled to any box). */
    fun unitCube(name: String): MeshResource {
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        val faces = listOf(Vector3(1f, 0f, 0f), Vector3(-1f, 0f, 0f), Vector3(0f, 1f, 0f), Vector3(0f, -1f, 0f), Vector3(0f, 0f, 1f), Vector3(0f, 0f, -1f))
        for (n in faces) {
            val nn = Vec3(n.x, n.y, n.z)
            val u = (if (kotlin.math.abs(n.y) > 0.5f) Vec3(1f, 0f, 0f) else Vec3(0f, 1f, 0f)).let { it.rejectFrom(nn).normalized() }
            val v = nn.cross(u)
            val base = positions.size
            for ((a, b) in listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)) {
                val p = (nn + u * a + v * b) * 0.5f
                positions += Vector3(p.x, p.y, p.z)
                normals += n
            }
            indices += listOf(base, base + 1, base + 2, base, base + 2, base + 3)
        }
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = normals)
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(0.5f, 0.5f, 0.5f)), name = name)
    }

    /** Strip x 0..1, y -0.5..0.5 facing +Z, cut into [dashes] (0 = solid): scaled to any length and width. */
    fun unitBand(name: String, dashes: Int = 0, fill: Float = 0.55f): MeshResource {
        val rects = if (dashes <= 0) listOf(0f to 1f) else List(dashes) { i -> val p = 1f / dashes; (i * p + p * (1f - fill) / 2f) to (i * p + p * (1f + fill) / 2f) }
        val positions = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        for ((x0, x1) in rects) {
            val b = positions.size
            positions += listOf(Vector3(x0, -0.5f, 0f), Vector3(x1, -0.5f, 0f), Vector3(x1, 0.5f, 0f), Vector3(x0, 0.5f, 0f))
            indices += listOf(b, b + 1, b + 2, b, b + 2, b + 3)
        }
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = List(positions.size) { Vector3(0f, 0f, 1f) })
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0.5f, 0f, 0f), halfExtent = Vector3(0.5f, 0.5f, 0.002f)), name = name)
    }

    /**
     * A flat ring (local XZ, facing +Y) between radii [r0] and [r1] from angle [a0] to [a1] (radians, 0 = +x,
     * counter-clockwise seen from above, i.e. towards -z).
     */
    fun ring(name: String, r0: Float, r1: Float, a0: Float = 0f, a1: Float = (2 * Math.PI).toFloat(), segments: Int = 48): MeshResource {
        val positions = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        for (k in 0..segments) {
            val a = a0 + (a1 - a0) * k / segments
            val c = cos(a)
            val sn = -sin(a)
            positions += Vector3(c * r0, 0f, sn * r0)
            positions += Vector3(c * r1, 0f, sn * r1)
            if (k > 0) {
                val b = 2 * (k - 1)
                indices += listOf(b, b + 1, b + 3, b, b + 3, b + 2)
            }
        }
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = List(positions.size) { UP })
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(r1, 0.001f, r1)), name = name)
    }

    /**
     * A terrain tile (design `terrain.relief`): [n] x [n] vertices over map degrees, x east, z = -lat, y = height in
     * metres (scaled by the parent). Row 0 is the north edge; the texture's top row is v = 1 (the engine reads v = 0
     * as the bitmap's bottom row: Light Planner / Fire Plan finding).
     */
    fun terrain(name: String, x0: Float, latTop: Float, w: Float, h: Float, heights: ShortArray, n: Int): MeshResource {
        val positions = ArrayList<Vector3>(n * n)
        val uvs = ArrayList<Vector2>(n * n)
        var maxY = 0f
        for (r in 0 until n) for (c in 0 until n) {
            val y = heights[r * n + c].toFloat().coerceAtLeast(0f)
            if (y > maxY) maxY = y
            positions += Vector3(x0 + w * c / (n - 1), y, -(latTop - h * r / (n - 1)))
            // half a texel in from the edges: linear filtering must not wrap to the opposite edge (tile seams)
            uvs += Vector2(EDGE + (1f - 2f * EDGE) * c / (n - 1), 1f - EDGE - (1f - 2f * EDGE) * r / (n - 1))
        }
        val indices = ArrayList<Int>((n - 1) * (n - 1) * 6)
        for (r in 0 until n - 1) for (c in 0 until n - 1) {
            val nw = r * n + c
            val ne = nw + 1
            val sw = nw + n
            val se = sw + 1
            indices += listOf(nw, se, ne, nw, sw, se)
        }
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = List(positions.size) { UP }, uv0 = uvs)
        return MeshResource.createWithMeshModel(
            model = model,
            bounds = BoundingBox(center = Vector3(x0 + w / 2f, maxY / 2f, -(latTop - h / 2f)), halfExtent = Vector3(w / 2f, maxY / 2f + 1f, h / 2f)),
            name = name,
        )
    }

    /** A flat triangle mesh over map degrees ([xy] pairs x, lat; [height] per vertex in metres). */
    fun flat(name: String, xy: FloatArray, indices: IntArray, height: (Float, Float) -> Float): MeshResource? {
        if (indices.isEmpty() || xy.size < 6) return null
        val positions = ArrayList<Vector3>(xy.size / 2)
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        var maxY = 0f
        for (k in 0 until xy.size / 2) {
            val x = xy[2 * k]
            val lat = xy[2 * k + 1]
            val y = height(x, lat)
            positions += Vector3(x, y, -lat)
            minX = minOf(minX, x)
            maxX = maxOf(maxX, x)
            minZ = minOf(minZ, -lat)
            maxZ = maxOf(maxZ, -lat)
            maxY = maxOf(maxY, y)
        }
        val model = MeshModel(positions = positions, triangleIndices = indices.toList(), normals = List(positions.size) { UP })
        return MeshResource.createWithMeshModel(
            model = model,
            bounds = BoundingBox(center = Vector3((minX + maxX) / 2f, maxY / 2f, (minZ + maxZ) / 2f), halfExtent = Vector3((maxX - minX) / 2f + 0.01f, maxY / 2f + 1f, (maxZ - minZ) / 2f + 0.01f)),
            name = name,
        )
    }

    /** Ribbons along polylines ([halfDeg] half width, map degrees) as one triangle list: x/lat pairs + indices. */
    fun ribbons(lines: List<FloatArray>, halfDeg: Float): Pair<FloatArray, IntArray> {
        val xy = ArrayList<Float>()
        val idx = ArrayList<Int>()
        for (l in lines) {
            val n = l.size / 2
            if (n < 2) continue
            val base = xy.size / 2
            for (k in 0 until n) {
                val a = if (k > 0) k - 1 else k
                val b = if (k < n - 1) k + 1 else k
                var dx = l[2 * b] - l[2 * a]
                var dy = l[2 * b + 1] - l[2 * a + 1]
                val len = hypot(dx, dy).coerceAtLeast(1e-6f)
                dx /= len
                dy /= len
                xy += listOf(l[2 * k] - dy * halfDeg, l[2 * k + 1] + dx * halfDeg, l[2 * k] + dy * halfDeg, l[2 * k + 1] - dx * halfDeg)
                if (k > 0) {
                    val p = base + 2 * (k - 1)
                    idx += listOf(p, p + 1, p + 3, p, p + 3, p + 2)
                }
            }
        }
        return xy.toFloatArray() to idx.toIntArray()
    }

    /** A disc of radius [rDeg] around ([cx], [cy]) in map degrees. */
    fun disc(cx: Float, cy: Float, rDeg: Float, segments: Int = 20): Pair<FloatArray, IntArray> {
        val xy = FloatArray(2 * (segments + 1))
        xy[0] = cx
        xy[1] = cy
        val idx = IntArray(segments * 3)
        for (k in 0 until segments) {
            val a = 2 * Math.PI * k / segments
            xy[2 + 2 * k] = cx + (cos(a) * rDeg).toFloat()
            xy[3 + 2 * k] = cy + (sin(a) * rDeg).toFloat()
            idx[3 * k] = 0
            idx[3 * k + 1] = 1 + k
            idx[3 * k + 2] = 1 + (k + 1) % segments
        }
        return xy to idx
    }
}

/**
 * The shared meshes of the scene, made one at a time with a yield after each ([build]): creating a mesh costs
 * about a rendered frame and building all of them in one go blocked the main thread past the focus-event ANR
 * limit on a loaded emulator.
 */
internal class AtlasMeshes(private val shared: SharedResources) {
    lateinit var cube: MeshResource
    lateinit var band: MeshResource
    lateinit var dashes: MeshResource
    lateinit var ring: MeshResource
    lateinit var dot: MeshResource
    lateinit var pin: MeshResource
    lateinit var flash: MeshResource

    /** 12 progress segments outside the ring (design `entities3d.crosshair`), clockwise from the far side. */
    val segments = ArrayList<MeshResource>()

    suspend fun build(yieldNow: suspend () -> Unit) {
        cube = shared.keep(MeshFactory.unitCube("unitCube")); yieldNow()
        band = shared.keep(MeshFactory.unitBand("unitBand")); yieldNow()
        dashes = shared.keep(MeshFactory.unitBand("unitDashes", dashes = 14)); yieldNow()
        ring = shared.keep(MeshFactory.ring("crossRing", 0.09f, 0.12f)); yieldNow()
        dot = shared.keep(MeshFactory.ring("crossDot", 0f, 0.015f, segments = 24)); yieldNow()
        pin = shared.keep(MeshFactory.ring("pin", 0f, 0.022f, segments = 24)); yieldNow()
        flash = shared.keep(MeshFactory.ring("flashRing", 0.85f, 1f)); yieldNow()
        val gap = 0.06f
        val step = (2 * Math.PI / 12).toFloat()
        for (k in 0 until 12) {
            val a1 = (Math.PI / 2).toFloat() - k * step - gap / 2f
            segments += shared.keep(MeshFactory.ring("seg$k", 0.13f, 0.155f, a1 - step + gap, a1, segments = 6))
            yieldNow()
        }
    }
}

/** A box entity on the shared unit cube: placed by centre, size and rotation. */
internal class Box(parent: Entity, mesh: MeshResource, material: Material, name: String) {
    val entity: Entity = ModelEntity(mesh, material)
    private var material: Material = material
    private var on = true

    init {
        runCatching { entity.setName(name) }
        parent.addChild(entity)
    }

    fun place(center: Vec3, size: Vec3, rotation: Quat4 = Quat4.IDENTITY) {
        entity.setLocal(position = center, rotation = rotation, scale = size)
        show(true)
    }

    fun material(m: Material) {
        if (m === material) return
        material = m
        entity.setMaterial(m)
    }

    fun show(visible: Boolean) {
        if (visible != on) {
            entity.enabled = visible
            on = visible
        }
    }
}

/**
 * A straight strip between two points with its face turned along a normal (made square to the strip):
 * the shared unit band scaled to length and width (map border, measuring line).
 */
internal class Strip(parent: Entity, mesh: MeshResource, material: Material, name: String) {
    private val entity: Entity = ModelEntity(mesh, material)
    private var material: Material = material
    private var on = true

    init {
        runCatching { entity.setName(name) }
        parent.addChild(entity)
        hide()
    }

    fun draw(from: Vec3, to: Vec3, width: Float, faceNormal: Vec3) {
        val full = to - from
        val length = full.length
        if (length < 1e-4f) {
            hide()
            return
        }
        val dir = full / length
        var z = faceNormal.rejectFrom(dir)
        if (z.length < 1e-4f) z = Vec3.UP.rejectFrom(dir)
        if (z.length < 1e-4f) z = Vec3.RIGHT.rejectFrom(dir)
        z = z.normalized()
        entity.setLocal(position = from, rotation = Quat4.fromBasis(dir, z.cross(dir).normalized(), z), scale = Vec3(length, width, 1f))
        if (!on) {
            entity.enabled = true
            on = true
        }
    }

    fun material(m: Material) {
        if (m === material) return
        material = m
        entity.setMaterial(m)
    }

    fun hide() {
        if (on) {
            entity.enabled = false
            on = false
        }
    }
}
