import * as admin from "firebase-admin";

// What this module knows about a /codes/{code} node. `note` is deliberately not
// a field: it holds opt-in holder details and must not be able to leave this file.
export interface CodeRecord {
  code: string;
  issued: boolean;
  usedBy: string | null;
  revoked: boolean;
  dispensary: string | null;
  activatedAt: number | null;
}

// The only shape of a code that ever reaches a client.
export interface PublicCode {
  code: string;
  dispensary: string | null;
  revoked: boolean;
  activatedAt: number | null;
}

export interface StatusResponse {
  status: "active" | "revoked";
  code?: PublicCode;
  dispensary?: unknown;
}

// A dispensary id is hand-edited data used as a path segment. Blank would resolve
// to the whole collection; forbidden characters would make ref() throw. Either way
// the claim is still good, so such an id means "no dispensary", not an error.
const DISPENSARY_ID = /^[^.$#[\]/\s]+$/;

const asString = (v: unknown): string | null => (typeof v === "string" ? v : null);
const asNumber = (v: unknown): number | null => (typeof v === "number" ? v : null);

// null when the node does not exist. Fields are named one by one, never spread.
function recordFrom(code: string, snap: admin.database.DataSnapshot): CodeRecord | null {
  if (!snap.exists()) return null;
  return {
    code,
    issued: snap.child("issued").exists(),
    usedBy: asString(snap.child("usedBy").val()),
    revoked: snap.child("revoked").val() === true,
    dispensary: asString(snap.child("dispensary").val()),
    activatedAt: asNumber(snap.child("activatedAt").val()),
  };
}

export async function readCode(code: string): Promise<CodeRecord | null> {
  return recordFrom(code, await admin.database().ref(`codes/${code}`).get());
}

function toPublic(r: CodeRecord): PublicCode {
  return {
    code: r.code,
    dispensary: r.dispensary,
    revoked: r.revoked,
    activatedAt: r.activatedAt,
  };
}

// Mirrors ActivationRepository.verify(): the claim is alive only if none of the
// five revoked conditions hold. A database failure throws; it is never "revoked".
export async function statusFor(uid: string, code: string): Promise<StatusResponse> {
  const record = await readCode(code);
  if (
    record === null || // node absent entirely
    record.revoked || // revoked === true
    !record.issued || // never issued by the NGO
    record.usedBy === null || // claim erased by the NGO
    record.usedBy !== uid // reissued to somebody else
  ) {
    return { status: "revoked" };
  }
  return activeResponse(record);
}

// The body for a standing claim, shared by /status and /activate.
async function activeResponse(record: CodeRecord): Promise<StatusResponse> {
  const response: StatusResponse = { status: "active", code: toPublic(record) };
  if (record.dispensary !== null && DISPENSARY_ID.test(record.dispensary)) {
    const node = await admin.database().ref(`dispensaries/${record.dispensary}`).get();
    if (node.exists()) response.dispensary = node.val();
  }
  return response;
}

export type RefusalReason = "unknown_code" | "not_issued" | "already_claimed" | "revoked";

// Claims `code` for `uid` in one RTDB transaction on the code node: the check and
// the write are a single compare-and-set, so two devices cannot both pass. The
// refusal is decided from the value the transaction itself read, never a prior get().
// Only usedBy and activatedAt are ever written; the rest of the node is carried over.
// A database fault rejects (never a refusal), so the caller can answer 5xx.
export async function claim(uid: string, code: string): Promise<StatusResponse | RefusalReason> {
  let refusal: RefusalReason | null = null;
  let alreadyMine = false;
  const result = await admin
    .database()
    .ref(`codes/${code}`)
    .transaction((current: Record<string, unknown> | null) => {
      refusal = null;
      alreadyMine = false;
      // The first run may see null even for an existing node (no local cache). Returning
      // undefined here would abort on that guess; returning null commits only if the
      // server really holds null, otherwise the SDK retries with the real value.
      if (current === null) {
        refusal = "unknown_code";
        return null;
      }
      if (current.issued == null) {
        refusal = "not_issued";
        return undefined;
      }
      if (current.revoked === true) {
        refusal = "revoked";
        return undefined;
      }
      if (current.usedBy != null) {
        if (current.usedBy === uid) alreadyMine = true; // retry after a lost response
        else refusal = "already_claimed";
        return undefined;
      }
      // A concrete time, not ServerValue.TIMESTAMP: the committed snapshot would then
      // carry the SDK's estimate, which differs from what the server stores.
      return { ...current, usedBy: uid, activatedAt: Date.now() };
    });
  if (refusal !== null) return refusal;
  const record = recordFrom(code, result.snapshot);
  if (record === null) return "unknown_code";
  if (!result.committed && !alreadyMine) throw new Error("claim transaction aborted unexpectedly");
  return activeResponse(record);
}
