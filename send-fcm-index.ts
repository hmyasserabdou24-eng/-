// Supabase Edge Function: send-fcm
// Sends generic FCM notifications for rows of public.notification_outbox to the devices of the
// TARGET parents only. Invoked by a Database Webhook (INSERT on notification_outbox) and/or by a
// scheduler for retries. Deploy with:  supabase functions deploy send-fcm --no-verify-jwt
// Secrets (Supabase > Edge Functions > Secrets, never in the APK):
//   FCM_SERVICE_ACCOUNT  = full JSON of a Firebase service account (role: Firebase Cloud Messaging API Admin)
//   FCM_DISPATCH_SECRET  = long random string; the caller sends it in header x-dispatch-secret
// SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY are provided automatically by Supabase.
import { createClient } from "npm:@supabase/supabase-js@2";

const sb = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
  auth: { persistSession: false },
});

// Fixed, generic texts: nothing from the outbox/title/body (which could be edited by admins) is ever sent.
const TEXT: Record<string, string> = {
  new_message: "لديك رسالة جديدة من إدارة دار الإحسان",
  new_report: "تم نشر ملف جديد في فضاء الأولياء",
  new_evaluation: "تم نشر ملف جديد في فضاء الأولياء",
};
const GENERIC = "لديك إشعار جديد من دار الإحسان";
const ROUTE: Record<string, string> = {
  new_message: "msgs", new_report: "reports", new_evaluation: "reports",
  announcement: "msgs", reminder: "appts", system_alert: "children",
};

type Outbox = {
  id: string; target_type: string; target_child_id: string | null; target_parent_id: string | null;
  target_section_id: string | null; event_type: string; tag: string | null;
};

function safeEqual(a: string, b: string): boolean {
  const x = new TextEncoder().encode(a), y = new TextEncoder().encode(b);
  if (x.length !== y.length) return false;
  let r = 0;
  for (let i = 0; i < x.length; i++) r |= x[i] ^ y[i];
  return r === 0;
}

// ---- Google OAuth for FCM HTTP v1 (service account JWT, RS256) ----
let cached: { token: string; exp: number } | null = null;
const b64u = (b: ArrayBuffer | string) =>
  btoa(typeof b === "string" ? b : String.fromCharCode(...new Uint8Array(b))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");

async function accessToken(sa: { client_email: string; private_key: string; token_uri?: string }): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  if (cached && cached.exp - 60 > now) return cached.token;
  const pem = sa.private_key.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const der = Uint8Array.from(atob(pem), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const head = b64u(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claim = b64u(JSON.stringify({
    iss: sa.client_email, scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: sa.token_uri ?? "https://oauth2.googleapis.com/token", iat: now, exp: now + 3600,
  }));
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(`${head}.${claim}`));
  const res = await fetch(sa.token_uri ?? "https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: `${head}.${claim}.${b64u(sig)}` }),
  });
  if (!res.ok) throw new Error("oauth " + res.status);
  const j = await res.json();
  cached = { token: j.access_token, exp: now + (j.expires_in ?? 3600) };
  return cached.token;
}

// ---- Who may receive this event? (never trust a client; recompute from source rows) ----
async function activeParents(ids: string[]): Promise<string[]> {
  const out: string[] = [];
  for (let i = 0; i < ids.length; i += 100) {
    const { data, error } = await sb.from("parent_profiles").select("user_id").in("user_id", ids.slice(i, i + 100)).eq("disabled", false);
    if (error) throw error;
    out.push(...(data ?? []).map((r) => r.user_id));
  }
  return out;
}
async function parentsOfChildren(childIds: string[]): Promise<string[]> {
  if (!childIds.length) return [];
  const { data, error } = await sb.from("parent_children").select("parent_id").in("child_id", childIds);
  if (error) throw error;
  return [...new Set((data ?? []).map((r) => r.parent_id))];
}

/** returns { parents } or { skip: reason } when the event must not be sent (e.g. unpublished). */
async function recipients(o: Outbox): Promise<{ parents: string[] } | { skip: string }> {
  const m = /^de-(msg|rep)-([0-9a-f-]{36})$/.exec(o.tag ?? "");
  if (m && m[1] === "msg") {
    const { data: msg, error } = await sb.from("messages").select("audience,parent_id,child_id,published_at").eq("id", m[2]).maybeSingle();
    if (error) throw error;
    if (!msg) return { skip: "message not found" };
    if (!msg.published_at || new Date(msg.published_at) > new Date()) return { skip: "message not published" };
    if (msg.audience === "parent" && msg.parent_id) return { parents: await activeParents([msg.parent_id]) };
    if (msg.audience === "child" && msg.child_id) return { parents: await activeParents(await parentsOfChildren([msg.child_id])) };
    if (msg.audience === "all") return { parents: await allActiveParents() };
    return { skip: "unknown audience" };
  }
  if (m && m[1] === "rep") {
    const { data: rep, error } = await sb.from("progress_reports").select("child_id,status").eq("id", m[2]).maybeSingle();
    if (error) throw error;
    if (!rep || rep.status !== "published") return { skip: "report not published" };
    return { parents: await activeParents(await parentsOfChildren([rep.child_id])) };
  }
  // Generic/manual outbox rows: follow the explicit target; a parent target always wins over target_type.
  if (o.target_parent_id) return { parents: await activeParents([o.target_parent_id]) };
  switch (o.target_type) {
    case "single_parent":
      if (!o.target_child_id) return { skip: "no target child" };
      return { parents: await activeParents(await parentsOfChildren([o.target_child_id])) };
    case "section_parents": {
      if (!o.target_section_id) return { skip: "no target section" };
      const { data, error } = await sb.from("children").select("id").eq("section_id", o.target_section_id).eq("active", true);
      if (error) throw error;
      return { parents: await activeParents(await parentsOfChildren((data ?? []).map((c) => c.id))) };
    }
    case "all_parents":
    case "broadcast":
      return { parents: await allActiveParents() };
  }
  return { skip: "unknown target" };
}
async function allActiveParents(): Promise<string[]> {
  const { data, error } = await sb.from("parent_profiles").select("user_id").eq("disabled", false).limit(5000);
  if (error) throw error;
  return (data ?? []).map((r) => r.user_id);
}

