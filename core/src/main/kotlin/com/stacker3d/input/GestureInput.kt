package com.stacker3d.input

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.utils.TimeUtils
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Touch controls from spec §5:
 *  - drag diagonally: move one cell per step along the board axis that matches the drag
 *  - tap: rotate, two-finger tap: tip
 *  - fast swipe straight down: hard drop
 * Desktop keys (arrows/WASD, R, T, Space) map to the same actions for testing.
 */
class GestureInput(private val actions: Actions) : InputAdapter() {
    interface Actions {
        /** Screen-space swipe direction (x right, y up) → one-cell move. */
        fun move(sx: Float, sy: Float)
        fun moveCell(dx: Int, dz: Int)
        fun rotate()
        fun tip()
        fun drop()
        val enabled: Boolean
    }

    private var downX = 0f
    private var downY = 0f
    private var anchorX = 0f
    private var anchorY = 0f
    private var downTime = 0L
    private var tracking = false
    private var moved = false
    private var multi = false
    private var pointersDown = 0

    private val step get() = min(Gdx.graphics.width, Gdx.graphics.height) * 0.075f

    override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        if (!actions.enabled) return false
        pointersDown++
        if (pointer == 0) {
            downX = screenX.toFloat(); downY = screenY.toFloat()
            anchorX = downX; anchorY = downY
            downTime = TimeUtils.millis()
            tracking = true
            moved = false
            multi = false
        } else if (tracking) {
            multi = true
        }
        return true
    }

    override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
        if (!tracking || pointer != 0 || multi) return false
        val sx = screenX - anchorX
        val sy = anchorY - screenY
        if (hypot(sx, sy) < step) return true
        moved = true
        if (!isVertical(sx, sy)) actions.move(sx, sy)
        anchorX = screenX.toFloat()
        anchorY = screenY.toFloat()
        return true
    }

    override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        pointersDown = (pointersDown - 1).coerceAtLeast(0)
        if (!tracking) return false
        if (pointersDown > 0) return true
        tracking = false
        val elapsed = TimeUtils.millis() - downTime
        val sx = screenX - downX
        val sy = downY - screenY
        val dist = hypot(sx, sy)
        when {
            multi && elapsed < 450 && dist < step -> actions.tip()
            multi -> Unit
            !moved && dist < step * 0.6f && elapsed < 350 -> actions.rotate()
            sy < 0 && isVertical(sx, sy) && -sy > Gdx.graphics.height * 0.08f && elapsed < 450 -> actions.drop()
        }
        return true
    }

    /** Within ±30° of straight up/down — reserved for hard drop, so it never moves the piece sideways. */
    private fun isVertical(sx: Float, sy: Float) = abs(sx) < abs(sy) * 0.577f

    override fun keyDown(keycode: Int): Boolean {
        if (!actions.enabled) return false
        when (keycode) {
            Input.Keys.LEFT, Input.Keys.A -> actions.moveCell(-1, 0)
            Input.Keys.RIGHT, Input.Keys.D -> actions.moveCell(1, 0)
            Input.Keys.UP, Input.Keys.W -> actions.moveCell(0, -1)
            Input.Keys.DOWN, Input.Keys.S -> actions.moveCell(0, 1)
            Input.Keys.R -> actions.rotate()
            Input.Keys.T -> actions.tip()
            Input.Keys.SPACE -> actions.drop()
            else -> return false
        }
        return true
    }
}
