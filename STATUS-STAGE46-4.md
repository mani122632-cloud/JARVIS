# STATUS — Stage 46.4 (Brain + Memory + Final Integration)

## Build
**Build واقعی انجام نشد.** در محیط ساخت Android SDK، Kotlin compiler (`kotlinc`) و Gradle/اینترنت وجود نداشت. هیچ کد Kotlin کامپایل یا اجرا نشده است.
آنچه واقعاً انجام شد: بررسی source-level — اسکریپت import/reference روی همهٔ فایل‌های `.kt` (فقط خطاهای مورد انتظار: `R` تولیدی Android و `com.k2fsa.sherpa.onnx.*` که فقط بعد از `tools/install-offline-assets.sh` وجود دارد) و بازبینی دستی؛ منطق `MemoryCommandParser` با mirror پایتونی تست شد (۲۰ جملهٔ نمونه)، نه با Kotlin. `assembleDebug` واقعی (GitHub Actions) هنوز لازم است.

## پیاده‌سازی‌شده (کد نوشته‌شده، تست‌نشده روی دستگاه)
- `brain/`: `JarvisBrain` (+ `BrainResult` = COMMAND / CONVERSATION / UNKNOWN)، `DefaultJarvisBrain` روی `JarvisCommandProcessor`/`CommandIntentParser` مرحله 46.3 (حذف یا جایگزین نشدند)، `BasicConversation` (سلام / خوبی؟ / اسمت چیه؟ — جدول ثابت)، `MemoryCommandParser`.
- `memory/`: `JarvisMemory` (remember / recall / forget / clear) و `SharedPreferencesJarvisMemory` (محلی، حداکثر ۵۰ مورد). فقط فرمان صریح ذخیره می‌کند؛ متن کاربر log نمی‌شود.
- `JarvisConversationController` به `JarvisBrain` وصل شد (به‌جای `JarvisCommandProcessor` مستقیم). Unknown/Conversation هیچ Action اجرا نمی‌کنند. خطای Brain و Executor گرفته می‌شود.
- `JarvisOverlayService`: فقط ساخت Brain اضافه شد. `JarvisPhrases`: سه جملهٔ جدید + PREWARM.
- Merge سه ZIP: ZIP مرحله 3 مجموعهٔ کامل فایل‌های ZIP 1 و 2 را دارد (diff بررسی شد؛ هیچ فایل مرحلهٔ قبل حذف نشد). گزارش‌های وضعیت قدیمی به `docs/history/` رفتند. `INTEGRATION.md` از نو و هماهنگ با وضعیت واقعی نوشته شد (ادعاهای منسوخ مثل «پروژه Build نمی‌شود چون JarvisCommandProcessor حذف شده» و «TTS به صدای نصب‌شده روی گوشی نیاز دارد» حذف شدند).
- Broken referenceهای 46.3 (`JarvisAction`، `JarvisCommandProcessor`، `JarvisActionExecutor`، `JarvisConversationController`، import/package/Manifest/Gradle): در source ZIP مرحله 3 مورد شکسته‌ای پیدا نشد؛ همه resolve می‌شوند.

## دست‌نخورده (diff ثابت‌شده نسبت به ZIP 46.3)
`wakeword/` (WakePhrase، VoskWakeWordEngine، WakeWordState، آستانه‌ها، گرامر، RMS)، `core/` (Arc Reactor، Renderer، Animation)، `JarvisOverlayWindow.kt` (طراحی و موقعیت Overlay)، `speech/tts/*`، `AndroidManifest.xml`، Gradle.
Android System TTS در پروژه وجود ندارد (grep).

## محدودیت‌های باقی‌مانده
1. **Build نشده** — ممکن است خطای کامپایل کوچک داشته باشد؛ اولین Build واقعی لازم است.
2. **مدل‌ها داخل ZIP نیستند**: Vosk فارسی (`assets/model-fa/`) و Piper/sherpa + `.so` (با `tools/install-offline-assets.sh`). بدون آن‌ها Wake Word / صدا کار نمی‌کند (بدون Crash). مسیرها در `INTEGRATION.md` بخش ۴.
3. **شنیدن فرمان** با `SpeechRecognizer` فارسی ممکن است اینترنت بخواهد.
4. Wake Word، کیفیت تلفظ Piper فارسی، و تشخیص «جارویس» توسط Vosk روی دستگاه واقعی تست نشده‌اند.
5. Memory فقط جفت «چیز ← مقدار»؛ «این رو فراموش کن» فقط به آخرین مورد همان اجرای سرویس اشاره می‌کند. گفتگو فقط سه عبارت پایه (+ مترادف) است.
6. در debug build دکمهٔ DEV صدا یک نمونهٔ TTS جدا در Activity می‌سازد (حافظهٔ اضافه، فقط debug).
7. «برگرد» = بستن Overlay؛ Wi-Fi/Bluetooth فقط صفحهٔ تنظیمات؛ Android 14+ محدودیت شروع مجدد سرویس میکروفون.
8. حذف نشده/ بررسی نشده: پکیج قدیمی `ui/` (کپی قدیمی `JarvisCoreView`/`JarvisState`) از مراحل قبل باقی است (UI دست نخورد).

Stage 47 شروع نشده.
