import * as admin from "firebase-admin";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { claim } from "../src/codes";
import { emulatorAvailable, initEmulatorApp, seed } from "./emulator";

const UID = "uid-1";
const DISPENSARY = { label: "Main Dispensary", topic: "notices_main" };
const ISSUED = { issued: 1700000000000 };
const NOTE = "SECRET holder details";

const raw = async (code: string) => (await admin.database().ref(`codes/${code}`).get()).val();

// Only src/codes is imported here, never src/index: index.ts initialises a default
// app at import time, which would pre-empt the emulator app. Every test uses its own
// code key, so nothing shares a node.
describe.skipIf(!emulatorAvailable())("claim against the RTDB emulator", () => {
  beforeAll(() => initEmulatorApp());
  afterAll(() => seed(null));
  beforeEach(() =>
    seed({
      dispensaries: { d1: DISPENSARY },
      codes: {
        free: { ...ISSUED, dispensary: "d1", note: NOTE },
        noIssued: { dispensary: "d1" },
        taken: { ...ISSUED, usedBy: "someone-else", dispensary: "d1", activatedAt: 1700000001000 },
        revoked: { ...ISSUED, revoked: true, dispensary: "d1" },
        mine: { ...ISSUED, usedBy: UID, dispensary: "d1", activatedAt: 1700000001000, note: NOTE },
        untouched: { ...ISSUED, dispensary: "d1", note: NOTE, revoked: false },
        nodisp: { ...ISSUED },
      },
    }),
  );

  it("is talking to the emulator, not a real database", () => {
    const url = admin.app().options.databaseURL ?? "";
    expect(url).toContain(process.env.FIREBASE_DATABASE_EMULATOR_HOST);
    expect(admin.app().options.projectId).toBe("demo-paramanu");
  });

  it.each([
    ["an absent node", "nope", "unknown_code"],
    ["a node without issued", "noIssued", "not_issued"],
    ["a node claimed by a different uid", "taken", "already_claimed"],
    ["a revoked node", "revoked", "revoked"],
  ])("refuses %s", async (_name, code, reason) => {
    const before = await raw(code);
    expect(await claim(UID, code)).toBe(reason);
    expect(await raw(code)).toEqual(before); // a refusal writes nothing, not even a node
  });

  it("claims a free code, answering as /status would", async () => {
    const res = await claim(UID, "free");
    expect(res).toMatchObject({
      status: "active",
      code: { code: "free", dispensary: "d1", revoked: false },
      dispensary: DISPENSARY,
    });
    const stored = await raw("free");
    expect(stored.usedBy).toBe(UID);
    expect(typeof stored.activatedAt).toBe("number");
    expect((res as { code: { activatedAt: number } }).code.activatedAt).toBe(stored.activatedAt);
  });

  it("never lets note reach the response", async () => {
    const body = JSON.stringify(await claim(UID, "free"));
    expect(body).not.toContain("note");
    expect(body).not.toContain("SECRET");
  });

  it("writes only usedBy and activatedAt", async () => {
    await claim(UID, "untouched");
    const { usedBy, activatedAt, ...rest } = await raw("untouched");
    expect(usedBy).toBe(UID);
    expect(typeof activatedAt).toBe("number");
    expect(rest).toEqual({ ...ISSUED, dispensary: "d1", note: NOTE, revoked: false });
  });

  it("succeeds without a dispensary and omits it from the response", async () => {
    const res = await claim(UID, "nodisp");
    expect(res).toMatchObject({ status: "active" });
    expect(res).not.toHaveProperty("dispensary");
  });

  it("treats a retry by the same uid as success and does not rewrite activatedAt", async () => {
    const before = await raw("mine");
    const res = await claim(UID, "mine");
    expect(res).toEqual({
      status: "active",
      code: { code: "mine", dispensary: "d1", revoked: false, activatedAt: 1700000001000 },
      dispensary: DISPENSARY,
    });
    expect(await raw("mine")).toEqual(before);
  });

  it("lets exactly one of two concurrent claims with different uids win", async () => {
    // Many rounds, each on its own fresh code, both claims in flight at once.
    const rounds = Array.from({ length: 20 }, (_, i) => `race${i}`);
    await Promise.all(rounds.map((c) => admin.database().ref(`codes/${c}`).set({ ...ISSUED })));
    for (const code of rounds) {
      const [a, b] = await Promise.all([claim("uid-a", code), claim("uid-b", code)]);
      const results = [a, b];
      expect(results.filter((r) => typeof r === "object")).toHaveLength(1);
      expect(results.filter((r) => r === "already_claimed")).toHaveLength(1);
      const winner = typeof a === "object" ? "uid-a" : "uid-b";
      expect((await raw(code)).usedBy).toBe(winner);
    }
  });
});
