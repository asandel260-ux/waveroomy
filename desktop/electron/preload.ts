import { contextBridge, ipcRenderer } from "electron";

const on = (ch: string, cb: (...a: any[]) => void) => ipcRenderer.on(ch, (_e, ...a) => cb(...a));

contextBridge.exposeInMainWorld("homitNative", {
  kind: "electron",
  platform: process.platform,
  version: ipcRenderer.sendSync("app:version"),
  docsLoad: () => ipcRenderer.invoke("docs:load"),
  docWrite: (name: string, text: string) => ipcRenderer.send("docs:write", name, text),
  docDelete: (name: string) => ipcRenderer.send("docs:delete", name),
  binRead: async (name: string) => { const b = await ipcRenderer.invoke("bin:read", name); return b ? new Uint8Array(b) : null; },
  binWrite: (name: string, bytes: Uint8Array) => ipcRenderer.invoke("bin:write", name, bytes),
  binDelete: (name: string) => ipcRenderer.send("bin:delete", name),
  binList: () => ipcRenderer.invoke("bin:list"),
  wipe: () => ipcRenderer.invoke("app:wipe"),
  copy: (t: string) => ipcRenderer.send("app:copy", t),
  openUrl: (u: string) => ipcRenderer.send("app:open-url", u),
  saveFile: (name: string, bytes: Uint8Array) => ipcRenderer.invoke("app:save-file", name, bytes),
  pickFile: async (accept: string) => {
    const r = await ipcRenderer.invoke("app:pick-file", accept);
    return r ? { name: r.name, type: r.type, bytes: new Uint8Array(r.bytes) } : null;
  },
  notify: (title: string, body: string) => ipcRenderer.send("app:notify", title, body),
  setBadge: (n: number) => ipcRenderer.send("app:badge", n),
  onOpenUrl: (cb: (u: string) => void) => on("app:url", cb),
  setPrefs: (p: any) => ipcRenderer.send("app:prefs", p),
  lan: {
    available: true,
    start: () => ipcRenderer.invoke("lan:start"),
    setBeacons: (p: any[]) => ipcRenderer.send("lan:beacons", p),
    addresses: () => ipcRenderer.invoke("lan:addresses"),
    connect: (ip: string, port: number) => ipcRenderer.invoke("lan:connect", ip, port),
    send: (id: number, line: string) => ipcRenderer.send("lan:send", id, line),
    close: (id: number) => ipcRenderer.send("lan:close", id),
    listen: (b: boolean) => ipcRenderer.send("lan:listen", b),
    onBeacon: (cb: (m: any, ip: string) => void) => on("lan:beacon", cb),
    onIncoming: (cb: (id: number, line: string | null) => void) => on("lan:in", cb),
    onOutgoing: (cb: (id: number, line: string | null) => void) => on("lan:out", cb)
  },
  screens: {
    sources: () => ipcRenderer.invoke("screens:sources"),
    choose: (id: string | null, audio: boolean) => ipcRenderer.invoke("screens:choose", id, audio)
  },
  win: {
    minimize: () => ipcRenderer.send("win:minimize"),
    maximize: () => ipcRenderer.send("win:maximize"),
    close: () => ipcRenderer.send("win:close"),
    isMaximized: () => ipcRenderer.invoke("win:is-max"),
    onState: (cb: (m: boolean) => void) => on("win:state", cb)
  }
});
