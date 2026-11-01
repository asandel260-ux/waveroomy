import { BoardObj, BoardState } from "../../core/models";
import { Ids, cancelLater, later, nowMs, type J, type Timer } from "../../core/util";
import type { RoomSession } from "../../room/session";
import { BoardGeo, BoardImages, type Pt, type Rect } from "./boardCore";

export const BOARD_COLORS = [0xededef, 0x9a9aa3, 0x6e56cf, 0x3dd68c, 0xe5484d, 0xe2b93b, 0x4da3ff, 0xff8a3d];
export const DRAW_TOOLS = new Set(["pencil", "pen", "highlight", "line", "arrow", "rect", "ellipse"]);

type BOp = { k: "add"; objs: BoardObj[] } | { k: "del"; ids: string[] } | { k: "patch"; patches: Map<string, J> };
interface BEntry { undo: BOp[]; redo: BOp[] }
export interface LaserPoint { x: number; y: number; t: number }
export interface TextEdit { existing: BoardObj | null; wx: number; wy: number }

const clamp = (v: number, a: number, b: number) => Math.max(a, Math.min(b, v));

/** All interaction logic of the shared blackboard (a line-for-line port of the phone's BoardController). */
export class BoardController {
  camX = 0; camY = 0; zoom = 1; vw = 1; vh = 1;
  density = 1;
  tool = "select"; color = 0xededef; width = 3; opacity = 1; dash = false; textSize = 24;
  selection = new Set<string>();
  draft: BoardObj | null = null;
  marquee: Rect | null = null;
  canUndo = false; canRedo = false;
  erasing = new Set<string>();
  textEdit: TextEdit | null = null;
  readonly images: BoardImages;
  readonly lasers = new Map<string, LaserPoint[]>();

  private history: BEntry[] = [];
  private histIdx = -1;
  private mode = "";
  private downS: Pt = { x: 0, y: 0 };
  private downW: Pt = { x: 0, y: 0 };
  private moved = false;
  private snap = new Map<string, BoardObj>();
  private handle = -1;
  private stroke: number[] = [];
  private panCamX = 0; private panCamY = 0;
  private lastTapAt = 0; private lastTapId: string | null = null;
  private pending = new Map<string, J>();
  private flushTimer: Timer | undefined;

  constructor(private session: RoomSession, readonly board: BoardState, private bump: () => void) {
    this.images = new BoardImages(bump);
  }

  private redraw() { this.bump(); }
  toWorldX = (sx: number) => sx / this.zoom + this.camX;
  toWorldY = (sy: number) => sy / this.zoom + this.camY;
  toScreenX = (wx: number) => (wx - this.camX) * this.zoom;
  toScreenY = (wy: number) => (wy - this.camY) * this.zoom;
  sorted() { return this.board.sortedByZ(); }
  selected(): BoardObj[] { const out: BoardObj[] = []; for (const id of this.selection) { const o = this.board.objects.get(id); if (o) out.push(o); } return out; }
  setSelection(s: Iterable<string>) { this.selection = new Set(s); this.redraw(); }

  /* ---- camera ---- */
  pinch(cx: number, cy: number, factor: number, dx: number, dy: number) {
    const wx = this.toWorldX(cx), wy = this.toWorldY(cy);
    this.zoom = clamp(this.zoom * factor, 0.08, 8);
    this.camX = wx - cx / this.zoom - dx / this.zoom;
    this.camY = wy - cy / this.zoom - dy / this.zoom;
    this.redraw();
  }
  fitAll() {
    const u = BoardGeo.union(this.board.objects.values());
    if (!u) { this.zoom = 1; this.camX = -this.vw / 2; this.camY = -this.vh / 2; }
    else {
      const pad = 60 * this.density;
      const z = clamp(Math.min((this.vw - pad * 2) / Math.max(1, u.right - u.left), (this.vh - pad * 2) / Math.max(1, u.bottom - u.top)), 0.08, 2);
      this.zoom = z; this.camX = (u.left + u.right) / 2 - this.vw / z / 2; this.camY = (u.top + u.bottom) / 2 - this.vh / z / 2;
    }
    this.redraw();
  }
  resetZoom() {
    const wx = this.toWorldX(this.vw / 2), wy = this.toWorldY(this.vh / 2);
    this.zoom = 1; this.camX = wx - this.vw / 2; this.camY = wy - this.vh / 2;
    this.redraw();
  }
  zoomAt(cx: number, cy: number, factor: number) { this.pinch(cx, cy, factor, 0, 0); }
  panBy(dx: number, dy: number) { this.camX -= dx / this.zoom; this.camY -= dy / this.zoom; this.redraw(); }

