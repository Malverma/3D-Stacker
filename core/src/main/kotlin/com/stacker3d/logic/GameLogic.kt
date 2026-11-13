package com.stacker3d.logic

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/** A cube that locked into the tower: where it started ([from]), where it settled ([to]) and what it is. */
data class PlacedCube(val from: Vec3i, val to: Vec3i, val type: PieceType)

/** Why a tower cube broke after a landing. */
enum class BreakCause { CRUMBLE, SHATTER, DYNAMITE }

/** A tower cube removed after a landing: the cubes above it drop one layer. */
data class Break(val at: Vec3i, val type: PieceType, val cause: BreakCause)

/** A cube that missed the board and fell away. */
data class LostCube(val at: Vec3i, val type: PieceType)

data class LandingResult(
    val type: PieceType,
    /** Piece origin at the moment it landed. */
    val origin: Vec3i,
    val placed: List<PlacedCube>,
    /** Plain or special cubes that missed the board. (A dodged X cube is reported by [dodged] instead.) */
    val lost: List<LostCube>,
    val perfect: Boolean,
    val points: Int,
    /** Bomb: blast center. X cube or cursed Mystery cube: where it shatters on the board. */
    val impact: Vec3i? = null,
    /** A bomb went off (a Bomb, or a Mystery cube that became one). */
    val exploded: Boolean = false,
    /** Bomb: cubes destroyed by the blast (old positions). */
    val cleared: List<Pair<Vec3i, PieceType>> = emptyList(),
    /** Bomb: surviving cubes that dropped to fill the hole. */
    val shifted: List<PlacedCube> = emptyList(),
    /** The cube that sets off [exploded] or [xHit] (or was [dodged]): its cell when the piece landed. */
    val effectCell: Vec3i? = null,
    /** X cube landed on the board. */
    val xHit: Boolean = false,
    /** X cube hung off the edge and fell away safely. */
    val dodged: Boolean = false,
    /** Heart block landed on the board and restored a heart. */
    val healed: Boolean = false,
    /** Coins earned this landing (Golden Block), banked when the run ends. */
    val coins: Int = 0,
    /** Score multiplier gained this landing, in tenths (Multiplier Block). */
    val multiplierGain: Int = 0,
    /** Mystery cube: the effect it turned into. */
    val mystery: MysteryEffect? = null,
    /**
     * Tower cubes that broke after this landing, in order: Glass Blocks shattered by it, then layers blown up by
     * Dynamite whose fuse ran out, then Cracked Blocks that crumbled. Each one is removed from its column and every
     * cube above it drops one layer before the next one breaks.
     */
    val breaks: List<Break> = emptyList(),
    /** A Freeze Block landed on the board: the next [GameLogic.FREEZE_PIECES] pieces fall at the starting speed. */
    val froze: Boolean = false,
    /** No Fall mode: cells that joined the board because a cube of this piece hung over them; y is the new floor. */
    val grown: List<Vec3i> = emptyList(),
)

