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
 * Title and body, plus an attachment when the body links to a PDF or a picture: see
 * messageAttachment_. Nothing is uploaded; the phones fetch the file from where the link points.
 *
 * SETUP (once)
 *  1. New Apps Script project. Paste this as Code.gs and Index.html as "Index".
 *  2. Script Properties:
 *       SERVICE_ACCOUNT_JSON = <the whole service account key JSON>
 *       DATABASE_URL         = https://paramanu-seniors-default-rtdb.asia-southeast1.firebasedatabase.app
 *       ALLOWED_EDITORS      = <comma-separated Google account emails permitted to use this>
 *       PROJECT_ID           = paramanu-seniors
 *       DISPENSARY_ID        = barc-vashi   (whose topics notices go to; see dispensaryTopic_)
 *       STAFF_PIN            = <at least six digits; required for every send>
 *       REQUIRE_STAFF_PIN    = 'false' to drop the PIN and rely on the Google allowlist alone
 *                              (optional; anything else, or absent, means the PIN is required)
 *       ADMIN_URL            = the admin console's /exec URL, linked from the top of the page
 *                              (optional; absent, or not https, means no link)
 *  3. Deploy -> Web app.
 *       Execute as:     User accessing the web app   <- REQUIRED, see requireEditor_
 *       Who has access: Anyone with a Google account
 *
 * No OAuth2 library is needed; the JWT is signed inline, so there is nothing to add under
 * Libraries and nothing to break when that library changes.
 */

var SCRIPT_VERSION = '2026-09-13-sender';

/**
 * Must match ActivationRepository.CONTROL_TOPIC. Revoke, resume and banner changes.
 *
 * Every activated phone holds it, revoked included, and nobody can switch it off -- which is the
 * point: a revoked phone is exactly the one that needs to hear a resume, and a user who has turned
 * notices off still has to receive a revoke.
 */
var CONTROL_TOPIC = 'control-v1';

/**
 * The most a banner may weigh, in UTF-8 bytes, to travel inside a control message.
 *
 * FCM refuses a data payload over 4096 bytes, keys included. The banner's html is by far the largest
 * value, and this leaves room for the rest. Bytes rather than characters: a banner in Marathi or
 * Hindi costs three bytes a character, so a character count would pass a banner FCM then rejects.
 * Must match BANNER_MAX_BYTES in the console.
 */
var BANNER_MAX_BYTES = 3500;

/**
 * Set by the test suite to divert sends to a scratch topic. Null in normal operation.
 *
 * It exists so the end-to-end test can exercise the real FCM call -- real credentials, real payload,
 * real HTTP response -- without the message reaching anybody's phone. Only the topic differs, which
 * is the one part of the request that carries no risk of being wrong in an interesting way.
 */
var TOPIC_OVERRIDE = null;

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
 * Google account; it is least defensible on a shared computer that stays signed in.
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
  rows.sort(function (a, b) { return labelOrder_(a.label, b.label); });
  return rows;
}

/**
 * Orders labels the way people number them: "2. Disp Closed" before "10. Landline not working".
 * Pure.
 *
 * Staff number the saved messages to fix their order on the page, and a plain text comparison put
 * 10 to 13 between 1 and 2. Runs of digits compare by value and everything else case-insensitively.
 * Written out rather than left to localeCompare's numeric option, which depends on the ICU data the
 * runtime happens to carry.
 */
