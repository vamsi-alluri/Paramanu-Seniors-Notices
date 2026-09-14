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
  tc_eq_('the event set is closed', AUDIT_EVENTS.length, 14);
  tc_ok_('claimed is not a stored event', AUDIT_EVENTS.indexOf('claimed') < 0, 'synthesised only');

  // The guard runs before any network call, so this throws without touching the database.
  // Deliberately a name nothing will ever add: 'deleted' was used here once and quietly stopped
  // testing anything the day deletion was implemented.
  tc_throws_('audit_ refuses an unknown event', function () {
    audit_('A1B2C3D4', 'incinerated', 'a@x.org');
  }, 'Unknown audit event');
}

// ---------------------------------------------------------------- generateCode_

function tc_countRepeatingCharacters_() {
  tc_eq_('nothing repeats in seven distinct characters', countRepeatingCharacters_('ABCDEFG'), 0);
  tc_eq_('one pair is one repeating character', countRepeatingCharacters_('ABCDEFA'), 1);
  tc_eq_('a triple is still one repeating character', countRepeatingCharacters_('AAABCDE'), 1);
  tc_eq_('two pairs are two', countRepeatingCharacters_('ABABCDE'), 2);
  tc_eq_('a pair and a triple are two', countRepeatingCharacters_('AXGXBBB'), 2);
  tc_eq_('an empty payload has none', countRepeatingCharacters_(''), 0);

  // The four codes the rule was drawn from. Pinned so a later "improvement" to the generator
  // cannot quietly go back to producing the two that were rejected at the counter.
  tc_ok_('AXGX-BBBE qualifies', countRepeatingCharacters_('AXGXBBB') >= PAYLOAD_MIN_REPEATS);
  tc_ok_('003C-93VV qualifies', countRepeatingCharacters_('003C93V') >= PAYLOAD_MIN_REPEATS);
  tc_ok_('GGGW-YBHZ does not', countRepeatingCharacters_('GGGWYBH') < PAYLOAD_MIN_REPEATS);
  tc_ok_('EWNN-NMGA does not', countRepeatingCharacters_('EWNNNMG') < PAYLOAD_MIN_REPEATS);
}

function tc_generateCode_() {
  // Generated codes must stay valid, stay inside the alphabet, and always carry the repeats that
  // make them sayable. Run over many samples: each of these is a property of the whole output, not
  // of one lucky draw, and the generator rejection-samples so a bad rule shows up as a shortfall.
  var fewestRepeats = 99;
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

    // Measured on the payload. The check character is derived from it and may add a repeat of its
    // own, which is welcome but must not be what satisfies the rule.
    var repeats = countRepeatingCharacters_(code.substring(0, PAYLOAD_LENGTH));
    if (repeats < fewestRepeats) fewestRepeats = repeats;
  }

  tc_ok_('every generated code is the right length', allRightLength);
  tc_ok_('every generated code passes isValidCode', allValid);
  tc_ok_('every generated code stays inside the alphabet', allInAlphabet);
  tc_ok_('every payload has ' + PAYLOAD_MIN_REPEATS + ' repeating characters',
    fewestRepeats >= PAYLOAD_MIN_REPEATS, 'fewest seen ' + fewestRepeats);

  // A generator that had collapsed to a handful of outputs would still pass everything above.
  var distinct = Object.keys(seen).length;
  tc_ok_('the generator is still varied', distinct > 490, distinct + ' distinct of 500');
}

// ---------------------------------------------------------------- codeIsPrinted_

