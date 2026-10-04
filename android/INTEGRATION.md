# JARVIS — Stage 46 (نهایی): Wake Word + TTS آفلاین + فرمان‌ها + Brain + Memory

**وضعیت Build:** Build و اجرا **نشده**. محیط ساخت Android SDK، Kotlin compiler و اینترنت نداشت. فقط بررسی سطح source انجام شده (اسکریپت بررسی import/reference روی همهٔ فایل‌های Kotlin + بازبینی دستی؛ منطق parser حافظه با mirror پایتونی تست شد، نه با Kotlin). اولین `assembleDebug` واقعی (GitHub Actions) ممکن است خطای کامپایل کوچکی نشان بدهد. جزئیات در `STATUS-STAGE46-4.md`.

## 1. جریان نهایی
```
Wake Word (Vosk، «هی جارویس») → Activation → TTS «بله ارباب.»
→ Command Listening (SpeechRecognizer fa-IR؛ Vosk متوقف)
→ Brain (JarvisBrain)
     ├─ COMMAND       → TTS «حتماً.» → JarvisActionExecutor
     ├─ CONVERSATION  → TTS پاسخ (سلام / حافظه / ...)
     └─ UNKNOWN       → TTS «متوجه نشدم.» (هیچ Action اجرا نمی‌شود)
→ پایان → Overlay بسته می‌شود → Vosk دوباره شروع می‌شود
```
- هر لحظه فقط یک میکروفون فعال است: Vosk **یا** SpeechRecognizer. TTS با AudioTrack پخش می‌شود و میکروفون نمی‌گیرد.
- همهٔ پاسخ‌ها از `OfflinePersianTts` (مرحله 46.2) می‌آیند. Android System TTS در پروژه نیست. موتور TTS یک بار در `onCreate` سرویس load می‌شود (`initialize()`) و برای هر جمله دوباره ساخته نمی‌شود.
- یک فرمان فقط یک بار اجرا می‌شود (`dispatched` + ورودی واحد `onCommand`). Partial فقط با confidence ≥ 0.9، ۵۰۰ms ثبات و فقط برای فرمان دستگاه (نه حافظه) زودتر اجرا می‌شود.
- هیچ AI / API / LLM / اینترنتی در Brain، parser و executor نیست.

## 2. Brain، Conversation، Memory (مرحله 46.4)
کد در `app/src/main/java/com/jarvis/assistant/`:
- `brain/JarvisBrain.kt` — interface `JarvisBrain` (`think(text): BrainResult`، `isConfidentCommand(partial)`) و `BrainResult` با `Command` / `Conversation` / `Unknown` (`kind: BrainKind`).
- `brain/DefaultJarvisBrain.kt` — پیاده‌سازی. **روی** `JarvisCommandProcessor`/`CommandIntentParser` مرحله 46.3 قرار دارد (جایگزین نشده‌اند). ترتیب: فرمان صریح حافظه → فرمان دستگاه (confidence ≥ 0.7) → گفتگوی پایه → Unknown.
- `brain/BasicConversation.kt` — جدول ثابت، **نه** Local LLM: «سلام» → «سلام ارباب.»، «خوبی؟» → «ممنون، آماده‌ام.»، «اسمت چیه؟» → «من جارویس هستم.» (چند مترادف ساده هم دارند). افزودن عبارت: به مجموعه‌های داخل همین فایل.
- `brain/MemoryCommandParser.kt` — فقط فرمان‌های **صریح** حافظه را تشخیص می‌دهد.
- `memory/JarvisMemory.kt` — interface با `remember` / `recall` / `forget` / `clear` (خطا: `MemoryException`).
- `memory/SharedPreferencesJarvisMemory.kt` — ذخیرهٔ محلی در فایل خصوصی `jarvis_memory` (حداکثر ۵۰ مورد، هر مقدار ≤ ۱۰۰ نویسه). بدون cloud، `allowBackup=false`.

### فرمان‌های حافظه
| بگو | نتیجه |
|---|---|
| «یادت باشه اسم من علی هست» | ذخیره (`اسم` = `علی`) → «باشه، به خاطر سپردم.» |
| «اسم من چیه؟» | «اسم شما علی است.» (اگر ذخیره نشده: «هنوز اسم شما را نمی‌دانم.») |
| «اسم من رو فراموش کن» / «این رو فراموش کن» | حذف همان مورد (یا آخرین موردِ ذخیره/پرسیده‌شده در همین اجرای سرویس) |
| «همه چیز رو فراموش کن» / «حافظه رو پاک کن» | پاک‌کردن کل حافظه |
الگوی کلی: «یادت باشه ‹چیز› من ‹مقدار› [هست]» و «‹چیز› من چیه؟» (مثلاً «ماشین من پژو هست»). جمله‌های معمولی **هرگز** ذخیره نمی‌شوند. متن کاربر در Logcat نوشته نمی‌شود (فقط طول).

