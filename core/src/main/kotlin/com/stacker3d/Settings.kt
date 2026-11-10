package com.stacker3d

import com.badlogic.gdx.Preferences
import com.stacker3d.logic.PieceType
import com.stacker3d.shop.Catalog
import com.stacker3d.shop.ShopItem
import com.stacker3d.shop.SpecialBlock

/** Persistent settings and records (spec §8, §9). */
class Settings(private val prefs: Preferences) {
    var highScore: Int
        get() = prefs.getInteger("highScore", 0)
        set(v) { prefs.putInteger("highScore", v).flush() }

    var bestHeight: Int
        get() = prefs.getInteger("bestHeight", 0)
        set(v) { prefs.putInteger("bestHeight", v).flush() }

    var sound: Boolean
        get() = prefs.getBoolean("sound", true)
        set(v) { prefs.putBoolean("sound", v).flush() }

    var vibration: Boolean
        get() = prefs.getBoolean("vibration", true)
        set(v) { prefs.putBoolean("vibration", v).flush() }

    var showButtons: Boolean
        get() = prefs.getBoolean("showButtons", true)
        set(v) { prefs.putBoolean("showButtons", v).flush() }

    // ---- coins & shop -------------------------------------------------------------------

    var coins: Int
        get() = prefs.getInteger("coins", 0)
        set(v) { prefs.putInteger("coins", v).flush() }

    private var owned: Set<String>
        get() = prefs.getString("owned", "").split(',').filter { it.isNotEmpty() }.toSet()
        set(v) { prefs.putString("owned", v.joinToString(",")).flush() }

    var skyId: String
        get() = prefs.getString("sky", Catalog.classicSky.id)
        set(v) { prefs.putString("sky", v).flush() }

    var skinId: String
        get() = prefs.getString("skin", Catalog.skins.first().id)
        set(v) { prefs.putString("skin", v).flush() }

    fun owns(item: ShopItem) = item.price == 0 || item.id in owned

    fun isEquipped(item: ShopItem) = item.id == skyId || item.id == skinId

    /** Spends coins on [item]; equippable items are equipped right away. */
    fun buy(item: ShopItem): Boolean {
        if (owns(item) || coins < item.price) return false
        coins -= item.price
        owned = owned + item.id
        equip(item)
        return true
    }

    fun equip(item: ShopItem) {
        if (!owns(item) || !item.equippable) return
        if (Catalog.skies.any { it.id == item.id }) skyId = item.id else skinId = item.id
    }

    // ---- special block upgrades ---------------------------------------------------------

    /** 0 until bought; buying sets level 1 (owners from before upgrades existed also start there). */
    fun level(special: SpecialBlock): Int = if (special.id in owned) prefs.getInteger("level_${special.id}", 1) else 0

    fun canUpgrade(special: SpecialBlock): Boolean {
        val level = level(special)
        return level in 1 until SpecialBlock.MAX_LEVEL && coins >= special.upgradeCost(level)
    }

    fun upgrade(special: SpecialBlock): Boolean {
        if (!canUpgrade(special)) return false
        val level = level(special)
        coins -= special.upgradeCost(level)
        prefs.putInteger("level_${special.id}", level + 1).flush()
        return true
    }

    /** Spawn chances for every unlocked special, at its current level. */
    fun specialChances(): Map<PieceType, Float> =
        Catalog.specials.filter { level(it) > 0 }.associate { it.type to it.chance(level(it)) }
}
