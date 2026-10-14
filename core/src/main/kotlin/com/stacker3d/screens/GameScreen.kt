package com.stacker3d.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.math.Interpolation
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.utils.Align
import com.stacker3d.Autoplay
import com.stacker3d.StackerGame
import com.stacker3d.input.GestureInput
import com.stacker3d.logic.GameLogic
import com.stacker3d.logic.GameOverReason
import com.stacker3d.logic.PieceType
import com.stacker3d.logic.LandingResult
import com.stacker3d.logic.Piece
import com.stacker3d.render.PiecePreview
import com.stacker3d.render.WorldRenderer
import com.stacker3d.shop.Catalog
import com.stacker3d.ui.ACCENT
import com.stacker3d.ui.ButtonInput
import com.stacker3d.ui.DANGER
import com.stacker3d.ui.HEART_PINK
import com.stacker3d.ui.UiButton
import com.stacker3d.ui.dim
import com.stacker3d.ui.panel
import com.stacker3d.ui.text
import kotlin.math.min

class GameScreen(private val game: StackerGame) : ScreenAdapter(), GameLogic.Listener, GestureInput.Actions {
    private enum class State { PLAYING, PAUSED, REVIVE, GAME_OVER }

    private class Popup(val text: String, val color: Color, val life: Float = 1.1f, var age: Float = 0f)

    private val a = game.assets
    private val world = WorldRenderer(a.models)
    private val preview = PiecePreview(a.models)
    private val batch = SpriteBatch()
    private val logic = GameLogic(listener = this)
    private val autoplay = if (game.debug.autoplay) Autoplay(logic, game.debug.autoplayDelay, game.debug.chaos) else null

    private var state = State.PLAYING
    private var w = 1f
    private var h = 1f
    private var gameOverTime = 0f
    private var time = 0f
    private var newRecord = false
    private var heartFlash = 0f
    private var boomFlash = 0f
    private var boomText: String? = null
    private var healFlash = 0f
    private var coinsEarned = 0
    private val popups = ArrayList<Popup>()
    private val glyphs = GlyphLayout()

    private val pauseBtn = UiButton(icon = a.btnPause) { state = State.PAUSED }
    private val rotateBtn = UiButton(icon = a.btnRotate) { rotate() }
    private val tipBtn = UiButton(icon = a.btnTip) { tip() }
    private val dropBtn = UiButton(icon = a.btnDrop) { drop() }
    private val resumeBtn = UiButton("RESUME", primary = true) { state = State.PLAYING }
    private val restartBtn = UiButton("RESTART") { restart() }
    private val settingsBtn = UiButton("SETTINGS") { game.showSettings { game.setScreen(this) } }
    private val menuBtn = UiButton("MENU") { game.showMenu() }
    private val againBtn = UiButton("PLAY AGAIN", primary = true) { restart() }
    private val overMenuBtn = UiButton("MENU") { game.showMenu() }
    private val watchAdBtn = UiButton("WATCH AD", primary = true, labelIcon = a.heartFull) { watchAd() }
    private val noThanksBtn = UiButton("NO THANKS") { finishGameOver() }

    private val input = InputMultiplexer(
        ButtonInput(a, ::activeButtons) { onBack() },
        GestureInput(this),
    )

    init {
        world.onImpact = { a.play(a.sLand, 0.9f, 1f + min(logic.towerHeight, 60) * 0.01f) }
        world.onExplode = {
            boomFlash = 1f
            boomText?.let { popups += Popup(it, ACCENT) }
            boomText = null
            a.play(a.sBoom)
            a.vibrate(90)
        }
        logic.immortal = game.debug.immortal
        if (game.settings.heartBlockUnlocked) logic.heartBlockChance = GameLogic.HEART_BLOCK_CHANCE
        a.models.skin = Catalog.skin(game.settings.skinId)
        world.theme = Catalog.sky(game.settings.skyId)
        logic.start()
    }

