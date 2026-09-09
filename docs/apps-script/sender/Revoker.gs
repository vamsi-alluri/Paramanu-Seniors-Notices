/**
 * Paramanu Seniors Notices - revoke queue drain
 *
 * A third file in the SENDER Apps Script project, driven by a time-based trigger. It reads the
 * codes the console has revoked and broadcasts a revoke for each, so a cut-off phone stops
 * receiving within about a minute rather than waiting for its next daily verification.
 *
 * WHY A QUEUE AND NOT AN API CALL
 *   The console decides revocations but has no FCM credentials, and is deliberately not given any:
 *   issuing codes and broadcasting to four hundred phones are separate jobs held in separate
 *   projects (SYSTEM.md 2.3).
 *
 *   The obvious bridge -- the console POSTs to this project's web app, forwarding the staff
 *   member's identity with ScriptApp.getOAuthToken() -- does not work. Every request returns
 *   HTTP 401 from Google's auth frontend before any script runs, because that token carries the
 *   CONSOLE's scopes and invoking a web app needs one authorized for THIS project. No manifest
 *   scope creates that grant across two projects. The evidence is in
 *   docs/superpowers/specs/2026-09-09-revoke-queue-design.md; do not spend an afternoon rediscovering
 *   it.
 *
 *   So the console writes to the database it already owns, and this drains it. No token, no shared
 *   secret, no second deployment.
 *
 * NAMING
 *   Apps Script gives every .gs file in a project one shared global scope, so two files declaring
 *   the same function name is not an error - the second silently replaces the first, and the
 *   symptom appears somewhere else entirely. Everything this file owns is therefore prefixed
 *   `revoker`. The only names it reuses from Code.gs are the shared plumbing it deliberately does
 *   not duplicate:
 *
 *     property_()   accessToken_()   firebase_()   pushRevoke_()   TOPIC   TOPIC_OVERRIDE
 *
 *   If you rename any of those in Code.gs, this file has to follow.
 *
 * WHAT IT DELIBERATELY DOES NOT REUSE
 *   sendNotice(), because it calls requireEditor_() and checkPin_(). A trigger has no signed-in
 *   caller and cannot type a PIN. pushRevoke_() takes neither and sends the fixed revoke envelope,
 *   which is exactly what is wanted here.
 *
 * SETUP (once)
 *  1. Paste this into the sender project as a new file, Revoker.gs.
 *  2. No new Script Properties. SERVICE_ACCOUNT_JSON, DATABASE_URL and PROJECT_ID are already set.
 *  3. Run revokerDryRun(). Reads the queue, logs what a real run would send, sends nothing.
 *  4. Run revokerInstallTrigger() to create the one-minute trigger.
 *
 * Adding this file does not change the web app, so there is no need to redeploy it.
 */

var REVOKER_VERSION = '2026-09-09-revoker';

var REVOKER_QUEUE_PATH = '/revokeQueue';

/**
 * Most revocations broadcast in one run.
 *
 * A ceiling, not a throttle, mirroring POLLER_MAX_PER_RUN. Revoking twenty people in one minute is
 * not something that happens by hand; if the queue ever holds more than this, something has gone
 * wrong upstream and the rest staying visible in the queue is a problem somebody can look at rather
 * than four hundred phones that have already been cut off.
 */
var REVOKER_MAX_PER_RUN = 20;

/**
 * The trigger entry point. Reads the queue, broadcasts each pending revoke, clears what succeeded.
 *
 * Runs every minute, and on almost every run the queue is empty and this costs one small REST call.
 *
 * Delivery is at-least-once by design: an entry is deleted only after FCM has accepted the message,
 * so a failure leaves it for the next run rather than losing it. A phone receiving the same revoke
 * twice is harmless -- ActivationRepository.suspendClaim() is idempotent, and announceRevocation()
 * is guarded by the local-revoked row id, so nobody is notified twice.
 */
