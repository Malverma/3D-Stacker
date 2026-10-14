package com.stacker3d.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Align
import com.stacker3d.StackerGame
import com.stacker3d.logic.PieceType
import com.stacker3d.render.Sky
import com.stacker3d.render.color
import com.stacker3d.shop.Catalog
import com.stacker3d.shop.ShopItem
import com.stacker3d.shop.ShopTab
import com.stacker3d.ui.ButtonInput
import com.stacker3d.ui.TextSize
import com.stacker3d.ui.UiButton
import com.stacker3d.ui.panel
import com.stacker3d.ui.text

/** Spend coins on backgrounds, block colors and special blocks. [back] returns to the menu. */
class ShopScreen(
    private val game: StackerGame,
    initialTab: ShopTab = ShopTab.BACKGROUNDS,
    private val back: () -> Unit,
) : ScreenAdapter() {
    private val a = game.assets
    private val s = game.settings
    private val sky = Sky(Catalog.sky(s.skyId))
    private val batch = SpriteBatch()
    private var w = 1f
    private var h = 1f
    private var disposed = false
    private var tab = initialTab
    private val heartTex = Texture(Gdx.files.internal("textures/cube_heart.png"))

    private class Row(val item: ShopItem, val button: UiButton) {
        var y = 0f
    }

    private val tabButtons = ShopTab.entries.map { t -> UiButton(t.title, textSize = TextSize.TINY) { tab = t; rebuild() } }
    private val backBtn = UiButton("BACK", primary = true) { goBack() }
    private var rows = emptyList<Row>()
    private val input = ButtonInput(a, { tabButtons + rows.map { it.button } + backBtn }) { goBack() }

    init {
        rebuild()
    }

    private fun rebuild() {
        tabButtons.forEachIndexed { i, b -> b.primary = ShopTab.entries[i] == tab }
        rows = Catalog.items.filter { it.tab == tab }.map { item -> Row(item, UiButton(textSize = TextSize.SMALL) { onItem(item) }) }
        refreshButtons()
        layout()
    }

    private fun refreshButtons() {
        for (r in rows) {
            val item = r.item
            val b = r.button
            b.labelIcon = null
            b.enabled = true
            b.primary = false
            when {
                !s.owns(item) -> {
                    b.label = item.price.toString()
                    b.labelIcon = a.coin
                    b.primary = true
                    b.enabled = s.coins >= item.price
                }
                !item.equippable -> { b.label = "OWNED"; b.enabled = false }
                s.isEquipped(item) -> { b.label = "EQUIPPED"; b.enabled = false }
                else -> b.label = "EQUIP"
            }
        }
    }

    private fun onItem(item: ShopItem) {
        if (!s.owns(item)) {
            if (s.buy(item)) a.play(a.sPerfect, 0.6f)
        } else {
            s.equip(item)
        }
        sky.theme = Catalog.sky(s.skyId)
        refreshButtons()
    }

    private fun goBack() {
        back()
        if (game.screen !== this) dispose()
    }

    override fun show() {
        Gdx.input.inputProcessor = input
        refreshButtons()
    }

    override fun resize(width: Int, height: Int) {
        w = width.toFloat()
        h = height.toFloat()
        batch.projectionMatrix.setToOrtho2D(0f, 0f, w, h)
        layout()
    }

    private fun layout() {
        val u = a.u
        val m = 40f * u
        val top = h - Gdx.graphics.safeInsetTop
        val gap = 16f * u
        val tw = (w - 2 * m - 2 * gap) / 3f
        tabButtons.forEachIndexed { i, b -> b.set(m + i * (tw + gap), top - 400f * u, tw, 110f * u) }
        var y = top - 440f * u
        for (r in rows) {
            y -= ROW_H * u
            r.y = y
            r.button.set(w - m - 30f * u - 270f * u, y + (ROW_H - ROW_GAP - 100f) / 2f * u, 270f * u, 100f * u)
        }
        val bw = 620f * u
        backBtn.set((w - bw) / 2f, Gdx.graphics.safeInsetBottom + m, bw, 140f * u)
    }

    override fun render(delta: Float) {
        sky.render(w, h, 0f, delta)
        val u = a.u
        val m = 40f * u
        val top = h - Gdx.graphics.safeInsetTop
        batch.begin()
        text(batch, a.fontLarge, "SHOP", w / 2f, top - 110f * u)

        // Coin balance.
        val label = s.coins.toString()
        val cs = 76f * u
        val bw = 320f * u
        panel(batch, a, (w - bw) / 2f, top - 290f * u, bw, 110f * u, 0.55f)
        batch.draw(a.coin, (w - bw) / 2f + 28f * u, top - 290f * u + (110f * u - cs) / 2f, cs, cs)
        text(batch, a.fontMedium, label, (w - bw) / 2f + 28f * u + cs + 20f * u, top - 235f * u, Align.left)

        tabButtons.forEach { it.draw(batch, a) }

        for (r in rows) {
            val x = m
            val rw = w - 2 * m
            val rh = (ROW_H - ROW_GAP) * u
            panel(batch, a, x, r.y, rw, rh, 0.72f)
            val ss = rh - 40f * u
            drawSwatch(r.item, x + 20f * u, r.y + 20f * u, ss)
            val tx = x + 20f * u + ss + 28f * u
            if (r.item.description.isEmpty()) {
                text(batch, a.fontSmall, r.item.name, tx, r.y + rh / 2f, Align.left)
            } else {
                text(batch, a.fontSmall, r.item.name, tx, r.y + rh * 0.66f, Align.left)
                text(batch, a.fontTiny, r.item.description, tx, r.y + rh * 0.3f, Align.left)
            }
            r.button.draw(batch, a)
        }
        backBtn.draw(batch, a)
        batch.end()
    }

    /** Preview square: sky gradient, block color(s), or the special block's face. */
    private fun drawSwatch(item: ShopItem, x: Float, y: Float, size: Float) {
        when (item.tab) {
            ShopTab.BACKGROUNDS -> {
                val keys = Catalog.sky(item.id).keys
                val strips = 8
                val c = Color()
                for (i in 0 until strips) {
                    // Bottom of the swatch shows the ground-level gradient; higher strips step through the theme.
                    val k = keys[(i * (keys.size - 1)) / (strips - 1)]
                    c.set(k.bottom shl 8 or 0xFF).lerp(Color(k.top shl 8 or 0xFF), 0.5f)
                    batch.color = c
                    batch.draw(a.white, x, y + i * size / strips, size, size / strips + 1f)
                }
                batch.color = Color.WHITE
            }
            ShopTab.BLOCKS -> {
                val skin = Catalog.skin(item.id)
                val half = size / 2f
                val colors = if (skin.rgb != null) List(4) { Color(skin.rgb shl 8 or 0xFF) } else PieceType.normals.take(4).map { it.color }
                colors.forEachIndexed { i, col ->
                    batch.color = col
                    batch.draw(a.white, x + (i % 2) * half + 3f, y + (i / 2) * half + 3f, half - 6f, half - 6f)
                }
                batch.color = Color.WHITE
            }
            ShopTab.SPECIALS -> batch.draw(heartTex, x, y, size, size)
        }
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        sky.dispose()
        batch.dispose()
        heartTex.dispose()
    }

    companion object {
        /** Row height in reference units, including the gap below it. */
        const val ROW_H = 168f
        const val ROW_GAP = 18f
    }
}
