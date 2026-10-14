package com.stacker3d.logic

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameLogicTest {
    private class Recorder : GameLogic.Listener {
        val landings = ArrayList<LandingResult>()
        var heartsLost = 0
        var gameOver: GameOverReason? = null
        var heartsGained = 0
        override fun onHeartGained(hearts: Int) { heartsGained++ }
        override fun onLanded(result: LandingResult) { landings += result }
        override fun onHeartLost(heartsLeft: Int) { heartsLost++ }
        override fun onGameOver(reason: GameOverReason) { gameOver = reason }
    }

    private val rec = Recorder()
    private val logic = GameLogic(Random(1), rec).apply { start() }

    private fun awaitPiece(type: PieceType? = null) {
        while (logic.current == null) logic.update(0.1f)
        // Random specials would derail scripted placements, so default to a plain piece.
        val wanted = type ?: if (logic.current!!.type.kind != PieceType.Kind.NORMAL) PieceType.SINGLE else null
        if (wanted != null) logic.debugSpawn(wanted)
    }

    private fun drop(cells: List<Vec3i>, x: Int, z: Int, type: PieceType? = null) {
        awaitPiece(type)
        assertTrue(logic.debugPlace(Orientation.normalize(cells), x, z), "placement should fit")
        logic.hardDrop()
    }

    private fun single(x: Int, z: Int) = drop(listOf(v(0, 0, 0)), x, z)

    /** Fills every column up to [h] with single cubes, keeping the gap at most 1. */
    private fun fillLevel(h: Int) = repeat(h) { for (x in 0..2) for (z in 0..2) if (logic.board.height(x, z) < h) single(x, z) }

    private fun v(x: Int, y: Int, z: Int) = Vec3i(x, y, z)

    @Test
    fun `cube over a gap settles onto the cube below`() {
        single(0, 0)
        single(0, 0) // column (0,0) is now 2 tall
        drop(listOf(v(0, 0, 0), v(1, 0, 0)), 0, 0) // domino across (0,0) h=2 and (1,0) h=0

        val last = rec.landings.last()
        assertEquals(listOf(v(0, 2, 0), v(1, 2, 0)), last.placed.map { it.from })
        assertEquals(setOf(v(0, 2, 0), v(1, 0, 0)), last.placed.map { it.to }.toSet())
        assertEquals(3, logic.board.height(0, 0))
        assertEquals(1, logic.board.height(1, 0))
        assertFalse(last.perfect)
    }

    @Test
    fun `off-board cubes fall and cost exactly one heart per piece`() {
        drop(listOf(v(0, 0, 0), v(1, 0, 0), v(2, 0, 0)), 2, 0) // bar with 2 cubes past the edge
        val last = rec.landings.last()
        assertEquals(1, last.placed.size)
        assertEquals(2, last.lost.size)
        assertEquals(2, logic.hearts)
        assertEquals(1, rec.heartsLost)
    }

    @Test
    fun `three spilled pieces end the game and hearts never come back`() {
        repeat(2) { drop(listOf(v(0, 0, 0), v(1, 0, 0)), 2, 2) }
        fillLevel(3) // lots of clean placements
        assertEquals(1, logic.hearts)
        drop(listOf(v(0, 0, 0), v(1, 0, 0)), 2, 2)
        assertEquals(0, logic.hearts)
        assertEquals(GameOverReason.HEARTS, rec.gameOver)
        assertTrue(logic.isGameOver)
    }

    @Test
    fun `piece can never move completely off the board`() {
        awaitPiece(PieceType.SINGLE)
        logic.debugPlace(listOf(v(0, 0, 0)), 2, 1)
        assertFalse(logic.move(1, 0))
        assertTrue(logic.move(-1, 0))
    }

    @Test
    fun `tip stands a bar up and lays it back down`() {
        val bar = PieceType.BAR.cells
        val standing = Orientation.tip(bar)
        assertEquals(setOf(v(0, 0, 0), v(0, 1, 0), v(0, 2, 0)), standing.toSet())
        assertEquals(bar.toSet(), Orientation.tip(standing).toSet())
    }

    @Test
    fun `standing bar fills one column`() {
        drop(listOf(v(0, 0, 0), v(0, 1, 0), v(0, 2, 0)), 1, 1)
        assertEquals(3, logic.board.height(1, 1))
        assertEquals(3, logic.towerHeight)
        assertTrue(rec.landings.last().perfect)
    }

    @Test
    fun `scoring counts cubes, new layers and perfect bonus`() {
        drop(listOf(v(0, 0, 0), v(1, 0, 0)), 0, 0)
        // 2 cubes * 10 + 1 new layer * 50 + perfect 25
        assertEquals(95, logic.score)
    }

    @Test
    fun `ghost shows settled positions`() {
        single(0, 0)
        awaitPiece(PieceType.DOMINO)
        logic.debugPlace(listOf(v(0, 0, 0), v(1, 0, 0)), 0, 0)
        val ghost = logic.ghost()!!
        assertEquals(setOf(v(0, 1, 0), v(1, 0, 0)), ghost.cells.toSet())
        assertEquals(0, ghost.lostCount)
    }

    @Test
    fun `bomb clears nearby cubes, drops the rest and never costs a heart`() {
        fillLevel(4) // every column 4 tall
        drop(listOf(v(0, 0, 0)), 0, 0, PieceType.BOMB) // bomb sits at (0,4,0)

        val last = rec.landings.last()
        assertEquals(v(0, 4, 0), last.impact)
        // Columns within one cell of (0,0) lose layer 3 (the blast reaches 1 below the bomb).
        assertEquals(4, last.cleared.size)
        for ((x, z) in listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1)) assertEquals(3, logic.board.height(x, z))
        assertEquals(4, logic.board.height(2, 2))
        assertEquals(3, logic.hearts)
        assertEquals(4, logic.towerHeight)
    }

    @Test
    fun `bomb lets cubes above the blast drop down`() {
        fillLevel(2)
        repeat(4) { single(2, 2) } // (2,2) is 6 tall
        drop(listOf(v(0, 0, 0)), 1, 1, PieceType.BOMB) // bomb at (1,2,1): clears layers 1..3 next to it

        val last = rec.landings.last()
        assertEquals(3, logic.board.height(2, 2)) // layers 1..3 gone, 4..5 dropped to 1..2
        assertTrue(last.shifted.contains(PlacedCube(v(2, 5, 2), v(2, 2, 2))))
        assertEquals(1, logic.board.height(1, 1))
    }

    private val xDomino get() = PieceType.X_DOMINO.cells // X cube is index 1, at x+1

    @Test
    fun `x cube on the board costs a heart and never joins the tower`() {
        drop(xDomino, 0, 1, PieceType.X_DOMINO) // plain cube at (0,1), X at (1,1)
        val last = rec.landings.last()
        assertTrue(last.xHit)
        assertEquals(2, logic.hearts)
        assertEquals(1, logic.board.height(0, 1))
        assertEquals(0, logic.board.height(1, 1))
        assertEquals(PieceType.DOMINO, logic.board.typeAt(0, 0, 1))
    }

    @Test
    fun `x cube hung off the edge is safe and the rest stacks`() {
        drop(xDomino, 2, 1, PieceType.X_DOMINO) // plain cube at (2,1), X at (3,1) off the board
        val last = rec.landings.last()
        assertTrue(last.dodged)
        assertEquals(3, logic.hearts)
        assertEquals(1, logic.board.height(2, 1))
        assertTrue(logic.score >= GameLogic.DODGE_BONUS)
    }

    @Test
    fun `x bar needs its plain cubes on the board too`() {
        // Cells at x=2 (plain, on), x=3 (plain, off), x=4 (X, off).
        drop(PieceType.X_BAR.cells, 2, 0, PieceType.X_BAR)
        val last = rec.landings.last()
        assertTrue(last.dodged)
        assertEquals(1, last.lost.size)
        assertEquals(2, logic.hearts) // one heart for the spilled plain cube
    }

    @Test
    fun `x pieces can't leave the board entirely`() {
        awaitPiece(PieceType.X_DOMINO)
        logic.debugPlace(xDomino, 2, 1)
        assertFalse(logic.move(1, 0))
    }

    @Test
    fun `x marker follows the piece through rotations`() {
        awaitPiece(PieceType.X_BAR)
        logic.debugPlace(PieceType.X_BAR.cells, 0, 1)
        repeat(3) { logic.rotate() }
        logic.tip()
        val c = logic.current!!.cells
        fun dist(a: Vec3i, b: Vec3i) = kotlin.math.abs(a.x - b.x) + kotlin.math.abs(a.y - b.y) + kotlin.math.abs(a.z - b.z)
        // The X cube is still the far end of the bar: two cells from cell 0, next to the middle cube.
        assertEquals(2, dist(c[0], c[PieceType.X_BAR.xIndex]))
        assertEquals(1, dist(c[1], c[PieceType.X_BAR.xIndex]))
    }

    @Test
    fun `game speeds up as the score climbs`() {
        val start = logic.fallInterval
        fillLevel(4)
        assertTrue(logic.fallInterval < start)
        assertTrue(logic.fallInterval >= GameLogic.MIN_FALL_INTERVAL)
    }

    @Test
    fun `game is lost when a cell falls more than 10 levels behind`() {
        val bar = listOf(v(0, 0, 0), v(0, 1, 0), v(0, 2, 0))
        repeat(3) { drop(bar, 0, 0) } // 9 tall, other cells empty
        assertNull(rec.gameOver)
        single(0, 0) // 10 tall: still allowed
        assertNull(rec.gameOver)
        single(0, 0) // 11 tall with empty cells: lost
        assertEquals(GameOverReason.GAPS, rec.gameOver)
        assertEquals(3, logic.hearts)
    }

    @Test
    fun `specials never come from the bag`() {
        val bag = PieceBag(Random(5))
        repeat(200) { assertEquals(PieceType.Kind.NORMAL, bag.next().kind) }
    }

    @Test
    fun `heart block restores a heart and stays in the tower`() {
        drop(listOf(v(0, 0, 0), v(1, 0, 0)), 2, 2) // spill: 2 hearts left
        drop(listOf(v(0, 0, 0)), 1, 1, PieceType.HEART)
        assertTrue(rec.landings.last().healed)
        assertEquals(3, logic.hearts)
        assertEquals(PieceType.HEART, logic.board.typeAt(1, 0, 1))
    }

    @Test
    fun `heart block at full hearts does nothing extra`() {
        drop(listOf(v(0, 0, 0)), 1, 1, PieceType.HEART)
        assertFalse(rec.landings.last().healed)
        assertEquals(3, logic.hearts)
        assertEquals(0, rec.heartsGained)
    }

    @Test
    fun `heart block falling off the board costs nothing`() {
        awaitPiece(PieceType.HEART)
        assertFalse(logic.move(5, 0)) // can't leave the board entirely
        logic.debugPlace(listOf(v(0, 0, 0)), 1, 1)
        logic.hardDrop()
        assertEquals(3, logic.hearts)
    }

    @Test
    fun `heart blocks only spawn once unlocked`() {
        fun count(chance: Float): Int {
            val l = GameLogic(Random(9)).apply { heartBlockChance = chance; start() }
            var hearts = 0
            repeat(300) {
                while (l.current == null) l.update(0.1f)
                if (l.current!!.type == PieceType.HEART) hearts++
                if (l.current!!.type.kind == PieceType.Kind.X) l.debugSpawn(PieceType.SINGLE)
                // Keep the board level so the gap rule never ends the run.
                val col = (0 until 9).minBy { l.board.height(it % 3, it / 3) }
                l.debugPlace(listOf(v(0, 0, 0)), col % 3, col / 3) || l.debugPlace(l.current!!.cells, -1, 1)
                l.hardDrop()
                if (l.isGameOver) l.start()
            }
            return hearts
        }
        assertEquals(0, count(0f))
        assertTrue(count(GameLogic.HEART_BLOCK_CHANCE * 20) > 0)
    }

    @Test
    fun `revive gives one heart once per run, only for heart deaths`() {
        repeat(3) { drop(listOf(v(0, 0, 0), v(1, 0, 0)), 2, 2) }
        assertEquals(GameOverReason.HEARTS, rec.gameOver)
        assertTrue(logic.revive())
        assertFalse(logic.isGameOver)
        assertEquals(1, logic.hearts)
        drop(listOf(v(0, 0, 0), v(1, 0, 0)), 2, 2)
        assertTrue(logic.isGameOver)
        assertFalse(logic.revive())
    }

    @Test
    fun `coins are score divided by 100`() {
        assertEquals(12, GameLogic.coinsFor(1200))
        assertEquals(12, GameLogic.coinsFor(1299))
        assertEquals(0, GameLogic.coinsFor(99))
    }
}
