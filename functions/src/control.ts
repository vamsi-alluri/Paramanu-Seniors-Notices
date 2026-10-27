import * as admin from "firebase-admin";
import { logger } from "firebase-functions";

// Pushes a code's revoke and resume to the one install holding it, and a dispensary's banner to
// the phones of that dispensary, straight from the database write that caused them. It replaces
// the console's /controlQueue and the sender's one-minute Control.gs drain; see the topic section
// of docs/decisions.md.
//
// Push is a courtesy, the API is the truth: a phone that misses one of these still finds the
// change at its next /status call. So nothing here retries for long or tries to be clever.
//
// The holder's UID is the topic, so it is known to this file and nowhere else. It must never be
// logged, and neither may the activation code (the same rule as index.ts): log lines carry the
// message type and the event id only.

// Topic families. A version means one envelope and one meaning, so a change to either is a new
// version, never an edit. Must match ActivationRepository in the app.
export const DEVICE_TOPIC_PREFIX = "device-v1-";
export const BANNER_TOPIC_PREFIX = "banner-v1-";

// TEMPORARY. The retired broadcast topic every build up to 0.8.1 listens on. Each message is also
// sent here, in the same envelope, so those builds keep hearing about a disable, an enable or a
// banner while they age out; the builds that know the topics above have left it. Remove it, with
// legacyCodePush and its two call sites, when the database rules close (phase 5 of
// docs/superpowers/plans/2026-10-28-backend-into-functions.md).
export const LEGACY_CONTROL_TOPIC = "control-v1";

// FCM's topic grammar. Anonymous UIDs and the console's dispensary ids both fit it; anything that
// does not would be refused by FCM on every retry, so it is dropped before a send is tried.
const TOPIC_SAFE = /^[A-Za-z0-9_.~%-]{1,200}$/;

// FCM refuses a data payload over 4096 bytes, keys included; the html is by far the largest value.
// Must match BANNER_MAX_BYTES in the console's Code.gs, which refuses to save a longer banner.
export const BANNER_MAX_BYTES = 3500;

export type ControlType = "revoke" | "resume";

// The FCM data map. Every value a string, as FCM requires.
export type ControlData = Record<string, string>;

export interface Push {
  topic: string;
  data: ControlData;
}

type Node = Record<string, unknown>;

const asNode = (v: unknown): Node | null =>
  v !== null && typeof v === "object" && !Array.isArray(v) ? (v as Node) : null;

function holderOf(node: unknown): string | null {
  const usedBy = asNode(node)?.usedBy;
  return typeof usedBy === "string" && usedBy !== "" ? usedBy : null;
}

// Whether [uid]'s claim on this code stands. The same five conditions as statusFor in codes.ts,
// so a push never says anything /status would contradict.
export function standsFor(node: unknown, uid: string): boolean {
  const n = asNode(node);
  return n !== null && n.issued != null && n.usedBy === uid && n.revoked !== true;
}

/**
 * What one write to /codes/{code} means for the phone that held it before the write. Pure.
 *
 * Only the before-image's holder is ever addressed. A claim (no holder before) sends nothing: the
 * phone that claimed already knows. A holder who loses the claim -- disabled, unlinked, the node
 * deleted, or usedBy hand-edited to someone else -- is sent a revoke; one whose claim comes back is
 * sent a resume. Unlinking a code that was already disabled sends a second revoke to a phone that
 * should already be revoked; the app ignores it, and it reaches a phone that missed the first.
 * The next holder of an unlinked code is a different install with a different UID, so it cannot
 * receive the old phone's revoke -- which is what makes unlink safe to push at all.
 */
export function decideCode(before: unknown, after: unknown): { type: ControlType; uid: string } | null {
  const uid = holderOf(before);
  if (uid === null) return null;
  const was = standsFor(before, uid);
  const now = standsFor(after, uid);
  if (!now && (was || holderOf(after) !== uid)) return { type: "revoke", uid };
  if (now && !was) return { type: "resume", uid };
  return null;
}

// Whether a decision still holds against the node as it is now. Triggers can run late (a retry)
// and out of order, so a revoke for a code enabled since, or a resume for one disabled again, is
// dropped rather than sent. The stamp on the message orders the rest on the phone.
export function stillHolds(type: ControlType, uid: string, current: unknown): boolean {
  return type === "revoke" ? !standsFor(current, uid) : standsFor(current, uid);
}

export function devicePush(type: ControlType, uid: string, code: string, at: number): Push | null {
  if (!TOPIC_SAFE.test(uid)) return null;
  // The code stays in the message although the topic already names the phone: a revoke for a code
  // the phone has since replaced must not stop the new one, and the app checks it against its own.
  return { topic: DEVICE_TOPIC_PREFIX + uid, data: { type, code, at: String(at) } };
}

/**
 * The copy of a code message for [LEGACY_CONTROL_TOPIC], or null when none goes there. Pure.
 *
 * Only what the old Control.gs drain sent: a disable or an enable of a code a phone still holds,
 * so the after-image must still name the same holder. Never an unlink or a delete. On a broadcast
 * addressed by code, a revoke for a freed code could reach the next phone to claim it -- one that was
 * offline when it was sent gets it from FCM's queue afterwards, newer than anything it has applied,
 * and stops a claim that is perfectly good. The device topic has no such problem, since a new holder
 * has a new UID.
 */
export function legacyCodePush(type: ControlType, uid: string, code: string, after: unknown, at: number): Push | null {
  if (asNode(after)?.usedBy !== uid) return null;
  return { topic: LEGACY_CONTROL_TOPIC, data: { type, code, at: String(at) } };
}

