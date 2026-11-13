package com.stacker3d.shop

import com.stacker3d.logic.PieceType

/**
 * A background color scheme: gradient keys by tower height, plus when stars fade in. [sunGlow] adds a
 * soft sun that fades out as the sky darkens.
 */
class SkyTheme(val id: String, val name: String, val keys: List<SkyKey>, val starsFrom: Float, val sunGlow: Boolean = false)

/** Sky gradient at a tower height, colors as 0xRRGGBB. */
class SkyKey(val height: Float, val top: Int, val bottom: Int)

/** Board colors under the tower, as 0xRRGGBB: the 3x3 [tiles], the [base] slab, and the raised [rim] around it. */
class BoardStyle(val tiles: Int, val base: Int, val rim: Int)

/** Block look: [rgb] paints every plain block one color; null keeps the per-piece colors. */
class BlockSkin(
    val id: String,
    val name: String,
    val rgb: Int?,
    val price: Int = 100,
    val description: String = "",
    /** Below 1, plain blocks are see-through and glossy. */
    val opacity: Float = 1f,
    /** Looks each plain block picks from (needs [rgb]); empty means plain colored cubes. */
    val styles: List<BlockStyle> = emptyList(),
    /** 0..1: how much each plain block's shade varies from the next. */
    val variety: Float = 0f,
    /** Tower cubes squash and wobble when something lands on or near them. */
    val jiggly: Boolean = false,
    /** Wobble speed (radians/s) and how fast it dies down: lower is gooier. */
    val wobbleFreq: Float = 26f,
    val wobbleDamp: Float = 5f,
    /**
     * Blocks sag, grow drips down over the block below once they land, and stick to their neighbors, slowly
     * spreading wider under the weight of the blocks above.
     */
    val melts: Boolean = false,
    /** Sound file (sfx/<name>.wav) played on landing instead of the usual thud. */
    val landSound: String? = null,
    val landPitch: Float = 1f,
    /** The board the tower starts on. */
    val board: BoardStyle = BoardStyle(0xF2F6FC, 0x2B3A67, 0x5B7CFA),
)

/** A look for one plain block of a fancy skin; a skin mixes several. */
enum class BlockStyle {
    /** A few small white bubbles. */
    BUBBLY,
    /** Extra see-through, nearly bubble-free. */
    CLEAR,
    /** A darker blob suspended inside. */
    CORE,
    /** Milky white swirls. */
    CLOUDY,
    /** Marbled with white and a deeper shade. */
    SWIRL,
    /** A deeper, more saturated shade with a few specks. */
    HOT,
}

enum class ShopTab(val title: String) { BACKGROUNDS("BACKGROUNDS"), BLOCKS("BLOCKS"), SPECIALS("SPECIALS") }

/**
 * A special block sold in the shop. Buying it unlocks level 1; each upgrade adds [chancePerLevel] to its spawn
 * chance, up to [MAX_LEVEL].
 */
class SpecialBlock(
    val type: PieceType,
    val id: String,
    val name: String,
    /** Short effect line for the shop row. */
    val effect: String,
    val price: Int,
    val baseChance: Float,
    val chancePerLevel: Float,
    /** Face shown in the shop row. */
    val texture: String,
) {
    fun chance(level: Int) = if (level <= 0) 0f else baseChance + (level.coerceAtMost(MAX_LEVEL) - 1) * chancePerLevel

    /** Coins to go from [level] to the next one. */
    fun upgradeCost(level: Int) = price * level / 2

    companion object {
        const val MAX_LEVEL = 5
    }
}

class ShopItem(
    val id: String,
    val tab: ShopTab,
    val name: String,
    val price: Int,
    val description: String = "",
    val special: SpecialBlock? = null,
) {
    /** Backgrounds and block skins are equipped one at a time; specials are simply unlocked. */
    val equippable get() = tab != ShopTab.SPECIALS
}