## 3. فرمان‌های دستگاه (46.3)
| دسته | نمونه | Action |
|---|---|---|
| برنامه | «اینستاگرام رو باز کن»، «کروم رو باز کن»، «یوتیوب رو باز کن» | `OpenApp` |
| تنظیمات | «تنظیمات رو باز کن»، «تنظیمات وای فای»، «تنظیمات بلوتوث» | `OpenSettings` / `OpenWifiSettings` / `OpenBluetoothSettings` |
| چراغ قوه | «چراغ قوه رو روشن کن / خاموش کن» | `ToggleFlashlight` |
| صدا | «صدا رو زیاد کن»، «صدا رو روی ۵۰ درصد بذار» | `SetVolume` |
| تایمر / آلارم | «یک تایمر ۵ دقیقه‌ای بذار»، «برای ساعت ۸ صبح آلارم بذار» | `CreateTimer` / `CreateAlarm` |
| ناوبری | «برو صفحه اصلی»، «برگرد» | `GoHome` / `GoBack` |
| پایان | «هیچی»، «خداحافظ»، «لغو» | `DismissAssistant` |

افزودن Action: subclass در `command/JarvisAction.kt` ← `matchXxx` در `nlu/CommandIntentParser.kt` ← شاخه در `when` داخل `JarvisActionExecutor.dispatch`. افزودن برنامه: `AppEntry` در `command/AppRegistry.kt` **و** `<package>` در `<queries>` فایل Manifest.

## 4. نصب مدل‌ها (داخل ZIP نیستند)
### 4.1 مدل Wake Word (Vosk فارسی)
1. `vosk-model-small-fa-0.42` را از alphacephei.com/vosk/models دانلود و unzip کن.
2. پوشهٔ داخلی را با نام `model-fa` بگذار: `android/app/src/main/assets/model-fa/` (باید `assets/model-fa/am/final.mdl` وجود داشته باشد).
3. بدون مدل، برنامه Build می‌شود ولی Wake Word کار نمی‌کند و UI «مدل صوتی فارسی داخل برنامه نیست» نشان می‌دهد. `vosk-android` و `jna` از Maven با Gradle می‌آیند؛ `.so` آن‌ها داخل ZIP نیست.

### 4.2 صدای فارسی آفلاین (sherpa-onnx + Piper)
یک بار، از پوشهٔ `android/` (حدود ۱۰۰ مگابایت دانلود؛ Termux: `pkg install -y curl tar bzip2 unzip git`):
```
bash tools/install-offline-assets.sh
```
مسیرهای ساخته‌شده:
```
app/src/main/assets/tts-fa/model.onnx
app/src/main/assets/tts-fa/tokens.txt
app/src/main/assets/tts-fa/espeak-ng-data/
app/src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so
app/src/main/jniLibs/arm64-v8a/libonnxruntime.so
app/src/sherpa/java/com/k2fsa/sherpa/onnx/Tts.kt
```
- مدل پیش‌فرض `vits-piper-fa_IR-amir-medium` (با `VOICE=...` قابل تغییر)، sherpa-onnx نسخهٔ `1.13.7` (با `SHERPA_VERSION=...`). نسخهٔ `Tts.kt` باید هم‌نسخهٔ `.so` باشد.
- فقط ABI `arm64-v8a` (`abiFilters` در `app/build.gradle`؛ روی Vosk هم اثر دارد: گوشی ۳۲ بیتی پشتیبانی نمی‌شود).
- `app/build.gradle` فقط وقتی `Tts.kt` و `libsherpa-onnx-jni.so` وجود دارند source set مربوط به sherpa را اضافه می‌کند؛ پس پروژه بدون آن‌ها هم Build می‌شود.
- بدون مدل/موتور: `OfflinePersianTts.status` = `MODEL_MISSING` / `ENGINE_MISSING` / `ERROR`، هر `speak()` فوراً `onDone(false)` می‌دهد و Crash نمی‌شود. گفتگو بدون صدا ادامه می‌یابد (Action اجرا می‌شود) و وضعیت در صفحهٔ اصلی نشان داده می‌شود.

