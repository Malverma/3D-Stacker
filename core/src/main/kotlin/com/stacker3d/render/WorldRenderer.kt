package com.stacker3d.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.graphics.g3d.Model
import com.badlogic.gdx.graphics.g3d.ModelBatch
import com.badlogic.gdx.graphics.g3d.ModelInstance
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.Disposable
import com.stacker3d.logic.Board
import com.stacker3d.logic.Break
import com.stacker3d.logic.BreakCause
import com.stacker3d.logic.DropPreview
import com.stacker3d.logic.GameLogic
import com.stacker3d.logic.LandingResult
import com.stacker3d.logic.Piece
import com.stacker3d.logic.PieceType
import com.stacker3d.logic.Vec3i
import com.stacker3d.shop.Catalog
import com.stacker3d.shop.SkyTheme
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Draws the board, tower, falling piece, ghost, effects and debris, and owns the climbing camera (spec §7).
 * Board cell (x, z) and layer y map to a cube centered at (x - 1, y + 0.5, z - 1).
 */
class WorldRenderer(private val models: Models, theme: SkyTheme = Catalog.classicSky) : Disposable {
    val cam = PerspectiveCamera(FOV, 1f, 1f)
    private val batch = ModelBatch()
    private val env = lightEnvironment()
    private val boardInstance = ModelInstance(models.board)
    private val sky = Sky(theme)

    var theme: SkyTheme
        get() = sky.theme
        set(value) { sky.theme = value }

    private class Settle(val instance: ModelInstance, val x: Float, var y: Float, var targetY: Float, val z: Float, val group: Int) {
        var velocity = 0f
    }

    private class Debris(val instance: ModelInstance, val pos: Vector3, val vel: Vector3, val axis: Vector3, val spin: Float, val scale: Float = 1f) {
        var angle = 0f
        var life = 0f
    }

    /**
     * A bomb or X block (or cursed Mystery Block) still falling toward its impact; the effect fires when it arrives.
     * Cracked cubes that crumble on the same landing wait for it, since the bomb changes the column first.
     */
    private class Pending(val result: LandingResult, val instance: ModelInstance, var y: Float) {
        var velocity = 0f
    }

    private class Flash(val instance: ModelInstance, val pos: Vector3, val color: Color) {
        var life = 0f
    }

    private class Goo {
        var age = 0f
        /** 0..1: how flattened and spread out it is, easing toward the weight of the cubes above. */
        var press = 0f
        var drip = -1f
    }

    /** Jiggly-skin squash on a tower cube: a damped oscillation that starts after [delay] seconds. */
    private class Wobble(var amp: Float, var delay: Float) {
        var age = 0f
        val t get() = age - delay
        fun envelope(damp: Float) = if (t <= 0f) amp else amp * exp(-damp * t)
    }

    /** Tower cubes per board column, indexed by layer. */
    private val columns = Array(Board.SPAN * Board.SPAN) { ArrayList<ModelInstance>() }

    /** Which cells the board covers on screen; grows with [LandingResult.grown] in No Fall mode. */
    private val footprint = Board()
    private val settles = ArrayList<Settle>()
    private val debris = ArrayList<Debris>()
    private val flashes = ArrayList<Flash>()
    private val wobbles = HashMap<ModelInstance, Wobble>()
    private val wobblingColumns = BooleanArray(Board.SPAN * Board.SPAN)
    private var pending: Pending? = null
    private val pools = HashMap<Model, ArrayList<ModelInstance>>()
    private val impactedGroups = HashSet<Int>()
    private var groupCounter = 0
    private var spawnCount = 0
    /** Melting skins: how far along each tower cube is in growing drips and spreading under the weight above. */
    private val goo = HashMap<ModelInstance, Goo>()

    private val visual = Vector3()
    /** The falling piece as of the last [update], for touch hit tests. */
    private var current: Piece? = null
    private val hull = FloatArray(16)
    private val tmp = Vector3()
    private val tmp2 = Vector3()
    private var time = 0f

    /** Called once per landing, when its first cube hits the tower. */
    var onImpact: (() -> Unit)? = null
    /** Called when a bomb actually goes off on screen. */
    var onExplode: (() -> Unit)? = null
    /** Called when cracked cubes crumble on screen, with how many broke. */
    var onCrumble: ((Int) -> Unit)? = null
    /** Called when Glass Blocks shatter on screen, with how many broke. */
    var onShatter: ((Int) -> Unit)? = null
    /** Called when Dynamite blows up a layer on screen, with how many cubes it destroyed. */
    var onDynamite: ((Int) -> Unit)? = null

    var towerHeight = 0
    var gameOver = false
    /** Degrees per second of slow orbit (used by the menu backdrop). */
    var orbitSpeed = 0f
    /** Raises the tower on screen by this many world units (the menu needs room for its buttons). */
    var lift = 0f

