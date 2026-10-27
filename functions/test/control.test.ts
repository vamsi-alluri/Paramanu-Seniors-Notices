import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import {
  BANNER_MAX_BYTES,
  bannerChanged,
  bannerPush,
  decideCode,
  devicePush,
  handleBannerWrite,
  handleCodeWrite,
  stillHolds,
  type Push,
} from "../src/control";
import { emulatorAvailable, initEmulatorApp, seed } from "./emulator";

const UID = "AbCdEf0123456789xyzUVW012345"; // mixed case, as Firebase issues them
const ISSUED = { issued: 1700000000000, dispensary: "barc-vashi" };
const held = { ...ISSUED, usedBy: UID, activatedAt: 1700000001000 };
const disabled = { ...held, revoked: true };
const unclaimed = { ...ISSUED };

// The holder's UID and the code are never logged; the logger is spied on to prove it.
// vi.hoisted, because vi.mock runs before this file's other declarations.
const logged = vi.hoisted((): string[] => []);
vi.mock("firebase-functions", () => ({
  logger: {
    info: (...args: unknown[]) => logged.push(JSON.stringify(args)),
    warn: (...args: unknown[]) => logged.push(JSON.stringify(args)),
    error: (...args: unknown[]) => logged.push(JSON.stringify(args)),
  },
}));

describe("decideCode", () => {
  it.each([
    ["disable", held, disabled],
    ["unlink", held, unclaimed],
    ["unlink of a disabled code", disabled, unclaimed],
    ["delete", held, null],
    ["usedBy hand-edited to another install", held, { ...held, usedBy: "someone-else" }],
    ["issued removed", held, { ...held, issued: null }],
  ])("revokes the previous holder on %s", (_name, before, after) => {
    expect(decideCode(before, after)).toEqual({ type: "revoke", uid: UID });
  });

  it("resumes the holder when a disabled code is enabled", () => {
    expect(decideCode(disabled, held)).toEqual({ type: "resume", uid: UID });
  });

  it.each([
    ["a claim", unclaimed, held],
    ["a new code", null, unclaimed],
    ["a note edit on a held code", held, { ...held, note: "x" }],
    ["a note edit on a disabled code", disabled, { ...disabled, note: "x" }],
    ["disabling an unclaimed code", unclaimed, { ...unclaimed, revoked: true }],
    ["deleting an unclaimed code", unclaimed, null],
  ])("sends nothing for %s", (_name, before, after) => {
    expect(decideCode(before, after)).toBeNull();
  });

  it("never addresses the after-image's holder", () => {
    expect(decideCode({ ...held, usedBy: "old" }, held)).toEqual({ type: "revoke", uid: "old" });
  });
});

describe("stillHolds", () => {
  it("drops a revoke for a code enabled again since", () => {
    expect(stillHolds("revoke", UID, held)).toBe(false);
    expect(stillHolds("revoke", UID, disabled)).toBe(true);
    expect(stillHolds("revoke", UID, null)).toBe(true);
  });

  it("drops a resume for a code disabled or unlinked since", () => {
    expect(stillHolds("resume", UID, held)).toBe(true);
    expect(stillHolds("resume", UID, disabled)).toBe(false);
    expect(stillHolds("resume", UID, unclaimed)).toBe(false);
  });
});

describe("devicePush", () => {
  it("addresses the install by its UID, case kept, with the code and the stamp", () => {
    expect(devicePush("revoke", UID, "A1B2C3D4", 123)).toEqual({
      topic: `device-v1-${UID}`,
      data: { type: "revoke", code: "A1B2C3D4", at: "123" },
    });
  });

  it("refuses a UID that is not a valid topic name", () => {
    expect(devicePush("revoke", "has space", "A1B2C3D4", 1)).toBeNull();
    expect(devicePush("revoke", "a/b", "A1B2C3D4", 1)).toBeNull();
  });
});

describe("banner", () => {
  const info = { heading: "H", lines: "L", html: "<b>Closed</b>", htmlUpdated: 50 };

  it("counts only html and htmlUpdated as a change", () => {
    expect(bannerChanged(info, { ...info, heading: "New" })).toBe(false);
    expect(bannerChanged(info, { ...info, html: "<b>Open</b>", htmlUpdated: 60 })).toBe(true);
    expect(bannerChanged(info, { ...info, html: null, htmlUpdated: 60 })).toBe(true);
    expect(bannerChanged(null, info)).toBe(true);
  });

  it("pushes to the dispensary's banner topic, stamped with htmlUpdated", () => {
    expect(bannerPush("barc-vashi", info)).toEqual({
      push: {
        topic: "banner-v1-barc-vashi",
        data: { type: "banner", dispensary: "barc-vashi", html: "<b>Closed</b>", at: "50" },
      },
    });
  });

  it("pushes a removal as an empty html", () => {
    const result = bannerPush("barc-vashi", { heading: "H", htmlUpdated: 60 });
    expect("push" in result && result.push.data.html).toBe("");
  });

  it("drops a banner with no stamp, or one too large for FCM", () => {
    expect(bannerPush("barc-vashi", { html: "x" })).toHaveProperty("drop");
    // Three bytes a character, as Marathi or Hindi would be: under the limit in characters, over it in bytes.
    const heavy = "क".repeat(Math.floor(BANNER_MAX_BYTES / 3) + 1);
    expect(bannerPush("barc-vashi", { html: heavy, htmlUpdated: 1 })).toHaveProperty("drop");
    expect(bannerPush("barc-vashi", { html: "क".repeat(Math.floor(BANNER_MAX_BYTES / 3)), htmlUpdated: 1 }))
      .toHaveProperty("push");
  });
});

