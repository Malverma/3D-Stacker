package com.stacker3d.input

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.utils.TimeUtils
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sign

/**
 * Touch controls from spec §5:
 *  - drag: the piece follows the finger cell by cell along the board axes as they appear on screen
 *  - tap: rotate, two-finger tap: tip
 *  - press on the board and drag sideways (or two-finger drag/twist anywhere): turn the board view, which
 *    follows the finger and snaps to quarter turns on release
 *  - fast swipe straight down: hard drop
 * Desktop keys (arrows/WASD, R, T, Space, Q/E) map to the same actions for testing.
 */
class GestureInput(private val actions: Actions) : InputAdapter() {
    interface Actions {
        /** Screen drag (pixels, x right, y up) → fractional board cells along X and Z, written to [out]. */
        fun dragToCells(sx: Float, sy: Float, out: FloatArray)
        /** True if a touch here (screen pixels, y down) is on the board or tower rather than the piece. */
        fun touchesBoard(sx: Float, sy: Float): Boolean
        /** Board-axis move; false if it was blocked. */
        fun moveCell(dx: Int, dz: Int): Boolean
        /** Move in default-view axes, turned to match the current view (keyboard). */
        fun moveView(dx: Int, dz: Int)
        fun rotate()
        fun tip()
        fun drop()
        fun rotateView(steps: Int)
        fun twistView(degrees: Float)
        fun endTwist()
        val enabled: Boolean
    }

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var tracking = false
    private var moved = false
    private var multi = false
    private var twisting = false
    /** A twist already turned the board during this touch; nothing else fires until all fingers lift. */
    private var twisted = false
    /** This touch started on the board, so a sideways drag spins the view instead of moving the piece. */
    private var onBoard = false
    private var pointersDown = 0

    /** Drag not yet classified (direction is judged over a short stretch, not per noisy event). */
    private var pendX = 0f
    private var pendY = 0f
    /** Cells travelled along X / Z that have not turned into a move yet. */
    private val acc = FloatArray(2)
    private val cells = FloatArray(2)

    // Both fingers' start and current positions (y down), for the two-finger turn.
    private val start = FloatArray(4)
    private val cur = FloatArray(4)

    private val minSide get() = min(Gdx.graphics.width, Gdx.graphics.height).toFloat()
    private val slop get() = minSide * 0.03f