    private var yaw = BASE_YAW
    /** Yaw the view settles on during play: BASE_YAW plus whole quarter turns. */
    private var targetYaw = BASE_YAW
    /** Live offset while the player twists the board with two fingers. */
    private var twist = 0f
    private var dist = 20f
    private var aspect = 1f
    /** World x/z the camera looks at: the footprint's center (eased toward [frameX]/[frameZ]). */
    private var lookX = 0f
    private var lookZ = 0f
    private var frameX = 0f
    private var frameZ = 0f
    private var currentDist = 20f
    private var viewHeight = 14f
    private var camY = Float.NaN
    private var shakeAmount = 0f
    private var screenW = 1f
    private var screenH = 1f

    /** Smoothed tower height that drives the sky colors. */
    val displayHeight: Float get() = max(0f, camY - towerOffset())

    fun resize(width: Int, height: Int) {
        screenW = width.toFloat()
        screenH = height.toFloat()
        aspect = screenW / screenH
        frame()
        if (camY.isNaN()) {
            currentDist = dist
            camY = towerHeight + towerOffset()
        }
        cam.viewportWidth = screenW
        cam.viewportHeight = screenH
        cam.near = 0.5f
        cam.far = 200f
        updateCamera(0f)
    }

    /**
     * Fits the camera to the board: centered on the footprint, and pulled back as far as a grown board needs
     * (the extra is how much farther its corners reach than the 3×3's).
     */
    private fun frame() {
        val halfH = tan(FOV / 2f * MathUtils.degreesToRadians)
        val hx = (footprint.maxX - footprint.minX + 1) / 2f
        val hz = (footprint.maxZ - footprint.minZ + 1) / 2f
        val extra = max(0f, sqrt(hx * hx + hz * hz) - CLASSIC_REACH)
        dist = max((VISIBLE_HALF_WIDTH + extra) / (halfH * aspect), (MIN_VISIBLE_HALF_HEIGHT + extra) / halfH)
        viewHeight = 2f * dist * halfH
        frameX = (footprint.minX + footprint.maxX) / 2f - 1f
        frameZ = (footprint.minZ + footprint.maxZ) / 2f - 1f
    }

    /** Top layer of the tallest column on screen. */
    private fun towerTop() = footprint.cells.maxOf { footprint.baseAt(it) + columns[it].size }

    /** Places the camera target so the tower top sits about a third of the way up the screen. */
    private fun towerOffset() = viewHeight / 6f / cos(PITCH * MathUtils.degreesToRadians)

    fun reset() {
        columns.forEach { it.clear() }
        settles.clear()
        debris.clear()
        flashes.clear()
        wobbles.clear()
        goo.clear()
        wobblingColumns.fill(false)
        pending = null
        impactedGroups.clear()
        towerHeight = 0
        gameOver = false
        footprint.clear()
        frame()
        lookX = frameX
        lookZ = frameZ
        yaw = BASE_YAW
        targetYaw = BASE_YAW
        twist = 0f
        currentDist = dist
        camY = towerOffset() - lift
    }

    /** Appends a cube on top of column (x, z); [fromY] makes it drop in from higher up. */
    fun addCube(x: Int, z: Int, type: PieceType, fromY: Float? = null, group: Int = -1, look: Int = MathUtils.random.nextInt(Int.MAX_VALUE)) {
        val column = columns[Board.index(x, z)]
        val y = footprint.base(x, z) + column.size
        val instance = models.instance(type, look)
        if (models.skin.melts) {
            instance.setDrips(DRIP_START)
            goo[instance] = Goo()
        }
        val start = fromY ?: y.toFloat()
        instance.transform.setToTranslation(x - 1f, start + 0.5f, z - 1f)
        column.add(instance)
        if (start > y + 0.001f) settles += Settle(instance, x - 1f, start, y.toFloat(), z - 1f, group)
        else if (group >= 0) jiggle(x, y, z, LAND_KICK)
    }

    /**
     * Seed for a falling cube's skin variety (bubble layout, shade), from the piece and the cube's offset in it,
     * so it keeps the same look once it lands.
     */
    private fun look(dx: Int, dy: Int, dz: Int) = spawnCount * 7919 + (dx + 4) * 81 + (dy + 4) * 9 + dz + 4

    fun onSpawn(piece: Piece) {
        spawnCount++
        visual.set(piece.x.toFloat(), piece.y.toFloat(), piece.z.toFloat())
    }

    fun onLanded(result: LandingResult) {
        pending?.let { resolvePending(it) }
        val offsetY = max(0f, visual.y - result.origin.y)
        if (result.grown.isNotEmpty()) {
            for (g in result.grown) footprint.grow(g.x, g.z, g.y)
            frame()
        }
        // Stack first: a bomb's blast indices count the cubes this same piece just placed.
        val group = ++groupCounter
        val o = result.origin
        for (p in result.placed) addCube(p.to.x, p.to.z, p.type, p.from.y + offsetY, group, look(p.from.x - o.x, p.from.y - o.y, p.from.z - o.z))
        if (result.placed.isNotEmpty() && result.placed.none { it.from.y + offsetY > it.to.y + 0.001f }) {
            impactedGroups += group
            onImpact?.invoke()
        }
        for (c in result.lost) fallOff(models.instance(c.type), c.at.x, c.at.y + offsetY, c.at.z)
        val cell = result.effectCell
        if (cell != null && (result.exploded || result.xHit)) {
            pending = Pending(result, ModelInstance(effectModel(result)), max(result.impact!!.y.toFloat(), cell.y + offsetY))
        } else if (result.dodged && cell != null) {
            fallOff(ModelInstance(models.xBlock), cell.x, cell.y + offsetY, cell.z)
        }
        if (pending == null) crumble(result.breaks)
    }

