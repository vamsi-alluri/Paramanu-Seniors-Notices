import { createServer, Server } from "node:http";
import { AddressInfo } from "node:net";
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";

// Plain functions rather than vi.fn so rejecting stubs are not reported as unhandled.
let verify: (h: string | undefined) => Promise<string>;
let status: (uid: string, code: string) => Promise<unknown>;
const statusCalls: Array<[string, string]> = [];
vi.mock("../src/auth", async (orig) => ({
  ...(await orig<typeof import("../src/auth")>()),
  verifyBearer: (h: string | undefined) => verify(h),
}));
vi.mock("../src/codes", () => ({
  statusFor: (uid: string, code: string) => {
    statusCalls.push([uid, code]);
    return status(uid, code);
  },
}));

import { AuthError } from "../src/auth";
import { handle } from "../src/index";

let server: Server;
let base: string;
beforeAll(async () => {
  server = createServer(handle);
  await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});
afterAll(() => new Promise((r) => server.close(r)));
beforeEach(() => {
  statusCalls.length = 0;
  verify = async () => "uid-1";
  status = async () => ({ status: "active" });
});

const post = (body: string, headers: Record<string, string> = {}) =>
  fetch(`${base}/status`, { method: "POST", headers: { "Content-Type": "application/json", ...headers }, body });

describe("POST /status", () => {
  it("401 when the token cannot be verified, without touching the database", async () => {
    verify = async () => {
      throw new AuthError("invalid_token");
    };
    const res = await post(JSON.stringify({ code: "abc" }));
    expect(res.status).toBe(401);
    expect(statusCalls).toEqual([]);
  });

  it.each([
    ["not JSON", "{nope"],
    ["no code", "{}"],
    ["a non-string code", JSON.stringify({ code: 5 })],
    ["an empty code", JSON.stringify({ code: "" })],
    ["a code that cannot be an RTDB key", JSON.stringify({ code: "a/b" })],
    ["a code with a dot", JSON.stringify({ code: "a.b" })],
  ])("400 for %s", async (_n, body) => {
    const res = await post(body);
    expect(res.status).toBe(400);
    expect(statusCalls).toEqual([]);
  });

  it("200 with the StatusResponse, using the verified uid and the body's code", async () => {
    status = async () => ({ status: "revoked" });
    const res = await post(JSON.stringify({ code: "abc", uid: "attacker" }));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ status: "revoked" });
    expect(statusCalls).toEqual([["uid-1", "abc"]]);
  });

  it("500, never a revoked body, when the lookup fails", async () => {
    status = async () => {
      throw new Error("db down");
    };
    const res = await post(JSON.stringify({ code: "abc" }));
    expect(res.status).toBe(500);
    expect(JSON.stringify(await res.json())).not.toContain("revoked");
  });

  it("405-style 404 for GET /status", async () => {
    expect((await fetch(`${base}/status`)).status).toBe(404);
  });
});
