package com.jarvis.assistant.command

/**
 * Apps JARVIS can open by voice.
 *
 * To add an app: add an [AppEntry] below AND add its package to `<queries>` in AndroidManifest.xml
 * (Android 11+ package visibility; without it `getLaunchIntentForPackage` cannot see the app).
 * [aliases] are matched after normalization (see PersianNormalizer), so write them as people say them.
 */
data class AppEntry(
    val id: String,
    val label: String,
    val packageNames: List<String>,
    val aliases: List<String>
)

class AppRegistry(val entries: List<AppEntry>) {
    companion object {
        fun default() = AppRegistry(
            listOf(
                AppEntry(
                    id = "instagram", label = "اینستاگرام",
                    packageNames = listOf("com.instagram.android"),
                    aliases = listOf("اینستاگرام", "اینستا گرام", "اینستا", "اینستگرام", "ایستاگرام", "اینستاگرم", "اینستا گرم", "اینستاگرامم", "instagram", "insta")
                ),
                AppEntry(
                    id = "chrome", label = "کروم",
                    packageNames = listOf("com.android.chrome"),
                    aliases = listOf("گوگل کروم", "کروم", "کرووم", "کرم", "chrome")
                ),
                AppEntry(
                    id = "youtube", label = "یوتیوب",
                    packageNames = listOf("com.google.android.youtube"),
                    aliases = listOf("یوتیوب", "یوتوب", "یوتیوپ", "یو تیوب", "یو توب", "یوتیوبم", "youtube")
                )
            )
        )
    }
}