    /**
     * Gap-continue: drops filler cubes into their columns from just above the tower, each column a beat after the
     * last and each cube a little after the one below, so the holes visibly fill up. [cells] are bottom-up per column.
     */
    fun fillGaps(cells: List<Vec3i>, type: PieceType) {
        val start = towerTop() + FILL_DROP
        var col = -1
        var colIndex = -1
        var k = 0
        var group = -1
        for (c in cells) {
            val ci = Board.index(c.x, c.z)
            if (ci != col) {
                col = ci
                colIndex++
                k = 0
                group = ++groupCounter
            }
            addCube(c.x, c.z, type, start + colIndex * FILL_COLUMN_STAGGER + k * FILL_CUBE_STAGGER, group)
            k++
        }
    }

    /** The cube that falls to an impact: a bomb, the X cube, or a cursed Mystery cube (which shatters into its own shards). */
    private fun effectModel(result: LandingResult): Model = when {
        result.exploded -> models.bomb
        result.type.kind == PieceType.Kind.X -> models.xBlock
        else -> models.mysteryBlock
    }

    /**
     * Breaks tower cubes in the order the logic did (shattered glass, dynamite layers, crumbled cracked cubes),
     * dropping everything above each one by a layer.
     */
    private fun crumble(breaks: List<Break>) {
        if (breaks.isEmpty()) return
        for (br in breaks) {
            val c = br.at
            val column = columns[Board.index(c.x, c.z)]
            val b = footprint.base(c.x, c.z)
            if (c.y - b !in column.indices) continue
            val broken = column.removeAt(c.y - b)
            goo.remove(broken)
            settles.removeAll { it.instance === broken }
            val center = Vector3(c.x - 1f, c.y + 0.5f, c.z - 1f)
            when (br.cause) {
                BreakCause.CRUMBLE -> {
                    jiggle(c.x, c.y, c.z, CRUMBLE_KICK)
                    repeat(7) {
                        val dir = tmp.set(MathUtils.random(-1f, 1f), MathUtils.random(-0.2f, 0.8f), MathUtils.random(-1f, 1f)).nor()
                        addDebris(ModelInstance(models.crackedBlock), center.cpy(), Vector3(dir.x * 3.5f, dir.y * 4f, dir.z * 3.5f), 0.36f)
                    }
                }
                BreakCause.SHATTER -> {
                    jiggle(c.x, c.y, c.z, CRUMBLE_KICK)
                    repeat(10) {
                        val dir = tmp.set(MathUtils.random(-1f, 1f), MathUtils.random(0f, 0.9f), MathUtils.random(-1f, 1f)).nor()
                        addDebris(ModelInstance(models.glassBlock), center.cpy(), Vector3(dir.x * 4.5f, dir.y * 5f, dir.z * 4.5f), 0.24f)
                    }
                    flashes += Flash(ModelInstance(models.ghost), center, Color(0.85f, 0.95f, 1f, 1f))
                }
                BreakCause.DYNAMITE -> {
                    // The whole layer is flung outward from the board's center.
                    jiggle(c.x, c.y, c.z, BOMB_KICK)
                    val dir = tmp.set(center.x - lookX, 0.5f, center.z - lookZ)
                        .add(MathUtils.random(-0.4f, 0.4f), MathUtils.random(0f, 0.5f), MathUtils.random(-0.4f, 0.4f)).nor()
                    addDebris(broken, center.cpy(), Vector3(dir.x * 9f, 5f + dir.y * 6f, dir.z * 9f), 1f)
                    flashes += Flash(ModelInstance(models.ghost), center, Color(1f, 0.55f, 0.1f, 1f))
                }
            }
            for (i in c.y - b until column.size) {
                val above = column[i]
                val layer = b + i
                val settling = settles.firstOrNull { it.instance === above }
                if (settling != null) settling.targetY = layer.toFloat()
                else settles += Settle(above, c.x - 1f, layer + 1f, layer.toFloat(), c.z - 1f, -1)
            }
        }
        val blasted = breaks.count { it.cause == BreakCause.DYNAMITE }
        shake(if (blasted > 0) 1f else 0.5f)
        breaks.count { it.cause == BreakCause.CRUMBLE }.let { if (it > 0) onCrumble?.invoke(it) }
        breaks.count { it.cause == BreakCause.SHATTER }.let { if (it > 0) onShatter?.invoke(it) }
        if (blasted > 0) onDynamite?.invoke(blasted)
    }

    /** A cube that missed the board tumbles away outward. */
    private fun fallOff(instance: ModelInstance, x: Int, y: Float, z: Int) {
        val pos = Vector3(x - 1f, y + 0.5f, z - 1f)
        val out = tmp.set(pos.x, 0f, pos.z).nor()
        addDebris(instance, pos, Vector3(out.x * 3f, 2.5f, out.z * 3f), 1f)
    }

