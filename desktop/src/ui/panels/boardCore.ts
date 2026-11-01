import { BoardObj } from "../../core/models";

export interface Rect { left: number; top: number; right: number; bottom: number }
export interface Pt { x: number; y: number }
const W = (r: Rect) => r.right - r.left;
const H = (r: Rect) => r.bottom - r.top;
export { W as rectW, H as rectH };

let mctx: CanvasRenderingContext2D | null = null;
function measure(font: string, s: string): number {
  if (!mctx) mctx = document.createElement("canvas").getContext("2d")!;
  mctx.font = font;
  return mctx.measureText(s).width;
}
export const FONT = (size: number) => `${size}px "Segoe UI", Roboto, sans-serif`;

export const BoardGeo = {
  textLines: (o: BoardObj) => o.text.split("\n"),
  textSize: (o: BoardObj) => (o.size <= 0 ? 18 : o.size),
  measureText(o: BoardObj): [number, number] {
    const size = BoardGeo.textSize(o);
    let w = 0;
    const lines = BoardGeo.textLines(o);
    for (const l of lines) w = Math.max(w, measure(FONT(size), l));
    return [w + 4, lines.length * size * 1.35 + 4];
  },
  bbox(o: BoardObj): Rect {
    switch (o.type) {
      case "stroke": {
        const p = o.points;
        if (!p || p.length < 2) return { left: 0, top: 0, right: 0, bottom: 0 };
        let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
        for (let i = 0; i + 1 < p.length; i += 2) { x0 = Math.min(x0, p[i]); x1 = Math.max(x1, p[i]); y0 = Math.min(y0, p[i + 1]); y1 = Math.max(y1, p[i + 1]); }
        const pad = o.width / 2;
        return { left: x0 - pad, top: y0 - pad, right: x1 + pad, bottom: y1 + pad };
      }
      case "line": case "arrow": {
        const pad = o.width / 2;
        return { left: Math.min(o.x1, o.x2) - pad, top: Math.min(o.y1, o.y2) - pad, right: Math.max(o.x1, o.x2) + pad, bottom: Math.max(o.y1, o.y2) + pad };
      }
      case "text": { const [w, h] = BoardGeo.measureText(o); return { left: o.x, top: o.y, right: o.x + w, bottom: o.y + h }; }
      default: return { left: Math.min(o.x, o.x + o.w), top: Math.min(o.y, o.y + o.h), right: Math.max(o.x, o.x + o.w), bottom: Math.max(o.y, o.y + o.h) };
    }
  },
  center(o: BoardObj): Pt { const b = BoardGeo.bbox(o); return { x: (b.left + b.right) / 2, y: (b.top + b.bottom) / 2 }; },
  rot(px: number, py: number, cx: number, cy: number, a: number): Pt {
    const c = Math.cos(a), s = Math.sin(a), dx = px - cx, dy = py - cy;
    return { x: cx + dx * c - dy * s, y: cy + dx * s + dy * c };
  },
  toLocal(o: BoardObj, wx: number, wy: number): Pt {
    if (o.rot === 0) return { x: wx, y: wy };
    const c = BoardGeo.center(o);
    return BoardGeo.rot(wx, wy, c.x, c.y, -o.rot);
  },
  corners(o: BoardObj): Pt[] {
    const b = BoardGeo.bbox(o), c = BoardGeo.center(o);
    const pts = [{ x: b.left, y: b.top }, { x: b.right, y: b.top }, { x: b.right, y: b.bottom }, { x: b.left, y: b.bottom }];
    return o.rot === 0 ? pts : pts.map((p) => BoardGeo.rot(p.x, p.y, c.x, c.y, o.rot));
  },
  rotatedBounds(o: BoardObj): Rect {
    const cs = BoardGeo.corners(o);
    const xs = cs.map((p) => p.x), ys = cs.map((p) => p.y);
    return { left: Math.min(...xs), top: Math.min(...ys), right: Math.max(...xs), bottom: Math.max(...ys) };
  },
  union(list: Iterable<BoardObj>): Rect | null {
    let r: Rect | null = null;
    for (const o of list) {
      const b = BoardGeo.rotatedBounds(o);
      r = r ? { left: Math.min(r.left, b.left), top: Math.min(r.top, b.top), right: Math.max(r.right, b.right), bottom: Math.max(r.bottom, b.bottom) } : b;
    }
    return r;
  },
  intersects(a: Rect, b: Rect) { return a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom; },
  segDist(px: number, py: number, x1: number, y1: number, x2: number, y2: number): number {
    const dx = x2 - x1, dy = y2 - y1, l2 = dx * dx + dy * dy;
    let t = l2 === 0 ? 0 : ((px - x1) * dx + (py - y1) * dy) / l2;
    t = Math.max(0, Math.min(1, t));
    return Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy));
  },
  hit(o: BoardObj, wx: number, wy: number, tol: number): boolean {
    const l = BoardGeo.toLocal(o, wx, wy), x = l.x, y = l.y;
    if (o.type === "stroke") {
      const p = o.points; if (!p) return false;
      const t = Math.max(tol, o.width / 2 + tol * 0.5);
      if (p.length === 2) return Math.hypot(x - p[0], y - p[1]) <= t;
      for (let i = 0; i + 3 < p.length; i += 2) if (BoardGeo.segDist(x, y, p[i], p[i + 1], p[i + 2], p[i + 3]) <= t) return true;
      return false;
    }
    if (o.type === "line" || o.type === "arrow") return BoardGeo.segDist(x, y, o.x1, o.y1, o.x2, o.y2) <= Math.max(tol, o.width / 2 + tol * 0.5);
    const b = BoardGeo.bbox(o);
    return x >= b.left - tol * 0.4 && x <= b.right + tol * 0.4 && y >= b.top - tol * 0.4 && y <= b.bottom + tol * 0.4;
  },
  translate(o: BoardObj, dx: number, dy: number) {
    if (o.type === "stroke") { if (!o.points) return; o.points = o.points.map((v, i) => v + (i % 2 === 0 ? dx : dy)); }
    else if (o.type === "line" || o.type === "arrow") { o.x1 += dx; o.y1 += dy; o.x2 += dx; o.y2 += dy; }
    else { o.x += dx; o.y += dy; }
  },
};