object Catalog {
    val classicSky = SkyTheme(
        "bg_classic", "CLASSIC SKY",
        listOf(
            SkyKey(0f, 0x3CCBF4, 0x00FFFF),
            SkyKey(15f, 0x4AA3E8, 0xFFEFA8),
            SkyKey(30f, 0xFF7E5F, 0xFFB8C9),
            SkyKey(50f, 0x1E2A78, 0x6A3FA0),
            SkyKey(75f, 0x050818, 0x0E1A3D),
            SkyKey(100f, 0x000000, 0x06060F),
        ),
        starsFrom = 55f,
        sunGlow = true,
    )

    /** Example paid theme: synthwave pinks and purples under a starry sky from the start. */
    val neonSky = SkyTheme(
        "bg_neon", "NEON NIGHTS",
        listOf(
            SkyKey(0f, 0x2B0B4F, 0xFF4FA3),
            SkyKey(15f, 0x1A0F5C, 0xFF7A59),
            SkyKey(30f, 0x120A3D, 0xB13BFF),
            SkyKey(50f, 0x0A0F33, 0x00C2FF),
            SkyKey(75f, 0x05031A, 0x3B0A57),
            SkyKey(100f, 0x000000, 0x0B0420),
        ),
        starsFrom = -20f,
    )

    /** Warm desert evening: sandy gold fading through burnt orange into a deep violet night. */
    val duneSky = SkyTheme(
        "bg_dune", "DESERT DUSK",
        listOf(
            SkyKey(0f, 0xF2A65A, 0xFFE0A3),
            SkyKey(15f, 0xE76F51, 0xF4A261),
            SkyKey(30f, 0xB5446E, 0xF28482),
            SkyKey(50f, 0x4A1E5C, 0x9D4E7A),
            SkyKey(75f, 0x1A0B2E, 0x3A1C47),
            SkyKey(100f, 0x05020C, 0x120818),
        ),
        starsFrom = 45f,
        sunGlow = true,
    )

    /** Polar night: teal and green aurora bands over a dark sky, stars almost from the start. */
    val auroraSky = SkyTheme(
        "bg_aurora", "AURORA",
        listOf(
            SkyKey(0f, 0x0B2A3C, 0x1FD1A0),
            SkyKey(15f, 0x0A2340, 0x38F2C2),
            SkyKey(30f, 0x14174A, 0x5BE37D),
            SkyKey(50f, 0x1A0F45, 0x7A5CFF),
            SkyKey(75f, 0x060A24, 0x153A4A),
            SkyKey(100f, 0x000000, 0x040A12),
        ),
        starsFrom = 5f,
    )

    /** Soft pastel daydream: cotton-candy pinks and lavenders that drift into a dreamy lilac night. */
    val candySky = SkyTheme(
        "bg_candy", "COTTON CANDY",
        listOf(
            SkyKey(0f, 0xA0D8FF, 0xFFD6EC),
            SkyKey(15f, 0xC3B5FF, 0xFFC2E2),
            SkyKey(30f, 0xFF9FD0, 0xFFE3B3),
            SkyKey(50f, 0x8E7BEF, 0xF3A6E0),
            SkyKey(75f, 0x3B2C7A, 0x7C5BB8),
            SkyKey(100f, 0x120C2E, 0x2A1A52),
        ),
        starsFrom = 60f,
        sunGlow = true,
    )

    val skies = listOf(classicSky, neonSky, duneSky, auroraSky, candySky)