  /* ---- network ---- */
  private patchOf(o: BoardObj): J { const j = o.toJson(false); delete j.id; delete j.type; delete j.by; return j; }
  private queuePatch(o: BoardObj) {
    this.pending.set(o.id, this.patchOf(o));
    if (this.flushTimer === undefined) this.flushTimer = later(70, () => this.flushPending());
  }
  private flushPending() {
    this.flushTimer = undefined;
    const items = [...this.pending]; this.pending.clear();
    for (const [id, p] of items) if (this.board.objects.has(id)) this.session.boardUpdate(id, p);
  }
  private applyOps(ops: BOp[]) {
    for (const op of ops) {
      if (op.k === "add") { for (const o of op.objs) if (!this.board.objects.has(o.id)) this.session.boardAdd(o.deepCopy()); }
      else if (op.k === "del") {
        const ids = op.ids.filter((i) => this.board.objects.has(i));
        if (ids.length) { for (const i of ids) this.pending.delete(i); this.session.boardDelete(ids); this.images.forget(ids); }
        for (const i of op.ids) this.selection.delete(i);
      } else {
        for (const [id, p] of op.patches) if (this.board.objects.has(id)) { this.pending.delete(id); this.board.patch(id, p); this.session.boardUpdate(id, p); }
      }
    }
    this.redraw();
  }

  /* ---- history ---- */
  private push(e: BEntry) {
    this.history.length = this.histIdx + 1;
    this.history.push(e);
    if (this.history.length > 100) this.history.shift();
    this.histIdx = this.history.length - 1;
    this.syncHist();
  }
  private syncHist() { this.canUndo = this.histIdx >= 0; this.canRedo = this.histIdx < this.history.length - 1; }
  undo() { if (this.histIdx < 0) return; this.applyOps(this.history[this.histIdx].undo); this.histIdx--; this.syncHist(); this.redraw(); }
  redo() { if (this.histIdx >= this.history.length - 1) return; this.histIdx++; this.applyOps(this.history[this.histIdx].redo); this.syncHist(); this.redraw(); }
  private patchEntry(before: Map<string, BoardObj>) {
    const b = new Map<string, J>(), a = new Map<string, J>();
    for (const [id, o] of before) { const cur = this.board.objects.get(id); if (!cur) continue; b.set(id, this.patchOf(o)); a.set(id, this.patchOf(cur)); }
    if (b.size) this.push({ undo: [{ k: "patch", patches: b }], redo: [{ k: "patch", patches: a }] });
  }

