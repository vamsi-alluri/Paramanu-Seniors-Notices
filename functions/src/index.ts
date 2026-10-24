import { IncomingMessage, ServerResponse } from "node:http";
import { onRequest } from "firebase-functions/v2/https";

// Must match the Realtime Database region (paramanu-seniors-default-rtdb is in
// asia-southeast1) so the function sits next to the data it reads.
const REGION = "asia-southeast1";

function sendJson(res: ServerResponse, status: number, body: unknown): void {
  res.statusCode = status;
  res.setHeader("Content-Type", "application/json");
  res.end(JSON.stringify(body));
}

// Plain node http handler so tests can drive it without the Functions runtime.
export function handle(req: IncomingMessage, res: ServerResponse): void {
  const path = new URL(req.url ?? "/", "http://localhost").pathname;
  if (req.method === "GET" && path === "/health") {
    sendJson(res, 200, { ok: true });
    return;
  }
  sendJson(res, 404, { error: "not_found" });
}

export const codeManagement = onRequest({ region: REGION }, (req, res) => {
  handle(req, res);
});
