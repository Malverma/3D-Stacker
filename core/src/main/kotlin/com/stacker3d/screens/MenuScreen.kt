package com.stacker3d.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.math.MathUtils
import com.stacker3d.StackerGame
import com.stacker3d.logic.PieceType
import com.stacker3d.render.WorldRenderer
import com.stacker3d.shop.Catalog
import com.stacker3d.ui.ButtonInput
import com.stacker3d.ui.UiButton
import com.stacker3d.ui.text
import kotlin.math.min
import kotlin.random.Random

/** Title, records, Play and Settings over a slowly orbiting sample tower (spec §9.1). */
class MenuScreen(private val game: StackerGame) : ScreenAdapter() {
    private val a = game.assets
    private val world = WorldRenderer(a.models)
    private val batch = SpriteBatch()
    private var w = 1f
    private var h = 1f
    private var time = 0f
    private val glyphs = GlyphLayout()

    private val playBtn = UiButton("PLAY", primary = true) { game.startGame() }
    private val noFallBtn = UiButton("NO FALL") { game.startGame(noFall = true) }
    private val settingsBtn = UiButton("SETTINGS") { game.showSettings { game.setScreen(this) } }
    private val shopBtn = UiButton("SHOP") { game.showShop { game.setScreen(this) } }
    private val buttons = listOf(playBtn, noFallBtn, settingsBtn, shopBtn)
    private val input = ButtonInput(a, { buttons }) { Gdx.app.exit() }

    init {
        world.orbitSpeed = 9f
    }

    /** Rebuilds the backdrop tower so it shows the currently equipped sky and block colors. */
    private fun buildBackdrop() {
        world.theme = Catalog.sky(game.settings.skyId)
        a.models.skin = Catalog.skin(game.debug.skinId ?: game.settings.skinId)
        world.lift = 2.2f
        world.reset()
        world.orbitSpeed = 9f
        val random = Random(42)
        // A full, stepped tower: every column filled, rising toward the back corner.
        for (x in 0..2) for (z in 0..2) {
            repeat(MENU_HEIGHTS[x + z * 3]) { world.addCube(x, z, PieceType.normals.random(random)) }
        }
    }

    override fun show() {
        buildBackdrop()
        Gdx.input.inputProcessor = input
    }

    override fun resize(width: Int, height: Int) {
        w = width.toFloat()
        h = height.toFloat()
        world.resize(width, height)
        batch.projectionMatrix.setToOrtho2D(0f, 0f, w, h)
        val u = a.u
        val bw = 620f * u
        val bh = 150f * u
        val step = bh + 32f * u
        val bottom = Gdx.graphics.safeInsetBottom + 100f * u
        playBtn.set((w - bw) / 2f, bottom + 3 * step, bw, bh)
        noFallBtn.set((w - bw) / 2f, bottom + 2 * step, bw, bh)
        settingsBtn.set((w - bw) / 2f, bottom + step, bw, bh)
        shopBtn.set((w - bw) / 2f, bottom, bw, bh)
    }

    override fun render(delta: Float) {
        val dt = min(delta, 1f / 20f)
        time += dt
        world.update(dt, null)
        world.render(null, null, dt)

        val u = a.u
        val top = h - Gdx.graphics.safeInsetTop
        batch.begin()
        val bob = MathUtils.sin(time * 1.6f) * 10f * u
        text(batch, a.fontTitle, "3D", w / 2f, top - 230f * u + bob)
        text(batch, a.fontTitle, "STACKER", w / 2f, top - 400f * u + bob)
        text(batch, a.fontSmall, "HIGH SCORE  ${game.settings.highScore}", w / 2f, top - 540f * u)
        text(batch, a.fontSmall, "BEST HEIGHT  ${game.settings.bestHeight}", w / 2f, top - 600f * u)
        buttons.forEach { it.draw(batch, a) }

        // Coin balance, top-right.
        val cs = 70f * u
        val m = 40f * u
        glyphs.setText(a.fontMedium, game.settings.coins.toString())
        val cx = w - m - glyphs.width
        text(batch, a.fontMedium, game.settings.coins.toString(), cx, top - m - cs / 2f, Align.left)
        batch.draw(a.coin, cx - cs - 14f * u, top - m - cs, cs, cs)
        batch.end()
    }

    override fun dispose() {
        world.dispose()
        batch.dispose()
    }

    companion object {
        /** Backdrop column heights, indexed x + z * 3 (back corner tallest). */
        val MENU_HEIGHTS = intArrayOf(
            4, 3, 2,
            3, 3, 2,
            2, 2, 1,
        )
    }
}