/** Decoded images, kept in a small LRU. */
export class BoardImages {
  private map = new Map<string, ImageBitmap | HTMLImageElement>();
  private pending = new Set<string>();
  constructor(private onLoaded: () => void) {}
  get(o: BoardObj): CanvasImageSource | null {
    const hit = this.map.get(o.id);
    if (hit) { this.map.delete(o.id); this.map.set(o.id, hit); return hit; }
    const src = o.src;
    if (!src || !this.pending.add(o.id)) return null;
    const id = o.id;
    const img = new Image();
    img.onload = () => {
      this.map.set(id, img);
      while (this.map.size > 12) this.map.delete(this.map.keys().next().value as string);
      this.pending.delete(id);
      this.onLoaded();
    };
    img.onerror = () => this.pending.delete(id);
    img.src = "data:image/jpeg;base64," + src;
    return null;
  }
  forget(ids: Iterable<string>) { for (const i of ids) this.map.delete(i); }
}

const css = (c: number) => "#" + (c & 0xffffff).toString(16).padStart(6, "0");

function strokePath(ctx: CanvasRenderingContext2D, p: number[]) {
  const n = p.length / 2;
  ctx.beginPath();
  if (n < 2) return;
  ctx.moveTo(p[0], p[1]);
  for (let i = 1; i < n - 1; i++) ctx.quadraticCurveTo(p[i * 2], p[i * 2 + 1], (p[i * 2] + p[i * 2 + 2]) / 2, (p[i * 2 + 1] + p[i * 2 + 3]) / 2);
  ctx.lineTo(p[(n - 1) * 2], p[(n - 1) * 2 + 1]);
}

