/**
 * Paramanu Seniors Notices - admin console
 *
 * Generates activation codes, writes them to Realtime Database, and shows them in a printable
 * page for the dispensary counter.
 *
 * SETUP (once)
 *  1. New Apps Script project at script.google.com.
 *  2. Paste this file as Code.gs and Index.html as an HTML file named exactly "Index".
 *  3. Firebase console -> Project settings -> Service accounts -> Generate new private key.
 *     Open the downloaded JSON, copy the whole thing.
 *  4. Apps Script -> Project Settings -> Script Properties, add:
 *        SERVICE_ACCOUNT_JSON  = <the entire JSON file contents>
 *        DATABASE_URL          = https://paramanu-seniors-default-rtdb.asia-southeast1.firebasedatabase.app
 *        ALLOWED_EDITORS       = <comma-separated Google account emails permitted to use this>
 *        SENDER_URL            = <the sender web app's /exec URL, no trailing path>  (optional)
 *     Take DATABASE_URL from the Realtime Database page; it is region qualified.
 *  5. Deploy -> Web app.
 *       Execute as:     User accessing the web app   <- REQUIRED, see requireEditor_
 *       Who has access: Anyone with a Google account
 *  6. For SENDER_URL to work, this project's manifest needs an identity scope so the sender can
 *     tell who is calling. Project Settings -> "Show appsscript.json", then:
 *        "oauthScopes": [
 *          "https://www.googleapis.com/auth/script.external_request",
 *          "https://www.googleapis.com/auth/userinfo.email"
 *        ]
 *     Run any function once afterwards to re-consent. Without SENDER_URL a Revoke still works --
 *     it is recorded in the database and the phone finds it within a day -- it just is not pushed.
 *
 * This console never gets FCM credentials. It asks the sender to broadcast a revoke and cannot
 * broadcast anything itself; issuing codes and reaching four hundred phones stay separate jobs.
 *
 * The service account key is a real credential. It lives in Script Properties, never in this file,
 * and never in the Android app.
 */

var SCRIPT_VERSION = '2026-09-01-codes';

// Must stay identical to ActivationCode.kt in the Android app. Crockford Base32: no I, L, O or U.
// GeneratedCodeCompatibilityTest.kt pins this agreement; if you change the rule here, regenerate
// the codes in that test.
var ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
var PAYLOAD_LENGTH = 7;

/** Position-weighted so that transposing two adjacent characters changes the sum. */
function checkCharacter(payload) {
  if (payload.length !== PAYLOAD_LENGTH) return null;
  var sum = 0;
  for (var i = 0; i < payload.length; i++) {
    var value = ALPHABET.indexOf(payload.charAt(i));
    if (value < 0) return null;
    sum += value * (i + 1);
  }
  return ALPHABET.charAt(sum % ALPHABET.length);
}

function generateCode_() {
  var payload = '';
  for (var i = 0; i < PAYLOAD_LENGTH; i++) {
    payload += ALPHABET.charAt(Math.floor(Math.random() * ALPHABET.length));
  }
  return payload + checkCharacter(payload);
}

function isValidCode(code) {
  if (!code || code.length !== PAYLOAD_LENGTH + 1) return false;
  return checkCharacter(code.substring(0, PAYLOAD_LENGTH)) === code.charAt(PAYLOAD_LENGTH);
}

// ---------------------------------------------------------------- Firebase

// ---------------------------------------------------------------- who may use this

/**
 * Refuses anyone who is not on the allowlist.
 *
 * ALLOWED_EDITORS is a Script Property: a comma-separated list of Google account emails. Kept in a
 * property rather than in this file so adding or removing a person does not need a redeploy, and so
 * the list is not readable in a public repository.
 *
 * REQUIRES the deployment to be set to:
 *   Execute as:      User accessing the web app
 *   Who has access:  Anyone with a Google account
 *
 * Under "Execute as: Me" the visitor's email comes back empty for consumer accounts, and an
 * allowlist that matches nobody is indistinguishable from one that is simply misconfigured. That is
 * why an empty email is reported as a deployment error rather than quietly refused -- the tempting
 * "fix" for a silent refusal is to treat empty as allowed, which would remove the lock entirely.
 */
