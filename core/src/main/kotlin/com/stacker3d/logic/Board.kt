package com.stacker3d.logic

/**
 * The 3×3 tower. Cubes always settle onto the cube below (spec §4), so every column is solid
 * and the whole tower is just 9 stacks of piece types (kept for their colors).
 */
class Board {
    private val columns = Array(SIZE * SIZE) { ArrayList<PieceType>() }

    fun inside(x: Int, z: Int) = x in 0 until SIZE && z in 0 until SIZE

    fun height(x: Int, z: Int) = columns[x + z * SIZE].size

    fun typeAt(x: Int, y: Int, z: Int): PieceType = columns[x + z * SIZE][y]

    fun maxHeight() = columns.maxOf { it.size }

    fun minHeight() = columns.minOf { it.size }

    fun removeAt(x: Int, y: Int, z: Int): PieceType = columns[x + z * SIZE].removeAt(y)

    fun heights() = IntArray(SIZE * SIZE) { columns[it].size }

    fun push(x: Int, z: Int, type: PieceType): Int {
        val col = columns[x + z * SIZE]
        col.add(type)
        return col.size - 1
    }

    fun clear() = columns.forEach { it.clear() }

    companion object {
        const val SIZE = 3
    }
}