    private fun activeButtons(): List<UiButton> = when (state) {
        State.PLAYING -> if (game.settings.showButtons) listOf(pauseBtn, rotateBtn, tipBtn, dropBtn) else listOf(pauseBtn)
        State.PAUSED -> listOf(resumeBtn, restartBtn, settingsBtn, menuBtn)
        State.REVIVE -> listOf(watchAdBtn, noThanksBtn)
        State.GAME_OVER -> if (gameOverTime > OVER_PANEL_DELAY) listOf(againBtn, overMenuBtn) else emptyList()
    }

    private fun onBack() {
        when (state) {
            State.PLAYING -> state = State.PAUSED
            State.PAUSED -> state = State.PLAYING
            State.REVIVE -> finishGameOver()
            State.GAME_OVER -> game.showMenu()
        }
    }

    private fun restart() {
        world.reset()
        popups.clear()
        newRecord = false
        gameOverTime = 0f
        heartFlash = 0f
        boomFlash = 0f
        healFlash = 0f
        coinsEarned = 0
        state = State.PLAYING
        logic.start()
    }

    // ---- GestureInput.Actions -------------------------------------------------------------

    override val enabled get() = state == State.PLAYING && autoplay == null

    override fun move(sx: Float, sy: Float) {
        val (dx, dz) = world.swipeToCell(sx, sy)
        logic.move(dx, dz)
    }

    override fun moveCell(dx: Int, dz: Int) {
        logic.move(dx, dz)
    }

    override fun rotate() {
        logic.rotate()
    }

    override fun tip() {
        logic.tip()
    }

    override fun drop() = logic.hardDrop()

    // ---- GameLogic.Listener ---------------------------------------------------------------

    override fun onSpawn(piece: Piece) {
        world.onSpawn(piece)
        when (piece.type.kind) {
            PieceType.Kind.BOMB -> {
                popups += Popup("BOMB!", ACCENT, 1.8f)
                popups += Popup("CLEARS NEARBY BLOCKS", Color.WHITE, 1.8f)
            }
            PieceType.Kind.X -> {
                popups += Popup("X BLOCK!", DANGER, 1.8f)
                popups += Popup("DROP IT OFF THE EDGE", Color.WHITE, 1.8f)
            }
            PieceType.Kind.HEART -> {
                popups += Popup("HEART BLOCK!", HEART_PINK, 1.8f)
                popups += Popup("PLACE IT TO HEAL", Color.WHITE, 1.8f)
            }
            PieceType.Kind.NORMAL -> Unit
        }
    }

    override fun onLanded(result: LandingResult) {
        world.onLanded(result)
        world.towerHeight = logic.towerHeight
        popups.removeAll { it.life > 1.1f }
        when {
            result.type.kind == PieceType.Kind.BOMB -> boomText = "BOOM! +${result.points}"
            result.xHit -> popups += Popup("OUCH! X BLOCK", DANGER)
            result.dodged -> {
                popups += Popup("DODGED! +${result.points}", ACCENT)
                a.play(a.sPerfect, 0.5f)
            }
            else -> popups += Popup("+${result.points}", Color.WHITE)
        }
        if (result.perfect) {
            popups += Popup("PERFECT!", ACCENT)
            a.play(a.sPerfect, 0.5f)
        }
        if (result.lost.isNotEmpty() && !result.dodged) a.play(a.sFall, 0.8f)
    }

    override fun onHeartLost(heartsLeft: Int) {
        heartFlash = 1f
        world.shake(0.8f)
        a.play(a.sHeart)
        a.vibrate(120)
    }

    override fun onHeartGained(hearts: Int) {
        healFlash = 1f
        popups += Popup("+1 HEART", HEART_PINK)
        a.play(a.sPerfect, 0.7f, 1.2f)
    }

    override fun onGameOver(reason: GameOverReason) {
        // First heart death of a run: offer the ad revive instead of ending (the tidy bot skips it).
        if (reason == GameOverReason.HEARTS && !logic.revived && (autoplay == null || game.debug.chaos)) {
            state = State.REVIVE
            a.play(a.sHeart)
            a.vibrate(200)
        } else {
            finishGameOver()
        }
    }

