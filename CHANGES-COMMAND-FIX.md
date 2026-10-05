# JARVIS-COMMAND-FIX (command recognition only)

Why only "برو داخل اینستاگرام" worked: the old rules needed exact spellings/tokens. App names needed an explicit verb
or nothing else in the sentence, glued/variant words ("بازکن", "روشنکن", "صدارو", "بلوتوس", "تنضیمات") matched no
keyword, "برو خونه" had no rule, bare "تایمر"/"آلارم" returned nothing, "بلوتوث رو روشن کن" was rejected,
and settings/back rejected any extra word. "برو" in "برو داخل ..." happened to be an open-verb, so that one passed.

Fix (threshold unchanged, 0.7): CommandIntentParser now canonicalizes every token (phonetic folding ث/ص→س ذ/ض/ظ→ز ط→ت ح→ه غ→ق,
glued "…رو" / "…کن" split, one-typo match for keywords of 5+ letters) and matches entities by keyword (apps, wifi,
bluetooth, چراغ قوه) with filler words ignored. Short/ambiguous words ("خونه", "کرم") need an opening verb.
Unrelated sentences stay Unknown. BasicConversation: lenient greeting / how-are-you / name / "what can you do".
Every JarvisAction has a parser path and an executor branch (13/13 + Unknown).
Logcat tag JarvisConversation now logs "Brain decided: <kind> <action> conf=" (no user text).
Untouched: Gyro TTS, Vosk wake word, Arc Reactor, overlay, UI, memory, Gradle, manifest.