// ---- send ----
async function sendOne(sa: any, tokenRow: { id: string; fcm_token: string }, o: Outbox): Promise<"sent" | "dead" | "retry" | "failed"> {
  const kind = o.event_type;
  const body = {
    message: {
      token: tokenRow.fcm_token,
      notification: { title: "دار الإحسان – فضاء الأولياء", body: TEXT[kind] ?? GENERIC },
      data: { event_id: o.id, kind, route: ROUTE[kind] ?? "msgs" },
      android: {
        priority: "HIGH", ttl: "86400s", collapse_key: "de-" + o.id.slice(0, 8),
        notification: { channel_id: "parents_general", tag: o.id },
      },
    },
  };
  try {
    const res = await fetch(`https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`, {
      method: "POST",
      headers: { Authorization: `Bearer ${await accessToken(sa)}`, "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    if (res.ok) return "sent";
    const txt = await res.text();
    if (res.status === 404 || /UNREGISTERED|INVALID_ARGUMENT/.test(txt)) return "dead";
    if (res.status === 429 || res.status >= 500) return "retry";
    console.error("fcm permanent error", res.status);   // no token / body logged
    return "failed";
  } catch (_e) { return "retry"; }
}

async function processOne(sa: any, o: Outbox) {
  const r = await recipients(o);
  if ("skip" in r) {
    await sb.from("fcm_dispatch").update({ status: "done", finished_at: new Date().toISOString(), last_error: r.skip }).eq("outbox_id", o.id);
    await sb.from("notification_outbox").update({ status: "cancelled", error_message: r.skip }).eq("id", o.id);
    return { id: o.id, skipped: r.skip };
  }
  const tokens: { id: string; fcm_token: string }[] = [];
  for (let i = 0; i < r.parents.length; i += 100) {
    const { data, error } = await sb.from("device_tokens").select("id,fcm_token").in("parent_user_id", r.parents.slice(i, i + 100));
    if (error) throw error;
    tokens.push(...(data ?? []));
  }
  const { data: done } = await sb.from("fcm_deliveries").select("device_token_id").eq("outbox_id", o.id);
  const already = new Set((done ?? []).map((d) => d.device_token_id));
  let sent = 0, retry = 0, failed = 0;
  const todo = tokens.filter((t) => !already.has(t.id));
  for (let i = 0; i < todo.length; i += 10) {
    await Promise.all(todo.slice(i, i + 10).map(async (t) => {
      const res = await sendOne(sa, t, o);
      if (res === "sent") {
        sent++;
        await sb.from("fcm_deliveries").upsert({ outbox_id: o.id, device_token_id: t.id, status: "sent" }, { onConflict: "outbox_id,device_token_id", ignoreDuplicates: true });
      } else if (res === "dead") {
        await sb.from("device_tokens").delete().eq("id", t.id);       // uninstalled / token rotated
      } else if (res === "retry") retry++;
      else failed++;
    }));
  }
  const now = new Date().toISOString();
  if (retry > 0) {
    await sb.from("fcm_dispatch").update({ status: "retry", last_error: `${retry} transient failures` }).eq("outbox_id", o.id);
  } else {
    await sb.from("fcm_dispatch").update({ status: failed ? "failed" : "done", finished_at: now, last_error: failed ? `${failed} permanent failures` : null }).eq("outbox_id", o.id);
    await sb.from("notification_outbox").update({ status: failed && !sent ? "failed" : "sent", sent_at: now, error_message: tokens.length ? null : "no registered devices" }).eq("id", o.id);
  }
  return { id: o.id, devices: tokens.length, sent, retry, failed };
}

Deno.serve(async (req) => {
  const secret = Deno.env.get("FCM_DISPATCH_SECRET") ?? "";
  if (req.method !== "POST" || !secret || !safeEqual(req.headers.get("x-dispatch-secret") ?? "", secret)) {
    return new Response("forbidden", { status: 403 });
  }
  let sa: any;
  try { sa = JSON.parse(Deno.env.get("FCM_SERVICE_ACCOUNT") ?? ""); } catch { return new Response("not configured", { status: 500 }); }
  if (!sa?.project_id || !sa?.private_key || !sa?.client_email) return new Response("not configured", { status: 500 });

  const { data, error } = await sb.rpc("claim_fcm_outbox", { p_limit: 20 });
  if (error) { console.error("claim failed", error.code); return new Response("error", { status: 500 }); }
  const results = [];
  for (const o of (data ?? []) as Outbox[]) {
    try { results.push(await processOne(sa, o)); }
    catch (e) {
      console.error("process failed", o.id, (e as Error).name);
      await sb.from("fcm_dispatch").update({ status: "retry", last_error: "internal error" }).eq("outbox_id", o.id);
    }
  }
  return Response.json({ processed: results.length, results });
});
