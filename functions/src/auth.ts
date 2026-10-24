import * as admin from "firebase-admin";

// Any failure to authenticate the caller. Always 401: the client treats 401 as
// "inconclusive" (notices still delivered), so a bad token must never be
// reported as anything that reads as a revoked claim.
export class AuthError extends Error {
  readonly status = 401;
  constructor(message: string) {
    super(message);
    this.name = "AuthError";
  }
}

const PREFIX = "Bearer ";

// Resolves to the caller's uid. The uid comes from the verified ID token and
// nowhere else; the request body is never consulted.
export async function verifyBearer(header: string | undefined): Promise<string> {
  if (!header || !header.startsWith(PREFIX)) {
    throw new AuthError("missing_bearer_token");
  }
  const token = header.slice(PREFIX.length).trim();
  if (!token) {
    throw new AuthError("missing_bearer_token");
  }
  try {
    const decoded = await admin.auth().verifyIdToken(token);
    return decoded.uid;
  } catch {
    throw new AuthError("invalid_token");
  }
}
