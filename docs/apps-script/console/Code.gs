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
 *        DISPENSARY_ID         = barc-vashi   (the dispensary new codes belong to, whose banner this edits)
 *        PRINTING_ENABLED      = 'false' to turn slip printing off (optional; absent means on)
 *     Take DATABASE_URL from the Realtime Database page; it is region qualified.
 *  5. Deploy -> Web app.
 *       Execute as:     User accessing the web app   <- REQUIRED, see requireEditor_
 *       Who has access: Anyone with a Google account
 *  6. Run purgeInstallTrigger() once from the editor. Every 30 days it erases holders removed more
 *     than 30 days ago; purgeDryRun() lists what it would erase and changes nothing.
 *
 * This console never gets FCM credentials and cannot broadcast anything itself; issuing codes and
 * reaching four hundred phones stay separate jobs. Disable, Enable, removing or restoring a holder,
 * and banner changes write /controlQueue and a minute-ly trigger in the SENDER project broadcasts
 * them -- see Control.gs there. If that trigger is not installed, they still work: they are recorded in the database,
 * and phones catch up at their next daily check or app open. Only the speed depends on the trigger.
 *
 * The service account key is a real credential. It lives in Script Properties, never in this file,
 * and never in the Android app.
 */

var SCRIPT_VERSION = '2026-09-13-codes';

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

/** How many different characters must each appear more than once in a payload. */
var PAYLOAD_MIN_REPEATS = 2;

/**
 * Codes that exist for Play review and must never reach a member of the public.
 *
 * `P1AYREVQ` is the app access code given to Play reviewers. It has to exist and stay unclaimed, or
 * a reviewer cannot get past the code screen.
 *
 * This exists **only** to keep it off the print sheet. Printing is the one bulk action here: one
 * button covers every eligible code across every page, and the note that explains what a code is
 * for is in the table, not on the slip -- so nobody is looking at this row at the moment it would
 * come out of the printer and be handed over at a counter.
 *
 * Deleting is not guarded, deliberately. Every code carries a `note` saying what it is, deletion is
 * one row at a time behind a confirmation, and refusing it would be second-guessing a staff member
 * who is looking straight at the row.
 *
 * Note that it cannot be regenerated either way: `P1AYREVQ` has no repeated characters, so
 * generateCode_ will never produce it. Hand-written or gone.
 */
var RESERVED_CODES = ['P1AYREVQ'];

function isReservedCode_(code) {
  return RESERVED_CODES.indexOf(String(code).toUpperCase()) >= 0;
}

/** How many of [s]'s characters appear more than once. Pure, so the rule can be tested. */
function countRepeatingCharacters_(s) {
  var counts = {};
  for (var i = 0; i < s.length; i++) {
    var ch = s.charAt(i);
    counts[ch] = (counts[ch] || 0) + 1;
  }
  var repeating = 0;
  for (var key in counts) {
    if (counts.hasOwnProperty(key) && counts[key] >= 2) repeating++;
  }
  return repeating;
}

/**
 * A code in which at least two different characters each occur more than once.
 *
 * Codes are read aloud across a counter to someone in their eighties, and again over the phone to
 * the helpdesk afterwards, so they need something to hold on to. Two separate repeats do that:
 * AXGX-BBBE and 003C-93VV are easy to say back. Adjacency is not required and is not the point.
 *
 * An earlier version forced a contiguous run of three instead, and it produced exactly the codes
 * that turned out to be hard: GGGW-YBHZ and EWNN-NMGA have one repeated character and six unrelated
 * ones, and a run of three invites the question "was that two Gs or three?" -- which the check
 * character catches, but only after somebody has typed it wrong at a counter.
 *
 * Rejection sampling rather than construction: placing chosen characters in chosen slots would
 * bias the distribution towards whatever pattern the construction happened to favour. Drawing
 * uniformly and discarding what does not qualify keeps every allowed payload equally likely. About
 * one draw in twelve qualifies, so a run of two hundred costs a few thousand cheap iterations.
 *
 * COST IN GUESSABILITY: the payload space falls from 32^7 (about 34 billion) to roughly 8% of that,
 * about 2.9 billion. Against four hundred live codes that is under one in seven million per guess,
 * each guess costing an authenticated write the rules refuse, and /codes cannot be listed.
 */