function tc_codeIsPrinted_() {
  function timeline() {
    var out = [];
    for (var i = 0; i < arguments.length; i++) out.push({ at: i + 1, event: arguments[i], by: 'a@x.org' });
    return out;
  }

  tc_ok_('a code with no history is unprinted', !codeIsPrinted_([]));
  tc_ok_('an undefined timeline is unprinted', !codeIsPrinted_(undefined));
  tc_ok_('issued alone is unprinted', !codeIsPrinted_(timeline('issued')));
  tc_ok_('a printed code is printed', codeIsPrinted_(timeline('issued', 'printed')));

  // Other events must not disturb it: a note change is not a reprint.
  tc_ok_('a note after printing leaves it printed',
    codeIsPrinted_(timeline('issued', 'printed', 'note')));
  tc_ok_('revoking does not unprint',
    codeIsPrinted_(timeline('issued', 'printed', 'revoked')));

  // Unlinking a phone leaves the slip with its holder, so it must not go back on the print sheet.
  tc_ok_('unlinking a phone does not clear it', codeIsPrinted_(timeline('issued', 'printed', 'released')));

  // The purge puts the code back to Unused, for somebody new who needs a fresh slip.
  tc_ok_('the purge clears it',
    !codeIsPrinted_(timeline('issued', 'printed', 'holder-removed', 'holder-purged')));
  tc_ok_('and printing again sets it once more',
    codeIsPrinted_(timeline('issued', 'printed', 'holder-purged', 'printed')));

  // Order matters, not merely which events are present.
  tc_ok_('a print before the purge does not survive it',
    !codeIsPrinted_(timeline('printed', 'holder-purged')));
}

// ---------------------------------------------------------------- codeIsDeletable_

function tc_codeIsDeletable_() {
  tc_ok_('a fresh unclaimed code can be deleted',
    codeIsDeletable_({ issued: 1 }, false, false));
  tc_ok_('an unclaimed code with a note can be deleted',
    codeIsDeletable_({ issued: 1, note: 'batch 3' }, false, false));

  // Never claimed, so nobody is using it, whatever its revoked flag says.
  tc_ok_('a disabled but never claimed code can be deleted',
    codeIsDeletable_({ issued: 1, revoked: true }, false, false));

  // The one that matters: deleting this would cut a working phone off with no explanation
  // anywhere, because the device reads a missing node as a withdrawn claim.
  tc_ok_('a claimed code cannot be deleted',
    !codeIsDeletable_({ issued: 1, usedBy: 'uid-123', activatedAt: 2 }, false, false));
  tc_ok_('a claimed and disabled code cannot be deleted',
    !codeIsDeletable_({ issued: 1, usedBy: 'uid-123', revoked: true }, false, false));

  // A code with a holder is somebody's: move the holder to their new code first.
  tc_ok_('a code with a holder cannot be deleted', !codeIsDeletable_({ issued: 1 }, true, false));
  // Restore and the purge both need the code to still exist.
  tc_ok_('an On hold code cannot be deleted', !codeIsDeletable_({ issued: 1, revoked: true }, false, true));

  tc_ok_('a code that does not exist cannot be deleted', !codeIsDeletable_(null, false, false));
  tc_ok_('an undefined node cannot be deleted', !codeIsDeletable_(undefined, false, false));

  // An empty string would be a claim by nobody, which nothing writes; treat it as unclaimed
  // rather than pretending a phone holds it.
  tc_ok_('an empty usedBy counts as unclaimed', codeIsDeletable_({ issued: 1, usedBy: '' }, false, false));
}

// ---------------------------------------------------------------- utf8Length_

function tc_utf8Length_() {
  tc_eq_('ascii is one byte a character', utf8Length_('Closed Friday'), 13);
  tc_eq_('an empty banner is zero', utf8Length_(''), 0);
  tc_eq_('null is zero rather than "null"', utf8Length_(null), 0);
  tc_eq_('an accented letter is two bytes', utf8Length_('é'), 2);
  // The case this exists for: a character count passes a Marathi banner that FCM then refuses.
  tc_eq_('devanagari is three bytes a character', utf8Length_('बंद'), 9);
  tc_eq_('an emoji is four bytes, not six', utf8Length_('🙏'), 4);
  tc_eq_('the limit matches the sender', BANNER_MAX_BYTES, 3500);
}

// ---------------------------------------------------------------- holders

function tc_events_() {
  var out = [];
  for (var i = 0; i < arguments.length; i++) out.push({ at: i + 1, event: arguments[i], by: 'a@x.org' });
  return out;
}

