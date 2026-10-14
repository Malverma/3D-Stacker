package com.stacker3d

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.audio.Sound
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.NinePatch
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator
import com.badlogic.gdx.utils.Disposable
import com.stacker3d.render.Models
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Textures, fonts and sounds. UI is laid out for a 1080×1920 reference screen and scaled by [u],
 * and fonts are rasterized at the real pixel size so text stays sharp on every device.
 */
class Assets(private val settings: Settings) : Disposable {
    private val textures = ArrayList<Texture>()
    private fun tex(path: String) = Texture(Gdx.files.internal(path), true).also {
        it.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear)
        textures += it
    }

    val heartFull = tex("ui/heart_full.png")
    val heartEmpty = tex("ui/heart_empty.png")
    val btnPause = tex("ui/btn_pause.png")
    val btnRotate = tex("ui/btn_rotate.png")
    val btnTip = tex("ui/btn_tip.png")
    val btnDrop = tex("ui/btn_drop.png")
    val coin = tex("ui/coin.png")
    private val panelTex = Texture(Gdx.files.internal("ui/panel.png")).also {
        it.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        textures += it
    }
    val panel = NinePatch(panelTex, 40, 40, 40, 40)
    val white: Texture = run {
        val pm = com.badlogic.gdx.graphics.Pixmap(1, 1, com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888)
        pm.setColor(Color.WHITE)
        pm.fill()
        Texture(pm).also { pm.dispose(); textures += it }
    }

    val models = Models()

    private val sounds = ArrayList<Sound>()
    private fun sfx(name: String) = Gdx.audio.newSound(Gdx.files.internal("sfx/$name.wav")).also { sounds += it }

    val sLand = sfx("land")
    val sFall = sfx("fall")
    val sHeart = sfx("heart")
    val sClick = sfx("click")
    val sGameOver = sfx("gameover")
    val sPerfect = sfx("perfect")
    val sBoom = sfx("boom")

    private val generator = FreeTypeFontGenerator(Gdx.files.internal("fonts/NotoSans-Black.ttf"))
    lateinit var fontTitle: BitmapFont; private set
    lateinit var fontLarge: BitmapFont; private set
    lateinit var fontMedium: BitmapFont; private set
    lateinit var fontSmall: BitmapFont; private set
    lateinit var fontTiny: BitmapFont; private set
    private var fontsBuilt = false

    /** UI scale: pixels per reference unit. */
    var u = 1f; private set

    fun resize(width: Int, height: Int) {
        val nu = min(width / 1080f, height / 1920f)
        if (fontsBuilt && abs(nu - u) < 0.001f) return
        if (fontsBuilt) fonts().forEach { it.dispose() }
        u = nu
        fontTitle = font(150f, 8f)
        fontLarge = font(96f, 6f)
        fontMedium = font(60f, 4f)
        fontSmall = font(42f, 3f)
        fontTiny = font(32f, 2f)
        fontsBuilt = true
    }

    private fun fonts() = listOf(fontTitle, fontLarge, fontMedium, fontSmall, fontTiny)

    private fun font(size: Float, shadow: Float): BitmapFont {
        val p = FreeTypeFontGenerator.FreeTypeFontParameter().apply {
            this.size = max(8, (size * u).roundToInt())
            color = Color.WHITE
            shadowOffsetX = 0
            shadowOffsetY = max(1, (shadow * u).roundToInt())
            shadowColor = Color(0.04f, 0.07f, 0.18f, 0.45f)
            minFilter = Texture.TextureFilter.Linear
            magFilter = Texture.TextureFilter.Linear
        }
        return generator.generateFont(p)
    }

    /** Draws the rounded panel with its corner radius scaled to the UI, so small screens keep round corners. */
    fun drawPanel(batch: SpriteBatch, x: Float, y: Float, w: Float, h: Float) {
        val s = min(u, min(w, h) / 2f / panel.leftWidth)
        panel.draw(batch, x, y, 0f, 0f, w / s, h / s, s, s, 0f)
    }

    fun play(sound: Sound, volume: Float = 1f, pitch: Float = 1f) {
        if (settings.sound) sound.play(volume, pitch, 0f)
    }

    fun vibrate(ms: Int) {
        if (settings.vibration) Gdx.input.vibrate(ms)
    }

    override fun dispose() {
        textures.forEach { it.dispose() }
        models.dispose()
        sounds.forEach { it.dispose() }
        if (fontsBuilt) fonts().forEach { it.dispose() }
        generator.dispose()
    }
}
