import * as admin from "firebase-admin";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { readCode, statusFor } from "../src/codes";
import { emulatorAvailable, initEmulatorApp, seed } from "./emulator";

const UID = "uid-1";
const DISPENSARY = { label: "Main Dispensary", topic: "notices_main" };
const ISSUED = { issued: 1700000000000 };

// Only src/codes and src/auth are imported here, never src/index: index.ts
// initialises a default app at import time, which would pre-empt the emulator app.
describe.skipIf(!emulatorAvailable())("statusFor against the RTDB emulator", () => {
  beforeAll(() => initEmulatorApp());
  afterAll(() => seed(null));
  beforeEach(() =>
    seed({
      dispensaries: { d1: DISPENSARY },
      codes: {
        good: { ...ISSUED, usedBy: UID, dispensary: "d1", activatedAt: 1700000001000, note: "SECRET holder details" },
        revoked: { ...ISSUED, usedBy: UID, dispensary: "d1", revoked: true },
        noIssued: { usedBy: UID, dispensary: "d1" },
        unclaimed: { ...ISSUED, dispensary: "d1" },
        other: { ...ISSUED, usedBy: "someone-else", dispensary: "d1" },
      },
    }),
  );

  it("is talking to the emulator, not a real database", () => {
    const url = admin.app().options.databaseURL ?? "";
    expect(url).toContain(process.env.FIREBASE_DATABASE_EMULATOR_HOST);
    expect(admin.app().options.projectId).toBe("demo-paramanu");
  });

  it("readCode returns null for an absent node", async () => {
    expect(await readCode("nope")).toBeNull();
  });

  it.each([
    ["the node is absent entirely", "nope"],
    ["revoked is true", "revoked"],
    ["issued is absent", "noIssued"],
    ["usedBy is null", "unclaimed"],
    ["usedBy differs from the uid", "other"],
  ])("returns revoked when %s", async (_name, code) => {
    expect(await statusFor(UID, code)).toEqual({ status: "revoked" });
  });

  it("returns active with the code and dispensary node when the claim stands", async () => {
    expect(await statusFor(UID, "good")).toEqual({
      status: "active",
      code: { code: "good", dispensary: "d1", revoked: false, activatedAt: 1700000001000 },
      dispensary: DISPENSARY,
    });
  });

  it("never lets note reach the response", async () => {
    const body = JSON.stringify(await statusFor(UID, "good"));
    expect(body).not.toContain("note");
    expect(body).not.toContain("SECRET");
  });

  it("does not treat revoked:false or a non-true revoked as revoked", async () => {
    await admin.database().ref("codes/good/revoked").set(false);
    expect((await statusFor(UID, "good")).status).toBe("active");
  });
});
