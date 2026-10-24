import { createServer, Server } from "node:http";
import { AddressInfo } from "node:net";
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";

type Entry = { severity: string; message: string; fields: Record<string, unknown> };
const entries: Entry[] = [];
vi.mock("firebase-functions", () => {
  const at = (severity: string) => (message: string, fields: Record<string, unknown>) => {
    entries.push({ severity, message, fields });
  };
  return { logger: { info: at("info"), warn: at("warn"), error: at("error") } };
});

let verify: (h: string | undefined) => Promise<string>;
let status: () => Promise<unknown>;
let claim: () => Promise<unknown>;
vi.mock("../src/auth", async (orig) => ({
  ...(await orig<typeof import("../src/auth")>()),
  verifyBearer: (h: string | undefined) => verify(h),
}));
vi.mock("../src/codes", () => ({
  statusFor: () => status(),
  claim: () => claim(),
}));

import { AuthError } from "../src/auth";
import { handle } from "../src/index";

const SECRET_CODE = "SECRET-CODE-123";
const SECRET_TOKEN = "secret.id.token";

let server: Server;
let base: string;
beforeAll(async () => {
  server = createServer(handle);
  await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});
afterAll(() => new Promise((r) => server.close(r)));
beforeEach(() => {
  entries.length = 0;
  verify = async () => "uid-1";
  status = async () => ({ status: "active" });
  claim = async () => ({ status: "active" });
});

const call = (route: string, code: unknown = SECRET_CODE) =>
  fetch(`${base}/${route}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${SECRET_TOKEN}` },
    body: JSON.stringify({ code }),
  });

// Everything a real logger could serialise from the captured entries. JSON.stringify alone
// renders an Error as {}, so Error values are expanded to name, message and stack here.
function reachableText(value: unknown, seen = new Set<unknown>()): string {
  if (value === null || value === undefined) return "";
  if (typeof value !== "object") return String(value);
  if (seen.has(value)) return "";
  seen.add(value);
  const own = value instanceof Error ? [value.name, value.message, value.stack ?? ""] : [];
  const nested = Object.entries(value).flatMap(([k, v]) => [k, reachableText(v, seen)]);
  return [...own, ...nested].join("\n");
}

const severities =() => entries.map((e) => e.severity);

describe("route outcome logging", () => {
  it("logs a normal answer at info", async () => {
    await call("status");
    expect(entries).toHaveLength(1);
    expect(entries[0].severity).toBe("info");
    expect(entries[0].fields).toMatchObject({ route: "status", outcome: "active", uid: "uid-1", httpStatus: 200 });
  });

  it("logs a revoked answer at info, not as a fault", async () => {
    status = async () => ({ status: "revoked" });
    await call("status");
    expect(severities()).toEqual(["info"]);
    expect(entries[0].fields.outcome).toBe("revoked");
  });

  it("logs a 409 refusal at warn, never error", async () => {
    claim = async () => "already_claimed";
    const res = await call("activate");
    expect(res.status).toBe(409);
    expect(severities()).toEqual(["warn"]);
    expect(entries[0].fields).toMatchObject({ outcome: "refused", reason: "already_claimed", httpStatus: 409 });
  });

  it("logs a bad token at warn", async () => {
    verify = async () => {
      throw new AuthError("invalid_token");
    };
    expect((await call("status")).status).toBe(401);
    expect(severities()).toEqual(["warn"]);
  });

  it("logs a bad code at warn", async () => {
    expect((await call("activate", "a/b")).status).toBe(400);
    expect(severities()).toEqual(["warn"]);
  });

  it.each(["status", "activate"])("logs a %s server fault at error and answers 500", async (route) => {
    const boom = async () => {
      throw new Error(`failed reading codes/${SECRET_CODE}`);
    };
    status = boom;
    claim = boom;
    const res = await call(route);
    expect(res.status).toBe(500);
    expect(severities()).toEqual(["error"]);
    expect(entries[0].fields).toMatchObject({ route, outcome: "fault", httpStatus: 500, errorName: "Error" });
  });

  it("never logs the code, the token, the note or an error message", async () => {
    claim = async () => {
      throw new Error(`failed reading codes/${SECRET_CODE}`);
    };
    status = async () => ({ status: "active", note: "holder details" });
    const BAD_CODE = "a/b";
    await call("status");
    await call("activate");
    // The malformed-code path must run while the token still verifies, or it answers 401.
    expect((await call("activate", BAD_CODE)).status).toBe(400);
    verify = async () => {
      throw new AuthError("invalid_token");
    };
    expect((await call("status")).status).toBe(401);
    expect(entries.map((e) => e.fields.outcome)).toEqual([
      "active",
      "fault",
      "invalid_code",
      "unauthorized",
    ]);
    const everything = reachableText(entries);
    for (const secret of [SECRET_CODE, BAD_CODE, SECRET_TOKEN, "holder details", "failed reading"]) {
      expect(everything).not.toContain(secret);
    }
  });
});