function tc_codeIsOnHold_() {
  tc_ok_('no history is not on hold', !codeIsOnHold_([]));
  tc_ok_('an undefined timeline is not on hold', !codeIsOnHold_(undefined));
  tc_ok_('removing the holder puts it on hold', codeIsOnHold_(tc_events_('holder-added', 'holder-removed')));
  tc_ok_('restoring the holder takes it off', !codeIsOnHold_(tc_events_('holder-added', 'holder-removed', 'holder-restored')));
  tc_ok_('the purge takes it off', !codeIsOnHold_(tc_events_('holder-removed', 'holder-purged')));
  tc_ok_('removed again after a restore is on hold again',
    codeIsOnHold_(tc_events_('holder-removed', 'holder-restored', 'holder-removed')));

  // Disabling stops the phone but leaves the code workable; On hold is only ever a removed holder.
  tc_ok_('disabling is not a hold', !codeIsOnHold_(tc_events_('issued', 'revoked')));
  tc_ok_('moving the holder away is not a hold', !codeIsOnHold_(tc_events_('holder-added', 'holder-moved-out')));
}

function tc_normaliseHolder_() {
  var h = normaliseHolder_('  Mrs   Rao ', ' CHSS/123 ', '+91 98765 43210');
  tc_eq_('the name is trimmed and single-spaced', h.name, 'Mrs Rao');
  tc_eq_('the CHSS number is kept as typed, only trimmed', h.chss, 'CHSS/123');
  tc_eq_('+91 and spaces come off a mobile number', h.phone, '9876543210');
  tc_eq_('a leading 0 comes off', normaliseHolder_('A', '', '098765-43210').phone, '9876543210');
  tc_eq_('a bare 91 prefix comes off', normaliseHolder_('A', '', '919876543210').phone, '9876543210');

  // Anything that is not a ten-digit number is kept as typed rather than refused at the counter.
  tc_eq_('an unusual number is kept as typed', normaliseHolder_('A', '', '2550 5050 ext 12').phone, '2550 5050 ext 12');
  tc_eq_('an empty number stays empty', normaliseHolder_('A', '', '').phone, '');
  tc_eq_('an empty CHSS number stays empty', normaliseHolder_('A', null, '').chss, '');

  tc_throws_('a name is required', function () { normaliseHolder_('   ', 'C1', '9876543210'); }, 'name is required');
}

function tc_holderChanges_() {
  var before = { name: 'Mrs Rao', chss: 'C1', phone: '9876543210' };
  tc_eq_('nothing changed is empty',
    Object.keys(holderChanges_(before, { name: 'Mrs Rao', chss: 'C1', phone: '9876543210' })).length, 0);

  var changed = holderChanges_(before, { name: 'Mrs Rao', chss: 'C2', phone: '' });
  tc_eq_('only changed fields are listed', Object.keys(changed).sort().join(','), 'chss,phone');
  tc_eq_('the old value is kept, so the change can be read back', changed.chss.from, 'C1');
  tc_eq_('a cleared field records empty', changed.phone.to, '');

  tc_eq_('adding lists every filled field',
    Object.keys(holderChanges_({}, { name: 'A', chss: '', phone: '1' })).sort().join(','), 'name,phone');
}

function tc_splitHolders_() {
  var split = splitHolders_({
    a: { code: 'AAAAAAAA', name: 'Asha', createdAt: 1 },
    b: { code: 'BBBBBBBB', name: 'Ravi', createdAt: 2, removedAt: 50, wasDisabled: true },
    c: { code: 'CCCCCCCC', name: 'Meera', createdAt: 3, removedAt: 90,
         history: { k2: { at: 90, event: 'removed' }, k1: { at: 3, event: 'added' } } }
  });
  tc_eq_('an active holder is found by code', split.byCode.AAAAAAAA.name, 'Asha');
  tc_eq_('the holder carries its id', split.byCode.AAAAAAAA.id, 'a');
  tc_ok_('a removed holder is not active', !split.byCode.BBBBBBBB);
  tc_eq_('removed holders are newest first', split.removed.map(function (h) { return h.id; }).join(','), 'c,b');
  tc_ok_('whether the code was disabled survives', split.removed[1].wasDisabled === true);
  tc_eq_('history is in order', split.removed[0].history.map(function (e) { return e.event; }).join(','), 'added,removed');
  tc_eq_('an empty /holders splits to nothing', splitHolders_(null).removed.length, 0);
}