## 5. Permissionها
`RECORD_AUDIO` (فقط با لمس کاربر درخواست می‌شود) · `SYSTEM_ALERT_WINDOW` («نمایش روی برنامه‌ها») · `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE` + `FOREGROUND_SERVICE_SPECIAL_USE` · `com.android.alarm.permission.SET_ALARM` · `android.permission.MODIFY_AUDIO_SETTINGS` (احتیاطی؛ شاید روی AOSP لازم نباشد). چراغ قوه permission نمی‌خواهد. `<queries>`: instagram، chrome، youtube و `RecognitionService`. Android 13+: درخواست خودکار اجازهٔ notification وجود ندارد (سرویس بدون آن هم کار می‌کند، فقط notification دیده نمی‌شود).

## 6. فعال‌سازی و تست
1. «فعال‌سازی نمایش روی برنامه‌ها» → اجازه بده. 2. «فعال‌سازی فرمان صوتی» → میکروفون. 3. «روشن کردن فرمان صوتی» → وضعیت «منتظر «هی جارویس» هستم».
4. بگو «هی جارویس»، پس از «بله ارباب.» یکی از این‌ها: «سلام» · «خوبی؟» · «اسمت چیه؟» · «یادت باشه اسم من علی هست» · «اسم من چیه؟» · «این رو فراموش کن» · «همه چیز رو فراموش کن» · «چراغ قوه رو روشن کن» · یک جملهٔ بی‌معنی (باید فقط «متوجه نشدم.» بگوید).
5. Debug build دکمه‌های **«[DEV] تست صدای فارسی»** و **«[DEV] نمایش آزمایشی»** و خط وضعیت Vosk دارد. دکمهٔ DEV صدا یک نمونهٔ TTS جدا می‌سازد (فقط debug).

## 7. محدودیت‌های شناخته‌شده (صادقانه)
- **Build نشده**: هیچ بخشی روی دستگاه یا با کامپایلر تست نشده؛ مدل Vosk و صدای Piper هنوز با گوش واقعی ارزیابی نشده‌اند (تلفظ ممکن است نیاز به `PersianTtsText.PRONUNCIATION` داشته باشد؛ «جارویس» ممکن است در lexicon Vosk نباشد، املاها در `WakePhrase.kt`).
- **شنیدن فرمان** با `SpeechRecognizer` فارسی روی بعضی گوشی‌ها اینترنت می‌خواهد (اجرای فرمان آفلاین است، شنیدنش نه). بدون آن «برای تشخیص گفتار به اینترنت نیاز دارم.» گفته می‌شود. Vosk دوم برای فرمان عمداً اضافه نشد.
- **حافظه** فقط جفت «چیز ← مقدار» است؛ جملهٔ آزاد یا چندمقداره ندارد. «این رو فراموش کن» فقط به آخرین مورد ذخیره/پرسیده‌شده در همان اجرای سرویس اشاره می‌کند؛ بعد از kill سرویس جواب می‌دهد کدام مورد را فراموش کند.
- **گفتگو** فقط چند عبارت ثابت است، نه Local LLM. هر چیز دیگر «متوجه نشدم.»
- **برگرد**: Back واقعی در برنامهٔ دیگر بدون AccessibilityService ممکن نیست؛ فقط Overlay بسته می‌شود. **Wi-Fi/Bluetooth** فقط صفحهٔ تنظیمات را باز می‌کنند. تایمر ≤ ۲۴ ساعت. صدا روی دستگاه‌های fixed-volume اثر ندارد. چراغ قوه اگر دوربین مشغول باشد خطا می‌دهد.
- **Android 14+**: میکروفون foreground service فقط با اقدام کاربر از داخل برنامه شروع می‌شود؛ بعد از kill شدن سرویس Wake Word خودکار برنمی‌گردد (`START_NOT_STICKY`). `.so` های `vosk-android 0.3.47` ممکن است با 16KB page size (Android 15+) سازگار نباشند.
- اولین اجرا چند ثانیه برای کپی مدل TTS (~۶۰–۷۰ MB) و مدل Vosk از assets به حافظهٔ داخلی طول می‌کشد.

## 8. فایل‌های مرتبط
`STATUS-STAGE46-4.md` (وضعیت واقعی) · `android/INTEGRATION-stage43.1.md` (سند قدیمی Overlay مرحله 43.1) · `docs/history/` (گزارش‌های وضعیت قدیمی مراحل 46.x، فقط برای تاریخچه؛ بخش‌هایی از آن‌ها منسوخ است).