  /* ---- hit testing ---- */
  private expandGroup(ids: Set<string>): Set<string> {
    const groups = new Set<string>();
    for (const i of ids) { const g = this.board.objects.get(i)?.group; if (g) groups.add(g); }
    if (!groups.size) return ids;
    const out = new Set(ids);
    for (const o of this.board.objects.values()) if (o.group && groups.has(o.group)) out.add(o.id);
    return out;
  }
  private hitObject(wx: number, wy: number): BoardObj | null {
    const tol = (14 * this.density) / this.zoom, list = this.sorted();
    for (let i = list.length - 1; i >= 0; i--) if (BoardGeo.hit(list[i], wx, wy, tol)) return list[i];
    return null;
  }
  private rotPt(p: Pt, c: Pt, a: number): Pt { return a === 0 ? { ...p } : BoardGeo.rot(p.x, p.y, c.x, c.y, a); }
  private boxHandles(b: Rect): Pt[] {
    const cx = (b.left + b.right) / 2, cy = (b.top + b.bottom) / 2;
    return [{ x: b.left, y: b.top }, { x: cx, y: b.top }, { x: b.right, y: b.top }, { x: b.right, y: cy }, { x: b.right, y: b.bottom }, { x: cx, y: b.bottom }, { x: b.left, y: b.bottom }, { x: b.left, y: cy }];
  }
  handlePoints(): Pt[] {
    const sel = this.selected();
    if (!sel.length) return [];
    if (sel.length === 1 && (sel[0].type === "line" || sel[0].type === "arrow")) return [{ x: sel[0].x1, y: sel[0].y1 }, { x: sel[0].x2, y: sel[0].y2 }];
    if (sel.length === 1) { const o = sel[0], c = BoardGeo.center(o); return this.boxHandles(BoardGeo.bbox(o)).map((p) => this.rotPt(p, c, o.rot)); }
    const u = BoardGeo.union(sel);
    return u ? this.boxHandles(u) : [];
  }
  rotateHandle(): Pt | null {
    const sel = this.selected();
    if (sel.length !== 1) return null;
    const o = sel[0];
    if (o.type === "line" || o.type === "arrow" || o.locked) return null;
    const b = BoardGeo.bbox(o), c = BoardGeo.center(o);
    return this.rotPt({ x: (b.left + b.right) / 2, y: b.top - (30 * this.density) / this.zoom }, c, o.rot);
  }
  private hitHandle(wx: number, wy: number): number {
    const sel = this.selected();
    if (!sel.length || sel.every((o) => o.locked)) return -1;
    const r = (26 * this.density) / this.zoom;
    const rh = this.rotateHandle();
    if (rh && Math.hypot(wx - rh.x, wy - rh.y) < r) return 100;
    const hp = this.handlePoints();
    for (let i = 0; i < hp.length; i++) if (Math.hypot(wx - hp[i].x, wy - hp[i].y) < r) return i;
    return -1;
  }

  /* ---- gestures ---- */
  onDown(sx: number, sy: number) {
    this.downS = { x: sx, y: sy };
    this.downW = { x: this.toWorldX(sx), y: this.toWorldY(sy) };
    this.moved = false; this.handle = -1; this.snap.clear(); this.mode = "";
    const wx = this.downW.x, wy = this.downW.y;
    switch (this.tool) {
      case "hand": this.mode = "pan"; this.panCamX = this.camX; this.panCamY = this.camY; break;
      case "laser": this.mode = "laser"; this.laser(wx, wy); break;
      case "eraser": this.mode = "erase"; this.erasing = new Set(); this.eraseAt(wx, wy); break;
      case "text": this.mode = "text"; break;
      case "select": {
        const h = this.hitHandle(wx, wy);
        if (h >= 0) { this.mode = h === 100 ? "rotate" : "resize"; this.handle = h; this.snapshot(); }
        else {
          const o = this.hitObject(wx, wy);
          if (o) { if (!this.selection.has(o.id)) this.selection = this.expandGroup(new Set([o.id])); this.mode = "move"; this.snapshot(); }
          else { this.mode = "marquee"; this.marquee = { left: wx, top: wy, right: wx, bottom: wy }; }
        }
        break;
      }
      default: if (DRAW_TOOLS.has(this.tool)) { this.mode = "draw"; this.startDraft(wx, wy); }
    }
    this.redraw();
  }

