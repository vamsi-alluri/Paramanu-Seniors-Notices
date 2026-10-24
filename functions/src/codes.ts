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

const asString = (v: unknown): string | null => (typeof v === "string" ? v : null);
const asNumber = (v: unknown): number | null => (typeof v === "number" ? v : null);

// null when the node does not exist. Fields are named one by one, never spread.
export async function readCode(code: string): Promise<CodeRecord | null> {
  const snap = await admin.database().ref(`codes/${code}`).get();
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
  const response: StatusResponse = { status: "active", code: toPublic(record) };
  if (record.dispensary !== null) {
    const node = await admin.database().ref(`dispensaries/${record.dispensary}`).get();
    if (node.exists()) response.dispensary = node.val();
  }
  return response;
}
