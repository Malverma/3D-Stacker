package com.stacker3d

import com.stacker3d.logic.Board
import com.stacker3d.logic.GameLogic
import com.stacker3d.logic.Orientation
import com.stacker3d.logic.PieceType
import com.stacker3d.logic.Vec3i
import kotlin.random.Random

/** Desktop testing bot: picks a placement that keeps the tower level, then hard-drops. */
class Autoplay(
    private val logic: GameLogic,
    private val delay: Float,
    /** Place pieces at random instead, to exercise spills, hearts and game over. */
    private val chaos: Boolean = false,
    private val random: Random = Random(3),
) {
    private var timer = 0f
    private var planned = false

    fun update(dt: Float) {
        if (logic.current == null) {
            planned = false
            return
        }
        if (!planned) {
            plan()
            planned = true
            timer = 0f
        }
        timer += dt
        if (timer >= delay) {
            logic.hardDrop()
            planned = false
        }
    }

    private fun plan() {
        val piece = logic.current ?: return
        when (piece.type.kind) {
            PieceType.Kind.BOMB -> if (!chaos) {
                // Blow up the tallest column to keep the tower level.
                val h = logic.board.heights()
                val col = logic.board.cells.maxBy { h[it] }
                logic.debugPlace(piece.cells, Board.xOf(col), Board.zOf(col))
                return
            }
            else -> Unit
        }
        // Keyed by cell order, which matters for X pieces (the X cube is a fixed index).
        val orientations = LinkedHashSet<List<Vec3i>>()
        var yawed = piece.cells
        repeat(4) {
            var tipped = yawed
            repeat(3) {
                orientations += tipped
                tipped = Orientation.tip(tipped)
            }
            yawed = Orientation.yaw(yawed)
        }
        var best: Triple<List<Vec3i>, Int, Int>? = null
        var bestScore = Float.MAX_VALUE
        val b = logic.board
        for (cells in orientations) for (x in b.minX - 2..b.maxX + 1) for (z in b.minZ - 2..b.maxZ + 1) {
            if (!logic.fits(cells, x, piece.y, z)) continue
            val r = logic.previewDrop(cells, x, z, piece.type.xIndex) ?: continue
            val score = if (chaos) random.nextFloat() else (r.lostCount + if (r.xOnBoard) 1 else 0) * 1000f +
                spread(r.heightsAfter) * 4f + b.cells.maxOf { r.heightsAfter[it] } + random.nextFloat()
            if (score < bestScore) {
                bestScore = score
                best = Triple(cells, x, z)
            }
        }
        best?.let { (cells, x, z) -> logic.debugPlace(cells, x, z) }
    }

    /** Tallest minus shortest column over the board cells (cells it would grow are part of it too). */
    private fun spread(h: IntArray): Int {
        var lo = Int.MAX_VALUE
        var hi = 0
        for (i in logic.board.cells) { lo = minOf(lo, h[i]); hi = maxOf(hi, h[i]) }
        return hi - lo
    }
}
