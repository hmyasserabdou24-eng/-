# تطبيق «دار الإحسان – فضاء الأولياء» (Android)

تطبيق مستقل يعرض **فضاء الأولياء فقط** (`parent.html`)، يحفظ جلسة الولي بأمان، ويستقبل إشعارات FCM.
هذا المستودع **منفصل** عن مستودع الموقع، ولم يُعدَّل شيء في الموقع أو في لوحة الإدارة.

> **حالة التسليم (بصراحة):** الكود والترحيل ودالة الإرسال مكتوبة، واختُبر منها هنا فقط منطق قاعدة البيانات (انظر `TESTING-AR.md`).
> **لم يُبنَ APK ولم يُجرَّب على هاتف**، ولا تعمل الإشعارات قبل إتمام الخطوات اليدوية أدناه. لا تعتمدوا على الإشعارات قبل اجتياز اختبارات `TESTING-AR.md` على جهاز حقيقي.

## ما الذي يفعله التطبيق
| المتطلب | التنفيذ |
|---|---|
| يفتح فضاء الأولياء مباشرة | `BuildConfig.PARENT_URL` رابط ثابت (انظر «الرابط الحقيقي» أدناه) |
| منع الموقع العام والإدارة | `UrlPolicy.kt`: يُسمح بصفحة `parent.html` فقط (مع `?…#…` لروابط الدعوة والاستعادة). أي صفحة أخرى على نفس الموقع (`index/manage/admin/site/qr…`) تُمنع ويبقى المستخدم في الفضاء. روابط المواقع الأخرى (واتساب، فيسبوك، البريد) تُفتح خارج التطبيق. زر الرجوع لا يخرج من الفضاء |
| تسجيل دخول مرة واحدة | الموقع نفسه يحفظ جلسة Supabase في `sessionStorage` (`de_parent_session`) فتضيع بإغلاق التطبيق. التطبيق (دون تعديل الموقع) يلتقط كل تغيير للجلسة عبر `inject.js` ويحفظها مشفّرة بمفتاح **AES-256-GCM داخل Android Keystore**، ويعيدها عند الفتح. التجديد يقوم به عميل الموقع نفسه (Refresh Token)، والتطبيق يحفظ الرمز الجديد فور تغيّره. لا تُخزَّن كلمة المرور |
| لا خروج بسبب الخمول | الموقع يخرج بعد 30 دقيقة خمول (`DE.idle`). التطبيق يعطّل هذه الدالة **داخل التطبيق فقط** (قرار مقصود حسب طلبكم؛ المخاطرة: هاتف ضائع بلا قفل شاشة. انصحوا الأولياء بتفعيل قفل الشاشة) |
| الخروج اليدوي | يفك ربط الجهاز بالحساب أولًا ثم يسجّل الخروج ويمسح الجلسة المحفوظة |
| الإشعارات | FCM + دالة `send-fcm` + طابور `notification_outbox` الموجود أصلًا |

## الرابط الحقيقي لفضاء الأولياء — يحتاج تأكيدكم
ملفات المشروع لا تحتوي رابطًا نهائيًا مؤكَّدًا. أكثر رابط تكرارًا (`INVITE-FIX-AR.md` و`index.ts`):
`https://hmyasserabdou24-eng.github.io/DarElIhsan3/parent.html`
(وفي `SETUP-AR.md` مثال آخر `…/DarElIhsan3-v7/parent.html`). **لم أستطع فتح أي منهما من بيئتي** (لا شبكة).
اضبطوا الرابط الصحيح في GitHub: *Settings ▸ Secrets and variables ▸ Actions ▸ Variables ▸ `PARENT_URL`*، أو محليًا `-PPARENT_URL=https://…/parent.html`. يجب أن يكون `https` وينتهي بـ `parent.html`.

## 1) إنشاء المستودع
أنشئوا مستودعًا جديدًا باسم مثل `darelihsan-parents-app` وارفعوا محتوى هذا المجلد (ملف `.gitignore` يمنع رفع الأسرار).

## 2) Firebase (حسابكم)
1. افتحوا مشروع `darelihsan-3b67e` ◂ ⚙ Project settings ◂ Your apps ◂ **Add app ◂ Android**: الحزمة `org.darelihsan.parents`، الاسم `دار الإحسان – فضاء الأولياء`.
2. نزّلوا `google-services.json` وحوّلوه إلى base64: `base64 -w0 google-services.json` وضعوه سرًّا باسم `GOOGLE_SERVICES_JSON_B64` في GitHub Actions. (للبناء محليًا ضعوا الملف في `app/` — لا تَرفعوه.)
3. **المفتاح السري للإرسال:** Project settings ◂ Service accounts ◂ *Generate new private key* → ملف JSON. **لا يوضع في التطبيق ولا في GitHub**؛ يوضع فقط في أسرار Supabase (خطوة 3).
4. تأكدوا أن *Firebase Cloud Messaging API (V1)* مفعّلة للمشروع.
(لا حاجة لـ Firebase Authentication أو Firestore: المصادقة والبيانات كلها في Supabase.)

