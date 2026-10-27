import { deliver, sendPush, type Push } from "./control";

// Tells every install that Remote Config was published, so a kill switch or a version floor
// reaches phones in minutes instead of at their next six-hourly fetch. The phone answers by
// fetching past its interval, spread over a quarter of an hour; see ConfigRefreshWorker in the app.
//
// Sent on every publish, whatever changed. Deciding which keys matter would put a second copy of
// the app's Remote Config knowledge here, and a needless fetch costs a phone one request.

// Every install holds this topic, claimed or not, and keeps it while its gate is closed: it is how
// a closed gate is lifted too. Must match ControlTopics.CONFIG in the app.
export const CONFIG_TOPIC = "control-v2";

export function configStalePush(at: number): Push {
  return { topic: CONFIG_TOPIC, data: { type: "configStale", at: String(at) } };
}

export interface ConfigEvent {
  at: number;
  eventId: string;
}

export async function handleConfigUpdate(
  e: ConfigEvent,
  deps: { send?: (p: Push) => Promise<void> } = {},
): Promise<void> {
  await deliver(configStalePush(e.at), deps.send ?? sendPush, "configStale", e.eventId);
}