    private fun resolvePending(p: Pending) {
        pending = null
        val result = p.result
        val impact = result.impact!!
        val center = Vector3(impact.x - 1f, impact.y + 0.5f, impact.z - 1f)
        if (result.exploded) {
            // Remove blasted cubes (highest first so indices stay valid) and fling them outward.
            for ((pos, _) in result.cleared.sortedByDescending { it.first.y }) {
                val column = columns[Board.index(pos.x, pos.z)]
                val b = footprint.base(pos.x, pos.z)
                if (pos.y - b !in column.indices) continue
                val inst = column.removeAt(pos.y - b)
                goo.remove(inst)
                settles.removeAll { it.instance === inst }
                val from = Vector3(pos.x - 1f, pos.y + 0.5f, pos.z - 1f)
                val dir = tmp.set(from).sub(center).add(MathUtils.random(-0.3f, 0.3f), 0.6f, MathUtils.random(-0.3f, 0.3f)).nor()
                addDebris(inst, from, Vector3(dir.x * 9f, 6f + dir.y * 6f, dir.z * 9f), 1f)
            }
            // Survivors above the blast drop into the hole.
            for (s in result.shifted) {
                val column = columns[Board.index(s.to.x, s.to.z)]
                val inst = column.getOrNull(s.to.y - footprint.base(s.to.x, s.to.z)) ?: continue
                settles.removeAll { it.instance === inst }
                settles += Settle(inst, s.to.x - 1f, s.from.y.toFloat(), s.to.y.toFloat(), s.to.z - 1f, -1)
            }
            flashes += Flash(ModelInstance(models.ghost), center, Color(1f, 0.6f, 0.15f, 1f))
            jiggle(impact.x, impact.y, impact.z, BOMB_KICK)
            shake(1f)
            onExplode?.invoke()
        } else {
            // The X cube shatters into small shards.
            val shard = effectModel(result)
            repeat(8) {
                val dir = tmp.set(MathUtils.random(-1f, 1f), MathUtils.random(0.6f, 1.4f), MathUtils.random(-1f, 1f)).nor()
                addDebris(ModelInstance(shard), center.cpy(), Vector3(dir.x * 5f, dir.y * 7f, dir.z * 5f), 0.32f)
            }
            flashes += Flash(ModelInstance(models.ghost), center, Color(1f, 0.15f, 0.2f, 1f))
            onImpact?.invoke()
        }
        crumble(result.breaks)
    }

    /**
     * Starts tower cubes wobbling around cell ([x], [y], [z]) when the skin is jiggly. Strength falls off with
     * grid distance and the wobble reaches farther cubes a little later, so it ripples out and down the tower.
     */
    private fun jiggle(x: Int, y: Int, z: Int, strength: Float) {
        if (!models.skin.jiggly) return
        for (ci in columns.indices) {
            val column = columns[ci]
            if (column.isEmpty()) continue
            val reach = WOBBLE_REACH - abs(Board.xOf(ci) - x) - abs(Board.zOf(ci) - z)
            if (reach < 0) continue
            val b = footprint.baseAt(ci)
            for (cy in max(0, y - reach - b)..min(column.size - 1, y + reach - b)) {
                val d = WOBBLE_REACH - reach + abs(b + cy - y)
                if (d > WOBBLE_REACH) continue
                val add = strength * WOBBLE_FALLOFF.pow(d)
                val delay = d * WOBBLE_DELAY
                val w = wobbles[column[cy]]
                if (w == null) wobbles[column[cy]] = Wobble(min(add, WOBBLE_MAX), delay)
                else {
                    // Kick a cube that's still moving: build on what's left of its wobble, restarting the swing.
                    w.amp = min(w.envelope(models.skin.wobbleDamp) + add, WOBBLE_MAX)
                    w.age = 0f
                    w.delay = delay
                }
            }
        }
    }

