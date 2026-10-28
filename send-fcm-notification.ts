// Supabase Edge Function: send-fcm-notification
// Sends push notifications to Dar El Ihsan Parents Android App via Firebase Cloud Messaging (HTTP v1 API)
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.8";

interface ServiceAccountKey {
  project_id: string;
  private_key: string;
  client_email: string;
}

// Convert PEM string to ArrayBuffer for crypto.subtle
function pemToArrayBuffer(pem: string): ArrayBuffer {
  const b64 = pem
    .replace(/-----\w+ PRIVATE KEY-----/g, "")
    .replace(/\s+/g, "");
  const raw = atob(b64);
  const buf = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) {
    buf[i] = raw.charCodeAt(i);
  }
  return buf.buffer;
}

// Base64URL encoding helper
function base64UrlEncode(data: Uint8Array | string): string {
  let str: string;
  if (typeof data === "string") {
    str = btoa(unescape(encodeURIComponent(data)));
  } else {
    let binary = "";
    for (let i = 0; i < data.byteLength; i++) {
      binary += String.fromCharCode(data[i]);
    }
    str = btoa(binary);
  }
  return str.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

// Generate Google OAuth 2.0 Access Token using Service Account
async function getGoogleAccessToken(sa: ServiceAccountKey): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const header = { alg: "RS256", typ: "JWT" };
  const claims = {
    iss: sa.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    exp: now + 3600,
    iat: now,
  };

  const encodedHeader = base64UrlEncode(JSON.stringify(header));
  const encodedClaims = base64UrlEncode(JSON.stringify(claims));
  const unsignedToken = `${encodedHeader}.${encodedClaims}`;

  const keyBuffer = pemToArrayBuffer(sa.private_key);
  const cryptoKey = await crypto.subtle.importKey(
    "pkcs8",
    keyBuffer,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"]
  );

  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    cryptoKey,
    new TextEncoder().encode(unsignedToken)
  );

  const signedJwt = `${unsignedToken}.${base64UrlEncode(new Uint8Array(signature))}`;

  // Exchange JWT for access token
  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: signedJwt,
    }),
  });

  if (!res.ok) {
    const errText = await res.text();
    throw new Error(`Failed to obtain Google access token: ${res.status} ${errText}`);
  }

  const json = await res.json();
  return json.access_token;
}

