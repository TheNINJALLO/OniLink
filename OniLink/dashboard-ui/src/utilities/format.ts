export function duration(milliseconds: number): string {
  if (!Number.isFinite(milliseconds) || milliseconds < 0) return "—";
  const seconds = Math.floor(milliseconds / 1000);
  const days = Math.floor(seconds / 86_400);
  const hours = Math.floor((seconds % 86_400) / 3_600);
  const minutes = Math.floor((seconds % 3_600) / 60);
  if (days) return `${days}d ${hours}h`;
  if (hours) return `${hours}h ${minutes}m`;
  return `${minutes}m ${seconds % 60}s`;
}

export function bytes(value: number): string {
  if (!Number.isFinite(value) || value < 0) return "—";
  const units = ["B", "KiB", "MiB", "GiB"];
  let size = value;
  let unit = 0;
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024;
    unit += 1;
  }
  return `${size.toFixed(unit > 1 ? 1 : 0)} ${units[unit]}`;
}

export function timestamp(value?: string | number): string {
  if (value == null || value === "") return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString();
}

export function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : "The request could not be completed.";
}

export function endpoint(host: string, port: string | number): string {
  const cleanHost = host.trim();
  const cleanPort = String(port).trim();
  if (!cleanHost || !cleanPort) return "Not entered yet";
  const wrappedHost =
    cleanHost.includes(":") && !(cleanHost.startsWith("[") && cleanHost.endsWith("]"))
      ? `[${cleanHost}]`
      : cleanHost;
  return `${wrappedHost}:${cleanPort}`;
}
