# STATUS — Stage 46.3: Offline Commands + Conversation

**Build: انجام نشد.** Android SDK، Kotlin compiler و اینترنت در محیط نبود. کد Kotlin کامپایل نشده؛ importها/referenceها دستی بررسی شدند و منطق parser با mirror پایتونی تست شد (نه با خود Kotlin). `assembleDebug` واقعی (GitHub Actions) هنوز لازم است.

## Commandهای اضافه‌شده
برنامه (Instagram/Chrome/YouTube) · تنظیمات / Wi-Fi / Bluetooth · چراغ قوه (روشن/خاموش/toggle) · صدا (زیاد/کم/درصد/حداکثر) · تایمر · آلارم (ساعت، «و نیم/ربع»، «ربع به»، «N دقیقه دیگه») · صفحه اصلی · برگرد · پایان («هیچی/خداحافظ/…»). Unknown یا confidence < 0.7 ⇒ هیچ Action اجرا نمی‌شود، فقط «متوجه نشدم.» و پایان مکالمه.

## فایل‌ها
جدید: `command/JarvisAction.kt`, `command/AppRegistry.kt`, `command/JarvisCommandProcessor.kt`, `nlu/PersianNormalizer.kt`, `nlu/PersianNumbers.kt`, `nlu/CommandIntentParser.kt`, `nlu/CommandConfidence.kt`
تغییر: `command/JarvisActionExecutor.kt` (بازنویسی کامل), `conversation/JarvisConversationController.kt`, `overlay/JarvisOverlayService.kt` (فقط نگه‌داشتن/release برای executor), `speech/JarvisPhrases.kt` (متن «متوجه نشدم.»), `AndroidManifest.xml`, `INTEGRATION.md`
دست نخورد: Arc Reactor/Renderer/Animation, Wake Word/Vosk/WakePhrase, موتور TTS، UI/Overlay.

## Permission
`com.android.alarm.permission.SET_ALARM` · `android.permission.MODIFY_AUDIO_SETTINGS` (احتیاطی؛ شاید روی AOSP لازم نباشد) · `<queries>` برای Chrome و YouTube. چراغ قوه permission نمی‌خواهد.

## محدودیت‌ها
- SpeechRecognizer فارسی ممکن است روی بعضی گوشی‌ها به شبکه نیاز داشته باشد (اجرای فرمان آفلاین است، شنیدن آن نه).
- «برگرد» = بستن Overlay (Back واقعی در برنامهٔ دیگر بدون AccessibilityService ممکن نیست).
- Wi-Fi/Bluetooth فقط صفحهٔ تنظیمات باز می‌شود. تایمر ≤ ۲۴ ساعت.
- `PersianNormalizer` تازه ساخته شد چون Normalizer عمومی وجود نداشت.

## فرمان‌های تست (بعد از «هی جارویس»)
«اینستاگرام رو باز کن» · «برو داخل اینستاگرام» · «اینستاگرام» · «کروم رو باز کن» · «یوتیوب رو باز کن» · «تنظیمات رو باز کن» · «برو تنظیمات وای فای» · «تنظیمات بلوتوث رو باز کن» · «چراغ قوه رو روشن کن» · «چراغ قوه رو خاموش کن» · «صدا رو زیاد کن» · «صدا رو کم کن» · «صدا رو روی ۵۰ درصد بذار» · «یک تایمر ۵ دقیقه‌ای بذار» · «برای ساعت ۸ صبح آلارم بذار» · «برو صفحه اصلی» · «برگرد» · یک جملهٔ بی‌معنی (باید فقط «متوجه نشدم.» بدهد).