/** UTF-8 length in bytes, as the console counts it. */
export function utf8Length(s: string): number {
  return Buffer.byteLength(s, "utf8");
}

// Whether a write to /dispensaries/{id}/info changed the banner. Heading and lines are hand-edited
// fallbacks the phone reads with the rest of the dispensary, so a change to them alone is not
// pushed. A removed banner is an empty html with a fresh htmlUpdated, and is pushed like any other.
export function bannerChanged(before: unknown, after: unknown): boolean {
  const was = asNode(before);
  const now = asNode(after);
  return was?.html !== now?.html || was?.htmlUpdated !== now?.htmlUpdated;
}

/**
 * The banner push for a dispensary's info node, or why there is none. Pure.
 *
 * The stamp is htmlUpdated, the same value the phone compares against when it reads the banner
 * itself, so a push and a read cannot undo each other.
 */
export function bannerPush(dispensary: string, info: unknown): { push: Push } | { drop: string } {
  const now = asNode(info);
  if (!TOPIC_SAFE.test(dispensary)) return { drop: "dispensary id is not a valid topic name" };
  const at = now?.htmlUpdated;
  if (typeof at !== "number" || !Number.isFinite(at) || at <= 0) return { drop: "banner has no timestamp" };
  const html = typeof now?.html === "string" ? now.html : "";
  const bytes = utf8Length(html);
  if (bytes > BANNER_MAX_BYTES) {
    return { drop: `banner is ${bytes} bytes, over the ${BANNER_MAX_BYTES}-byte limit` };
  }
  return {
    push: {
      topic: BANNER_TOPIC_PREFIX + dispensary,
      data: { type: "banner", dispensary, html, at: String(at) },
    },
  };
}

// Data-only and high priority, with no notification block: a notification block would be drawn by
// the SDK and onMessageReceived would never run, so a revoke would be shown and never applied.
export async function sendPush(push: Push): Promise<void> {
  await admin.messaging().send({ topic: push.topic, android: { priority: "high" }, data: push.data });
}

// FCM errors that will fail the same way on every retry. Anything else (a network fault, FCM
// unavailable or over quota) is thrown, so the event is retried.
const PERMANENT = new Set([
  "messaging/invalid-argument",
  "messaging/invalid-payload",
  "messaging/payload-size-limit-exceeded",
  "messaging/invalid-data-payload-key",
]);

async function deliver(push: Push, send: (p: Push) => Promise<void>, kind: string, eventId: string) {
  try {
    await send(push);
  } catch (e) {
    const code = (e as { code?: unknown } | null)?.code;
    if (typeof code === "string" && PERMANENT.has(code)) {
      logger.error(`control ${kind} refused`, { kind, eventId, errorCode: code });
      return;
    }
    throw e;
  }
  logger.info(`control ${kind} sent`, { kind, eventId });
}

export interface CodeEvent {
  code: string;
  before: unknown;
  after: unknown;
  // When the write happened, in epoch millis: the message's stamp.
  at: number;
  eventId: string;
}

/**
 * The whole of the /codes/{code} trigger, with the database read and the send injectable so a test
 * can drive it. Throws only for a fault worth retrying.
 */
export async function handleCodeWrite(
  e: CodeEvent,
  deps: {
    read?: (code: string) => Promise<unknown>;
    send?: (p: Push) => Promise<void>;
  } = {},
): Promise<void> {
  const decision = decideCode(e.before, e.after);
  if (decision === null) return;
  const read = deps.read ?? (async (code: string) => (await admin.database().ref(`codes/${code}`).get()).val());
  if (!stillHolds(decision.type, decision.uid, await read(e.code))) {
    logger.info(`control ${decision.type} dropped`, { kind: decision.type, eventId: e.eventId, reason: "superseded" });
    return;
  }
  const send = deps.send ?? sendPush;
  const push = devicePush(decision.type, decision.uid, e.code, e.at);
  if (push === null) {
    logger.error(`control ${decision.type} dropped`, { kind: decision.type, eventId: e.eventId, reason: "uid_not_topic_safe" });
  } else {
    await deliver(push, send, decision.type, e.eventId);
  }
  // A throw above retries the event before this runs, and the retry sends both again: harmless,
  // because a phone ignores a stamp it has already applied.
  const legacy = legacyCodePush(decision.type, decision.uid, e.code, e.after, e.at);
  if (legacy !== null) await deliver(legacy, send, `legacy-${decision.type}`, e.eventId);
}

export interface BannerEvent {
  dispensary: string;
  before: unknown;
  after: unknown;
  eventId: string;
}

// The banner sent is the one in the database now, not the write's after-image, as the drain did: a
// late or retried event then still sends the latest banner, and two saves in quick succession
// both send the second.
export async function handleBannerWrite(
  e: BannerEvent,
  deps: {
    read?: (dispensary: string) => Promise<unknown>;
    send?: (p: Push) => Promise<void>;
  } = {},
): Promise<void> {
  if (!bannerChanged(e.before, e.after)) return;
  const read =
    deps.read ?? (async (id: string) => (await admin.database().ref(`dispensaries/${id}/info`).get()).val());
  const decision = bannerPush(e.dispensary, await read(e.dispensary));
  if ("drop" in decision) {
    logger.warn("control banner dropped", { kind: "banner", eventId: e.eventId, reason: decision.drop });
    return;
  }
  const send = deps.send ?? sendPush;
  await deliver(decision.push, send, "banner", e.eventId);
  // The old builds check the dispensary in the message and keep only their own, so the same data
  // serves them on the broadcast.
  await deliver({ topic: LEGACY_CONTROL_TOPIC, data: decision.push.data }, send, "legacy-banner", e.eventId);
}
