/* ---------------------------------------------------------------------------
 * Utilities shared by everything: timers, ids, JSON helpers, rate limiter,
 * formatting. Mirrors core/Util.kt so both apps behave the same.
 * ------------------------------------------------------------------------- */

export type J = any;
export type Timer = ReturnType<typeof setTimeout> | null;

export const nowMs = (): number => Date.now();

export function later(ms: number, fn: () => void): Timer {
  return setTimeout(fn, ms);
}

export function cancelLater(t: Timer | undefined): void {
  if (t != null) clearTimeout(t);
}

const ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

export const Ids = {
  rand(n: number): string {
    const buf = new Uint32Array(n);
    crypto.getRandomValues(buf);
    let s = "";
    for (let i = 0; i < n; i++) s += ALPHABET[buf[i] % ALPHABET.length];
    return s;
  },
  sid: () => Ids.rand(10),
  id: (prefix: string) => prefix + Ids.rand(10),
  nonce: () => Ids.rand(24)
};

/* ---- JSON accessors (same defaults as the Android helpers) ---- */

const has = (j: J, k: string) => j != null && typeof j === "object" && k in j && j[k] !== null && j[k] !== undefined;

export const str = (j: J, k: string, d = ""): string => {
  if (!has(j, k)) return d;
  const v = j[k];
  return typeof v === "string" ? v : String(v);
};
export const int = (j: J, k: string, d = 0): number => {
  if (!has(j, k)) return d;
  const v = Number(j[k]);
  return Number.isFinite(v) ? Math.trunc(v) : d;
};
export const lng = int;
export const dbl = (j: J, k: string, d = 0): number => {
  if (!has(j, k)) return d;
  const v = Number(j[k]);
  return Number.isFinite(v) ? v : d;
};
export const flt = dbl;
export const bool = (j: J, k: string, d = false): boolean => {
  if (!has(j, k)) return d;
  const v = j[k];
  if (typeof v === "boolean") return v;
  if (typeof v === "string") return v === "true";
  if (typeof v === "number") return v !== 0;
  return d;
};
export const obj = (j: J, k: string): J | null => (has(j, k) && typeof j[k] === "object" && !Array.isArray(j[k]) ? j[k] : null);
export const arr = (j: J, k: string): any[] | null => (has(j, k) && Array.isArray(j[k]) ? j[k] : null);
export const strings = (a: any[] | null | undefined): string[] => (a ?? []).map((x) => (typeof x === "string" ? x : String(x)));
export const objects = (a: any[] | null | undefined): J[] => (a ?? []).filter((x) => x && typeof x === "object" && !Array.isArray(x));

export function parseJson(s: string): J | null {
  try {
    const v = JSON.parse(s);
    return v && typeof v === "object" && !Array.isArray(v) ? v : null;
  } catch {
    return null;
  }
}

export const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));

/* ---- rate limiter (fixed window per key) ---- */

export class RateLimiter {
  private buckets = new Map<string, { start: number; count: number }>();
  allow(key: string, max: number, windowMs: number): boolean {
    const t = nowMs();
    if (this.buckets.size > 400) for (const [k, b] of this.buckets) if (t - b.start > 120_000) this.buckets.delete(k);
    const b = this.buckets.get(key);
    if (!b || t - b.start > windowMs) {
      this.buckets.set(key, { start: t, count: 1 });
      return true;
    }
    b.count++;
    return b.count <= max;
  }
}

/* ---- formatting ---- */

export function timeAgo(ts: number): string {
  const s = Math.max(1, Math.floor((nowMs() - ts) / 1000));
  if (s < 60) return "just now";
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  const d = Math.floor(h / 24);
  if (d < 30) return `${d}d ago`;
  return new Date(ts).toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" });
}

export function fmtClock(ts: number): string {
  const d = new Date(ts);
  return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}

export function fmtDuration(totalSeconds: number): string {
  const t = Math.max(0, Math.floor(totalSeconds));
  const h = Math.floor(t / 3600);
  const m = Math.floor((t % 3600) / 60);
  const s = t % 60;
  const p = (n: number) => String(n).padStart(2, "0");
  return h > 0 ? `${h}:${p(m)}:${p(s)}` : `${m}:${p(s)}`;
}

/* ---- tiny observable store (replaces Kotlin StateFlow) ---- */

export class Flow<T> {
  private subs = new Set<() => void>();
  constructor(private v: T) {}
  get value(): T { return this.v; }
  set value(n: T) {
    this.v = n;
    for (const s of [...this.subs]) s();
  }
  update(f: (cur: T) => T) { this.value = f(this.v); }
  subscribe = (cb: () => void): (() => void) => {
    this.subs.add(cb);
    return () => { this.subs.delete(cb); };
  };
  /** React useSyncExternalStore snapshot getter */
  snap = (): T => this.v;
}

export class Emitter<T> {
  private subs = new Set<(v: T) => void>();
  emit(v: T) { for (const s of [...this.subs]) s(v); }
  on(cb: (v: T) => void): () => void { this.subs.add(cb); return () => { this.subs.delete(cb); }; }
}

/* ---- base64 helpers ---- */

export function b64encode(bytes: Uint8Array): string {
  let s = "";
  const CH = 0x8000;
  for (let i = 0; i < bytes.length; i += CH) s += String.fromCharCode(...bytes.subarray(i, i + CH));
  return btoa(s);
}
export function b64decode(s: string): Uint8Array {
  const bin = atob(s.replace(/-/g, "+").replace(/_/g, "/"));
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}
export function b64url(bytes: Uint8Array): string {
  return b64encode(bytes).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
export function hex(bytes: Uint8Array): string {
  return [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
}
export const utf8 = (s: string): Uint8Array => new TextEncoder().encode(s);
export const unutf8 = (b: Uint8Array): string => new TextDecoder().decode(b);
export async function sha256(data: Uint8Array | string): Promise<Uint8Array> {
  const d = typeof data === "string" ? utf8(data) : data;
  return new Uint8Array(await crypto.subtle.digest("SHA-256", d as BufferSource));
}
