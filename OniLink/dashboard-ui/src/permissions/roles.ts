import type { GlobalRole, Role } from "../types/dashboard";

const rank: Record<GlobalRole, number> = { viewer: 0, operator: 1, admin: 2, owner: 3 };

export function isGlobalRole(role: Role): role is GlobalRole {
  return role !== "tenant";
}

export function hasRole(role: Role, required: GlobalRole): boolean {
  return isGlobalRole(role) && rank[role] >= rank[required];
}
