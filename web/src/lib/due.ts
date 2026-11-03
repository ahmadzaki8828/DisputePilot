export type DueTone = "urgent" | "soon" | "ok" | "none";

const H = 3600_000;

export function dueInfo(dueAt: string | null | undefined, now: Date = new Date()): { label: string; tone: DueTone } {
  if (!dueAt) return { label: "no deadline", tone: "none" };
  const ms = new Date(dueAt).getTime() - now.getTime();
  if (Number.isNaN(ms)) return { label: "no deadline", tone: "none" };
  if (ms <= 0) return { label: "overdue", tone: "urgent" };
  const hours = Math.floor(ms / H);
  const tone: DueTone = ms < 48 * H ? "urgent" : ms < 96 * H ? "soon" : "ok";
  const days = Math.floor(hours / 24);
  if (days >= 2 || (days >= 1 && tone === "ok")) return { label: `due in ${days}d`, tone };
  if (days >= 1) return { label: `due in ${days}d ${hours % 24}h`, tone };
  const minutes = Math.floor((ms % H) / 60_000);
  return { label: `due in ${hours}h ${minutes}m`, tone };
}
