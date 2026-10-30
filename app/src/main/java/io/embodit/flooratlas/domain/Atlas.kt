package io.embodit.flooratlas.domain

import org.json.JSONArray
import org.json.JSONObject

/**
 * The map's own coordinates (design `maps[0]`): plate carree, [x] degrees east of 30 W (0..360), [lat] degrees
 * north (84 N .. 80 S). One degree is the same floor length in both directions.
 */
data class MapPt(val x: Float, val lat: Float) {
    operator fun plus(o: MapPt) = MapPt(x + o.x, lat + o.lat)

    operator fun minus(o: MapPt) = MapPt(x - o.x, lat - o.lat)

    operator fun times(k: Float) = MapPt(x * k, lat * k)

    val length: Float
        get() = kotlin.math.hypot(x, lat)

    val lon: Float
        get() = wrapLon(x + MapGeo.LON0)

    val onMap: Boolean
        get() = x in 0f..MapGeo.WIDTH_DEG && lat in MapGeo.LAT_BOTTOM..MapGeo.LAT_TOP

    companion object {
        fun ofLonLat(lon: Float, lat: Float) = MapPt(((lon - MapGeo.LON0) % 360f + 360f) % 360f, lat)

        private fun wrapLon(l: Float): Float {
            var v = l
            while (v >= 180f) v -= 360f
            while (v < -180f) v += 360f
            return v
        }
    }
}

object MapGeo {
    const val LON0 = -30f
    const val WIDTH_DEG = 360f
    const val LAT_TOP = 84f
    const val LAT_BOTTOM = -80f
    const val HEIGHT_DEG = LAT_TOP - LAT_BOTTOM
    val CENTER = MapPt(WIDTH_DEG / 2f, (LAT_TOP + LAT_BOTTOM) / 2f)

    /** Long side of the laid map, metres (design `maps[0].sizeM`). */
    const val MIN_SIZE_M = 2.4f
    const val MAX_SIZE_M = 98f
    const val DEFAULT_SIZE_M = 3.0f
    const val KM_PER_DEG = 111.32f
}

enum class Kind { AREA, LINE, POINT }

/** One place of a question set (design `sets[].targets`), geometry in map degrees. */
class Target(
    val id: String,
    val zh: String,
    val en: String,
    val kind: Kind,
    val rings: List<FloatArray>,
    val lines: List<FloatArray>,
    val point: MapPt?,
    val meshVerts: FloatArray,
    val meshIndices: IntArray,
    val anchor: MapPt,
    val factZh: String,
    val factEn: String,
) {
    fun name(zh: Boolean) = if (zh) this.zh else en

    fun fact(zh: Boolean) = if (zh) factZh else factEn

    /** A point to hang a name label over: the safe landing point. */
    val labelAt: MapPt
        get() = anchor
}

class QuizSet(val id: String, val zh: String, val en: String, val level: Int, val minSizeM: Float, val targets: List<Target>) {
    fun name(zh: Boolean) = if (zh) this.zh else en

    fun target(id: String): Target? = targets.firstOrNull { it.id == id }
}

/** A landform name plate of a featured region (design `terrain.featured.labels`). */
class Landform(val region: String, val zh: String, val en: String, val at: MapPt, val heightM: Int, val factZh: String, val factEn: String) {
    fun name(zh: Boolean) = if (zh) this.zh else en

    fun fact(zh: Boolean) = if (zh) factZh else factEn
}

/** A featured region: map box (x0..x1, lat0..lat1) where levels 5 and 6 exist. */
class Region(val id: String, val zh: String, val en: String, val x0: Float, val x1: Float, val lat0: Float, val lat1: Float) {
    fun contains(p: MapPt) = p.x in x0..x1 && p.lat in lat0..lat1

    fun name(zh: Boolean) = if (zh) this.zh else en
}

class Atlas(val sets: List<QuizSet>, val landforms: List<Landform>, val regions: List<Region>, val extraTiles: Map<Int, Set<Long>>) {
    fun set(id: String): QuizSet? = sets.firstOrNull { it.id == id }

