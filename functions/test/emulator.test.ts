import * as admin from "firebase-admin";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { assertEmulatorPresentInCi, emulatorAvailable, initEmulatorApp, seed } from "./emulator";

// Stand-in for production code in src/: uses bare admin.database(), the default app.
async function isCodeActive(code: string): Promise<boolean> {
  const snap = await admin.database().ref(`codes/${code}/active`).get();
  return snap.val() === true;
}

// Deliberately NOT inside skipIf: this is what stops a CI run passing with every emulator test skipped.
describe("emulator presence", () => {
  it("is present whenever CI is set", () => {
    expect(() => assertEmulatorPresentInCi()).not.toThrow();
  });
});

// Proves the harness: skipped under plain `npm test`, run by `npm run test:emulator`.
// Task 3 copies this pattern: initEmulatorApp(), seed(), then call the code under test.
describe.skipIf(!emulatorAvailable())("RTDB emulator harness", () => {
  beforeAll(() => initEmulatorApp());
  afterAll(() => seed(null));

  it("code using bare admin.database() reads what seed() wrote", async () => {
    await seed({ codes: { abc: { active: true } } });
    expect(await isCodeActive("abc")).toBe(true);
    expect(await isCodeActive("missing")).toBe(false);
  });
});