    override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        if (!actions.enabled) return false
        pointersDown++
        if (pointer == 0) {
            downX = screenX.toFloat(); downY = screenY.toFloat()
            lastX = downX; lastY = downY
            downTime = TimeUtils.millis()
            tracking = true
            moved = false
            multi = false
            twisting = false
            twisted = false
            onBoard = actions.touchesBoard(downX, downY)
            pendX = 0f; pendY = 0f
            acc.fill(0f)
        } else if (tracking && pointer == 1) {
            multi = true
            start[0] = Gdx.input.getX(0).toFloat(); start[1] = Gdx.input.getY(0).toFloat()
            start[2] = screenX.toFloat(); start[3] = screenY.toFloat()
            start.copyInto(cur)
        }
        return true
    }

    override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
        if (!tracking) return false
        if (twisted) return true
        if (multi) {
            if (pointer > 1) return true
            cur[pointer * 2] = screenX.toFloat()
            cur[pointer * 2 + 1] = screenY.toFloat()
            val degrees = turnDegrees()
            if (!twisting && abs(degrees) > TWIST_START) twisting = true
            if (twisting) actions.twistView(degrees)
            return true
        }
        if (pointer != 0) return false
        if (onBoard) {
            val sx = screenX - downX
            if (!twisting && abs(sx) > slop && !isVertical(sx, downY - screenY)) twisting = true
            if (twisting) {
                moved = true
                actions.twistView(-sx / Gdx.graphics.width * TWIST_DEGREES)
            }
            return true
        }

        pendX += screenX - lastX
        pendY += lastY - screenY
        lastX = screenX.toFloat()
        lastY = screenY.toFloat()
        if (hypot(pendX, pendY) < slop * 0.5f) return true
        if (hypot(screenX - downX, downY - screenY) > slop) moved = true

        // Mostly-vertical motion is left for the hard-drop flick so it never slides the piece.
        if (!isVertical(pendX, pendY)) {
            actions.dragToCells(pendX * SENSITIVITY, pendY * SENSITIVITY, cells)
            acc[0] += cells[0]
            acc[1] += cells[1]
            step(0)
            step(1)
        }
        pendX = 0f; pendY = 0f
        return true
    }

    /**
     * How far two fingers have turned the board: sliding both sideways (like spinning a turntable) and
     * twisting them around each other both count, so either habit works.
     */
    private fun turnDegrees(): Float {
        val slide = -((cur[0] - start[0]) + (cur[2] - start[2])) / 2f / Gdx.graphics.width * TWIST_DEGREES
        val a0 = MathUtils.atan2(start[3] - start[1], start[2] - start[0])
        val a1 = MathUtils.atan2(cur[3] - cur[1], cur[2] - cur[0])
        var twist = (a1 - a0) * MathUtils.radiansToDegrees
        if (twist > 180f) twist -= 360f else if (twist < -180f) twist += 360f
        // Screen-clockwise twist (y down → positive angle) spins the board clockwise too.
        return slide + twist
    }

    /** Commits whole cells on one axis once enough travel has built up. */
    private fun step(axis: Int) {
        while (abs(acc[axis]) >= COMMIT) {
            val dir = sign(acc[axis]).toInt()
            val ok = if (axis == 0) actions.moveCell(dir, 0) else actions.moveCell(0, dir)
            if (!ok) {
                // Pushing against a wall or the tower doesn't wind up hidden travel.
                acc[axis] = dir * COMMIT * 0.99f
                return
            }
            acc[axis] -= dir
            // Damp the other axis so a slightly crooked drag doesn't drift sideways.
            acc[1 - axis] *= 0.5f
        }
    }

    override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        pointersDown = (pointersDown - 1).coerceAtLeast(0)
        if (!tracking) return false
        if (twisting && (pointer <= 1 || pointersDown == 0)) {
            twisting = false
            twisted = true
            actions.endTwist()
        }
        if (pointersDown > 0) return true
        tracking = false
        val elapsed = TimeUtils.millis() - downTime
        val sx = screenX - downX
        val sy = downY - screenY
        val dist = hypot(sx, sy)
        when {
            twisted -> Unit
            multi && elapsed < 450 && dist < slop * 2.5f -> actions.tip()
            multi -> Unit
            !moved && dist < slop * 1.5f && elapsed < 350 -> actions.rotate()
            sy < 0 && isVertical(sx, sy) && -sy > Gdx.graphics.height * 0.08f && elapsed < 450 -> actions.drop()
        }
        return true
    }

    /** Within ±30° of straight up/down — reserved for hard drop, so it never moves the piece sideways. */
    private fun isVertical(sx: Float, sy: Float) = abs(sx) < abs(sy) * 0.577f

    override fun keyDown(keycode: Int): Boolean {
        if (!actions.enabled) return false
        when (keycode) {
            Input.Keys.LEFT, Input.Keys.A -> actions.moveView(-1, 0)
            Input.Keys.RIGHT, Input.Keys.D -> actions.moveView(1, 0)
            Input.Keys.UP, Input.Keys.W -> actions.moveView(0, -1)
            Input.Keys.DOWN, Input.Keys.S -> actions.moveView(0, 1)
            Input.Keys.R -> actions.rotate()
            Input.Keys.T -> actions.tip()
            Input.Keys.SPACE -> actions.drop()
            Input.Keys.Q -> actions.rotateView(-1)
            Input.Keys.E -> actions.rotateView(1)
            else -> return false
        }
        return true
    }

    companion object {
        /** Finger travel is scaled up so one cell takes a bit less than its on-screen size. */
        const val SENSITIVITY = 1.35f
        /** Fraction of a cell the drag must cover before the piece steps (leftover carries over). */
        const val COMMIT = 0.55f
        /** Degrees the view turns for a two-finger drag across the full screen width. */
        const val TWIST_DEGREES = 220f
        /** Degrees of two-finger motion before it counts as turning rather than a tip tap. */
        const val TWIST_START = 8f
    }
}
