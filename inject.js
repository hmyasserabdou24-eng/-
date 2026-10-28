/* Injected by the Android app at document start, only on the parents' space origin.
   The site's own files are NOT modified. This script:
   1) restores the Supabase session saved securely by the app (same sessionStorage key the site uses),
   2) reports every session change to the app so the refresh token is always saved (token rotation safe),
   3) disables the site's 30-minute inactivity logout (the app keeps the parent signed in on purpose),
   4) registers/unregisters the FCM token for the signed-in parent through RPCs that RLS protects,
   5) opens the right tab when a notification is tapped.
   __DE is defined by the app before this file: { seed, installId, fcm, ver }. */
(function () {
  'use strict';
  var KEY = 'de_parent_session';
  var D = (typeof __DE === 'object' && __DE) || {};
  var S; try { S = window.sessionStorage; } catch (e) { return; }
  var origFetch = window.fetch ? window.fetch.bind(window) : null;
  var oSet = Storage.prototype.setItem, oRem = Storage.prototype.removeItem, oClr = Storage.prototype.clear;

  function post(o) { try { window.DEBridge.postMessage(JSON.stringify(o)); } catch (e) {} }

  // 1) restore session (only if the tab has none; never overwrites a live session)
  try { if (D.seed && !S.getItem(KEY)) oSet.call(S, KEY, D.seed); } catch (e) {}

  // 2) report changes
  var regTimer = null;
  Storage.prototype.setItem = function (k, v) {
    oSet.apply(this, arguments);
    if (this === S && k === KEY) { post({ t: 'session', v: String(v) }); clearTimeout(regTimer); regTimer = setTimeout(maybeRegister, 800); }
  };
  Storage.prototype.removeItem = function (k) {
    oRem.apply(this, arguments);
    if (this === S && k === KEY) post({ t: 'cleared' });
  };
  Storage.prototype.clear = function () {
    oClr.apply(this, arguments);
    if (this === S) post({ t: 'cleared' });
  };

  // 3) no inactivity logout: neutralise DE.idle when de-core.js assigns window.DE
  var holder;
  try {
    Object.defineProperty(window, 'DE', {
      configurable: true,
      get: function () { return holder; },
      set: function (v) { if (v && typeof v === 'object') v.idle = function () { return function () {}; }; holder = v; }
    });
  } catch (e) {}

  // 4) device token registration
  function session() {
    try {
      var s = JSON.parse(S.getItem(KEY) || 'null');
      if (!s || !s.access_token || !s.refresh_token) return null;
      return s;
    } catch (e) { return null; }
  }
  function sub(jwt) {
    try { return JSON.parse(atob(jwt.split('.')[1].replace(/-/g, '+').replace(/_/g, '/'))).sub || ''; } catch (e) { return ''; }
  }
  function rpc(fn, body, s) {
    var C = window.DE_CONFIG || {};
    if (!origFetch || !C.supabaseUrl || !C.supabaseKey) return Promise.resolve(false);
    return origFetch(String(C.supabaseUrl).replace(/\/+$/, '') + '/rest/v1/rpc/' + fn, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', apikey: C.supabaseKey, Authorization: 'Bearer ' + s.access_token },
      body: JSON.stringify(body)
    }).then(function (r) { return r.ok; }, function () { return false; });
  }
  var registeredFor = '';
  function maybeRegister() {
    if (!D.fcm || !D.installId) return;
    var s = session(); if (!s) return;
    var now = Math.floor(Date.now() / 1000);
    if (s.expires_at && s.expires_at < now + 5) return;       // expired: the site refreshes it, then setItem fires again
    var uid = sub(s.access_token); if (!uid) return;
    if (registeredFor === uid + '|' + D.fcm) return;
    rpc('register_device_token', { p_install_id: D.installId, p_token: D.fcm, p_app_version: String(D.ver || '') }, s)
      .then(function (ok) { if (ok) registeredFor = uid + '|' + D.fcm; });
  }
  function unregister() {
    var s = session();
    if (!s || !D.installId) return Promise.resolve();
    registeredFor = '';
    return Promise.race([rpc('unregister_device_token', { p_install_id: D.installId }, s), new Promise(function (r) { setTimeout(r, 4000); })]);
  }
  // unlink this device BEFORE the site calls /auth/v1/logout (manual sign-out)
  if (origFetch) {
    window.fetch = function (input, init) {
      var u = typeof input === 'string' ? input : (input && input.url) || '';
      if (u.indexOf('/auth/v1/logout') !== -1) {
        return unregister().catch(function () {}).then(function () { return origFetch(input, init); });
      }
      return origFetch(input, init);
    };
  }
  document.addEventListener('DOMContentLoaded', function () { setTimeout(maybeRegister, 1500); });
  setInterval(maybeRegister, 60000);

  // 5) open a tab after notification tap (tabs only exist after sign-in)
  var IDX = { children: 0, programs: 1, reports: 2, appts: 3, msgs: 4, docs: 5 };
  window.__deOpenTab = function (route) {
    if (!Object.prototype.hasOwnProperty.call(IDX, route)) return;
    var t0 = Date.now();
    (function tick() {
      var b = document.querySelectorAll('#tabs button')[IDX[route]];
      if (b) { b.click(); return; }
      if (Date.now() - t0 < 120000) setTimeout(tick, 400);
    })();
  };
})();
