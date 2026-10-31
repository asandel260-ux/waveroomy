import { Flow } from "../core/util";

export type Route =
  | { t: "home" } | { t: "room" } | { t: "create" } | { t: "join"; code: string } | { t: "invite"; purpose: string }
  | { t: "chat"; uid: string } | { t: "profile"; uid: string } | { t: "people" } | { t: "editProfile" } | { t: "settings" }
  | { t: "moderation" } | { t: "help" } | { t: "legal" } | { t: "about" } | { t: "newGroup" } | { t: "groupInfo"; gid: string };

const same = (a: Route, b: Route) => JSON.stringify(a) === JSON.stringify(b);

/** Screen stack. On desktop the Home tabs live in a sidebar; everything else is pushed on top of Home. */
export class Nav {
  readonly stack = new Flow<Route[]>([{ t: "home" }]);
  readonly homeTab = new Flow(0);
  get top(): Route { const s = this.stack.value; return s[s.length - 1]; }
  push(r: Route) { if (same(this.top, r)) return; this.stack.value = [...this.stack.value, r]; }
  pop(): boolean { if (this.stack.value.length <= 1) return false; this.stack.value = this.stack.value.slice(0, -1); return true; }
  replaceTop(r: Route) { this.stack.value = [...this.stack.value.slice(0, -1), r]; }
  resetTo(r: Route) { this.stack.value = [r]; }
  removeAll(t: Route["t"]) { const s = this.stack.value.filter((r) => r.t !== t); this.stack.value = s.length ? s : [{ t: "home" }]; }
  has(t: Route["t"]) { return this.stack.value.some((r) => r.t === t); }
  /** Tapping a sidebar tab: show Home with that tab. */
  goHome(tab: number) { this.homeTab.value = tab; this.stack.value = [{ t: "home" }]; }
}