  onMove(sx: number, sy: number) {
    if (Math.hypot(sx - this.downS.x, sy - this.downS.y) > 6 * this.density) this.moved = true;
    const wx = this.toWorldX(sx), wy = this.toWorldY(sy);
    switch (this.mode) {
      case "pan": this.camX = this.panCamX - (sx - this.downS.x) / this.zoom; this.camY = this.panCamY - (sy - this.downS.y) / this.zoom; break;
      case "laser": this.laser(wx, wy); break;
      case "erase": this.eraseAt(wx, wy); break;
      case "draw": this.updateDraft(wx, wy); break;
      case "marquee": this.marquee = { left: Math.min(this.downW.x, wx), top: Math.min(this.downW.y, wy), right: Math.max(this.downW.x, wx), bottom: Math.max(this.downW.y, wy) }; break;
      case "move": if (this.moved) this.moveSelection(wx - this.downW.x, wy - this.downW.y); break;
      case "resize": this.resize(wx, wy); break;
      case "rotate": this.rotateSel(wx, wy); break;
    }
    if (this.tool !== "laser" && this.mode !== "pan") this.session.sendCursor(wx, wy, false);
    this.redraw();
  }

  /** Mouse hover with no button down: share our pointer like the phone does while touching. */
  hover(sx: number, sy: number) { this.session.sendCursor(this.toWorldX(sx), this.toWorldY(sy), false); }

  onUp(sx: number, sy: number) {
    const wx = this.toWorldX(sx), wy = this.toWorldY(sy);
    switch (this.mode) {
      case "draw": this.commitDraft(); break;
      case "erase": {
        const ids = [...this.erasing]; this.erasing = new Set();
        if (ids.length) {
          const copies = ids.map((i) => this.board.objects.get(i)?.deepCopy()).filter((o): o is BoardObj => !!o);
          this.session.boardDelete(ids); this.images.forget(ids);
          for (const i of ids) this.selection.delete(i);
          this.push({ undo: [{ k: "add", objs: copies }], redo: [{ k: "del", ids }] });
        }
        break;
      }
      case "marquee": {
        const m = this.marquee;
        if (m && this.moved) {
          const hit = [...this.board.objects.values()].filter((o) => BoardGeo.intersects(m, BoardGeo.rotatedBounds(o))).map((o) => o.id);
          this.selection = this.expandGroup(new Set(hit));
        } else if (!this.moved) this.selection = new Set();
        this.marquee = null;
        break;
      }
      case "move": case "resize": case "rotate":
        if (this.moved) { this.flushPending(); this.patchEntry(this.snap); }
        else if (this.mode === "move") {
          const o = this.hitObject(wx, wy), t = nowMs();
          if (o && o.type === "text" && this.lastTapId === o.id && t - this.lastTapAt < 400 && !o.locked) this.textEdit = { existing: o, wx: o.x, wy: o.y };
          this.lastTapAt = t; this.lastTapId = o?.id ?? null;
        }
        break;
      case "text": if (!this.moved) this.textEdit = { existing: null, wx, wy }; break;
      case "laser": this.session.hideCursor(); break;
    }
    this.mode = ""; this.snap.clear();
    this.redraw();
  }

  cancelGesture() {
    if (this.mode === "draw") this.draft = null;
    if (this.mode === "marquee") this.marquee = null;
    if (this.mode === "erase") this.erasing = new Set();
    if (this.mode === "move" || this.mode === "resize" || this.mode === "rotate") {
      for (const [id, o] of this.snap) { const d = this.board.objects.get(id); if (d) this.restoreGeometry(d, o); }
      this.board.touch();
    }
    this.mode = ""; this.snap.clear();
    this.redraw();
  }

  private restoreGeometry(d: BoardObj, s: BoardObj) {
    d.points = s.points ? [...s.points] : null;
    d.x = s.x; d.y = s.y; d.w = s.w; d.h = s.h; d.x1 = s.x1; d.y1 = s.y1; d.x2 = s.x2; d.y2 = s.y2; d.size = s.size; d.rot = s.rot;
  }
  private snapshot() { this.snap.clear(); for (const o of this.selected()) this.snap.set(o.id, o.deepCopy()); }

