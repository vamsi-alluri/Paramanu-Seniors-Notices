/**
 * End-to-end tests for the sender, runnable from the Apps Script editor.
 *
 * Run `runAllTests` and read the execution log. Every function in Code.gs is exercised against the
 * real database and the real FCM endpoint.
 *
 * WHAT THIS TOUCHES, AND HOW IT CLEANS UP
 *
 * These are not isolated unit tests; there is no test double for Realtime Database or FCM, and
 * pretending otherwise would test the mock rather than the thing that breaks. So:
 *
 *  - Rows are written under /_test and to /sent, and deleted again in a finally block.
 *  - The PIN failure counter is exercised and then cleared, because leaving it set would lock the
 *    real PIN out for fifteen minutes.
 *  - The REQUIRE_STAFF_PIN property is flipped and put back. Left on 'false' it would silently
 *    drop the PIN from every send, including the QR path, and nothing would look wrong.
 *  - /status is emptied, rewritten and restored. Left holding a test string, the next real QR scan
 *    would send that string to every phone. If the restore itself fails the log says so; check
 *    /status by hand before the next scan.
 *  - Sends go to TEST_TOPIC, a topic no phone subscribes to. The credentials, the payload and the
 *    FCM response are all real; only the audience is empty.
 *
 * To send to the real audience instead, set SEND_FOR_REAL to true. That reaches every activated
 * phone and cannot be undone, so it is off by default and should be turned back off afterwards.
 */

var TEST_TOPIC = 'zz-selftest-no-subscribers';
var SEND_FOR_REAL = false;

var T_RESULTS = [];

function t_ok_(name, condition, detail) {
  T_RESULTS.push({ name: name, pass: !!condition, detail: condition ? (detail || '') : ('FAILED ' + (detail || '')) });
}

function t_eq_(name, actual, expected) {
  var pass = String(actual) === String(expected);
  t_ok_(name, pass, pass ? String(actual) : ('expected ' + expected + ', got ' + actual));
}

/** Asserts that [fn] throws, and that the message mentions [expect]. */
function t_throws_(name, fn, expect) {
  try {
    fn();
    t_ok_(name, false, 'did not throw');
  } catch (e) {
    var message = String(e && e.message ? e.message : e);
    t_ok_(name, !expect || message.indexOf(expect) >= 0, message);
  }
}

// ---------------------------------------------------------------- pure logic

function t_statusMessage_() {
  t_eq_('statusMessage_ open', statusMessage_('open').title, 'The dispensary is open today');
  t_eq_('statusMessage_ closed', statusMessage_('closed').title, 'The dispensary is closed now');
  // The bug that broke the QR: the value arrived carrying its quote characters.
  t_ok_('statusMessage_ tolerates quotes', statusMessage_('"closed"') !== null);
  t_ok_('statusMessage_ tolerates whitespace', statusMessage_('  open ') !== null);
  t_ok_('statusMessage_ tolerates case', statusMessage_('CLOSED') !== null);
  t_ok_('statusMessage_ rejects unknown', statusMessage_('ajar') === null);
  t_ok_('statusMessage_ rejects empty', statusMessage_('') === null);
}

function t_property_() {
  t_ok_('property_ reads DATABASE_URL', String(property_('DATABASE_URL')).indexOf('http') === 0);
  t_throws_('property_ throws when missing', function () { property_('NO_SUCH_PROPERTY_XYZ'); }, 'not set');
}

function t_databaseUrl_() {
  var url = databaseUrl_();
  t_ok_('databaseUrl_ has no trailing slash', url.charAt(url.length - 1) !== '/', url);
  t_ok_('databaseUrl_ is the RTDB host', url.indexOf('firebasedatabase.app') > 0 || url.indexOf('firebaseio.com') > 0, url);
}

// ---------------------------------------------------------------- credentials and database

function t_accessToken_() {
  var first = accessToken_();
  t_ok_('accessToken_ returns a token', typeof first === 'string' && first.length > 20);
  var second = accessToken_();
  t_eq_('accessToken_ caches', second, first);
}

