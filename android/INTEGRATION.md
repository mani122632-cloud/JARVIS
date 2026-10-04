# Stage 44 — Wake Word آفلاین «هی جارویس»

**وضعیت: Build و اجرا نشده است.** محیط ساخت این مرحله اینترنت و Android SDK نداشت، پس کد فقط با بازبینی نوشته شده و کامپایل نشده. مدل فارسی Vosk هم داخل ZIP نیست (پایین را ببین). بدون مدل، برنامه Build می‌شود ولی Wake Word کار نمی‌کند و در UI «مدل صوتی فارسی داخل برنامه نیست» نشان می‌دهد.

## 1. فایل‌های تغییرکرده
جدید (`app/src/main/java/com/jarvis/assistant/wakeword/`):
- `VoskWakeWordEngine.kt` — تشخیص آفلاین با Vosk، یک thread و یک AudioRecord
- `WakePhrase.kt` — عبارت و املاهای قابل‌قبول، گرامر بسته Vosk
- `WakeWordState.kt` — وضعیت برای UI (`WakeStatus`, `AssistantFlow`)

تغییرکرده:
- `overlay/JarvisOverlayService.kt` — اتصال Wake Word، type مربوط به foreground service، suspend/resume
- `MainActivity.kt` — بخش «فرمان صوتی» و کنترل DEV
- `AndroidManifest.xml` — `FOREGROUND_SERVICE_MICROPHONE` و `foregroundServiceType="microphone|specialUse"`
- `app/build.gradle` — وابستگی‌های `vosk-android` و `jna`
- `INTEGRATION.md` (این فایل)؛ فایل قبلی با نام `INTEGRATION-stage43.1.md` ماند

دست‌نخورده: همه‌ی `core/` (Reactor)، `JarvisOverlayWindow.kt`، `JarvisActivationController.kt`، `AndroidTtsSpeechController.kt`.

## 2. فعال شدن Vosk
وابستگی‌ها در `app/build.gradle` اضافه شده‌اند (`com.alphacephei:vosk-android:0.3.47` و `net.java.dev.jna:jna:5.13.0@aar`، هر دو از Maven Central). API key یا حساب لازم نیست. کتابخانه با Gradle sync خودکار دانلود می‌شود.

## 3. مدل فارسی (باید دستی گذاشته شود)
1. از صفحه‌ی مدل‌های Vosk (alphacephei.com/vosk/models) مدل **`vosk-model-small-fa-0.42`** را دانلود و unzip کن.
2. پوشه‌ی داخلی آن را با نام **`model-fa`** اینجا بگذار:
   `android/app/src/main/assets/model-fa/`
3. باید این مسیر وجود داشته باشد: `assets/model-fa/am/final.mdl` (کنار `conf/`، `graph/`، `ivector/`). برنامه وجود همین فایل را برای تشخیص مدل چک می‌کند.

مدل در اولین اجرا یک‌بار به حافظه‌ی داخلی کپی می‌شود (چند ثانیه). با هر به‌روزرسانی برنامه دوباره کپی می‌شود.

## 4. Permissionها
- `RECORD_AUDIO` — با لمس «فعال‌سازی فرمان صوتی» درخواست می‌شود، هیچ dialog خودکاری باز نمی‌شود
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_SPECIAL_USE` (Manifest)
- `SYSTEM_ALERT_WINDOW` (Stage 43، همان «نمایش روی برنامه‌ها»)
- Android 13+: اگر اجازه‌ی نوتیفیکیشن نداده باشی، notification سرویس مخفی می‌ماند ولی سرویس کار می‌کند. چون در این مرحله خواسته نشده بود، درخواست خودکار نوشته نشده.

## 5. فعال کردن Wake Word
1. «فعال‌سازی نمایش روی برنامه‌ها» را بزن و اجازه بده.
2. «فعال‌سازی فرمان صوتی» را بزن و میکروفون را مجاز کن.
3. «روشن کردن فرمان صوتی» را بزن. وضعیت از «در حال آماده‌سازی مدل» به «منتظر «هی جارویس» هستم» می‌رسد.
4. از برنامه خارج شو. سرویس با یک notification foreground به گوش دادن ادامه می‌دهد.

جریان: `IDLE → WAKE_WORD_LISTENING → OVERLAY_ACTIVATING («بله ارباب.») → LISTENING → hide → WAKE_WORD_LISTENING`. میکروفون از لحظه‌ی تشخیص تا ۶۰۰ms بعد از بسته شدن Overlay کاملاً آزاد است. در این بازه نه صدای TTS باعث trigger می‌شود و نه listener دوم ساخته می‌شود.

محدودیت Android 14+: سرویس میکروفون را فقط وقتی می‌توان روشن کرد که کاربر برنامه را باز کرده و دکمه را زده باشد. بعد از kill شدن سرویس (reboot یا kill سیستم) Wake Word خودکار برنمی‌گردد (`START_NOT_STICKY`)؛ باید دوباره از برنامه روشنش کنی.

## 6. تست بدون ADB
در **debug build** (که CI می‌سازد) پایین صفحه دو چیز دیده می‌شود (موقت و قابل حذف):
- **«[DEV] نمایش آزمایشی پس از ۸ ثانیه»**: Overlay را با همان مسیر activation اجرا می‌کند (`JarvisOverlayService.show`). این مسیر Wake Word را آزمایش نمی‌کند، فقط Overlay و TTS را.
- یک خط وضعیت با `WakeStatus / AssistantFlow` و **آخرین متنی که Vosk شنیده**. با آن می‌توانی ببینی «هی جارویس» چطور تشخیص داده می‌شود.

تست نهایی: Wake Word را روشن کن، Instagram را باز کن و بگو «هی جارویس». Overlay باید روی Instagram بیاید، «بله ارباب.» پخش شود و بعد از حدود ۸ ثانیه (auto-dismiss موقت Stage 43) Overlay بسته شود.

## 7. ریسک‌های شناخته‌شده
- **واژگان مدل**: «جارویس» ممکن است در lexicon مدل کوچک فارسی نباشد. Vosk کلمه‌های ناشناخته‌ی گرامر را نادیده می‌گیرد. برای همین چند املا در `WakePhrase.kt` هست. اگر در تست نمی‌شناسد، متن دیده‌شده در خط DEV را نگاه کن و املای نزدیک را به `VARIANTS` اضافه کن. هنوز با مدل واقعی تست نشده.
- **false positive / negative**: آستانه `MIN_CONFIDENCE = 0.6` در `VoskWakeWordEngine.kt` است (بالاتر = محتاط‌تر). گرامر بسته هم باعث می‌شود حرف عادی به `[unk]` برود.
- **16KB page size (Android 15+)**: ممکن است `.so` های `vosk-android 0.3.47` برای دستگاه‌های 16KB سازگار نباشند. روی دستگاه‌های معمولی مشکلی نیست. اگر لازم شد نسخه‌ی جدیدتر Vosk را در `app/build.gradle` جایگزین کن.
- **Persian TTS**: «بله ارباب.» به صدای فارسی نصب‌شده روی گوشی نیاز دارد (مثل Stage 43).
- **Build**: هنوز `./gradlew assembleDebug` اجرا نشده. اگر خطای کامپایل داد، متن خطا را بفرست تا اصلاح کنم.
