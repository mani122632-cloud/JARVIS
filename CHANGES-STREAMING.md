# JARVIS — Streaming پاسخ از Local LLM به Gyro

مسیرها نسبت به ریشه پروژه‌اند (`android/app/src/main/java/com/jarvis/assistant/...`).
اگر سورس‌های شما زیر `kotlin/` است، فقط پوشه `java` را به `kotlin` تغییر دهید.

## فایل‌های جدید
| فایل | کار |
|---|---|
| `brain/llm/LlmStreaming.kt` | `LlmStreamListener`، `LlmCancel`، `ReplyStreamSink`، `StreamingJarvisBrain` (واسط اختیاری؛ `JarvisBrain` تغییر نکرده) |
| `brain/llm/ReplyStreamer.kt` | پاکسازی تدریجی (think/tool_call/توکن‌های قالب/مارک‌داون/متن غیرقابل‌خواندن)، سقف ۳۲۰ حرف، تحویل بخش‌ها |
| `speech/SpeechChunker.kt` | برش بخش‌های طبیعی: پایان جمله، ویرگول برای بخش اول، جمله طولانی فقط روی فاصله؛ هیچ کاراکتری دوبار یا گم‌شده نمی‌رود |
| `speech/StreamingSpeechAdapter.kt` | Adapter کوچک روی `JarvisSpeechController` فعلی (Gyro دست‌نخورده): بخش‌ها را یکی‌یکی و بدون هم‌پوشانی پخش می‌کند |

## فایل‌های تغییرکرده
- `brain/llm/LlmProvider.kt` — متد `generateStream` (پیش‌فرض = `generate`).
- `brain/llm/OpenAiCompatibleProvider.kt` — Streaming واقعی با `"stream": true` و SSE روی همان endpoint؛ تکه‌های tool call جمع می‌شوند.
- `brain/llm/LoopbackHttp.kt` — `postStream` (خواندن تدریجی chunked روی socket لوپ‌بک)؛ `post` همان رفتار قبلی.
- `brain/llm/LlmJarvisBrain.kt` — راند streaming، لغو، fallback، قرارداد «یک‌بار گفتن».
- `brain/llm/LlmPrompt.kt` — فقط قواعد کوتاه‌گویی و پاسخ کوتاه بعد از Tool.
- `conversation/JarvisConversationController.kt` — اتصال به sink/adapter؛ بقیه منطق جلسه دست‌نخورده.

## رفتار
- اولین بخش قابل‌پخش به Gyro می‌رود در حالی که LLM هنوز می‌نویسد؛ نتیجه نهایی Brain یک‌بار می‌رسد و متنِ قبلاً‌گفته‌شده دوباره پخش نمی‌شود.
- Tool Calling: متن راندی که tool call شد گفته نمی‌شود؛ نتیجه Tool به LLM برمی‌گردد و راند بعدی (تأیید کوتاه، سقف ۸۰ توکن) هم استریم می‌شود. `ask_user`/`go_back`/`end_conversation` بعد از تمام‌شدن صدای در صف اجرا می‌شوند.
- فقط یک درخواست LLM: worker تک‌نخی است؛ لغو/timeout/پایان جلسه اتصال را می‌بندد تا سرور هم تولید را متوقف کند. با رسیدن به سقف طول، خواندن استریم قطع می‌شود.
- Fallback: خطای HTTP/غیر-SSE قبل از رسیدن هر متنی → همان درخواست با مسیر non-streaming (که منطق رد‌شدن tools را هم دارد) و متن کامل بعداً بخش‌بندی می‌شود. اگر بعد از رسیدن متن قطع شود، همان متن جزئی استفاده می‌شود و درخواست تکرار نمی‌شود. Timeoutها تغییر نکرده‌اند.
- Adapter: timeoutهای هر بخش مثل پاسخ عادی؛ اگر TTS اصلاً کار نکند بقیه رها می‌شود و جلسه گیر نمی‌کند.

## لاگ‌ها
`OpenAiProvider` (قطع/Fallback استریم)، `JarvisStreamTts` (timeout بخش‌ها)، `LlmJarvisBrain`.

## محدودیت‌ها
- بین دو بخش، سنتز بخش بعدی بعد از پایان پخش قبلی شروع می‌شود (همان رفتار چندجمله‌ای فعلی Gyro)؛ پیش‌سنتز موازی نیاز به تغییر Gyro دارد و عمداً انجام نشد.
- در این محیط Kotlin/Android SDK نبود؛ کد کامپایل نشده است. ابتدا یک Build در GitHub Actions بگیرید.
