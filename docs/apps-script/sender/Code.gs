/**
 * Paramanu Seniors Notices - Sender
 *
 * Sends a notice to every activated phone. Separate Apps Script project from the code console, so
 * the people who send notices are not the people who issue and revoke codes.
 *
 * This is an adaptation of the Notifier sender, not a rewrite. Three things differ:
 *   - it points at paramanu-seniors, not utils-5cb5b;
 *   - the PDF field is `pdfUrl`, not `imageUrl` (this app ignores `imageUrl` entirely);
 *   - `level` and `color` are gone; the severity system was a server-monitoring idea.
 *
 * Text only for now: title and body. Attachments come later.
 *
 * SETUP (once)
 *  1. New Apps Script project. Paste this as Code.gs and Index.html as "Index".
 *  2. Script Properties:
 *       SERVICE_ACCOUNT_JSON = <the whole service account key JSON>
 *       DATABASE_URL         = https://paramanu-seniors-default-rtdb.asia-southeast1.firebasedatabase.app
 *       ALLOWED_EDITORS      = <comma-separated Google account emails permitted to use this>
 *       PROJECT_ID           = paramanu-seniors
 *       STAFF_PIN            = <at least six digits; required for EVERY send, not just the QR>
 *       REQUIRE_STAFF_PIN    = 'false' to drop the PIN and rely on the Google allowlist alone
 *                              (optional; anything else, or absent, means the PIN is required)
 *  3. Deploy -> Web app.
 *       Execute as:     User accessing the web app   <- REQUIRED, see requireEditor_
 *       Who has access: Anyone with a Google account
 *
 * No OAuth2 library is needed; the JWT is signed inline, so there is nothing to add under
 * Libraries and nothing to break when that library changes.
 */

var SCRIPT_VERSION = '2026-09-01-sender';

/** Must match Subscription.NOTICES.topic in the Android app. */
var TOPIC = 'notices-v1';

/** Must match Subscription.STATUS.topic. Separate topic, so unsubscribing genuinely stops these. */
var STATUS_TOPIC = 'status-v1';

/**
 * Set by the test suite to divert sends to a scratch topic. Null in normal operation.
 *
 * It exists so the end-to-end test can exercise the real FCM call -- real credentials, real payload,
 * real HTTP response -- without the message reaching anybody's phone. Only the topic differs, which
 * is the one part of the request that carries no risk of being wrong in an interesting way.
 */
var TOPIC_OVERRIDE = null;

/** A repeat of the same status inside this window is treated as a mis-scan and refused. */
var DUPLICATE_WINDOW_MS = 10 * 60 * 1000;

/**
 * Guessing budget before the PIN is refused for everyone for LOCKOUT_SECONDS.
 *
 * Deliberately small. Staff mistype occasionally and wait a few minutes; an attacker gets nowhere.
 * Note this is best effort: CacheService entries can be evicted early, so the real defence is a
 * PIN long enough to be worth guarding -- use at least six digits, not four.
 */
/**
 * Whether a PIN is demanded on top of the Google account check.
 *
 * A Script Property rather than a constant here, so it can be switched without a redeploy -- and a
 * redeploy is the step most easily forgotten, since /exec keeps serving the old version silently.
 *
 * Defaults to ON. Turning it off is defensible now that every send also requires an allowlisted
 * Google account; it is least defensible for the QR path, where the counter phone is permanently
 * signed in and sitting where anyone can pick it up.
 */
function requiresPin() {
  var value = PropertiesService.getScriptProperties().getProperty('REQUIRE_STAFF_PIN');
  return String(value === null || value === undefined ? 'true' : value).trim().toLowerCase() !== 'false';
}

var MAX_PIN_FAILURES = 5;
var LOCKOUT_SECONDS = 900;

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
  if (!value) throw new Error('Script property ' + name + ' is not set. See the notes at the top of Code.gs.');
  return value;
}