    /**
     * Placeholder for a rewarded video ad. No ad SDK is wired up yet, so the reward is granted immediately;
     * plug the ad callback in here later.
     */
    private fun watchAd() {
        if (logic.revive()) {
            state = State.PLAYING
            healFlash = 1f
            popups += Popup("+1 HEART", HEART_PINK)
            a.play(a.sPerfect, 0.7f, 1.2f)
        }
    }

    private fun finishGameOver() {
        state = State.GAME_OVER
        gameOverTime = 0f
        world.gameOver = true
        val s = game.settings
        coinsEarned = GameLogic.coinsFor(logic.score)
        s.coins += coinsEarned
        newRecord = logic.score > s.highScore
        if (newRecord) s.highScore = logic.score
        if (logic.towerHeight > s.bestHeight) s.bestHeight = logic.towerHeight
        a.play(a.sGameOver, 0.8f)
        a.vibrate(250)
    }

    // ---- Screen ---------------------------------------------------------------------------

    override fun show() {
        Gdx.input.inputProcessor = input
    }

    override fun hide() {
        if (state == State.PLAYING) state = State.PAUSED
    }

    override fun pause() {
        if (state == State.PLAYING) state = State.PAUSED
    }

    override fun resize(width: Int, height: Int) {
        w = width.toFloat()
        h = height.toFloat()
        world.resize(width, height)
        batch.projectionMatrix.setToOrtho2D(0f, 0f, w, h)
        layout()
    }

    private fun layout() {
        val u = a.u
        val m = 40f * u
        val top = h - Gdx.graphics.safeInsetTop
        val bottom = Gdx.graphics.safeInsetBottom.toFloat()
        pauseBtn.set(w - m - 130f * u, top - m - 130f * u, 130f * u, 130f * u)
        val b = 180f * u
        rotateBtn.set(m, bottom + m, b, b)
        tipBtn.set(m + b + 30f * u, bottom + m, b, b)
        dropBtn.set(w - m - b, bottom + m, b, b)

        val bw = 600f * u
        val bh = 140f * u
        val cx = (w - bw) / 2f
        val cy = h / 2f
        resumeBtn.set(cx, cy + 120f * u, bw, bh)
        restartBtn.set(cx, cy - 50f * u, bw, bh)
        settingsBtn.set(cx, cy - 220f * u, bw, bh)
        menuBtn.set(cx, cy - 390f * u, bw, bh)
        againBtn.set(cx, cy - 290f * u, bw, bh)
        overMenuBtn.set(cx, cy - 455f * u, bw, bh)
        watchAdBtn.set(cx, cy - 210f * u, bw, bh)
        noThanksBtn.set(cx, cy - 380f * u, bw, bh)
    }

    override fun render(delta: Float) {
        val dt = min(delta, 1f / 20f)
        if (state == State.PLAYING) {
            autoplay?.update(dt)
            logic.update(dt)
        }
        if (state == State.GAME_OVER) gameOverTime += dt
        time += dt
        if (state != State.PAUSED) world.update(dt, logic.current)
        heartFlash = (heartFlash - dt * 1.5f).coerceAtLeast(0f)
        boomFlash = (boomFlash - dt * 3f).coerceAtLeast(0f)
        healFlash = (healFlash - dt * 1.5f).coerceAtLeast(0f)

        val showGhost = state == State.PLAYING
        world.render(logic.current, if (showGhost) logic.ghost() else null, dt, if (showGhost) logic.board.heights() else null)
        preview.render(logic.next)

        batch.begin()
        drawHud(dt)
        when (state) {
            State.PAUSED -> drawPause()
            State.REVIVE -> drawRevive()
            State.GAME_OVER -> drawGameOver()
            State.PLAYING -> Unit
        }
        batch.end()
    }

