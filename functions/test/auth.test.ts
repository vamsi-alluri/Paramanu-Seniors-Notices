import { beforeEach, describe, expect, it, vi } from "vitest";

// A plain function, not vi.fn: vi.fn records the promise it returns, and for a
// rejecting stub vitest reports that record as an unhandled rejection.
let verifyIdToken: (token: string) => Promise<{ uid: string }>;
const calls: string[] = [];
vi.mock("firebase-admin", () => ({
  auth: () => ({
    verifyIdToken: (token: string) => {
      calls.push(token);
      return verifyIdToken(token);
    },
  }),
}));

import { AuthError, verifyBearer } from "../src/auth";

describe("verifyBearer", () => {
  beforeEach(() => {
    calls.length = 0;
    verifyIdToken = async () => {
      throw new Error("unexpected verifyIdToken call");
    };
  });

  it("resolves to the uid of the verified token", async () => {
    verifyIdToken = async () => ({ uid: "uid-123" });
    await expect(verifyBearer("Bearer good-token")).resolves.toBe("uid-123");
    expect(calls).toEqual(["good-token"]);
  });

  it.each([
    ["a missing header", undefined],
    ["an empty header", ""],
    ["a header without the Bearer prefix", "good-token"],
    ["a non-Bearer scheme", "Basic abc"],
    ["a Bearer prefix with no token", "Bearer "],
  ])("rejects %s with 401 without calling verifyIdToken", async (_name, header) => {
    const err = await verifyBearer(header).catch((e) => e);
    expect(err).toBeInstanceOf(AuthError);
    expect(err.status).toBe(401);
    expect(calls).toEqual([]);
  });

  it("rejects a token verifyIdToken refuses with 401, not a different error", async () => {
    verifyIdToken = async () => {
      throw new Error("auth/id-token-expired");
    };
    const err = await verifyBearer("Bearer expired").catch((e) => e);
    expect(err).toBeInstanceOf(AuthError);
    expect(err.status).toBe(401);
  });
});
