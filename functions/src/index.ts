import { IncomingMessage, ServerResponse } from "node:http";
import * as admin from "firebase-admin";
import { logger } from "firebase-functions";
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

// Behind onRequest the runtime (gen2 functions-framework and the emulator alike) has
// already drained the request stream through its body parsers: the bytes are on
// req.rawBody and the parsed value on req.body. Iterating the stream then yields nothing
// and JSON.parse("") throws, so every call would answer 400. Read what the runtime kept
// first (rawBody, the authoritative bytes whatever the content type), then a parsed
// object, and only then the stream, which is what a bare node:http server (the route
// tests) still needs. test/body-parsed-route.test.ts pins this.
async function readJson(req: IncomingMessage): Promise<unknown> {
  const parsed = req as IncomingMessage & { rawBody?: unknown; body?: unknown };
  if (Buffer.isBuffer(parsed.rawBody)) return JSON.parse(parsed.rawBody.toString("utf8"));
  if (parsed.body !== null && typeof parsed.body === "object" && !Buffer.isBuffer(parsed.body)) {
    return parsed.body;
  }
  const chunks: Buffer[] = [];
  for await (const chunk of req) chunks.push(chunk as Buffer);
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

// One structured entry per route outcome. Severity is the contract the outage alert
// relies on (see docs/code-management-runbook.md section 7):
//   INFO     a normal answer (active / revoked / activated)
//   WARNING  a refusal: 401, 400, 409. Expected traffic, never paged on
//   ERROR    a server fault only. This is the one severity the alert counts
// Fields are named one by one. The activation code, the ID token, the request body and
// the `note` field must never be added here, and neither may an Error's message
// (database errors can embed the path, which contains the code).
type Route = "status" | "activate";
function logOutcome(
  severity: "info" | "warn" | "error",
  route: Route,
  outcome: string,
  fields: { uid?: string; httpStatus: number; reason?: string; errorName?: string },
): void {
  logger[severity](`code_management ${route} ${outcome}`, { route, outcome, ...fields });
}

// Codes become RTDB path segments, which cannot hold . $ # [ ] / or be empty.
const CODE_PATTERN = /^[^.$#[\]/\s]{1,128}$/;

// Identity first, and only from the verified token; the body supplies the code.
// Answers 401/400 itself and returns null when the request cannot proceed.
async function caller(
  route: Route,
  req: IncomingMessage,
  res: ServerResponse,
): Promise<{ uid: string; code: string } | null> {
  let uid: string;
  try {
    uid = await verifyBearer(req.headers.authorization);
  } catch (e) {
    if (e instanceof AuthError) {
      logOutcome("warn", route, "unauthorized", { httpStatus: e.status, reason: e.message });
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
    logOutcome("warn", route, "invalid_code", { uid, httpStatus: 400 });
    sendJson(res, 400, { error: "invalid_code" });
    return null;
  }
  return { uid, code };
}

async function status(req: IncomingMessage, res: ServerResponse): Promise<void> {
  const who = await caller("status", req, res);
  if (!who) return;
  const body = await statusFor(who.uid, who.code);
  logOutcome("info", "status", body.status, { uid: who.uid, httpStatus: 200 });
  sendJson(res, 200, body);
}

async function activate(req: IncomingMessage, res: ServerResponse): Promise<void> {
  const who = await caller("activate", req, res);
  if (!who) return;
  const outcome = await claim(who.uid, who.code);
  if (typeof outcome === "string") {
    logOutcome("warn", "activate", "refused", { uid: who.uid, httpStatus: 409, reason: outcome });
    sendJson(res, 409, { status: "refused", reason: outcome });
  } else {
    logOutcome("info", "activate", "activated", { uid: who.uid, httpStatus: 200 });
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
    const route: Route = path === "/status" ? "status" : "activate";
    (route === "status" ? status : activate)(req, res).catch((e: unknown) => {
      // The error's name only: its message may carry the database path, i.e. the code.
      logOutcome("error", route, "fault", {
        httpStatus: 500,
        errorName: e instanceof Error ? e.name : typeof e,
      });
      if (!res.headersSent) sendJson(res, 500, { error: "internal" });
    });
    return;
  }
  sendJson(res, 404, { error: "not_found" });
}

export const codeManagement = onRequest({ region: REGION }, (req, res) => {
  handle(req, res);
});
