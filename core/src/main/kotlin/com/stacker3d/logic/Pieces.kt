package com.stacker3d.logic

import kotlin.math.max
import kotlin.math.min

data class Vec3i(val x: Int, val y: Int, val z: Int) {
    operator fun plus(o: Vec3i) = Vec3i(x + o.x, y + o.y, z + o.z)
}

/** The piece set (spec §6). [rgb] is the cube color as 0xRRGGBB. */
enum class PieceType(
    val rgb: Int,
    val cells: List<Vec3i>,
    val kind: Kind = Kind.NORMAL,
    /** For X pieces: index into [cells] of the X cube. Cell order survives rotation, so this stays valid. */
    val xIndex: Int = -1,
    /** Regular pieces only: the tower height at which this piece joins the bag (0 = from the start). */
    val unlockHeight: Int = 0,
    /** Huge pieces that only come in No Fall mode, where the board can grow to fit them. */
    val noFallOnly: Boolean = false,
) {
    SINGLE(0xFFD23F, cells("000")),
    DOMINO(0xFF6B6B, cells("000", "100")),
    BAR(0x4D96FF, cells("000", "100", "200")),
    CORNER(0x6BCB77, cells("000", "100", "001")),
    SQUARE(0xB983FF, cells("000", "100", "001", "101")),
    TEE(0xFF9F45, cells("000", "100", "200", "101")),
    PLUS(0x2EC4B6, cells("100", "001", "101", "201", "102")),
    ZIGZAG(0xC0E218, cells("101", "201", "000", "100")),

    // Large pieces (spec §6): harder to fit, they join the bag as the tower climbs.
    /** 5 cubes: two 3-long arms meeting at a corner. */
    BIG_CORNER(0xE84A5F, cells("000", "100", "200", "001", "002"), unlockHeight = 10),
    /** 5 cubes: a U with a gap in the middle of one side. */
    U_PIECE(0x00A8E8, cells("000", "200", "001", "101", "201"), unlockHeight = 10),
    /** 6 cubes: a flat 3×2 slab. */
    SLAB(0x9D4EDD, cells("000", "100", "200", "001", "101", "201"), unlockHeight = 20),
    /** 6 cubes: a staircase three steps high. */
    STAIRS(0xF4A261, cells("000", "100", "200", "110", "210", "220"), unlockHeight = 20),
    /** 5 cubes: two legs with a beam across the top. */
    ARCH(0x2A9D8F, cells("000", "200", "010", "110", "210"), unlockHeight = 35),
    /** 5 cubes: a corner with a two-high post at the joint. */
    POST_CORNER(0xE76F51, cells("000", "100", "001", "010", "020"), unlockHeight = 35),
    /** 8 cubes: the whole 3×3 board outline with a hole in the middle. */
    RING(0xFF477E, cells("000", "100", "200", "001", "201", "002", "102", "202"), unlockHeight = 50),
    /** 8 cubes: a 2×2×2 block. */
    BIG_CUBE(0x6A994E, cells("000", "100", "001", "101", "010", "110", "011", "111"), unlockHeight = 50),

    // Huge pieces (spec §4b), No Fall mode only: longer than the 3×3, so they always hang off and grow the board.
    // Each tier is bigger and more awkward than the last: 4 long, then wider, then 5 long, then 3D.
    /** 4 cubes: a 4-long bar. */
    LONG_BAR(0x48CAE4, flat("####"), unlockHeight = 25, noFallOnly = true),
    /** 5 cubes: a 4-long bar with a foot. */
    LONG_L(0xF15BB5, flat("####", "#..."), unlockHeight = 25, noFallOnly = true),
    /** 6 cubes: a 4-wide U. */
    WIDE_U(0x00BBF9, flat("####", "#..#"), unlockHeight = 50, noFallOnly = true),
    /** 6 cubes: a diagonal staircase lying flat. */
    DIAGONAL(0xFEE440, flat("##..", ".##.", "..##"), unlockHeight = 50, noFallOnly = true),
    /** 5 cubes: a 5-long bar. */
    GIANT_BAR(0x9B5DE5, flat("#####"), unlockHeight = 75, noFallOnly = true),
    /** 7 cubes: a 5-wide T with a long stem. */
    GIANT_TEE(0xFF7B00, flat("#####", "..#..", "..#.."), unlockHeight = 75, noFallOnly = true),
    /** 9 cubes: a 5×5 cross. */
    CROSS(0x06D6A0, flat("..#..", "..#..", "#####", "..#..", "..#.."), unlockHeight = 100, noFallOnly = true),
    /** 12 cubes: the outline of a 4×4 square. */
    FRAME(0xEF476F, flat("####", "#..#", "#..#", "####"), unlockHeight = 100, noFallOnly = true),
    /** 10 cubes: a staircase four steps high. */
    BIG_STAIRS(0xFFB703, cells("000", "100", "200", "300", "110", "210", "310", "220", "320", "330"), unlockHeight = 125, noFallOnly = true),
    /** 11 cubes: two 3-high legs, 5 apart, with a beam across the top. */
    GATE(0x8338EC, cells("000", "010", "020", "400", "410", "420", "030", "130", "230", "330", "430"), unlockHeight = 125, noFallOnly = true),
    /** 15 cubes: a flat 5×3 slab. */
    MEGA_SLAB(0x3A86FF, flat("#####", "#####", "#####"), unlockHeight = 150, noFallOnly = true),
    /** 13 cubes: a 5×5 H. */
    MEGA_H(0xFB5607, flat("#...#", "#...#", "#####", "#...#", "#...#"), unlockHeight = 150, noFallOnly = true),

    /** Clears nearby cubes where it lands; never costs a heart. */
    BOMB(0x2E2E3A, cells("000"), Kind.BOMB),

    /**
     * A domino / bar with an X cube on one end. The plain cubes stack normally; the X cube costs a heart if it
     * lands on the board, so it has to hang off the edge while the rest stays on.
     */
    X_DOMINO(0xFF6B6B, cells("000", "100"), Kind.X, xIndex = 1),
    X_BAR(0x4D96FF, cells("000", "100", "200"), Kind.X, xIndex = 2),

    /** Shop unlock: stays in the tower like a normal cube and restores a heart (up to the max). */
    HEART(0xFF8FB8, cells("000"), Kind.HEART),

    /** Shop unlock: stays in the tower and pays a few coins when placed on the board. */
    GOLD(0xFFC93C, cells("000"), Kind.GOLD),

    /** Shop unlock: stays in the tower and raises the run's score multiplier when placed on the board. */
    MULTIPLIER(0x7B4DFF, cells("000"), Kind.MULTIPLIER),

    /** Shop unlock: worth bonus points, but crumbles once the tower climbs 10–15 layers above it. */
    CRACKED(0x9A8F84, cells("000"), Kind.CRACKED),

    /** Shop unlock: turns into a random effect, good or bad, when it lands on the board. */
    MYSTERY(0x3FD1C4, cells("000"), Kind.MYSTERY),

    /** Shop unlock: stays in the tower on a short fuse, then blows up the whole layer it sits in. */
    DYNAMITE(0xD62828, cells("000"), Kind.DYNAMITE),

    /** Shop unlock: worth bonus points, but shatters if a cube drops onto it. */
    GLASS(0xBDE0FE, cells("000"), Kind.GLASS),

    /** Shop unlock: slows the fall back to the starting speed for the next few pieces. */
    FREEZE(0x5CC8F0, cells("000"), Kind.FREEZE);

    enum class Kind { NORMAL, BOMB, X, HEART, GOLD, MULTIPLIER, CRACKED, MYSTERY, DYNAMITE, GLASS, FREEZE }

    /** The type stored in the tower for this piece's plain cubes (X pieces stack as their base shape). */
    val stackType: PieceType
        get() = when (this) {
            X_DOMINO -> DOMINO
            X_BAR -> BAR
            else -> this
        }

    fun isX(cellIndex: Int) = cellIndex == xIndex

    companion object {
        /** The regular pieces available from the start. */
        val normals = entries.filter { it.kind == Kind.NORMAL && it.unlockHeight == 0 }

        /** The large regular pieces that join the bag as the tower climbs (both modes), in unlock order. */
        val largePieces = entries.filter { it.kind == Kind.NORMAL && it.unlockHeight > 0 && !it.noFallOnly }

        /** The huge pieces only No Fall mode adds, in unlock order. */
        val hugePieces = entries.filter { it.kind == Kind.NORMAL && it.noFallOnly }

        /** Every large piece that can unlock in this mode. */
        fun unlockablesFor(noFall: Boolean) = if (noFall) largePieces + hugePieces else largePieces

        /** Every regular piece in the bag once the tower has reached [height]. */
        fun regularsAt(height: Int, noFall: Boolean = false) =
            entries.filter { it.kind == Kind.NORMAL && it.unlockHeight <= height && (noFall || !it.noFallOnly) }
        val xPieces = entries.filter { it.kind == Kind.X }

        /** Specials that only spawn once bought in the shop; their chances come from the player's upgrade levels. */
        val unlockables = listOf(HEART, GOLD, MULTIPLIER, CRACKED, MYSTERY, DYNAMITE, GLASS, FREEZE)
    }
}