function generateCode_() {
  // A ceiling, not a expectation: at an 8% acceptance rate the odds of reaching it are vanishing,
  // and a bounded loop cannot hang the console if the alphabet or the rule is ever changed badly.
  for (var attempt = 0; attempt < 1000; attempt++) {
    var payload = '';
    for (var i = 0; i < PAYLOAD_LENGTH; i++) {
      payload += ALPHABET.charAt(Math.floor(Math.random() * ALPHABET.length));
    }
    if (countRepeatingCharacters_(payload) >= PAYLOAD_MIN_REPEATS) {
      return payload + checkCharacter(payload);
    }
  }
  throw new Error('Could not generate a code with enough repeated characters; check the rule.');
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
 * The only events the audit may contain. A closed set on purpose: a typo that invents a new event
 * would produce a row nobody ever queries, and the log is the record the NGO is meant to trust
 * without asking a developer.
 *
 * The first seven keep the names they were stored under, whatever the page now calls the action:
 * `revoked` is shown as Disabled, `restored` as Enabled, `released` as Phone unlinked.
 *
 * The holder events never carry the holder's details -- only the holder's id, and for a move the
 * other code. /audit is never erased, and the purge must actually remove a person's name and number;
 * those live in /holders, where it can.
 */
var AUDIT_EVENTS = ['issued', 'printed', 'revoked', 'restored', 'released', 'note', 'deleted',
                    'holder-added', 'holder-edited', 'holder-moved-in', 'holder-moved-out',
                    'holder-removed', 'holder-restored', 'holder-purged'];

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
/**
 * Whether a slip has been printed for this code, derived from its history.
 *
 * Not a field on /codes. A new sibling there needs its own .validate rule or the $other catch-all
 * refuses the app's claim write for every unclaimed slip -- the trap that adding `note` sprang once
 * already. The audit is the record of what has been done to a code, and printing is one of those
 * things, so the state lives there and costs no rules change.
 *
 * Unlinking a phone does not clear it. The slip still belongs to the code's holder, who is usually
 * the one getting a new phone, so it must not come out on the next print run as though it were new.
 * Only the purge clears it, when a removed holder is erased and the code goes back to Unused.
 */
function codeIsPrinted_(timeline) {
  var printed = false;
  for (var i = 0; i < (timeline || []).length; i++) {
    if (timeline[i].event === 'printed') printed = true;
    else if (timeline[i].event === 'holder-purged') printed = false;
  }
  return printed;
}

/**
 * Whether a code is On hold, derived from its history the same way as printed.
 *
 * Removing a holder puts the code on hold; restoring the holder, or the purge erasing them, takes it
 * off. Kept in the audit rather than as a field on /codes for the reason printed is: a new sibling
 * there needs a rules change or it breaks the app's claim write. The phone never needs to know --
 * to the phone an On hold code is simply revoked.
 */
function codeIsOnHold_(timeline) {
  var onHold = false;
  for (var i = 0; i < (timeline || []).length; i++) {
    var event = timeline[i].event;
    if (event === 'holder-removed') onHold = true;
    else if (event === 'holder-restored' || event === 'holder-purged') onHold = false;
  }
  return onHold;
}

function auditTimeline_(entries, node) {
  var out = [];

  for (var key in entries) {
    if (!entries.hasOwnProperty(key)) continue;
    var e = entries[key] || {};
    out.push({ at: e.at || 0, by: e.by || '', event: e.event || '', to: e.to || '', holder: e.holder || '' });
  }

  if (node && node.activatedAt) {
    out.push({ at: node.activatedAt, by: '', event: 'claimed', to: '', holder: '' });
  }

  out.sort(function (a, b) { return a.at - b.at; });
  return out;
}

// ---------------------------------------------------------------- Control queue

/**
 * Asks the sender to broadcast a control message, by writing where its trigger looks.
 *
 * PUT rather than PATCH, and keyed rather than pushed, so the latest decision replaces one still
 * waiting: disabling and enabling inside a minute leaves a single resume, never a revoke followed by
 * a resume that could arrive in either order. `at` is when staff acted; the phone uses it to ignore
 * a message older than one it has already applied.
 */
function queueControl_(key, entry) {
  firebase_('put', '/controlQueue/' + key + '.json', entry);
}

/**
 * Drops anything still waiting to be broadcast for [code].
 *
 * Used when a code's phone is unlinked, or the code is deleted or purged. A stale revoke firing
 * afterwards would cut off whoever types the code next -- they would enter their slip and be told at
 * once that they had been removed.
 */
function clearCodeControl_(code) {
  firebase_('delete', '/controlQueue/code_' + code + '.json');
}

/**
 * The most a banner may weigh, in UTF-8 bytes, to travel inside a control message.
 *
 * FCM refuses a data payload over 4096 bytes, keys included, and the html is sent inside the message
 * so four hundred phones do not all fetch /info at once (SYSTEM.md 5.14). Bytes rather than
 * characters: Marathi or Hindi costs three bytes a character. Must match BANNER_MAX_BYTES in the
 * sender's Code.gs.
 */
var BANNER_MAX_BYTES = 3500;

/** UTF-8 length of [s] in bytes. Pure, and identical to the sender's copy. */
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

// ---------------------------------------------------------------- Settings

/**
 * Whether slips are printed from this console.
 *
 * A Script Property, PRINTING_ENABLED, so it can be switched without a redeploy. Only the exact word
 * 'false' turns it off; absent or anything else leaves printing on, which is how the console has
 * always behaved.
 *
 * Off disables the Print controls on the page and nothing else. The Printed status is still derived
 * and shown, so codes printed before the switch keep saying so and their history stays true.
 */
function printingEnabled_() {
  var value = PropertiesService.getScriptProperties().getProperty('PRINTING_ENABLED');
  return String(value === null || value === undefined ? 'true' : value).trim().toLowerCase() !== 'false';
}

// ---------------------------------------------------------------- Codes and holders (pure)

function formatCode_(code) {
  code = String(code || '');
  return code.length === 8 ? code.substring(0, 4) + '-' + code.substring(4) : code;
}

/**
 * A code as typed by a person reduced to how it is stored: capitals and digits only, with O read as
 * zero and I or L as one, the way the app reads them. So "axgx-bbbe" finds AXGXBBBE.
 */
function normaliseCode_(typed) {
  return String(typed || '').toUpperCase().replace(/[^0-9A-Z]/g, '').replace(/O/g, '0').replace(/[IL]/g, '1');
}

/** How long a removed holder is kept, so a removal can be undone, before the purge erases them. */
var HOLDER_RETENTION_MS = 30 * 24 * 60 * 60 * 1000;

var HOLDER_FIELDS = ['name', 'chss', 'phone'];

/**
 * Cleans what staff typed into the holder form. Pure, so the rules can be tested.
 *
 * The name is required: a holder nobody can find again by name defeats the point of recording one.
 * The CHSS number is kept exactly as typed, because its format is not known and a strict check would
 * refuse a real card at the counter. A ten-digit telephone number loses its spaces, dashes and any
 * leading +91, 91 or 0, so the same number typed two ways is found by one search; anything else is
 * kept as typed rather than refused, for the same reason as the CHSS number.
 */
function normaliseHolder_(name, chss, phone) {
  var clean = {
    name: String(name || '').replace(/\s+/g, ' ').trim().slice(0, 80),
    chss: String(chss || '').trim().slice(0, 40),
    phone: String(phone || '').trim().slice(0, 30)
  };
  if (!clean.name) throw new Error('A name is required, so the holder can be found again.');

  var digits = clean.phone.replace(/[\s\-().]/g, '')
    .replace(/^\+91/, '').replace(/^91(?=\d{10}$)/, '').replace(/^0(?=\d{10}$)/, '');
  if (/^\d{10}$/.test(digits)) clean.phone = digits;
  return clean;
}

/** What an edit changed, as { field: { from, to } }. Empty when nothing did. Pure. */
function holderChanges_(before, after) {
  var changes = {};
  HOLDER_FIELDS.forEach(function (field) {
    var from = (before && before[field]) || '';
    var to = (after && after[field]) || '';
    if (from !== to) changes[field] = { from: from, to: to };
  });
  return changes;
}

function holderHistoryList_(entries) {
  var out = [];
  for (var key in entries) {
    if (!entries.hasOwnProperty(key)) continue;
    var e = entries[key] || {};
    out.push({ at: e.at || 0, by: e.by || '', event: e.event || '', changes: e.changes || null });
  }
  out.sort(function (a, b) { return a.at - b.at; });
  return out;
}

/**
 * Splits /holders into the active holder of each code and the removed ones. Pure.
 *
 * Returns { byCode: { CODE: holder }, removed: [holder] }, each holder carrying its id and its
 * history in order. Two active holders on one code would be a bug -- adding and moving both refuse to
 * make one -- and the newer is kept so the page still renders.
 */
function splitHolders_(all) {
  var byCode = {};
  var removed = [];
  for (var id in all) {
    if (!all.hasOwnProperty(id)) continue;
    var h = all[id] || {};
    var holder = {
      id: id, code: h.code || '', name: h.name || '', chss: h.chss || '', phone: h.phone || '',
      createdAt: h.createdAt || 0, removedAt: h.removedAt || 0, removedBy: h.removedBy || '',
      wasDisabled: h.wasDisabled === true, history: holderHistoryList_(h.history)
    };
    if (holder.removedAt) {
      removed.push(holder);
    } else if (!byCode[holder.code] || byCode[holder.code].createdAt < holder.createdAt) {
      byCode[holder.code] = holder;
    }
  }
  removed.sort(function (a, b) { return b.removedAt - a.removedAt; });
  return { byCode: byCode, removed: removed };
}

/** Whether a removed holder has been gone long enough to erase. Pure. */
function holderIsPurgeable_(holder, now) {
  return !!(holder && holder.removedAt) && now - holder.removedAt >= HOLDER_RETENTION_MS;
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
  // Which dispensary the slip belongs to decides what the phone that types it is offered. Read once,
  // before generating, so a missing Script Property fails the whole batch rather than half of it.
  var dispensary = dispensaryId_();

  var guard = 0;
  while (created.length < count) {
    if (++guard > count * 50) throw new Error('Could not find unused codes; try again.');
    var code = generateCode_();
    if (existing[code] || updates[code]) continue;
    var node = { issued: issued, dispensary: dispensary };
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
  refuseOnHold_(loadCode_(code), 'given a note');
  note = (note || '').toString().trim().slice(0, 120);
  firebase_('patch', '/codes/' + code + '.json', { note: note || null });
  audit_(code, 'note', by, { to: note });
  return listCodes();
}

/**
 * A code's node and whether it is On hold, read fresh for an action to check against.
 *
 * The page hides the buttons that do not apply; this is what makes that a rule rather than a
 * suggestion. Throws for a malformed or missing code.
 */
function loadCode_(code) {
  if (!isValidCode(code)) throw new Error('Not a valid code: ' + code);
  var node = firebase_('get', '/codes/' + code + '.json');
  if (!node) throw new Error(formatCode_(code) + ' does not exist.');
  var timeline = auditTimeline_(firebase_('get', '/audit/' + code + '.json'), node);
  return { code: code, node: node, onHold: codeIsOnHold_(timeline) };
}

/** Nothing may be done to an On hold code; its history explains why, and Restore holder undoes it. */
function refuseOnHold_(state, action) {
  if (state.onHold) {
    throw new Error(formatCode_(state.code) + ' is On hold because its holder was removed, so it cannot be ' +
                    action + '. Restore the holder first, from Removed holders.');
  }
}

/**
 * Unlinks the phone from a code, so the code can be typed on a new phone.
 *
 * For a phone that is gone: the app was uninstalled, the phone was replaced, or its data was cleared.
 * The device stores the anonymous UID locally, so losing that storage orphans the claim -- the code
 * stays In use with no phone able to prove ownership of it.
 *
 * The note, the holder and the Printed status all stay: the usual case is the same person on a new
 * phone, still holding the same slip.
 *
 * WARNING: if a working phone still holds this claim, unlinking cuts that phone off. Its next check
 * finds no usedBy and stops. Use disableCode for a phone you mean to stop; use this only for one that
 * is genuinely gone.
 */
function unlinkPhone(code) {
  var by = requireEditor_();
  refuseOnHold_(loadCode_(code), 'unlinked');
  firebase_('patch', '/codes/' + code + '.json', { usedBy: null, activatedAt: null, revoked: null });

  // The next phone to type this code must not be cut off by something queued for the last one.
  clearCodeControl_(code);

  audit_(code, 'released', by);
  return listCodes();
}

/**
 * Everything the codes section shows, read in one go: every code newest first with its state and
 * holder, the removed holders that can still be restored, and whether printing is on.
 */
function listCodes() {
  requireEditor_();
  var all = firebase_('get', '/codes.json') || {};
  var audit = firebase_('get', '/audit.json') || {};
  var holders = splitHolders_(firebase_('get', '/holders.json') || {});

  // History lines name the holder they concern while that holder still exists. After the purge the
  // id names nobody, which is the point of the purge.
  var names = {};
  Object.keys(holders.byCode).forEach(function (code) { names[holders.byCode[code].id] = holders.byCode[code].name; });
  holders.removed.forEach(function (h) { names[h.id] = h.name; });

  var rows = [];
  for (var code in all) {
    if (!all.hasOwnProperty(code)) continue;
    var node = all[code] || {};
    var timeline = auditTimeline_(audit[code], node);
    timeline.forEach(function (entry) { if (entry.holder) entry.holderName = names[entry.holder] || ''; });
    rows.push({
      code: code, formatted: formatCode_(code), issued: node.issued || 0,
      claimed: !!node.usedBy, claimedAt: node.activatedAt || 0, revoked: node.revoked === true,
      printed: codeIsPrinted_(timeline), onHold: codeIsOnHold_(timeline), reserved: isReservedCode_(code),
      note: node.note || '', holder: holders.byCode[code] || null,
      audit: timeline, lastChange: timeline.length ? timeline[timeline.length - 1] : null
    });
  }
  rows.sort(function (a, b) { return b.issued - a.issued; });

  return {
    codes: rows,
    removedHolders: holders.removed.map(function (h) {
      h.purgeAfter = h.removedAt + HOLDER_RETENTION_MS;
      return h;
    }),
    printingEnabled: printingEnabled_()
  };
}

/**
 * Stops the phone on a code. The code is not deleted: deleting it would let the next person to type
 * it claim it afresh, because the rules only refuse a code that already carries usedBy.
 */
function disableCode(code) {
  var by = requireEditor_();
  refuseOnHold_(loadCode_(code), 'disabled');
  firebase_('patch', '/codes/' + code + '.json', { revoked: true });

  // The database is the truth; the queue is only how the phone hears about it sooner. A minute-ly
  // trigger in the sender drains this and broadcasts, because this console has no FCM credentials
  // and is deliberately not given any (SYSTEM.md 2.3).
  queueControl_('code_' + code, { type: 'revoke', code: code, at: Date.now(), by: by });

  audit_(code, 'revoked', by);
  return listCodes();
}

/**
 * Records that slips have been printed for [codes].
 *
 * Called after the print dialog has been dismissed, not before: marking first and printing second
 * would mark a run the user cancelled, and there is no way to ask a browser whether paper actually
 * came out. The page confirms instead.
 *
 * One multi-path PATCH rather than a POST per code, because a run can be two hundred slips. The
 * key is unique within a code by construction -- one entry per code per call, keyed on the shared
 * timestamp -- and the timeline sorts on `at` rather than on the key, so it need not be a push id.
 *
 * A code that has since been claimed is skipped rather than refused: the run may have been sitting
 * on screen for a while, and failing the whole batch because one person activated meanwhile would
 * be worse than quietly not marking that one.
 */
function markPrinted(codes) {
  var by = requireEditor_();
  if (!codes || !codes.length) return listCodes();

  var all = firebase_('get', '/codes.json') || {};
  var at = Date.now();
  var updates = {};
  var marked = 0;

  for (var i = 0; i < codes.length; i++) {
    var code = String(codes[i]);
    if (!isValidCode(code)) continue;
    var node = all[code];
    if (!node || node.usedBy || node.revoked === true) continue;
    // Belt and braces: the page leaves it off the sheet, so it should never reach here.
    if (isReservedCode_(code)) continue;
    updates[code + '/p' + at] = { at: at, by: by, event: 'printed' };
    marked++;
  }

  if (marked) firebase_('patch', '/audit.json', updates);
  return listCodes();
}

/**
 * Whether a code may be deleted. Pure, so the rule can be tested.
 *
 * Only a code with no phone and no holder, that is not On hold.
 *
 * A code with a phone: deleting it would cut that phone off at its next check with no explanation
 * anywhere -- the device would find no node, read that as a withdrawn claim, and raise the banner.
 * Disable does that deliberately and reversibly; this must not do it by accident.
 *
 * A code with a holder is somebody's. Move the holder to their new code first, and the old one can
 * then be deleted. An On hold code is waiting on a restore or on the purge, and both need it to exist.
 *
 * A disabled-but-never-claimed code is deletable: disabled or not, nobody ever used it.
 */
function codeIsDeletable_(node, hasHolder, onHold) {
  if (!node || hasHolder || onHold) return false;
  return !node.usedBy;
}

/**
 * Removes a code that was generated but never used.
 *
 * For over-generation and misprints. Note what this cannot know: unclaimed is not the same as
 * unprinted. A slip for this code may already be in somebody's pocket, and deleting it means they
 * will be told "that code was not accepted" at the counter with nothing to explain why -- so the
 * page asks before calling this.
 *
 * The /audit node is deliberately kept. It is the only remaining record that this code ever
 * existed, and the only way to answer "why did this slip stop working"; the code itself is gone
 * from /codes, so nothing lists it any more.
 */
function deleteCode(code) {
  var by = requireEditor_();
  var state = loadCode_(code);
  var holder = activeHolderOf_(code);

  // Checked here and not only in the page: a hidden button is not a rule.
  if (!codeIsDeletable_(state.node, !!holder, state.onHold)) {
    var label = formatCode_(code);
    if (state.onHold) throw new Error(label + ' is On hold and cannot be deleted.');
    if (holder) throw new Error(label + ' has a holder, ' + holder.name + '. Move the holder to their new code first.');
    throw new Error(label + ' has a phone linked and cannot be deleted. Unlink the phone first if it is gone.');
  }

  firebase_('delete', '/codes/' + code + '.json');
  // Defensive: an unclaimed code should never have anything queued. The drain would drop a leftover
  // entry on finding the code gone, but only after logging it as something to look at.
  clearCodeControl_(code);

  audit_(code, 'deleted', by);
  return listCodes();
}

/** Starts a disabled phone again. The same code keeps working, so no new slip is needed. */
function enableCode(code) {
  var by = requireEditor_();
  refuseOnHold_(loadCode_(code), 'enabled');
  firebase_('patch', '/codes/' + code + '.json', { revoked: null });

  // Tells the phone to resume at once rather than at its next daily check, and replaces any revoke
  // not yet broadcast, so disabling and enabling inside a minute sends one resume and no revoke.
  //
  // The drain sends the resume only if a phone holds the code, so enabling a code nobody has claimed
  // broadcasts nothing.
  queueControl_('code_' + code, { type: 'resume', code: code, at: Date.now(), by: by });

  audit_(code, 'restored', by);
  return listCodes();
}

// ---------------------------------------------------------------- Holders

/**
 * The person a code was given to: name, CHSS number and telephone number.
 *
 * Stored at /holders/{id}, never on /codes. /codes/{CODE} is readable by any signed-in phone that
 * knows the code, whereas no security rule opens /holders to anyone, so only this console sees who
 * holds what. Keyed by an id of its own rather than by code, so a person keeps their record and its
 * history when they are moved to a new code.
 *
 * Each holder carries its own history, with the values before and after every change. The code's
 * /audit records only that something happened to its holder, by id -- see AUDIT_EVENTS for why.
 *
 * Writes are ordered so that a failure part-way leaves something staff can finish by pressing the
 * same button again: the code and its audit first, the holder record last.
 */

function loadHolder_(id) {
  id = String(id || '');
  if (!/^[A-Za-z0-9_-]{1,40}$/.test(id)) throw new Error('That is not a valid holder.');
  var holder = firebase_('get', '/holders/' + id + '.json');
  if (!holder) throw new Error('That holder no longer exists. Refresh the page.');
  holder.id = id;
  return holder;
}

function activeHolderOf_(code) {
  return splitHolders_(firebase_('get', '/holders.json') || {}).byCode[code] || null;
}

function holderHistory_(id, entry) {
  firebase_('post', '/holders/' + id + '/history.json', entry);
}

/** Records the person a code was given to. A code has one holder at a time. */
function addHolder(code, name, chss, phone) {
  var by = requireEditor_();
  refuseOnHold_(loadCode_(code), 'given a holder');
  var existing = activeHolderOf_(code);
  if (existing) {
    throw new Error(formatCode_(code) + ' already has a holder, ' + existing.name + '. Edit or move that holder instead.');
  }

  var clean = normaliseHolder_(name, chss, phone);
  var at = Date.now();
  var id = firebase_('post', '/holders.json', {
    code: code, name: clean.name, chss: clean.chss, phone: clean.phone, createdAt: at, createdBy: by
  }).name;

  audit_(code, 'holder-added', by, { holder: id });
  holderHistory_(id, { at: at, by: by, event: 'added', changes: holderChanges_({}, clean) });
  return listCodes();
}

/** Changes any of a holder's three details. Every change is kept in the holder's history. */
function editHolder(id, name, chss, phone) {
  var by = requireEditor_();
  var holder = loadHolder_(id);
  if (holder.removedAt) throw new Error(holder.name + ' has been removed. Restore them before editing.');

  var clean = normaliseHolder_(name, chss, phone);
  var changes = holderChanges_(holder, clean);
  if (!Object.keys(changes).length) return listCodes();

  var at = Date.now();
  audit_(holder.code, 'holder-edited', by, { holder: holder.id });
  firebase_('patch', '/holders/' + holder.id + '.json', clean);
  holderHistory_(holder.id, { at: at, by: by, event: 'edited', changes: changes });
  return listCodes();
}

/**
 * Moves a holder to another code, for when the dispensary issues them a new slip.
 *
 * The old code is left with no holder and otherwise exactly as it was -- still In use if a phone is
 * on it. Unlink that phone, and the old code can be deleted.
 */
function moveHolder(id, toCode) {
  var by = requireEditor_();
  var holder = loadHolder_(id);
  if (holder.removedAt) throw new Error(holder.name + ' has been removed. Restore them before moving.');

  toCode = normaliseCode_(toCode);
  if (toCode === holder.code) throw new Error(holder.name + ' already holds ' + formatCode_(toCode) + '.');
  refuseOnHold_(loadCode_(toCode), 'given a holder');
  var occupant = activeHolderOf_(toCode);
  if (occupant) throw new Error(formatCode_(toCode) + ' already has a holder, ' + occupant.name + '.');

  var at = Date.now();
  var from = holder.code;
  audit_(from, 'holder-moved-out', by, { holder: holder.id, to: toCode });
  audit_(toCode, 'holder-moved-in', by, { holder: holder.id, to: from });
  firebase_('patch', '/holders/' + holder.id + '.json', { code: toCode });
  holderHistory_(holder.id, { at: at, by: by, event: 'moved', changes: { code: { from: from, to: toCode } } });
  return listCodes();
}

/**
 * Takes a person off their code. The code goes On hold: its phone stops, and nothing can be done to
 * it until the holder is restored or the purge erases them.
 *
 * Whether the code was already Disabled is kept on the holder, so Restore puts it back exactly as it
 * was rather than starting a phone that somebody had deliberately stopped.
 */
function removeHolder(id) {
  var by = requireEditor_();
  var holder = loadHolder_(id);
  if (holder.removedAt) throw new Error(holder.name + ' has already been removed.');

  var state = loadCode_(holder.code);
  var wasDisabled = state.node.revoked === true;
  var at = Date.now();

  if (!wasDisabled) {
    firebase_('patch', '/codes/' + holder.code + '.json', { revoked: true });
    // Only a code with a phone has anybody to tell.
    if (state.node.usedBy) queueControl_('code_' + holder.code, { type: 'revoke', code: holder.code, at: at, by: by });
  }
  audit_(holder.code, 'holder-removed', by, { holder: holder.id });
  firebase_('patch', '/holders/' + holder.id + '.json', { removedAt: at, removedBy: by, wasDisabled: wasDisabled });
  holderHistory_(holder.id, { at: at, by: by, event: 'removed' });
  return listCodes();
}

/** Brings a removed holder back and takes their code off hold, as it was before the removal. */
function restoreHolder(id) {
  var by = requireEditor_();
  var holder = loadHolder_(id);
  if (!holder.removedAt) throw new Error(holder.name + ' has not been removed.');

  var state = loadCode_(holder.code);
  var at = Date.now();

  if (holder.wasDisabled !== true) {
    firebase_('patch', '/codes/' + holder.code + '.json', { revoked: null });
    if (state.node.usedBy) queueControl_('code_' + holder.code, { type: 'resume', code: holder.code, at: at, by: by });
  }
  audit_(holder.code, 'holder-restored', by, { holder: holder.id });
  firebase_('patch', '/holders/' + holder.id + '.json', { removedAt: null, removedBy: null, wasDisabled: null });
  holderHistory_(holder.id, { at: at, by: by, event: 'restored' });
  return listCodes();
}

// ---------------------------------------------------------------- Purge

/**
 * Erases holders removed more than 30 days ago, and puts their codes back to Unused.
 *
 * Erasing is the point. A removed holder is kept only so the removal can be undone; after that there
 * is no reason for a person's name and telephone number to stay. The code's /audit keeps its record
 * that it had a holder, by id, which after this names nobody.
 *
 * Back to Unused means: its phone is unlinked, it is no longer stopped, its Printed status is cleared
 * (by the holder-purged event), and anything queued for it is dropped. Its note stays.
 *
 * Returns one line per holder, saying what was done -- or with [dryRun], what would be.
 */
function purgeRemovedHolders_(dryRun, by) {
  var now = Date.now();
  var due = splitHolders_(firebase_('get', '/holders.json') || {}).removed.filter(function (h) {
    return holderIsPurgeable_(h, now);
  });

  return due.map(function (h) {
    var line = formatCode_(h.code) + '  removed ' + new Date(h.removedAt).toISOString() + '  holder ' + h.id;
    if (dryRun) return 'would erase  ' + h.name + '  ' + line;

    var node = h.code ? firebase_('get', '/codes/' + h.code + '.json') : null;
    if (node) {
      firebase_('patch', '/codes/' + h.code + '.json', { usedBy: null, activatedAt: null, revoked: null });
      clearCodeControl_(h.code);
      audit_(h.code, 'holder-purged', by, { holder: h.id });
    }
    firebase_('delete', '/holders/' + h.id + '.json');
    return 'erased  ' + line;
  });
}

/** The trigger handler. A trigger runs with no signed-in person, so it records itself as the actor. */
function purgeRun() {
  var lines = purgeRemovedHolders_(false, 'purge (automatic)');
  Logger.log('purge: %s holder(s) erased%s', lines.length, lines.length ? '\n' + lines.join('\n') : '');
}

/** Lists what the next purge would erase. Changes nothing. Run from the editor. */
function purgeDryRun() {
  requireEditor_();
  var lines = purgeRemovedHolders_(true, '');
  Logger.log('purge dry run: %s holder(s) due%s', lines.length, lines.length ? '\n' + lines.join('\n') : '');
}

/**
 * Creates the purge trigger, every 30 days. Safe to run twice: it removes any existing one first.
 *
 * With a 30-day run and 30-day retention, a removed holder is erased between 30 and 60 days after
 * removal. At least 30 is the promise; the rest is only how long the trigger sleeps between runs.
 */
function purgeInstallTrigger() {
  requireEditor_();
  purgeRemoveTriggers_();
  ScriptApp.newTrigger('purgeRun').timeBased().everyDays(30).create();
  Logger.log('Trigger installed: purgeRun every 30 days.');
}

function purgeStopTrigger() {
  requireEditor_();
  Logger.log('Removed %s trigger(s).', purgeRemoveTriggers_());
}

function purgeRemoveTriggers_() {
  var removed = 0;
  ScriptApp.getProjectTriggers().forEach(function (trigger) {
    if (trigger.getHandlerFunction() === 'purgeRun') {
      ScriptApp.deleteTrigger(trigger);
      removed++;
    }
  });
  return removed;
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

/**
 * The dispensary this console issues codes for and whose banner it edits.
 *
 * A Script Property, set to `barc-vashi` today. The id must be lower-case letters, digits and dashes:
 * it is part of a database path and of the control queue key the sender matches on.
 */
function dispensaryId_() {
  var id = property_('DISPENSARY_ID');
  if (!/^[a-z0-9-]+$/.test(id)) {
    throw new Error('DISPENSARY_ID "' + id + '" must be lower-case letters, digits and dashes only.');
  }
  return id;
}

function dispensaryInfoPath_(id) {
  return '/dispensaries/' + id + '/info.json';
}

/** The banner as stored, plus the plain lines it falls back to when no banner is set. */
function getBanner() {
  requireEditor_();
  var info = firebase_('get', dispensaryInfoPath_(dispensaryId_())) || {};
  return { html: info.html || '', heading: info.heading || '', lines: info.lines || '' };
}

/**
 * Stores the banner. Sanitised here as well as in the page, and again on the phone.
 *
 * Written with PATCH so heading and lines survive: they are the fallback shown when there is no
 * banner, and replacing the info node wholesale would delete them.
 */
function saveBanner(html) {
  var by = requireEditor_();
  var clean = sanitiseBanner_(html);
  if (clean.replace(/<[^>]*>/g, '').replace(/&nbsp;/g, ' ').trim() === '') {
    throw new Error('The banner is empty. Use Remove if that is what you meant.');
  }
  var bytes = utf8Length_(clean);
  if (bytes > BANNER_MAX_BYTES) {
    throw new Error('That banner is too long to send to phones (' + bytes + ' of ' + BANNER_MAX_BYTES +
                    ' allowed). Shorten the text or remove some formatting, then save again.');
  }
  var id = dispensaryId_();
  var at = Date.now();
  firebase_('patch', dispensaryInfoPath_(id), { html: clean, htmlUpdated: at });
  // Queued after the database write, never before: a phone that opens the app on seeing the change
  // must find the new banner there. The drain reads it again when it broadcasts, so a second save
  // inside the same minute sends the second banner. Keyed by dispensary, so each has its own entry.
  queueControl_('banner_' + id, { dispensary: id, at: at, by: by });
  return getBanner();
}

/** Removes the banner, so the app falls back to the plain heading and lines. */
function removeBanner() {
  var by = requireEditor_();
  var id = dispensaryId_();
  var at = Date.now();
  // htmlUpdated is stamped rather than cleared, so a phone can tell a removed banner from one that was
  // never set, and ignore an older banner arriving after the removal.
  firebase_('patch', dispensaryInfoPath_(id), { html: null, htmlUpdated: at });
  queueControl_('banner_' + id, { dispensary: id, at: at, by: by });
  return getBanner();
}

// ---------------------------------------------------------------- One-off: move to dispensaries

/**
 * The update that gives every existing code its dispensary. Pure, so it can be tested.
 *
 * Multi-path keys, one per code: updating `CODE/dispensary` changes that one field and leaves usedBy,
 * activatedAt, revoked and note exactly as they are. Never an import or a PUT on /codes, either of
 * which replaces every code and the claims on them (SYSTEM.md 5.6). A code that already names a
 * dispensary is left out, so a second run changes nothing.
 */
function dispensaryBackfill_(codes, id) {
  var patch = {};
  for (var code in codes) {
    if (!codes.hasOwnProperty(code)) continue;
    var node = codes[code];
    if (!node || typeof node !== 'object' || node.dispensary) continue;
    patch[code + '/dispensary'] = id;
  }
  return patch;
}

/**
 * Moves the live database to the dispensary shape. Run once from the editor, dry run first:
 *
 *   migrateToDispensariesDryRun()   logs what would change and writes nothing
 *   migrateToDispensaries()         makes the change
 *
 * BEFORE RUNNING
 *   1. Import docs/dispensary-barc-vashi.json at /dispensaries/barc-vashi -- select that node, never
 *      the root. Its `info` may stay: the live /info copied below replaces it.
 *   2. Set DISPENSARY_ID = barc-vashi in this project's Script Properties.
 *   3. Run it when nobody is using the console. It writes only to codes it has just read, but a code
 *      deleted in the seconds between would come back as a node holding nothing but `dispensary`.
 *
 * WHAT IT DOES
 *   - Copies /info (the live banner and timings) into /dispensaries/{id}/info. After step 4 of the
 *     runbook deletes /info, a second run has nothing to copy.
 *   - Gives every code without one `dispensary: {id}`.
 *
 * AFTERWARDS
 *   Deploy database.rules.json, then delete /info and /status in the data viewer (SYSTEM.md §10).
 *   Delete this section once the move is done; nothing else calls it.
 */
function migrateToDispensaries() {
  return migrateToDispensaries_(false);
}

function migrateToDispensariesDryRun() {
  return migrateToDispensaries_(true);
}

function migrateToDispensaries_(dryRun) {
  requireEditor_();
  var id = dispensaryId_();
  var prefix = dryRun ? 'DRY RUN, nothing written. ' : '';

  var record = firebase_('get', '/dispensaries/' + id + '.json');
  if (!record || !record.topics) {
    throw new Error('/dispensaries/' + id + ' has no topics yet. Import docs/dispensary-barc-vashi.json ' +
                    'at that node first -- select the node, never the root.');
  }

  // The live banner and timings win over the seed's placeholders. Only fields /info actually has are
  // copied, so an absent html does not wipe one the dispensary already holds.
  var info = firebase_('get', '/info.json');
  var infoPatch = {};
  ['heading', 'lines', 'html', 'htmlUpdated'].forEach(function (field) {
    if (info && info[field] !== undefined && info[field] !== null) infoPatch[field] = info[field];
  });
  var infoFields = Object.keys(infoPatch);

  var codes = firebase_('get', '/codes.json') || {};
  var patch = dispensaryBackfill_(codes, id);
  var keys = Object.keys(patch);

  if (!dryRun) {
    if (infoFields.length) firebase_('patch', '/dispensaries/' + id + '/info.json', infoPatch);
    if (keys.length) firebase_('patch', '/codes.json', patch);
  }

  var summary = {
    dispensary: id,
    topics: Object.keys(record.topics).length,
    infoFieldsCopied: infoFields,
    codesTotal: Object.keys(codes).length,
    codesGivenDispensary: keys.length,
    codesAlreadyHadOne: Object.keys(codes).length - keys.length
  };
  Logger.log('%s%s', prefix, JSON.stringify(summary, null, 2));
  if (keys.length) Logger.log('%sFirst few: %s', prefix, keys.slice(0, 5).join(', '));
  Logger.log(dryRun
    ? 'Looks right? Run migrateToDispensaries().'
    : 'Done. Next: deploy database.rules.json, then delete /info and /status in the data viewer.');
  return summary;
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
  var rows = listCodes().codes;
  Logger.log('version=%s codes=%s printing=%s', SCRIPT_VERSION, rows.length, printingEnabled_());
  return rows.length;
}
