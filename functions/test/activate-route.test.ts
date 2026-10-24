import { createServer, Server } from "node:http";
import { AddressInfo } from "node:net";
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";

// Plain functions rather than vi.fn so rejecting stubs are not reported as unhandled.
let verify: (h: string | undefined) => Promise<string>;
let doClaim: (uid: string, code: string) => Promise<unknown>;
const claimCalls: Array<[string, string]> = [];
vi.mock("../src/auth", async (orig) => ({
  ...(await orig<typeof import("../src/auth")>()),
  verifyBearer: (h: string | undefined) => verify(h),
}));
vi.mock("../src/codes", () => ({
  statusFor: async () => ({ status: "revoked" }),
  claim: (uid: string, code: string) => {
    claimCalls.push([uid, code]);
    return doClaim(uid, code);
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
  claimCalls.length = 0;
  verify = async () => "uid-1";
  doClaim = async () => ({ status: "active" });
});

const post = (body: string) =>
  fetch(`${base}/activate`, { method: "POST", headers: { "Content-Type": "application/json" }, body });

describe("POST /activate", () => {
  it("401 when the token cannot be verified, without claiming", async () => {
    verify = async () => {
      throw new AuthError("invalid_token");
    };
    const res = await post(JSON.stringify({ code: "abc" }));
    expect(res.status).toBe(401);
    expect(claimCalls).toEqual([]);
  });

  it("400 for a code that cannot be an RTDB key, without claiming", async () => {
    expect((await post(JSON.stringify({ code: "a/b" }))).status).toBe(400);
    expect(claimCalls).toEqual([]);
  });

  it("200 with the claim's body, using the verified uid and the body's code", async () => {
    doClaim = async () => ({ status: "active", code: { code: "abc" } });
    const res = await post(JSON.stringify({ code: "abc", uid: "attacker" }));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ status: "active", code: { code: "abc" } });
    expect(claimCalls).toEqual([["uid-1", "abc"]]);
  });

  it.each(["unknown_code", "not_issued", "already_claimed", "revoked"])(
    "409 with status refused and reason %s",
    async (reason) => {
      doClaim = async () => reason;
      const res = await post(JSON.stringify({ code: "abc" }));
      expect(res.status).toBe(409);
      expect(await res.json()).toEqual({ status: "refused", reason });
    },
  );

  it("500, never a refusal or revoked body, when the claim fails", async () => {
    doClaim = async () => {
      throw new Error("db down");
    };
    const res = await post(JSON.stringify({ code: "abc" }));
    expect(res.status).toBe(500);
    const body = JSON.stringify(await res.json());
    expect(body).not.toContain("refused");
    expect(body).not.toContain("reason");
    expect(body).not.toContain("revoked");
  });

  it("404 for GET /activate", async () => {
    expect((await fetch(`${base}/activate`)).status).toBe(404);
  });
});