    /**
     * Poses tower cubes for jiggly and melting skins, each anchored at its base. Wobbling cubes squash and the
     * cubes above ride the squash so the column stays stacked; columns that wobbled last frame are redrawn once more
     * at rest so nothing is left offset. Melting cubes also stretch to close the gaps around them (so they stick
     * together), slowly spread under the weight of the cubes above, and grow their drips.
     */
    private fun poseTower(dt: Float) {
        if (wobbles.isEmpty() && goo.isEmpty() && wobblingColumns.none { it }) return
        for (w in wobbles.values) w.age += dt
        val skin = models.skin
        wobbles.values.removeAll { it.t > WOBBLE_FADE / skin.wobbleDamp }
        val ease = 1f - exp(-dt / PRESS_TIME)
        for (ci in columns.indices) {
            val column = columns[ci]
            val wobbling = column.any { it in wobbles }
            if (!wobbling && !wobblingColumns[ci] && goo.isEmpty()) continue
            wobblingColumns[ci] = wobbling
            var sink = 0f
            for (cy in column.indices) {
                val inst = column[cy]
                if (settles.any { it.instance === inst }) { sink = 0f; continue }
                val w = wobbles[inst]
                val squash = if (w != null && w.t > 0f) w.envelope(skin.wobbleDamp) * MathUtils.sin(skin.wobbleFreq * w.t) else 0f
                var fill = 1f
                var spread = 1f
                val g = goo[inst]
                if (g != null) {
                    g.age += dt
                    val above = column.size - 1 - cy
                    g.press += (min(1f, PRESS_BASE + above * PRESS_PER_CUBE) - g.press) * ease
                    fill = 1f + (STICK_FILL - 1f) * min(1f, g.age / STICK_TIME)
                    spread = 1f + PRESS_SPREAD * g.press
                    val t = min(1f, g.age / MELT_TIME)
                    val drip = (DRIP_START + (1f - DRIP_START) * (1f - (1f - t) * (1f - t))) * (1f + DRIP_PRESS * g.press)
                    if (abs(drip - g.drip) > 0.003f) {
                        g.drip = drip
                        inst.setDrips(drip)
                    }
                }
                val sy = fill * (1f - squash)
                val sxz = spread * (1f + squash * 0.5f)
                inst.transform.setToTranslation(Board.xOf(ci) - 1f, footprint.baseAt(ci) + cy + 0.5f + sink - CUBE_SIZE / 2f * (1f - sy), Board.zOf(ci) - 1f)
                    .scale(sxz, sy, sxz)
                sink -= CUBE_SIZE * fill * squash
            }
        }
    }

    private fun addDebris(instance: ModelInstance, pos: Vector3, vel: Vector3, scale: Float) {
        val axis = Vector3(vel.z, 0f, -vel.x).let { if (it.isZero(0.01f)) it.set(1f, 0f, 0f) else it.nor() }
        debris += Debris(instance, pos, vel, axis, MathUtils.random(240f, 520f), scale)
    }

    fun shake(amount: Float) {
        shakeAmount = max(shakeAmount, amount)
    }

    /**
     * Converts a screen drag (pixels, x right, y up) into board cells along X and Z by inverting how one
     * cell step on each axis projects onto the screen at the falling piece. Works at any view angle.
     */
    fun dragToCells(sx: Float, sy: Float, out: FloatArray) {
        val o = cam.project(tmp.set(visual.x - 1f, visual.y + 0.5f, visual.z - 1f), 0f, 0f, screenW, screenH)
        val ox = o.x; val oy = o.y
        val a = cam.project(tmp.set(visual.x, visual.y + 0.5f, visual.z - 1f), 0f, 0f, screenW, screenH)
        val ax = a.x - ox; val ay = a.y - oy
        val b = cam.project(tmp.set(visual.x - 1f, visual.y + 0.5f, visual.z), 0f, 0f, screenW, screenH)
        val bx = b.x - ox; val by = b.y - oy
        val det = ax * by - ay * bx
        if (abs(det) < 1e-3f) { out[0] = 0f; out[1] = 0f; return }
        out[0] = (sx * by - sy * bx) / det
        out[1] = (ax * sy - ay * sx) / det
    }

    /**
     * True if a touch at ([sx], [sy]) — screen pixels, y down — lands on the board or the tower standing on
     * it (but not on the falling piece), i.e. a drag there should spin the board.
     */
    fun touchesBoard(sx: Float, sy: Float): Boolean {
        val y = screenH - sy
        val piece = current
        if (piece != null) {
            // The piece wins where it overlaps the tower: test its screen bounds, padded a little.
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (c in piece.cells) for (corner in 0..7) {
                val p = cam.project(
                    tmp.set(visual.x + c.x - 1.5f + (corner and 1), visual.y + c.y + (corner shr 1 and 1), visual.z + c.z - 1.5f + (corner shr 2 and 1)),
                    0f, 0f, screenW, screenH,
                )
                minX = min(minX, p.x); maxX = max(maxX, p.x); minY = min(minY, p.y); maxY = max(maxY, p.y)
            }
            val pad = min(screenW, screenH) * 0.03f
            if (sx in minX - pad..maxX + pad && y in minY - pad..maxY + pad) return false
        }
        // Convex hull of the board slab and the tower's bounding box, projected to the screen.
        val top = max(0.5f, towerTop().toFloat())
        for (corner in 0..7) {
            val p = cam.project(
                tmp.set(
                    if (corner and 1 == 0) footprint.minX - 1.8f else footprint.maxX - 0.2f,
                    if (corner and 2 == 0) -0.8f else top,
                    if (corner and 4 == 0) footprint.minZ - 1.8f else footprint.maxZ - 0.2f,
                ),
                0f, 0f, screenW, screenH,
            )
            hull[corner * 2] = p.x; hull[corner * 2 + 1] = p.y
        }
        return insideHull(hull, sx, y)
    }