function revokerDrain_() {
  var queue = firebase_('get', REVOKER_QUEUE_PATH + '.json') || {};
  var codes = Object.keys(queue);
  if (!codes.length) return { pushed: 0, skipped: 0, failed: 0 };

  var pushed = 0;
  var skipped = 0;
  var failed = 0;

  for (var i = 0; i < codes.length && i < REVOKER_MAX_PER_RUN; i++) {
    var code = codes[i];

    if (!revokerIsValidCode_(code)) {
      // Nothing legitimate writes a malformed key here. Drop it rather than retrying it forever.
      Logger.log('revoker: discarding malformed queue key "%s"', code);
      firebase_('delete', REVOKER_QUEUE_PATH + '/' + code + '.json');
      skipped++;
      continue;
    }

    // Re-read the code before broadcasting. The console clears the queue entry when a code is
    // restored or released, but this closes the window where that happened while the queue was
    // being read -- and, more importantly, a released code goes back into the pool, so a stale
    // revoke would cut off whoever claimed it next the moment they typed their slip.
    var node = firebase_('get', '/codes/' + code + '.json');
    if (!node || node.revoked !== true) {
      Logger.log('revoker: %s is no longer revoked; dropping without sending', code);
      firebase_('delete', REVOKER_QUEUE_PATH + '/' + code + '.json');
      skipped++;
      continue;
    }

    try {
      pushRevoke_(code);
      firebase_('delete', REVOKER_QUEUE_PATH + '/' + code + '.json');
      pushed++;
      Logger.log('revoker: broadcast revoke for %s (queued by %s)', code, queue[code].by || 'unknown');
    } catch (err) {
      // Left in the queue on purpose: the next run tries again.
      failed++;
      Logger.log('revoker: FAILED for %s, leaving it queued -- %s', code, err.message);
    }
  }

  if (codes.length > REVOKER_MAX_PER_RUN) {
    Logger.log('revoker: %s entries queued, capped at %s this run.', codes.length, REVOKER_MAX_PER_RUN);
  }
  return { pushed: pushed, skipped: skipped, failed: failed };
}

/** The trigger handler. Separate from the drain so the drain can be called from a test. */
function revokerRun() {
  var result = revokerDrain_();
  if (result.pushed || result.failed || result.skipped) {
    Logger.log('revoker: pushed=%s skipped=%s failed=%s', result.pushed, result.skipped, result.failed);
  }
}

/**
 * Shape check on a queue key.
 *
 * The sender has no view of which codes exist -- that is the console's half -- so this rejects a
 * malformed key rather than an unknown code.
 */
function revokerIsValidCode_(code) {
  return /^[0-9A-Z]{8}$/.test(String(code || ''));
}

/** Reads the queue and logs what a real run would broadcast. Sends nothing. */
function revokerDryRun() {
  var queue = firebase_('get', REVOKER_QUEUE_PATH + '.json') || {};
  var codes = Object.keys(queue);
  Logger.log('version=%s queued=%s', REVOKER_VERSION, codes.length);
  for (var i = 0; i < codes.length; i++) {
    var node = firebase_('get', '/codes/' + codes[i] + '.json');
    Logger.log('  %s  queuedBy=%s  stillRevoked=%s  wouldSend=%s',
      codes[i],
      queue[codes[i]].by || 'unknown',
      node && node.revoked === true,
      revokerIsValidCode_(codes[i]) && !!node && node.revoked === true);
  }
}

/** Creates the one-minute trigger. Safe to run twice; it removes any existing one first. */
function revokerInstallTrigger() {
  ScriptApp.getProjectTriggers().forEach(function (trigger) {
    if (trigger.getHandlerFunction() === 'revokerRun') ScriptApp.deleteTrigger(trigger);
  });
  ScriptApp.newTrigger('revokerRun').timeBased().everyMinutes(1).create();
  Logger.log('Trigger installed: revokerRun every minute.');
}

/** Removes the trigger. The first thing to reach for if something is going wrong. */
function revokerStopTrigger() {
  var removed = 0;
  ScriptApp.getProjectTriggers().forEach(function (trigger) {
    if (trigger.getHandlerFunction() === 'revokerRun') {
      ScriptApp.deleteTrigger(trigger);
      removed++;
    }
  });
  Logger.log('Removed %s trigger(s).', removed);
}
