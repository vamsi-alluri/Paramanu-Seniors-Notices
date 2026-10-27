import * as admin from "firebase-admin";
import { describe, expect, it } from "vitest";

describe("index.ts app initialisation", () => {
  it("neither throws nor replaces a default app that already exists", async () => {
    const existing = admin.initializeApp({ projectId: "demo-existing" });
    await expect(import("../src/index")).resolves.toBeDefined();
    expect(admin.apps.length).toBe(1);
    expect(admin.app()).toBe(existing);
    expect(admin.app().options.projectId).toBe("demo-existing");
  });
});
