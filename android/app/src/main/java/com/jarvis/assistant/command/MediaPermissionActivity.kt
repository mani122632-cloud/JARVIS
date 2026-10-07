package com.jarvis.assistant.command

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle

/**
 * Transparent screen that only asks for the runtime permission to read the phone's audio / video files
 * (needed by [MediaTool] to play a song or movie that is already on the phone), then closes.
 *
 *  - Android 13+ (API 33): READ_MEDIA_AUDIO + READ_MEDIA_VIDEO
 *  - Android 12 and below: READ_EXTERNAL_STORAGE
 *
 * Started by [MediaTool] (FLAG_ACTIVITY_NEW_TASK); declared in AndroidManifest.xml (not exported, translucent theme).
 */
class MediaPermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val missing = REQUIRED().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { MediaTool.onPermissionResult(true); finish(); return }
        requestPermissions(missing.toTypedArray(), REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // MediaTool re-runs the waiting command itself (it re-checks the permission it needs: audio or video).
        MediaTool.onPermissionResult(grantResults.isNotEmpty() && grantResults.any { it == PackageManager.PERMISSION_GRANTED })
        finish()
    }

    override fun onDestroy() {
        // Closed without an answer (e.g. the user left the screen): do not leave the command waiting.
        MediaTool.onPermissionResult(false)
        super.onDestroy()
    }

    private companion object {
        const val REQUEST_CODE = 71

        /** String names: READ_MEDIA_* constants only exist from compileSdk 33. */
        @Suppress("FunctionName")
        fun REQUIRED(): List<String> =
            if (Build.VERSION.SDK_INT >= 33) listOf("android.permission.READ_MEDIA_AUDIO", "android.permission.READ_MEDIA_VIDEO")
            else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}
