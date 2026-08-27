// kiosk-overlay/js/shims/supabase-realtime.js
//
// Stands in for `js/data/services/supabase/supabase-config.js` in the KIOSK bundle.
// Same module shape (a lazy `supabase` proxy that publishes `window.supabase` on first
// touch) — but it is REALTIME ONLY, and it is BUNDLED.
//
// ## Why the real module can't be used
//
// `supabase-config.js` starts with
//     import { createClient } from 'https://cdn.skypack.dev/@supabase/supabase-js@2'
// A kiosk is an appliance on someone's wall. It boots when the power comes back, often
// before the WAN does, and a CDN import is a hard boot failure with no recovery — the
// module never evaluates, so the entire settings stack silently never exists. Bundling
// is mandatory, not a size optimisation.
//
// ## Why not just bundle supabase-js
//
// Because the settings stack uses exactly ONE thing from it: `channel()` (plus
// `removeChannel()`). Auth, PostgREST, Storage and Functions are all dead weight — every
// database call goes through EdgeClient's `fetch` to the edge functions, not through
// supabase-js. Bundling the whole SDK measured at **+236 KB** for that one method
// (409 KB shell vs 173 KB without). Bundling `@supabase/realtime-js` directly gets the
// shell to ~250-270 KB, which is what has to parse and live in RAM on a 512 MB Echo Show.
//
// ## What consumes this
//
//   device-settings-service.subscribeToSettingsChanges/broadcastSettingsChange
//     → window.supabase.channel(`device_settings_${userId}`)   [console/mobile → this kiosk]
//   settings-sync.connect/broadcast
//     → this._supabase.channel(`user_settings_${userId}`) + removeChannel  [account fan-out]
//
// Both use only: channel(topic) → .on('broadcast', {event}, cb) / .subscribe(cb) /
// .send({type,event,payload}) / .unsubscribe(), and client.removeChannel(ch).
// That is the whole contract this facade has to satisfy.
//
// The anon key is the right credential here, exactly as on the dashboard: supabase-js
// connects realtime with the anon key too (these are broadcast channels, not RLS-gated
// table streams). The account JWT authenticates the EDGE calls, not the socket.
//
// (Kiosk Real Login, Phase 2, item 4.)

import { RealtimeClient } from '@supabase/realtime-js';
import { SUPABASE_CONFIG } from '../../../js/data/auth/auth-config.js';

let _client = null;

function initializeRealtime() {
  if (_client) return _client;

  // SUPABASE_CONFIG is flavor-driven on a kiosk (DashieNative.getSupabaseConfig()), NOT
  // hostname-driven — a kiosk's page is served by the user's own HA box, whose hostname
  // matches neither `dev.` nor `local.`, and the old fallthrough was PRODUCTION.
  // See auth-config.js + §10 bug 7. Phase 2 is the change that would have armed it.
  const { url, anonKey } = SUPABASE_CONFIG;

  // Same URL construction supabase-js does: `${url}/realtime/v1`, http→ws.
  const realtimeUrl = new URL('realtime/v1', `${url}/`);
  realtimeUrl.protocol = realtimeUrl.protocol.replace('http', 'ws');

  _client = new RealtimeClient(realtimeUrl.href, { params: { apikey: anonKey } });

  window.supabase = _client;
  console.log('📡 Kiosk realtime client initialized:', realtimeUrl.origin);

  return _client;
}

// Same lazy-proxy shape as the real supabase-config.js: settings-service.setEdgeClient()
// force-touches a property to trigger initialization, then reads window.supabase.
export const supabase = new Proxy({}, {
  get(_target, prop) {
    const client = initializeRealtime();
    const value = client[prop];
    return typeof value === 'function' ? value.bind(client) : value;
  }
});

export default supabase;