function databaseUrl_() { return property_('DATABASE_URL').replace(/\/+$/, ''); }

function accessToken_() {
  var cache = CacheService.getScriptCache();
  var cached = cache.get('fb_token');
  if (cached) return cached;

  var key = JSON.parse(property_('SERVICE_ACCOUNT_JSON'));
  var now = Math.floor(Date.now() / 1000);

  var header = Utilities.base64EncodeWebSafe(JSON.stringify({ alg: 'RS256', typ: 'JWT' })).replace(/=+$/, '');
  var claim = Utilities.base64EncodeWebSafe(JSON.stringify({ iss: key.client_email, scope: [ 'https://www.googleapis.com/auth/firebase.messaging', 'https://www.googleapis.com/auth/firebase.database', 'https://www.googleapis.com/auth/userinfo.email' ].join(' '), aud: 'https://oauth2.googleapis.com/token', exp: now + 3600, iat: now })).replace(/=+$/, '');

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

// ---------------------------------------------------------------- Templates

/** The saved messages, for the picker. Managed in the code console, not here. */
function listTemplates() {
  requireEditor_();
  var all = firebase_('get', '/templates.json') || {};
  var rows = [];
  for (var id in all) {
    if (!all.hasOwnProperty(id)) continue;
    rows.push({ id: id, label: all[id].label || all[id].title || '(untitled)', title: all[id].title || '', body: all[id].body || '' });
  }
  rows.sort(function (a, b) { return a.label.localeCompare(b.label); });
  return rows;
}

// ---------------------------------------------------------------- Sending

/**
 * Allocates a unique, ordered message id.
 *
 * Epoch millis rather than a UUID, because the app parses this into the notice's timestamp: a phone
 * that was switched off overnight then orders by when the NGO published, not by when it happened to
 * receive. The loop is the fix for the collision bug in the original Notifier sender, where two
 * sends inside the same millisecond meant the second notice was silently dropped by Room's unique
 * index and never appeared on anyone's phone.
 */
function nextLogId_() {
  var candidate = Date.now();
  var guard = 0;
  while (firebase_('get', '/sent/' + candidate + '.json?shallow=true') !== null) {
    candidate += 1;
    if (++guard > 1000) throw new Error('Could not allocate a message id.');
  }
  return String(candidate);
}

/**
 * Sends one notice to the topic, and records it.
 *
 * Recorded before the send: a notice that went out but was never logged is worse than one logged
 * and not sent, because the second is visible and the first is not.
 */
/**
 * Checks the staff PIN, with lockout.
 *
 * The PIN is the only thing standing between this web app and four hundred phones. It has to be
 * deployed as "Anyone with the link" for a QR scan to work without a Google sign-in, which means
 * anyone who learns the URL can reach these functions -- so an unlimited guessing budget against a
 * short numeric PIN would not be a lock at all.
 *
 * The PIN never leaves the server. It lives in Script Properties, the pages never receive it, and
 * nothing is written to browser storage: staff type it for each send. That is deliberate friction.
 * A PIN cached in sessionStorage on a phone left on a counter is a PIN anybody can use.
 *
 * Note the residual exposure this cannot fix: Script Properties are readable by anyone with edit
 * access to this Apps Script project. When access is narrowed after alpha, that list is the real
 * list of people who can send.
 */
function checkPin_(pin) {
  if (!requiresPin()) return;

  var cache = CacheService.getScriptCache();
  var failures = Number(cache.get('pin_failures') || 0);

  if (failures >= MAX_PIN_FAILURES) {
    throw new Error('Too many incorrect PINs. Wait a few minutes and try again.');
  }

  var expected = String(property_('STAFF_PIN')).trim();
  if (String(pin || '').trim() !== expected) {
    cache.put('pin_failures', String(failures + 1), LOCKOUT_SECONDS);
    throw new Error('That PIN is not correct.');
  }

  cache.remove('pin_failures');
}

function sendNotice(title, body, category, pin) {
  var by = requireEditor_();
  // Every path that reaches FCM checks the PIN, not just the QR one. The compose page is the more
  // dangerous of the two: it sends arbitrary text rather than one of two fixed messages.
  checkPin_(pin);

  title = (title || '').toString().trim();
  body = (body || '').toString().trim();

  if (!title) throw new Error('A title is required. It is what people read first, and often all they read.');
  if (title.length > 120) throw new Error('Title is too long (' + title.length + '); keep it under 120 characters.');
  if (body.length > 900) throw new Error('Body is too long (' + body.length + '); keep it under 900 characters.');

  category = (category === 'STATUS') ? 'STATUS' : 'NOTICES';

  var logId = nextLogId_();
  var sentAt = new Date().toISOString();

  firebase_('put', '/sent/' + logId + '.json', { title: title, body: body, category: category, sentAt: sentAt, sentBy: by, scriptVersion: SCRIPT_VERSION });

  // Data-only. A `notification` block here would make the FCM SDK draw the tray notification
  // itself while the app is backgrounded: onMessageReceived would never run, the entitlement check
  // would be skipped, and nothing would be written to the phone's history.
  // The app maps this onto a Subscription: it chooses the notification channel, and it is
  // checked again on the phone so a user who has just switched the daily status off does not
  // receive one more while FCM catches up with the unsubscribe.
  var message = { message: { topic: TOPIC_OVERRIDE || (category === 'STATUS' ? STATUS_TOPIC : TOPIC), android: { priority: 'high' }, data: { logId: logId, title: title, body: body, category: category } } };

  var response = UrlFetchApp.fetch( 'https://fcm.googleapis.com/v1/projects/' + property_('PROJECT_ID') + '/messages:send', { method: 'post', contentType: 'application/json', headers: { Authorization: 'Bearer ' + accessToken_() }, payload: JSON.stringify(message), muteHttpExceptions: true } );

  if (response.getResponseCode() >= 300) {
    firebase_('patch', '/sent/' + logId + '.json', { error: response.getContentText() });
    throw new Error('FCM refused the message: ' + response.getContentText());
  }

  firebase_('patch', '/sent/' + logId + '.json', { fcmName: JSON.parse(response.getContentText()).name || '' });
  return { logId: logId, sentAt: sentAt, title: title, category: category };
}

/** The last [limit] notices sent, newest first. */
function listSent(limit) {
  requireEditor_();
  limit = Math.max(1, Math.min(100, parseInt(limit, 10) || 25));
  // orderBy must be a JSON string, so the quotes are part of the value and have to be
  // percent-encoded: UrlFetchApp rejects a raw " in a URL outright with "Invalid argument", which
  // reads like a credentials problem rather than a malformed query.
  var query = '?orderBy=' + encodeURIComponent('"$key"') + '&limitToLast=' + limit;
  var all = firebase_('get', '/sent.json' + query) || {};
  var rows = [];
  for (var logId in all) {
    if (!all.hasOwnProperty(logId)) continue;
    rows.push({ logId: logId, title: all[logId].title || '', body: all[logId].body || '', category: all[logId].category || 'NOTICES', sentAt: all[logId].sentAt || '', sentBy: all[logId].sentBy || '', failed: !!all[logId].error });
  }
  rows.sort(function (a, b) { return Number(b.logId) - Number(a.logId); });
  return rows;
}

// ---------------------------------------------------------------- Daily status (QR codes)

/**
 * The two QR codes at the counter encode:
 *
 *   <web app url>?status=open
 *   <web app url>?status=closed
 *
 * Scanning one opens a confirmation page. It does NOT send. Sending happens only when a member of
 * staff types the PIN and presses the button, and that is a google.script.run call, never a GET.
 *
 * This matters more than it looks. A URL that sends on being fetched is fired by anything that
 * follows links to build a preview -- WhatsApp, Gmail, Slack, a browser preloading the omnibox
 * suggestion -- so the notice would go to four hundred people with no human involved. And a QR on
 * a wall is public: anyone in the queue can photograph it and broadcast from home, forever, with
 * no way to revoke the picture. The PIN is what makes a photographed QR worthless.
 */
/** The wording shipped with the script. Used when the database has nothing for this status. */
function defaultStatusMessage_(which) {
  var fallback = defaultStatusMessage_(which);
  if (!fallback) return null;

  try {
    var stored = firebase_('get', '/status/' + which + '.json');
    if (stored && stored.title) {
      return { title: String(stored.title), body: String(stored.body || '') };
    }
  } catch (e) {
    Logger.log('Status wording lookup failed, using the shipped default: %s', e.message);
  }
  return fallback;
}

/**
 * The wording for a status message, as edited in the console.
 *
 * Stored at /status/{open,closed}. The lookup is wrapped because this runs on the QR path: a member
 * of staff standing at a counter with a queue must not be blocked by a database hiccup, so an
 * unreachable read falls back to the shipped wording rather than failing the send.
 */
function statusMessage_(which) {
  // Defensive normalisation. The value has already crossed a template and a browser by the time it
  // gets here, and a stray pair of quote characters once made this return null for a perfectly good
  // 'closed' -- an error that read like a bug in the QR rather than in the plumbing.
  which = String(which || '').trim().replace(/^["']|["']$/g, '').toLowerCase();

  if (which === 'open') {
    return { title: 'The dispensary is open today', body: 'Normal OPD timings.' };
  }
  if (which === 'closed') {
    return { title: 'The dispensary is closed now', body: 'It will reopen at the usual time.' };
  }
  return null;
}

/**
 * Sends one of the two status messages, after checking the PIN.
 *
 * Goes to the `status-v1` topic and is tagged `category: STATUS`, so it reaches only the phones
 * that asked for the daily status and lands on the quiet channel. Notices are untouched.
 */
function sendStatus(which, pin) {
  // Attribution is not needed here: the actual write happens in sendNotice below, which records
  // the caller itself. This call stays because it refuses a non-editor before the duplicate scan.
  requireEditor_();
  checkPin_(pin);

  var message = statusMessage_(which);
  if (!message) {
    throw new Error('Unknown status "' + which + '". Expected open or closed.');
  }

  // A second scan a minute later is far more likely to be a mis-scan than a real change, and a
  // duplicate to four hundred phones is not recoverable.
  var recent = listSent(5);
  for (var i = 0; i < recent.length; i++) {
    if (recent[i].title === message.title && Date.now() - Number(recent[i].logId) < DUPLICATE_WINDOW_MS) {
      throw new Error('That was already sent a few minutes ago. Nothing has been sent again.');
    }
  }

  return sendNotice(message.title, message.body, 'STATUS', pin);
}

// ---------------------------------------------------------------- Web app

function doGet(e) {
  try {
    requireEditor_();
  } catch (err) {
    return refusalPage_(err.message);
  }
  var status = e && e.parameter ? e.parameter.status : null;

  if (status === 'open' || status === 'closed') {
    var template = HtmlService.createTemplateFromFile('Status');
    template.status = status;
    template.message = statusMessage_(status);
    return template.evaluate().setTitle('Paramanu Seniors Notices - ' + status).addMetaTag('viewport', 'width=device-width, initial-scale=1');
  }

  return HtmlService.createTemplateFromFile('Index').evaluate().setTitle('Paramanu Seniors Notices - Send').addMetaTag('viewport', 'width=device-width, initial-scale=1');
}

/** Run from the editor after setup to prove the credentials work without sending anything. */
function testConnection() {
  requireEditor_();
  Logger.log('version=%s templates=%s sent=%s', SCRIPT_VERSION, listTemplates().length, listSent(5).length);
}