  /* ---- drawing ---- */
  private startDraft(wx: number, wy: number) {
    const t = this.tool;
    const type = t === "line" ? "line" : t === "arrow" ? "arrow" : t === "rect" ? "rect" : t === "ellipse" ? "ellipse" : "stroke";
    const o = new BoardObj(Ids.id("b_"), type);
    o.color = this.color;
    o.width = t === "highlight" ? Math.max(10, this.width * 4) : t === "pen" ? Math.max(3, this.width) : this.width;
    o.opacity = t === "highlight" ? 0.35 : this.opacity;
    o.dash = this.dash && t !== "pencil" && t !== "pen" && t !== "highlight";
    if (type === "stroke") { this.stroke = [wx, wy]; o.points = [wx, wy]; }
    else if (type === "line" || type === "arrow") { o.x1 = wx; o.y1 = wy; o.x2 = wx; o.y2 = wy; }
    else { o.x = wx; o.y = wy; o.w = 0; o.h = 0; }
    this.draft = o;
  }

  private updateDraft(wx: number, wy: number) {
    const o = this.draft; if (!o) return;
    if (o.type === "stroke") {
      const n = this.stroke.length / 2, lx = this.stroke[(n - 1) * 2], ly = this.stroke[(n - 1) * 2 + 1];
      if (Math.hypot(wx - lx, wy - ly) * this.zoom > 1.6 * this.density && n < 6000) { this.stroke.push(wx, wy); o.points = [...this.stroke]; }
    } else if (o.type === "line" || o.type === "arrow") {
      let x = wx, y = wy;
      const a = Math.atan2(y - o.y1, x - o.x1), step = Math.PI / 12, snapped = Math.round(a / step) * step;
      if (Math.abs(a - snapped) < 0.05) { const d = Math.hypot(x - o.x1, y - o.y1); x = o.x1 + d * Math.cos(snapped); y = o.y1 + d * Math.sin(snapped); }
      o.x2 = x; o.y2 = y;
    } else { o.w = wx - o.x; o.h = wy - o.y; }
  }

  private commitDraft() {
    const o = this.draft; if (!o) return;
    this.draft = null;
    if (o.type === "stroke") { if (this.stroke.length < 4) o.points = [this.downW.x, this.downW.y]; }
    else if (o.type === "line" || o.type === "arrow") { if (Math.hypot(o.x2 - o.x1, o.y2 - o.y1) * this.zoom < 6 * this.density) return; }
    else if (Math.abs(o.w) * this.zoom < 6 * this.density && Math.abs(o.h) * this.zoom < 6 * this.density) return;
    if (o.type === "rect" || o.type === "ellipse") {
      const b = BoardGeo.bbox(o), bw = b.right - b.left, bh = b.bottom - b.top;
      o.x = b.left + o.width / 2; o.y = b.top + o.width / 2; o.w = bw - o.width; o.h = bh - o.width;
      if (o.w < 1 || o.h < 1) { o.x = b.left; o.y = b.top; o.w = bw; o.h = bh; }
    }
    this.session.boardAdd(o);
    this.push({ undo: [{ k: "del", ids: [o.id] }], redo: [{ k: "add", objs: [o.deepCopy()] }] });
  }

  /* ---- erase / laser ---- */
  private eraseAt(wx: number, wy: number) {
    const tol = (12 * this.density) / this.zoom, list = this.sorted();
    for (let i = list.length - 1; i >= 0; i--) {
      const o = list[i];
      if (o.locked || this.erasing.has(o.id)) continue;
      if (BoardGeo.hit(o, wx, wy, tol)) { this.erasing = new Set([...this.erasing, o.id]); break; }
    }
  }
  private laser(wx: number, wy: number) {
    let l = this.lasers.get("me"); if (!l) this.lasers.set("me", (l = []));
    l.push({ x: wx, y: wy, t: nowMs() });
    this.session.sendCursor(wx, wy, true);
  }

