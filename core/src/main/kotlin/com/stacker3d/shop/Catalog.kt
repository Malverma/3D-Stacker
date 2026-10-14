package com.stacker3d.shop

/** A background color scheme: gradient keys by tower height, plus when stars fade in. */
class SkyTheme(val id: String, val name: String, val keys: List<SkyKey>, val starsFrom: Float)

/** Sky gradient at a tower height, colors as 0xRRGGBB. */
class SkyKey(val height: Float, val top: Int, val bottom: Int)

/** Block look: [rgb] paints every plain block one color; null keeps the per-piece colors. */
class BlockSkin(val id: String, val name: String, val rgb: Int?)

enum class ShopTab(val title: String) { BACKGROUNDS("BACKGROUNDS"), BLOCKS("BLOCKS"), SPECIALS("SPECIALS") }

class ShopItem(val id: String, val tab: ShopTab, val name: String, val price: Int, val description: String = "") {
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

    val skies = listOf(classicSky, neonSky)

    val skins = listOf(
        BlockSkin("skin_classic", "CLASSIC MIX", null),
        BlockSkin("skin_ruby", "ALL RUBY", 0xE63946),
        BlockSkin("skin_gold", "ALL GOLD", 0xFFC93C),
        BlockSkin("skin_mint", "ALL MINT", 0x3DDC97),
        BlockSkin("skin_ocean", "ALL OCEAN", 0x2F80ED),
        BlockSkin("skin_grape", "ALL GRAPE", 0x9B5DE5),
        BlockSkin("skin_snow", "ALL SNOW", 0xEEF3F8),
    )

    const val HEART_BLOCK_ID = "special_heart"

    val items: List<ShopItem> =
        skies.map { ShopItem(it.id, ShopTab.BACKGROUNDS, it.name, if (it === classicSky) 0 else 100) } +
            skins.map { ShopItem(it.id, ShopTab.BLOCKS, it.name, if (it.rgb == null) 0 else 100) } +
            ShopItem(HEART_BLOCK_ID, ShopTab.SPECIALS, "HEART BLOCK", 250, "+1 HEART · 1% SPAWN")

    fun sky(id: String) = skies.firstOrNull { it.id == id } ?: classicSky

    fun skin(id: String) = skins.firstOrNull { it.id == id } ?: skins.first()
}