function t_firebase_() {
  var path = '/_test/roundtrip.json';
  var payload = { value: 'hello', when: Date.now() };

  firebase_('put', path, payload);
  var read = firebase_('get', path);
  t_eq_('firebase_ put then get', read.value, 'hello');

  firebase_('patch', path, { value: 'changed' });
  t_eq_('firebase_ patch', firebase_('get', path).value, 'changed');

  firebase_('delete', path);
  t_ok_('firebase_ delete', firebase_('get', path) === null);

  t_throws_('firebase_ surfaces a bad path', function () { firebase_('get', '/_test/nope.json?orderBy=broken'); });
}

function t_nextLogId_() {
  var a = nextLogId_();
  var b = nextLogId_();
  t_ok_('nextLogId_ is numeric', /^[0-9]+$/.test(a), a);
  // The collision bug from the original Notifier sender: two ids inside one millisecond.
  t_ok_('nextLogId_ is unique', a !== b, a + ' vs ' + b);
  t_ok_('nextLogId_ is ordered', Number(b) >= Number(a));
}

function t_listTemplates_() {
  var rows = listTemplates();
  t_ok_('listTemplates returns an array', rows && rows.length >= 0);
  for (var i = 0; i < rows.length; i++) {
    t_ok_('template ' + i + ' has a label', !!rows[i].label);
  }
}

function t_listSent_() {
  var rows = listSent(5);
  t_ok_('listSent returns an array', rows && rows.length >= 0, rows.length + ' rows');
  t_ok_('listSent respects the limit', rows.length <= 5);
}

// ---------------------------------------------------------------- who may use this

function t_requireEditor_() {
  var email = null;
  var threw = false;
  try { email = requireEditor_(); } catch (e) { threw = true; t_ok_('requireEditor_ allows the person running the tests', false, e.message); }

  if (!threw) {
    t_ok_('requireEditor_ allows the person running the tests', true, email);
    t_ok_('requireEditor_ returns a normalised address', email === email.trim().toLowerCase(), email);
    t_ok_('isEditor_ agrees', isEditor_() === true);

    // The allowlist is the whole lock, so confirm the running account is actually on it rather
    // than being let through by an empty list or a missing check.
    var list = String(PropertiesService.getScriptProperties().getProperty('ALLOWED_EDITORS') || '');
    t_ok_('ALLOWED_EDITORS is set', list.length > 0, list ? (list.split(',').length + ' entries') : 'EMPTY');
    t_ok_('the running account is on the list', list.toLowerCase().indexOf(email) >= 0);
  }

  // The refusal page must not reflect an attacker-supplied string back as markup.
  var page = refusalPage_('<script>alert(1)</script>').getContent();
  t_ok_('refusalPage_ escapes markup', page.indexOf('<script>alert') < 0 && page.indexOf('&lt;script&gt;') > 0);
}

// ---------------------------------------------------------------- the PIN

function t_checkPin_() {
  var cache = CacheService.getScriptCache();
  cache.remove('pin_failures');

  var pin = property_('STAFF_PIN');
  var threw = false;
  try { checkPin_(pin); } catch (e) { threw = true; }
  t_ok_('checkPin_ accepts the real PIN', !threw);

  t_throws_('checkPin_ rejects a wrong PIN', function () { checkPin_('definitely-not-the-pin'); }, 'not correct');
  t_throws_('checkPin_ rejects an empty PIN', function () { checkPin_(''); }, 'not correct');

  // Exhaust the budget, confirm the lockout, then clear it. Leaving it set would lock the counter
  // staff out of sending for fifteen minutes.
  for (var i = 0; i < MAX_PIN_FAILURES + 1; i++) {
    try { checkPin_('wrong'); } catch (e) { /* expected */ }
  }
  t_throws_('checkPin_ locks out after repeated failures', function () { checkPin_(pin); }, 'Too many');

  cache.remove('pin_failures');
  var recovered = true;
  try { checkPin_(pin); } catch (e) { recovered = false; }
  t_ok_('checkPin_ recovers once the lockout is cleared', recovered);
}

