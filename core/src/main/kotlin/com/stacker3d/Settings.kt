package com.stacker3d

import com.badlogic.gdx.Preferences
import com.stacker3d.shop.Catalog
import com.stacker3d.shop.ShopItem

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

    val heartBlockUnlocked get() = Catalog.HEART_BLOCK_ID in owned
}