export class BoardRenderer {
  constructor(private images: BoardImages) {}

  private style(ctx: CanvasRenderingContext2D, o: BoardObj) {
    ctx.strokeStyle = css(o.color);
    ctx.globalAlpha = Math.max(0, Math.min(1, o.opacity));
    ctx.lineWidth = Math.max(0.5, o.width);
    ctx.lineCap = "round"; ctx.lineJoin = "round";
    if (o.dash) { const d = Math.max(4, o.width * 2.5); ctx.setLineDash([d, d]); } else ctx.setLineDash([]);
  }

  drawObj(ctx: CanvasRenderingContext2D, o: BoardObj) {
    ctx.save();
    if (o.rot !== 0) { const c = BoardGeo.center(o); ctx.translate(c.x, c.y); ctx.rotate(o.rot); ctx.translate(-c.x, -c.y); }
    switch (o.type) {
      case "stroke": {
        const p = o.points;
        if (p && p.length >= 2) {
          if (p.length < 4) {
            ctx.globalAlpha = Math.max(0, Math.min(1, o.opacity)); ctx.fillStyle = css(o.color);
            ctx.beginPath(); ctx.arc(p[0], p[1], Math.max(1, o.width / 2), 0, Math.PI * 2); ctx.fill();
          } else { this.style(ctx, o); strokePath(ctx, p); ctx.stroke(); }
        }
        break;
      }
      case "line": case "arrow": {
        this.style(ctx, o);
        ctx.beginPath(); ctx.moveTo(o.x1, o.y1); ctx.lineTo(o.x2, o.y2); ctx.stroke();
        if (o.type === "arrow") {
          const ang = Math.atan2(o.y2 - o.y1, o.x2 - o.x1), hl = Math.max(10, o.width * 3.2);
          ctx.setLineDash([]);
          ctx.beginPath();
          ctx.moveTo(o.x2 - hl * Math.cos(ang - 0.44), o.y2 - hl * Math.sin(ang - 0.44)); ctx.lineTo(o.x2, o.y2);
          ctx.lineTo(o.x2 - hl * Math.cos(ang + 0.44), o.y2 - hl * Math.sin(ang + 0.44));
          ctx.stroke();
        }
        break;
      }
      case "rect": { this.style(ctx, o); const b = BoardGeo.bbox(o), h = o.width / 2; ctx.strokeRect(b.left + h, b.top + h, W(b) - o.width, H(b) - o.width); break; }
      case "ellipse": {
        this.style(ctx, o);
        const b = BoardGeo.bbox(o), h = o.width / 2;
        ctx.beginPath(); ctx.ellipse((b.left + b.right) / 2, (b.top + b.bottom) / 2, Math.max(0, W(b) / 2 - h), Math.max(0, H(b) / 2 - h), 0, 0, Math.PI * 2); ctx.stroke();
        break;
      }
      case "text": {
        const size = BoardGeo.textSize(o);
        ctx.globalAlpha = Math.max(0, Math.min(1, o.opacity)); ctx.fillStyle = css(o.color);
        ctx.font = FONT(size); ctx.textBaseline = "alphabetic";
        BoardGeo.textLines(o).forEach((l, i) => ctx.fillText(l, o.x + 2, o.y + 2 + i * size * 1.35 + size * 0.95));
        break;
      }
      case "image": {
        const b = BoardGeo.bbox(o), img = this.images.get(o);
        if (img) { ctx.globalAlpha = Math.max(0, Math.min(1, o.opacity)); ctx.drawImage(img, b.left, b.top, W(b), H(b)); }
        else { ctx.strokeStyle = "#37373f"; ctx.lineWidth = 1; ctx.setLineDash([4, 4]); ctx.strokeRect(b.left, b.top, W(b), H(b)); }
        break;
      }
    }
    ctx.restore();
  }