private fun cells(vararg xyz: String) = xyz.map { Vec3i(it[0] - '0', it[1] - '0', it[2] - '0') }

/** A flat piece drawn as rows (z) of characters (x): '#' is a cube. */
private fun flat(vararg rows: String) =
    rows.flatMapIndexed { z, row -> row.mapIndexedNotNull { x, ch -> if (ch == '#') Vec3i(x, 0, z) else null } }

/** Pure functions for re-orienting a piece's local cells. Results are always normalized to start at 0 on every axis. */
object Orientation {
    fun normalize(cells: List<Vec3i>): List<Vec3i> {
        val mx = cells.minOf { it.x }
        val my = cells.minOf { it.y }
        val mz = cells.minOf { it.z }
        return cells.map { Vec3i(it.x - mx, it.y - my, it.z - mz) }
    }

    /** 90° turn around the vertical axis. */
    fun yaw(cells: List<Vec3i>) = normalize(cells.map { Vec3i(-it.z, it.y, it.x) })

    /**
     * Tips the piece 90° over a horizontal axis so long pieces stand up / lie down.
     * Rotates around Z when the piece is at least as wide in X as in Z, otherwise around X,
     * so tipping a standing bar lays it back down and vice versa.
     */
    fun tip(cells: List<Vec3i>): List<Vec3i> {
        val ex = extent(cells) { it.x }
        val ez = extent(cells) { it.z }
        return if (ex >= ez) {
            normalize(cells.map { Vec3i(-it.y, it.x, it.z) })
        } else {
            normalize(cells.map { Vec3i(it.x, it.z, -it.y) })
        }
    }