    fun target(id: String): Target? = sets.firstNotNullOfOrNull { it.target(id) }

    fun regionAt(p: MapPt): Region? = regions.firstOrNull { it.contains(p) }

    companion object {
        val EMPTY = Atlas(emptyList(), emptyList(), emptyList(), emptyMap())

        fun tileKey(i: Int, j: Int): Long = (i.toLong() shl 32) or j.toLong()

        /** sets.json + index.json (written by tools/make_maps.py and tools/make_tiles.py). */
        fun parse(setsJson: String, indexJson: String): Atlas {
            val root = JSONObject(setsJson)
            val sets = ArrayList<QuizSet>()
            val ja = root.getJSONArray("sets")
            for (k in 0 until ja.length()) {
                val s = ja.getJSONObject(k)
                val ts = s.getJSONArray("targets")
                val targets = (0 until ts.length()).map { target(ts.getJSONObject(it)) }
                sets += QuizSet(s.getString("id"), s.getString("zh"), s.getString("en"), s.getInt("level"), s.getDouble("minSizeM").toFloat(), targets)
            }
            val lf = root.getJSONArray("landforms")
            val landforms =
                (0 until lf.length()).map {
                    val o = lf.getJSONObject(it)
                    Landform(o.getString("region"), o.getString("zh"), o.getString("en"), MapPt(o.getDouble("x").toFloat(), o.getDouble("lat").toFloat()), o.getInt("h"), o.getString("fz"), o.getString("fe"))
                }
            val names = root.getJSONObject("regionNames")
            val index = JSONObject(indexJson)
            val feat = index.getJSONObject("featured")
            val regions =
                feat.keys().asSequence().map { id ->
                    val b = feat.getJSONArray(id)
                    val n = names.optJSONArray(id)
                    Region(id, n?.optString(0) ?: id, n?.optString(1) ?: id, b.getDouble(0).toFloat(), b.getDouble(1).toFloat(), b.getDouble(2).toFloat(), b.getDouble(3).toFloat())
                }.toList()
            val extra = HashMap<Int, Set<Long>>()
            val ex = index.getJSONObject("extra")
            for (z in ex.keys()) {
                val arr = ex.getJSONArray(z)
                extra[z.toInt()] = (0 until arr.length()).map { arr.getString(it).split('_').let { p -> tileKey(p[0].toInt(), p[1].toInt()) } }.toSet()
            }
            return Atlas(sets, landforms, regions, extra)
        }

        private fun floats(a: JSONArray): FloatArray = FloatArray(a.length()) { a.getDouble(it).toFloat() }

        private fun target(o: JSONObject): Target {
            val kind = when (o.getString("kind")) { "point" -> Kind.POINT; "line" -> Kind.LINE; else -> Kind.AREA }
            val rings = o.optJSONArray("rings")?.let { r -> (0 until r.length()).map { floats(r.getJSONArray(it)) } } ?: emptyList()
            val lines = o.optJSONArray("lines")?.let { r -> (0 until r.length()).map { floats(r.getJSONArray(it)) } } ?: emptyList()
            val point = o.optJSONArray("point")?.let { MapPt(it.getDouble(0).toFloat(), it.getDouble(1).toFloat()) }
            val mesh = o.optJSONObject("mesh")
            val verts = mesh?.let { floats(it.getJSONArray("v")) } ?: FloatArray(0)
            val idx = mesh?.getJSONArray("i")?.let { a -> IntArray(a.length()) { a.getInt(it) } } ?: IntArray(0)
            val an = o.getJSONArray("anchor")
            return Target(o.getString("id"), o.getString("zh"), o.getString("en"), kind, rings, lines, point, verts, idx, MapPt(an.getDouble(0).toFloat(), an.getDouble(1).toFloat()), o.getString("fz"), o.getString("fe"))
        }
    }
}