function tc_holderIsPurgeable_() {
  var day = 24 * 60 * 60 * 1000;
  tc_eq_('retention is 30 days', HOLDER_RETENTION_MS, 30 * day);
  tc_ok_('an active holder is never purged', !holderIsPurgeable_({ removedAt: 0 }, 100 * day));
  tc_ok_('29 days after removal is kept', !holderIsPurgeable_({ removedAt: day }, 30 * day));
  tc_ok_('30 days after removal is erased', holderIsPurgeable_({ removedAt: day }, 31 * day));
  tc_ok_('nothing at all is not purged', !holderIsPurgeable_(null, 31 * day));
}

function tc_codesAndTimeline_() {
  tc_eq_('a dashed lowercase code is normalised', normaliseCode_('axgx-bbbe'), 'AXGXBBBE');
  tc_eq_('O, I and L read as digits', normaliseCode_('OOIL'), '0011');
  tc_eq_('a code is shown with its dash', formatCode_('AXGXBBBE'), 'AXGX-BBBE');

  var entries = { k: { at: 1, by: 'a@x.org', event: 'holder-moved-out', holder: '-Nabc', to: 'BBBBBBBB' } };
  var line = auditTimeline_(entries, {})[0];
  tc_eq_('a holder event keeps its holder id', line.holder, '-Nabc');
  tc_eq_('a move keeps the other code', line.to, 'BBBBBBBB');
}

// ---------------------------------------------------------------- the move to dispensaries

function tc_dispensaryBackfill_() {
  var patch = dispensaryBackfill_({
    AAAAAAAA: { issued: 1 },
    BBBBBBBB: { issued: 1, usedBy: 'uid', activatedAt: 2, revoked: true, note: 'Mrs Rao' },
    CCCCCCCC: { issued: 1, dispensary: 'barc-vashi' }
  }, 'barc-vashi');

  tc_eq_('every code without a dispensary gets one',
    Object.keys(patch).sort().join(','), 'AAAAAAAA/dispensary,BBBBBBBB/dispensary');
  tc_eq_('the value is the dispensary id', patch['AAAAAAAA/dispensary'], 'barc-vashi');

  // The one that matters: a key per field, never a whole code, so a claim cannot be overwritten.
  tc_ok_('only the dispensary field is written on a claimed code',
    patch['BBBBBBBB/dispensary'] === 'barc-vashi' && !('BBBBBBBB' in patch));
  tc_ok_('a code that already has one is left alone', !('CCCCCCCC/dispensary' in patch));

  tc_eq_('nothing to do is an empty patch', Object.keys(dispensaryBackfill_({}, 'x')).length, 0);
  tc_eq_('an empty database is an empty patch', Object.keys(dispensaryBackfill_(null, 'x')).length, 0);
  tc_eq_('a malformed node is skipped', Object.keys(dispensaryBackfill_({ BAD: 'text' }, 'x')).length, 0);
}

function runConsoleTests() {
  TC_RESULTS = [];
  tc_dispensaryBackfill_();
  tc_codeIsOnHold_();
  tc_normaliseHolder_();
  tc_holderChanges_();
  tc_splitHolders_();
  tc_holderIsPurgeable_();
  tc_codesAndTimeline_();
  tc_utf8Length_();
  tc_auditTimeline_();
  tc_auditEvents_();
  tc_countRepeatingCharacters_();
  tc_generateCode_();
  tc_codeIsPrinted_();
  tc_codeIsDeletable_();

  var failed = 0;
  for (var i = 0; i < TC_RESULTS.length; i++) {
    var r = TC_RESULTS[i];
    if (!r.pass) failed++;
    Logger.log((r.pass ? 'ok   ' : 'FAIL ') + r.name + (r.detail ? '  -- ' + r.detail : ''));
  }
  Logger.log(failed === 0 ? ('All ' + TC_RESULTS.length + ' passed.') : (failed + ' of ' + TC_RESULTS.length + ' FAILED.'));
}
