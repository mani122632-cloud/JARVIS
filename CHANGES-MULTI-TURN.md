# CHANGES — Multi-Turn Conversation (Phase 2)

**Build:** کامپایل/اجرا نشده (محیط Kotlin/Android SDK نداشت). فقط بررسی سطح source (import/brace) و تست mirror پایتونی منطق گفت‌وگو. اولین `assembleDebug` واقعی لازم است.

## 1. فایل‌های تغییرکرده  (مسیر: `android/app/src/main/java/com/jarvis/assistant/`)
جدید:
- `speech/SpeechInput.kt` — interface ورودی گفتار + `SpeechInputError`
- `speech/SpeechInputFactory.kt` — تنها نقطهٔ انتخاب پیاده‌سازی SpeechInput
- `conversation/SessionState.kt` — `IDLE, ACTIVE_LISTENING, PROCESSING, SPEAKING, ENDING`
- `conversation/ConversationContext.kt` — context کوتاه‌مدت جلسه (حداکثر ۶ utterance و ۶ پاسخ، فقط RAM)

تغییرکرده:
- `conversation/JarvisConversationController.kt` — از «یک فرمان» به جلسهٔ چندنوبتی (همان کلاس، موازی نیست)
- `brain/JarvisBrain.kt`, `brain/DefaultJarvisBrain.kt`, `brain/BasicConversation.kt` — `think(text, context)`، خداحافظی، گفت‌وگوی طبیعی‌تر
- `speech/JarvisCommandSpeechController.kt` — فقط `: SpeechInput` شد (adapter موقت؛ بدون کد SpeechRecognizer جدید)
- `speech/JarvisPhrases.kt` — دو عبارت ثابت + PREWARM
- `overlay/JarvisOverlayService.kt` — فقط سیم‌کشی: `SpeechInputFactory`، نگاشت `SessionState` → `AssistantFlow`

دست‌نخورده: Vosk/wakeword، Gyro/TTS، `core/` (Reactor)، `JarvisOverlayWindow`، parser/executor دستورات، Memory دائمی، Manifest، Gradle.

## 2. قابلیت‌های اضافه‌شده
- بعد از «هی جارویس» → «بله ارباب.» → چند جمله پشت‌سرهم بدون Wake Word؛ بعد از هر پاسخ دوباره گوش می‌دهد.
- پایان جلسه: «خداحافظ / فعلاً / تمام / دیگه کاری ندارم / دیگه باهات کاری ندارم» (+ چند مترادف، «ممنون خداحافظ» هم) با پاسخ خداحافظی؛ «هیچی/بیخیال/لغو» هم همین‌طور.
- Timeout سکوت ۲۵ ثانیه (بی‌صدا تمام می‌شود)، سقف ۱۰ دقیقه، ۴ نوبت خالی پشت‌سرهم، ۳ «متوجه نشدم» پشت‌سرهم.
- Vosk در کل جلسه Suspend می‌ماند و بعد از بستن Overlay Resume می‌شود (مسیر قبلی سرویس).
- فرمان‌ها داخل جلسه از همان Brain → `JarvisCommandProcessor` → `JarvisActionExecutor` رد می‌شوند («برو داخل اینستاگرام»، «آلارم ۸ صبح»، …). بعد از اجرا جلسه ادامه دارد؛ اگر اجرا شکست بخورد پیام خطا گفته می‌شود و جلسه ادامه می‌یابد.
- «برگرد» اجرا می‌شود ولی جلسه را هم می‌بندد: اثر واقعی GoBack فعلی فقط بستن Overlay است (Android دکمهٔ Back برنامهٔ دیگر را نمی‌دهد)، پس ادامهٔ جلسه معنی ندارد.
- گفت‌وگو: سلام، خوبی/حالت چطوره/تو خوبی، چه خبر، ممنون/مرسی، تو کی هستی/اسمت چیه (و «ChatGPT هستی؟» → صادقانه نه)، خسته‌ام، حوصله ندارم، ناراحتم، یه چیزی بگو، کمکم کن، چه کارهایی بلدی، باشه/اوکی. هر مورد چند نسخه دارد و نسخهٔ تازه‌گفته‌نشده در همان جلسه ترجیح داده می‌شود.
- Fast-path partial دیگر برای «خداحافظ/هیچی» (Dismiss) کار نمی‌کند؛ پایان جلسه فقط با نتیجهٔ نهایی.
- زمان انتظار TTS با طول متن زیاد می‌شود تا میکروفون وسط حرف‌زدن JARVIS باز نشود.
- Context جلسه هنگام شروع/پایان پاک می‌شود، لاگ نمی‌شود و به Memory دائمی نمی‌رسد.

## 3. نقطهٔ اتصال Offline STT
یک خط در `speech/SpeechInputFactory.kt`:
`fun create(context) : SpeechInput = JarvisCommandSpeechController(context)` ← پیاده‌سازی `SpeechInput` از `JARVIS-OFFLINE-STT.zip` را برگردان.
قرارداد: هر `startListening()` = یک utterance؛ پایان با `onFinalAlternatives`+`onFinalResult` یا `onError`؛ `stopListening()` بی‌صدا و میکروفون را فوراً آزاد می‌کند؛ callbackها روی main thread. Vosk Wake Word در کل جلسه Suspend است.
اکنون تا اتصال STT، همان recognizer قبلی (`SpeechRecognizer`، احتمالاً نیازمند اینترنت) پشت interface کار می‌کند.

## 4. محدودیت‌های شناخته‌شده
1. Build/Run نشده؛ ممکن است خطای کامپایل کوچک داشته باشد.
2. کلاس `OfflineConversationBrain` در ZIP نبود؛ `BasicConversation` (همان گفت‌وگوی آفلاین موجود) گسترش یافت. قاعده‌محور است، نه LLM؛ جملهٔ خارج از قواعد → «متوجه نشدم.» و جلسه ادامه دارد.
3. با recognizer فعلی هر نوبت یک session جدا می‌سازد؛ بین نوبت‌ها چند صد میلی‌ثانیه میکروفون بسته است و بخش اول جملهٔ بعدی ممکن است گم شود. با Offline STT پیوسته بهتر می‌شود.
4. اگر کاربر وسط جلسه برنامهٔ دیگری را باز کند، Overlay روی آن می‌ماند تا جلسه تمام شود.
5. بدون Echo cancellation: صدای بلند JARVIS ممکن است به میکروفون برسد (تأخیر ۳۰۰ms قبل از شنیدن).
6. Online (جستجو/هوا) پیاده نشده؛ جای آن بین Command و BasicConversation در `DefaultJarvisBrain.decide` است.
7. `INTEGRATION.md`/`STATUS-STAGE46-4.md` هنوز جریان تک‌نوبتی قدیمی را توصیف می‌کنند؛ مرجع جدید همین فایل است.
