import { afterAll, describe, expect, it } from "vitest";
import { emulatorAvailable, emulatorDb, seed } from "./emulator";

// Proves the harness: skipped under plain `npm test`, run by `npm run test:emulator`.
describe.skipIf(!emulatorAvailable())("RTDB emulator harness", () => {
  afterAll(() => seed(null));

  it("seeds data and reads it back through firebase-admin", async () => {
    await seed({ codes: { abc: { active: true } } });
    const snap = await emulatorDb().ref("codes/abc").get();
    expect(snap.val()).toEqual({ active: true });
  });
});