  drawAll(ctx: CanvasRenderingContext2D, objs: BoardObj[], skip?: Set<string>) {
    for (const o of objs) if (!skip || !skip.has(o.id)) this.drawObj(ctx, o);
  }
}

/* ---------------- export ---------------- */

const EXPORT_BG = "#0d0d0f";
const f1 = (v: number) => v.toFixed(1);
const esc = (s: string) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

export const BoardExport = {
  region(objs: Iterable<BoardObj>, pad = 40): Rect | null {
    const u = BoardGeo.union(objs);
    return u ? { left: u.left - pad, top: u.top - pad, right: u.right + pad, bottom: u.bottom + pad } : null;
  },
  canvas(objs: BoardObj[], region: Rect, images: BoardImages, maxScale: number, maxSide: number): HTMLCanvasElement {
    const scale = Math.min(maxScale, maxSide / Math.max(W(region), H(region)));
    const cv = document.createElement("canvas");
    cv.width = Math.max(1, Math.floor(W(region) * scale)); cv.height = Math.max(1, Math.floor(H(region) * scale));
    const ctx = cv.getContext("2d")!;
    ctx.fillStyle = EXPORT_BG; ctx.fillRect(0, 0, cv.width, cv.height);
    ctx.scale(scale, scale); ctx.translate(-region.left, -region.top);
    new BoardRenderer(images).drawAll(ctx, objs);
    return cv;
  },
  async raster(objs: BoardObj[], region: Rect, images: BoardImages, jpeg: boolean): Promise<Uint8Array> {
    const cv = BoardExport.canvas(objs, region, images, 2, 4096);
    const blob: Blob | null = await new Promise((r) => cv.toBlob(r, jpeg ? "image/jpeg" : "image/png", 0.92));
    return new Uint8Array(await blob!.arrayBuffer());
  },
  /** A single-page PDF with the board as one JPEG, no libraries needed. */
  async pdf(objs: BoardObj[], region: Rect, images: BoardImages): Promise<Uint8Array> {
    const cv = BoardExport.canvas(objs, region, images, 1, 2000);
    const blob: Blob | null = await new Promise((r) => cv.toBlob(r, "image/jpeg", 0.92));
    const jpg = new Uint8Array(await blob!.arrayBuffer());
    const w = cv.width, h = cv.height, enc = new TextEncoder(), parts: Uint8Array[] = [], offs: number[] = [];
    let len = 0;
    const push = (b: Uint8Array | string) => { const u = typeof b === "string" ? enc.encode(b) : b; parts.push(u); len += u.length; };
    const obj = (n: number, body: string) => { offs[n] = len; push(`${n} 0 obj\n${body}\nendobj\n`); };
    push("%PDF-1.4\n");
    obj(1, "<< /Type /Catalog /Pages 2 0 R >>");
    obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
    obj(3, `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${w} ${h}] /Resources << /XObject << /Im0 4 0 R >> >> /Contents 5 0 R >>`);
    offs[4] = len; push(`4 0 obj\n<< /Type /XObject /Subtype /Image /Width ${w} /Height ${h} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpg.length} >>\nstream\n`); push(jpg); push("\nendstream\nendobj\n");
    const content = `q ${w} 0 0 ${h} 0 0 cm /Im0 Do Q`;
    obj(5, `<< /Length ${content.length} >>\nstream\n${content}\nendstream`);
    const xref = len;
    let x = "xref\n0 6\n0000000000 65535 f \n";
    for (let i = 1; i <= 5; i++) x += String(offs[i]).padStart(10, "0") + " 00000 n \n";
    push(x + `trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF`);
    const out = new Uint8Array(len); let p = 0;
    for (const b of parts) { out.set(b, p); p += b.length; }
    return out;
  },
  svg(objs: BoardObj[], region: Rect): Uint8Array {
    const w = W(region), h = H(region);
    let s = `<svg xmlns="http://www.w3.org/2000/svg" width="${f1(w)}" height="${f1(h)}" viewBox="${f1(region.left)} ${f1(region.top)} ${f1(w)} ${f1(h)}">`;
    s += `<rect x="${f1(region.left)}" y="${f1(region.top)}" width="${f1(w)}" height="${f1(h)}" fill="#0d0d0f"/>`;
    for (const o of objs) {
      const c = BoardGeo.center(o);
      const tr = o.rot !== 0 ? ` transform="rotate(${f1((o.rot * 180) / Math.PI)} ${f1(c.x)} ${f1(c.y)})"` : "";
      const hex = css(o.color);
      const st = `stroke="${hex}" stroke-width="${f1(o.width)}" stroke-opacity="${o.opacity}" stroke-linecap="round" stroke-linejoin="round" fill="none"` + (o.dash ? ` stroke-dasharray="${f1(Math.max(4, o.width * 2.5))}"` : "");
      const b = BoardGeo.bbox(o);
      switch (o.type) {
        case "stroke": {
          const p = o.points; if (!p) break;
          if (p.length < 4) s += `<circle cx="${f1(p[0])}" cy="${f1(p[1])}" r="${f1(o.width / 2)}" fill="${hex}" fill-opacity="${o.opacity}"${tr}/>`;
          else {
            let d = `M${f1(p[0])} ${f1(p[1])}`; const n = p.length / 2;
            for (let i = 1; i < n - 1; i++) d += ` Q${f1(p[i * 2])} ${f1(p[i * 2 + 1])} ${f1((p[i * 2] + p[i * 2 + 2]) / 2)} ${f1((p[i * 2 + 1] + p[i * 2 + 3]) / 2)}`;
            d += ` L${f1(p[(n - 1) * 2])} ${f1(p[(n - 1) * 2 + 1])}`;
            s += `<path d="${d}" ${st}${tr}/>`;
          }
          break;
        }
        case "line": case "arrow": {
          s += `<g${tr}><line x1="${f1(o.x1)}" y1="${f1(o.y1)}" x2="${f1(o.x2)}" y2="${f1(o.y2)}" ${st}/>`;
          if (o.type === "arrow") {
            const ang = Math.atan2(o.y2 - o.y1, o.x2 - o.x1), hl = Math.max(10, o.width * 3.2);
            s += `<path d="M${f1(o.x2 - hl * Math.cos(ang - 0.44))} ${f1(o.y2 - hl * Math.sin(ang - 0.44))} L${f1(o.x2)} ${f1(o.y2)} L${f1(o.x2 - hl * Math.cos(ang + 0.44))} ${f1(o.y2 - hl * Math.sin(ang + 0.44))}" ${st}/>`;
          }
          s += "</g>"; break;
        }
        case "rect": s += `<rect x="${f1(b.left)}" y="${f1(b.top)}" width="${f1(W(b))}" height="${f1(H(b))}" ${st}${tr}/>`; break;
        case "ellipse": s += `<ellipse cx="${f1((b.left + b.right) / 2)}" cy="${f1((b.top + b.bottom) / 2)}" rx="${f1(W(b) / 2)}" ry="${f1(H(b) / 2)}" ${st}${tr}/>`; break;
        case "text": {
          const size = BoardGeo.textSize(o);
          s += `<text font-family="sans-serif" font-size="${f1(size)}" fill="${hex}" fill-opacity="${o.opacity}"${tr}>`;
          BoardGeo.textLines(o).forEach((l, i) => { s += `<tspan x="${f1(o.x + 2)}" y="${f1(o.y + 2 + size + i * size * 1.35 - size * 0.2)}">${esc(l)}</tspan>`; });
          s += "</text>"; break;
        }
        case "image": s += `<image x="${f1(b.left)}" y="${f1(b.top)}" width="${f1(W(b))}" height="${f1(H(b))}" opacity="${o.opacity}" href="data:image/jpeg;base64,${o.src ?? ""}"${tr}/>`; break;
      }
    }
    return new TextEncoder().encode(s + "</svg>");
  },
};