function requireEditor_() {
  var email = String((Session.getActiveUser() && Session.getActiveUser().getEmail()) || '').trim().toLowerCase();

  if (!email) {
    throw new Error('This deployment cannot identify you. Set Execute as: "User accessing the web app" and Who has access: "Anyone with a Google account", then deploy a new version.');
  }

  var allowed = String(PropertiesService.getScriptProperties().getProperty('ALLOWED_EDITORS') || '');
  var list = allowed.split(',').map(function (e) { return e.trim().toLowerCase(); }).filter(function (e) { return e.length > 0; });

  if (list.length === 0) {
    throw new Error('No ALLOWED_EDITORS script property is set, so nobody is permitted. Add the emails that may use this console.');
  }

  if (list.indexOf(email) < 0) {
    throw new Error(email + ' is not permitted to use this. Ask for your address to be added.');
  }

  return email;
}

/** True when the caller is allowed, without throwing. Used to choose what doGet renders. */
function isEditor_() {
  try {
    requireEditor_();
    return true;
  } catch (e) {
    return false;
  }
}

/** The page shown to somebody who reaches the URL but is not on the list. */
function refusalPage_(reason) {
  var safe = String(reason || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  return HtmlService.createHtmlOutput('<div style="font-family:-apple-system,Segoe UI,Roboto,sans-serif;max-width:520px;margin:60px auto;padding:0 20px;color:#10131a"><h1 style="font-size:20px">Not available</h1><p style="font-size:15px;line-height:1.5">' + safe + '</p></div>').setTitle('Not available');
}

function property_(name) {
  var value = PropertiesService.getScriptProperties().getProperty(name);
  if (!value) throw new Error('Script property ' + name + ' is not set. See the setup notes at the top of Code.gs.');
  return value;
}

function databaseUrl_() {
  return property_('DATABASE_URL').replace(/\/+$/, '');
}

/**
 * Mints an OAuth2 access token from the service account.
 *
 * Cached for fifty minutes against a one hour token, so a session of printing slips makes one token
 * request rather than one per click.
 */
function accessToken_() {
  var cache = CacheService.getScriptCache();
  var cached = cache.get('fb_token');
  if (cached) return cached;

  var key = JSON.parse(property_('SERVICE_ACCOUNT_JSON'));
  var now = Math.floor(Date.now() / 1000);

  var header = Utilities.base64EncodeWebSafe(JSON.stringify({ alg: 'RS256', typ: 'JWT' })).replace(/=+$/, '');
  var claim = Utilities.base64EncodeWebSafe(JSON.stringify({ iss: key.client_email, scope: 'https://www.googleapis.com/auth/firebase.database https://www.googleapis.com/auth/userinfo.email', aud: 'https://oauth2.googleapis.com/token', exp: now + 3600, iat: now })).replace(/=+$/, '');

  var signature = Utilities.base64EncodeWebSafe( Utilities.computeRsaSha256Signature(header + '.' + claim, key.private_key) ).replace(/=+$/, '');

  var response = UrlFetchApp.fetch('https://oauth2.googleapis.com/token', { method: 'post', payload: { grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: header + '.' + claim + '.' + signature }, muteHttpExceptions: true });

  if (response.getResponseCode() !== 200) {
    throw new Error('Token request failed: ' + response.getContentText());
  }
  var token = JSON.parse(response.getContentText()).access_token;
  cache.put('fb_token', token, 3000);
  return token;
}

function firebase_(method, path, payload) {
  var options = { method: method, contentType: 'application/json', headers: { Authorization: 'Bearer ' + accessToken_() }, muteHttpExceptions: true };
  if (payload !== undefined) options.payload = JSON.stringify(payload);

  var response = UrlFetchApp.fetch(databaseUrl_() + path, options);
  if (response.getResponseCode() >= 300) {
    throw new Error(method + ' ' + path + ' failed: ' + response.getContentText());
  }
  var text = response.getContentText();
  return text ? JSON.parse(text) : null;
}

// ---------------------------------------------------------------- Audit

/**
 * The only events the audit may contain. A closed set on purpose: a typo that invents a sixth
 * event would produce a row nobody ever queries, and the log is the record the NGO is meant to
 * trust without asking a developer.
 */
var AUDIT_EVENTS = ['issued', 'revoked', 'restored', 'released', 'note'];

/**
 * Appends one entry to a code's history.
 *
 * POST rather than PATCH so Realtime Database mints the key. A client-generated key from
 * Date.now() is exactly what silently lost a notice in SYSTEM.md quirk 5.12: two actions inside
 * one millisecond and the second overwrites the first with no error anywhere.
 *
 * This never writes to /codes. Adding a field there needs a matching .validate rule or the app's
 * claim write starts failing for every unclaimed slip -- see the note on `note` in SYSTEM.md 6a.
 */
function audit_(code, event, by, extra) {
  if (AUDIT_EVENTS.indexOf(event) < 0) throw new Error('Unknown audit event: ' + event);

  var entry = { at: Date.now(), by: by, event: event };
  if (extra) {
    for (var key in extra) {
      if (extra.hasOwnProperty(key) && extra[key] !== undefined && extra[key] !== null) {
        entry[key] = extra[key];
      }
    }
  }
  firebase_('post', '/audit/' + code + '.json', entry);
}

/**
 * A code's stored history plus the one event that is never stored: the device's own claim.
 *
 * The phone cannot write here. Letting it would mean opening /audit to 400 devices, and the audit
 * is only trustworthy because nothing but the console can reach it. So the claim is reconstructed
 * from /codes/{CODE}.activatedAt at render time.
 *
 * Known limit: activatedAt holds only the most recent claim, so a released-and-reclaimed code
 * shows just the latest. The console actions either side still show the shape of what happened.
 */
function auditTimeline_(entries, node) {
  var out = [];

  for (var key in entries) {
    if (!entries.hasOwnProperty(key)) continue;
    var e = entries[key] || {};
    out.push({ at: e.at || 0, by: e.by || '', event: e.event || '', to: e.to || '' });
  }

  if (node && node.activatedAt) {
    out.push({ at: node.activatedAt, by: '', event: 'claimed', to: '' });
  }

  out.sort(function (a, b) { return a.at - b.at; });
  return out;
}

// ---------------------------------------------------------------- Operations

/**
 * Creates [count] fresh codes and stores them.
 *
 * PATCH rather than PUT: PUT at /codes would replace the whole collection and silently destroy
 * every code already issued, including the claims attached to them.
 */
function createCodes(count, note) {
  var by = requireEditor_();
  count = Math.max(1, Math.min(200, parseInt(count, 10) || 1));
  note = (note || '').toString().trim().slice(0, 120);

  var existing = firebase_('get', '/codes.json?shallow=true') || {};
  var updates = {};
  var created = [];
  var issued = Date.now();

  var guard = 0;
  while (created.length < count) {
    if (++guard > count * 50) throw new Error('Could not find unused codes; try again.');
    var code = generateCode_();
    if (existing[code] || updates[code]) continue;
    var node = { issued: issued };
    if (note) node.note = note;
    updates[code] = node;
    created.push(code);
  }

  firebase_('patch', '/codes.json', updates);

  // One multi-path PATCH rather than up to 200 POSTs. Safe to use a client-generated key here
  // precisely because these codes did not exist a moment ago, so their logs are empty and cannot
  // collide. Every other audit write uses POST and lets the server mint the key.
  var auditUpdates = {};
  for (var i = 0; i < created.length; i++) {
    var entry = { at: issued, by: by, event: 'issued' };
    if (note) entry.to = note;
    auditUpdates[created[i] + '/' + issued] = entry;
  }
  firebase_('patch', '/audit.json', auditUpdates);

  return created;
}

/**
 * Adds or changes the note on one code. Passing an empty string removes it.
 *
 * The note is for the counter's own bookkeeping ("Mrs Rao, ward 3", "batch printed 12 Sep"). It is
 * written and read only by this console; the app never sees it, and the security rules forbid the
 * app from altering it.
 */
function setNote(code, note) {
  var by = requireEditor_();
  if (!isValidCode(code)) throw new Error('Not a valid code: ' + code);
  note = (note || '').toString().trim().slice(0, 120);
  firebase_('patch', '/codes/' + code + '.json', { note: note || null });
  audit_(code, 'note', by, { to: note });
  return listCodes();
}

/**
 * Releases a claim so the code can be handed out again.
 *
 * This is for a claim that no longer belongs to any working phone: the app was uninstalled, the
 * phone was replaced, or its data was cleared. The device stores the anonymous UID locally, so
 * losing that storage orphans the claim -- the code stays marked "in use" with nobody able to prove
 * ownership of it.
 *
 * WARNING: if a working phone still holds this claim, releasing it cuts that phone off. Its next
 * check finds no usedBy, reads that as revoked, and returns the user to the code screen. Use
 * revokeCode for a device you intend to cut off; use this only for one that is genuinely gone.
 */
function releaseCode(code) {
  var by = requireEditor_();
  if (!isValidCode(code)) throw new Error('Not a valid code: ' + code);
  firebase_('patch', '/codes/' + code + '.json', { usedBy: null, activatedAt: null, revoked: null });
  audit_(code, 'released', by);
  return listCodes();
}

/**
 * Run from the editor when a Revoke reports that the sender replied with a web page.
 *
 * ALREADY RUN, ANSWER RECORDED: all three probes return 401, so the token is rejected outright.
 * Kept only so the finding can be reproduced; it goes when the queue replaces the HTTP bridge.
 *
 * There are only two causes and they need different fixes, so this prints the evidence that tells
 * them apart rather than leaving you to guess:
 *
 *   HTTP 200 + HTML, or a redirect to accounts.google.com
 *       The request never reached the script. The token was not accepted -- usually because
 *       userinfo.email is missing from this project's oauthScopes, or this account is not in the
 *       sender's ALLOWED_EDITORS.
 *
 *   HTTP 404/500 + an Apps Script error page mentioning a function
 *       The request reached Apps Script but the deployed version has no doPost. The sender needs
 *       Manage deployments -> edit -> New version. /exec always serves the deployed version, never
 *       HEAD, so editing the sender and running its tests proves nothing about what /exec answers.
 *
 * Sends nothing: the code below is deliberately malformed, so a working sender refuses it at the
 * shape check and no phone hears anything.
 */
function testSenderLink() {
  var url = PropertiesService.getScriptProperties().getProperty('SENDER_URL');
  if (!url) throw new Error('No SENDER_URL is set.');

  var base = url.replace(/\/+$/, '');
  var token = ScriptApp.getOAuthToken();

  // Three probes, one variable at a time, so a 401 can be attributed rather than guessed at.
  //
  //   GET  /exec         does this token authenticate against this web app at all?
  //   POST /exec         does POST work without a path? (expects a JSON "Unknown endpoint")
  //   POST /exec/revoke  the real call.
  //
  // Nothing is sent to any phone: the code below is deliberately malformed, so even a fully
  // working sender refuses it at the shape check before it reaches FCM.
  var probes = [
    { name: 'GET  /exec       ', url: base, method: 'get' },
    { name: 'POST /exec       ', url: base, method: 'post' },
    { name: 'POST /exec/revoke', url: base + '/revoke', method: 'post' }
  ];

  Logger.log('as: %s', Session.getActiveUser().getEmail() || '(cannot identify this account)');

  var codes = [];
  for (var i = 0; i < probes.length; i++) {
    var probe = probes[i];
    var options = {
      method: probe.method,
      headers: { Authorization: 'Bearer ' + token },
      muteHttpExceptions: true,
      followRedirects: true
    };
    if (probe.method === 'post') {
      options.contentType = 'application/json';
      options.payload = JSON.stringify({ action: 'revoke', code: 'not-a-code' });
    }

    var status;
    var body;
    try {
      var response = UrlFetchApp.fetch(probe.url, options);
      status = response.getResponseCode();
      body = response.getContentText();
    } catch (err) {
      status = 'threw';
      body = String(err && err.message ? err.message : err);
    }
    codes.push(status);
    Logger.log('%s -> HTTP %s  %s  %s',
      probe.name, status, body.charAt(0) === '<' ? 'HTML' : 'JSON', body.slice(0, 120));
  }

  // The verdict, so the numbers do not have to be interpreted by hand.
  if (codes[0] === 401) {
    Logger.log('VERDICT: the token is not accepted by this web app at all -- the path is not the ' +
               'problem. Bearer-token invocation is the thing to change, not /exec/revoke.');
  } else if (codes[1] !== 401 && codes[2] === 401) {
    Logger.log('VERDICT: the token works, but the extra /revoke path is rejected. Route on a query ' +
               'parameter instead and keep posting to the bare /exec.');
  } else if (codes[2] === 200) {
    Logger.log('VERDICT: the link works. A JSON refusal naming the code is the expected answer.');
  } else {
    Logger.log('VERDICT: not one of the known shapes. Paste the three lines above.');
  }
}

/** Everything currently stored, newest first, with its state. */
function listCodes() {
  requireEditor_();
  var all = firebase_('get', '/codes.json') || {};
  var audit = firebase_('get', '/audit.json') || {};
  var rows = [];
  for (var code in all) {
    if (!all.hasOwnProperty(code)) continue;
    var node = all[code] || {};
    var timeline = auditTimeline_(audit[code], node);
    rows.push({ code: code, formatted: code.substring(0, 4) + '-' + code.substring(4), issued: node.issued || 0, claimed: !!node.usedBy, claimedAt: node.activatedAt || 0, revoked: node.revoked === true, note: node.note || '', audit: timeline, lastChange: timeline.length ? timeline[timeline.length - 1] : null });
  }
  rows.sort(function (a, b) { return b.issued - a.issued; });
  return rows;
}

/**
 * Cuts a device off. The code is not deleted: deleting it would let the next person to type it
 * claim it afresh, because the rules only refuse a code that already carries usedBy.
 */
function revokeCode(code) {
  var by = requireEditor_();
  if (!isValidCode(code)) throw new Error('Not a valid code: ' + code);
  firebase_('patch', '/codes/' + code + '.json', { revoked: true });

  // The database is the truth; the push is only how the phone hears about it sooner. So the audit
  // entry is written either way, and records which of the two happened.
  var push = pushRevokeToSender_(code, by);
  audit_(code, 'revoked', by, { pushed: !!push.ok, pushError: push.ok ? null : push.error });

  return listCodes();
}

/**
 * Asks the sender to broadcast a revocation.
 *
 * KNOWN BROKEN, AND BEING REPLACED. Every request returns HTTP 401 from Google's auth frontend
 * before the sender's script runs: ScriptApp.getOAuthToken() mints a token carrying THIS project's
 * scopes, and invoking a web app needs one authorized for the SENDER's project, which does not
 * exist across two separate projects. No manifest scope fixes it. Do not spend time on this again
 * -- the evidence and the replacement are in
 * docs/superpowers/specs/2026-09-09-revoke-queue-design.md.
 *
 * Left in place until that lands because it fails safely: the revocation is already written to
 * /codes and /audit before this runs, so the only thing lost is the push, and phones still act on
 * the revocation at their next daily check.
 *
 * This console has no FCM credentials and is not given any: issuing codes and broadcasting to four
 * hundred phones are separate jobs held in separate projects (SYSTEM.md 2.3). The sender exposes
 * one narrow endpoint that sends a fixed envelope, and this calls it.
 *
 * Never throws. By the time this runs the revocation is already recorded in /codes and /audit, and
 * the phone's periodic verification finds it within a day regardless -- so a failure here is a
 * delay, not a lost revocation, and must not present to the staff member as a failed Revoke.
 *
 * ScriptApp.getOAuthToken() forwards the signed-in staff member's own identity, so the sender's
 * requireEditor_ sees the person who clicked rather than this script. That needs userinfo.email in
 * this project's oauthScopes; without it the sender answers "cannot identify you".
 */
function pushRevokeToSender_(code, by) {
  var url = PropertiesService.getScriptProperties().getProperty('SENDER_URL');
  if (!url) return { ok: false, error: 'No SENDER_URL is set, so the revoke was not pushed.' };

  // Routed by path: the sender reads e.pathInfo. `action` rides along in the body too, because a
  // redirected request can arrive with its path stripped.
  var endpoint = url.replace(/\/+$/, '') + '/revoke';

  try {
    var response = UrlFetchApp.fetch(endpoint, {
      method: 'post',
      contentType: 'application/json',
      headers: { Authorization: 'Bearer ' + ScriptApp.getOAuthToken() },
      payload: JSON.stringify({ action: 'revoke', code: code, by: by }),
      muteHttpExceptions: true,
      followRedirects: true
    });

    var text = response.getContentText();

    // The failure that actually happens is an HTML page where JSON was expected: Google's sign-in
    // page when the token is not accepted, or Apps Script's error page when the deployment has not
    // been given a New version and doPost does not yet exist in the version /exec serves.
    // "Unexpected token '<'" tells a staff member nothing, so name the two causes instead.
    if (text.charAt(0) === '<') {
      return {
        ok: false,
        error: 'The sender replied with a web page instead of JSON (HTTP ' + response.getResponseCode() +
               '). Either the sender has not been redeployed as a New version, so /exec/revoke does ' +
               'not exist yet, or this console is not permitted to call it.'
      };
    }

    var parsed = JSON.parse(text);
    return parsed && typeof parsed.ok !== 'undefined'
      ? parsed
      : { ok: false, error: 'Unexpected reply from the sender: ' + text.slice(0, 200) };
  } catch (err) {
    return { ok: false, error: String(err && err.message ? err.message : err) };
  }
}

function unrevokeCode(code) {
  var by = requireEditor_();
  if (!isValidCode(code)) throw new Error('Not a valid code: ' + code);
  firebase_('patch', '/codes/' + code + '.json', { revoked: null });
  audit_(code, 'restored', by);
  return listCodes();
}

// ---------------------------------------------------------------- Saved messages

/**
 * Prebuilt messages the sender offers as one-tap choices.
 *
 * They live here rather than in the sender so that composing a notice and deciding what the
 * standard wordings are stay separate jobs. Whoever is on the counter picks; whoever maintains the
 * wording edits it once, here, and every sender sees the change.
 *
 * Stored at /templates. No security rule grants any client access to that path, so it is invisible
 * to phones; only these consoles, writing as the service account, can read or change it.
 */
function listTemplates() {
  requireEditor_();
  var all = firebase_('get', '/templates.json') || {};
  var rows = [];
  for (var id in all) {
    if (!all.hasOwnProperty(id)) continue;
    rows.push({ id: id, label: all[id].label || '', title: all[id].title || '', body: all[id].body || '', updated: all[id].updated || 0 });
  }
  rows.sort(function (a, b) { return a.label.localeCompare(b.label); });
  return rows;
}

/** Creates or updates one. Pass an empty id to create. */
function saveTemplate(id, label, title, body) {
  requireEditor_();
  label = (label || '').toString().trim().slice(0, 60);
  title = (title || '').toString().trim().slice(0, 120);
  body = (body || '').toString().trim().slice(0, 900);

  if (!label) throw new Error('A short name is required, so the sender has something to put on the button.');
  if (!title) throw new Error('A title is required.');

  // Keys are generated rather than derived from the label, so renaming a saved message edits it
  // instead of quietly creating a second one alongside the original.
  id = (id || '').toString().trim() || ('t' + Date.now());

  firebase_('put', '/templates/' + encodeURIComponent(id) + '.json', { label: label, title: title, body: body, updated: Date.now() });
  return listTemplates();
}

function deleteTemplate(id) {
  requireEditor_();
  if (!id) throw new Error('No saved message selected.');
  firebase_('delete', '/templates/' + encodeURIComponent(id) + '.json');
  return listTemplates();
}

// ---------------------------------------------------------------- Dispensary banner

/**
 * Tags and style properties the banner may use. Everything else is discarded.
 *
 * Sizes and fonts are deliberately absent: the banner is authored in Gmail or Word at fixed point
 * sizes, and honouring those would override the reader's own font setting. Emphasis travels as
 * weight, underline and colour; size stays with the user.
 *
 * The page sanitises on paste using the browser's parser, which is stricter and better informed
 * than anything achievable here. This pass exists because a browser can be bypassed -- the phone
 * must never depend on the page having behaved.
 */
var BANNER_TAGS = ['b', 'strong', 'i', 'em', 'u', 'br', 'p', 'span', 'a', 'div', 'font'];
var BANNER_STYLES = ['color', 'background-color', 'background'];

function isBannerColour_(value) {
  if (/^#[0-9a-fA-F]{3,8}$/.test(value)) return true;
  if (/^rgba?\(\s*[0-9.,\s%]+\)$/i.test(value)) return true;
  return /^[a-zA-Z]{3,20}$/.test(value);
}

function bannerStyle_(attrs) {
  var match = /style\s*=\s*("([^"]*)"|'([^']*)')/i.exec(attrs);
  if (!match) return '';
  var kept = [];
  (match[2] || match[3] || '').split(';').forEach(function (declaration) {
    var at = declaration.indexOf(':');
    if (at < 0) return;
    var property = declaration.slice(0, at).trim().toLowerCase();
    var value = declaration.slice(at + 1).trim();
    if (BANNER_STYLES.indexOf(property) < 0 || !value) return;
    if (!isBannerColour_(value)) return;
    kept.push((property === 'background' ? 'background-color' : property) + ':' + value);
  });
  return kept.join(';');
}

/** Mirrors HtmlBanner.kt in the Android app. Change one and change the other. */
function sanitiseBanner_(html) {
  var out = String(html || '');
  out = out.replace(/<\s*(script|style)\b[^>]*>[\s\S]*?<\s*\/\s*\1\s*>/gi, '');
  out = out.replace(/<!--[\s\S]*?-->/g, '');

  out = out.replace(/<\s*(\/?)\s*([a-zA-Z0-9]+)([^>]*)>/g, function (whole, closing, rawTag, attrs) {
    var tag = rawTag.toLowerCase();
    if (BANNER_TAGS.indexOf(tag) < 0) return '';
    // A closing </font> becomes </span>, matching the opening tag rewritten below.
    if (closing === '/') return tag === 'font' ? '</span>' : '</' + tag + '>';
    if (tag === 'br') return '<br>';

    // contenteditable still emits <font color> for a colour change. It is rewritten to a span so
    // the phone only ever has to understand one shape.
    if (tag === 'font') {
      var colour = /color\s*=\s*("([^"]*)"|'([^']*)')/i.exec(attrs);
      var value = colour ? String(colour[2] || colour[3]).trim() : '';
      return (value && isBannerColour_(value)) ? '<span style="color:' + value + '">' : '<span>';
    }

    if (tag === 'a') {
      var href = /href\s*=\s*("([^"]*)"|'([^']*)')/i.exec(attrs);
      var url = href ? String(href[2] || href[3]).trim() : '';
      // An unsafe link loses its tag but keeps its words: losing the sentence is worse.
      if (!url || !/^(https?:\/\/|mailto:|tel:)/i.test(url)) return '';
      return '<a href="' + url.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;') + '">';
    }

    var style = bannerStyle_(attrs);
    return style ? '<' + tag + ' style="' + style + '">' : '<' + tag + '>';
  });

  return out.trim();
}

