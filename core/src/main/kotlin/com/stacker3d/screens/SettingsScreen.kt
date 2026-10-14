package com.stacker3d.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.stacker3d.StackerGame
import com.stacker3d.render.Sky
import com.stacker3d.ui.ButtonInput
import com.stacker3d.ui.UiButton
import com.stacker3d.ui.panel
import com.stacker3d.ui.text

/** Sound, vibration and on-screen button toggles (spec §9.5). [back] returns to the menu or the paused game. */
class SettingsScreen(private val game: StackerGame, private val back: () -> Unit) : ScreenAdapter() {
    private val a = game.assets
    private val s = game.settings
    private val sky = Sky()
    private val batch = SpriteBatch()
    private var w = 1f
    private var h = 1f
    private var disposed = false

    private val soundBtn = UiButton { s.sound = !s.sound; refresh() }
    private val vibrateBtn = UiButton { s.vibration = !s.vibration; refresh(); a.vibrate(40) }
    private val buttonsBtn = UiButton { s.showButtons = !s.showButtons; refresh() }
    private val backBtn = UiButton("BACK", primary = true) { goBack() }
    private val buttons = listOf(soundBtn, vibrateBtn, buttonsBtn, backBtn)
    private val input = ButtonInput(a, { buttons }) { goBack() }

    init {
        refresh()
    }

    private fun refresh() {
        soundBtn.label = "SOUND: ${onOff(s.sound)}"
        vibrateBtn.label = "VIBRATION: ${onOff(s.vibration)}"
        buttonsBtn.label = "BUTTONS: ${onOff(s.showButtons)}"
    }

    private fun onOff(v: Boolean) = if (v) "ON" else "OFF"

    private fun goBack() {
        back()
        if (game.screen !== this) dispose()
    }

    override fun show() {
        Gdx.input.inputProcessor = input
    }

    override fun resize(width: Int, height: Int) {
        w = width.toFloat()
        h = height.toFloat()
        batch.projectionMatrix.setToOrtho2D(0f, 0f, w, h)
        val u = a.u
        val bw = 680f * u
        val bh = 140f * u
        val x = (w - bw) / 2f
        val cy = h / 2f
        soundBtn.set(x, cy + 160f * u, bw, bh)
        vibrateBtn.set(x, cy - 10f * u, bw, bh)
        buttonsBtn.set(x, cy - 180f * u, bw, bh)
        backBtn.set(x, cy - 400f * u, bw, bh)
    }

    override fun render(delta: Float) {
        sky.render(w, h, 0f, delta)
        val u = a.u
        batch.begin()
        val pw = 820f * u
        val ph = 1060f * u
        panel(batch, a, (w - pw) / 2f, h / 2f - 480f * u, pw, ph)
        text(batch, a.fontLarge, "SETTINGS", w / 2f, h / 2f + 440f * u)
        buttons.forEach { it.draw(batch, a) }
        batch.end()
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        sky.dispose()
        batch.dispose()
    }
}
