package com.stacker3d.ui

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Align
import com.stacker3d.Assets

val NAVY: Color = Color.valueOf("1B2A4A")
val ACCENT: Color = Color.valueOf("FFD23F")
val DANGER: Color = Color.valueOf("FF4D5E")
val HEART_PINK: Color = Color.valueOf("FF8FB8")

enum class TextSize { MEDIUM, SMALL, TINY }

/** A tappable rectangle in screen pixels (origin bottom-left), drawn either as an icon or a labeled pill. */
class UiButton(
    var label: String = "",
    val icon: Texture? = null,
    var primary: Boolean = false,
    /** Small icon drawn before the label (e.g. a coin next to a price). */
    var labelIcon: Texture? = null,
    /** Label size; narrow buttons (tabs, shop rows) use the smaller fonts. */
    val textSize: TextSize = TextSize.MEDIUM,
    val onClick: () -> Unit,
) {
    var x = 0f
    var y = 0f
    var w = 0f
    var h = 0f
    var visible = true
    var pressed = false
    /** Disabled buttons are drawn faded and ignore taps. */
    var enabled = true

    fun set(x: Float, y: Float, w: Float, h: Float) = apply { this.x = x; this.y = y; this.w = w; this.h = h }

    fun contains(px: Float, py: Float) = visible && enabled && px >= x && px <= x + w && py >= y && py <= y + h

    fun draw(batch: SpriteBatch, a: Assets) {
        if (!visible) return
        val s = if (pressed) 0.94f else 1f
        val dw = w * s
        val dh = h * s
        val dx = x + (w - dw) / 2f
        val dy = y + (h - dh) / 2f
        if (icon != null) {
            batch.color = Color.WHITE
            batch.draw(icon, dx, dy, dw, dh)
            return
        }
        val alpha = if (enabled) 1f else 0.55f
        batch.setColor(0f, 0f, 0f, 0.18f * alpha)
        a.drawPanel(batch, dx, dy - 8f * a.u, dw, dh)
        val base = if (primary) ACCENT else Color.WHITE
        batch.setColor(base.r, base.g, base.b, alpha)
        a.drawPanel(batch, dx, dy, dw, dh)
        batch.color = Color.WHITE
        val font = when (textSize) {
            TextSize.MEDIUM -> a.fontMedium
            TextSize.SMALL -> a.fontSmall
            TextSize.TINY -> a.fontTiny
        }
        font.setColor(NAVY.r, NAVY.g, NAVY.b, alpha)
        val iconTex = labelIcon
        if (iconTex == null) {
            text(batch, font, label, dx + dw / 2f, dy + dh / 2f)
        } else {
            val size = dh * 0.55f
            val gap = 12f * a.u
            layout.setText(font, label)
            val left = dx + (dw - size - gap - layout.width) / 2f
            batch.setColor(1f, 1f, 1f, alpha)
            batch.draw(iconTex, left, dy + (dh - size) / 2f, size, size)
            batch.color = Color.WHITE
            text(batch, font, label, left + size + gap, dy + dh / 2f, Align.left)
        }
        font.color = Color.WHITE
    }
}

private val layout = GlyphLayout()

/** Draws [str] vertically centered on cy; [align] decides whether cx is its left edge, center or right edge. */
fun text(batch: SpriteBatch, font: BitmapFont, str: String, cx: Float, cy: Float, align: Int = Align.center) {
    layout.setText(font, str)
    val x = when (align) {
        Align.left -> cx
        Align.right -> cx - layout.width
        else -> cx - layout.width / 2f
    }
    font.draw(batch, layout, x, cy + layout.height / 2f)
}

fun panel(batch: SpriteBatch, a: Assets, x: Float, y: Float, w: Float, h: Float, alpha: Float = 0.82f) {
    batch.setColor(NAVY.r, NAVY.g, NAVY.b, alpha)
    a.drawPanel(batch, x, y, w, h)
    batch.color = Color.WHITE
}

fun dim(batch: SpriteBatch, a: Assets, w: Float, h: Float, alpha: Float) {
    batch.setColor(0f, 0.02f, 0.08f, alpha)
    batch.draw(a.white, 0f, 0f, w, h)
    batch.color = Color.WHITE
}

/**
 * Routes touches to whichever [UiButton]s are active. A button fires on release only if the finger is still on it.
 * Touches that miss every button fall through to the next processor (the game gestures).
 */
class ButtonInput(
    private val assets: Assets,
    private val buttons: () -> List<UiButton>,
    private val onBack: () -> Unit,
) : InputAdapter() {
    private val pressed = HashMap<Int, UiButton>()

    override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        val y = Gdx.graphics.height - screenY.toFloat()
        val hit = buttons().lastOrNull { it.contains(screenX.toFloat(), y) } ?: return false
        hit.pressed = true
        pressed[pointer] = hit
        return true
    }

    override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
        val b = pressed[pointer] ?: return false
        b.pressed = b.contains(screenX.toFloat(), Gdx.graphics.height - screenY.toFloat())
        return true
    }

    override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        val b = pressed.remove(pointer) ?: return false
        val wasPressed = b.pressed
        b.pressed = false
        if (wasPressed && buttons().contains(b)) {
            assets.play(assets.sClick, 0.6f)
            b.onClick()
        }
        return true
    }

    override fun keyDown(keycode: Int): Boolean {
        if (keycode == Input.Keys.BACK || keycode == Input.Keys.ESCAPE) {
            onBack()
            return true
        }
        return false
    }
}