/**
 * The PIN switch.
 *
 * Saves and restores REQUIRE_STAFF_PIN in a finally block. Leaving it on 'false' after a failed run
 * would silently drop the PIN from every send, including the QR path, and nothing would look wrong.
 */
function t_pinSwitch_() {
  var props = PropertiesService.getScriptProperties();
  var original = props.getProperty('REQUIRE_STAFF_PIN');
  var cache = CacheService.getScriptCache();

  try {
    props.deleteProperty('REQUIRE_STAFF_PIN');
    t_ok_('requiresPin defaults to true when unset', requiresPin() === true);

    props.setProperty('REQUIRE_STAFF_PIN', 'false');
    t_ok_('requiresPin is false when set to false', requiresPin() === false);

    cache.remove('pin_failures');
    var threw = false;
    try { checkPin_('a-completely-wrong-pin'); } catch (e) { threw = true; }
    t_ok_('checkPin_ waves anything through while the switch is off', !threw);

    // Only the exact word disables it. Anything ambiguous must keep the lock on, because the
    // failure direction matters: a typo in this property should never silently unlock sending.
    props.setProperty('REQUIRE_STAFF_PIN', ' FALSE ');
    t_ok_('requiresPin tolerates spacing and capitals', requiresPin() === false);

    ['true', '0', 'no', 'off', 'nope', ''].forEach(function (value) {
      props.setProperty('REQUIRE_STAFF_PIN', value);
      t_ok_('requiresPin stays on for ' + JSON.stringify(value), requiresPin() === true);
    });

    props.setProperty('REQUIRE_STAFF_PIN', 'true');
    t_throws_('checkPin_ rejects again once the switch is back on', function () { checkPin_('wrong'); }, 'not correct');
  } finally {
    if (original === null || original === undefined) props.deleteProperty('REQUIRE_STAFF_PIN');
    else props.setProperty('REQUIRE_STAFF_PIN', original);
    cache.remove('pin_failures');
  }
}

/**
 * The editable open/closed wording.
 *
 * Writes to /status directly rather than through the console, which lives in the other project.
 * The original contents are saved and put back in a finally block: leaving a test string there
 * would mean the next real QR scan sends it to every phone.
 */
function t_statusWording_() {
  var original = null;
  try {
    original = firebase_('get', '/status.json');
  } catch (e) {
    t_ok_('status wording: could read /status', false, e.message);
    return;
  }

  try {
    // With nothing stored, the shipped wording is what goes out.
    firebase_('delete', '/status.json');
    t_eq_('statusMessage_ falls back to the shipped title', statusMessage_('open').title, defaultStatusMessage_('open').title);
    t_eq_('statusMessage_ falls back to the shipped body', statusMessage_('closed').body, defaultStatusMessage_('closed').body);

    // Once edited in the console, the new wording is what goes out -- with no redeploy.
    firebase_('put', '/status/open.json', { title: '[selftest] open title', body: '[selftest] open body', updated: Date.now() });
    t_eq_('statusMessage_ prefers the stored title', statusMessage_('open').title, '[selftest] open title');
    t_eq_('statusMessage_ prefers the stored body', statusMessage_('open').body, '[selftest] open body');

    // The normalisation that fixed the quoted-value bug must still apply on the stored path.
    t_eq_('stored wording survives a quoted argument', statusMessage_('"open"').title, '[selftest] open title');
    t_eq_('stored wording survives casing and spacing', statusMessage_('  OPEN ').title, '[selftest] open title');

    // Editing one must not disturb the other.
    t_eq_('the other status is untouched', statusMessage_('closed').title, defaultStatusMessage_('closed').title);

    // A half-written node -- body but no title -- must not produce a titleless notification.
    firebase_('put', '/status/closed.json', { body: 'orphan body with no title' });
    t_eq_('a node without a title falls back', statusMessage_('closed').title, defaultStatusMessage_('closed').title);

    // An empty body is a legitimate choice, not a reason to fall back.
    firebase_('put', '/status/open.json', { title: '[selftest] title only', body: '' });
    t_eq_('an empty stored body is respected', statusMessage_('open').body, '');

    t_ok_('unknown statuses are still rejected', statusMessage_('ajar') === null);
  } finally {
    try {
      firebase_('delete', '/status.json');
      if (original) firebase_('put', '/status.json', original);
    } catch (e) {
      Logger.log('WARNING: could not restore /status -- check it by hand: %s', e.message);
    }
  }
}