  /* ---- transforms ---- */
  private moveSelection(dx: number, dy: number) {
    for (const [id, src] of this.snap) {
      const o = this.board.objects.get(id); if (!o || o.locked) continue;
      this.restoreGeometry(o, src); BoardGeo.translate(o, dx, dy); this.queuePatch(o);
    }
    this.board.touch();
  }
  private rotateSel(wx: number, wy: number) {
    const src = this.snap.values().next().value as BoardObj | undefined; if (!src) return;
    const o = this.board.objects.get(src.id); if (!o) return;
    const c = BoardGeo.center(src);
    let a = Math.atan2(wy - c.y, wx - c.x) + Math.PI / 2;
    const step = Math.PI / 12, s = Math.round(a / step) * step;
    if (Math.abs(a - s) < 0.06) a = s;
    o.rot = a; this.queuePatch(o); this.board.touch();
  }
  private resize(wx: number, wy: number) {
    const sel = [...this.snap.values()]; if (!sel.length) return;
    if (sel.length === 1) {
      const s0 = sel[0], o = this.board.objects.get(s0.id); if (!o) return;
      if (s0.type === "line" || s0.type === "arrow") {
        this.restoreGeometry(o, s0);
        if (this.handle === 0) { o.x1 = wx; o.y1 = wy; } else { o.x2 = wx; o.y2 = wy; }
        this.queuePatch(o); this.board.touch(); return;
      }
      this.resizeSingle(o, s0, wx, wy); this.queuePatch(o); this.board.touch();
    } else this.resizeMulti(sel, wx, wy);
  }
  private resizeSingle(o: BoardObj, s0: BoardObj, wx: number, wy: number) {
    const b = BoardGeo.bbox(s0), c0 = BoardGeo.center(s0), p = BoardGeo.toLocal(s0, wx, wy), h = this.handle;
    let l = b.left, t = b.top, r = b.right, bt = b.bottom;
    if (h === 0 || h === 6 || h === 7) l = Math.min(p.x, r - 4);
    if (h === 2 || h === 3 || h === 4) r = Math.max(p.x, l + 4);
    if (h === 0 || h === 1 || h === 2) t = Math.min(p.y, bt - 4);
    if (h === 4 || h === 5 || h === 6) bt = Math.max(p.y, t + 4);
    let nw = r - l, nh = bt - t;
    const corner = h === 0 || h === 2 || h === 4 || h === 6;
    const ow = Math.max(1, b.right - b.left), oh = Math.max(1, b.bottom - b.top);
    let uniform = 1;
    if ((s0.type === "image" || s0.type === "text") && corner) {
      uniform = Math.max(nw / ow, nh / oh); nw = ow * uniform; nh = oh * uniform;
      if (h === 0 || h === 6) l = r - nw; else r = l + nw;
      if (h === 0 || h === 2) t = bt - nh; else bt = t + nh;
    } else if (s0.type === "text") return;
    const c1 = this.rotPt({ x: (l + r) / 2, y: (t + bt) / 2 }, c0, s0.rot);
    this.restoreGeometry(o, s0);
    if (s0.type === "rect" || s0.type === "ellipse" || s0.type === "image") { o.x = c1.x - nw / 2; o.y = c1.y - nh / 2; o.w = nw; o.h = nh; }
    else if (s0.type === "text") {
      o.size = Math.max(6, s0.size * uniform);
      const [tw, th] = BoardGeo.measureText(o);
      o.x = c1.x - tw / 2; o.y = c1.y - th / 2;
    } else if (s0.type === "stroke") {
      const pts = s0.points; if (!pts) return;
      const sx = nw / ow, sy = nh / oh;
      o.points = pts.map((v, i) => (i % 2 === 0 ? c1.x + (v - c0.x) * sx : c1.y + (v - c0.y) * sy));
    }
  }
  private resizeMulti(sel: BoardObj[], wx: number, wy: number) {
    const u = BoardGeo.union(sel); if (!u) return;
    const h = this.handle;
    let l = u.left, t = u.top, r = u.right, bt = u.bottom;
    if (h === 0 || h === 6 || h === 7) l = Math.min(wx, r - 4);
    if (h === 2 || h === 3 || h === 4) r = Math.max(wx, l + 4);
    if (h === 0 || h === 1 || h === 2) t = Math.min(wy, bt - 4);
    if (h === 4 || h === 5 || h === 6) bt = Math.max(wy, t + 4);
    const sx = (r - l) / Math.max(1, u.right - u.left), sy = (bt - t) / Math.max(1, u.bottom - u.top);
    const left = h === 0 || h === 6 || h === 7, top = h === 0 || h === 1 || h === 2;
    const ax = left ? u.right : u.left, ay = top ? u.bottom : u.top, nax = left ? r : l, nay = top ? bt : t;
    const mx = (x: number) => nax + (x - ax) * sx, my = (y: number) => nay + (y - ay) * sy;
    for (const s of sel) {
      const o = this.board.objects.get(s.id); if (!o || o.locked) continue;
      this.restoreGeometry(o, s);
      if (s.type === "stroke") { if (!s.points) continue; o.points = s.points.map((v, i) => (i % 2 === 0 ? mx(v) : my(v))); }
      else if (s.type === "line" || s.type === "arrow") { o.x1 = mx(s.x1); o.y1 = my(s.y1); o.x2 = mx(s.x2); o.y2 = my(s.y2); }
      else if (s.type === "text") { o.size = Math.max(6, s.size * ((sx + sy) / 2)); o.x = mx(s.x); o.y = my(s.y); }
      else { const x0 = mx(s.x), x1 = mx(s.x + s.w), y0 = my(s.y), y1 = my(s.y + s.h); o.x = x0; o.y = y0; o.w = x1 - x0; o.h = y1 - y0; }
      this.queuePatch(o);
    }
    this.board.touch();
  }