/** The banner as stored, plus the plain lines it falls back to when no banner is set. */
function getBanner() {
  requireEditor_();
  var info = firebase_('get', '/info.json') || {};
  return { html: info.html || '', heading: info.heading || '', lines: info.lines || '' };
}

/**
 * Stores the banner. Sanitised here as well as in the page, and again on the phone.
 *
 * Written with PATCH so heading and lines survive: they are the fallback shown by any app version
 * that predates the banner, and replacing /info wholesale would delete them.
 */
function saveBanner(html) {
  requireEditor_();
  var clean = sanitiseBanner_(html);
  if (clean.replace(/<[^>]*>/g, '').replace(/&nbsp;/g, ' ').trim() === '') {
    throw new Error('The banner is empty. Use Remove if that is what you meant.');
  }
  if (clean.length > 4000) throw new Error('That banner is too long (' + clean.length + ' characters of markup). Keep it under 4000.');
  firebase_('patch', '/info.json', { html: clean, htmlUpdated: Date.now() });
  return getBanner();
}

/** Removes the banner, so the app falls back to the plain heading and lines. */
function removeBanner() {
  requireEditor_();
  firebase_('patch', '/info.json', { html: null, htmlUpdated: null });
  return getBanner();
}

// ---------------------------------------------------------------- Daily status wording