// ---------------------------------------------------------------- sending

function t_sendNotice_(created) {
  var pin = property_('STAFF_PIN');

  t_throws_('sendNotice refuses a wrong PIN', function () { sendNotice('x', 'y', 'NOTICES', 'wrong'); }, 'not correct');
  CacheService.getScriptCache().remove('pin_failures');

  t_throws_('sendNotice requires a title', function () { sendNotice('', 'body', 'NOTICES', pin); }, 'title is required');

  var longTitle = new Array(200).join('x');
  t_throws_('sendNotice caps the title', function () { sendNotice(longTitle, '', 'NOTICES', pin); }, 'too long');

  var result = sendNotice('[selftest] notice', 'Sent by runAllTests. Ignore.', 'NOTICES', pin);
  created.push(result.logId);
  t_ok_('sendNotice returns a logId', /^[0-9]+$/.test(result.logId), result.logId);
  t_eq_('sendNotice tags the category', result.category, 'NOTICES');

  var stored = firebase_('get', '/sent/' + result.logId + '.json');
  t_ok_('sendNotice records the send', stored && stored.title === '[selftest] notice');
  t_ok_('sendNotice records no error', !stored.error, stored.error || '');
  t_ok_('sendNotice captured the FCM name', !!stored.fcmName, stored.fcmName || 'missing');

  // Who sent it. Compared against requireEditor_ rather than a literal so the test does not have
  // to know which allowlisted person is running the suite.
  t_ok_('sendNotice records sentBy', !!stored.sentBy, stored.sentBy || 'missing');
  t_eq_('sentBy is the caller', stored.sentBy, requireEditor_());
}

function t_sendStatus_(created) {
  var pin = property_('STAFF_PIN');

  t_throws_('sendStatus refuses a wrong PIN', function () { sendStatus('open', 'wrong'); }, 'not correct');
  CacheService.getScriptCache().remove('pin_failures');

  t_throws_('sendStatus rejects an unknown status', function () { sendStatus('ajar', pin); }, 'Unknown status');

  var result = sendStatus('open', pin);
  created.push(result.logId);
  t_eq_('sendStatus sends as STATUS', result.category, 'STATUS');
  t_eq_('sendStatus uses the fixed title', result.title, 'The dispensary is open today');

  // The mis-scan guard: the same status again within the window must be refused.
  t_throws_('sendStatus refuses an immediate repeat', function () { sendStatus('open', pin); }, 'already sent');
}

// ---------------------------------------------------------------- routing

function t_doGet_() {
  var open = doGet({ parameter: { status: 'open' } });
  t_ok_('doGet serves the Status page for open', !!open && typeof open.getContent === 'function');
  t_ok_('doGet open page has a PIN field', open.getContent().indexOf('Staff PIN') > 0);
  t_ok_('doGet open page carries the status', open.getContent().indexOf('data-status="open"') > 0);

  var closed = doGet({ parameter: { status: 'closed' } });
  t_ok_('doGet closed page carries the status', closed.getContent().indexOf('data-status="closed"') > 0);

  var main = doGet({ parameter: {} });
  t_ok_('doGet serves the compose page by default', main.getContent().indexOf('Send a notice') > 0);

  var bare = doGet(null);
  t_ok_('doGet survives no event object', !!bare);
}

function t_isValidRevokeCode_() {
  t_ok_('accepts a well-formed code', isValidRevokeCode_('A1B2C3D4'));
  t_ok_('refuses a dashed code', !isValidRevokeCode_('A1B2-C3D4'));
  t_ok_('refuses lowercase', !isValidRevokeCode_('a1b2c3d4'));
  t_ok_('refuses the wrong length', !isValidRevokeCode_('A1B2C3D'));
  t_ok_('refuses nothing at all', !isValidRevokeCode_(''));
  t_ok_('refuses undefined', !isValidRevokeCode_(undefined));
}