    val skins = listOf(
        BlockSkin("skin_classic", "CLASSIC MIX", null, price = 0),
        BlockSkin("skin_ruby", "RUBY", 0xE63946, board = BoardStyle(0xF7DADD, 0x3D0A12, 0xE63946)),
        BlockSkin("skin_gold", "GOLD", 0xFFC93C, board = BoardStyle(0xFFF6D6, 0x3B2A0A, 0xD4A017)),
        // Id kept from the old Mint skin so players who bought it keep it.
        BlockSkin("skin_mint", "EMERALD", 0x138A4E, board = BoardStyle(0xCFEBD9, 0x082A1A, 0x1FA463)),
        BlockSkin("skin_ocean", "OCEAN", 0x2F80ED, board = BoardStyle(0xDCEBFF, 0x0A1F44, 0x56CCF2)),
        BlockSkin("skin_snow", "SNOW", 0xEEF3F8, board = BoardStyle(0xB9D3E6, 0x2C3E50, 0xFFFFFF)),
        BlockSkin(
            "skin_slime", "SLIME", 0x3CC43A, price = 300, description = "JIGGLES WHEN HIT",
            board = BoardStyle(0xD9F7C8, 0x16341A, 0x8CFF5A),
            opacity = 0.8f, variety = 0.45f, jiggly = true, landSound = "squelch",
            styles = listOf(BlockStyle.BUBBLY, BlockStyle.CLEAR, BlockStyle.CORE, BlockStyle.CLOUDY),
        ),
        BlockSkin(
            "skin_gum", "BUBBLE GUM", 0xFF7EB9, price = 300, description = "GOOEY, SAGS AND DRIPS",
            board = BoardStyle(0xFFE3F0, 0x5C1238, 0xFF4FA3),
            variety = 0.4f, jiggly = true, wobbleFreq = 17f, wobbleDamp = 3.2f, melts = true,
            styles = listOf(BlockStyle.BUBBLY, BlockStyle.SWIRL, BlockStyle.HOT),
            landSound = "squelch", landPitch = 0.8f,
        ),
    )

    /**
     * Spawn chances are per piece. Hearts stay scarce (X pieces alone are 8%) so losing one still matters:
     * about one heal per run at level 1, two or three fully upgraded. All eight together top out near 20%.
     */
    val specials = listOf(
        SpecialBlock(PieceType.HEART, "special_heart", "HEART BLOCK", "+1 HEART", 250, 0.004f, 0.0015f, "textures/cube_heart.png"),
        SpecialBlock(PieceType.GOLD, "special_gold", "GOLDEN BLOCK", "+2 COINS", 150, 0.02f, 0.005f, "textures/cube_gold.png"),
        SpecialBlock(
            PieceType.MULTIPLIER, "special_multiplier", "MULTIPLIER BLOCK", "+0.1x SCORE", 300, 0.01f, 0.0025f,
            "textures/cube_multiplier.png",
        ),
        SpecialBlock(
            PieceType.CRACKED, "special_cracked", "CRACKED BLOCK", "+40 PTS, CRUMBLES", 100, 0.02f, 0.005f,
            "textures/cube_cracked.png",
        ),
        SpecialBlock(PieceType.MYSTERY, "special_mystery", "MYSTERY BLOCK", "ANY EFFECT", 500, 0.003f, 0.001f, "textures/cube_mystery.png"),
        SpecialBlock(
            PieceType.DYNAMITE, "special_dynamite", "DYNAMITE", "BLOWS UP A LAYER", 250, 0.01f, 0.0035f,
            "textures/cube_dynamite.png",
        ),
        SpecialBlock(
            PieceType.GLASS, "special_glass", "GLASS BLOCK", "+60 PTS, FRAGILE", 150, 0.015f, 0.00375f,
            "textures/cube_glass.png",
        ),
        SpecialBlock(PieceType.FREEZE, "special_freeze", "FREEZE BLOCK", "SLOWS 5 PIECES", 150, 0.015f, 0.00375f, "textures/cube_freeze.png"),
    )

    val items: List<ShopItem> =
        skies.map { ShopItem(it.id, ShopTab.BACKGROUNDS, it.name, if (it === classicSky) 0 else 100) } +
            skins.map { ShopItem(it.id, ShopTab.BLOCKS, it.name, it.price, it.description) } +
            specials.map { ShopItem(it.id, ShopTab.SPECIALS, it.name, it.price, special = it) }

    fun sky(id: String) = skies.firstOrNull { it.id == id } ?: classicSky

    fun skin(id: String) = skins.firstOrNull { it.id == id } ?: skins.first()
}
