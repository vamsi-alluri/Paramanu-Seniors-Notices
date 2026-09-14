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
 *    drop the PIN from every send, and nothing would look wrong.
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

/** Which of a dispensary's topics a notice goes to. Pure: no database. */
function t_noticeTopic_() {
  t_eq_('the only topic is the one', noticeTopic_({ notices: { topic: 'notices-v1', order: 1 } }), 'notices-v1');
  t_eq_('the lowest order wins', noticeTopic_({ b: { topic: 'b-v1', order: 2 }, a: { topic: 'a-v1', order: 1 } }), 'a-v1');
  t_eq_('an entry without an order goes last', noticeTopic_({ a: { topic: 'a-v1' }, b: { topic: 'b-v1', order: 9 } }), 'b-v1');
  // Ties go by key, so the answer never depends on the order the database returns entries in.
  t_eq_('a tie goes by key', noticeTopic_({ z: { topic: 'z-v1', order: 1 }, a: { topic: 'a-v1', order: 1 } }), 'a-v1');
  t_eq_('an entry without a topic name is skipped', noticeTopic_({ a: { order: 0 }, b: { topic: 'b-v1', order: 5 } }), 'b-v1');
  t_ok_('no topics at all is null', noticeTopic_({}) === null);
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
  // Compared, never printed: t_eq_ writes the value into the log, and this value is a live credential.
  t_ok_('accessToken_ caches', second === first, 'same token returned');
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
 * would silently drop the PIN from every send, and nothing would look wrong.
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

// ---------------------------------------------------------------- sending

function t_sendNotice_(created) {
  var pin = property_('STAFF_PIN');

  t_throws_('sendNotice refuses a wrong PIN', function () { sendNotice('x', 'y', 'wrong'); }, 'not correct');
  CacheService.getScriptCache().remove('pin_failures');

  t_throws_('sendNotice requires a title', function () { sendNotice('', 'body', pin); }, 'title is required');

  var longTitle = new Array(200).join('x');
  t_throws_('sendNotice caps the title', function () { sendNotice(longTitle, '', pin); }, 'too long');

  var result = sendNotice('[selftest] notice', 'Sent by runAllTests. Ignore.', pin);
  created.push(result.logId);
  t_ok_('sendNotice returns a logId', /^[0-9]+$/.test(result.logId), result.logId);
  // Against the real record, so a typo in /dispensaries fails here rather than at the next real send.
  t_eq_('sendNotice goes to the dispensary topic', result.topic, dispensaryTopic_());

  var stored = firebase_('get', '/sent/' + result.logId + '.json');
  t_ok_('sendNotice records the send', stored && stored.title === '[selftest] notice');
  t_eq_('sendNotice records the topic', stored.topic, result.topic);
  t_eq_('sendNotice records the dispensary', stored.dispensary, dispensaryId_());
  t_ok_('sendNotice records no error', !stored.error, stored.error || '');
  t_ok_('sendNotice captured the FCM name', !!stored.fcmName, stored.fcmName || 'missing');

  // Who sent it. Compared against requireEditor_ rather than a literal so the test does not have
  // to know which allowlisted person is running the suite.
  t_ok_('sendNotice records sentBy', !!stored.sentBy, stored.sentBy || 'missing');
  t_eq_('sentBy is the caller', stored.sentBy, requireEditor_());
}

// ---------------------------------------------------------------- routing

function t_doGet_() {
  t_ok_('doGet serves the compose page', doGet().getContent().indexOf('Send a notice') > 0);
  // The daily open and closed route is gone. A bookmarked ?status= link opens the compose page.
  t_ok_('doGet ignores a leftover status link',
    doGet({ parameter: { status: 'open' } }).getContent().indexOf('Send a notice') > 0);
}

// ---------------------------------------------------------------- control messages

function t_utf8Length_() {
  t_eq_('utf8Length_ ascii', utf8Length_('Closed Friday'), 13);
  t_eq_('utf8Length_ empty', utf8Length_(''), 0);
  t_eq_('utf8Length_ null is zero', utf8Length_(null), 0);
  // The case this exists for: a character count passes a Marathi banner that FCM then refuses.
  t_eq_('utf8Length_ devanagari is three bytes a character', utf8Length_('बंद'), 9);
  t_eq_('utf8Length_ emoji is four bytes', utf8Length_('🙏'), 4);
}

function t_controlRequest_() {
  var request = controlRequest_('control-v1', { type: 'banner', html: '', at: 123 });
  t_eq_('control request goes to the topic', request.message.topic, 'control-v1');
  t_eq_('control request is high priority', request.message.android.priority, 'high');
  t_ok_('control request has no notification block', !request.message.notification);
  t_ok_('control values are strings', typeof request.message.data.at === 'string');
  // Empty is how a removed banner is said, so it must survive the envelope.
  t_eq_('an empty html is kept', request.message.data.html, '');
  t_ok_('a null value is omitted',
    !('code' in controlRequest_('x', { type: 'revoke', code: null }).message.data));
}

/** The decision the drain makes for each entry. Pure: no database, no FCM. */
function t_controlDecide_() {
  var held = { issued: 1, usedBy: 'uid' };
  var revoked = { issued: 1, usedBy: 'uid', revoked: true };
  var revoke = { type: 'revoke', code: 'A1B2C3D4', at: 50, by: 'a@x.org' };
  var resume = { type: 'resume', code: 'A1B2C3D4', at: 60, by: 'a@x.org' };

  t_eq_('a revoke for a revoked code is sent', controlDecide_('code_A1B2C3D4', revoke, revoked, null).send.type, 'revoke');
  t_eq_('the revoke carries its stamp', controlDecide_('code_A1B2C3D4', revoke, revoked, null).send.at, 50);
  t_ok_('a revoke for a restored code is dropped', !!controlDecide_('code_A1B2C3D4', revoke, held, null).drop);
  t_ok_('a revoke for a deleted code is dropped', !!controlDecide_('code_A1B2C3D4', revoke, null, null).drop);

  t_eq_('a resume for a held, live code is sent', controlDecide_('code_A1B2C3D4', resume, held, null).send.type, 'resume');
  t_ok_('a resume for a code revoked again is dropped', !!controlDecide_('code_A1B2C3D4', resume, revoked, null).drop);
  t_ok_('a resume for a code nobody holds is dropped', !!controlDecide_('code_A1B2C3D4', resume, { issued: 1 }, null).drop);

  t_ok_('a malformed key is dropped', !!controlDecide_('code_a1b2', revoke, revoked, null).drop);
  t_ok_('an unknown type is dropped', !!controlDecide_('code_A1B2C3D4', { type: 'ping', at: 1 }, revoked, null).drop);
  t_ok_('an unstamped entry is dropped', !!controlDecide_('code_A1B2C3D4', { type: 'revoke' }, revoked, null).drop);

  var banner = controlDecide_('banner_barc-vashi', { at: 70 }, null, { html: '<b>Closed</b>', htmlUpdated: 80 });
  t_eq_('a banner sends what the dispensary holds now', banner.send.html, '<b>Closed</b>');
  t_eq_('a banner says whose it is', banner.send.dispensary, 'barc-vashi');
  t_eq_('a banner is stamped with the save', banner.send.at, 80);
  t_eq_('a removed banner is sent as empty html',
    controlDecide_('banner_barc-vashi', { at: 90 }, null, { htmlUpdated: 90 }).send.html, '');
  t_ok_('an oversized banner is dropped rather than retried forever',
    !!controlDecide_('banner_barc-vashi', { at: 1 }, null, { html: new Array(BANNER_MAX_BYTES + 2).join('x'), htmlUpdated: 1 }).drop);
  t_ok_('a banner key that names no dispensary is dropped',
    !!controlDecide_('banner', { at: 1 }, null, { htmlUpdated: 1 }).drop);
}

function t_pushControl_() {
  // TOPIC_OVERRIDE is TEST_TOPIC for the whole suite, so this reaches no real phone.
  var name = pushControl_({ type: 'revoke', code: 'ZZZZZZZZ', at: Date.now() });
  t_ok_('pushControl_ returns an FCM name', String(name).indexOf('projects/') === 0, String(name));
}

/**
 * Drains a seeded queue against TEST_TOPIC.
 *
 * Uses codes under /codes that this test creates and removes, because the drain re-reads each code
 * before broadcasting -- that re-read is the guard that stops a stale revoke cutting off whoever
 * claims a released code next, so a test that bypassed it would be testing nothing.
 *
 * Like the drain itself, this broadcasts anything genuinely queued at the moment it runs -- to
 * TEST_TOPIC, where no phone hears it -- and clears it. Run it when nobody is using the console.
 */
function t_controlDrain_() {
  var live = 'ZZZZZZZZ';       // disabled: should be broadcast and cleared
  var stale = 'YYYYYYYY';      // enabled since: should be dropped without sending
  var junk = 'code_not-a-code';
  var now = Date.now();

  try {
    firebase_('put', '/codes/' + live + '.json', { issued: now, usedBy: 'uid-selftest', revoked: true });
    firebase_('put', '/codes/' + stale + '.json', { issued: now, usedBy: 'uid-selftest' });
    firebase_('put', '/controlQueue/code_' + live + '.json', { type: 'revoke', code: live, at: now, by: 'test@example.org' });
    firebase_('put', '/controlQueue/code_' + stale + '.json', { type: 'revoke', code: stale, at: now, by: 'test@example.org' });
    firebase_('put', '/controlQueue/' + junk + '.json', { type: 'revoke', at: now, by: 'test@example.org' });

    var result = controlDrain_();

    t_eq_('drain broadcasts the disabled one', result.pushed, 1);
    t_eq_('drain skips the enabled and the malformed', result.skipped, 2);
    t_eq_('drain reports no failures', result.failed, 0);

    t_ok_('a broadcast entry is cleared', firebase_('get', '/controlQueue/code_' + live + '.json') === null);
    t_ok_('an enabled code is dropped without sending', firebase_('get', '/controlQueue/code_' + stale + '.json') === null);
    t_ok_('a malformed key is discarded', firebase_('get', '/controlQueue/' + junk + '.json') === null);

    // The guard that keeps a restore queued while its revoke was being sent: an entry replaced
    // since it was read must be left for the next run.
    firebase_('put', '/controlQueue/code_' + live + '.json', { type: 'resume', code: live, at: now + 5, by: 'test@example.org' });
    controlClear_({ key: 'code_' + live, path: '/controlQueue/code_' + live, entry: { at: now } });
    t_ok_('an entry replaced mid-send is not cleared', firebase_('get', '/controlQueue/code_' + live + '.json') !== null);
    firebase_('delete', '/controlQueue/code_' + live + '.json');

    // An empty queue must cost nothing and report nothing.
    t_eq_('an empty queue pushes nothing', controlDrain_().pushed, 0);
  } finally {
    [live, stale].forEach(function (code) {
      firebase_('delete', '/codes/' + code + '.json');
      firebase_('delete', '/controlQueue/code_' + code + '.json');
    });
    firebase_('delete', '/controlQueue/' + junk + '.json');
  }
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
    t_noticeTopic_();
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
    t_sendNotice_(created);
    t_utf8Length_();
    t_controlRequest_();
    t_controlDecide_();
    t_pushControl_();
    t_controlDrain_();
    t_doGet_();
    t_testConnection_();
  } catch (e) {
    T_RESULTS.push({ name: 'SUITE ABORTED', pass: false, detail: String(e) });
  } finally {
    TOPIC_OVERRIDE = previousOverride;
    CacheService.getScriptCache().remove('pin_failures');

    // Sent rows are removed so the sender's "recently sent" list is not full of test traffic.
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
