import React from "react";
import { useEffect, useRef, useState } from "react";
import { core } from "../../app/instance";
import { expectedPosition, mkVideo } from "../../core/models";
import { fmtDuration, nowMs } from "../../core/util";
import { useTick } from "../hooks";
import { Button, Chip, Field, Icon, IconButton, Menu, Muted, Segmented, Sheet, SwitchRow, Tiny } from "../components/kit";

export function parseYouTube(url: string): string | null {
  const s = url.trim();
  for (const re of [/[?&]v=([A-Za-z0-9_-]{11})/, /youtu\.be\/([A-Za-z0-9_-]{11})/, /\/embed\/([A-Za-z0-9_-]{11})/, /\/shorts\/([A-Za-z0-9_-]{11})/, /\/live\/([A-Za-z0-9_-]{11})/]) {
    const m = re.exec(s); if (m) return m[1];
  }
  return /^[A-Za-z0-9_-]{11}$/.test(s) ? s : null;
}

async function fetchMeta(id: string): Promise<{ title: string; author: string }> {
  try {
    const r = await fetch(`https://www.youtube.com/oembed?url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3D${id}&format=json`);
    const j = await r.json();
    return { title: j.title || "YouTube video", author: j.author_name || "" };
  } catch { return { title: "YouTube video", author: "" }; }
}

let apiPromise: Promise<void> | null = null;
function loadApi(): Promise<void> {
  if ((window as any).YT?.Player) return Promise.resolve();
  if (!apiPromise) apiPromise = new Promise((res) => {
    (window as any).onYouTubeIframeAPIReady = () => res();
    const s = document.createElement("script"); s.src = "https://www.youtube.com/iframe_api"; document.head.appendChild(s);
  });
  return apiPromise;
}

/** Fallback: play the watch page itself and drive its <video> element (works for videos whose owner blocked embedding). */
const PAGE_JS = `(function(){if(window.__wr)return;window.__wr=1;
var st=document.createElement('style');st.textContent='html,body{overflow:hidden!important;background:#000!important}body *{visibility:hidden!important}video{visibility:visible!important;position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;z-index:2147483647!important;object-fit:contain!important;background:#000!important;pointer-events:none!important}';document.documentElement.appendChild(st);
function V(){return document.querySelector('video')}
window.__w={play:function(){var v=V();if(v){var p=v.play();if(p&&p.catch)p.catch(function(){})}},pause:function(){var v=V();if(v)v.pause()},seek:function(t){var v=V();if(v)v.currentTime=t},rate:function(r){var v=V();if(v)v.playbackRate=r},vol:function(x){var v=V();if(v){v.muted=(x==0);v.volume=x/100}},
read:function(){var v=V();if(!v)return null;var s=v.ended?0:(v.paused?2:(v.readyState<3?3:1));var d=(isFinite(v.duration)&&v.duration>0)?v.duration:0;if(!d){try{d=parseFloat(window.ytInitialPlayerResponse.videoDetails.lengthSeconds)||0}catch(e){}}return [v.currentTime||0,d,s,v.playbackRate]}};
})();`;

