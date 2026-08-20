import type { Backend, Player, RuntimeState } from "../types/dashboard";

export const state: RuntimeState = {
  name: "OniLink",
  version: "0.2.0-beta.2",
  timestamp: "2026-08-19T20:00:00Z",
  startedAt: "2026-08-19T19:00:00Z",
  uptimeMillis: 3_600_000,
  players: 1,
  maxPlayers: 100,
  backends: 2,
  allowlistEnabled: true,
  allowlistEntries: 1,
  listener: { host: "0.0.0.0", port: 19132 },
  memoryUsedBytes: 1048576,
  memoryCommittedBytes: 2097152,
  memoryMaxBytes: 4194304,
  processors: 4,
  threads: 12,
  systemLoadAverage: 0.5,
  principal: { username: "owner", role: "owner", tenantId: "" },
};

export const player: Player = {
  name: "TheN1NJ4LL0",
  xuid: "2535438695543476",
  identity: "bedrock:2535438695543476",
  backend: "survival",
  switching: false,
  switchTarget: "",
  connectedMillis: 60_000,
  joinedWorld: true,
  protocol: "827",
  address: "174.84.137.109:51120",
  packetTraceActive: false,
};

export const backends: Backend[] = [
  {
    name: "survival",
    host: "10.0.0.2",
    port: 19132,
    protocol: "bedrock",
    players: 1,
    default: true,
    hub: false,
    forwarding: true,
    dropSubChunkRequests: false,
    health: { status: "online", latencyMillis: 12 },
  },
  {
    name: "creative",
    host: "10.0.0.3",
    port: 19132,
    protocol: "bedrock",
    players: 0,
    default: false,
    hub: false,
    forwarding: true,
    dropSubChunkRequests: true,
    health: { status: "degraded", latencyMillis: 150, message: "Slow response" },
  },
];
