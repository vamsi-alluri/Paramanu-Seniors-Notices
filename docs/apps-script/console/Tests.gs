/**
 * Tests for the console's pure helpers, runnable from the Apps Script editor.
 *
 * Only the pure logic is covered. Everything else in Code.gs talks to Realtime Database with a
 * service account, and a mock of that would test the mock rather than the thing that breaks. The
 * timeline is here because it renders the record the NGO is meant to trust without asking a
 * developer, and "it looked right" is not a way to verify an ordering.
 *
 * Run `runConsoleTests` and read the execution log. Nothing here touches the database.
 */

var TC_RESULTS = [];

function tc_ok_(name, condition, detail) {
  TC_RESULTS.push({ name: name, pass: !!condition, detail: condition ? (detail || '') : ('FAILED ' + (detail || '')) });
}

function tc_eq_(name, actual, expected) {
  var pass = String(actual) === String(expected);
  tc_ok_(name, pass, pass ? String(actual) : ('expected ' + expected + ', got ' + actual));
}

/** Asserts that [fn] throws, and that the message mentions [expect]. */
function tc_throws_(name, fn, expect) {
  try {
    fn();
    tc_ok_(name, false, 'did not throw');
  } catch (e) {
    var message = String(e && e.message ? e.message : e);
    tc_ok_(name, !expect || message.indexOf(expect) >= 0, message);
  }
}

// ---------------------------------------------------------------- auditTimeline_

function tc_auditTimeline_() {
  var entries = {
    k1: { at: 300, by: 'ravi@x.org', event: 'revoked' },
    k2: { at: 100, by: 'asha@x.org', event: 'issued' }
  };

  var plain = auditTimeline_(entries, {});
  tc_eq_('timeline sorts ascending by at', plain[0].event + ',' + plain[1].event, 'issued,revoked');
  tc_eq_('timeline keeps who', plain[1].by, 'ravi@x.org');

  // A claim sits between the two console actions and is synthesised, not stored: the phone cannot
  // write to /audit and must not be able to.
  var withClaim = auditTimeline_(entries, { activatedAt: 200 });
  tc_eq_('claim is interleaved', withClaim.length, 3);
  tc_eq_('claim lands in the middle', withClaim[1].event, 'claimed');
  tc_eq_('claim has no email', withClaim[1].by, '');

  tc_eq_('no claim when never activated', auditTimeline_(entries, {}).length, 2);

  // A code with no history yet must not throw: every code issued before this feature existed is
  // in exactly that state, and they are not backfilled.
  tc_eq_('empty audit is empty', auditTimeline_(null, {}).length, 0);
  tc_eq_('claim alone still renders', auditTimeline_(null, { activatedAt: 50 })[0].event, 'claimed');

  // Note text rides along on the entry so the table can show what it was changed to.
  var noted = auditTimeline_({ k: { at: 10, by: 'a@x.org', event: 'note', to: 'Mrs Rao, ward 3' } }, {});
  tc_eq_('note carries its text', noted[0].to, 'Mrs Rao, ward 3');
}

// ---------------------------------------------------------------- audit_ validation

function tc_auditEvents_() {
  tc_eq_('the event set is closed', AUDIT_EVENTS.length, 5);
  tc_ok_('claimed is not a stored event', AUDIT_EVENTS.indexOf('claimed') < 0, 'synthesised only');

  // The guard runs before any network call, so this throws without touching the database.
  tc_throws_('audit_ refuses an unknown event', function () {
    audit_('A1B2C3D4', 'deleted', 'a@x.org');
  }, 'Unknown audit event');
}

// ---------------------------------------------------------------- generateCode_

/** The longest run of one repeated character in [s]. */
function tc_longestRun_(s) {
  var best = 1;
  var run = 1;
  for (var i = 1; i < s.length; i++) {
    run = (s.charAt(i) === s.charAt(i - 1)) ? run + 1 : 1;
    if (run > best) best = run;
  }
  return best;
}

function tc_generateCode_() {
  // Generated codes must stay valid, stay inside the alphabet, and always carry the run that makes
  // them sayable. Run over many samples because every one of these is a property of the whole
  // output, not of one lucky draw.
  var shortestRun = 99;
  var allValid = true;
  var allInAlphabet = true;
  var allRightLength = true;
  var seen = {};

  for (var i = 0; i < 500; i++) {
    var code = generateCode_();
    seen[code] = true;

    if (code.length !== PAYLOAD_LENGTH + 1) allRightLength = false;
    if (!isValidCode(code)) allValid = false;
    for (var j = 0; j < code.length; j++) {
      if (ALPHABET.indexOf(code.charAt(j)) < 0) allInAlphabet = false;
    }

    // The run is a property of the payload; the check character is computed from it and may
    // happen to extend or break the run, which does not matter.
    var run = tc_longestRun_(code.substring(0, PAYLOAD_LENGTH));
    if (run < shortestRun) shortestRun = run;
  }

  tc_ok_('every generated code is the right length', allRightLength);
  tc_ok_('every generated code passes isValidCode', allValid);
  tc_ok_('every generated code stays inside the alphabet', allInAlphabet);
  tc_ok_('every payload carries a run of at least ' + REPEAT_RUN, shortestRun >= REPEAT_RUN, 'shortest run ' + shortestRun);

  // A generator that had collapsed to a handful of outputs would still pass everything above.
  var distinct = Object.keys(seen).length;
  tc_ok_('the generator is still varied', distinct > 490, distinct + ' distinct of 500');
}

function runConsoleTests() {
  TC_RESULTS = [];
  tc_auditTimeline_();
  tc_auditEvents_();
  tc_generateCode_();

  var failed = 0;
  for (var i = 0; i < TC_RESULTS.length; i++) {
    var r = TC_RESULTS[i];
    if (!r.pass) failed++;
    Logger.log((r.pass ? 'ok   ' : 'FAIL ') + r.name + (r.detail ? '  -- ' + r.detail : ''));
  }
  Logger.log(failed === 0 ? ('All ' + TC_RESULTS.length + ' passed.') : (failed + ' of ' + TC_RESULTS.length + ' FAILED.'));
}