## 3) Supabase (مشروع `DarElIhsan`)
1. **راجعوا ثم شغّلوا** `supabase/migrations/20261028000000_fcm_device_tokens.sql` في SQL Editor. إضافي فقط: جدول `device_tokens` وجدولا `fcm_dispatch` و`fcm_deliveries` و3 دوال، مع RLS. لا يمسّ أي جدول أو سياسة أو بيانات قائمة.
2. الأسرار (Edge Functions ▸ Secrets): `FCM_SERVICE_ACCOUNT` (محتوى JSON كاملًا) و`FCM_DISPATCH_SECRET` (نص عشوائي طويل).
3. انشروا الدالة: `supabase functions deploy send-fcm --no-verify-jwt --project-ref qgjddtylriqdwvpihvmi`.
4. **المشغّل:** Database ▸ Webhooks ▸ Create: الجدول `notification_outbox`، الحدث `INSERT`، النوع *Supabase Edge Functions* ◂ `send-fcm`، الطريقة POST، وأضيفوا ترويسة `x-dispatch-secret` بنفس قيمة السر. لإعادة المحاولة بعد الأعطال أضيفوا جدولة دورية (كل دقيقة) تستدعي الدالة نفسها (مثلًا pg_cron + pg_net بعد تفعيلهما، غير مفعّلين الآن).
5. Authentication ▸ Sessions: لا تفعّلوا «Time-box» ولا «Inactivity timeout» وإلا ستنتهي الجلسة رغم التطبيق.

## 4) البناء والتوقيع (GitHub Actions)
- مفتاح التوقيع **ينشئه المالك** ويحتفظ به (ضياعه يمنع تحديث التطبيق لاحقًا):
  `keytool -genkeypair -v -keystore release.jks -alias parents -keyalg RSA -keysize 4096 -validity 10000`
  ثم `base64 -w0 release.jks`.
- الأسرار: `KEYSTORE_B64`، `KEYSTORE_PASSWORD`، `KEY_ALIAS`، `KEY_PASSWORD`، (و`GOOGLE_SERVICES_JSON_B64`).
- شغّلوا *Actions ▸ Build Android APK ▸ Run workflow*، أو ادفعوا وسمًا `v1.0.0` ليُرفق APK الموقَّع بصفحة Release (رابط التنزيل النهائي). يتحقق السير من وجود الملف ومن توقيعه (`apksigner verify`).

## 5) ما وجدته في قاعدتكم الحالية (يحتاج قراركم)
1. **خلل في مشغّل التقارير `de_notify_on_new_report`:** يقرأ `NEW.content` وجدول `progress_reports` **لا يحتوي عمودًا بهذا الاسم**. عند نشر أي تقرير جديد (الحالة `published`) سيفشل المشغّل ويُرجَّح أن يفشل النشر نفسه. لم أعدّل شيئًا (طلبتم عدم تغيير القائم إلا للضرورة). الإصلاح البسيط: حذف شرط `NEW.content ILIKE '%scorecard%'`. أخبروني لأجهّز ترحيل الإصلاح.
2. مشغّل الرسائل يعمل عند `INSERT` فقط: رسالة تُحفظ مسودة ثم تُنشر لاحقًا لا يصدر لها إشعار. دالة الإرسال لا ترسل أصلًا لرسالة غير منشورة (`published_at`).
3. رسالة موجّهة لولي واحد (`audience='parent'`) تُسجَّل في الطابور بنوع `broadcast` مع `target_parent_id`. دالة الإرسال تعيد حساب المستلمين من الرسالة نفسها فلا تُرسَل إلا لذلك الولي.
4. لا توجد مشغّلات للمواعيد (`appointments`) ولا الوثائق (`documents`) ولا البرامج (`educational_programs`): لن تُرسل إشعارات لها حتى تُضاف مشغّلات (الطابور يدعم `announcement`/`reminder`). لم أضفها قبل تأكيدكم.
5. في الطابور 3 إشعارات معلّقة قديمة لن تُرسل (الدالة ترسل ما أُنشئ خلال آخر 24 ساعة فقط). ويوجد جدول `push_subscriptions` (Web Push) بلا مرسِل؛ لم ألمسه.
6. الدالة تكتب `sent/failed/cancelled` في `notification_outbox.status`. إن أضفتم لاحقًا مرسِل Web Push فسيحتاج حقل حالة مستقلًّا.

## الأمان باختصار
لا `service_role` ولا مفتاح Firebase السري في التطبيق. تسجيل رمز الجهاز عبر RPC بصلاحية الولي (يتحقق أنه ولي فعّال)، وقراءة الرموز لا تتم إلا بمفتاح الخادم. نص الإشعار ثابت وعام داخل التطبيق والدالة (لا اسم طفل ولا محتوى). منع التكرار بمفتاحين: حجز ذرّي للحدث + قيد فريد `(الحدث، الجهاز)` + `tag` للإشعار على الجهاز. الرموز الميتة تُحذف تلقائيًا.
