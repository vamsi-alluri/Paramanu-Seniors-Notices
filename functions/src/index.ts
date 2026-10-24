import { IncomingMessage, ServerResponse } from "node:http";
import * as admin from "firebase-admin";
import { onRequest } from "firebase-functions/v2/https";
import { AuthError, verifyBearer } from "./auth";
import { claim, statusFor } from "./codes";

// Must match the Realtime Database region (paramanu-seniors-default-rtdb is in
// asia-southeast1) so the function sits next to the data it reads.
const REGION = "asia-southeast1";

// Default app, once, from the runtime environment (Application Default
// Credentials; project and database URL come from the environment). No key file.
if (admin.apps.length === 0) {
  admin.initializeApp();
}

function sendJson(res: ServerResponse, status: number, body: unknown): void {
  res.statusCode = status;
  res.setHeader("Content-Type", "application/json");
  res.end(JSON.stringify(body));
}

async function readJson(req: IncomingMessage): Promise<unknown> {
  const chunks: Buffer[] = [];
  for await (const chunk of req) chunks.push(chunk as Buffer);
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

// Codes become RTDB path segments, which cannot hold . $ # [ ] / or be empty.
const CODE_PATTERN = /^[^.$#[\]/\s]{1,128}$/;

// Identity first, and only from the verified token; the body supplies the code.
// Answers 401/400 itself and returns null when the request cannot proceed.
async function caller(
  req: IncomingMessage,
  res: ServerResponse,
): Promise<{ uid: string; code: string } | null> {
  let uid: string;
  try {
    uid = await verifyBearer(req.headers.authorization);
  } catch (e) {
    if (e instanceof AuthError) {
      sendJson(res, e.status, { error: e.message });
      return null;
    }
    throw e;
  }
  let code: unknown;
  try {
    code = ((await readJson(req)) as { code?: unknown } | null)?.code;
  } catch {
    code = undefined;
  }
  if (typeof code !== "string" || !CODE_PATTERN.test(code)) {
    sendJson(res, 400, { error: "invalid_code" });
    return null;
  }
  return { uid, code };
}

async function status(req: IncomingMessage, res: ServerResponse): Promise<void> {
  const who = await caller(req, res);
  if (who) sendJson(res, 200, await statusFor(who.uid, who.code));
}

async function activate(req: IncomingMessage, res: ServerResponse): Promise<void> {
  const who = await caller(req, res);
  if (!who) return;
  const outcome = await claim(who.uid, who.code);
  if (typeof outcome === "string") {
    sendJson(res, 409, { status: "refused", reason: outcome });
  } else {
    sendJson(res, 200, outcome);
  }
}

// Plain node http handler so tests can drive it without the Functions runtime.
export function handle(req: IncomingMessage, res: ServerResponse): void {
  const path = new URL(req.url ?? "/", "http://localhost").pathname;
  if (req.method === "GET" && path === "/health") {
    sendJson(res, 200, { ok: true });
    return;
  }
  if (req.method === "POST" && (path === "/status" || path === "/activate")) {
    // A fault is a 500 (the client reads it as inconclusive), never a "revoked" or
    // "refused" body.
    (path === "/status" ? status : activate)(req, res).catch(() => {
      if (!res.headersSent) sendJson(res, 500, { error: "internal" });
    });
    return;
  }
  sendJson(res, 404, { error: "not_found" });
}

export const codeManagement = onRequest({ region: REGION }, (req, res) => {
  handle(req, res);
});
