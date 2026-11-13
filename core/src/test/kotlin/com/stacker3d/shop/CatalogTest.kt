package com.stacker3d.shop

import com.stacker3d.logic.GameLogic
import com.stacker3d.logic.PieceType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogTest {
    /** Built into every run (spec §6); everything else that isn't a regular piece must be bought in the shop. */
    private val builtIn = setOf(PieceType.Kind.NORMAL, PieceType.Kind.BOMB, PieceType.Kind.X)

    @Test
    fun `every shop-only special block is sold in the shop`() {
        val specials = PieceType.entries.filter { it.kind !in builtIn }.toSet()
        assertEquals(specials, PieceType.unlockables.toSet())
        assertEquals(specials, Catalog.specials.map { it.type }.toSet())
    }

    @Test
    fun `no shop special spawns before it is bought`() {
        val logic = GameLogic(Random(7)).apply { specialChances = emptyMap(); immortal = true; start() }
        repeat(3000) {
            while (logic.current == null && !logic.isGameOver) logic.update(0.1f)
            if (logic.isGameOver) logic.start()
            val p = logic.current!!
            assertTrue(p.type !in PieceType.unlockables && p.spec.special == null, "${p.type} spawned unowned")
            logic.hardDrop()
        }
    }
}