    /** Point-in-convex-hull of 8 points: inside if it isn't strictly outside any edge between two of them. */
    private fun insideHull(pts: FloatArray, x: Float, y: Float): Boolean {
        for (i in 0..7) for (j in 0..7) {
            if (i == j) continue
            val ax = pts[i * 2]; val ay = pts[i * 2 + 1]
            val ex = pts[j * 2] - ax; val ey = pts[j * 2 + 1] - ay
            // An edge of the hull has every other point on one side; skip pairs that aren't hull edges.
            var allLeft = true
            for (k in 0..7) if (k != i && k != j && ex * (pts[k * 2 + 1] - ay) - ey * (pts[k * 2] - ax) < -1e-3f) { allLeft = false; break }
            if (allLeft && ex * (y - ay) - ey * (x - ax) < 0f) return false
        }
        return true
    }

    /** Quarter turns the view is (or is settling) away from the default angle. */
    private val quarterTurns get() = MathUtils.round((targetYaw - BASE_YAW) / 90f).mod(4)

    /** Rotates a move given in default-view board axes so it matches what the player sees now. */
    fun viewToBoard(dx: Int, dz: Int): IntArray = when (quarterTurns) {
        1 -> intArrayOf(dz, -dx)
        2 -> intArrayOf(-dx, -dz)
        3 -> intArrayOf(-dz, dx)
        else -> intArrayOf(dx, dz)
    }

    /** Turns the board view by [steps] quarter turns (positive orbits the camera to the right). */
    fun rotateView(steps: Int) {
        targetYaw += steps * 90f
    }

    /** Follows a two-finger twist live; [degrees] is the total since the twist started. */
    fun twistView(degrees: Float) {
        twist = degrees
    }

    /** Ends a twist, snapping to the nearest quarter turn (a short flick still commits one turn). */
    fun endTwist() {
        val turns = when {
            abs(twist) < TWIST_COMMIT -> 0
            else -> sign(twist).toInt() * max(1, MathUtils.round(abs(twist) / 90f))
        }
        targetYaw += turns * 90f
        twist = 0f
    }

    fun update(dt: Float, piece: Piece?) {
        time += dt
        current = piece
        if (piece != null) {
            val k = 1f - exp(-18f * dt)
            visual.x += (piece.x - visual.x) * k
            visual.y += (piece.y - visual.y) * k
            visual.z += (piece.z - visual.z) * k
        }

        pending?.let { p ->
            val impact = p.result.impact!!
            p.velocity += GRAVITY * dt
            p.y -= p.velocity * dt
            if (p.y <= impact.y) resolvePending(p)
            else p.instance.transform.setToTranslation(impact.x - 1f, p.y + 0.5f, impact.z - 1f)
        }

        val it = settles.iterator()
        while (it.hasNext()) {
            val s = it.next()
            s.velocity += GRAVITY * dt
            s.y -= s.velocity * dt
            if (s.y <= s.targetY) {
                s.y = s.targetY
                it.remove()
                if (s.group >= 0 && impactedGroups.add(s.group)) onImpact?.invoke()
                jiggle(MathUtils.round(s.x) + 1, MathUtils.round(s.y), MathUtils.round(s.z) + 1, if (s.group >= 0) LAND_KICK else SHIFT_KICK)
            }
            s.instance.transform.setToTranslation(s.x, s.y + 0.5f, s.z)
        }
        poseTower(dt)

        val dIt = debris.iterator()
        while (dIt.hasNext()) {
            val d = dIt.next()
            d.life += dt
            d.vel.y -= GRAVITY * 0.6f * dt
            d.pos.mulAdd(d.vel, dt)
            d.angle += d.spin * dt
            d.instance.transform.setToTranslation(d.pos).rotate(d.axis, d.angle).scale(d.scale, d.scale, d.scale)
            if (d.life > 3f) dIt.remove()
        }

        val fIt = flashes.iterator()
        while (fIt.hasNext()) {
            val f = fIt.next()
            f.life += dt
            val t = f.life / FLASH_TIME
            if (t >= 1f) { fIt.remove(); continue }
            val s = 1f + 3.2f * t
            f.instance.transform.setToTranslation(f.pos).scale(s, s, s)
            f.instance.diffuse().set(f.color)
            f.instance.blending().opacity = 0.75f * (1f - t)
        }

        updateCamera(dt)
    }

    private fun updateCamera(dt: Float) {
        val halfH = tan(FOV / 2f * MathUtils.degreesToRadians)
        val goalY: Float
        val goalDist: Float
        if (gameOver) {
            yaw += 14f * dt
            goalY = towerHeight / 2f + 0.5f
            goalDist = max(dist, (towerHeight + 5f) / (2f * halfH) * 1.1f)
        } else if (orbitSpeed != 0f) {
            yaw += orbitSpeed * dt
            goalY = towerHeight + towerOffset() - lift
            goalDist = dist
        } else {
            val goalYaw = targetYaw + twist
            yaw = if (twist != 0f || dt == 0f) goalYaw else yaw + (goalYaw - yaw) * (1f - exp(-12f * dt))
            goalY = towerHeight + towerOffset() - lift
            goalDist = dist
        }
        val k = if (dt == 0f) 1f else 1f - exp(-2.5f * dt)
        camY += (goalY - camY) * k
        lookX += (frameX - lookX) * k
        lookZ += (frameZ - lookZ) * k
        currentDist += (goalDist - currentDist) * (if (dt == 0f) 1f else 1f - exp(-1.5f * dt))
        shakeAmount = max(0f, shakeAmount - dt * 1.5f)

        val p = PITCH * MathUtils.degreesToRadians
        val y = yaw * MathUtils.degreesToRadians
        cam.position.set(
            lookX + MathUtils.sin(y) * cos(p) * currentDist,
            camY + MathUtils.sin(p) * currentDist,
            lookZ + MathUtils.cos(y) * cos(p) * currentDist,
        )
        cam.up.set(Vector3.Y)
        cam.lookAt(lookX, camY, lookZ)
        if (shakeAmount > 0f) {
            val s = shakeAmount * shakeAmount * 0.5f
            cam.position.add(MathUtils.random(-s, s), MathUtils.random(-s, s), MathUtils.random(-s, s))
        }
        cam.update()
    }

