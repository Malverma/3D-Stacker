package com.stacker3d.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.utils.Disposable
import com.stacker3d.shop.Catalog
import com.stacker3d.shop.SkyTheme
import kotlin.random.Random

/** Height-driven gradient sky (spec §7): day → afternoon → sunset → dusk → night → space. */
class Sky(theme: SkyTheme = Catalog.classicSky) : Disposable {
    private class Key(val height: Float, top: Int, bottom: Int) {
        val top = Color(top shl 8 or 0xFF)
        val bottom = Color(bottom shl 8 or 0xFF)
    }

    private var keys = emptyList<Key>()
    private var starsFrom = 55f

    /** The equipped background scheme (shop "Backgrounds" tab). */
    var theme: SkyTheme = theme
        set(value) {
            field = value
            keys = value.keys.map { Key(it.height, it.top, it.bottom) }
            starsFrom = value.starsFrom
        }

    init {
        this.theme = theme
    }

    private val shapes = ShapeRenderer()
    private val top = Color()
    private val bottom = Color()
    private val stars = Array(140) { floatArrayOf(Random.nextFloat(), Random.nextFloat(), Random.nextFloat()) }
    private var time = 0f

    fun render(width: Float, height: Float, towerHeight: Float, dt: Float) {
        time += dt
        colorsAt(towerHeight)
        shapes.projectionMatrix.setToOrtho2D(0f, 0f, width, height)
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        shapes.rect(0f, 0f, width, height, bottom, bottom, top, top)

        val starAlpha = MathUtils.clamp((towerHeight - starsFrom) / 20f, 0f, 1f)
        if (starAlpha > 0f) {
            val size = height / 400f
            for (s in stars) {
                val twinkle = 0.6f + 0.4f * MathUtils.sin(time * 2f + s[2] * 40f)
                shapes.setColor(1f, 1f, 1f, starAlpha * twinkle * (0.4f + 0.6f * s[2]))
                shapes.rect(s[0] * width, s[1] * height, size * (1f + s[2]), size * (1f + s[2]))
            }
        }
        shapes.end()
    }

    private fun colorsAt(h: Float) {
        val last = keys.last()
        if (h >= last.height) {
            // Past the last key the space glow slowly cycles through hues.
            val hue = ((h - last.height) * 2.5f) % 360f
            top.set(last.top)
            bottom.fromHsv(hue, 0.7f, 0.16f).a = 1f
            bottom.lerp(last.bottom, MathUtils.clamp(1f - (h - last.height) / 15f, 0f, 1f))
            return
        }
        val i = keys.indexOfLast { it.height <= h }.coerceAtLeast(0)
        val a = keys[i]
        val b = keys[i + 1]
        val t = MathUtils.clamp((h - a.height) / (b.height - a.height), 0f, 1f)
        top.set(a.top).lerp(b.top, t)
        bottom.set(a.bottom).lerp(b.bottom, t)
    }

    override fun dispose() = shapes.dispose()
}