    private fun drawHud(dt: Float) {
        val u = a.u
        val m = 40f * u
        val top = h - Gdx.graphics.safeInsetTop

        if (boomFlash > 0f) {
            batch.setColor(1f, 0.85f, 0.6f, boomFlash * 0.45f)
            batch.draw(a.white, 0f, 0f, w, h)
            batch.color = Color.WHITE
        }
        if (heartFlash > 0f) {
            batch.setColor(1f, 0.1f, 0.15f, heartFlash * 0.28f)
            batch.draw(a.white, 0f, 0f, w, h)
            batch.color = Color.WHITE
        }

        // Hearts (top-left); the heart that was just lost pops and wobbles.
        val hs = 100f * u
        for (i in 0 until GameLogic.MAX_HEARTS) {
            val full = i < logic.hearts
            var size = hs
            var ox = 0f
            if (!full && i == logic.hearts && heartFlash > 0f) {
                size = hs * (1f + 0.35f * heartFlash)
                ox = MathUtils.sin(heartFlash * 40f) * 10f * u * heartFlash
            }
            if (full && i == logic.hearts - 1 && healFlash > 0f) size = hs * (1f + 0.45f * healFlash)
            val x = m + i * (hs + 12f * u) + (hs - size) / 2f + ox
            val y = top - m - hs + (hs - size) / 2f
            batch.draw(if (full) a.heartFull else a.heartEmpty, x, y, size, size)
        }
        text(batch, a.fontSmall, "HEIGHT ${logic.towerHeight}", m + 6f * u, top - m - hs - 45f * u, Align.left)

        // Score (top-center).
        text(batch, a.fontLarge, logic.score.toString(), w / 2f, top - m - 55f * u)
        text(batch, a.fontSmall, "BEST ${maxOf(game.settings.highScore, logic.score)}", w / 2f, top - m - 140f * u)

        // Floating score popups.
        val it = popups.iterator()
        var row = 0
        while (it.hasNext()) {
            val p = it.next()
            p.age += dt
            if (p.age > p.life) { it.remove(); continue }
            val alpha = 1f - Interpolation.pow2In.apply(p.age / p.life)
            val font = a.fontMedium
            font.setColor(p.color.r, p.color.g, p.color.b, alpha)
            text(batch, font, p.text, w / 2f, top - m - 280f * u - row * 70f * u + p.age * 40f * u)
            font.color = Color.WHITE
            row++
        }

        // Next piece (top-right, under pause).
        val ps = 210f * u
        val px = w - m - ps
        val py = pauseBtn.y - 40f * u - ps
        panel(batch, a, px, py, ps, ps, 0.35f)
        text(batch, a.fontSmall, "NEXT", px + ps / 2f, py + ps - 32f * u)
        batch.draw(preview.region, px + 10f * u, py, ps - 20f * u, ps - 20f * u)

        drawGapWarning(u, m)
        if (state == State.PLAYING) activeButtons().forEach { it.draw(batch, a) }
    }

    /** Spec §4: warns before a trailing column ends the run. */
    private fun drawGapWarning(u: Float, m: Float) {
        val gap = logic.gap
        if (state != State.PLAYING || gap < GameLogic.GAP_WARNING) return
        val left = GameLogic.GAP_LIMIT + 1 - gap
        val pulse = 0.65f + 0.35f * MathUtils.sin(time * 8f)
        val y = Gdx.graphics.safeInsetBottom + m + (if (game.settings.showButtons) 250f else 70f) * u
        val pw = 760f * u
        panel(batch, a, (w - pw) / 2f, y - 45f * u, pw, 165f * u, 0.8f)
        a.fontMedium.setColor(DANGER.r, DANGER.g, DANGER.b, pulse)
        text(batch, a.fontMedium, "FILL THE GAPS!", w / 2f, y + 70f * u)
        a.fontMedium.color = Color.WHITE
        text(batch, a.fontSmall, if (left == 1) "LAST LEVEL BEFORE YOU LOSE" else "$left LEVELS BEFORE YOU LOSE", w / 2f, y)
    }

