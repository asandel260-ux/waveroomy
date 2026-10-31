import { J, b64decode, b64url, obj, str } from "../core/util";

/* ===========================================================================
 * SDP helpers. Invite/answer codes carry a whole SDP, so it is shrunk: only Opus
 * for audio and VP8 (+RTX) for video, no link-local / TCP candidates, then
 * raw-deflate + base64url. Byte-compatible with net/Sdp.kt.
 * ========================================================================= */

const rtpmapRe = /^a=rtpmap:(\d+) ([^/\s]+)/;
const fmtpAptRe = /^a=fmtp:(\d+) .*apt=(\d+)/;
const ptLineRe = /^a=(rtpmap|fmtp|rtcp-fb):(\d+)/;

const splitLines = (sdp: string) => sdp.split(/\r?\n/).filter((l) => l.length > 0);

function pruneSection(section: string[], keep: Set<string>): string[] {
  const keptPts = new Set<string>();
  for (const l of section) {
    const m = rtpmapRe.exec(l);
    if (m && keep.has(m[2].toLowerCase())) keptPts.add(m[1]);
  }
  if (keptPts.size === 0) return section;
  const rtxPts = new Set<string>();
  for (const l of section) {
    const m = rtpmapRe.exec(l);
    if (m && m[2].toLowerCase() === "rtx") {
      const pt = m[1];
      for (const f of section) {
        const fm = fmtpAptRe.exec(f);
        if (fm && fm[1] === pt && keptPts.has(fm[2])) rtxPts.add(pt);
      }
    }
  }
  const allowed = new Set([...keptPts, ...rtxPts]);
  const out: string[] = [];
  for (const l of section) {
    if (l.startsWith("m=")) {
      const parts = l.split(" ");
      out.push([...parts.slice(0, 3), ...parts.slice(3).filter((p) => allowed.has(p))].join(" "));
      continue;
    }
    const pm = ptLineRe.exec(l);
    if (pm && !allowed.has(pm[2])) continue;
    out.push(l);
  }
  return out;
}

/** Keep only [keep] codec names (case-insensitive) in the m-line of [kind], plus matching RTX. */
export function pruneCodecs(sdp: string, kind: string, keep: string[]): string {
  const lines = splitLines(sdp);
  const keepNames = new Set(keep.map((k) => k.toLowerCase()));
  const out: string[] = [];
  let i = 0;
  while (i < lines.length) {
    if (!lines[i].startsWith(`m=${kind} `)) { out.push(lines[i]); i++; continue; }
    let j = i + 1;
    while (j < lines.length && !lines[j].startsWith("m=")) j++;
    out.push(...pruneSection(lines.slice(i, j), keepNames));
    i = j;
  }
  return out.join("\r\n") + "\r\n";
}

/** Drop IPv6 link-local and TCP candidates; they bloat the code and rarely help. */
export function filterCandidates(sdp: string): string {
  const out = splitLines(sdp).filter((l) => !(l.startsWith("a=candidate:") && (l.includes(" fe80:") || /\stcp\s/i.test(l) || l.includes("tcptype"))));
  return out.join("\r\n") + "\r\n";
}

export const forOffer = (sdp: string): string => pruneCodecs(pruneCodecs(sdp, "audio", ["opus"]), "video", ["VP8"]);

async function pipe(data: Uint8Array, stream: CompressionStream | DecompressionStream): Promise<Uint8Array> {
  const w = stream.writable.getWriter();
  void w.write(data as BufferSource);
  void w.close();
  const chunks: Uint8Array[] = [];
  const r = stream.readable.getReader();
  let total = 0;
  for (;;) {
    const { done, value } = await r.read();
    if (done) break;
    chunks.push(value);
    total += value.length;
    if (total > 200_000) break;
  }
  const out = new Uint8Array(total);
  let p = 0;
  for (const c of chunks) { out.set(c, p); p += c.length; }
  return out;
}

export const PREFIX = "WR1.";

export async function pack(json: J): Promise<string> {
  const raw = new TextEncoder().encode(JSON.stringify(json));
  return PREFIX + b64url(await pipe(raw, new CompressionStream("deflate-raw")));
}

/** Accepts a bare code or any text containing one (e.g. a pasted message or homit:// link). */
export async function unpack(text: string): Promise<J | null> {
  const t = text.trim();
  const at = t.indexOf(PREFIX);
  if (at < 0) return null;
  let end = at + PREFIX.length;
  while (end < t.length && /[A-Za-z0-9\-_]/.test(t[end])) end++;
  const body = t.substring(at + PREFIX.length, end);
  try {
    const bytes = await pipe(b64decode(body), new DecompressionStream("deflate-raw"));
    const j = JSON.parse(new TextDecoder().decode(bytes));
    return j && typeof j === "object" && str(j, "k") ? j : null;
  } catch {
    return null;
  }
}

export const previewOf = (code: J): J | null => obj(code, "v");