export function WatchPanel() {
  const session = core.session;
  useTick(session.tick);
  const s = session.room;
  const [ready, setReady] = useState(false);
  const [cur, setCur] = useState(0);
  const [dur, setDur] = useState(0);
  const [fallback, setFallback] = useState(false);
  const [drag, setDrag] = useState<number | null>(null);
  const [url, setUrl] = useState("");
  const [perms, setPerms] = useState(false);
  const [speed, setSpeed] = useState<{ x: number; y: number } | null>(null);
  const [muted, setMuted] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const host = useRef<HTMLDivElement>(null);
  const player = useRef<any>(null);
  const web = useRef<any>(null);
  const loadedId = useRef<string | null>(null);
  const lastFix = useRef(0);
  const wRef = useRef(s?.watch);
  wRef.current = s?.watch;
  const w = s?.watch;
  const canControl = session.canWatchControl();
  const isHost = session.isHost;

  /** Run a command on whichever player is active. */
  const cmd = (name: string, arg?: number) => {
    if (fallback) { web.current?.executeJavaScript?.(`window.__w&&window.__w.${name}(${arg ?? ""})`).catch?.(() => {}); return; }
    const p = player.current; if (!p) return;
    try {
      if (name === "play") p.playVideo(); else if (name === "pause") p.pauseVideo(); else if (name === "seek") p.seekTo(arg, true);
      else if (name === "rate") p.setPlaybackRate(arg); else if (name === "vol") p.setVolume(arg);
    } catch { /* ignore */ }
  };

  const onTick = (c: number, d: number, st: number, r: number) => {
    setCur(c); setDur(d);
    const cw = wRef.current; const v = cw?.video;
    if (!cw || !v) return;
    if (d > 0 && v.duration === 0) session.watchMeta(Math.floor(d));
    const expected = expectedPosition(cw);
    const now = nowMs();
    if (cw.playing && st !== 1 && st !== 3) cmd("play");
    if (!cw.playing && st === 1) cmd("pause");
    if (Math.abs(c - expected) > 0.9 && now - lastFix.current > 1500 && st !== -1 && st !== 5) { lastFix.current = now; cmd("seek", expected); }
    if (Math.abs(r - cw.rate) > 0.01) cmd("rate", cw.rate);
  };
  const tickRef = useRef(onTick); tickRef.current = onTick;

  // embedded player
  useEffect(() => {
    if (fallback) return;
    let dead = false; let iv: any;
    void loadApi().then(() => {
      if (dead || !host.current) return;
      const el = document.createElement("div"); host.current.innerHTML = ""; host.current.appendChild(el);
      const YT = (window as any).YT;
      player.current = new YT.Player(el, {
        width: "100%", height: "100%",
        playerVars: { controls: 0, disablekb: 1, rel: 0, iv_load_policy: 3, playsinline: 1, fs: 0, modestbranding: 1, origin: "https://www.youtube.com" },
        events: {
          onReady: () => setReady(true),
          onStateChange: (e: any) => { if (e.data === 0 && session.isHost && wRef.current?.playing) session.watchControl("skip"); },
          onError: (e: any) => { setFallback(true); loadedId.current = null; setReady(false); setErrorMsg(null); void e; }
        }
      });
      iv = setInterval(() => { const p = player.current; if (p?.getCurrentTime) tickRef.current(p.getCurrentTime(), p.getDuration() || 0, p.getPlayerState(), p.getPlaybackRate()); }, 500);
    });
    return () => { dead = true; clearInterval(iv); try { player.current?.destroy(); } catch { /* ignore */ } player.current = null; setReady(false); };
  }, [fallback]);

  // fallback webview
  useEffect(() => {
    if (!fallback) return;
    const wv = web.current; if (!wv) return;
    const onLoad = () => { void wv.executeJavaScript(PAGE_JS).then(() => setReady(true)).catch(() => {}); };
    wv.addEventListener("did-finish-load", onLoad);
    const iv = setInterval(async () => {
      try { const r = await wv.executeJavaScript("window.__w&&window.__w.read()"); if (r) tickRef.current(r[0], r[1], r[2], r[3]); } catch { /* not ready */ }
    }, 500);
    return () => { wv.removeEventListener("did-finish-load", onLoad); clearInterval(iv); };
  }, [fallback]);

  // load whichever video the room is on
  const vid = w?.video?.id ?? null;
  useEffect(() => {
    const cw = wRef.current;
    if (fallback) {
      if (vid && vid !== loadedId.current) { loadedId.current = vid; setReady(false); setErrorMsg(null); try { web.current?.loadURL(`https://m.youtube.com/watch?v=${vid}`); } catch { /* ignore */ } }
      if (!vid) loadedId.current = null;
      return;
    }
    if (ready && vid && vid !== loadedId.current && cw) {
      loadedId.current = vid; setErrorMsg(null);
      const p = player.current; const t = expectedPosition(cw);
      try { if (cw.playing) p.loadVideoById({ videoId: vid, startSeconds: t }); else p.cueVideoById({ videoId: vid, startSeconds: t }); } catch { /* ignore */ }
    }
    if (!vid) loadedId.current = null;
  }, [vid, ready, fallback]);
  // keep playing state in step even between ticks
  useEffect(() => {
    if (ready && loadedId.current && w) { cmd(w.playing ? "play" : "pause"); cmd("seek", expectedPosition(w)); }
  }, [w?.playing, w?.position, w?.anchorMs]);

  if (!s || !w) return null;
  const submitUrl = async () => {
    const id = parseYouTube(url);
    if (!id) { core.toast("That doesn't look like a YouTube link"); return; }
    setUrl("");
    const m = await fetchMeta(id);
    const v = mkVideo(id, m.title, m.author, 0);
    if (!w.video) session.watchStart(v); else session.watchQueueAdd(v);
  };
  const v = w.video;
  const total = dur > 0 ? dur : v?.duration ?? 0;
  const shown = drag ?? cur;

  return (
    <div style={{ flex: 1, minHeight: 0, display: "flex", background: "var(--bg)" }}>
      <div style={{ flex: 1.4, minWidth: 0, background: "#000", display: "grid", placeItems: "center" }}>
        <div style={{ position: "relative", width: "100%", maxHeight: "100%", aspectRatio: "16/9" }}>
          {fallback
            ? React.createElement("webview", { ref: web, src: "about:blank", partition: "persist:browse", style: { position: "absolute", inset: 0, width: "100%", height: "100%" } })
            : <div ref={host} style={{ position: "absolute", inset: 0 }} />}
          {!v && (
            <div style={{ position: "absolute", inset: 0, background: "var(--bg)", display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", gap: 8 }}>
              <Icon name="youtube" size={36} color="var(--faint)" />
              <div style={{ fontSize: 16, fontWeight: 600 }}>Nothing playing</div>
              <Muted size={12}>{canControl ? "Paste a YouTube link on the right" : "Waiting for someone to pick a video"}</Muted>
            </div>
          )}
          {errorMsg && v && <div style={{ position: "absolute", inset: 0, background: "rgb(0 0 0 / .8)", display: "grid", placeItems: "center", color: "#fff", fontSize: 13, padding: 20 }}>{errorMsg}</div>}
        </div>
      </div>
      <div style={{ flex: 1, minWidth: 300, maxWidth: 420, overflowY: "auto", borderLeft: "1px solid var(--border)" }}>
        {v && (
          <div style={{ padding: "8px 14px" }}>
            <div style={{ fontSize: 15, fontWeight: 600 }}>{v.title}</div>
            <Muted size={12}>{[v.author, w.controllerName ? `last: ${w.controllerName}` : ""].filter(Boolean).join(" · ")}</Muted>
            <div className="hstack g8">
              <Tiny>{fmtDuration(shown)}</Tiny>
              <input type="range" min={0} max={Math.max(1, total)} step={1} value={Math.min(shown, Math.max(1, total))} disabled={!canControl || total <= 0} style={{ flex: 1, accentColor: "var(--accent-bright)" }}
                onChange={(e) => canControl && setDrag(parseFloat(e.target.value))}
                onPointerUp={() => { if (drag != null) session.watchControl("seek", drag); setDrag(null); }}
                onKeyUp={() => { if (drag != null) session.watchControl("seek", drag); setDrag(null); }} />
              <Tiny>{fmtDuration(total)}</Tiny>
            </div>
            <div className="hstack" style={{ justifyContent: "space-between" }}>
              <IconButton icon="restart" disabled={!canControl} onClick={() => session.watchControl("restart")} title="Restart" />
              <IconButton icon={w.playing ? "pause" : "play"} size={52} disabled={!canControl} bg={canControl ? "var(--accent)" : "var(--surface2)"} color="#fff" onClick={() => session.watchControl(w.playing ? "pause" : "play")} />
              <IconButton icon="skip" disabled={!canControl} onClick={() => session.watchControl("skip")} title="Skip" />
              <Chip text={`${w.rate}x`} onClick={(e?: any) => { if (canControl && e) { const b = e.currentTarget.getBoundingClientRect(); setSpeed({ x: b.left, y: b.bottom }); } }} />
              <IconButton icon={muted ? "volume-x" : "volume"} onClick={() => { setMuted(!muted); cmd("vol", muted ? 100 : 0); }} />
              {isHost && <IconButton icon="sliders" onClick={() => setPerms(true)} />}
            </div>
            {!canControl && <Muted size={12}>{w.perm === "mods" ? "Only playback moderators can control this." : "The host controls playback."}</Muted>}
          </div>
        )}
        <div className="hstack g8" style={{ padding: "6px 14px" }}>
          <div className="grow"><Field placeholder="YouTube link" leadingIcon="link" value={url} onChange={setUrl} onEnter={() => void submitUrl()} /></div>
          <Button small text={w.video ? "Queue" : "Play"} disabled={!canControl || !url.trim()} onClick={() => void submitUrl()} />
        </div>
        {w.queue.length > 0 && <>
          <div style={{ padding: "6px 14px" }}><Tiny>Up next · {w.queue.length}</Tiny></div>
          {w.queue.map((q) => (
            <div key={q.qid || q.id} className="hstack" style={{ padding: "8px 14px" }}>
              <div className="grow" style={{ minWidth: 0 }}><div className="ellipsis" style={{ fontSize: 14 }}>{q.title}</div><Muted size={12}>added by {q.by}</Muted></div>
              {canControl && <IconButton icon="x" size={36} iconSize={16} color="var(--muted)" onClick={() => session.watchQueueRemove(q.qid)} />}
            </div>
          ))}
        </>}
      </div>
      {speed && <Menu at={speed} onDismiss={() => setSpeed(null)} entries={[0.5, 0.75, 1, 1.25, 1.5, 2].map((r) => ({ label: `${r}x`, onClick: () => session.watchControl("rate", r) }))} />}
      {perms && (
        <Sheet title="Watch Together" center onDismiss={() => setPerms(false)}>
          <div style={{ padding: "0 20px 16px" }}>
            <Tiny>Who can control playback</Tiny><div style={{ height: 8 }} />
            <Segmented options={[["host", "Host only"], ["mods", "Moderators"], ["everyone", "Everyone"]]} selected={w.perm} onSelect={(x) => session.watchPerm(x)} />
            {w.perm === "mods" && <div style={{ marginTop: 12 }}>{[...s.participants.values()].filter((p) => p.role !== "host").map((p) => <SwitchRow key={p.uid} title={p.displayName} checked={w.mods.includes(p.uid)} onChange={(on) => session.watchMod(p.uid, on)} />)}</div>}
            <div style={{ height: 14 }} />
            <Button block kind="danger" text="End Watch Together" onClick={() => { setPerms(false); session.watchEnd(); }} />
          </div>
        </Sheet>
      )}
    </div>
  );
}
