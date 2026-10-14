package com.stacker3d.android

import android.os.Bundle
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration
import com.stacker3d.StackerGame

class AndroidLauncher : AndroidApplication() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = AndroidApplicationConfiguration().apply {
            useImmersiveMode = true
            useAccelerometer = false
            useCompass = false
            useGyroscope = false
            numSamples = 2
            depth = 16
        }
        initialize(StackerGame(), config)
    }
}
