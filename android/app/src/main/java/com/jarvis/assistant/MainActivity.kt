package com.jarvis.assistant

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsetsController
import com.jarvis.assistant.activation.JarvisActivationController
import com.jarvis.assistant.core.JarvisCoreView
import com.jarvis.assistant.speech.AndroidTtsSpeechController
import com.jarvis.assistant.speech.JarvisSpeechController

class MainActivity : Activity() {

    private lateinit var core: JarvisCoreView
    private lateinit var speech: JarvisSpeechController
    private lateinit var activation: JarvisActivationController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        setContentView(R.layout.activity_main)
        configureSystemBars()

        core = findViewById(R.id.jarvis_core)

        speech = AndroidTtsSpeechController(this)
        activation = JarvisActivationController(speech)
        activation.bind(core)
    }

    override fun onDestroy() {
        activation.unbind()
        speech.shutdown()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun configureSystemBars() {
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
