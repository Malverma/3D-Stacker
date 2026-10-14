package com.stacker3d.lwjgl3

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.stacker3d.DebugOptions
import com.stacker3d.StackerGame

/**
 * Desktop build for quick testing. Environment variables:
 *  STACKER_AUTOPLAY=1, STACKER_CHAOS=1 (random placements), STACKER_IMMORTAL=1, STACKER_DELAY=<seconds per piece>,
 *  STACKER_START=menu|game|paused|settings|shop_backgrounds|shop_blocks|shop_specials, STACKER_SHOTS=<file prefix>, STACKER_SHOT_TIMES=2,5,9
 */
fun main() {
    val env = System.getenv()
    val debug = DebugOptions(
        autoplay = env["STACKER_AUTOPLAY"] == "1",
        immortal = env["STACKER_IMMORTAL"] == "1",
        chaos = env["STACKER_CHAOS"] == "1",
        autoplayDelay = env["STACKER_DELAY"]?.toFloatOrNull() ?: 0.25f,
        screenshotPrefix = env["STACKER_SHOTS"],
        screenshotTimes = env["STACKER_SHOT_TIMES"]?.split(',')?.mapNotNull { it.trim().toFloatOrNull() } ?: emptyList(),
        startScreen = env["STACKER_START"] ?: "menu",
    )
    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("3D Stacker")
        setWindowedMode(450, 900)
        setResizable(false)
        useVsync(true)
        setForegroundFPS(60)
        setBackBufferConfig(8, 8, 8, 8, 16, 0, 4)
    }
    Lwjgl3Application(StackerGame(debug), config)
}
