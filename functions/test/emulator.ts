// Harness for tests that need Realtime Database (Tasks 3 and 4).
//
// Run them with `npm run test:emulator`, which starts the RTDB emulator and sets
// FIREBASE_DATABASE_EMULATOR_HOST for the vitest process. firebase-admin then
// talks to the emulator with no credentials, so no live project is touched.
// `npm test` alone never needs the emulator.
import * as admin from "firebase-admin";

const PROJECT_ID = "demo-paramanu"; // "demo-" projects can never reach production.

export function emulatorAvailable(): boolean {
  return !!process.env.FIREBASE_DATABASE_EMULATOR_HOST;
}

export function emulatorDb(): admin.database.Database {
  if (!emulatorAvailable()) {
    throw new Error("FIREBASE_DATABASE_EMULATOR_HOST not set; use npm run test:emulator");
  }
  const app = admin.apps.find((a) => a?.name === "emulator-test") ??
    admin.initializeApp(
      { projectId: PROJECT_ID, databaseURL: `http://${process.env.FIREBASE_DATABASE_EMULATOR_HOST}?ns=${PROJECT_ID}` },
      "emulator-test",
    );
  return app.database();
}

// Replace the whole database with `data` (null clears it).
export async function seed(data: unknown): Promise<void> {
  await emulatorDb().ref("/").set(data);
}