    /** [heights] (optional) turns on the red warning pillars over columns that trail the tallest one. */
    fun render(piece: Piece?, ghost: DropPreview?, dt: Float, heights: IntArray? = null) {
        sky.render(screenW, screenH, displayHeight, dt)
        Gdx.gl.glClear(GL20.GL_DEPTH_BUFFER_BIT)
        pools.values.forEach { it.forEach { inst -> inst.userData = null } }

        batch.begin(cam)
        val lo = max(0, floor(camY - viewHeight * 1.2f).toInt())
        if (footprint.isClassic) {
            if (camY - viewHeight < 1f) {
                models.paintBoard(boardInstance)
                batch.render(boardInstance, env)
            }
        } else {
            // A grown board is drawn tile by tile, checkered like the 3×3. Cells grown off a cube hanging above
            // ground level get no tile: the cube just stays up there.
            if (camY - viewHeight < 1f) for (i in footprint.cells) {
                if (footprint.baseAt(i) != 0) continue
                val x = Board.xOf(i)
                val z = Board.zOf(i)
                val inst = pooled(models.boardTile)
                models.paintTile(inst, (x + z).mod(2) == 1)
                inst.transform.setToTranslation(x - 1f, 0f, z - 1f)
                batch.render(inst, env)
                // The colored rim follows the outline of the ground-level floor: an edge on every open side,
                // and a corner piece where two open sides meet with nothing diagonal.
                for (side in 0..3) {
                    val (dx, dz) = SIDES[side]
                    if (groundTile(x + dx, z + dz)) continue
                    val edge = pooled(models.boardEdge)
                    models.paintRim(edge)
                    edge.transform.setToTranslation(x - 1f, 0f, z - 1f).rotate(Vector3.Y, side * 90f)
                    batch.render(edge, env)
                    val (cx, cz) = SIDES[(side + 1) % 4]
                    if (groundTile(x + cx, z + cz) || groundTile(x + dx + cx, z + dz + cz)) continue
                    val corner = pooled(models.boardCorner)
                    models.paintRim(corner)
                    corner.transform.setToTranslation(x - 1f, 0f, z - 1f).rotate(Vector3.Y, side * 90f)
                    batch.render(corner, env)
                }
            }
        }
        for (ci in footprint.cells) {
            val column = columns[ci]
            for (i in max(0, lo - footprint.baseAt(ci)) until column.size) batch.render(column[i], env)
        }
        for (d in debris) batch.render(d.instance, env)
        pending?.let { batch.render(it.instance, env) }

        if (piece != null) {
            for ((i, c) in piece.cells.withIndex()) {
                val type = piece.cellType(i)
                val seed = look(c.x, c.y, c.z)
                val inst = pooled(models.modelFor(type, seed))
                if (models.skin.melts) inst.setDrips(DRIP_START)
                if (type.kind == PieceType.Kind.NORMAL) inst.diffuse().set(models.colorOf(type, seed))
                inst.transform.setToTranslation(visual.x + c.x - 1f, visual.y + c.y + 0.5f, visual.z + c.z - 1f)
                batch.render(inst, env)
            }
        }
        if (ghost != null) {
            for ((i, c) in ghost.cells.withIndex()) {
                // Fade the shadow out as the falling piece reaches it; overlapping surfaces would flicker.
                val fade = ghostFade(piece, c.x, c.y, c.z)
                if (fade <= 0f) continue
                val inst = pooled(models.ghost)
                // The X cube's shadow is always red: it must not land on the board.
                if (ghost.onBoard[i] && piece?.type?.isX(i) != true) {
                    inst.diffuse().set(1f, 1f, 1f, 1f); inst.blending().opacity = 0.38f * fade
                } else {
                    inst.diffuse().set(1f, 0.2f, 0.25f, 1f); inst.blending().opacity = 0.55f * fade
                }
                inst.transform.setToTranslation(c.x - 1f, c.y + 0.5f, c.z - 1f)
                batch.render(inst, env)
            }
        }
        if (heights != null) renderWarnings(heights)
        for (f in flashes) batch.render(f.instance, env)
        batch.end()
    }

