/* ---------------------------------------------------------------------------
 * The thin layer between the app and the machine it runs on. In the desktop app
 * `window.homitNative` is provided by the Electron preload; in a plain browser
 * (development, automated tests) a fallback built on IndexedDB takes over.
 * ------------------------------------------------------------------------- */

export interface LanEvents {
  onBeacon(cb: (msg: any, ip: string) => void): void;
  onIncoming(cb: (connId: number, line: string | null) => void): void;
  onOutgoing(cb: (connId: number, line: string | null) => void): void;
}

export interface ScreenSource { id: string; name: string; thumb: string; appIcon: string; isScreen: boolean }

export interface Native {
  kind: "electron" | "web";
  /** every small text document, keyed by name */
  docsLoad(): Promise<Record<string, string>>;
  docWrite(name: string, text: string): void;
  docDelete(name: string): void;
  binRead(name: string): Promise<Uint8Array | null>;
  binWrite(name: string, bytes: Uint8Array): Promise<void>;
  binDelete(name: string): void;
  binList(): Promise<string[]>;
  wipe(): Promise<void>;
  copy(text: string): void;
  openUrl(url: string): void;
  saveFile(name: string, bytes: Uint8Array): Promise<boolean>;
  pickFile(accept: string): Promise<{ name: string; type: string; bytes: Uint8Array } | null>;
  notify(title: string, body: string): void;
  setBadge(n: number): void;
  platform: string;
  version: string;
  lan: {
    available: boolean;
    start(): Promise<number>;                    // opens the TCP server, returns its port
    setBeacons(packets: any[]): void;            // payloads to broadcast every few seconds
    addresses(): Promise<string[]>;              // my "ip:port" strings
    connect(ip: string, port: number): Promise<number>;   // outgoing connection id, or -1
    send(connId: number, line: string): void;
    close(connId: number): void;
    listen(on: boolean): void;                   // start / stop receiving beacons
  } & LanEvents;
  screens: {
    sources(): Promise<ScreenSource[]>;
    choose(id: string | null, audio: boolean): Promise<void>;
  };
  /** homit:// and waveroom:// links opened from outside the app */
  onOpenUrl(cb: (url: string) => void): void;
  /** tell the shell about preferences it acts on (tray behaviour, start hidden) */
  setPrefs(p: { closeToTray: boolean; startMinimized: boolean; notifications: boolean }): void;
  /** window controls for the custom title bar */
  win: { minimize(): void; maximize(): void; close(): void; isMaximized(): Promise<boolean>; onState(cb: (max: boolean) => void): void };
}

declare global {
  interface Window { homitNative?: Native }
}

/* ---------------- browser fallback ---------------- */

function idb(): Promise<IDBDatabase> {
  return new Promise((res, rej) => {
    const r = indexedDB.open("homit", 1);
    r.onupgradeneeded = () => {
      r.result.createObjectStore("docs");
      r.result.createObjectStore("bin");
    };
    r.onsuccess = () => res(r.result);
    r.onerror = () => rej(r.error);
  });
}

function tx<T>(store: string, mode: IDBTransactionMode, f: (s: IDBObjectStore) => IDBRequest<T> | void): Promise<T | undefined> {
  return idb().then(
    (db) =>
      new Promise((res, rej) => {
        const t = db.transaction(store, mode);
        const r = f(t.objectStore(store));
        t.oncomplete = () => res(r ? (r as IDBRequest<T>).result : undefined);
        t.onerror = () => rej(t.error);
      })
  );
}

function webNative(): Native {
  const noop = () => {};
  return {
    kind: "web",
    platform: "web",
    version: "dev",
    async docsLoad() {
      const out: Record<string, string> = {};
      const db = await idb();
      await new Promise<void>((res) => {
        const t = db.transaction("docs", "readonly");
        const c = t.objectStore("docs").openCursor();
        c.onsuccess = () => {
          const cur = c.result;
          if (cur) { out[String(cur.key)] = String(cur.value); cur.continue(); }
        };
        t.oncomplete = () => res();
        t.onerror = () => res();
      });
      return out;
    },
    docWrite(name, text) { void tx("docs", "readwrite", (s) => { s.put(text, name); }); },
    docDelete(name) { void tx("docs", "readwrite", (s) => { s.delete(name); }); },
    async binRead(name) {
      const v = await tx<any>("bin", "readonly", (s) => s.get(name));
      return v ? new Uint8Array(v) : null;
    },
    async binWrite(name, bytes) { await tx("bin", "readwrite", (s) => { s.put(bytes.slice().buffer, name); }); },
    binDelete(name) { void tx("bin", "readwrite", (s) => { s.delete(name); }); },
    async binList() { return ((await tx<any>("bin", "readonly", (s) => s.getAllKeys())) ?? []).map(String); },
    async wipe() {
      await tx("docs", "readwrite", (s) => { s.clear(); });
      await tx("bin", "readwrite", (s) => { s.clear(); });
    },
    copy(text) { void navigator.clipboard?.writeText(text).catch(noop); },
    openUrl(url) { window.open(url, "_blank", "noopener"); },
    async saveFile(name, bytes) {
      const a = document.createElement("a");
      a.href = URL.createObjectURL(new Blob([bytes.slice().buffer]));
      a.download = name;
      a.click();
      setTimeout(() => URL.revokeObjectURL(a.href), 5000);
      return true;
    },
    pickFile(accept) {
      return new Promise((res) => {
        const i = document.createElement("input");
        i.type = "file";
        if (accept) i.accept = accept;
        i.onchange = async () => {
          const f = i.files?.[0];
          if (!f) return res(null);
          res({ name: f.name, type: f.type || "application/octet-stream", bytes: new Uint8Array(await f.arrayBuffer()) });
        };
        i.oncancel = () => res(null);
        i.click();
      });
    },
    notify(title, body) { try { if (Notification.permission === "granted") new Notification(title, { body }); } catch {} },
    setBadge: noop,
    onOpenUrl: noop,
    setPrefs: noop,
    lan: {
      available: false,
      async start() { return 0; },
      setBeacons: noop,
      async addresses() { return []; },
      async connect() { return -1; },
      send: noop,
      close: noop,
      listen: noop,
      onBeacon: noop,
      onIncoming: noop,
      onOutgoing: noop
    },
    screens: { async sources() { return []; }, async choose() {} },
    win: { minimize: noop, maximize: noop, close: noop, async isMaximized() { return false; }, onState: noop }
  };
}

export const native: Native = window.homitNative ?? webNative();
