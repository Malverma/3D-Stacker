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
import com.stacker3d.logic.DropPreview
import com.stacker3d.logic.GameLogic
import com.stacker3d.logic.LandingResult
import com.stacker3d.logic.Piece
import com.stacker3d.logic.PieceType
import com.stacker3d.shop.Catalog
import com.stacker3d.shop.SkyTheme
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sign
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

    private class Settle(val instance: ModelInstance, val x: Float, var y: Float, val targetY: Float, val z: Float, val group: Int) {
        var velocity = 0f
    }

    private class Debris(val instance: ModelInstance, val pos: Vector3, val vel: Vector3, val axis: Vector3, val spin: Float, val scale: Float = 1f) {
        var angle = 0f
        var life = 0f
    }

    /** A bomb or X block still falling toward its impact; the effect fires when it arrives. */
    private class Pending(val result: LandingResult, val instance: ModelInstance, var y: Float) {
        var velocity = 0f
    }

    private class Flash(val instance: ModelInstance, val pos: Vector3, val color: Color) {
        var life = 0f
    }

    /** Tower cubes per board column, indexed by layer. */
    private val columns = Array(Board.SIZE * Board.SIZE) { ArrayList<ModelInstance>() }
    private val settles = ArrayList<Settle>()
    private val debris = ArrayList<Debris>()
    private val flashes = ArrayList<Flash>()
    private var pending: Pending? = null
    private val pools = HashMap<Model, ArrayList<ModelInstance>>()
    private val impactedGroups = HashSet<Int>()
    private var groupCounter = 0

    private val visual = Vector3()
    private val tmp = Vector3()
    private val tmp2 = Vector3()
    private var time = 0f

    /** Called once per landing, when its first cube hits the tower. */
    var onImpact: (() -> Unit)? = null
    /** Called when a bomb actually goes off on screen. */
    var onExplode: (() -> Unit)? = null

    var towerHeight = 0
    var gameOver = false
    /** Degrees per second of slow orbit (used by the menu backdrop). */
    var orbitSpeed = 0f
    /** Raises the tower on screen by this many world units (the menu needs room for its buttons). */
    var lift = 0f

    private var yaw = BASE_YAW
    private var dist = 20f
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
        val aspect = screenW / screenH
        val halfH = tan(FOV / 2f * MathUtils.degreesToRadians)
        dist = max(VISIBLE_HALF_WIDTH / (halfH * aspect), MIN_VISIBLE_HALF_HEIGHT / halfH)
        viewHeight = 2f * dist * halfH
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

    /** Places the camera target so the tower top sits about a third of the way up the screen. */
    private fun towerOffset() = viewHeight / 6f / cos(PITCH * MathUtils.degreesToRadians)

    fun reset() {
        columns.forEach { it.clear() }
        settles.clear()
        debris.clear()
        flashes.clear()
        pending = null
        impactedGroups.clear()
        towerHeight = 0
        gameOver = false
        yaw = BASE_YAW
        currentDist = dist
        camY = towerOffset() - lift
    }

    /** Appends a cube on top of column (x, z); [fromY] makes it drop in from higher up. */
    fun addCube(x: Int, z: Int, type: PieceType, fromY: Float? = null, group: Int = -1) {
        val column = columns[x + z * Board.SIZE]
        val y = column.size
        val instance = models.instance(type)
        val start = fromY ?: y.toFloat()
        instance.transform.setToTranslation(x - 1f, start + 0.5f, z - 1f)
        column.add(instance)
        if (start > y + 0.001f) settles += Settle(instance, x - 1f, start, y.toFloat(), z - 1f, group)
    }

    fun onSpawn(piece: Piece) {
        visual.set(piece.x.toFloat(), piece.y.toFloat(), piece.z.toFloat())
    }

    fun onLanded(result: LandingResult) {
        pending?.let { resolvePending(it) }
        val offsetY = max(0f, visual.y - result.origin.y)
        when {
            result.type.kind == PieceType.Kind.BOMB || result.xHit -> {
                val impact = result.impact!!
                val instance = models.instance(result.type)
                pending = Pending(result, instance, max(impact.y.toFloat(), result.origin.y + offsetY))
            }
            else -> {
                val group = ++groupCounter
                for (p in result.placed) addCube(p.to.x, p.to.z, result.type, p.from.y + offsetY, group)
                if (result.placed.isNotEmpty() && result.placed.none { it.from.y + offsetY > it.to.y + 0.001f }) {
                    impactedGroups += group
                    onImpact?.invoke()
                }
                for (c in result.lost) {
                    val pos = Vector3(c.x - 1f, c.y + offsetY + 0.5f, c.z - 1f)
                    val out = tmp.set(pos.x, 0f, pos.z).nor()
                    addDebris(models.instance(result.type), pos, Vector3(out.x * 3f, 2.5f, out.z * 3f), 1f)
                }
            }
        }
    }

    private fun resolvePending(p: Pending) {
        pending = null
        val result = p.result
        val impact = result.impact!!
        val center = Vector3(impact.x - 1f, impact.y + 0.5f, impact.z - 1f)
        if (result.type.kind == PieceType.Kind.BOMB) {
            // Remove blasted cubes (highest first so indices stay valid) and fling them outward.
            for ((pos, _) in result.cleared.sortedByDescending { it.first.y }) {
                val column = columns[pos.x + pos.z * Board.SIZE]
                if (pos.y >= column.size) continue
                val inst = column.removeAt(pos.y)
                settles.removeAll { it.instance === inst }
                val from = Vector3(pos.x - 1f, pos.y + 0.5f, pos.z - 1f)
                val dir = tmp.set(from).sub(center).add(MathUtils.random(-0.3f, 0.3f), 0.6f, MathUtils.random(-0.3f, 0.3f)).nor()
                addDebris(inst, from, Vector3(dir.x * 9f, 6f + dir.y * 6f, dir.z * 9f), 1f)
            }
            // Survivors above the blast drop into the hole.
            for (s in result.shifted) {
                val column = columns[s.to.x + s.to.z * Board.SIZE]
                val inst = column.getOrNull(s.to.y) ?: continue
                settles.removeAll { it.instance === inst }
                settles += Settle(inst, s.to.x - 1f, s.from.y.toFloat(), s.to.y.toFloat(), s.to.z - 1f, -1)
            }
            flashes += Flash(ModelInstance(models.ghost), center, Color(1f, 0.6f, 0.15f, 1f))
            shake(1f)
            onExplode?.invoke()
        } else {
            // X block shatters into small shards.
            repeat(8) {
                val dir = tmp.set(MathUtils.random(-1f, 1f), MathUtils.random(0.6f, 1.4f), MathUtils.random(-1f, 1f)).nor()
                addDebris(models.instance(result.type), center.cpy(), Vector3(dir.x * 5f, dir.y * 7f, dir.z * 5f), 0.32f)
            }
            flashes += Flash(ModelInstance(models.ghost), center, Color(1f, 0.15f, 0.2f, 1f))
            onImpact?.invoke()
        }
    }

    private fun addDebris(instance: ModelInstance, pos: Vector3, vel: Vector3, scale: Float) {
        val axis = Vector3(vel.z, 0f, -vel.x).let { if (it.isZero(0.01f)) it.set(1f, 0f, 0f) else it.nor() }
        debris += Debris(instance, pos, vel, axis, MathUtils.random(240f, 520f), scale)
    }

    fun shake(amount: Float) {
        shakeAmount = max(shakeAmount, amount)
    }

    /** Maps a screen swipe (x right, y up) to a one-cell board move along the axis that best matches it. */
    fun swipeToCell(sx: Float, sy: Float): IntArray {
        val fwd = tmp.set(cam.direction.x, 0f, cam.direction.z).nor()
        val right = tmp2.set(-fwd.z, 0f, fwd.x)
        val wx = sx * right.x + sy * fwd.x
        val wz = sx * right.z + sy * fwd.z
        return if (abs(wx) >= abs(wz)) intArrayOf(sign(wx).toInt(), 0) else intArrayOf(0, sign(wz).toInt())
    }

    fun update(dt: Float, piece: Piece?) {
        time += dt
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
            }
            s.instance.transform.setToTranslation(s.x, s.y + 0.5f, s.z)
        }

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
        } else {
            yaw += orbitSpeed * dt
            goalY = towerHeight + towerOffset() - lift
            goalDist = dist
        }
        val k = if (dt == 0f) 1f else 1f - exp(-2.5f * dt)
        camY += (goalY - camY) * k
        currentDist += (goalDist - currentDist) * (if (dt == 0f) 1f else 1f - exp(-1.5f * dt))
        shakeAmount = max(0f, shakeAmount - dt * 1.5f)

        val p = PITCH * MathUtils.degreesToRadians
        val y = yaw * MathUtils.degreesToRadians
        cam.position.set(
            MathUtils.sin(y) * cos(p) * currentDist,
            camY + MathUtils.sin(p) * currentDist,
            MathUtils.cos(y) * cos(p) * currentDist,
        )
        cam.up.set(Vector3.Y)
        cam.lookAt(0f, camY, 0f)
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
        if (camY - viewHeight < 1f) batch.render(boardInstance, env)
        val lo = max(0, floor(camY - viewHeight * 1.2f).toInt())
        for (column in columns) for (y in lo until column.size) batch.render(column[y], env)
        for (d in debris) batch.render(d.instance, env)
        pending?.let { batch.render(it.instance, env) }

        if (piece != null) {
            val model = models.modelFor(piece.type)
            for (c in piece.cells) {
                val inst = pooled(model)
                if (piece.type.kind == PieceType.Kind.NORMAL) inst.diffuse().set(models.colorOf(piece.type))
                inst.transform.setToTranslation(visual.x + c.x - 1f, visual.y + c.y + 0.5f, visual.z + c.z - 1f)
                batch.render(inst, env)
            }
        }
        if (ghost != null) {
            val bad = piece?.type?.kind == PieceType.Kind.X
            for ((i, c) in ghost.cells.withIndex()) {
                val inst = pooled(models.ghost)
                if (ghost.onBoard[i] && !bad) {
                    inst.diffuse().set(1f, 1f, 1f, 1f); inst.blending().opacity = 0.38f
                } else {
                    inst.diffuse().set(1f, 0.2f, 0.25f, 1f); inst.blending().opacity = 0.55f
                }
                inst.transform.setToTranslation(c.x - 1f, c.y + 0.5f, c.z - 1f)
                batch.render(inst, env)
            }
        }
        if (heights != null) renderWarnings(heights)
        for (f in flashes) batch.render(f.instance, env)
        batch.end()
    }

    private fun renderWarnings(heights: IntArray) {
        val top = heights.max()
        val pulse = 0.22f + 0.12f * MathUtils.sin(time * 6f)
        for (i in heights.indices) {
            val gap = top - heights[i]
            if (gap < GameLogic.GAP_WARNING) continue
            val inst = pooled(models.pillar)
            val danger = (gap - GameLogic.GAP_WARNING + 1f) / (GameLogic.GAP_LIMIT - GameLogic.GAP_WARNING + 1f)
            inst.blending().opacity = pulse + 0.2f * danger
            inst.transform.setToTranslation(i % Board.SIZE - 1f, heights[i] + gap / 2f, i / Board.SIZE - 1f)
                .scale(0.9f, gap.toFloat(), 0.9f)
            batch.render(inst, env)
        }
    }

    /** Reuses instances across frames: each one is marked as taken until the next render call. */
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
        /** World units that must fit across the screen around the board center (board + overhang room). */
        const val VISIBLE_HALF_WIDTH = 4.1f
        const val MIN_VISIBLE_HALF_HEIGHT = 6.5f
        const val GRAVITY = 40f
        const val FLASH_TIME = 0.4f
    }
}
