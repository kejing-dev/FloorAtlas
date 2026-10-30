package io.embodit.flooratlas.domain

import java.io.File
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AtlasDomainTest {
    private val atlas: Atlas by lazy {
        val dir = listOf(File("src/main/assets/atlas"), File("app/src/main/assets/atlas")).first { it.exists() }
        Atlas.parse(File(dir, "sets.json").readText(), File(dir, "index.json").readText())
    }

    @Test
    fun data_has_six_sets_and_67_places() {
        assertEquals(6, atlas.sets.size)
        assertEquals(67, atlas.sets.sumOf { it.targets.size })
        assertEquals(listOf(1, 2, 3, 4, 5, 6), atlas.sets.map { it.level })
        assertTrue(atlas.landforms.size >= 18)
        assertEquals(3, atlas.regions.size)
        // every landform plate stands inside its featured region (index.json box order: x0, x1, lat0, lat1)
        for (lf in atlas.landforms) assertTrue(lf.en, atlas.regions.first { it.id == lf.region }.contains(lf.at))
    }

    @Test
    fun every_anchor_judges_as_its_own_target_at_the_set_minimum() {
        for (set in atlas.sets) {
            val s = set.minSizeM / MapGeo.WIDTH_DEG
            for (t in set.targets) {
                val p = Judge.pick(set.targets, t.anchor, s)
                assertEquals("${set.id}/${t.id}", t.id, p.on?.id)
            }
        }
    }

    @Test
    fun map_pose_round_trips_and_lays_north_ahead() {
        val m = MapPose.lay(P2(0.3f, -0.2f), P2(1f, 0f), 3f)
        val p = MapPt(123.4f, -12.5f)
        val back = m.toMap(m.toFloor(p))
        assertEquals(p.x, back.x, 1e-3f)
        assertEquals(p.lat, back.lat, 1e-3f)
        // north points along the view direction (+x here)
        assertEquals(1f, m.north.x, 1e-4f)
        assertEquals(0f, m.north.z, 1e-4f)
        assertEquals(3f, m.sizeM, 1e-4f)
    }

    @Test
    fun rotate_and_zoom_keep_the_crosshair_point() {
        val m = MapPose.lay(P2(0f, 0f), P2(0f, -1f), 3f)
        val c = P2(0f, -0.45f)
        val under = m.toMap(c)
        val r = m.rotateAbout(c, 0.7f).zoomTo(c, 12f)
        val after = r.toMap(c)
        assertEquals(under.x, after.x, 1e-3f)
        assertEquals(under.lat, after.lat, 1e-3f)
        assertEquals(12f, r.sizeM, 1e-3f)
        assertEquals(MapGeo.MAX_SIZE_M, r.zoomTo(c, 500f).sizeM, 1e-3f)
    }

    @Test
    fun bring_puts_a_place_under_the_crosshair() {
        val m = MapPose.lay(P2(0f, 0f), P2(0f, -1f), 3f)
        val c = P2(0.1f, -0.45f)
        val beijing = atlas.target("beijing")!!
        val b = m.bring(beijing.anchor, c)
        val at = b.toMap(c)
        assertEquals(beijing.anchor.x, at.x, 1e-3f)
    }

    @Test
    fun beijing_to_cairo_is_about_7500_km() {
        val km = Judge.greatCircleKm(atlas.target("beijing")!!.point!!, atlas.target("cairo")!!.point!!)
        assertTrue("km=$km", abs(km - 7_550) < 7_550 * 0.03)
    }

    @Test
    fun snap_moves_into_a_near_area_only() {
        val set = atlas.set("world-continents")!!
        val s = 3f / 360f
        val africa = set.target("africa")!!
        // a point 3 cm west of Africa's west coast at the equator-ish
        val edge = Judge.closestPoint(africa, MapPt(10f, 10f))!!
        val out = MapPt(edge.x - 0.03f / s, edge.lat)
        val to = Judge.snapTarget(set.targets, out, s)
        if (to != null) assertEquals(0f, Judge.distanceDeg(africa, to), 1e-3f)
        // inside something: no snap
        assertNull(Judge.snapTarget(set.targets, africa.anchor, s))
    }

    @Test
    fun quiz_three_tries_then_reveal_then_next() {
        val set = atlas.set("world-cities")!!
        var q = Quiz.start(set, Random(1))
        val cur = q.current!!
        val wrong = set.targets.first { it.id != cur }.id
        var r: AnswerResult
        q.answer(wrong).also { q = it.first; r = it.second }
        assertEquals(AnswerResult.WRONG, r)
        q.answer(wrong).also { q = it.first; r = it.second }
        assertEquals(AnswerResult.WRONG, r)
        q.answer(wrong).also { q = it.first; r = it.second }
        assertEquals(AnswerResult.REVEAL, r)
        assertTrue(q.revealing)
        q.answer(wrong).also { q = it.first; r = it.second }
        assertEquals(AnswerResult.IGNORED, r)
        q.answer(cur).also { q = it.first; r = it.second }
        assertEquals(AnswerResult.RIGHT, r)
        assertEquals(Outcome.REVEALED, q.outcomes[cur])
        assertEquals(1, q.index)
        assertEquals(listOf(cur), q.misses)
    }

    @Test
    fun best_compares_percent_then_time() {
        assertTrue(Best(90, 100_000).beats(Best(80, 50_000)))
        assertTrue(Best(90, 40_000).beats(Best(90, 50_000)))
        assertFalse(Best(90, 60_000).beats(Best(90, 50_000)))
        assertTrue(Best(0, 1).beats(null))
    }

    @Test
    fun dwell_needs_arming_and_stillness() {
        val d = DwellJudge()
        val s = 3f / 360f
        val p0 = MapPt(100f, 10f)
        d.reset(p0, "a")
        // standing on the same place it started on: not armed, nothing fires
        assertNull(d.step(0, p0, s, "a", moving = false))
        assertNull(d.step(2_000, p0, s, "a", moving = false))
        // moved onto another target: armed; fires after 0.8 s still
        val p1 = MapPt(130f, 10f)
        assertNull(d.step(2_100, p1, s, "b", moving = true))
        assertNull(d.step(2_200, p1, s, "b", moving = false))
        assertNull(d.step(2_900, p1, s, "b", moving = false))
        assertEquals("b", d.step(3_000, p1, s, "b", moving = false))
        // wrong at b: not judged again until it leaves b
        d.wrongAt("b", p1)
        assertNull(d.step(5_000, p1, s, "b", moving = false))
        assertNull(d.step(6_000, p1, s, "b", moving = false))
    }

    @Test
    fun tile_plan_has_no_holes_and_stays_in_budget() {
        val loaded = HashSet<TileId>()
        fun exists(t: TileId) = t.z <= 4 && t.i < (2 shl t.z) && t.j < (1 shl t.z)
        TileId.ROOTS.forEach { loaded += it }
        val viewer = MapPt(100f, 30f)
        val s = 40f / 360f
        repeat(8) {
            val plan = TileSelect.plan(viewer, s, ::exists, loaded::contains)
            assertTrue(plan.draw.size <= TileSelect.MAX_VISIBLE)
            // the drawn tiles cover the map exactly once: areas add up
            val area = plan.draw.sumOf { (it.widthDeg * it.heightDeg).toDouble() }
            assertEquals(360.0 * 164.0, area, 1.0)
            loaded += plan.want
        }
        val plan = TileSelect.plan(viewer, s, ::exists, loaded::contains)
        assertTrue(plan.draw.any { it.z == 4 })
    }

    @Test
    fun target_levels_follow_the_size_table() {
        assertEquals(0, TileSelect.targetLevel(2.4f))
        assertEquals(1, TileSelect.targetLevel(3f))
        assertEquals(4, TileSelect.targetLevel(30f))
        assertEquals(6, TileSelect.targetLevel(98f))
    }

    @Test
    fun scale_bar_is_one_two_five_and_30_to_60_cm() {
        for (size in listOf(3f, 12f, 50f, 98f)) {
            val (km, m) = Scale.bar(size / 360f)
            assertTrue("$size -> $km", km in setOf(10, 20, 50, 100, 200, 500, 1000, 2000, 5000))
            assertTrue("$size -> $m", m in 0.25f..0.65f)
        }
        assertEquals(8_700, Scale.stepKmRounded(3f / 360f))
    }

    @Test
    fun relief_caps_lift() {
        val v = Relief.scale(60f, 8800f, quiz = false)
        assertTrue(8800f * v <= 0.1001f)
        val q = Relief.scale(60f, 8800f, quiz = true)
        assertTrue(8800f * q <= 0.0301f)
        assertNotNull(v)
    }
}
