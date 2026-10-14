package com.stacker3d

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
                val col = h.indices.maxBy { h[it] }
                logic.debugPlace(piece.cells, col % 3, col / 3)
                return
            }
            PieceType.Kind.NORMAL, PieceType.Kind.HEART, PieceType.Kind.X -> Unit
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
        for (cells in orientations) for (x in -2..3) for (z in -2..3) {
            if (!logic.fits(cells, x, piece.y, z)) continue
            val r = logic.previewDrop(cells, x, z, piece.type.xIndex) ?: continue
            val score = if (chaos) random.nextFloat() else (r.lostCount + if (r.xOnBoard) 1 else 0) * 1000f +
                (r.heightsAfter.max() - r.heightsAfter.min()) * 4f + r.heightsAfter.max() + random.nextFloat()
            if (score < bestScore) {
                bestScore = score
                best = Triple(cells, x, z)
            }
        }
        best?.let { (cells, x, z) -> logic.debugPlace(cells, x, z) }
    }
}
