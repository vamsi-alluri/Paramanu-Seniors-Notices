import { createServer, IncomingMessage, Server, ServerResponse } from "node:http";
import { AddressInfo } from "node:net";
import bodyParser from "body-parser";
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";

// The Functions runtime (gen2 functions-framework and the emulator) mounts body parsers
// BEFORE the user handler, so by the time handle() runs the request stream is already
// drained: the parsed value is on req.body and the bytes on req.rawBody. Every other
// route suite drives a bare node:http server and cannot see that layer. This one mounts
// the same parsers the emulator does (functionsEmulatorRuntime.js: json/text/urlencoded/
// raw, each recording rawBody) and asserts a well-formed call still reaches the handler.

const statusCalls: Array<[string, string]> = [];
const claimCalls: Array<[string, string]> = [];
vi.mock("../src/auth", async (orig) => ({
  ...(await orig<typeof import("../src/auth")>()),
  verifyBearer: async () => "uid-1",
}));
vi.mock("../src/codes", () => ({
  statusFor: async (uid: string, code: string) => {
    statusCalls.push([uid, code]);
    return { status: "active" };
  },
  claim: async (uid: string, code: string) => {
    claimCalls.push([uid, code]);
    return { code: { dispensary: "d1" }, dispensary: { label: "L" } };
  },
}));

import { handle } from "../src/index";

const rawBodySaver = (req: IncomingMessage, _res: ServerResponse, buf: Buffer) => {
  (req as IncomingMessage & { rawBody?: Buffer }).rawBody = buf;
};

type Middleware = (req: IncomingMessage, res: ServerResponse, next: (e?: unknown) => void) => void;
const parsers: Middleware[] = [
  bodyParser.json({ verify: rawBodySaver }) as Middleware,
  bodyParser.text({ verify: rawBodySaver }) as Middleware,
  bodyParser.urlencoded({ extended: true, verify: rawBodySaver }) as Middleware,
  bodyParser.raw({ type: "*/*", verify: rawBodySaver }) as Middleware,
];

function parsedThenHandle(req: IncomingMessage, res: ServerResponse): void {
  let i = 0;
  const next = (err?: unknown): void => {
    if (err) {
      res.statusCode = 400;
      res.end();
      return;
    }
    const parser = parsers[i++];
    if (parser) parser(req, res, next);
    else handle(req, res);
  };
  next();
}

let server: Server;
let base: string;
beforeAll(async () => {
  server = createServer(parsedThenHandle);
  await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});
afterAll(() => new Promise((r) => server.close(r)));
beforeEach(() => {
  statusCalls.length = 0;
  claimCalls.length = 0;
});

const post = (path: string, body: string, contentType: string) =>
  fetch(`${base}${path}`, { method: "POST", headers: { "Content-Type": contentType }, body });

describe("handle() behind the runtime's body parsers", () => {
  it.each(["application/json", "text/plain", "application/octet-stream"])(
    "POST /status with a well-formed body (%s) reaches the handler, not 400",
    async (contentType) => {
      const res = await post("/status", JSON.stringify({ code: "abc" }), contentType);
      expect(res.status).toBe(200);
      expect(statusCalls).toEqual([["uid-1", "abc"]]);
    },
  );

  it("POST /activate with a well-formed body reaches the handler, not 400", async () => {
    const res = await post("/activate", JSON.stringify({ code: "abc" }), "application/json");
    expect(res.status).toBe(200);
    expect(claimCalls).toEqual([["uid-1", "abc"]]);
  });

  it("still 400s a body with no usable code", async () => {
    const res = await post("/status", JSON.stringify({}), "application/json");
    expect(res.status).toBe(400);
    expect(statusCalls).toEqual([]);
  });
});
