/**
 * Paramanu Seniors Notices - control queue drain
 *
 * A file in the SENDER Apps Script project, driven by a one-minute trigger. It reads what the
 * console has queued -- a code disabled or enabled, a holder removed or restored, the banner
 * changed -- and broadcasts each on the control topic, so phones act within about a minute rather
 * than at their next daily check.
 *
 * WHY A QUEUE AND NOT AN API CALL
 *   The console decides these but has no FCM credentials, and is deliberately not given any:
 *   issuing codes and broadcasting to four hundred phones are separate jobs held in separate
 *   projects (SYSTEM.md 2.3).
 *
 *   The obvious bridge -- the console POSTs to this project's web app, forwarding the staff
 *   member's identity with ScriptApp.getOAuthToken() -- does not work. Every request returns
 *   HTTP 401 from Google's auth frontend before any script runs. The evidence is in
 *   docs/decisions.md; do not spend an afternoon rediscovering it.
 *
 *   So the console writes to the database it already owns, and this drains it.
 *
 * THE QUEUE
 *   /controlQueue/code_{CODE}  { type: 'revoke' | 'resume', code, at, by }
 *   /controlQueue/banner_{ID}  { dispensary, at, by }   one per dispensary
 *
 *   Keyed so the latest decision replaces an earlier one still waiting: disabling and enabling a
 *   code inside a minute leaves one resume, not a revoke followed by a resume.
 *
 * NAMING
 *   Apps Script gives every .gs file in a project one shared global scope, so two files declaring
 *   the same function name is not an error - the second silently replaces the first. Everything this
 *   file owns is therefore prefixed `control`. The names it reuses from Code.gs:
 *
 *     firebase_()   pushControl_()   utf8Length_()   BANNER_MAX_BYTES
 *
 * SETUP (once)
 *  1. Paste this into the sender project as a new file, Control.gs, and delete Revoker.gs.
 *  2. No new Script Properties.
 *  3. Run controlDryRun(). Reads the queue, logs what a real run would send, sends nothing.
 *  4. Run controlInstallTrigger() to create the one-minute trigger. It also removes the revokerRun
 *     trigger Revoker.gs installed, which would otherwise fail every minute once that file is gone.
 *
 * Adding this file does not change the web app, so there is no need to redeploy it -- but Code.gs
 * did change, and the trigger runs whatever is saved, so save both.
 */

var CONTROL_VERSION = '2026-09-13-control';

var CONTROL_QUEUE_PATH = '/controlQueue';

/**
 * Most messages broadcast in one run.
 *
 * A ceiling, not a throttle. Twenty changes in one minute is not something that happens by hand; if
 * the queue ever holds more, something has gone wrong upstream, and the rest staying visible in the
 * queue is a problem somebody can look at.
 */
var CONTROL_MAX_PER_RUN = 20;

/** A banner entry's key, naming the dispensary whose banner changed. */
var CONTROL_BANNER_KEY = /^banner_([a-z0-9-]+)$/;

/** What the drain has to read before deciding: the code, or the dispensary's banner. */
function controlLoad_(key) {
  var code = /^code_([0-9A-Z]{8})$/.exec(key);
  if (code) return { node: firebase_('get', '/codes/' + code[1] + '.json'), info: null };
  var banner = CONTROL_BANNER_KEY.exec(key);
  if (banner) return { node: null, info: firebase_('get', '/dispensaries/' + banner[1] + '/info.json') || {} };
  return { node: null, info: null };
}

/**
 * What to do with one queue entry, given what the database holds now. Pure, so it can be tested.
 *
 * Returns { send: data } or { drop: reason }.
 *
 * The database is re-read before deciding rather than trusting the entry, because the entry is only
 * a request. A code enabled after its revoke was queued must not be revoked; an unlinked code has no
 * phone left to cut off; and a resume for a code nobody holds reaches no phone at all.
 */
function controlDecide_(key, entry, node, info) {
  entry = entry || {};

  var banner = CONTROL_BANNER_KEY.exec(String(key || ''));
  if (banner) {
    var html = String((info && info.html) || '');
    if (utf8Length_(html) > BANNER_MAX_BYTES) {
      return { drop: 'banner is ' + utf8Length_(html) + ' bytes, over the ' + BANNER_MAX_BYTES + '-byte limit' };
    }
    var bannerAt = (info && info.htmlUpdated) || entry.at;
    if (!bannerAt) return { drop: 'banner has no timestamp' };
    // Every dispensary's banner shares the control topic, so the message says whose it is and each
    // phone keeps only its own.
    return { send: { type: 'banner', dispensary: banner[1], html: html, at: bannerAt } };
  }

  var match = /^code_([0-9A-Z]{8})$/.exec(String(key || ''));
  if (!match) return { drop: 'malformed key' };
  var code = match[1];

  if (entry.type === 'revoke') {
    if (!node || node.revoked !== true) return { drop: code + ' is no longer stopped' };
  } else if (entry.type === 'resume') {
    if (!node || !node.usedBy) return { drop: code + ' is not held by any phone' };
    if (node.revoked === true) return { drop: code + ' is stopped again' };
  } else {
    return { drop: 'unknown type ' + entry.type };
  }

  if (!entry.at) return { drop: 'entry has no timestamp' };
  return { send: { type: entry.type, code: code, at: entry.at } };
}

