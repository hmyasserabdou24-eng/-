-- ══════════════════════════════════════════════════════════════════════════════
-- DAR EL IHSAN — ANDROID FCM PUSH NOTIFICATIONS & DEVICE TOKEN REGISTRATION
-- الملف: 14-android-fcm-push-notifications.sql
-- المركز النفسي البيداغوجي دار الإحسان — العطف، ولاية غرداية
-- ══════════════════════════════════════════════════════════════════════════════
-- متوافق 100% مع الجداول الفعلية وقواعد RLS الصارمة:
-- 1. جدول رموز أجهزة الأولياء parent_device_tokens
-- 2. سياسات RLS لعزل وصول الأولياء بحيث لا يمكن لأي ولي الوصول لأجهزة غيره
-- 3. دوال آمنة RPC لتسجيل وإلغاء تسجيل أجهزة أندرويد
-- 4. مشغلات قاعدة بيانات تلقائية لكافة الأحداث مع منع التكرار (Idempotency)
-- 5. صياغات إشعار آمنة تحترم خصوصية شاشة القفل وتمنع ظهور أي بيانات طبية حساسة
-- ══════════════════════════════════════════════════════════════════════════════

-- 1. جدول رموز أجهزة الأولياء لتطبيق Android (FCM Tokens)
CREATE TABLE IF NOT EXISTS public.parent_device_tokens (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  parent_user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  fcm_token text NOT NULL UNIQUE,
  device_id text,
  device_model text,
  platform text NOT NULL DEFAULT 'android',
  app_version text DEFAULT '1.0.0',
  is_active boolean NOT NULL DEFAULT true,
  last_used_at timestamptz DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_parent_device_tokens_parent ON public.parent_device_tokens(parent_user_id) WHERE is_active = true;
CREATE INDEX IF NOT EXISTS idx_parent_device_tokens_fcm ON public.parent_device_tokens(fcm_token);

-- التأكد من وجود مؤشر فريد لمنع تكرار الأحداث في طابور الإشعارات
CREATE UNIQUE INDEX IF NOT EXISTS idx_notif_outbox_unique_tag ON public.notification_outbox(tag) WHERE tag IS NOT NULL;

-- 2. تفعيل سياسات أمان مستوى الصفوف (Row Level Security - RLS)
ALTER TABLE public.parent_device_tokens ENABLE ROW LEVEL SECURITY;

-- سياسات parent_device_tokens
DROP POLICY IF EXISTS "parents_manage_own_device_tokens" ON public.parent_device_tokens;
CREATE POLICY "parents_manage_own_device_tokens"
ON public.parent_device_tokens
FOR ALL
TO authenticated
USING (
  parent_user_id = auth.uid()
)
WITH CHECK (
  parent_user_id = auth.uid()
);

DROP POLICY IF EXISTS "admins_read_device_tokens" ON public.parent_device_tokens;
CREATE POLICY "admins_read_device_tokens"
ON public.parent_device_tokens
FOR SELECT
TO authenticated
USING (public.de_is_admin());

-- 3. دالة RPC لتسجيل رمز الجهاز بأمان عند تسجيل دخول الولي
CREATE OR REPLACE FUNCTION public.de_register_device_token(
  p_fcm_token text,
  p_device_id text DEFAULT NULL,
  p_device_model text DEFAULT NULL,
  p_app_version text DEFAULT '1.0.0'
)
RETURNS jsonb AS $$
DECLARE
  v_uid uuid;
  v_token_id uuid;
BEGIN
  v_uid := auth.uid();
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'المستخدم غير مسجل الدخول';
  END IF;

  IF p_fcm_token IS NULL OR length(btrim(p_fcm_token)) < 10 THEN
    RAISE EXCEPTION 'رمز FCM غير صالح';
  END IF;

  INSERT INTO public.parent_device_tokens (
    parent_user_id,
    fcm_token,
    device_id,
    device_model,
    app_version,
    is_active,
    last_used_at,
    updated_at
  ) VALUES (
    v_uid,
    btrim(p_fcm_token),
    p_device_id,
    p_device_model,
    p_app_version,
    true,
    now(),
    now()
  )
  ON CONFLICT (fcm_token) DO UPDATE SET
    parent_user_id = EXCLUDED.parent_user_id,
    device_id = COALESCE(EXCLUDED.device_id, parent_device_tokens.device_id),
    device_model = COALESCE(EXCLUDED.device_model, parent_device_tokens.device_model),
    app_version = EXCLUDED.app_version,
    is_active = true,
    last_used_at = now(),
    updated_at = now()
  RETURNING id INTO v_token_id;

  RETURN jsonb_build_object('success', true, 'token_id', v_token_id);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

REVOKE ALL ON FUNCTION public.de_register_device_token(text, text, text, text) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.de_register_device_token(text, text, text, text) TO authenticated;

-- 4. دالة RPC لإلغاء تفعيل رمز الجهاز عند تسجيل الخروج بأمان
CREATE OR REPLACE FUNCTION public.de_unregister_device_token(
  p_fcm_token text
)
RETURNS jsonb AS $$
DECLARE
  v_uid uuid;
BEGIN
  v_uid := auth.uid();
  IF v_uid IS NULL THEN
    RETURN jsonb_build_object('success', false, 'error', 'unauthenticated');
  END IF;

  UPDATE public.parent_device_tokens
  SET is_active = false, updated_at = now()
  WHERE fcm_token = btrim(p_fcm_token)
    AND parent_user_id = v_uid;

  RETURN jsonb_build_object('success', true);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

REVOKE ALL ON FUNCTION public.de_unregister_device_token(text) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.de_unregister_device_token(text) TO authenticated;

-- 5. مشغل تلقائي: إشعار عند وصول رسالة جديدة من الإدارة
CREATE OR REPLACE FUNCTION public.de_notify_on_new_message()
RETURNS trigger AS $$
DECLARE
  v_tag text;
BEGIN
  v_tag := 'de-msg-' || NEW.id::text;
  
  INSERT INTO public.notification_outbox (
    target_type,
    target_child_id,
    target_parent_id,
    event_type,
    title,
    body,
    url,
    tag
  ) VALUES (
    CASE 
      WHEN NEW.audience = 'parent' AND NEW.parent_id IS NOT NULL THEN 'single_parent'
      WHEN NEW.audience = 'child' AND NEW.child_id IS NOT NULL THEN 'single_parent'
      ELSE 'all_parents'
    END,
    NEW.child_id,
    NEW.parent_id,
    'new_message',
    'رسالة جديدة من إدارة دار الإحسان',
    'لديك رسالة وإشعار جديد خاص بملف طفلك في فضاء الأولياء.',
    './parent.html#msgs',
    v_tag
  )
  ON CONFLICT (tag) DO NOTHING;
  
  RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS trg_notify_on_new_message ON public.messages;
CREATE TRIGGER trg_notify_on_new_message
AFTER INSERT ON public.messages
FOR EACH ROW
EXECUTE FUNCTION public.de_notify_on_new_message();

-- 6. مشغل تلقائي: إشعار عند نشر تقرير دوري أو استمارة تقييم بيداغوجي
CREATE OR REPLACE FUNCTION public.de_notify_on_new_report()
RETURNS trigger AS $$
DECLARE
  is_eval boolean;
  n_title text;
  n_body text;
  n_event text;
  v_tag text;
BEGIN
  IF (NEW.status = 'published' AND (OLD IS NULL OR OLD.status <> 'published')) THEN
    is_eval := (NEW.title ILIKE '%تقييم%' OR NEW.title ILIKE '%évaluation%' OR NEW.content ILIKE '%scorecard%');
    v_tag := 'de-rep-' || NEW.id::text;
    
    IF is_eval THEN
      n_event := 'new_evaluation';
      n_title := 'تمت إضافة استمارة تقييم بيداغوجي جديدة';
      n_body := 'أصدرت الإدارة استمارة التقييم والنتائج البيداغوجية لطفلك؛ اضغط للمعاينة والتحميل.';
    ELSE
      n_event := 'new_report';
      n_title := 'تم نشر تقرير دوري جديد';
      n_body := 'أضافت المربية المشرفة تقرير متابعة جديداً يخص طفلك في فضاء الأولياء.';
    END IF;

    INSERT INTO public.notification_outbox (
      target_type,
      target_child_id,
      event_type,
      title,
      body,
      url,
      tag
    ) VALUES (
      'single_parent',
      NEW.child_id,
      n_event,
      n_title,
      n_body,
      CASE WHEN is_eval THEN './parent.html#evaluations' ELSE './parent.html#reports' END,
      v_tag
    )
    ON CONFLICT (tag) DO NOTHING;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS trg_notify_on_new_report ON public.progress_reports;
CREATE TRIGGER trg_notify_on_new_report
AFTER INSERT OR UPDATE OF status ON public.progress_reports
FOR EACH ROW
EXECUTE FUNCTION public.de_notify_on_new_report();

-- 7. مشغل تلقائي: إشعار عند مشاركة ملف أو وثيقة جديدة تخص الطفل
CREATE OR REPLACE FUNCTION public.de_notify_on_new_document()
RETURNS trigger AS $$
DECLARE
  v_tag text;
BEGIN
  IF (NEW.shared_with_parent = true AND (OLD IS NULL OR OLD.shared_with_parent = false)) THEN
    v_tag := 'de-doc-' || NEW.id::text;
    
    INSERT INTO public.notification_outbox (
      target_type,
      target_child_id,
      event_type,
      title,
      body,
      url,
      tag
    ) VALUES (
      'single_parent',
      NEW.child_id,
      'new_report',
      'تم نشر ملف جديد في فضاء الأولياء',
      'أرفقت إدارة المركز وثيقة جديدة في ملف طفلك؛ يمكنك الاطلاع عليها وتحميلها.',
      './parent.html#docs',
      v_tag
    )
    ON CONFLICT (tag) DO NOTHING;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS trg_notify_on_new_document ON public.documents;
CREATE TRIGGER trg_notify_on_new_document
AFTER INSERT OR UPDATE OF shared_with_parent ON public.documents
FOR EACH ROW
EXECUTE FUNCTION public.de_notify_on_new_document();

-- 8. مشغل تلقائي: إشعار عند نشر برنامج تربوي جديد للقسم
CREATE OR REPLACE FUNCTION public.de_notify_on_new_program()
RETURNS trigger AS $$
DECLARE
  v_tag text;
BEGIN
  IF (NEW.status = 'published' AND (OLD IS NULL OR OLD.status <> 'published')) THEN
    v_tag := 'de-prog-' || NEW.id::text;
    
    INSERT INTO public.notification_outbox (
      target_type,
      target_section_id,
      event_type,
      title,
      body,
      url,
      tag
    ) VALUES (
      'section_parents',
      NEW.section_id,
      'announcement',
      'برنامج وأنشطة تربوية جديدة',
      'تم نشر البرنامج والأنشطة التربوية الجديدة لقسم طفلك في دار الإحسان.',
      './parent.html#programs',
      v_tag
    )
    ON CONFLICT (tag) DO NOTHING;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS trg_notify_on_new_program ON public.educational_programs;
CREATE TRIGGER trg_notify_on_new_program
AFTER INSERT OR UPDATE OF status ON public.educational_programs
FOR EACH ROW
EXECUTE FUNCTION public.de_notify_on_new_program();

COMMENT ON TABLE public.parent_device_tokens IS 'رموز FCM لهواتف وأجهزة الأولياء لتلقي إشعارات Android حتى بعد إغلاق التطبيق';