    fun extent(cells: List<Vec3i>, axis: (Vec3i) -> Int): Int {
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        for (c in cells) {
            lo = min(lo, axis(c))
            hi = max(hi, axis(c))
        }
        return hi - lo + 1
    }
}

/**
 * What to spawn: a [type] and its [cells], optionally with a shop [special] cube attached to a regular piece.
 * The attached cube is always the last cell; cell order survives rotation, so that stays valid.
 */
data class PieceSpec(val type: PieceType, val cells: List<Vec3i> = type.cells, val special: PieceType? = null) {
    val specialIndex: Int get() = if (special == null) -1 else cells.lastIndex

    /** Anything but a plain regular piece: specials never come twice in a row. */
    val isSpecial: Boolean get() = type.kind != PieceType.Kind.NORMAL || special != null

    /** The block each cell is: the attached special, the X cube (its piece's own type), or the plain stacking type. */
    fun cellType(i: Int): PieceType = when {
        i == specialIndex -> special!!
        type.isX(i) -> type
        else -> type.stackType
    }

    companion object {
        /** No piece is ever longer than this along any axis (except No Fall's huge pieces). */
        const val MAX_LENGTH = 3

        /**
         * [base] with a [special] cube attached to one of its sides, picked at random among spots that keep it short
         * enough: at most [MAX_LENGTH], or the piece's own longest side if it's already longer (huge pieces).
         */
        fun attached(base: PieceType, special: PieceType, random: kotlin.random.Random): PieceSpec {
            val cells = base.cells
            val maxLength = max(MAX_LENGTH, max(Orientation.extent(cells) { it.x }, Orientation.extent(cells) { it.z }))
            val maxX = maxLength
            val maxZ = maxLength
            val spots = cells.flatMap { c ->
                listOf(Vec3i(c.x + 1, c.y, c.z), Vec3i(c.x - 1, c.y, c.z), Vec3i(c.x, c.y, c.z + 1), Vec3i(c.x, c.y, c.z - 1))
            }.distinct().filter { spot ->
                spot !in cells && (cells + spot).let { all ->
                    Orientation.extent(all) { it.x } <= maxX && Orientation.extent(all) { it.z } <= maxZ
                }
            }
            // Some large pieces (the ring) have no free side that stays short enough: the special comes alone.
            if (spots.isEmpty()) return PieceSpec(special)
            return PieceSpec(base, Orientation.normalize(cells + spots.random(random)), special)
        }
    }
}

/** A falling piece: local [cells] plus the world position of its origin. */
class Piece(val spec: PieceSpec, var x: Int, var y: Int, var z: Int) {
    val type: PieceType get() = spec.type
    var cells: List<Vec3i> = Orientation.normalize(spec.cells)

    fun cellType(i: Int) = spec.cellType(i)

    fun worldCells(): List<Vec3i> = cells.map { Vec3i(it.x + x, it.y + y, it.z + z) }
}

/**
 * Shuffled-bag randomizer: every piece type once per bag, in random order. Large pieces unlocked by [height]
 * join from the next bag on.
 */
class PieceBag(private val random: kotlin.random.Random) {
    private val bag = ArrayDeque<PieceType>()

    /** No Fall mode: the huge pieces join too. */
    var noFall = false
        set(value) { field = value; bag.clear() }

    fun next(height: Int = 0): PieceType {
        if (bag.isEmpty()) bag.addAll(PieceType.regularsAt(height, noFall).shuffled(random))
        return bag.removeFirst()
    }

    /** Newly unlocked pieces join the current bag at random spots, so they show up right away. */
    fun unlock(types: List<PieceType>) {
        for (t in types) bag.add(random.nextInt(bag.size + 1), t)
    }
}
