import { app, BrowserWindow, Menu, Notification, Tray, clipboard, desktopCapturer, dialog, ipcMain, nativeImage, session, shell } from "electron";
import * as dgram from "node:dgram";
import * as fs from "node:fs";
import * as net from "node:net";
import * as os from "node:os";
import * as path from "node:path";

const LAN_PORT = 47877;
const MAX_LINE = 80_000;

app.commandLine.appendSwitch("disable-features", "WebRtcHideLocalIpsWithMdns");
app.commandLine.appendSwitch("autoplay-policy", "no-user-gesture-required");

let win: BrowserWindow | null = null;
let tray: Tray | null = null;
let quitting = false;
const prefs = { closeToTray: true, startMinimized: false, notifications: true };
let pendingUrl: string | null = null;

const docsDir = () => path.join(app.getPath("userData"), "docs");
const binDir = () => path.join(app.getPath("userData"), "bin");
const safe = (name: string) => name.split("/").filter((p) => p && p !== "." && p !== "..").map((p) => p.replace(/[^A-Za-z0-9_.@-]/g, "_")).join(path.sep);
const within = (base: string, name: string) => path.join(base, safe(name));

/* ---------------- single instance + links ---------------- */

if (!app.requestSingleInstanceLock()) { app.quit(); process.exit(0); }
for (const proto of ["homit", "waveroom"]) {
  try {
    if (process.defaultApp && process.argv.length >= 2) app.setAsDefaultProtocolClient(proto, process.execPath, [path.resolve(process.argv[1])]);
    else app.setAsDefaultProtocolClient(proto);
  } catch { /* ignore */ }
}
const urlFrom = (argv: string[]) => argv.find((a) => /^(homit|waveroom):\/\//i.test(a)) ?? null;
function deliverUrl(u: string | null) {
  if (!u) return;
  if (win && !win.webContents.isLoading()) { win.webContents.send("app:url", u); showWindow(); } else pendingUrl = u;
}
app.on("second-instance", (_e, argv) => { showWindow(); deliverUrl(urlFrom(argv)); });
pendingUrl = urlFrom(process.argv);

/* ---------------- storage ---------------- */

const writes = new Map<string, Promise<void>>();
function atomicWrite(file: string, data: string | Buffer) {
  const prev = writes.get(file) ?? Promise.resolve();
  const next = prev.then(async () => {
    try {
      await fs.promises.mkdir(path.dirname(file), { recursive: true });
      const tmp = file + ".tmp";
      await fs.promises.writeFile(tmp, data);
      await fs.promises.rename(tmp, file);
    } catch (e) { console.warn("write failed", file, e); }
  });
  writes.set(file, next);
  void next.finally(() => { if (writes.get(file) === next) writes.delete(file); });
  return next;
}

function walk(dir: string, base = ""): string[] {
  const out: string[] = [];
  let entries: fs.Dirent[] = [];
  try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return out; }
  for (const e of entries) {
    const rel = base ? base + "/" + e.name : e.name;
    if (e.isDirectory()) out.push(...walk(path.join(dir, e.name), rel));
    else if (!e.name.endsWith(".tmp")) out.push(rel);
  }
  return out;
}

ipcMain.on("app:version", (e) => { e.returnValue = app.getVersion(); });
ipcMain.handle("docs:load", () => {
  const out: Record<string, string> = {};
  for (const rel of walk(docsDir())) { try { out[rel] = fs.readFileSync(path.join(docsDir(), rel), "utf8"); } catch { /* ignore */ } }
  return out;
});
ipcMain.on("docs:write", (_e, name: string, text: string) => { void atomicWrite(within(docsDir(), name), text); });
ipcMain.on("docs:delete", (_e, name: string) => { try { fs.rmSync(within(docsDir(), name), { force: true }); } catch { /* ignore */ } });
ipcMain.handle("bin:read", (_e, name: string) => { try { return fs.readFileSync(within(binDir(), name)); } catch { return null; } });
ipcMain.handle("bin:write", async (_e, name: string, bytes: Uint8Array) => { await atomicWrite(within(binDir(), name), Buffer.from(bytes)); });
ipcMain.on("bin:delete", (_e, name: string) => { try { fs.rmSync(within(binDir(), name), { force: true }); } catch { /* ignore */ } });
ipcMain.handle("bin:list", () => walk(binDir()));
ipcMain.handle("app:wipe", () => {
  try { fs.rmSync(docsDir(), { recursive: true, force: true }); fs.rmSync(binDir(), { recursive: true, force: true }); } catch { /* ignore */ }
});

