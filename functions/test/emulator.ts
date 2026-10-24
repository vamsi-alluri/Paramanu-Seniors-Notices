// Harness for tests that need Realtime Database (Tasks 3 and 4).
//
// Run them with `npm run test:emulator`, which starts the RTDB emulator and sets
// FIREBASE_DATABASE_EMULATOR_HOST for the vitest process. `npm test` alone never
// needs the emulator.
//
// Production code calls bare `admin.database()`, i.e. the DEFAULT app. Call
// initEmulatorApp() in beforeAll so that default app points at the emulator;
// then seed() and the code under test see the same database.
import * as admin from "firebase-admin";

const PROJECT_ID = "demo-paramanu"; // "demo-" projects can never reach production.

export function emulatorAvailable(): boolean {
  return !!process.env.FIREBASE_DATABASE_EMULATOR_HOST;
}

// The emulator describes are skipIf(!emulatorAvailable()), so without this a CI run whose
// emulator never started would skip every atomic-claim and /status test and exit 0.
// Under CI (GitHub Actions sets CI=true) a missing emulator is a hard failure; a bare local
// `npm test` has CI unset and still skips cleanly. An empty CI counts as unset, which is how
// the workflow's unit-only step opts out.
export function assertEmulatorPresentInCi(): void {
  if (process.env.CI && !emulatorAvailable()) {
    throw new Error(
      "CI is set but FIREBASE_DATABASE_EMULATOR_HOST is not: the emulator suite would be " +
        "skipped, not run. Use `npm run test:emulator`.",
    );
  }
}

// Idempotent. Initialises the default firebase-admin app against the emulator.
export function initEmulatorApp(): void {
  if (!emulatorAvailable()) {
    throw new Error("FIREBASE_DATABASE_EMULATOR_HOST not set; use npm run test:emulator");
  }
  if (admin.apps.length > 0) return;
  admin.initializeApp({
    projectId: PROJECT_ID,
    databaseURL: `http://${process.env.FIREBASE_DATABASE_EMULATOR_HOST}?ns=${PROJECT_ID}`,
  });
}

// Replace the whole database with `data` (null clears it).
export async function seed(data: unknown): Promise<void> {
  await admin.database().ref("/").set(data);
}
