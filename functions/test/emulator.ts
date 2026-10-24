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