function labelOrder_(a, b) {
  var x = String(a || '').toLowerCase().match(/\d+|\D+/g) || [];
  var y = String(b || '').toLowerCase().match(/\d+|\D+/g) || [];
  for (var i = 0; i < Math.min(x.length, y.length); i++) {
    var xNum = /^\d/.test(x[i]), yNum = /^\d/.test(y[i]);
    if (xNum && yNum) {
      var diff = Number(x[i]) - Number(y[i]);
      if (diff) return diff;
    } else if (x[i] !== y[i]) {
      return x[i] < y[i] ? -1 : 1;
    }
  }
  return (x.length - y.length) || String(a || '').localeCompare(String(b || ''));
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
 * A second factor, not the only one. This was written when the web app had to be deployed as
 * "Anyone with the link" so a QR scan worked without a Google sign-in, which meant anyone who
 * learned the URL could reach these functions. That is no longer true: every entry point calls
 * requireEditor_() first and an address outside ALLOWED_EDITORS is refused before any payload is
 * read. The PIN stays on the compose path, which sends arbitrary text to four hundred phones, and
 * can be dropped entirely with REQUIRE_STAFF_PIN.
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

// ---------------------------------------------------------------- Dispensary

/** The dispensary this sender sends for. Its record at /dispensaries/{id} lists what it offers. */
function dispensaryId_() {
  return property_('DISPENSARY_ID');
}

/**
 * The topic a notice goes to: the dispensary's first, by `order`. Pure, so it can be tested.
 *
 * There is no picker on the compose page. A dispensary with one topic -- every dispensary today --
 * needs none, and one that offers several will get a page designed for it rather than a picker
 * bolted onto this one. An entry without a topic name is skipped; ties on order go by key, so the
 * answer never depends on the order the database happens to return.
 */
function noticeTopic_(topics) {
  var best = null;
  for (var key in topics) {
    if (!topics.hasOwnProperty(key)) continue;
    var entry = topics[key] || {};
    if (!entry.topic) continue;
    var order = typeof entry.order === 'number' ? entry.order : Infinity;
    if (!best || order < best.order || (order === best.order && key < best.key)) {
      best = { key: key, order: order, topic: String(entry.topic) };
    }
  }
  return best ? best.topic : null;
}

/** Reads the dispensary's topics and returns the one notices go to. Throws if it offers none. */
function dispensaryTopic_() {
  var id = dispensaryId_();
  var topic = noticeTopic_(firebase_('get', '/dispensaries/' + encodeURIComponent(id) + '/topics.json') || {});
  if (!topic) {
    throw new Error('Dispensary ' + id + ' offers no topics, so there is nowhere to send. Check /dispensaries/' + id + '/topics.');
  }
  return topic;
}

// ---------------------------------------------------------------- Attachments from the message

/**
 * How many links in a message are asked about. Each is one HEAD request against the account-wide
 * UrlFetch quota, and a message that lists more links than this is a reading list, not a notice
 * with an attachment.
 */
var ATTACHMENT_LINKS_CHECKED = 3;

/** The image types the app can decode. SVG and HEIC are left as plain links. */
var ATTACHMENT_IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/webp', 'image/gif'];

/**
 * Every link in [text], in order, with no repeats. Pure.
 *
 * Word by word through pollerFirstLink_, so where a link ends is decided by exactly the rule the
 * poller and the app use -- a URL never contains whitespace, so no link is split by doing it this way.
 */
function messageLinks_(text) {
  var links = [];
  String(text || '').split(/\s+/).forEach(function (word) {
    var found = pollerFirstLink_(word);
    if (!found) return;
    var url = pollerNormaliseUrl_(found);
    if (links.indexOf(url) < 0) links.push(url);
  });
  return links;
}

/**
 * 'pdf', 'image' or '' for a link, from the type its server declared. Pure.
 *
 * The declared type wins when there is one: a page that merely ends in .pdf but serves text/html is
 * a viewer, not a document. Only a missing or generic download type falls back to the extension,
 * which is how a file host that labels everything application/octet-stream still works.
 */
function attachmentKind_(contentType, url) {
  var type = String(contentType || '').split(';')[0].trim().toLowerCase();
  if (type === 'application/pdf') return 'pdf';
  if (ATTACHMENT_IMAGE_TYPES.indexOf(type) >= 0) return 'image';
  if (type && type !== 'application/octet-stream' && type !== 'binary/octet-stream') return '';

  var path = String(url || '').split(/[?#]/)[0].toLowerCase();
  if (/\.pdf$/.test(path)) return 'pdf';
  if (/\.(jpe?g|png|webp|gif)$/.test(path)) return 'image';
  return '';
}

/**
 * Asks [url]'s server what it is, without downloading it. Returns { kind, bytes }.
 *
 * A server that refuses HEAD, or cannot be reached from Apps Script, is judged by the extension
 * alone. One that says the file is gone is not attached at all: a dead link sent as an attachment
 * would sit on every phone as a download glyph that never succeeds.
 */
function probeAttachment_(url) {
  var response;
  try {
    response = UrlFetchApp.fetch(url, { method: 'head', muteHttpExceptions: true, followRedirects: true });
  } catch (e) {
    return { kind: attachmentKind_('', url), bytes: null };
  }

  var code = response.getResponseCode();
  if (code === 404 || code === 410) return { kind: '', bytes: null };
  if (code >= 300) return { kind: attachmentKind_('', url), bytes: null };

  // Header names keep whatever case the server sent.
  var headers = response.getHeaders();
  var type = '', length = null;
  for (var name in headers) {
    if (!headers.hasOwnProperty(name)) continue;
    var lower = name.toLowerCase();
    if (lower === 'content-type') type = String(headers[name]);
    if (lower === 'content-length') length = parseInt(headers[name], 10);
  }
  return { kind: attachmentKind_(type, url), bytes: (length > 0) ? length : null };
}

/**
 * The payload fields for the first PDF or picture linked in [body], or {} when there is none.
 *
 * The link stays in the text as well. The app shows the attachment above it, and the text still
 * reads correctly on a phone whose copy of the attachment has not arrived.
 *
 * Never throws: an attachment is an addition to a notice, and failing to work one out must not stop
 * the notice itself from going.
 */
function messageAttachment_(body) {
  var links = messageLinks_(body).slice(0, ATTACHMENT_LINKS_CHECKED);
  for (var i = 0; i < links.length; i++) {
    try {
      var probe = probeAttachment_(links[i]);
      if (probe.kind === 'pdf') {
        // pdfBytes lets a phone on mobile data decide without a request of its own.
        var pdf = { pdfUrl: links[i] };
        if (probe.bytes) pdf.pdfBytes = probe.bytes;
        return pdf;
      }
      if (probe.kind === 'image') return { imageUrl: links[i] };
    } catch (e) {
      Logger.log('Could not check %s for an attachment (%s); left as a plain link.', links[i], e.message);
    }
  }
  return {};
}

function sendNotice(title, body, pin) {
  var by = requireEditor_();
  // The compose page sends arbitrary text to every phone, so it is the path that needs the PIN.
  checkPin_(pin);

  title = (title || '').toString().trim();
  body = (body || '').toString().trim();

  if (!title) throw new Error('A title is required. It is what people read first, and often all they read.');
  if (title.length > 120) throw new Error('Title is too long (' + title.length + '); keep it under 120 characters.');
  if (body.length > 900) throw new Error('Body is too long (' + body.length + '); keep it under 900 characters.');

  var topic = dispensaryTopic_();
  var attachment = messageAttachment_(body);
  var logId = nextLogId_();
  var sentAt = new Date().toISOString();

  var record = { title: title, body: body, topic: topic, dispensary: dispensaryId_(), sentAt: sentAt, sentBy: by, scriptVersion: SCRIPT_VERSION };
  var data = { logId: logId, title: title, body: body, topic: topic };
  for (var field in attachment) {
    if (!attachment.hasOwnProperty(field)) continue;
    record[field] = attachment[field];
    // FCM data values must be strings.
    data[field] = String(attachment[field]);
  }

  firebase_('put', '/sent/' + logId + '.json', record);

  // Data-only. A `notification` block here would make the FCM SDK draw the tray notification
  // itself while the app is backgrounded: onMessageReceived would never run, the entitlement check
  // would be skipped, and nothing would be written to the phone's history.
  //
  // `topic` travels in the data as well as the envelope: the phone checks it against what its
  // dispensary offers and what the user has switched on, and picks the notification channel from it.
  var message = { message: { topic: TOPIC_OVERRIDE || topic, android: { priority: 'high' }, data: data } };

  var response = UrlFetchApp.fetch( 'https://fcm.googleapis.com/v1/projects/' + property_('PROJECT_ID') + '/messages:send', { method: 'post', contentType: 'application/json', headers: { Authorization: 'Bearer ' + accessToken_() }, payload: JSON.stringify(message), muteHttpExceptions: true } );

  if (response.getResponseCode() >= 300) {
    firebase_('patch', '/sent/' + logId + '.json', { error: response.getContentText() });
    throw new Error('FCM refused the message: ' + response.getContentText());
  }

  firebase_('patch', '/sent/' + logId + '.json', { fcmName: JSON.parse(response.getContentText()).name || '' });
  return { logId: logId, sentAt: sentAt, title: title, topic: topic,
           attachment: attachment.pdfUrl ? 'pdf' : (attachment.imageUrl ? 'image' : '') };
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
    rows.push({ logId: logId, title: all[logId].title || '', body: all[logId].body || '', topic: all[logId].topic || '', sentAt: all[logId].sentAt || '', sentBy: all[logId].sentBy || '', failed: !!all[logId].error });
  }
  rows.sort(function (a, b) { return Number(b.logId) - Number(a.logId); });
  return rows;
}

// ---------------------------------------------------------------- Control messages

/**
 * UTF-8 length of [s] in bytes. Pure, so the banner limit can be tested.
 *
 * Counted by hand rather than through Utilities.newBlob so the test suite can check it without a
 * service call, and so it matches the console's copy exactly.
 */
function utf8Length_(s) {
  s = String(s === undefined || s === null ? '' : s);
  var bytes = 0;
  for (var i = 0; i < s.length; i++) {
    var c = s.charCodeAt(i);
    if (c < 0x80) bytes += 1;
    else if (c < 0x800) bytes += 2;
    else if (c >= 0xD800 && c <= 0xDBFF && i + 1 < s.length) { bytes += 4; i++; }
    else bytes += 3;
  }
  return bytes;
}

/**
 * The FCM request for one control message. Pure, so the envelope can be tested without sending.
 *
 * Data-only and deliberately without title or body: these show the user nothing. A notification
 * block here would be drawn by the SDK and onMessageReceived would never run, so a revoke would be
 * displayed and never applied. Every value is a string, as FCM v1 requires; empty values are kept,
 * because an empty banner html is how a removal is said.
 */
function controlRequest_(topic, data) {
  var strings = {};
  for (var key in data) {
    if (data.hasOwnProperty(key) && data[key] !== undefined && data[key] !== null) {
      strings[key] = String(data[key]);
    }
  }
  return { message: { topic: topic, android: { priority: 'high' }, data: strings } };
}

/**
 * Broadcasts one control message: a revoke, a resume or a banner change.
 *
 * A broadcast, not per-device addressing. onNewToken is deliberately not overridden in the app and
 * everything here is topic-addressed, so every phone receives a revoke or resume and tests the code
 * against its own. That publishes the code to all of them, which is harmless -- it says only that
 * the code was stopped or started -- but it is what is being sent.
 *
 * It cannot be authoritative. FCM is best-effort, and a phone switched off past the message TTL
 * never sees it, which is why the periodic verification on the device stays as the safety net. See
 * docs/decisions.md.
 *
 * Throws when FCM refuses. The caller leaves the entry queued and retries; a message that did go
 * out and is sent again is harmless, because the phone ignores a stamp it has already applied.
 */
function pushControl_(data) {
  var topic = TOPIC_OVERRIDE || CONTROL_TOPIC;
  var response = UrlFetchApp.fetch(
    'https://fcm.googleapis.com/v1/projects/' + property_('PROJECT_ID') + '/messages:send',
    { method: 'post', contentType: 'application/json',
      headers: { Authorization: 'Bearer ' + accessToken_() },
      payload: JSON.stringify(controlRequest_(topic, data)), muteHttpExceptions: true });

  if (response.getResponseCode() >= 300) {
    throw new Error('FCM refused the ' + data.type + ': ' + response.getContentText());
  }
  return JSON.parse(response.getContentText()).name || '';
}

// ---------------------------------------------------------------- Web app

/**
 * The admin console's /exec URL, from the ADMIN_URL Script Property, or '' for no link.
 *
 * A property so a redeploy of the console, which can change its URL, is followed by editing one
 * value here rather than by a redeploy of this project too. Only https is accepted: the value is
 * written into an href, and anything else is more likely a paste mistake than a link.
 */
function adminUrl_() {
  var value = String(PropertiesService.getScriptProperties().getProperty('ADMIN_URL') || '').trim();
  return /^https:\/\//i.test(value) ? value : '';
}

function doGet() {
  try {
    requireEditor_();
  } catch (err) {
    return refusalPage_(err.message);
  }
  var page = HtmlService.createTemplateFromFile('Index');
  page.adminUrl = adminUrl_();
  return page.evaluate().setTitle('Sender - Paramanu Seniors Health').addMetaTag('viewport', 'width=device-width, initial-scale=1').setFaviconUrl('https://paramanuseniorshealth.org/favicon.ico');
}

/** Run from the editor after setup to prove the credentials work without sending anything. */
function testConnection() {
  requireEditor_();
  Logger.log('version=%s templates=%s sent=%s', SCRIPT_VERSION, listTemplates().length, listSent(5).length);
}
