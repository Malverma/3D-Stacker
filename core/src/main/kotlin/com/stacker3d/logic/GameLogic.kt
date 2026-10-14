package com.stacker3d.logic

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/** A cube that locked into the tower: where it started ([from]) and where it settled ([to]). */
data class PlacedCube(val from: Vec3i, val to: Vec3i)

data class LandingResult(
    val type: PieceType,
    /** Piece origin at the moment it landed. */
    val origin: Vec3i,
    val placed: List<PlacedCube>,
    /** Cubes that left the board: off-board cubes, or a dodged X block. */
    val lost: List<Vec3i>,
    val perfect: Boolean,
    val points: Int,
    /** Bomb: blast center. X piece: where the X cube shatters on the board. */
    val impact: Vec3i? = null,
    /** Bomb: cubes destroyed by the blast (old positions). */
    val cleared: List<Pair<Vec3i, PieceType>> = emptyList(),
    /** Bomb: surviving cubes that dropped to fill the hole. */
    val shifted: List<PlacedCube> = emptyList(),
    /** X piece: the X cube's cell when the piece landed. */
    val xCell: Vec3i? = null,
    /** X cube landed on the board. */
    val xHit: Boolean = false,
    /** X cube hung off the edge and fell away safely. */
    val dodged: Boolean = false,
    /** Heart block landed on the board and restored a heart. */
    val healed: Boolean = false,
)

/** Where each cube of a piece would end up if dropped now. */
data class DropPreview(
    val cells: List<Vec3i>,
    val onBoard: List<Boolean>,
    /** Plain cubes that would fall off the board. */
    val lostCount: Int,
    val heightsAfter: IntArray,
    /** X piece: the X cube would land on the board. */
    val xOnBoard: Boolean = false,
)

enum class GameOverReason { HEARTS, GAPS }