    /**
     * 1 when no falling cube is near this ghost cell, dropping to 0 once one overlaps it.
     * Distance is per-axis (cubes are boxes), measured from the smoothed on-screen position.
     */
    private fun ghostFade(piece: Piece?, x: Int, y: Int, z: Int): Float {
        if (piece == null) return 1f
        var nearest = Float.MAX_VALUE
        for (c in piece.cells) {
            val d = max(abs(visual.x + c.x - x), max(abs(visual.y + c.y - y), abs(visual.z + c.z - z)))
            nearest = min(nearest, d)
        }
        return MathUtils.clamp((nearest - CUBE_SIZE) / GHOST_FADE_DISTANCE, 0f, 1f)
    }

    private fun renderWarnings(heights: IntArray) {
        val top = footprint.cells.maxOf { heights[it] }
        val pulse = 0.22f + 0.12f * MathUtils.sin(time * 6f)
        for (i in footprint.cells) {
            val gap = top - heights[i]
            if (gap < GameLogic.GAP_WARNING) continue
            val inst = pooled(models.pillar)
            val danger = (gap - GameLogic.GAP_WARNING + 1f) / (GameLogic.GAP_LIMIT - GameLogic.GAP_WARNING + 1f)
            inst.blending().opacity = pulse + 0.2f * danger
            inst.transform.setToTranslation(Board.xOf(i) - 1f, heights[i] + gap / 2f, Board.zOf(i) - 1f)
                .scale(0.9f, gap.toFloat(), 0.9f)
            batch.render(inst, env)
        }
    }

    /** Reuses instances across frames: each one is marked as taken until the next render call. */
    /** Cell ([x], [z]) is on the board with its floor at ground level (so it has a drawn tile). */
    private fun groundTile(x: Int, z: Int) = footprint.inside(x, z) && footprint.base(x, z) == 0

    private fun pooled(model: Model): ModelInstance {
        val pool = pools.getOrPut(model) { ArrayList() }
        val free = pool.firstOrNull { it.userData == null } ?: ModelInstance(model).also { pool += it }
        free.userData = true
        return free
    }

    override fun dispose() {
        batch.dispose()
        sky.dispose()
    }

    companion object {
        const val FOV = 38f
        const val PITCH = 30f
        const val BASE_YAW = 45f
        /**
         * Neighbor offsets in the order a quarter turn about +Y maps the rim models' +Z side onto them
         * (+Z, +X, -Z, -X), so side n is drawn rotated n × 90°.
         */
        private val SIDES = arrayOf(0 to 1, 1 to 0, 0 to -1, -1 to 0)

        /** Degrees a two-finger twist must reach before release turns the board. */
        const val TWIST_COMMIT = 20f
        /** World units that must fit across the screen around the board center (board + overhang room). */
        const val VISIBLE_HALF_WIDTH = 4.1f
        const val MIN_VISIBLE_HALF_HEIGHT = 6.5f
        /** Half-diagonal of the 3×3 board: how far its corners reach from the center at any view angle. */
        val CLASSIC_REACH = 1.5f * sqrt(2f)
        const val GRAVITY = 40f
        const val FLASH_TIME = 0.4f
        /** How far (in cells) above its shadow a falling cube starts fading that shadow out. */
        const val GHOST_FADE_DISTANCE = 0.6f

        // Jiggly skins: squash amount (fraction of cube height) for each kind of hit, and how the wobble spreads.
        const val LAND_KICK = 0.16f
        const val SHIFT_KICK = 0.08f
        const val CRUMBLE_KICK = 0.14f
        const val BOMB_KICK = 0.22f
        const val WOBBLE_MAX = 0.24f
        /** Strength kept per grid step away from the hit. */
        const val WOBBLE_FALLOFF = 0.55f
        /** Grid steps (|dx| + |dy| + |dz|) a hit reaches. */
        const val WOBBLE_REACH = 4
        /** Seconds the ripple takes per grid step. */
        const val WOBBLE_DELAY = 0.05f
        /** A wobble stops once it has decayed to about 1% (e^-4.6). */
        const val WOBBLE_FADE = 4.6f
        /** Melting skins: drip length while falling, and seconds to reach full length after landing. */
        const val DRIP_START = 0.2f
        const val MELT_TIME = 2.5f
        /** Drips grow this much longer on a fully pressed cube. */
        const val DRIP_PRESS = 0.45f
        /** Gap-continue filler cubes start this far above the tower top, staggered per column and per cube. */
        const val FILL_DROP = 2f
        const val FILL_COLUMN_STAGGER = 0.9f
        const val FILL_CUBE_STAGGER = 1.1f
        /** Melting skins stretch to this height (closing the gap to the cube above) over [STICK_TIME] seconds. */
        const val STICK_FILL = 1f / CUBE_SIZE + 0.01f
        const val STICK_TIME = 0.35f
        /** Press a melting cube eases toward: a little on its own, plus this much per cube resting on it. */
        const val PRESS_BASE = 0.25f
        const val PRESS_PER_CUBE = 0.25f
        /** Seconds for the press to settle most of the way, so the tower visibly sinks into itself. */
        const val PRESS_TIME = 2.5f
        /** How much wider a fully pressed cube gets; enough to merge into its neighbors. */
        const val PRESS_SPREAD = 0.1f
    }
}