describe("handleCodeWrite", () => {
  beforeEach(() => {
    logged.length = 0;
  });

  const event = (before: unknown, after: unknown) => ({ code: "A1B2C3D4", before, after, at: 99, eventId: "ev-1" });

  it("sends a disable to the device and to control-v1, and logs neither the UID nor the code", async () => {
    const sent: Push[] = [];
    await handleCodeWrite(event(held, disabled), { read: async () => disabled, send: async (p) => void sent.push(p) });
    const data = { type: "revoke", code: "A1B2C3D4", at: "99" };
    expect(sent).toEqual([
      { topic: `device-v1-${UID}`, data },
      { topic: "control-v1", data },
    ]);
    expect(logged.join("\n")).not.toContain(UID);
    expect(logged.join("\n")).not.toContain("A1B2C3D4");
  });

  it.each([
    ["unlink", unclaimed],
    ["delete", null],
  ])("sends an %s to the device only, never to control-v1", async (_name, after) => {
    const sent: Push[] = [];
    await handleCodeWrite(event(held, after), { read: async () => after, send: async (p) => void sent.push(p) });
    expect(sent.map((p) => p.topic)).toEqual([`device-v1-${UID}`]);
  });

  it("sends an enable to both", async () => {
    const sent: Push[] = [];
    await handleCodeWrite(event(disabled, held), { read: async () => held, send: async (p) => void sent.push(p) });
    expect(sent.map((p) => [p.topic, p.data.type])).toEqual([
      [`device-v1-${UID}`, "resume"],
      ["control-v1", "resume"],
    ]);
  });

  it("drops a revoke superseded by a later enable", async () => {
    const send = vi.fn();
    await handleCodeWrite(event(held, disabled), { read: async () => held, send });
    expect(send).not.toHaveBeenCalled();
  });

  it("reads nothing for a write that concerns no phone", async () => {
    const read = vi.fn();
    await handleCodeWrite(event(unclaimed, held), { read, send: vi.fn() });
    expect(read).not.toHaveBeenCalled();
  });

  it("swallows a refusal that would fail the same way on every retry", async () => {
    const refusal = Object.assign(new Error("bad"), { code: "messaging/invalid-argument" });
    await expect(
      handleCodeWrite(event(held, disabled), { read: async () => disabled, send: async () => Promise.reject(refusal) }),
    ).resolves.toBeUndefined();
  });

  it("throws anything else, so the event is retried", async () => {
    const outage = Object.assign(new Error("down"), { code: "messaging/server-unavailable" });
    await expect(
      handleCodeWrite(event(held, disabled), { read: async () => disabled, send: async () => Promise.reject(outage) }),
    ).rejects.toBe(outage);
  });
});

describe("handleBannerWrite", () => {
  it("sends the banner as it is now, not the write's after-image, to its topic and to control-v1", async () => {
    const sent: Push[] = [];
    await handleBannerWrite(
      { dispensary: "barc-vashi", before: {}, after: { html: "first", htmlUpdated: 1 }, eventId: "ev" },
      { read: async () => ({ html: "second", htmlUpdated: 2 }), send: async (p) => void sent.push(p) },
    );
    const data = { type: "banner", dispensary: "barc-vashi", html: "second", at: "2" };
    expect(sent).toEqual([
      { topic: "banner-v1-barc-vashi", data },
      { topic: "control-v1", data },
    ]);
  });

  it("reads and sends nothing when only the fallback lines changed", async () => {
    const read = vi.fn();
    const send = vi.fn();
    await handleBannerWrite(
      { dispensary: "barc-vashi", before: { lines: "a", htmlUpdated: 1 }, after: { lines: "b", htmlUpdated: 1 }, eventId: "ev" },
      { read, send },
    );
    expect(read).not.toHaveBeenCalled();
    expect(send).not.toHaveBeenCalled();
  });
});

// The default read, against the emulator: the re-read must see the node at its real path.
describe.skipIf(!emulatorAvailable())("control re-read against the RTDB emulator", () => {
  beforeAll(() => initEmulatorApp());
  afterAll(() => seed(null));

  it("sends a resume only while the code still stands", async () => {
    const sent: Push[] = [];
    const send = async (p: Push) => void sent.push(p);
    const resume = { code: "A1B2C3D4", before: disabled, after: held, at: 7, eventId: "ev" };

    await seed({ codes: { A1B2C3D4: held } });
    await handleCodeWrite(resume, { send });
    expect(sent.map((p) => p.data.type)).toEqual(["resume", "resume"]);

    await seed({ codes: { A1B2C3D4: disabled } });
    await handleCodeWrite(resume, { send });
    expect(sent).toHaveLength(2);
  });

  it("reads the dispensary's info node for the banner", async () => {
    const sent: Push[] = [];
    await seed({ dispensaries: { "barc-vashi": { label: "BARC", info: { html: "now", htmlUpdated: 5 } } } });
    await handleBannerWrite(
      { dispensary: "barc-vashi", before: null, after: { html: "now", htmlUpdated: 5 }, eventId: "ev" },
      { send: async (p) => void sent.push(p) },
    );
    expect(sent.map((p) => p.data.html)).toEqual(["now", "now"]);
  });
});