function t_pushRevoke_() {
  // TOPIC_OVERRIDE is already TEST_TOPIC for the whole suite, so this reaches no real phone.
  var name = pushRevoke_('ZZZZZZZZ');
  t_ok_('pushRevoke_ returns an FCM name', String(name).indexOf('projects/') === 0, String(name));
}

function t_doPost_() {
  function post(body) {
    return JSON.parse(doPost({ postData: { contents: JSON.stringify(body) } }).getContent());
  }

  var unknown = post({ action: 'nonsense' });
  t_ok_('doPost refuses an unknown action', !unknown.ok, unknown.error || '');
  t_ok_('the refusal names the action', String(unknown.error).indexOf('nonsense') >= 0, unknown.error);

  var malformed = post({ action: 'revoke', code: 'nope' });
  t_ok_('doPost refuses a malformed code', !malformed.ok, malformed.error || '');

  // doPost answers with JSON rather than throwing, so a console that cannot parse a thrown Apps
  // Script error page still learns what happened.
  var empty = JSON.parse(doPost({}).getContent());
  t_ok_('doPost survives an empty body', !empty.ok, empty.error || '');

  var good = post({ action: 'revoke', code: 'ZZZZZZZZ', by: 'someone-else@example.org' });
  t_ok_('doPost pushes a valid revoke', good.ok, good.error || '');
  t_eq_('doPost attributes to the token, not the payload', good.by, requireEditor_());
  t_eq_('a mismatched claim is recorded', good.claimedBy, 'someone-else@example.org');
}

function t_testConnection_() {
  var threw = false;
  try { testConnection(); } catch (e) { threw = true; }
  t_ok_('testConnection runs', !threw);
}

// ---------------------------------------------------------------- runner

function runAllTests() {
  T_RESULTS = [];
  var created = [];
  var previousOverride = TOPIC_OVERRIDE;

  if (!SEND_FOR_REAL) {
    TOPIC_OVERRIDE = TEST_TOPIC;
  }

  try {
    t_statusMessage_();
    t_property_();
    t_databaseUrl_();
    t_accessToken_();
    t_firebase_();
    t_nextLogId_();
    t_listTemplates_();
    t_listSent_();
    t_requireEditor_();
    t_checkPin_();
    t_pinSwitch_();
    t_statusWording_();
    t_sendNotice_(created);
    t_sendStatus_(created);
    t_isValidRevokeCode_();
    t_pushRevoke_();
    t_doPost_();
    t_doGet_();
    t_testConnection_();
  } catch (e) {
    T_RESULTS.push({ name: 'SUITE ABORTED', pass: false, detail: String(e) });
  } finally {
    TOPIC_OVERRIDE = previousOverride;
    CacheService.getScriptCache().remove('pin_failures');

    // Sent rows are removed so the duplicate guard does not refuse a genuine status message later
    // today, and so the console's "recently sent" list is not full of test traffic.
    for (var i = 0; i < created.length; i++) {
      try { firebase_('delete', '/sent/' + created[i] + '.json'); } catch (e2) { /* best effort */ }
    }
    try { firebase_('delete', '/_test.json'); } catch (e3) { /* best effort */ }
  }

  var passed = 0;
  var lines = [];
  for (var j = 0; j < T_RESULTS.length; j++) {
    var r = T_RESULTS[j];
    if (r.pass) passed++;
    lines.push((r.pass ? 'PASS  ' : 'FAIL  ') + r.name + (r.detail ? '   [' + r.detail + ']' : ''));
  }

  var summary = passed + '/' + T_RESULTS.length + ' passed' +
    (SEND_FOR_REAL ? '   (SENT TO THE REAL AUDIENCE)' : '   (sends went to ' + TEST_TOPIC + ')');

  Logger.log(lines.join('\n') + '\n\n' + summary);
  return summary;
}