  /* ---- selection actions ---- */
  styleSelection(patch: J) {
    const sel = this.selected().filter((o) => !o.locked); if (!sel.length) return;
    const before = new Map<string, BoardObj>();
    for (const o of sel) before.set(o.id, o.deepCopy());
    for (const o of sel) { o.applyPatch({ ...patch }); this.queuePatch(o); }
    this.board.touch(); this.flushPending(); this.patchEntry(before); this.redraw();
  }
  duplicate() {
    const sel = this.selected(); if (!sel.length) return;
    const gmap = new Map<string, string>();
    const copies = sel.map((o) => {
      const c = o.deepCopy(), n = new BoardObj(Ids.id("b_"), o.type);
      Object.assign(n, c, { id: n.id, locked: false, group: c.group ? (gmap.get(c.group) ?? (gmap.set(c.group, Ids.id("g_")), gmap.get(c.group)!)) : null });
      BoardGeo.translate(n, 24, 24);
      return n;
    });
    for (const n of copies) this.session.boardAdd(n);
    this.selection = new Set(copies.map((c) => c.id));
    this.push({ undo: [{ k: "del", ids: copies.map((c) => c.id) }], redo: [{ k: "add", objs: copies.map((c) => c.deepCopy()) }] });
    this.redraw();
  }
  deleteSelection() {
    const sel = this.selected().filter((o) => !o.locked); if (!sel.length) return;
    const copies = sel.map((o) => o.deepCopy()), ids = sel.map((o) => o.id);
    this.session.boardDelete(ids); this.images.forget(ids); this.selection = new Set();
    this.push({ undo: [{ k: "add", objs: copies }], redo: [{ k: "del", ids }] });
    this.redraw();
  }
  group(on: boolean) {
    const sel = this.selected();
    if (sel.length < 2 && on) return;
    const gid = Ids.id("g_"), b = new Map<string, J>(), a = new Map<string, J>();
    for (const o of sel) {
      b.set(o.id, { group: o.group }); a.set(o.id, { group: on ? gid : null });
      o.group = on ? gid : null;
      this.session.boardUpdate(o.id, { group: on ? gid : null });
    }
    this.board.touch();
    this.push({ undo: [{ k: "patch", patches: b }], redo: [{ k: "patch", patches: a }] });
    this.redraw();
  }
  lock(on: boolean) {
    const sel = this.selected(); if (!sel.length) return;
    const b = new Map<string, J>(), a = new Map<string, J>();
    for (const o of sel) { b.set(o.id, { locked: o.locked ? 1 : 0 }); a.set(o.id, { locked: on ? 1 : 0 }); o.locked = on; this.session.boardUpdate(o.id, { locked: on ? 1 : 0 }); }
    this.board.touch();
    this.push({ undo: [{ k: "patch", patches: b }], redo: [{ k: "patch", patches: a }] });
    this.redraw();
  }
  order(front: boolean) {
    const sel = this.selected().sort((x, y) => x.z - y.z); if (!sel.length) return;
    const b = new Map<string, J>(), a = new Map<string, J>();
    let minZ = Infinity; for (const o of this.board.objects.values()) minZ = Math.min(minZ, o.z);
    if (!Number.isFinite(minZ)) minZ = 0;
    sel.forEach((o, i) => {
      const nz = front ? this.board.z + i : Math.max(0, minZ - sel.length + i);
      b.set(o.id, { z: o.z }); a.set(o.id, { z: nz }); o.z = nz;
      this.session.boardUpdate(o.id, { z: nz });
    });
    if (front) this.board.z += sel.length;
    this.board.touch();
    this.push({ undo: [{ k: "patch", patches: b }], redo: [{ k: "patch", patches: a }] });
    this.redraw();
  }

