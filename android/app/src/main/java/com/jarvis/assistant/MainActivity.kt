package com.jarvis.assistant

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsetsController
import com.jarvis.assistant.ui.JarvisCoreView
import com.jarvis.assistant.ui.JarvisState

class MainActivity : Activity() {

    private lateinit var core: JarvisCoreView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        setContentView(R.layout.activity_main)
        // Must run after setContentView: window.insetsController can throw
        // (NullPointerException on some Android 11 builds) before the decor view exists.
        configureSystemBars()

        core = findViewById(R.id.jarvis_core)
        core.state = JarvisState.READY
    }

    @Suppress("DEPRECATION")
    private fun configureSystemBars() {
        // Purely cosmetic (light icons on black); must never be able to crash startup.
        runCatching {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.BLACK
            if (Build.VERSION.SDK_INT >= 30) {
                window.insetsController?.setSystemBarsAppearance(
                    0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                )
            } else {
                val decor = window.decorView
                decor.systemUiVisibility = decor.systemUiVisibility and
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and
                    View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
        }
    }
}