/** All rules from spec §3–§8. Rendering-free so it can be unit tested. */
class GameLogic(
    private val random: Random = Random.Default,
    private val listener: Listener = object : Listener {},
) {
    interface Listener {
        fun onSpawn(piece: Piece) {}
        fun onLanded(result: LandingResult) {}
        fun onHeartLost(heartsLeft: Int) {}
        fun onHeartGained(hearts: Int) {}
        fun onGameOver(reason: GameOverReason) {}
    }

    val board = Board()
    private val bag = PieceBag(random)

    var hearts = MAX_HEARTS; private set
    var score = 0; private set
    /** Current height of the tallest column. Bombs can lower it. */
    var towerHeight = 0; private set
    /** Highest the tower has been this run; new layers above it score points. */
    var peakHeight = 0; private set
    var current: Piece? = null; private set
    var next: PieceType = bag.next(); private set
    var isGameOver = false; private set
    var gameOverReason: GameOverReason? = null; private set
    var landedCount = 0; private set

    /** Debug/autoplay only: hearts are never lost. */
    var immortal = false

    /** Chance per spawn of a Heart Block; 0 until it's unlocked in the shop. */
    var heartBlockChance = 0f

    /** The one ad-revive per run has been used. */
    var revived = false; private set

    private var fallTimer = 0f
    private var spawnTimer = 0f

    /** Seconds per layer. Shrinks smoothly as the score climbs (spec §8). */
    val fallInterval: Float
        get() = max(MIN_FALL_INTERVAL, START_FALL_INTERVAL * SPEEDUP.pow(score / SPEEDUP_EVERY_POINTS))

    /** How far the lowest column trails the tallest one. */
    val gap: Int get() = board.maxHeight() - board.minHeight()

    fun start() {
        board.clear()
        hearts = MAX_HEARTS
        score = 0
        towerHeight = 0
        peakHeight = 0
        landedCount = 0
        isGameOver = false
        gameOverReason = null
        revived = false
        current = null
        spawnTimer = 0f
        spawn()
    }

    fun update(dt: Float) {
        if (isGameOver) return
        val piece = current
        if (piece == null) {
            spawnTimer -= dt
            if (spawnTimer <= 0f) spawn()
            return
        }
        fallTimer += dt
        while (fallTimer >= fallInterval && current != null) {
            fallTimer -= fallInterval
            stepDown()
        }
    }

    // ---- player actions -------------------------------------------------------------------

    fun move(dx: Int, dz: Int): Boolean {
        val p = current ?: return false
        if (!fits(p.cells, p.x + dx, p.y, p.z + dz)) return false
        p.x += dx
        p.z += dz
        return true
    }

    fun rotate(): Boolean = reorient(Orientation::yaw)

    fun tip(): Boolean = reorient(Orientation::tip)

    fun hardDrop() {
        val p = current ?: return
        p.y = landingY(p.cells, p.x, p.z)
        land()
    }

    // ---- queries --------------------------------------------------------------------------

    /** True if the cells can occupy this spot: at least one cube over the board and none inside a column. */
    fun fits(cells: List<Vec3i>, x: Int, y: Int, z: Int): Boolean {
        var anyOnBoard = false
        for (c in cells) {
            val wx = c.x + x
            val wz = c.z + z
            if (!board.inside(wx, wz)) continue
            anyOnBoard = true
            if (c.y + y < board.height(wx, wz)) return false
        }
        return anyOnBoard
    }

    /** The origin y at which these cells would come to rest if dropped at (x, z). */
    fun landingY(cells: List<Vec3i>, x: Int, z: Int): Int {
        var y = Int.MIN_VALUE
        for (c in cells) {
            val wx = c.x + x
            val wz = c.z + z
            if (board.inside(wx, wz)) y = max(y, board.height(wx, wz) - c.y)
        }
        return y
    }

    /** Simulates a drop of [cells] at (x, z) without changing the board. Null if no cube is over the board. */
    fun previewDrop(cells: List<Vec3i>, x: Int, z: Int, xIndex: Int = -1): DropPreview? {
        if (cells.none { board.inside(it.x + x, it.z + z) }) return null
        val y = landingY(cells, x, z)
        val heights = board.heights()
        val out = MutableList(cells.size) { Vec3i(0, 0, 0) }
        val onBoard = MutableList(cells.size) { false }
        var lost = 0
        var xOnBoard = false
        cells.indices.sortedBy { cells[it].y }.forEach { i ->
            val c = cells[i]
            val wx = c.x + x
            val wz = c.z + z
            if (i == xIndex) {
                // The X cube never joins the tower: show it where it lands.
                xOnBoard = board.inside(wx, wz)
                out[i] = Vec3i(wx, c.y + y, wz)
                onBoard[i] = xOnBoard
            } else if (board.inside(wx, wz)) {
                val col = wx + wz * Board.SIZE
                out[i] = Vec3i(wx, heights[col], wz)
                heights[col]++
                onBoard[i] = true
            } else {
                out[i] = Vec3i(wx, c.y + y, wz)
                lost++
            }
        }
        return DropPreview(out, onBoard, lost, heights, xOnBoard)
    }

    fun ghost(): DropPreview? = current?.let { previewDrop(it.cells, it.x, it.z, it.type.xIndex) }

    // ---- internals ------------------------------------------------------------------------

    private fun spawn() {
        val type = next
        next = pickNext(type)
        spawnAs(type)
    }

    private fun spawnAs(type: PieceType) {
        val cells = type.cells
        val x = (1f - (Orientation.extent(cells) { it.x } - 1) / 2f).roundToInt()
        val z = (1f - (Orientation.extent(cells) { it.z } - 1) / 2f).roundToInt()
        val piece = Piece(type, cells, x, towerHeight + SPAWN_GAP, z)
        current = piece
        fallTimer = 0f
        listener.onSpawn(piece)
    }

    /** Specials show up after a short warm-up, never twice in a row. */
    private fun pickNext(previous: PieceType): PieceType {
        if (landedCount >= SPECIALS_AFTER && previous.kind == PieceType.Kind.NORMAL) {
            if (random.nextFloat() < heartBlockChance) return PieceType.HEART
            val r = random.nextFloat()
            if (r < BOMB_CHANCE) return PieceType.BOMB
            if (r < BOMB_CHANCE + X_CHANCE) return PieceType.xPieces.random(random)
        }
        return bag.next()
    }

    private fun stepDown() {
        val p = current ?: return
        if (fits(p.cells, p.x, p.y - 1, p.z)) p.y-- else land()
    }

    private val kicks = listOf(
        Vec3i(0, 0, 0), Vec3i(1, 0, 0), Vec3i(-1, 0, 0), Vec3i(0, 0, 1), Vec3i(0, 0, -1),
        Vec3i(0, 1, 0), Vec3i(0, 2, 0),
    )

    /** Applies an orientation change around the piece's center, nudging it if the new shape would collide. */
    private fun reorient(transform: (List<Vec3i>) -> List<Vec3i>): Boolean {
        val p = current ?: return false
        val newCells = transform(p.cells)
        val cx = p.x + (Orientation.extent(p.cells) { it.x } - 1) / 2f
        val cz = p.z + (Orientation.extent(p.cells) { it.z } - 1) / 2f
        val baseX = (cx - (Orientation.extent(newCells) { it.x } - 1) / 2f).roundToInt()
        val baseZ = (cz - (Orientation.extent(newCells) { it.z } - 1) / 2f).roundToInt()
        for (k in kicks) {
            if (fits(newCells, baseX + k.x, p.y + k.y, baseZ + k.z)) {
                p.cells = newCells
                p.x = baseX + k.x
                p.y += k.y
                p.z = baseZ + k.z
                return true
            }
        }
        return false
    }

    private fun land() {
        val p = current ?: return
        current = null
        spawnTimer = SPAWN_DELAY
        landedCount++
        val origin = Vec3i(p.x, p.y, p.z)
        val result = when (p.type.kind) {
            PieceType.Kind.BOMB -> explode(p, origin)
            PieceType.Kind.X -> placeX(p, origin)
            PieceType.Kind.NORMAL -> place(p, origin)
            PieceType.Kind.HEART -> place(p, origin).let {
                if (it.placed.isNotEmpty() && hearts < MAX_HEARTS) it.copy(healed = true) else it
            }
        }
        towerHeight = board.maxHeight()
        score += result.points
        listener.onLanded(result)
        if (result.healed) {
            hearts++
            listener.onHeartGained(hearts)
        }

        val costsHeart = result.xHit ||
            (p.type.kind in listOf(PieceType.Kind.NORMAL, PieceType.Kind.X) && result.lost.isNotEmpty())
        if (costsHeart && !immortal) {
            hearts--
            listener.onHeartLost(hearts)
            if (hearts <= 0) {
                endGame(GameOverReason.HEARTS)
                return
            }
        }
        if (gap > GAP_LIMIT) endGame(GameOverReason.GAPS)
    }

    /** Ad reward: after running out of hearts, continue once with a single heart. */
    fun revive(): Boolean {
        if (!isGameOver || gameOverReason != GameOverReason.HEARTS || revived) return false
        revived = true
        isGameOver = false
        gameOverReason = null
        hearts = 1
        current = null
        spawnTimer = SPAWN_DELAY
        return true
    }

    private fun endGame(reason: GameOverReason) {
        isGameOver = true
        gameOverReason = reason
        listener.onGameOver(reason)
    }

    private fun place(p: Piece, origin: Vec3i): LandingResult {
        val placed = ArrayList<PlacedCube>()
        val lost = ArrayList<Vec3i>()
        val world = p.worldCells()
        for (i in world.indices.sortedBy { world[it].y }) {
            if (p.type.isX(i)) continue
            val c = world[i]
            if (board.inside(c.x, c.z)) {
                val y = board.push(c.x, c.z, p.type.stackType)
                placed += PlacedCube(c, Vec3i(c.x, y, c.z))
            } else {
                lost += c
            }
        }
        val perfect = lost.isEmpty() && placed.all { it.from.y == it.to.y }
        var points = POINTS_PER_CUBE * placed.size
        if (perfect) points += PERFECT_BONUS
        points += newLayerPoints()
        return LandingResult(p.type, origin, placed, lost, perfect, points)
    }

    private fun newLayerPoints(): Int {
        val h = board.maxHeight()
        if (h <= peakHeight) return 0
        val points = POINTS_PER_NEW_LAYER * (h - peakHeight)
        peakHeight = h
        return points
    }

    /** Destroys every cube within one cell of the bomb (sideways) and [BLAST_DOWN] layers below it; the rest drop. */
    private fun explode(p: Piece, origin: Vec3i): LandingResult {
        val cell = p.worldCells().first()
        val center = Vec3i(cell.x, board.height(cell.x, cell.z), cell.z)
        val cleared = ArrayList<Pair<Vec3i, PieceType>>()
        val shifted = ArrayList<PlacedCube>()
        for (x in center.x - 1..center.x + 1) for (z in center.z - 1..center.z + 1) {
            if (!board.inside(x, z)) continue
            val h = board.height(x, z)
            val lo = max(0, center.y - BLAST_DOWN)
            val hi = minOf(h - 1, center.y + BLAST_UP)
            if (hi < lo) continue
            for (y in hi downTo lo) cleared += Vec3i(x, y, z) to board.removeAt(x, y, z)
            val removed = hi - lo + 1
            for (y in hi + 1 until h) shifted += PlacedCube(Vec3i(x, y, z), Vec3i(x, y - removed, z))
        }
        val points = POINTS_PER_CLEARED * cleared.size
        return LandingResult(p.type, origin, emptyList(), emptyList(), false, points, center, cleared, shifted)
    }

    /** Plain cubes stack as usual; the X cube shatters on the board (costing a heart) or falls away if it hangs off. */
    private fun placeX(p: Piece, origin: Vec3i): LandingResult {
        val base = place(p, origin)
        val cell = p.worldCells()[p.type.xIndex]
        return if (board.inside(cell.x, cell.z)) {
            base.copy(perfect = false, xCell = cell, xHit = true, impact = Vec3i(cell.x, board.height(cell.x, cell.z), cell.z))
        } else {
            base.copy(xCell = cell, dodged = true, points = base.points + DODGE_BONUS)
        }
    }

    /** Debug/tests: swaps the falling piece for a fresh one of [type]. */
    fun debugSpawn(type: PieceType) {
        if (current != null) spawnAs(type)
    }

    /** Debug/autoplay: re-orient and reposition the current piece without collision kicks. */
    fun debugPlace(cells: List<Vec3i>, x: Int, z: Int): Boolean {
        val p = current ?: return false
        // X pieces locate their X cube by index, so their shape can't be swapped out.
        if (p.type.kind == PieceType.Kind.X && cells.size != p.cells.size) return false
        if (!fits(cells, x, p.y, z)) return false
        p.cells = cells
        p.x = x
        p.z = z
        return true
    }

    companion object {
        const val MAX_HEARTS = 3
        const val HEART_BLOCK_CHANCE = 0.01f
        const val SCORE_PER_COIN = 100

        fun coinsFor(score: Int) = score / SCORE_PER_COIN
        const val SPAWN_GAP = 5
        const val SPAWN_DELAY = 0.3f
        const val START_FALL_INTERVAL = 1.0f
        const val MIN_FALL_INTERVAL = 0.25f
        /** Fall interval is multiplied by this for every [SPEEDUP_EVERY_POINTS] points (applied continuously). */
        const val SPEEDUP = 0.98f
        const val SPEEDUP_EVERY_POINTS = 250f
        const val POINTS_PER_CUBE = 10
        const val POINTS_PER_NEW_LAYER = 50
        const val PERFECT_BONUS = 25
        const val POINTS_PER_CLEARED = 15
        const val DODGE_BONUS = 25

        /** Lose if the lowest column trails the tallest by more than this many levels. */
        const val GAP_LIMIT = 10
        /** Start warning the player when the gap reaches this. */
        const val GAP_WARNING = 7

        const val SPECIALS_AFTER = 4
        const val BOMB_CHANCE = 0.05f
        const val X_CHANCE = 0.08f
        const val BLAST_DOWN = 1
        const val BLAST_UP = 1
    }
}
