package com.stacker3d.logic

/**
 * The tower. Cubes always settle onto the cube below (spec §4), so every column is solid
 * and the whole tower is just a stack of piece types per cell (kept for their colors).
 *
 * The footprint starts as the classic 3×3 (cells 0..2). In No Fall mode it can grow outward by up to
 * [MAX_GROW] cells on every side, so cells may have negative coordinates. Columns are stored for the whole
 * growable area and addressed with [index]; only [inside] cells are part of the board.
 *
 * A grown cell starts at the level its first cube was hanging at ([base]): an invisible floor there with air
 * underneath, so its column holds cubes from [base] up. The air below counts as filled for collisions and
 * heights, like the rest of a solid column.
 */
class Board {
    private val columns = Array(SPAN * SPAN) { ArrayList<PieceType>() }

    /**
     * Per cube, parallel to [columns]: a countdown for cubes that have one, 0 otherwise. Cracked cubes: how far below
     * the top they can sit before they crumble. Dynamite: landings left before it goes off.
     */
    private val fuses = Array(SPAN * SPAN) { ArrayList<Int>() }

    private val active = BooleanArray(SPAN * SPAN)

    /** Per cell: the layer its floor sits at (0 for the starting 3×3 and cells grown at ground level). */
    private val bases = IntArray(SPAN * SPAN)

    /** Indices ([index]) of every board cell, in the order they joined. */
    val cells = ArrayList<Int>()

    /** Footprint bounds (inclusive). */
    var minX = 0; private set
    var maxX = SIZE - 1; private set
    var minZ = 0; private set
    var maxZ = SIZE - 1; private set

    init { clear() }

    fun inside(x: Int, z: Int) = inRange(x, z) && active[index(x, z)]

    /** A cell that No Fall mode could add to the footprint. */
    fun canGrow(x: Int, z: Int) = inRange(x, z) && !active[index(x, z)]

    /** Adds cell ([x], [z]) to the footprint with its floor at layer [level]; false if it was already on it or out of range. */
    fun grow(x: Int, z: Int, level: Int = 0): Boolean {
        if (!canGrow(x, z)) return false
        bases[index(x, z)] = level
        activate(x, z)
        return true
    }

    /** The layer cell ([x], [z])'s floor sits at. */
    fun base(x: Int, z: Int) = bases[index(x, z)]

    fun baseAt(i: Int) = bases[i]

    /** The footprint is still the starting 3×3. */
    val isClassic: Boolean get() = cells.size == SIZE * SIZE

    /** Top of column ([x], [z]): its floor plus the cubes on it. */
    fun height(x: Int, z: Int) = heightAt(index(x, z))

    private fun heightAt(i: Int) = bases[i] + columns[i].size

    fun typeAt(x: Int, y: Int, z: Int): PieceType = columns[index(x, z)][y - base(x, z)]

    fun fuseAt(x: Int, y: Int, z: Int): Int = fuses[index(x, z)][y - base(x, z)]

    fun setFuse(x: Int, y: Int, z: Int, fuse: Int) {
        fuses[index(x, z)][y - base(x, z)] = fuse
    }

    fun maxHeight() = cells.maxOf { heightAt(it) }

    fun minHeight() = cells.minOf { heightAt(it) }

    /** Removes the cube at layer [y]; the cubes above it drop one layer. */
    fun removeAt(x: Int, y: Int, z: Int): PieceType {
        val b = base(x, z)
        fuses[index(x, z)].removeAt(y - b)
        return columns[index(x, z)].removeAt(y - b)
    }

    /** Column heights for the whole growable area, indexed by [index] (cells off the board are 0). */
    fun heights() = IntArray(SPAN * SPAN) { if (active[it]) heightAt(it) else 0 }

    /** Stacks a cube on column ([x], [z]) and returns the layer it sits at. */
    fun push(x: Int, z: Int, type: PieceType, fuse: Int = 0): Int {
        val col = columns[index(x, z)]
        col.add(type)
        fuses[index(x, z)].add(fuse)
        return height(x, z) - 1
    }

    fun clear() {
        columns.forEach { it.clear() }
        fuses.forEach { it.clear() }
        active.fill(false)
        bases.fill(0)
        cells.clear()
        minX = 0; maxX = SIZE - 1; minZ = 0; maxZ = SIZE - 1
        for (z in 0 until SIZE) for (x in 0 until SIZE) activate(x, z)
    }

    private fun activate(x: Int, z: Int) {
        val i = index(x, z)
        active[i] = true
        cells += i
        minX = minOf(minX, x); maxX = maxOf(maxX, x)
        minZ = minOf(minZ, z); maxZ = maxOf(maxZ, z)
    }

    companion object {
        const val SIZE = 3
        /** No Fall mode: how many cells the board may grow past the starting 3×3 on each side. */
        const val MAX_GROW = 8
        /** Width of the growable area. */
        const val SPAN = SIZE + 2 * MAX_GROW
        const val MIN = -MAX_GROW
        const val MAX = SIZE - 1 + MAX_GROW

        fun inRange(x: Int, z: Int) = x in MIN..MAX && z in MIN..MAX
        fun index(x: Int, z: Int) = (x - MIN) + (z - MIN) * SPAN
        fun xOf(i: Int) = i % SPAN + MIN
        fun zOf(i: Int) = i / SPAN + MIN

        /** Index of a cell of the starting 3×3, given as x + z * 3 (e.g. a layout table). */
        fun classicIndex(i: Int) = index(i % SIZE, i / SIZE)
    }
}
