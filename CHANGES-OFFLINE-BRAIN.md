# JARVIS Offline Conversational Core

Pipeline (unchanged): Voice → JarvisCommandSpeechController → JarvisConversationController → JarvisBrain
(memory → LOCAL_COMMAND via JarvisCommandProcessor → JarvisActionExecutor | CONVERSATION | UNKNOWN).

Changed files
- NEW  brain/OfflineConversationBrain.kt — local rule-based small talk (greeting, goodbye, thanks, how are you,
       name / identity, abilities, offline info, usage help, weekday from the device clock, a few trivial facts).
       Requests that need live data (weather, news, search, prices) answer "برای این مورد باید به اینترنت وصل باشم."
- DEL  brain/BasicConversation.kt — replaced by the class above (no duplicate).
- EDIT brain/DefaultJarvisBrain.kt — uses OfflineConversationBrain; local commands still win; "خداحافظ" gets a farewell.
- EDIT speech/JarvisPhrases.kt — new fixed phrases (+ prewarm).
- EDIT speech/JarvisCommandSpeechController.kt — EXTRA_PREFER_OFFLINE hint only; recognizer NOT replaced.

Untouched: Vosk wake word, Gyro TTS, arc reactor / overlay / UI, memory, command parser and executor.
No network API added, no model files in this ZIP.
