import React, { useEffect, useRef, useState } from "react";
import { core } from "../../app/instance";
import { native } from "../../core/native";
import { useTick } from "../hooks";
import { Button, Chip, Field, Icon, IconButton, Muted, Segmented, Sheet, Tiny } from "../components/kit";

export function normalizeUrl(input: string): string {
  const t = input.trim();
  if (!t) return "";
  if (/^https?:\/\//i.test(t)) return t;
  if (!t.includes(" ") && t.includes(".")) return "https://" + t;
  return "https://duckduckgo.com/?q=" + encodeURIComponent(t);
}

export function BrowsePanel() {
  const session = core.session;
  useTick(session.tick);
  const s = session.room;
  const web = useRef<any>(null);
  const loadedNonce = useRef(-1);
  const [address, setAddress] = useState("");
  const [perms, setPerms] = useState(false);
  const [requested, setRequested] = useState(false);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [barOpen, setBarOpen] = useState(true);
  const [wvReady, setWvReady] = useState(false);
  const b = s?.browse;
  const canControl = session.canBrowseControl();
  const me = session.me;
  const bRef = useRef(b); bRef.current = b;

  useEffect(() => { setAddress(b?.url ?? ""); }, [b?.url]);

  // load whatever the room is on
  useEffect(() => {
    const w = web.current; if (!w || !wvReady || !b) return;
    const u = b.url;
    if (u && b.nonce !== loadedNonce.current) {
      loadedNonce.current = b.nonce;
      try { if (w.getURL() !== u) w.loadURL(u); else if (b.nonce > 0) w.reload(); } catch { /* ignore */ }
    }
    if (!u) { loadedNonce.current = -1; try { w.loadURL("about:blank"); } catch { /* ignore */ } }
  }, [b?.nonce, b?.url, wvReady]);

  useEffect(() => {
    const w = web.current; if (!w) return;
    const onReady = () => setWvReady(true);
    const onStart = () => { setLoading(true); setLoadError(null); };
    const onStop = () => {
      setLoading(false);
      const cur = bRef.current?.url;
      try { const u = w.getURL(); const t = w.getTitle(); if (u && cur && u === cur && session.canBrowseControl() && t) session.browseTitle(u, t); } catch { /* ignore */ }
    };
    const onFail = (e: any) => { if (e.isMainFrame && e.errorCode !== -3) setLoadError(e.errorDescription || "unknown error"); };
    const onNav = (e: any) => {
      if (!e.isMainFrame || e.isInPlace) return;
      const u: string = e.url;
      if (!/^https?:/.test(u)) return;
      const cur = bRef.current?.url;
      if (u === cur) return;
      if (session.canBrowseControl()) { let host = ""; try { host = new URL(u).host; } catch { /* ignore */ } session.browseNavigate(u, "", host); }
      else { core.toast("The host is driving. Request control to browse."); if (cur) setTimeout(() => { try { w.loadURL(cur); } catch { /* ignore */ } }, 0); }
    };
    w.addEventListener("dom-ready", onReady);
    w.addEventListener("did-start-loading", onStart);
    w.addEventListener("did-stop-loading", onStop);
    w.addEventListener("did-fail-load", onFail);
    w.addEventListener("did-start-navigation", onNav);
    return () => {
      w.removeEventListener("dom-ready", onReady); w.removeEventListener("did-start-loading", onStart); w.removeEventListener("did-stop-loading", onStop);
      w.removeEventListener("did-fail-load", onFail); w.removeEventListener("did-start-navigation", onNav);
      try { w.stop(); w.loadURL("about:blank"); } catch { /* ignore */ }
    };
  }, []);

  if (!s || !b) return null;
  const go = (raw: string) => {
    const u = normalizeUrl(raw);
    if (!u) return;
    if (!canControl) { core.toast("Ask for control to browse for the room"); return; }
    let host = ""; try { host = new URL(u).host; } catch { /* ignore */ }
    session.browseNavigate(u, "", host);
  };
  const navs = b.mods.map((uid) => { const p = session.participantByUid(uid); return p ? [uid, p.displayName] as const : null; }).filter(Boolean) as (readonly [string, string])[];

  return (
    <div style={{ flex: 1, minHeight: 0, display: "flex", flexDirection: "column", background: "var(--bg)" }}>
      {barOpen && <>
        <div className="hstack" style={{ padding: "4px 6px" }}>
          <IconButton icon="arrow-left" size={40} iconSize={18} disabled={!canControl || !(b.idx > 0)} onClick={() => session.browseStep(false)} title="Back" />
          <IconButton icon="arrow-right" size={40} iconSize={18} disabled={!canControl || !(b.idx < b.history.length - 1)} onClick={() => session.browseStep(true)} title="Forward" />
          <IconButton icon="reload" size={40} iconSize={18} disabled={!canControl || b.url == null} onClick={() => session.browseReload()} title="Reload" />
          <IconButton icon="home" size={40} iconSize={18} disabled={!canControl || b.url == null} onClick={() => session.browseHome()} title="Home" />
          <div className="grow" style={{ padding: "0 4px" }}><Field placeholder="Search or enter address" value={address} onChange={setAddress} onEnter={() => go(address)} /></div>
          <IconButton icon="users" size={40} iconSize={18} badge={me?.role === "host" ? b.requests.length : 0} onClick={() => setPerms(true)} title="Who can navigate" />
        </div>
        <div className="hstack" style={{ padding: "2px 14px" }}>
          <div className="grow"><Muted size={12}>{canControl ? "You're driving — everyone sees what you open." : `${b.navigatorName || "The host"} is driving. You can follow along.`}</Muted></div>
          {!canControl ? <Chip text={requested ? "Requested" : "Request control"} selected={!requested} onClick={() => { if (!requested) { setRequested(true); session.browseControl("request"); } }} />
            : me?.role !== "host" && b.mods.includes(me?.uid ?? "") ? <Chip text="Release" onClick={() => session.browseControl("release")} /> : null}
        </div>
      </>}
      <div style={{ flex: 1, position: "relative", minHeight: 0 }}>
        {React.createElement("webview", { ref: web, src: "about:blank", partition: "persist:browse", style: { position: "absolute", inset: 0, width: "100%", height: "100%", background: "#fff" } })}
        {b.url && (
          <div className="hstack g8" style={{ position: "absolute", right: 10, bottom: 10 }}>
            {[[barOpen ? "collapse" : "expand", () => setBarOpen(!barOpen)], ["external", () => { native.openUrl(b.url!); }]].map(([ic, act]) => (
              <button key={ic as string} onClick={act as () => void} style={{ width: 40, height: 40, borderRadius: "50%", display: "grid", placeItems: "center", background: "var(--surface)", boxShadow: "inset 0 0 0 1px var(--border)" }}><Icon name={ic as string} size={18} /></button>
            ))}
          </div>
        )}
        {loading && <div style={{ position: "absolute", top: 0, left: 0, right: 0, height: 3, background: "var(--surface2)", backgroundSize: "40% 100%", animation: "load 1s linear infinite" }} />}
        {loadError && b.url && (
          <div style={{ position: "absolute", inset: 0, background: "var(--bg)", display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", padding: 28, textAlign: "center" }}>
            <Icon name="globe" size={32} color="var(--faint)" />
            <div style={{ fontSize: 18, fontWeight: 600, margin: "10px 0 6px" }}>Couldn't load this page</div>
            <Muted>{loadError}<br /><br />Check your connection. Some sites are blocked on certain networks.</Muted>
            <div style={{ height: 14 }} /><Button small text="Try again" onClick={() => { setLoadError(null); try { web.current?.reload(); } catch { /* ignore */ } }} />
          </div>
        )}
        {b.url == null && (
          <div className="scroll" style={{ position: "absolute", inset: 0, background: "var(--bg)" }}><div style={{ maxWidth: 560, margin: "0 auto", padding: 20 }}>
            <Icon name="globe" size={32} color="var(--faint)" />
            <div style={{ fontSize: 20, fontWeight: 600, margin: "10px 0 4px" }}>Shared browser</div>
            <Muted>One person drives, everybody follows the same page. Each device loads the page itself, so your logins stay on your computer.</Muted>
            <div style={{ height: 16 }} /><Tiny>Quick start</Tiny><div style={{ height: 8 }} />
            {[["Wikipedia", "https://wikipedia.org"], ["DuckDuckGo", "https://duckduckgo.com"], ["OpenStreetMap", "https://openstreetmap.org"], ["Hacker News", "https://news.ycombinator.com"]].map(([n, u]) => (
              <button key={n} className="hstack g12" onClick={() => go(u)} style={{ width: "100%", textAlign: "left", margin: "4px 0", borderRadius: 4, background: "var(--surface)", boxShadow: "inset 0 0 0 1px var(--border)", padding: 14 }}>
                <Icon name="globe" size={20} color="var(--accent-bright)" />
                <div className="grow"><div style={{ fontSize: 15, fontWeight: 500 }}>{n}</div><Muted size={12}>{u.replace("https://", "")}</Muted></div>
                <Icon name="chevron-right" size={16} color="var(--faint)" />
              </button>
            ))}
          </div></div>
        )}
      </div>
      {perms && (
        <Sheet title="Shared browser" center onDismiss={() => setPerms(false)}>
          <div style={{ padding: "0 20px 16px" }}>
            {me?.role === "host" ? <>
              <Tiny>Who can navigate</Tiny><div style={{ height: 8 }} />
              <Segmented options={[["host", "Host only"], ["mods", "Navigators"], ["everyone", "Everyone"]]} selected={b.perm} onSelect={(v) => session.browsePerm(v)} />
              {b.requests.length > 0 && <><div style={{ height: 14 }} /><Tiny>Requests</Tiny>{b.requests.map((r) => (
                <div key={r.uid} className="hstack g6" style={{ padding: "6px 0" }}><span className="grow">{r.name}</span><Button small text="Allow" onClick={() => session.browseControl("grant", r.uid)} /><Button small kind="secondary" text="Dismiss" onClick={() => session.browseControl("dismiss", r.uid)} /></div>
              ))}</>}
              {navs.length > 0 && <><div style={{ height: 14 }} /><Tiny>Navigators</Tiny>{navs.map(([uid, name]) => (
                <div key={uid} className="hstack g6" style={{ padding: "6px 0" }}><span className="grow">{name}</span><Button small kind="secondary" text="Revoke" onClick={() => session.browseControl("revoke", uid)} /></div>
              ))}</>}
              <div style={{ height: 16 }} /><Button block kind="danger" text="End browsing session" onClick={() => { setPerms(false); session.browseEnd(); }} />
            </> : <Muted>Only the host manages who can navigate. Use “Request control” to ask.</Muted>}
          </div>
        </Sheet>
      )}
    </div>
  );
}
