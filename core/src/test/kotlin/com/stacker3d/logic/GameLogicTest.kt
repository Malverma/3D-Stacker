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
        val unlocks = ArrayList<List<PieceType>>()
        override fun onPiecesUnlocked(types: List<PieceType>) { unlocks += types }
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

    private fun GameLogic.awaitAndDrop(type: PieceType, cells: List<Vec3i>, x: Int, z: Int) {
        while (current == null) update(0.1f)
        debugSpawn(type)
        assertTrue(debugPlace(Orientation.normalize(cells), x, z))
        hardDrop()
    }

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
        assertTrue(last.shifted.any { it.from == v(2, 5, 2) && it.to == v(2, 2, 2) })
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
    fun `game is lost when a cell falls 10 levels behind`() {
        val bar = listOf(v(0, 0, 0), v(0, 1, 0), v(0, 2, 0))
        repeat(3) { drop(bar, 0, 0) } // 9 tall, other cells empty: still allowed
        assertNull(rec.gameOver)
        single(0, 0) // 10 tall with empty cells: lost
        assertEquals(GameOverReason.GAPS, rec.gameOver)
        assertEquals(3, logic.hearts)
    }

    @Test
    fun `gap continue fills trailing columns to 3 levels of grace, once per run`() {
        val bar = listOf(v(0, 0, 0), v(0, 1, 0), v(0, 2, 0))
        repeat(3) { drop(bar, 0, 0) }
        single(0, 0) // 10 tall, the rest empty
        assertFalse(logic.revive()) // the hearts revive doesn't apply
        assertTrue(logic.canReviveGaps)
        val added = logic.reviveGaps()!!
        assertFalse(logic.isGameOver)
        assertEquals(8 * 3, added.size)
        assertEquals(GameLogic.GAP_LIMIT + 1 - GameLogic.GAP_GRACE, logic.gap)
        assertEquals(10, logic.board.height(0, 0))
        // Bottom-up within each column.
        assertEquals(listOf(0, 1, 2), added.filter { it.x == 1 && it.z == 0 }.map { it.y })
        drop(bar, 0, 0) // 13 vs 3: gap 10 again
        assertTrue(logic.isGameOver)
        assertFalse(logic.canReviveGaps)
        assertNull(logic.reviveGaps())
    }

    @Test
    fun `large pieces join the bag only once the tower is tall enough`() {
        val bag = PieceBag(Random(5))
        repeat(200) { assertTrue(bag.next(0).unlockHeight == 0) }
        val seen = HashSet<PieceType>()
        repeat(400) { seen += bag.next(50).also { assertTrue(it.unlockHeight <= 50) } }
        assertTrue(seen.containsAll(PieceType.largePieces))
        for (t in PieceType.largePieces) for (axis in listOf<(Vec3i) -> Int>({ it.x }, { it.y }, { it.z })) {
            assertTrue(Orientation.extent(t.cells, axis) <= PieceSpec.MAX_LENGTH, "$t")
        }
    }

    @Test
    fun `reaching height 10 unlocks the first large pieces`() {
        fillLevel(9)
        assertTrue(rec.unlocks.isEmpty())
        fillLevel(10)
        assertEquals(listOf(listOf(PieceType.BIG_CORNER, PieceType.U_PIECE)), rec.unlocks)
    }

    @Test
    fun `height milestones come every 10 layers and pay more each time`() {
        assertEquals(0, GameLogic.milestoneAt(9))
        assertEquals(20, GameLogic.milestoneAt(27))
        assertEquals(listOf(10, 20, 30), GameLogic.milestonesBetween(0, 30))
        assertEquals(listOf(30), GameLogic.milestonesBetween(20, 34))
        assertEquals(emptyList(), GameLogic.milestonesBetween(30, 30))
        assertEquals(listOf(25, 50, 75, 250), listOf(10, 20, 30, 100).map(GameLogic::milestoneCoins))
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
    fun `shop specials only spawn once unlocked`() {
        fun count(type: PieceType, chance: Float): Int {
            val l = GameLogic(Random(9)).apply { specialChances = if (chance > 0f) mapOf(type to chance) else emptyMap(); start() }
            var seen = 0
            repeat(300) {
                while (l.current == null) l.update(0.1f)
                if (l.current!!.type == type) seen++
                if (l.current!!.type.kind == PieceType.Kind.X) l.debugSpawn(PieceType.SINGLE)
                // Keep the board level so the gap rule never ends the run.
                val col = (0 until 9).minBy { l.board.height(it % 3, it / 3) }
                l.debugPlace(listOf(v(0, 0, 0)), col % 3, col / 3) || l.debugPlace(l.current!!.cells, -1, 1)
                l.hardDrop()
                if (l.isGameOver) l.start()
            }
            return seen
        }
        for (type in PieceType.unlockables) {
            assertEquals(0, count(type, 0f), "$type locked")
            assertTrue(count(type, 0.2f) > 0, "$type unlocked")
        }
    }

    @Test
    fun `golden block pays coins only when placed on the board`() {
        drop(listOf(v(0, 0, 0)), 1, 1, PieceType.GOLD)
        assertEquals(GameLogic.GOLD_COINS, rec.landings.last().coins)
        assertEquals(GameLogic.GOLD_COINS, logic.bonusCoins)
        assertEquals(PieceType.GOLD, logic.board.typeAt(1, 0, 1))
    }

    @Test
    fun `multiplier block raises the score multiplier for later landings`() {
        drop(listOf(v(0, 0, 0)), 0, 0, PieceType.MULTIPLIER)
        // Its own landing still scores at x1.0: 10 + 50 new layer + 25 perfect.
        assertEquals(85, logic.score)
        assertEquals(11, logic.multiplierTenths)
        drop(listOf(v(0, 0, 0)), 1, 0, PieceType.MULTIPLIER)
        assertEquals(12, logic.multiplierTenths)
        // Single on an empty cell: (10 + 25 perfect) * 1.2 = 42.
        single(2, 2)
        assertEquals(42, rec.landings.last().points)
    }

    @Test
    fun `multiplier resets with a new run`() {
        drop(listOf(v(0, 0, 0)), 0, 0, PieceType.MULTIPLIER)
        drop(listOf(v(0, 0, 0)), 1, 1, PieceType.GOLD)
        logic.start()
        assertEquals(10, logic.multiplierTenths)
        assertEquals(0, logic.bonusCoins)
    }

    @Test
    fun `cracked block crumbles 10 to 15 layers down and the column drops`() {
        drop(listOf(v(0, 0, 0)), 0, 0, PieceType.CRACKED)
        // 10 per cube + 40 cracked bonus + 50 new layer + 25 perfect.
        assertEquals(125, rec.landings.last().points)
        val fuse = logic.board.fuseAt(0, 0, 0)
        assertTrue(fuse in GameLogic.CRACK_MIN_LAYERS..GameLogic.CRACK_MAX_LAYERS)

        fillLevel(fuse) // tower top is now `fuse` tall: fuse - 1 layers above the cracked cube
        assertTrue(rec.landings.none { it.breaks.isNotEmpty() })
        assertEquals(PieceType.CRACKED, logic.board.typeAt(0, 0, 0))

        single(1, 1) // one more layer on another column breaks it
        assertEquals(listOf(v(0, 0, 0)), rec.landings.last().breaks.map { it.at })
        assertEquals(fuse - 1, logic.board.height(0, 0))
        assertTrue((0 until logic.board.height(0, 0)).none { logic.board.typeAt(0, it, 0) == PieceType.CRACKED })
        assertEquals(3, logic.hearts)
    }

    @Test
    fun `dynamite blows up its whole layer after its fuse burns down`() {
        // Layer 0 full except (1,1); a second layer on (0,0) so we can see cubes drop.
        for (x in 0..2) for (z in 0..2) if (x != 1 || z != 1) single(x, z)
        single(0, 0)
        drop(listOf(v(0, 0, 0)), 1, 1, PieceType.DYNAMITE) // sits at layer 0
        val fuse = logic.board.fuseAt(1, 0, 1)
        assertTrue(fuse in GameLogic.DYNAMITE_MIN_FUSE..GameLogic.DYNAMITE_MAX_FUSE, "fuse $fuse")
        assertEquals(listOf(fuse), logic.dynamiteFuses())
        repeat(fuse - 1) { single(2, 2) }
        assertTrue(rec.landings.none { it.breaks.isNotEmpty() })
        assertEquals(listOf(1), logic.dynamiteFuses())

        single(2, 2) // the last landing sets it off
        val last = rec.landings.last()
        assertEquals(9, last.breaks.size)
        assertTrue(last.breaks.all { it.cause == BreakCause.DYNAMITE && it.at.y == 0 })
        assertEquals(1, logic.board.height(0, 0)) // its second cube dropped into layer 0
        assertEquals(0, logic.board.height(1, 1))
        assertTrue(logic.dynamiteFuses().isEmpty())
        assertEquals(3, logic.hearts)
    }

    @Test
    fun `glass survives a flush landing but shatters when a cube drops onto it`() {
        drop(listOf(v(0, 0, 0)), 0, 0, PieceType.GLASS)
        // 10 per cube + 60 glass bonus + 50 new layer + 25 perfect.
        assertEquals(145, rec.landings.last().points)

        single(0, 0) // lands flush on it: safe
        assertEquals(PieceType.GLASS, logic.board.typeAt(0, 0, 0))
        assertTrue(rec.landings.last().breaks.isEmpty())

        // A domino blocked by column (1,0) (2 tall) drops its other cube onto glass at (2,0).
        drop(listOf(v(0, 0, 0)), 1, 0)
        drop(listOf(v(0, 0, 0)), 1, 0)
        drop(listOf(v(0, 0, 0)), 2, 0, PieceType.GLASS)
        drop(listOf(v(0, 0, 0), v(1, 0, 0)), 1, 0)
        val last = rec.landings.last()
        assertEquals(listOf(Break(v(2, 0, 0), PieceType.GLASS, BreakCause.SHATTER)), last.breaks)
        assertEquals(1, logic.board.height(2, 0))
        assertTrue(logic.board.typeAt(2, 0, 0) != PieceType.GLASS)
        assertEquals(10, last.points) // 20 for the cubes + 50 new layer - 60 glass bonus
    }

    @Test
    fun `freeze block slows the next five pieces back to the starting speed`() {
        fillLevel(6) // fast enough to be below the starting speed
        val fast = logic.fallInterval
        assertTrue(fast < GameLogic.START_FALL_INTERVAL)
        drop(listOf(v(0, 0, 0)), 1, 1, PieceType.FREEZE)
        assertTrue(rec.landings.last().froze)
        repeat(GameLogic.FREEZE_PIECES) {
            assertEquals(GameLogic.START_FALL_INTERVAL, logic.fallInterval)
            single(it % 3, 0)
        }
        assertEquals(0, logic.frozenPieces)
        assertTrue(logic.fallInterval < GameLogic.START_FALL_INTERVAL)
    }

    @Test
    fun `bombed cracked cubes are gone for good`() {
        drop(listOf(v(0, 0, 0)), 0, 0, PieceType.CRACKED)
        drop(listOf(v(0, 0, 0)), 0, 0, PieceType.BOMB)
        assertEquals(0, logic.board.height(0, 0))
        fillLevel(GameLogic.CRACK_MAX_LAYERS + 1)
        assertTrue(rec.landings.none { it.breaks.isNotEmpty() })
    }

    @Test
    fun `mystery block can turn into every effect`() {
        val seen = HashSet<MysteryEffect>()
        for (seed in 0 until 80) {
            val r = Recorder()
            val l = GameLogic(Random(seed), r).apply { start() }
            while (l.current == null) l.update(0.1f)
            l.debugSpawn(PieceType.MYSTERY)
            l.debugPlace(listOf(v(0, 0, 0)), 1, 1)
            l.hardDrop()
            val result = r.landings.last()
            val effect = result.mystery!!
            seen += effect
            when (effect) {
                MysteryEffect.GOLD_RUSH -> {
                    assertEquals(GameLogic.MYSTERY_GOLD_COINS, l.bonusCoins)
                    assertEquals(PieceType.GOLD, l.board.typeAt(1, 0, 1))
                }
                MysteryEffect.MULTIPLY -> assertEquals(10 + GameLogic.MYSTERY_MULTIPLIER_STEP, l.multiplierTenths)
                MysteryEffect.BOMB -> {
                    assertTrue(result.exploded)
                    assertFalse(result.perfect)
                }
                MysteryEffect.CRACK -> assertTrue(l.board.fuseAt(1, 0, 1) > 0)
                MysteryEffect.CURSE -> {
                    assertEquals(2, l.hearts)
                    assertEquals(0, l.board.height(1, 1))
                }
                MysteryEffect.HEAL -> error("can't heal at full hearts")
            }
        }
        assertEquals(MysteryEffect.entries.toSet() - MysteryEffect.HEAL, seen)
    }

    @Test
    fun `mystery block can heal when a heart is missing`() {
        var healed = false
        for (seed in 0 until 80) {
            val r = Recorder()
            val l = GameLogic(Random(seed), r).apply { start() }
            l.awaitAndDrop(PieceType.DOMINO, listOf(v(0, 0, 0), v(1, 0, 0)), 2, 2) // spill: 2 hearts left
            l.awaitAndDrop(PieceType.MYSTERY, listOf(v(0, 0, 0)), 1, 1)
            if (r.landings.last().mystery == MysteryEffect.HEAL) {
                assertEquals(3, l.hearts)
                healed = true
            }
        }
        assertTrue(healed)
    }

    @Test
    fun `attached specials sit beside a regular piece and are never 4 long`() {
        val random = Random(4)
        for (base in PieceType.regularsAt(Int.MAX_VALUE)) for (special in PieceType.unlockables) repeat(20) {
            val spec = PieceSpec.attached(base, special, random)
            assertEquals(base.cells.size + 1, spec.cells.size)
            assertEquals(spec.cells.size, spec.cells.toSet().size, "no overlapping cubes")
            for (axis in listOf<(Vec3i) -> Int>({ it.x }, { it.y }, { it.z })) {
                assertTrue(Orientation.extent(spec.cells, axis) <= PieceSpec.MAX_LENGTH, "$base + $special: ${spec.cells}")
            }
            val extra = spec.cells.last()
            assertTrue(spec.cells.dropLast(1).any { (it.x - extra.x).let { d -> d * d } + (it.z - extra.z).let { d -> d * d } == 1 })
            assertEquals(special, spec.cellType(spec.specialIndex))
            assertEquals(base, spec.cellType(0))
        }
    }

    @Test
    fun `shop specials also spawn attached to regular pieces`() {
        val l = GameLogic(Random(5)).apply { specialChances = mapOf(PieceType.GOLD to 0.3f); start() }
        var lone = 0
        var attached = 0
        repeat(400) {
            val next = l.next
            if (next.special == PieceType.GOLD) attached++
            if (next.type == PieceType.GOLD) lone++
            while (l.current == null) l.update(0.1f)
            l.debugSpawn(PieceType.SINGLE)
            val col = (0 until 9).minBy { l.board.height(it % 3, it / 3) }
            l.debugPlace(listOf(v(0, 0, 0)), col % 3, col / 3)
            l.hardDrop()
            if (l.isGameOver) l.start()
        }
        assertTrue(lone > 0 && attached > 0, "lone=$lone attached=$attached")
    }

    private fun dominoWith(special: PieceType) = PieceSpec(PieceType.DOMINO, listOf(v(0, 0, 0), v(1, 0, 0), v(2, 0, 0)), special)

    private fun dropSpec(spec: PieceSpec, x: Int, z: Int) {
        while (logic.current == null) logic.update(0.1f)
        logic.debugSpawn(spec)
        assertTrue(logic.debugPlace(spec.cells, x, z))
        logic.hardDrop()
    }

    @Test
    fun `attached golden cube pays and stacks with its piece`() {
        dropSpec(dominoWith(PieceType.GOLD), 0, 0)
        val last = rec.landings.last()
        assertEquals(GameLogic.GOLD_COINS, last.coins)
        assertEquals(listOf(PieceType.DOMINO, PieceType.DOMINO, PieceType.GOLD), (0..2).map { logic.board.typeAt(it, 0, 0) })
        // 3 cubes + new layer + perfect.
        assertEquals(30 + 50 + 25, last.points)
    }

    @Test
    fun `attached special hanging off the board is lost and costs a heart`() {
        dropSpec(dominoWith(PieceType.HEART), 1, 0) // heart cube at x = 3, off the board
        val last = rec.landings.last()
        assertEquals(listOf(LostCube(v(3, 0, 0), PieceType.HEART)), last.lost)
        assertFalse(last.healed)
        assertEquals(2, logic.hearts)
    }

    @Test
    fun `attached cracked cube gets a fuse and its bonus`() {
        dropSpec(dominoWith(PieceType.CRACKED), 0, 0)
        assertTrue(logic.board.fuseAt(2, 0, 0) in GameLogic.CRACK_MIN_LAYERS..GameLogic.CRACK_MAX_LAYERS)
        assertEquals(0, logic.board.fuseAt(0, 0, 0))
        assertEquals(30 + GameLogic.CRACKED_BONUS + 50 + 25, rec.landings.last().points)
    }

    @Test
    fun `attached mystery cube acts after its piece stacks`() {
        val seen = HashSet<MysteryEffect>()
        for (seed in 0 until 80) {
            val r = Recorder()
            val l = GameLogic(Random(seed), r).apply { start() }
            while (l.current == null) l.update(0.1f)
            val spec = dominoWith(PieceType.MYSTERY)
            l.debugSpawn(spec)
            l.debugPlace(spec.cells, 0, 1)
            l.hardDrop()
            val result = r.landings.last()
            seen += result.mystery!!
            when (result.mystery) {
                MysteryEffect.BOMB -> {
                    // The domino stacked, then the blast took the cube next to the bomb; the far one survives.
                    assertEquals(2, result.placed.size)
                    assertTrue(result.exploded)
                    assertEquals(v(2, 0, 1), result.impact)
                    assertEquals(listOf(v(1, 0, 1)), result.cleared.map { it.first })
                    assertEquals(1, l.board.height(0, 1))
                    assertEquals(3, l.hearts)
                }
                MysteryEffect.CURSE -> {
                    assertEquals(2, result.placed.size)
                    assertEquals(2, l.hearts)
                    assertEquals(0, l.board.height(2, 1))
                }
                else -> assertEquals(3, result.placed.size)
            }
        }
        assertEquals(MysteryEffect.entries.toSet() - MysteryEffect.HEAL, seen)
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

    @Test
    fun `no fall mode grows the board instead of dropping overhanging cubes`() {
        logic.noFall = true
        drop(listOf(v(0, 0, 0), v(1, 0, 0)), 2, 0) // second cube hangs over x = 3
        val r = rec.landings.last()
        assertTrue(r.lost.isEmpty())
        assertEquals(listOf(v(3, 0, 0)), r.grown)
        assertTrue(logic.board.inside(3, 0))
        assertEquals(1, logic.board.height(3, 0))
        assertEquals(3, logic.hearts)
        // The new cell holds up the next piece like any other.
        drop(listOf(v(0, 0, 0)), 3, 0)
        assertEquals(2, logic.board.height(3, 0))
    }

    @Test
    fun `no fall hanging cubes stay at their level with air underneath`() {
        logic.noFall = true
        val bar = listOf(v(0, 0, 0), v(0, 1, 0), v(0, 2, 0))
        drop(bar, 2, 0) // column (2, 0) is 3 tall
        awaitPiece()
        val domino = Orientation.normalize(listOf(v(0, 0, 0), v(1, 0, 0)))
        val preview = logic.previewDrop(domino, 2, 0)!!
        assertEquals(0, preview.lostCount)
        assertTrue(v(3, 3, 0) in preview.cells)
        drop(domino, 2, 0) // lands on top at layer 3, second cube hangs over x = 3 at layer 3
        assertEquals(listOf(v(3, 3, 0)), rec.landings.last().grown)
        assertEquals(3, logic.board.base(3, 0))
        assertEquals(4, logic.board.height(3, 0))
        // The next cube over that cell lands on the raised one, not on the ground.
        drop(listOf(v(0, 0, 0)), 3, 0)
        assertEquals(5, logic.board.height(3, 0))
        assertEquals(v(3, 4, 0), rec.landings.last().placed.single().to)
    }

    @Test
    fun `no fall mode grows below zero`() {
        logic.noFall = true
        drop(listOf(v(0, 0, 0), v(1, 0, 0)), -1, 0)
        assertTrue(logic.board.inside(-1, 0))
        assertEquals(1, logic.board.height(-1, 0))
        assertEquals(-1, logic.board.minX)
    }

    @Test
    fun `no fall mode keeps the 10 level gap rule`() {
        logic.noFall = true
        val bar = listOf(v(0, 0, 0), v(0, 1, 0), v(0, 2, 0))
        repeat(3) { drop(bar, 0, 0) }
        assertNull(rec.gameOver)
        single(0, 0)
        assertEquals(GameOverReason.GAPS, rec.gameOver)
    }

    @Test
    fun `classic mode still drops overhanging cubes`() {
        drop(listOf(v(0, 0, 0), v(1, 0, 0)), 2, 0)
        assertEquals(1, rec.landings.last().lost.size)
        assertFalse(logic.board.inside(3, 0))
    }

    @Test
    fun `huge pieces come only in no fall mode, tier by tier`() {
        val classic = PieceBag(Random(5))
        repeat(500) { assertFalse(classic.next(1000).noFallOnly) }
        val bag = PieceBag(Random(5)).apply { noFall = true }
        repeat(300) { assertFalse(bag.next(24).noFallOnly) }
        val seen = HashSet<PieceType>()
        repeat(600) { seen += bag.next(150) }
        assertTrue(seen.containsAll(PieceType.hugePieces))
        assertEquals(listOf(25, 50, 75, 100, 125, 150), PieceType.hugePieces.map { it.unlockHeight }.distinct())
        // Each tier is at least as big as the one before, and every huge piece is longer than the 3×3.
        val tiers = PieceType.hugePieces.groupBy { it.unlockHeight }.values.map { t -> t.sumOf { it.cells.size } }
        assertEquals(tiers.sorted(), tiers)
        for (t in PieceType.hugePieces) {
            assertTrue(maxOf(Orientation.extent(t.cells) { it.x }, Orientation.extent(t.cells) { it.z }) > 3, "$t")
            assertEquals(t.cells.size, t.cells.toSet().size)
        }
    }

    @Test
    fun `no fall mode unlocks huge pieces as the tower climbs`() {
        logic.noFall = true
        fillLevel(25)
        assertTrue(rec.unlocks.flatten().containsAll(PieceType.hugePieces.filter { it.unlockHeight == 25 }))
        assertTrue(rec.unlocks.flatten().none { it.noFallOnly && it.unlockHeight > 25 })
    }

    @Test
    fun `specials attach to huge pieces without making them longer`() {
        val random = Random(4)
        for (base in PieceType.hugePieces) repeat(20) {
            val spec = PieceSpec.attached(base, PieceType.GOLD, random)
            assertEquals(base.cells.size + 1, spec.cells.size, "$base")
            val longest = maxOf(Orientation.extent(base.cells) { it.x }, Orientation.extent(base.cells) { it.z })
            assertTrue(Orientation.extent(spec.cells) { it.x } <= longest)
            assertTrue(Orientation.extent(spec.cells) { it.z } <= longest)
        }
    }
}
