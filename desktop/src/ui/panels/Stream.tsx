import { useEffect, useRef, useState } from "react";
import { core } from "../../app/instance";
import { native, type ScreenSource } from "../../core/native";
import type { Participant } from "../../core/models";
import { useFlow } from "../hooks";
import { Button, Icon, Muted, Segmented, Sheet, Spinner, SwitchRow, Tiny } from "../components/kit";

/** Shows somebody's shared screen (or the controls, if it is ours). */
export function StreamPanel({ p }: { p: Participant }) {
  const session = core.session;
  if (p.sid === session.mySid) return <SharerPanel p={p} />;
  const video = useRef<HTMLVideoElement>(null);
  const [hasFrame, setHasFrame] = useState(false);
  const at = p.sharing?.at ?? 0;
  useEffect(() => {
    setHasFrame(false);
    let live = true;
    let tries = 0;
    const attach = () => {
      if (!live) return;
      const t = session.linkTo(p.sid)?.remoteVideo();
      if (t && video.current) { video.current.srcObject = new MediaStream([t]); void video.current.play().catch(() => {}); return; }
      if (++tries < 120) setTimeout(attach, 500);
    };
    attach();
    return () => { live = false; };
  }, [p.sid, at]);
  return (
    <div style={{ position: "relative", flex: 1, minHeight: 0, background: "#000", display: "grid", placeItems: "center" }}>
      <video ref={video} playsInline autoPlay muted style={{ position: "absolute", inset: 0, width: "100%", height: "100%", objectFit: "contain" }} onPlaying={() => setHasFrame(true)} onLoadedData={() => setHasFrame(true)} onDoubleClick={(e) => { const v = e.currentTarget; if (document.fullscreenElement) void document.exitFullscreen(); else void v.requestFullscreen(); }} />
      {!hasFrame && <div className="stack" style={{ alignItems: "center", gap: 10, position: "relative" }}><Spinner size={28} /><span style={{ color: "rgb(255 255 255 / .8)", fontSize: 13 }}>Waiting for {p.displayName}'s screen…</span></div>}
      <div style={{ position: "absolute", top: 10, left: 10, background: "rgb(0 0 0 / .6)", borderRadius: 2, padding: "5px 10px", color: "#fff", fontSize: 12 }}>{p.displayName} · {p.sharing?.res}p {p.sharing?.fps}fps · double-click for full screen</div>
    </div>
  );
}

function SharerPanel({ p }: { p: Participant }) {
  const active = useFlow(core.screen.active);
  const [res, setRes] = useState(p.sharing?.res ?? 720);
  const [fps, setFps] = useState(p.sharing?.fps ?? 30);
  const preview = useRef<HTMLVideoElement>(null);
  useEffect(() => {
    const t = core.screen.previewTrack();
    if (t && preview.current) { preview.current.srcObject = new MediaStream([t]); void preview.current.play().catch(() => {}); }
  }, [active]);
  const quality = (r: number, f: number) => { core.screen.setQuality(r, f); core.session.shareQuality(r, f); };
  return (
    <div className="scroll"><div style={{ maxWidth: 560, margin: "0 auto", padding: 20, display: "flex", flexDirection: "column", alignItems: "center" }}>
      <div style={{ fontSize: 20 }}>{active ? "You're sharing your screen" : "Starting…"}</div>
      <div style={{ margin: "6px 0 14px" }}><Muted>Everyone in the room can see it.</Muted></div>
      <video ref={preview} muted playsInline style={{ width: "100%", maxHeight: 280, borderRadius: 4, background: "#000", objectFit: "contain" }} />
      <div style={{ height: 20 }} />
      <div style={{ width: "100%" }}>
        <Tiny>Resolution</Tiny><div style={{ height: 8 }} />
        <Segmented options={[["240", "240p"], ["480", "480p"], ["720", "720p"], ["1080", "1080p"]]} selected={String(res)} onSelect={(v) => { setRes(+v); quality(+v, fps); }} />
        <div style={{ height: 12 }} /><Tiny>Frame rate</Tiny><div style={{ height: 8 }} />
        <Segmented options={[["5", "5"], ["15", "15"], ["30", "30"], ["60", "60"]]} selected={String(fps)} onSelect={(v) => { setFps(+v); quality(res, +v); }} />
        <div style={{ height: 24 }} />
        <Button block kind="danger" icon="x" text="Stop sharing" onClick={() => core.screen.stop(true)} />
      </div>
    </div></div>
  );
}

/** Pick a screen or window and a quality, then start sharing. */
export function ShareSheet({ onDismiss, onStarted }: { onDismiss: () => void; onStarted: () => void }) {
  const st = useFlow(core.settings.state);
  const [res, setRes] = useState(st.shareRes);
  const [fps, setFps] = useState(st.shareFps);
  const [audio, setAudio] = useState(false);
  const [sources, setSources] = useState<ScreenSource[] | null>(null);
  const [pick, setPick] = useState<string | null>(null);
  useEffect(() => { void native.screens.sources().then((s) => { setSources(s); if (s.length) setPick((s.find((x) => x.isScreen) ?? s[0]).id); }); }, []);
  const start = () => {
    if (!pick) return;
    core.settings.update((s) => ({ ...s, shareRes: res, shareFps: fps }));
    onDismiss();
    core.session.announceShare(res, fps, (r) => {
      if (r.ok) {
        void core.screen.start(pick, audio, res, fps).then((err) => { if (err) { core.toast(err); core.session.announceStopShare(); } else onStarted(); });
      } else core.toast(String(r.error ?? "Could not start sharing"));
    });
  };
  const screens = (sources ?? []).filter((s) => s.isScreen), windows = (sources ?? []).filter((s) => !s.isScreen);
  const tile = (s: ScreenSource) => (
    <button key={s.id} onClick={() => setPick(s.id)} style={{ width: 168, textAlign: "left", borderRadius: 4, padding: 6, background: pick === s.id ? "var(--accent-30)" : "var(--surface2)", boxShadow: `inset 0 0 0 ${pick === s.id ? 2 : 1}px ${pick === s.id ? "var(--neon)" : "var(--border)"}` }}>
      <img src={s.thumb} alt="" style={{ width: "100%", aspectRatio: "16/10", objectFit: "cover", borderRadius: 2, background: "#000", display: "block" }} />
      <div className="hstack g6" style={{ marginTop: 6 }}>{s.appIcon && <img src={s.appIcon} alt="" style={{ width: 14, height: 14 }} />}<span className="ellipsis" style={{ fontSize: 12 }}>{s.name}</span></div>
    </button>
  );
  return (
    <Sheet title="Share your screen" center onDismiss={onDismiss}>
      <div style={{ padding: "0 20px", maxHeight: "72vh", overflowY: "auto" }}>
        <Muted>Everyone in the room can watch. Your screen is sent directly to each person, so lower the quality in big rooms.</Muted>
        <div style={{ height: 14 }} />
        {sources == null ? <div className="hstack g10"><Spinner size={18} /><Muted>Looking for screens…</Muted></div> : <>
          <Tiny>Screens</Tiny><div style={{ height: 8 }} />
          <div className="hstack" style={{ flexWrap: "wrap", gap: 10 }}>{screens.map(tile)}</div>
          {windows.length > 0 && <><div style={{ height: 14 }} /><Tiny>Windows</Tiny><div style={{ height: 8 }} /><div className="hstack" style={{ flexWrap: "wrap", gap: 10 }}>{windows.map(tile)}</div></>}
        </>}
        <div style={{ height: 14 }} /><Tiny>Resolution</Tiny><div style={{ height: 8 }} />
        <Segmented options={[["240", "240p"], ["480", "480p"], ["720", "720p"], ["1080", "1080p"]]} selected={String(res)} onSelect={(v) => setRes(+v)} />
        <div style={{ height: 14 }} /><Tiny>Frame rate</Tiny><div style={{ height: 8 }} />
        <Segmented options={[["5", "5"], ["15", "15"], ["30", "30"], ["60", "60"]]} selected={String(fps)} onSelect={(v) => setFps(+v)} />
        <SwitchRow title="Share system sound" subtitle="Viewers hear what plays on this computer, plus your microphone." checked={audio} onChange={setAudio} />
        <div style={{ height: 10 }} />
        <Button block icon="monitor" text="Start sharing" disabled={!pick} onClick={start} />
        <div style={{ height: 16 }} />
      </div>
    </Sheet>
  );
}