    private fun drawPause() {
        val u = a.u
        dim(batch, a, w, h, 0.5f)
        val pw = 760f * u
        val ph = 980f * u
        panel(batch, a, (w - pw) / 2f, h / 2f - 470f * u, pw, ph)
        text(batch, a.fontLarge, "PAUSED", w / 2f, h / 2f + 370f * u)
        activeButtons().forEach { it.draw(batch, a) }
    }

    private fun drawRevive() {
        val u = a.u
        dim(batch, a, w, h, 0.55f)
        val pw = 800f * u
        val ph = 900f * u
        panel(batch, a, (w - pw) / 2f, h / 2f - 470f * u, pw, ph)
        text(batch, a.fontLarge, "OUT OF HEARTS", w / 2f, h / 2f + 330f * u)
        val hs = 190f * u
        val pulse = 1f + 0.06f * MathUtils.sin(time * 5f)
        batch.draw(a.heartFull, (w - hs * pulse) / 2f, h / 2f + 70f * u - (hs * pulse - hs) / 2f, hs * pulse, hs * pulse)
        text(batch, a.fontSmall, "WATCH AN AD TO KEEP GOING", w / 2f, h / 2f + 20f * u)
        text(batch, a.fontSmall, "WITH +1 HEART?", w / 2f, h / 2f - 40f * u)
        activeButtons().forEach { it.draw(batch, a) }
    }

    private fun drawGameOver() {
        if (gameOverTime < OVER_PANEL_DELAY) return
        val u = a.u
        val t = MathUtils.clamp((gameOverTime - OVER_PANEL_DELAY) / 0.35f, 0f, 1f)
        dim(batch, a, w, h, 0.45f * t)
        val pw = 800f * u
        val ph = 1060f * u
        val slide = (1f - Interpolation.pow3Out.apply(t)) * 200f * u
        panel(batch, a, (w - pw) / 2f, h / 2f - 520f * u - slide, pw, ph, 0.85f * t)
        val cy = h / 2f - slide
        text(batch, a.fontLarge, "GAME OVER", w / 2f, cy + 430f * u)
        a.fontSmall.color = DANGER
        val reason = if (logic.gameOverReason == GameOverReason.GAPS) "BOARD NOT FILLED" else "OUT OF HEARTS"
        text(batch, a.fontSmall, reason, w / 2f, cy + 350f * u)
        a.fontSmall.color = Color.WHITE
        text(batch, a.fontSmall, "SCORE", w / 2f, cy + 280f * u)
        text(batch, a.fontTitle, logic.score.toString(), w / 2f, cy + 185f * u)
        text(batch, a.fontMedium, "HEIGHT ${logic.towerHeight}", w / 2f, cy + 80f * u)
        // Coins earned: score / 100.
        val coinLabel = if (coinsEarned == 1) "+1 COIN" else "+$coinsEarned COINS"
        val cs = 64f * u
        glyphs.setText(a.fontMedium, coinLabel)
        val cw = cs + 14f * u + glyphs.width
        batch.draw(a.coin, w / 2f - cw / 2f, cy - 10f * u - cs / 2f, cs, cs)
        a.fontMedium.color = ACCENT
        text(batch, a.fontMedium, coinLabel, w / 2f - cw / 2f + cs + 14f * u, cy - 10f * u, Align.left)
        a.fontMedium.color = Color.WHITE
        if (newRecord) {
            val pulse = 1f + 0.06f * MathUtils.sin(gameOverTime * 6f)
            a.fontMedium.color = ACCENT
            a.fontMedium.data.setScale(pulse)
            text(batch, a.fontMedium, "NEW HIGH SCORE!", w / 2f, cy - 105f * u)
            a.fontMedium.data.setScale(1f)
            a.fontMedium.color = Color.WHITE
        }
        if (t >= 1f) activeButtons().forEach { it.draw(batch, a) }
    }

    override fun dispose() {
        world.dispose()
        preview.dispose()
        batch.dispose()
    }

    companion object {
        const val OVER_PANEL_DELAY = 1.4f
    }
}