/** Everything waiting, oldest first, with where each one lives so it can be cleared. */
function controlJobs_() {
  var queue = firebase_('get', CONTROL_QUEUE_PATH + '.json') || {};
  var jobs = Object.keys(queue).map(function (key) {
    return { key: key, path: CONTROL_QUEUE_PATH + '/' + key, entry: queue[key] || {} };
  });
  jobs.sort(function (a, b) { return (a.entry.at || 0) - (b.entry.at || 0); });
  return jobs;
}

/**
 * Removes an entry, unless the console has replaced it since it was read.
 *
 * Without this check, an Enable queued while its Disable was being broadcast would be deleted along
 * with it, and the phone would stay off until its daily check. Comparing the stamp narrows that to
 * the few milliseconds between this read and the delete.
 */
function controlClear_(job) {
  var current = firebase_('get', job.path + '.json');
  if (current && current.at !== job.entry.at) {
    Logger.log('control: %s changed while being sent; leaving the newer entry', job.key);
    return;
  }
  firebase_('delete', job.path + '.json');
}

/**
 * The trigger's work. Reads the queue, broadcasts what still applies, clears what is done.
 *
 * Delivery is at-least-once: an entry is cleared only after FCM has accepted the message, so a
 * failure leaves it for the next run. A phone receiving the same message twice applies it once --
 * it ignores a stamp no newer than the last one applied.
 */
function controlDrain_() {
  var jobs = controlJobs_();
  var result = { pushed: 0, skipped: 0, failed: 0 };
  if (!jobs.length) return result;

  for (var i = 0; i < jobs.length && i < CONTROL_MAX_PER_RUN; i++) {
    var job = jobs[i];
    var loaded = controlLoad_(job.key);
    var decision = controlDecide_(job.key, job.entry, loaded.node, loaded.info);

    if (decision.drop) {
      Logger.log('control: dropping %s without sending -- %s', job.key, decision.drop);
      controlClear_(job);
      result.skipped++;
      continue;
    }

    try {
      pushControl_(decision.send);
      controlClear_(job);
      result.pushed++;
      Logger.log('control: broadcast %s (queued by %s)', job.key, job.entry.by || 'unknown');
    } catch (err) {
      // Left in the queue on purpose: the next run tries again.
      result.failed++;
      Logger.log('control: FAILED for %s, leaving it queued -- %s', job.key, err.message);
    }
  }

  if (jobs.length > CONTROL_MAX_PER_RUN) {
    Logger.log('control: %s entries queued, capped at %s this run.', jobs.length, CONTROL_MAX_PER_RUN);
  }
  return result;
}

/** The trigger handler. Separate from the drain so the drain can be called from a test. */
function controlRun() {
  var result = controlDrain_();
  if (result.pushed || result.failed || result.skipped) {
    Logger.log('control: pushed=%s skipped=%s failed=%s', result.pushed, result.skipped, result.failed);
  }
}

/** Reads the queue and logs what a real run would broadcast. Sends nothing. */
function controlDryRun() {
  var jobs = controlJobs_();
  Logger.log('version=%s queued=%s', CONTROL_VERSION, jobs.length);
  jobs.forEach(function (job) {
    var loaded = controlLoad_(job.key);
    var decision = controlDecide_(job.key, job.entry, loaded.node, loaded.info);
    Logger.log('  %s  queuedBy=%s  %s', job.key, job.entry.by || 'unknown',
      decision.send ? 'wouldSend=' + decision.send.type : 'wouldDrop: ' + decision.drop);
  });
}

/** Creates the one-minute trigger. Safe to run twice: it removes any existing one first. */
function controlInstallTrigger() {
  controlRemoveTriggers_();
  ScriptApp.newTrigger('controlRun').timeBased().everyMinutes(1).create();
  Logger.log('Trigger installed: controlRun every minute.');
}

/** Removes the trigger. The first thing to reach for if something is going wrong. */
function controlStopTrigger() {
  Logger.log('Removed %s trigger(s).', controlRemoveTriggers_());
}

function controlRemoveTriggers_() {
  var removed = 0;
  ScriptApp.getProjectTriggers().forEach(function (trigger) {
    var handler = trigger.getHandlerFunction();
    if (handler === 'controlRun' || handler === 'revokerRun') {
      ScriptApp.deleteTrigger(trigger);
      removed++;
    }
  });
  return removed;
}