/** What a Mystery Block can turn into when it lands on the board. */
enum class MysteryEffect(val label: String, val good: Boolean) {
    GOLD_RUSH("GOLD RUSH", true),
    HEAL("HEAL", true),
    MULTIPLY("MULTIPLY", true),
    BOMB("BOMB", true),
    CRACK("CRACKED", false),
    CURSE("CURSE", false),
}

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
        /** The tower reached a new height tier and these large pieces joined the bag. */
        fun onPiecesUnlocked(types: List<PieceType>) {}
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
    var next: PieceSpec = PieceSpec(bag.next()); private set
    var isGameOver = false; private set
    var gameOverReason: GameOverReason? = null; private set
    var landedCount = 0; private set

    /** Debug/autoplay only: hearts are never lost. */
    var immortal = false

    /**
     * No Fall mode: cubes hanging off the edge of a piece that has at least one cube on the board don't fall away.
     * They stay at the level they hung at, and their cells join the board with the floor there (air underneath,
     * which counts as filled for the gap rule).
     */
    var noFall = false
        set(value) { field = value; bag.noFall = value }

    /** Chance per spawn of each shop special ([PieceType.unlockables]); missing types never spawn. */
    var specialChances: Map<PieceType, Float> = emptyMap()

    /** Score multiplier in tenths: 10 is ×1.0. Each Multiplier Block adds [MULTIPLIER_STEP]. */
    var multiplierTenths = 10; private set
    val multiplier: Float get() = multiplierTenths / 10f

    /** Pieces left that fall at the starting speed because of a Freeze Block. */
    var frozenPieces = 0; private set

    /** Coins from Golden Blocks this run, paid out with the score coins when the run ends. */
    var bonusCoins = 0; private set

    /** The one ad-revive per run has been used. */
    var revived = false; private set

    /** The one ad-continue per run after a "board not filled" loss has been used. */
    var gapRevived = false; private set

    /** A "board not filled" loss can still be undone by watching an ad. */
    val canReviveGaps: Boolean get() = isGameOver && gameOverReason == GameOverReason.GAPS && !gapRevived

    private var fallTimer = 0f
    private var spawnTimer = 0f

    /** Seconds per layer. Shrinks smoothly as the score climbs (spec §8); a Freeze Block slows it back down for a while. */
    val fallInterval: Float
        get() = if (frozenPieces > 0) START_FALL_INTERVAL
        else max(MIN_FALL_INTERVAL, START_FALL_INTERVAL * SPEEDUP.pow(score / SPEEDUP_EVERY_POINTS))

    /** Landings left on each Dynamite Block in the tower, soonest first. */
    fun dynamiteFuses(): List<Int> = cubesOf(PieceType.DYNAMITE).map { board.fuseAt(it.x, it.y, it.z) }.sorted().toList()

    /** How far the lowest column trails the tallest one. */
    val gap: Int get() = board.maxHeight() - board.minHeight()

    fun start() {
        board.clear()
        hearts = MAX_HEARTS
        score = 0
        towerHeight = 0
        peakHeight = 0
        landedCount = 0
        multiplierTenths = 10
        bonusCoins = 0
        frozenPieces = 0
        isGameOver = false
        gameOverReason = null
        revived = false
        gapRevived = false
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

    /** Cube at (x, z) of a landing piece stacks there: on the board, or (No Fall) on a cell the board can grow into. */
    private fun stacksAt(x: Int, z: Int) = board.inside(x, z) || (noFall && board.canGrow(x, z))

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
        val grown = HashSet<Int>()
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
            } else if (stacksAt(wx, wz)) {
                val col = Board.index(wx, wz)
                // No Fall: a cell the board grows into gets its floor where its lowest cube hangs.
                if (!board.inside(wx, wz) && grown.add(col)) heights[col] = c.y + y
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
        val spec = next
        next = pickNext(spec)
        spawnAs(spec)
    }

    private fun spawnAs(spec: PieceSpec) {
        val cells = spec.cells
        // Centered over the board (which No Fall mode can grow lopsided).
        val cx = (board.minX + board.maxX) / 2f
        val cz = (board.minZ + board.maxZ) / 2f
        val x = (cx - (Orientation.extent(cells) { it.x } - 1) / 2f).roundToInt()
        val z = (cz - (Orientation.extent(cells) { it.z } - 1) / 2f).roundToInt()
        val piece = Piece(spec, x, towerHeight + SPAWN_GAP, z)
        current = piece
        fallTimer = 0f
        listener.onSpawn(piece)
    }

    /** Specials show up after a short warm-up, never twice in a row. */
    private fun pickNext(previous: PieceSpec): PieceSpec {
        if (landedCount >= SPECIALS_AFTER && !previous.isSpecial) {
            // One roll shared by every special, so their chances simply add up.
            var r = random.nextFloat()
            for (type in PieceType.unlockables) {
                val chance = specialChances[type] ?: continue
                if (r < chance) {
                    // Half the time the special cube comes attached to a regular piece from the bag.
                    return if (random.nextFloat() < ATTACH_CHANCE) PieceSpec.attached(bag.next(peakHeight), type, random) else PieceSpec(type)
                }
                r -= chance
            }
            if (r < BOMB_CHANCE) return PieceSpec(PieceType.BOMB)
            r -= BOMB_CHANCE
            if (r < X_CHANCE) return PieceSpec(PieceType.xPieces.random(random))
        }
        return PieceSpec(bag.next(peakHeight))
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
        val oldPeak = peakHeight
        val base = when (p.type.kind) {
            PieceType.Kind.BOMB -> explode(p, origin)
            PieceType.Kind.X -> placeX(p, origin)
            else -> place(p, origin)
        }
        // Glass under cubes that dropped onto it shatters, Dynamite fuses tick down, then cracked cubes crumble.
        val breaks = shatterGlass(base.placed) + tickDynamite()
        val shattered = breaks.count { it.cause == BreakCause.SHATTER }
        val blasted = breaks.count { it.cause == BreakCause.DYNAMITE }
        val points = max(0, base.points - GLASS_BONUS * shattered) + DYNAMITE_POINTS_PER_CUBE * blasted
        // The multiplier applies to this landing's points before a Multiplier Block raises it.
        val result = base.copy(points = points * multiplierTenths / 10, breaks = breaks + crumble())
        if (frozenPieces > 0) frozenPieces--
        if (result.froze) frozenPieces = FREEZE_PIECES
        multiplierTenths += result.multiplierGain
        bonusCoins += result.coins
        towerHeight = board.maxHeight()
        score += result.points
        listener.onLanded(result)
        val unlocked = PieceType.unlockablesFor(noFall).filter { it.unlockHeight in oldPeak + 1..peakHeight }
        if (unlocked.isNotEmpty()) {
            bag.unlock(unlocked)
            listener.onPiecesUnlocked(unlocked)
        }
        if (result.healed) {
            hearts++
            listener.onHeartGained(hearts)
        }

        // Regular pieces (with or without a special attached) and X pieces cost a heart when cubes spill.
        val spills = p.type.kind == PieceType.Kind.NORMAL || p.type.kind == PieceType.Kind.X
        val costsHeart = result.xHit || (spills && result.lost.isNotEmpty())
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

    /**
     * Ad reward after a "board not filled" loss: every column within [GAP_GRACE] levels of losing is topped up with
     * filler cubes until it trails the tallest column by exactly that grace, then play continues (once per run).
     * Returns the added cells, bottom-up within each column, or null if the continue isn't available.
     */
    fun reviveGaps(): List<Vec3i>? {
        if (!canReviveGaps) return null
        gapRevived = true
        isGameOver = false
        gameOverReason = null
        val target = board.maxHeight() - (GAP_LIMIT + 1 - GAP_GRACE)
        val added = ArrayList<Vec3i>()
        for (i in board.cells) {
            val x = Board.xOf(i)
            val z = Board.zOf(i)
            while (board.height(x, z) < target) {
                val y = board.push(x, z, FILLER)
                added += Vec3i(x, y, z)
            }
        }
        current = null
        spawnTimer = REFILL_DELAY
        return added
    }

    private fun endGame(reason: GameOverReason) {
        isGameOver = true
        gameOverReason = reason
        listener.onGameOver(reason)
    }

    /** Stacks the piece's plain cubes as [type]; [fuse] gives each cube's crack fuse (0 = not cracked). */
    /**
     * Stacks every cube except an X cube, each as its own block type, and applies the specials among them.
     * A Mystery cube over the board first rolls its effect: it then stacks as the block it became, or (bomb, curse)
     * acts where it landed once the rest has stacked.
     */
    private fun place(p: Piece, origin: Vec3i): LandingResult {
        val world = p.worldCells()
        val mysteryIndex = world.indices.firstOrNull {
            p.cellType(it).kind == PieceType.Kind.MYSTERY && stacksAt(world[it].x, world[it].z)
        } ?: -1
        val effect = if (mysteryIndex >= 0) rollMystery() else null

        val placed = ArrayList<PlacedCube>()
        val lost = ArrayList<LostCube>()
        var coins = 0
        var gain = 0
        var bonus = 0
        var heart = false
        var froze = false
        val grown = ArrayList<Vec3i>()
        for (i in world.indices.sortedBy { world[it].y }) {
            if (p.type.isX(i)) continue
            val c = world[i]
            // No Fall: a cube hanging off the edge stays at its level; its cell joins the board with its floor there.
            if (noFall && board.grow(c.x, c.z, c.y)) grown += c
            val fromMystery = i == mysteryIndex
            val type = if (!fromMystery) p.cellType(i) else when (effect!!) {
                MysteryEffect.GOLD_RUSH -> PieceType.GOLD
                MysteryEffect.HEAL -> PieceType.HEART
                MysteryEffect.MULTIPLY -> PieceType.MULTIPLIER
                MysteryEffect.CRACK -> PieceType.CRACKED
                MysteryEffect.BOMB, MysteryEffect.CURSE -> continue
            }
            if (!board.inside(c.x, c.z)) {
                lost += LostCube(c, type)
                continue
            }
            val fuse = when (type) {
                PieceType.CRACKED -> random.nextInt(CRACK_MIN_LAYERS, CRACK_MAX_LAYERS + 1)
                // One more than its fuse: this same landing's tick takes the first one off.
                PieceType.DYNAMITE -> random.nextInt(DYNAMITE_MIN_FUSE, DYNAMITE_MAX_FUSE + 1) + 1
                else -> 0
            }
            val y = board.push(c.x, c.z, type, fuse)
            placed += PlacedCube(c, Vec3i(c.x, y, c.z), type)
            when (type.kind) {
                PieceType.Kind.HEART -> heart = true
                PieceType.Kind.GOLD -> coins += if (fromMystery) MYSTERY_GOLD_COINS else GOLD_COINS
                PieceType.Kind.MULTIPLIER -> gain += if (fromMystery) MYSTERY_MULTIPLIER_STEP else MULTIPLIER_STEP
                PieceType.Kind.CRACKED -> if (!fromMystery) bonus += CRACKED_BONUS
                PieceType.Kind.GLASS -> bonus += GLASS_BONUS
                PieceType.Kind.FREEZE -> froze = true
                else -> Unit
            }
        }
        val acts = effect == MysteryEffect.BOMB || effect == MysteryEffect.CURSE
        val perfect = !acts && placed.isNotEmpty() && lost.isEmpty() && placed.all { it.from.y == it.to.y }
        var points = POINTS_PER_CUBE * placed.size + bonus
        if (perfect) points += PERFECT_BONUS
        points += newLayerPoints()
        val r = LandingResult(
            p.type, origin, placed, lost, perfect, points,
            healed = heart && hearts < MAX_HEARTS, coins = coins, multiplierGain = gain, mystery = effect, grown = grown,
            froze = froze,
        )
        if (mysteryIndex < 0) return r
        val cell = world[mysteryIndex]
        return when (effect) {
            MysteryEffect.BOMB -> blast(r, cell)
            // Shatters like an X cube on the board: costs a heart.
            MysteryEffect.CURSE -> r.copy(
                effectCell = cell, xHit = true, impact = Vec3i(cell.x, board.height(cell.x, cell.z), cell.z),
            )
            else -> r
        }
    }

    /** Heal is only offered while a heart is missing. */
    private fun rollMystery(): MysteryEffect =
        MysteryEffect.entries.filter { it != MysteryEffect.HEAL || hearts < MAX_HEARTS }.random(random)

    /** Breaks every cracked cube that now sits too far below the top of the tower; the cubes above it drop one layer. */
    private fun crumble(): List<Break> {
        val out = ArrayList<Break>()
        while (true) {
            val top = board.maxHeight()
            val cube = cubesOf(PieceType.CRACKED).firstOrNull { top - it.y - 1 >= board.fuseAt(it.x, it.y, it.z) } ?: return out
            board.removeAt(cube.x, cube.y, cube.z)
            out += Break(cube, PieceType.CRACKED, BreakCause.CRUMBLE)
        }
    }

    /** Every tower cube of [type]. */
    private fun cubesOf(type: PieceType) = sequence {
        for (i in board.cells) {
            val x = Board.xOf(i)
            val z = Board.zOf(i)
            for (y in board.base(x, z) until board.height(x, z)) if (board.typeAt(x, y, z) == type) yield(Vec3i(x, y, z))
        }
    }

    /**
     * A Glass Block shatters when a cube of the landing piece drops onto it (settles down onto it rather than landing
     * flush). Highest first, so the positions of lower ones stay valid.
     */
    private fun shatterGlass(placed: List<PlacedCube>): List<Break> {
        val glass = placed.filter { it.from.y > it.to.y }
            .map { Vec3i(it.to.x, it.to.y - 1, it.to.z) }
            .filter { it.y >= board.base(it.x, it.z) && board.typeAt(it.x, it.y, it.z) == PieceType.GLASS }
            .distinct().sortedByDescending { it.y }
        for (g in glass) board.removeAt(g.x, g.y, g.z)
        return glass.map { Break(it, PieceType.GLASS, BreakCause.SHATTER) }
    }

    /** Every Dynamite Block's fuse burns down by one landing; one that runs out destroys its whole layer. */
    private fun tickDynamite(): List<Break> {
        for (d in cubesOf(PieceType.DYNAMITE).toList()) board.setFuse(d.x, d.y, d.z, board.fuseAt(d.x, d.y, d.z) - 1)
        val out = ArrayList<Break>()
        while (true) {
            val d = cubesOf(PieceType.DYNAMITE).firstOrNull { board.fuseAt(it.x, it.y, it.z) <= 0 } ?: return out
            // Other Dynamite in the same layer goes with it.
            for (i in board.cells) {
                val x = Board.xOf(i)
                val z = Board.zOf(i)
                if (d.y < board.base(x, z) || d.y >= board.height(x, z)) continue
                out += Break(Vec3i(x, d.y, z), board.removeAt(x, d.y, z), BreakCause.DYNAMITE)
            }
        }
    }

    private fun newLayerPoints(): Int {
        val h = board.maxHeight()
        if (h <= peakHeight) return 0
        val points = POINTS_PER_NEW_LAYER * (h - peakHeight)
        peakHeight = h
        return points
    }

    private fun explode(p: Piece, origin: Vec3i) =
        blast(LandingResult(p.type, origin, emptyList(), emptyList(), false, 0), p.worldCells().first())

    /**
     * A bomb dropped at [cell] sits on top of its column and destroys every cube within one cell of it (sideways),
     * from [BLAST_DOWN] layers below to [BLAST_UP] above; the rest drop. Adds the blast to [r].
     */
    private fun blast(r: LandingResult, cell: Vec3i): LandingResult {
        val center = Vec3i(cell.x, board.height(cell.x, cell.z), cell.z)
        val cleared = ArrayList<Pair<Vec3i, PieceType>>()
        val shifted = ArrayList<PlacedCube>()
        for (x in center.x - 1..center.x + 1) for (z in center.z - 1..center.z + 1) {
            if (!board.inside(x, z)) continue
            val h = board.height(x, z)
            val lo = max(board.base(x, z), center.y - BLAST_DOWN)
            val hi = minOf(h - 1, center.y + BLAST_UP)
            if (hi < lo) continue
            for (y in hi downTo lo) cleared += Vec3i(x, y, z) to board.removeAt(x, y, z)
            val removed = hi - lo + 1
            for (y in hi + 1 until h) shifted += PlacedCube(Vec3i(x, y, z), Vec3i(x, y - removed, z), board.typeAt(x, y - removed, z))
        }
        return r.copy(
            points = r.points + POINTS_PER_CLEARED * cleared.size, impact = center, exploded = true, effectCell = cell,
            cleared = cleared, shifted = shifted,
        )
    }

    /** Plain cubes stack as usual; the X cube shatters on the board (costing a heart) or falls away if it hangs off. */
    private fun placeX(p: Piece, origin: Vec3i): LandingResult {
        val base = place(p, origin)
        val cell = p.worldCells()[p.type.xIndex]
        return if (board.inside(cell.x, cell.z)) {
            base.copy(perfect = false, effectCell = cell, xHit = true, impact = Vec3i(cell.x, board.height(cell.x, cell.z), cell.z))
        } else {
            base.copy(effectCell = cell, dodged = true, points = base.points + DODGE_BONUS)
        }
    }

    /** Debug/tests: swaps the falling piece for a fresh one of [type]. */
    fun debugSpawn(type: PieceType) = debugSpawn(PieceSpec(type))

    fun debugSpawn(spec: PieceSpec) {
        if (current != null) spawnAs(spec)
    }

    /** Debug/autoplay: re-orient and reposition the current piece without collision kicks. */
    fun debugPlace(cells: List<Vec3i>, x: Int, z: Int): Boolean {
        val p = current ?: return false
        // X and attached special cubes are found by index, so those pieces' shapes can't be swapped out.
        if ((p.type.kind == PieceType.Kind.X || p.spec.special != null) && cells.size != p.cells.size) return false
        if (!fits(cells, x, p.y, z)) return false
        p.cells = cells
        p.x = x
        p.z = z
        return true
    }

    companion object {
        const val MAX_HEARTS = 3
        const val SCORE_PER_COIN = 100

        fun coinsFor(score: Int) = score / SCORE_PER_COIN
        const val SPAWN_GAP = 5
        const val SPAWN_DELAY = 0.3f
        const val START_FALL_INTERVAL = 1.0f
        const val MIN_FALL_INTERVAL = 0.25f
        /** Fall interval is multiplied by this for every [SPEEDUP_EVERY_POINTS] points (applied continuously). */
        const val SPEEDUP = 0.975f
        const val SPEEDUP_EVERY_POINTS = 250f
        const val POINTS_PER_CUBE = 10
        const val POINTS_PER_NEW_LAYER = 50
        const val PERFECT_BONUS = 25
        const val POINTS_PER_CLEARED = 15
        const val DODGE_BONUS = 25

        /** Lose if the lowest column trails the tallest by more than this many levels (10 behind loses). */
        const val GAP_LIMIT = 9
        /** Start warning the player when the gap reaches this. */
        const val GAP_WARNING = 6
        /** Ad continue after a gap loss: trailing columns are filled until they're this many levels from losing. */
        const val GAP_GRACE = 3
        /** Block type the gap-continue fills with. */
        val FILLER = PieceType.SINGLE
        /** Spawn delay after the gap-continue, so the filler cubes can drop in first. */
        const val REFILL_DELAY = 1.3f

        /** A height milestone every this many layers (spec §8). */
        const val MILESTONE_EVERY = 10
        /** Milestone N (height N × [MILESTONE_EVERY]) pays N × this many coins. */
        const val MILESTONE_COINS_STEP = 25

        /** The highest milestone height at or below [height] (0 if none). */
        fun milestoneAt(height: Int) = height / MILESTONE_EVERY * MILESTONE_EVERY

        /** Milestone heights above [from] up to and including [to]. */
        fun milestonesBetween(from: Int, to: Int) =
            (milestoneAt(from) + MILESTONE_EVERY..to step MILESTONE_EVERY).toList()

        /** Coins for reaching the milestone at [height] for the first time: 25, 50, 75 … */
        fun milestoneCoins(height: Int) = height / MILESTONE_EVERY * MILESTONE_COINS_STEP

        const val SPECIALS_AFTER = 4
        const val BOMB_CHANCE = 0.05f
        const val X_CHANCE = 0.08f
        const val BLAST_DOWN = 1
        const val BLAST_UP = 1

        const val GOLD_COINS = 2
        /** Tenths of a multiplier: a Multiplier Block adds ×0.1. */
        const val MULTIPLIER_STEP = 1
        const val CRACKED_BONUS = 40
        /** A cracked cube crumbles once this many layers (picked at random per cube) sit above it. */
        const val CRACK_MIN_LAYERS = 10
        const val CRACK_MAX_LAYERS = 15
        const val GLASS_BONUS = 60
        /** Landings a Dynamite Block waits before it goes off (picked at random per block). */
        const val DYNAMITE_MIN_FUSE = 3
        const val DYNAMITE_MAX_FUSE = 5
        const val DYNAMITE_POINTS_PER_CUBE = 30
        /** Pieces that fall at the starting speed after a Freeze Block lands. */
        const val FREEZE_PIECES = 5
        const val MYSTERY_GOLD_COINS = 10
        const val MYSTERY_MULTIPLIER_STEP = 3
        /** Chance that a shop special comes attached to a regular piece instead of as a lone cube. */
        const val ATTACH_CHANCE = 0.5f
    }
}
