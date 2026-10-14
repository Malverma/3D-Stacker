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
) {
    SINGLE(0xFFD23F, cells("000")),
    DOMINO(0xFF6B6B, cells("000", "100")),
    BAR(0x4D96FF, cells("000", "100", "200")),
    CORNER(0x6BCB77, cells("000", "100", "001")),
    SQUARE(0xB983FF, cells("000", "100", "001", "101")),
    TEE(0xFF9F45, cells("000", "100", "200", "101")),

    /** Clears nearby cubes where it lands; never costs a heart. */
    BOMB(0x2E2E3A, cells("000"), Kind.BOMB),

    /**
     * A domino / bar with an X cube on one end. The plain cubes stack normally; the X cube costs a heart if it
     * lands on the board, so it has to hang off the edge while the rest stays on.
     */
    X_DOMINO(0xFF6B6B, cells("000", "100"), Kind.X, xIndex = 1),
    X_BAR(0x4D96FF, cells("000", "100", "200"), Kind.X, xIndex = 2),

    /** Shop unlock: stays in the tower like a normal cube and restores a heart (up to the max). */
    HEART(0xFF8FB8, cells("000"), Kind.HEART);

    enum class Kind { NORMAL, BOMB, X, HEART }

    /** The type stored in the tower for this piece's plain cubes (X pieces stack as their base shape). */
    val stackType: PieceType
        get() = when (this) {
            X_DOMINO -> DOMINO
            X_BAR -> BAR
            else -> this
        }

    fun isX(cellIndex: Int) = cellIndex == xIndex

    companion object {
        val normals = entries.filter { it.kind == Kind.NORMAL }
        val xPieces = entries.filter { it.kind == Kind.X }
    }
}

private fun cells(vararg xyz: String) = xyz.map { Vec3i(it[0] - '0', it[1] - '0', it[2] - '0') }

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

/** A falling piece: local [cells] plus the world position of its origin. */
class Piece(val type: PieceType, cells: List<Vec3i>, var x: Int, var y: Int, var z: Int) {
    var cells: List<Vec3i> = Orientation.normalize(cells)

    fun worldCells(): List<Vec3i> = cells.map { Vec3i(it.x + x, it.y + y, it.z + z) }
}

/** Shuffled-bag randomizer: every piece type once per bag, in random order. */
class PieceBag(private val random: kotlin.random.Random) {
    private val bag = ArrayDeque<PieceType>()

    fun next(): PieceType {
        if (bag.isEmpty()) bag.addAll(PieceType.normals.shuffled(random))
        return bag.removeFirst()
    }
}