/* ---------------- shell helpers ---------------- */

ipcMain.on("app:copy", (_e, t: string) => clipboard.writeText(String(t)));
ipcMain.on("app:open-url", (_e, u: string) => { if (/^https?:\/\//i.test(u)) void shell.openExternal(u); });
ipcMain.handle("app:save-file", async (_e, name: string, bytes: Uint8Array) => {
  if (!win) return false;
  const r = await dialog.showSaveDialog(win, { defaultPath: path.join(app.getPath("downloads"), path.basename(name)) });
  if (r.canceled || !r.filePath) return false;
  await fs.promises.writeFile(r.filePath, Buffer.from(bytes));
  return true;
});
ipcMain.handle("app:pick-file", async (_e, accept: string) => {
  if (!win) return null;
  const filters = accept.startsWith("image") ? [{ name: "Images", extensions: ["png", "jpg", "jpeg", "gif", "webp", "bmp"] }] : [{ name: "All files", extensions: ["*"] }];
  const r = await dialog.showOpenDialog(win, { properties: ["openFile"], filters });
  if (r.canceled || !r.filePaths[0]) return null;
  const file = r.filePaths[0];
  const stat = await fs.promises.stat(file);
  if (stat.size > 20 * 1024 * 1024) return { name: path.basename(file), type: "application/octet-stream", bytes: Buffer.alloc(20 * 1024 * 1024 + 1) };
  const ext = path.extname(file).slice(1).toLowerCase();
  const mime: Record<string, string> = { png: "image/png", jpg: "image/jpeg", jpeg: "image/jpeg", gif: "image/gif", webp: "image/webp", bmp: "image/bmp", pdf: "application/pdf", txt: "text/plain", mp4: "video/mp4", mp3: "audio/mpeg", zip: "application/zip" };
  return { name: path.basename(file), type: mime[ext] ?? "application/octet-stream", bytes: await fs.promises.readFile(file) };
});
ipcMain.on("app:notify", (_e, title: string, body: string) => {
  if (!prefs.notifications || !Notification.isSupported()) return;
  if (win && win.isFocused()) return;
  const n = new Notification({ title, body, silent: false });
  n.on("click", showWindow);
  n.show();
});
ipcMain.on("app:badge", (_e, n: number) => { tray?.setToolTip(n > 0 ? `HoMit: ${n} unread` : "HoMit"); });
ipcMain.on("app:prefs", (_e, p: typeof prefs) => { Object.assign(prefs, p); });

/* ---------------- window ---------------- */

function showWindow() {
  if (!win) { createWindow(); return; }
  if (win.isMinimized()) win.restore();
  win.show();
  win.focus();
}

function readPrefsFromDisk() {
  try {
    const j = JSON.parse(fs.readFileSync(path.join(docsDir(), "settings.json"), "utf8"));
    if (typeof j.closeToTray === "boolean") prefs.closeToTray = j.closeToTray;
    if (typeof j.startMinimized === "boolean") prefs.startMinimized = j.startMinimized;
    if (typeof j.notifications === "boolean") prefs.notifications = j.notifications;
  } catch { /* first run */ }
}

function createWindow() {
  win = new BrowserWindow({
    width: 1320, height: 840, minWidth: 940, minHeight: 620, backgroundColor: "#0A0710", show: false, frame: false, title: "HoMit",
    icon: path.join(__dirname, "..", "build", "icon.png"),
    webPreferences: { preload: path.join(__dirname, "preload.js"), contextIsolation: true, sandbox: true, webviewTag: true, backgroundThrottling: false }
  });
  win.removeMenu();
  void win.loadFile(path.join(__dirname, "..", "dist", "index.html"));
  win.once("ready-to-show", () => {
    if (!(prefs.startMinimized && process.argv.includes("--hidden"))) win?.show();
  });
  win.webContents.on("did-finish-load", () => { if (pendingUrl) { win?.webContents.send("app:url", pendingUrl); pendingUrl = null; } });
  win.webContents.setWindowOpenHandler(({ url }) => { if (/^https?:\/\//i.test(url)) void shell.openExternal(url); return { action: "deny" }; });
  win.webContents.on("will-navigate", (e, url) => { if (!url.startsWith("file://")) { e.preventDefault(); if (/^https?:\/\//i.test(url)) void shell.openExternal(url); } });
  win.on("maximize", () => win?.webContents.send("win:state", true));
  win.on("unmaximize", () => win?.webContents.send("win:state", false));
  win.on("close", (e) => {
    if (!quitting && prefs.closeToTray && tray) { e.preventDefault(); win?.hide(); }
  });
  win.on("closed", () => { win = null; });
}

ipcMain.on("win:minimize", () => win?.minimize());
ipcMain.on("win:maximize", () => { if (win?.isMaximized()) win.unmaximize(); else win?.maximize(); });
ipcMain.on("win:close", () => win?.close());
ipcMain.handle("win:is-max", () => win?.isMaximized() ?? false);

function createTray() {
  const iconPath = path.join(__dirname, "..", "build", "icon.png");
  let img = fs.existsSync(iconPath) ? nativeImage.createFromPath(iconPath) : nativeImage.createEmpty();
  if (!img.isEmpty()) img = img.resize({ width: 16, height: 16 });
  tray = new Tray(img);
  tray.setToolTip("HoMit");
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: "Open HoMit", click: showWindow },
    { type: "separator" },
    { label: "Quit", click: () => { quitting = true; app.quit(); } }
  ]));
  tray.on("click", showWindow);
}

/* ---------------- screen capture ---------------- */

let chosenSource: string | null = null;
let chosenAudio = false;

ipcMain.handle("screens:sources", async () => {
  const srcs = await desktopCapturer.getSources({ types: ["screen", "window"], thumbnailSize: { width: 320, height: 180 }, fetchWindowIcons: true });
  return srcs.map((s) => ({
    id: s.id, name: s.name, thumb: s.thumbnail.toDataURL(), appIcon: s.appIcon && !s.appIcon.isEmpty() ? s.appIcon.toDataURL() : "", isScreen: s.id.startsWith("screen:")
  }));
});
ipcMain.handle("screens:choose", (_e, id: string | null, audio: boolean) => { chosenSource = id; chosenAudio = audio; });

function setupSession() {
  const ses = session.defaultSession;
  ses.setDisplayMediaRequestHandler(async (_req, cb) => {
    try {
      const srcs = await desktopCapturer.getSources({ types: ["screen", "window"] });
      const pick = srcs.find((s) => s.id === chosenSource) ?? srcs[0];
      if (!pick) { cb({}); return; }
      cb({ video: pick, ...(chosenAudio ? { audio: "loopback" as const } : {}) });
    } catch { cb({}); }
  });
  const allowed = new Set(["media", "display-capture", "clipboard-sanitized-write", "clipboard-read", "notifications", "fullscreen", "audioCapture", "videoCapture"]);
  ses.setPermissionRequestHandler((_wc, perm, cb) => cb(allowed.has(perm)));
  ses.setPermissionCheckHandler((_wc, perm) => allowed.has(perm));

  // YouTube's embedded player refuses to play without a web referer, which an app has none of
  ses.webRequest.onBeforeSendHeaders({ urls: ["*://*.youtube.com/*", "*://*.youtube-nocookie.com/*", "*://*.googlevideo.com/*"] }, (d, cb) => {
    const h = d.requestHeaders;
    if (!h["Referer"] || h["Referer"].startsWith("file:")) h["Referer"] = "https://homit.app/";
    cb({ requestHeaders: h });
  });

  // the shared browser: a normal browser identity, no device permissions for websites
  const br = session.fromPartition("persist:browse");
  br.setUserAgent(ses.getUserAgent().replace(/ Electron\/[\d.]+/, "").replace(/ HoMit\/[\d.]+/, ""));
  br.setPermissionRequestHandler((_wc, perm, cb) => cb(perm === "fullscreen"));
  br.setPermissionCheckHandler((_wc, perm) => perm === "fullscreen");
}

/* ---------------- LAN: UDP beacons + TCP line protocol ---------------- */

let listenSock: dgram.Socket | null = null;
let sendSock: dgram.Socket | null = null;
let beaconPackets: any[] = [];
let beaconTimer: NodeJS.Timeout | null = null;
let server: net.Server | null = null;
let serverPort = 0;
let nextConn = 1;
const conns = new Map<number, net.Socket>();

function broadcastAddrs(): string[] {
  const out = new Set<string>(["255.255.255.255"]);
  for (const list of Object.values(os.networkInterfaces())) {
    for (const i of list ?? []) {
      if (i.family !== "IPv4" || i.internal) continue;
      const ip = i.address.split(".").map(Number), mask = i.netmask.split(".").map(Number);
      out.add(ip.map((b, k) => (b & mask[k]) | (~mask[k] & 255)).join("."));
    }
  }
  return [...out];
}

function sendBeacons() {
  if (!sendSock) return;
  for (const b of beaconPackets) {
    const data = Buffer.from(JSON.stringify(b), "utf8");
    for (const a of broadcastAddrs()) { try { sendSock.send(data, LAN_PORT, a); } catch { /* ignore */ } }
  }
}

function adopt(sock: net.Socket, id: number, channel: "lan:in" | "lan:out") {
  conns.set(id, sock);
  sock.setEncoding("utf8");
  sock.setNoDelay(true);
  let buf = "";
  sock.setTimeout(30_000, () => sock.destroy());
  sock.on("data", (chunk: string) => {
    buf += chunk;
    let nl: number;
    while ((nl = buf.indexOf("\n")) >= 0) {
      const line = buf.slice(0, nl).replace(/\r$/, "");
      buf = buf.slice(nl + 1);
      win?.webContents.send(channel, id, line);
    }
    if (buf.length > MAX_LINE) sock.destroy();
  });
  sock.on("error", () => { /* closed below */ });
  sock.on("close", () => { conns.delete(id); win?.webContents.send(channel, id, null); });
}

ipcMain.handle("lan:start", async () => {
  if (server) return serverPort;
  server = net.createServer((sock) => { adopt(sock, nextConn++, "lan:in"); });
  await new Promise<void>((res) => server!.listen(0, "0.0.0.0", () => res()));
  serverPort = (server.address() as net.AddressInfo).port;
  sendSock = dgram.createSocket({ type: "udp4", reuseAddr: true });
  sendSock.bind(0, () => { try { sendSock!.setBroadcast(true); } catch { /* ignore */ } });
  sendSock.on("error", () => { /* ignore */ });
  beaconTimer = setInterval(sendBeacons, 2500);
  return serverPort;
});
ipcMain.on("lan:beacons", (_e, packets: any[]) => { beaconPackets = packets ?? []; sendBeacons(); });
ipcMain.handle("lan:addresses", () => {
  const out: string[] = [];
  if (!serverPort) return out;
  for (const list of Object.values(os.networkInterfaces())) {
    for (const i of list ?? []) if (i.family === "IPv4" && !i.internal && !i.address.startsWith("169.254.")) out.push(`${i.address}:${serverPort}`);
  }
  return out.slice(0, 3);
});
ipcMain.handle("lan:connect", (_e, ip: string, port: number) => new Promise<number>((res) => {
  if (!net.isIPv4(ip) || !(port > 0 && port < 65536)) { res(-1); return; }
  const id = nextConn++;
  const sock = net.connect({ host: ip, port });
  const t = setTimeout(() => { sock.destroy(); res(-1); }, 6000);
  sock.once("connect", () => { clearTimeout(t); adopt(sock, id, "lan:out"); res(id); });
  sock.once("error", () => { clearTimeout(t); res(-1); });
}));
ipcMain.on("lan:send", (_e, id: number, line: string) => { conns.get(id)?.write(String(line) + "\n"); });
ipcMain.on("lan:close", (_e, id: number) => { const s = conns.get(id); if (s) setTimeout(() => s.end(), 50); });
ipcMain.on("lan:listen", (_e, on: boolean) => {
  if (!on) { try { listenSock?.close(); } catch { /* ignore */ } listenSock = null; return; }
  if (listenSock) return;
  const s = dgram.createSocket({ type: "udp4", reuseAddr: true });
  s.on("message", (msg, rinfo) => {
    if (msg.length > 2048) return;
    try { win?.webContents.send("lan:beacon", JSON.parse(msg.toString("utf8")), rinfo.address); } catch { /* ignore */ }
  });
  s.on("error", () => { try { s.close(); } catch { /* ignore */ } if (listenSock === s) listenSock = null; });
  s.bind(LAN_PORT, () => { try { s.setBroadcast(true); } catch { /* ignore */ } });
  listenSock = s;
});

/* ---------------- lifecycle ---------------- */

app.on("before-quit", () => { quitting = true; if (beaconTimer) clearInterval(beaconTimer); });
app.on("window-all-closed", () => { if (quitting || !prefs.closeToTray) app.quit(); });
app.whenReady().then(() => {
  readPrefsFromDisk();
  setupSession();
  createTray();
  createWindow();
  app.on("activate", showWindow);
});