/**
 * The two fixed messages the QR codes send, as currently stored.
 *
 * Returns the shipped defaults for anything not yet edited, so the form is never blank and an
 * editor can see what is actually going out before changing it.
 */
function listStatusMessages() {
  requireEditor_();
  var stored = firebase_('get', '/status.json') || {};
  var defaults = { open: { title: 'The dispensary is open today', body: 'Normal OPD timings.' }, closed: { title: 'The dispensary is closed now', body: 'It will reopen at the usual time.' } };
  var rows = [];
  ['open', 'closed'].forEach(function (which) { var node = stored[which] || {}; rows.push({ which: which, title: node.title || defaults[which].title, body: node.body === undefined ? defaults[which].body : node.body, customised: !!node.title }); });
  return rows;
}

/** Changes the wording of one status message. Takes effect on the next scan; no redeploy needed. */
function saveStatusMessage(which, title, body) {
  requireEditor_();
  which = String(which || '').trim().toLowerCase();
  if (which !== 'open' && which !== 'closed') throw new Error('Unknown status: ' + which);

  title = String(title || '').trim().slice(0, 120);
  body = String(body || '').trim().slice(0, 900);
  if (!title) throw new Error('A title is required. It is what people read first.');

  firebase_('put', '/status/' + which + '.json', { title: title, body: body, updated: Date.now() });
  return listStatusMessages();
}

/** Drops back to the wording shipped with the sender. */
function resetStatusMessage(which) {
  requireEditor_();
  which = String(which || '').trim().toLowerCase();
  if (which !== 'open' && which !== 'closed') throw new Error('Unknown status: ' + which);
  firebase_('delete', '/status/' + which + '.json');
  return listStatusMessages();
}

// ---------------------------------------------------------------- Web app

function doGet() {
  try {
    requireEditor_();
  } catch (err) {
    return refusalPage_(err.message);
  }
  return HtmlService.createTemplateFromFile('Index').evaluate().setTitle('Paramanu Seniors Notices - Admin').addMetaTag('viewport', 'width=device-width, initial-scale=1');
}

/** Run this from the editor once after setup to prove the credentials work. */
function testConnection() {
  requireEditor_();
  var rows = listCodes();
  Logger.log('version=%s codes=%s', SCRIPT_VERSION, rows.length);
  return rows.length;
}
