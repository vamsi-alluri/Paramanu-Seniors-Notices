import { describe, expect, it, vi } from "vitest";
import { configStalePush, handleConfigUpdate } from "../src/config";
import type { Push } from "../src/control";

vi.mock("firebase-functions", () => ({
  logger: { info: () => undefined, warn: () => undefined, error: () => undefined },
}));

describe("configStalePush", () => {
  it("is a stamped configStale on control-v2, every value a string", () => {
    // The app's ControlTopics.CONFIG and ControlMessage.parse read exactly this.
    expect(configStalePush(1789000000000)).toEqual({
      topic: "control-v2",
      data: { type: "configStale", at: "1789000000000" },
    });
  });
});

describe("handleConfigUpdate", () => {
  it("sends one configStale per publish", async () => {
    const sent: Push[] = [];
    await handleConfigUpdate({ at: 5, eventId: "ev" }, { send: async (p) => void sent.push(p) });
    expect(sent).toEqual([configStalePush(5)]);
  });

  it("swallows a refusal that would fail the same way on every retry", async () => {
    const refused = Object.assign(new Error("bad"), { code: "messaging/invalid-argument" });
    await expect(
      handleConfigUpdate({ at: 5, eventId: "ev" }, { send: async () => Promise.reject(refused) }),
    ).resolves.toBeUndefined();
  });

  it("throws anything else, so the event is retried", async () => {
    const down = Object.assign(new Error("down"), { code: "messaging/server-unavailable" });
    await expect(
      handleConfigUpdate({ at: 5, eventId: "ev" }, { send: async () => Promise.reject(down) }),
    ).rejects.toBe(down);
  });
});