  /* ---- text / images ---- */
  commitText(edit: TextEdit, text: string) {
    const t = text.replace(/\s+$/, ""), ex = edit.existing;
    if (ex) {
      if (!t) {
        const copy = ex.deepCopy();
        this.session.boardDelete([ex.id]);
        this.push({ undo: [{ k: "add", objs: [copy] }], redo: [{ k: "del", ids: [ex.id] }] });
      } else if (t !== ex.text) {
        const before = ex.deepCopy();
        ex.text = t; this.session.boardUpdate(ex.id, { text: t }); this.board.touch();
        this.patchEntry(new Map([[ex.id, before]]));
      }
    } else if (t) {
      const o = new BoardObj(Ids.id("b_"), "text");
      o.x = edit.wx; o.y = edit.wy; o.text = t.slice(0, 2000); o.size = this.textSize; o.color = this.color; o.opacity = this.opacity;
      this.session.boardAdd(o);
      this.push({ undo: [{ k: "del", ids: [o.id] }], redo: [{ k: "add", objs: [o.deepCopy()] }] });
    }
    this.textEdit = null;
    this.redraw();
  }
  placeImage(base64: string, pxW: number, pxH: number, at?: Pt) {
    const o = new BoardObj(Ids.id("b_"), "image");
    const k = Math.min(1, 480 / Math.max(pxW, pxH));
    o.w = pxW * k; o.h = pxH * k;
    const cx = at?.x ?? this.toWorldX(this.vw / 2), cy = at?.y ?? this.toWorldY(this.vh / 2);
    o.x = cx - o.w / 2; o.y = cy - o.h / 2; o.src = base64;
    this.session.boardAdd(o);
    this.selection = new Set([o.id]);
    this.push({ undo: [{ k: "del", ids: [o.id] }], redo: [{ k: "add", objs: [o.deepCopy()] }] });
    this.tool = "select";
    this.redraw();
  }

  dispose() { cancelLater(this.flushTimer); this.flushPending(); }
}