serve(async (req) => {
  // CORS headers
  const corsHeaders = {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  };

  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
    const supabaseServiceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
    const saJsonStr = Deno.env.get("FIREBASE_SERVICE_ACCOUNT_JSON");

    if (!saJsonStr) {
      return new Response(
        JSON.stringify({
          error: "FIREBASE_SERVICE_ACCOUNT_JSON secret is not set in Supabase Settings.",
          hint: "Please add your Firebase service account JSON under Supabase > Project Settings > Edge Functions > Secrets",
        }),
        { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const sa: ServiceAccountKey = JSON.parse(saJsonStr);
    const supabase = createClient(supabaseUrl, supabaseServiceKey);

    const body = await req.json().catch(() => ({}));
    const outboxId = body.outbox_id || body.record?.id;

    if (!outboxId) {
      return new Response(
        JSON.stringify({ error: "Missing outbox_id parameter." }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // 1. Fetch outbox item
    const { data: outboxItem, error: outboxError } = await supabase
      .from("notification_outbox")
      .select("*")
      .eq("id", outboxId)
      .single();

    if (outboxError || !outboxItem) {
      return new Response(
        JSON.stringify({ error: "Outbox item not found", details: outboxError }),
        { status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    if (outboxItem.status === "sent") {
      return new Response(
        JSON.stringify({ status: "already_sent", outbox_id: outboxId }),
        { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // 2. Identify target parent user IDs
    let targetTokens: Array<{ id: string; fcm_token: string; parent_user_id: string }> = [];

    if (outboxItem.target_type === "single_parent" && outboxItem.target_parent_id) {
      const { data } = await supabase
        .from("parent_device_tokens")
        .select("id, fcm_token, parent_user_id")
        .eq("parent_user_id", outboxItem.target_parent_id)
        .eq("is_active", true);
      targetTokens = data || [];
    } else if (outboxItem.target_child_id) {
      // Find all parents linked to this child
      const { data: parents } = await supabase
        .from("parent_children")
        .select("parent_id")
        .eq("child_id", outboxItem.target_child_id);

      const parentIds = (parents || []).map((p: any) => p.parent_id);
      if (parentIds.length > 0) {
        const { data } = await supabase
          .from("parent_device_tokens")
          .select("id, fcm_token, parent_user_id")
          .in("parent_user_id", parentIds)
          .eq("is_active", true);
        targetTokens = data || [];
      }
    } else if (outboxItem.target_type === "section_parents" && outboxItem.target_section_id) {
      // Find all children in section
      const { data: children } = await supabase
        .from("children")
        .select("id")
        .eq("section_id", outboxItem.target_section_id);

      const childIds = (children || []).map((c: any) => c.id);
      if (childIds.length > 0) {
        const { data: parents } = await supabase
          .from("parent_children")
          .select("parent_id")
          .in("child_id", childIds);

        const parentIds = Array.from(new Set((parents || []).map((p: any) => p.parent_id)));
        if (parentIds.length > 0) {
          const { data } = await supabase
            .from("parent_device_tokens")
            .select("id, fcm_token, parent_user_id")
            .in("parent_user_id", parentIds)
            .eq("is_active", true);
          targetTokens = data || [];
        }
      }
    } else if (outboxItem.target_type === "all_parents" || outboxItem.target_type === "broadcast") {
      const { data } = await supabase
        .from("parent_device_tokens")
        .select("id, fcm_token, parent_user_id")
        .eq("is_active", true);
      targetTokens = data || [];
    }

    // Deduplicate tokens
    const uniqueTokensMap = new Map<string, { id: string; fcm_token: string; parent_user_id: string }>();
    for (const t of targetTokens) {
      if (!uniqueTokensMap.has(t.fcm_token)) {
        uniqueTokensMap.set(t.fcm_token, t);
      }
    }
    const tokensToSend = Array.from(uniqueTokensMap.values());

    if (tokensToSend.length === 0) {
      // No active registered devices found for this target
      await supabase
        .from("notification_outbox")
        .update({
          status: "sent",
          sent_at: new Date().toISOString(),
          error_message: "No active device tokens found for recipient(s)",
        })
        .eq("id", outboxId);

      return new Response(
        JSON.stringify({
          success: true,
          message: "No registered device tokens found for target.",
          outbox_id: outboxId,
          tokens_count: 0,
        }),
        { headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // 3. Obtain Google OAuth 2.0 token
    const accessToken = await getGoogleAccessToken(sa);
    const fcmEndpoint = `https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`;

    // Determine target tab from URL (e.g., './parent.html#msgs' -> 'msgs')
    let targetTab = "children";
    const hashMatch = /#([a-zA-Z0-9_-]+)/.exec(outboxItem.url || "");
    if (hashMatch) {
      targetTab = hashMatch[1];
    }

    let successCount = 0;
    let failureCount = 0;
    const logsToInsert: any[] = [];
    const tokensToDeactivate: string[] = [];

    // 4. Send FCM messages in parallel
    await Promise.all(
      tokensToSend.map(async (device) => {
        const payload = {
          message: {
            token: device.fcm_token,
            notification: {
              title: outboxItem.title,
              body: outboxItem.body,
            },
            data: {
              outbox_id: outboxId,
              url: outboxItem.url || "./parent.html",
              tag: outboxItem.tag || `de-${Date.now()}`,
              target_tab: targetTab,
            },
            android: {
              priority: "HIGH",
              notification: {
                channel_id: "dar_el_ihsan_parents",
                sound: "default",
                notification_priority: "PRIORITY_HIGH",
                visibility: "PRIVATE",
                click_action: "FLUTTER_NOTIFICATION_CLICK",
              },
            },
          },
        };

        try {
          const resp = await fetch(fcmEndpoint, {
            method: "POST",
            headers: {
              "Content-Type": "application/json",
              Authorization: `Bearer ${accessToken}`,
            },
            body: JSON.stringify(payload),
          });

          const respText = await resp.text();
          const isSuccess = resp.ok;

          if (isSuccess) {
            successCount++;
          } else {
            failureCount++;
            // Check if token is invalid or unregistered
            if (
              respText.includes("UNREGISTERED") ||
              respText.includes("NOT_FOUND") ||
              respText.includes("InvalidArgument")
            ) {
              tokensToDeactivate.push(device.fcm_token);
            }
          }

          logsToInsert.push({
            outbox_id: outboxId,
            endpoint: `fcm:${device.fcm_token.slice(0, 15)}...`,
            status_code: resp.status,
            response_body: respText.slice(0, 500),
            success: isSuccess,
          });
        } catch (e: any) {
          failureCount++;
          logsToInsert.push({
            outbox_id: outboxId,
            endpoint: `fcm:${device.fcm_token.slice(0, 15)}...`,
            status_code: 0,
            response_body: String(e.message || e).slice(0, 500),
            success: false,
          });
        }
      })
    );

    // 5. Deactivate invalid/stale tokens
    if (tokensToDeactivate.length > 0) {
      await supabase
        .from("parent_device_tokens")
        .update({ is_active: false, updated_at: new Date().toISOString() })
        .in("fcm_token", tokensToDeactivate);
    }

    // 6. Record logs
    if (logsToInsert.length > 0) {
      await supabase.from("notification_logs").insert(logsToInsert);
    }

    // 7. Update outbox record
    await supabase
      .from("notification_outbox")
      .update({
        status: successCount > 0 ? "sent" : "failed",
        sent_at: new Date().toISOString(),
        error_message:
          failureCount > 0
            ? `${failureCount} deliveries failed out of ${tokensToSend.length}`
            : null,
      })
      .eq("id", outboxId);

    return new Response(
      JSON.stringify({
        success: true,
        outbox_id: outboxId,
        tokens_targeted: tokensToSend.length,
        delivered: successCount,
        failed: failureCount,
      }),
      { headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  } catch (err: any) {
    return new Response(
      JSON.stringify({ error: err.message || String(err) }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});
