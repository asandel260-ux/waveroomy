import { useEffect, useRef, useState, type ReactNode } from "react";
import { core } from "../../app/instance";
import { REACTIONS, canSpeak, type Participant } from "../../core/models";
import { useEmitter, useFlow, useTick } from "../hooks";
import { Avatar, Backdrop, Button, ConfirmDialog, Icon, IconButton, Menu, MenuEntry, Muted, NeonWave, Sheet, Tiny, Chip, Spinner } from "../components/kit";
import { VoiceFxPanel } from "./Settings";
import { ChatPanel, PersonSheet, ReportDialog, RoomSettingsSheet } from "./RoomPanels";
import { StreamPanel, ShareSheet } from "../panels/Stream";
import { WatchPanel } from "../panels/Watch";
import { BoardPanel } from "../panels/Board";
import { BrowsePanel } from "../panels/Browse";
import { ChessCreateSheet, ChessPanel } from "../panels/Chess";
import type { ScreenProps } from "./registry";

interface Floating { id: number; emoji: string; name: string; x: number }

function TabChip({ label, icon, on, live, onClick }: { label: string; icon: string; on: boolean; live: boolean; onClick: () => void }) {
  return (
    <button className="hstack g8" onClick={onClick} aria-pressed={on} style={{ borderRadius: 999, padding: "10px 18px", flex: "none", background: on ? "var(--accent-soft)" : "transparent", boxShadow: `inset 0 0 0 1px ${on ? "var(--accent)" : "var(--border)"}`, transition: "background .12s" }}>
      <Icon name={icon} size={17} color={on ? "var(--accent-bright)" : "var(--muted)"} />
      <span style={{ color: on ? "var(--text)" : "var(--muted)", fontSize: 14, fontWeight: 500 }}>{label}</span>
      {live && <span role="img" aria-label="active" style={{ width: 7, height: 7, borderRadius: "50%", background: "var(--live)" }} />}
    </button>
  );
}

function CtlButton({ icon, onClick, on, danger, badge = 0, title }: { icon: string; onClick: () => void; on?: boolean; danger?: boolean; badge?: number; title: string }) {
  return (
    <button onClick={onClick} title={title} aria-label={title} aria-pressed={on} style={{ position: "relative", width: 44, height: 44, borderRadius: 6, display: "grid", placeItems: "center", flex: "none",
      background: danger ? "var(--danger-soft)" : on ? "var(--accent)" : "transparent",
      boxShadow: on || danger ? "none" : "inset 0 0 0 1px var(--border-strong)" }}>
      <Icon name={icon} size={20} color={danger ? "var(--danger)" : on ? "var(--on-accent)" : "var(--text)"} />
      {badge > 0 && <span style={{ position: "absolute", top: 3, right: 3, width: 17, height: 17, borderRadius: 10, background: "var(--accent)", color: "var(--on-accent)", fontSize: 9, fontWeight: 700, display: "grid", placeItems: "center" }}>{badge > 9 ? "9+" : badge}</span>}
    </button>
  );
}

function MicButton({ muted, ptt }: { muted: boolean; ptt: boolean }) {
  const session = core.session;
  const level = useFlow(core.audio.level);
  const ring = Math.min(1, level * 6);
  const unmute = () => { core.requestMic(); session.setMuted(false); };
  const d = 72;
  const handlers = ptt
    ? { onMouseDown: unmute, onMouseUp: () => session.setMuted(true), onMouseLeave: () => { if (!session.me?.muted) session.setMuted(true); } }
    : { onClick: () => (muted ? unmute() : session.setMuted(true)) };
  return (
    <div style={{ display: "flex", flexDirection: "column", alignItems: "stretch", width: d, flex: "none", gap: 6 }}>
      <button {...handlers} aria-label={ptt ? "Hold to talk" : muted ? "Unmute microphone" : "Mute microphone"} aria-pressed={!muted} title={ptt ? "Hold to talk" : muted ? "Unmute" : "Mute"} style={{ width: d, height: d, borderRadius: 10, display: "grid", placeItems: "center",
        background: muted ? "var(--surface2)" : "var(--live)", boxShadow: muted ? "inset 0 0 0 1px var(--border-strong)" : "0 0 18px color-mix(in srgb, var(--live) 45%, transparent)", transition: "background .12s, box-shadow .12s" }}>
        <Icon name={muted ? "mic-off" : "mic"} size={30} color={muted ? "var(--text)" : "var(--on-live)"} />
      </button>
      <div style={{ height: 3, background: "var(--border)" }}><div style={{ height: "100%", width: muted ? 0 : `${Math.round(ring * 100)}%`, background: "var(--live)", transition: "width .08s linear" }} /></div>
    </div>
  );
}

/** Fills the stage with equal rectangles. The column count is whichever fits the space best, so one person gets the whole page and more people shrink the tiles evenly. */
function TileGrid({ children }: { children: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  const n = Array.isArray(children) ? children.flat().length : 1;
  const [box, setBox] = useState({ w: 0, h: 0 });
  useEffect(() => {
    const el = ref.current; if (!el) return;
    const ro = new ResizeObserver(() => setBox({ w: el.clientWidth, h: el.clientHeight }));
    ro.observe(el); setBox({ w: el.clientWidth, h: el.clientHeight });
    return () => ro.disconnect();
  }, []);
  const gap = 6;
  let best = 1, bestScore = Infinity;
  for (let c = 1; c <= Math.max(1, n); c++) {
    const rows = Math.ceil(n / c);
    const cw = (box.w - gap * (c - 1)) / c, ch = (box.h - gap * (rows - 1)) / rows;
    if (cw <= 0 || ch <= 0) continue;
    const score = Math.abs(Math.log(cw / ch / 1.6)) + (rows * c - n) * 0.08;
    if (score < bestScore) { bestScore = score; best = c; }
  }
  const rows = Math.ceil(n / best);
  const lastCount = n - best * (rows - 1);
  const items = (Array.isArray(children) ? children.flat() : [children]) as ReactNode[];
  // Two grid columns per tile lets a short last row sit centred instead of leaving a hole at the end.
  return (
    <div ref={ref} style={{ flex: 1, minHeight: 0, display: "grid", gap, gridTemplateColumns: `repeat(${best * 2}, minmax(0, 1fr))`, gridTemplateRows: `repeat(${rows}, minmax(0, 1fr))` }}>
      {items.map((c, i) => {
        const inLast = i >= best * (rows - 1);
        const col = inLast ? (best - lastCount) + (i - best * (rows - 1)) * 2 + 1 : (i % best) * 2 + 1;
        return <div key={i} style={{ gridColumn: `${col} / span 2`, gridRow: Math.floor(i / best) + 1, display: "grid", minWidth: 0, minHeight: 0 }}>{c}</div>;
      })}
    </div>
  );
}

function StageTile({ p, speaking, online, alone, onClick }: { p: Participant; speaking: boolean; online: boolean; alone: boolean; onClick: () => void }) {
  const mine = p.sid === core.session.mySid;
  const ref = useRef<HTMLButtonElement>(null);
  const [d, setD] = useState(96);
  useEffect(() => {
    const el = ref.current; if (!el) return;
    const ro = new ResizeObserver(() => setD(Math.max(48, Math.min(160, Math.min(el.clientWidth, el.clientHeight) * 0.42))));
    ro.observe(el); return () => ro.disconnect();
  }, []);
  return (
    <button ref={ref} onClick={onClick} aria-label={`${mine ? "You" : p.displayName}${p.muted ? ", muted" : ""}${speaking ? ", speaking" : ""}`}
      style={{ position: "relative", minWidth: 0, minHeight: 0, borderRadius: 10, overflow: "hidden", display: "grid", placeItems: "center",
        background: `color-mix(in srgb, hsl(${p.avatar.hue} 55% 50%) 14%, var(--surface2))`,
        boxShadow: speaking ? "inset 0 0 0 3px var(--live), 0 0 16px color-mix(in srgb, var(--live) 35%, transparent)" : "inset 0 0 0 1px var(--border)", transition: "box-shadow .15s" }}>
      <div style={{ opacity: online ? 1 : 0.55, display: "flex", flexDirection: "column", alignItems: "center", gap: 10 }}>
        <Avatar avatar={p.avatar} size={d} uid={p.uid} mine={mine} name={p.displayName} />
      </div>
      {!online && <span style={{ position: "absolute", top: 10, left: 10 }}><Spinner size={20} /></span>}
      {p.sharing && <span style={{ position: "absolute", top: 8, right: 8, width: 26, height: 26, borderRadius: 6, background: "var(--accent)", display: "grid", placeItems: "center" }}><Icon name="monitor" size={14} color="var(--on-accent)" /></span>}
      <div className="hstack g6" style={{ position: "absolute", left: 8, bottom: 8, maxWidth: "calc(100% - 52px)", borderRadius: 6, padding: "4px 10px", background: "var(--surface)", color: "var(--text)", fontSize: 13, fontWeight: 500 }}>
        {p.role === "host" && <Icon name="crown" size={12} color="var(--warn)" />}
        <span className="ellipsis">{mine ? "You" : p.displayName}</span>
      </div>
      {p.muted && <span style={{ position: "absolute", right: 8, bottom: 8, width: 28, height: 28, borderRadius: "50%", background: "var(--surface)", boxShadow: "inset 0 0 0 1px var(--danger)", display: "grid", placeItems: "center" }}><Icon name="mic-off" size={15} color="var(--danger)" /></span>}
    </button>
  );
}

function FloatingReaction({ f, onDone }: { f: Floating; onDone: () => void }) {
  useEffect(() => { const t = setTimeout(onDone, 2300); return () => clearTimeout(t); }, []);
  return <div className="floatup" style={{ position: "absolute", left: `${f.x * 100}%`, bottom: "8%", textAlign: "center", pointerEvents: "none" }}><div style={{ fontSize: 34 }}>{f.emoji}</div><div style={{ color: "var(--muted)", fontSize: 10 }}>{f.name}</div></div>;
}

export function RoomScreen({ nav }: ScreenProps) {
  const session = core.session;
  useTick(session.tick);
  const phase = useFlow(session.phase);
  const speaking = useFlow(session.speaking);
  const connected = useFlow(core.connected);
  const unread = useFlow(session.unreadChat);
  const deaf = useFlow(session.deafened);
  const settings = useFlow(core.settings.state);
  const sharing = useFlow(core.screen.active);
  const s = session.room;
  const me = session.me;

  const [mode, setMode] = useState("stage");
  const [chatOpen, setChatOpen] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [fxOpen, setFxOpen] = useState(false);
  const [reactOpen, setReactOpen] = useState(false);
  const [menu, setMenu] = useState<{ x: number; y: number } | null>(null);
  const [leaveConfirm, setLeaveConfirm] = useState(false);
  const [person, setPerson] = useState<string | null>(null);
  const [shareSheet, setShareSheet] = useState(false);
  const [reportRoom, setReportRoom] = useState(false);
  const [chessCreate, setChessCreate] = useState(false);
  const [chessFocus, setChessFocus] = useState(false);
  const [floats, setFloats] = useState<Floating[]>([]);
  const fid = useRef(0);

  useEmitter(session.reactions, (r) => setFloats((f) => [...f.slice(-11), { id: ++fid.current, emoji: r.emoji, name: r.name, x: 0.15 + Math.random() * 0.7 }]));
  useEffect(() => {
    if (!chatOpen) return;
    session.unreadChat.value = 0;
  }, [chatOpen, unread]);

  // push to talk from the keyboard
  useEffect(() => {
    if (!settings.ptt) return;
    const typing = () => { const a = document.activeElement; return !!a && (a.tagName === "INPUT" || a.tagName === "TEXTAREA"); };
    const down = (e: KeyboardEvent) => { if (e.code === settings.pttKey && !e.repeat && !typing()) { e.preventDefault(); core.requestMic(); session.setMuted(false); } };
    const up = (e: KeyboardEvent) => { if (e.code === settings.pttKey && !typing()) session.setMuted(true); };
    window.addEventListener("keydown", down); window.addEventListener("keyup", up);
    return () => { window.removeEventListener("keydown", down); window.removeEventListener("keyup", up); };
  }, [settings.ptt, settings.pttKey]);

  const participants = s ? [...s.participants.values()] : [];
  const speakers = participants.filter((p) => canSpeak(p.role)).sort((a, b) => a.joinedAt - b.joinedAt);
  const sharer = participants.find((p) => p.sharing != null);
  const watching = s?.watch.video != null;
  const browsing = s?.browse.url != null;
  const isHost = me?.role === "host";
  const canTalk = !!me && canSpeak(me.role);

  useEffect(() => { if (mode === "screen" && !sharer) setMode("stage"); }, [sharer?.sid, mode]);
  useEffect(() => {
    if (mode !== "browse") return;
    session.browsePresence(true);
    return () => session.browsePresence(false);
  }, [mode]);

  const exitToStage = () => { setMode("stage"); setChessFocus(false); };
  useEffect(() => {
    const k = (e: KeyboardEvent) => { if (e.key === "Escape" && phase !== "idle" && !document.querySelector(".dialog, .sheet, .menu")) { if (phase === "joining") session.leave(); else if (mode !== "stage") exitToStage(); } };
    window.addEventListener("keydown", k); return () => window.removeEventListener("keydown", k);
  }, [phase, mode]);

  if (!s || phase === "idle") return <Backdrop style={1} />;
  if (phase === "joining") {
    return (
      <Backdrop style={1}>
        <div style={{ flex: 1, display: "flex", flexDirection: "column", justifyContent: "center", padding: "0 64px", maxWidth: 720 }}>
          <Tiny>Connecting</Tiny>
          <div className="display" style={{ fontSize: 56, margin: "12px 0 16px", letterSpacing: "-.045em" }}>Joining room…</div>
          <Muted>Setting up direct connections with everyone in the room.</Muted>
          <div style={{ marginTop: 32, height: 2, background: "var(--border)", maxWidth: 360, overflow: "hidden" }}><div style={{ height: "100%", width: "40%", background: "var(--accent)", animation: "indet 1.1s var(--ease) infinite" }} /></div>
          <div style={{ marginTop: 32 }}><Button small kind="secondary" text="Cancel" onClick={() => session.leave()} /></div>
        </div>
      </Backdrop>
    );
  }

  const live = participants.filter((p) => p.sid === session.mySid || connected.has(p.uid)).length;
  const iShare = sharer?.sid === session.mySid;
  let main: ReactNode;
  switch (mode) {
    case "screen": main = sharer ? <StreamPanel p={sharer} /> : null; break;
    case "watch": main = <WatchPanel />; break;
    case "board": main = <BoardPanel />; break;
    case "browse": main = <BrowsePanel />; break;
    case "chess": main = <ChessPanel land={false} focus={chessFocus} setFocus={setChessFocus} onExit={exitToStage} onCreate={() => setChessCreate(true)} />; break;
    default: main = (
      <div style={{ flex: 1, minHeight: 0, display: "flex", flexDirection: "column", padding: 6, gap: 6 }}>
        {sharer && (
          <button className="hstack g12" onClick={() => setMode("screen")} style={{ width: "100%", textAlign: "left", borderRadius: 10, background: "var(--surface)", boxShadow: "inset 0 0 0 1px var(--border)", padding: 12, flex: "none" }}>
            <Icon name="monitor" size={22} color="var(--accent-bright)" />
            <div className="grow"><div style={{ fontSize: 14, fontWeight: 500 }}>{iShare ? "You are" : sharer.displayName + " is"} sharing a screen</div><Muted size={12}>{sharer.sharing?.res}p · {sharer.sharing?.fps} fps</Muted></div>
            <Chip text={iShare ? "Preview" : "Watch"} selected />
          </button>
        )}
        <TileGrid>
          {speakers.map((p) => <StageTile key={p.sid} p={p} alone={speakers.length === 1} speaking={speaking.has(p.sid)} online={p.sid === session.mySid || connected.has(p.uid)} onClick={() => setPerson(p.sid)} />)}
        </TileGrid>
      </div>
    );
  }

  const menuEntries: MenuEntry[] = [
    ...(isHost ? [{ label: "Room settings", icon: "sliders", onClick: () => setSettingsOpen(true) }] : []),
    ...(canTalk ? [{ label: "Voice effects", icon: "waveform", onClick: () => setFxOpen(true) }] : []),
    { label: "Report this room", icon: "flag", onClick: () => setReportRoom(true) },
    { label: "Leave room", icon: "log-out", danger: true, onClick: () => setLeaveConfirm(true) }
  ];
  const hideChrome = mode === "chess" && chessFocus;

  return (
    <Backdrop style={1}>
      <div style={{ flex: 1, minHeight: 0, display: "flex" }}>
        <div style={{ flex: 1, minWidth: 0, display: "flex", flexDirection: "column", position: "relative" }}>
          {!hideChrome && <>
            <div style={{ display: "grid", gridTemplateColumns: "minmax(0, 1fr) auto minmax(0, 1fr)", alignItems: "center", gap: 12, padding: "10px 8px 10px 16px" }}>
              <div style={{ minWidth: 0 }}>
                <div className="hstack g6"><span className="display ellipsis" style={{ fontSize: 18 }}>{s.meta.title}</span>{s.meta.locked && <Icon name="lock" size={14} color="var(--warn)" />}</div>
                <Muted size={12}>{participants.length} in room{live < participants.length ? ` · connecting ${participants.length - live}` : ""}</Muted>
              </div>
              <div className="hstack g8" role="group" aria-label="Room views" style={{ overflowX: "auto", maxWidth: "100%" }}>
              <TabChip label="Stage" icon="users" on={mode === "stage"} live={false} onClick={() => setMode("stage")} />
              {sharer && <TabChip label="Screen" icon="monitor" on={mode === "screen"} live onClick={() => setMode("screen")} />}
              <TabChip label="Watch" icon="youtube" on={mode === "watch"} live={watching} onClick={() => setMode("watch")} />
              <TabChip label="Board" icon="board" on={mode === "board"} live={s.board.objects.size > 0} onClick={() => setMode("board")} />
              <TabChip label="Browser" icon="globe" on={mode === "browse"} live={browsing} onClick={() => setMode("browse")} />
              <TabChip label="Chess" icon="chess" on={mode === "chess"} live={s.chess.active != null} onClick={() => setMode("chess")} />
              </div>
              <div className="hstack g8" style={{ justifyContent: "flex-end" }}>
              {mode !== "stage" && <Button small kind="ghost" icon="arrow-left" text="Exit to stage" onClick={exitToStage} />}
              {mode === "screen" && iShare && <Button small kind="danger" text="Stop sharing" onClick={() => { core.screen.stop(true); setMode("stage"); }} />}
              {mode === "watch" && watching && session.canWatchControl() && <Button small kind="danger" text="End Watch" onClick={() => { session.watchEnd(); setMode("stage"); }} />}
              {mode === "browse" && browsing && session.canBrowseControl() && <Button small kind="danger" text="Close browser" onClick={() => { session.browseEnd(); setMode("stage"); }} />}
                <IconButton icon="user-plus" onClick={() => nav.push({ t: "invite", purpose: "room" })} title="Invite" />
                <IconButton icon="more-v" title="More" onClick={(e) => { const b = e.currentTarget.getBoundingClientRect(); setMenu({ x: b.right - 200, y: b.bottom }); }} />
              </div>
            </div>
            <div className="hairline" />
          </>}
          <div style={{ flex: 1, minHeight: 0, display: "flex", flexDirection: "column", position: "relative" }} key={mode}>
            {main}
            {floats.map((f) => <FloatingReaction key={f.id} f={f} onDone={() => setFloats((x) => x.filter((y) => y.id !== f.id))} />)}
          </div>
          <div style={{ display: "flex", justifyContent: "center", padding: "16px", borderTop: "1px solid var(--border)" }}>
            <div className="hstack" style={{ gap: 12, alignItems: "center" }}>
              <CtlButton icon={deaf ? "volume-x" : "headphones"} title={deaf ? "Undeafen" : "Deafen"} danger={deaf} onClick={() => session.toggleDeafen()} />
              <CtlButton icon="smile-plus" title="React" onClick={() => setReactOpen(true)} />
              <MicButton muted={me?.muted !== false} ptt={settings.ptt} />
              <CtlButton icon="monitor" title={sharing ? "Stop sharing" : "Share your screen"} on={sharing} onClick={() => { if (sharing) core.screen.stop(true); else setShareSheet(true); }} />
              <CtlButton icon="message" title="Room chat" on={chatOpen} badge={chatOpen ? 0 : unread} onClick={() => { setChatOpen(!chatOpen); session.unreadChat.value = 0; }} />
              <CtlButton icon="log-out" title="Leave" danger onClick={() => setLeaveConfirm(true)} />
            </div>
          </div>
        </div>
        {chatOpen && <ChatPanel nav={nav} onOpenChess={() => setMode("chess")} onClose={() => setChatOpen(false)} />}
      </div>

      {menu && <Menu at={menu} entries={menuEntries} onDismiss={() => setMenu(null)} />}
      {reactOpen && (
        <Sheet title="React" center onDismiss={() => setReactOpen(false)}>
          <div className="hstack" style={{ flexWrap: "wrap", gap: 10, padding: "0 16px 8px", justifyContent: "center" }}>
            {REACTIONS.map((e) => <button key={e} aria-label={`React with ${e}`} style={{ width: 56, height: 56, borderRadius: 6, background: "var(--surface2)", fontSize: 26 }} onClick={() => { session.react(e); setReactOpen(false); }}>{e}</button>)}
          </div>
        </Sheet>
      )}
      {chessCreate && <ChessCreateSheet onDismiss={() => setChessCreate(false)} onCreated={() => setMode("chess")} />}
      {settingsOpen && <RoomSettingsSheet onDismiss={() => setSettingsOpen(false)} />}
      {fxOpen && <Sheet title="Voice effects" center onDismiss={() => setFxOpen(false)}><div style={{ padding: "0 20px 16px" }}><VoiceFxPanel /></div></Sheet>}
      {shareSheet && <ShareSheet onDismiss={() => setShareSheet(false)} onStarted={() => setMode("screen")} />}
      {person && (() => { const p = s.participants.get(person); return p ? <PersonSheet p={p} nav={nav} onDismiss={() => setPerson(null)} /> : null; })()}
      {leaveConfirm && (isHost
        ? <ConfirmDialog title="Leave room?" text={participants.length > 1 ? "You can hand the room over by leaving. The longest-present member becomes host. Or end it for everyone from Room settings." : "You're the only one here. The room will be saved on this computer."} confirm="Leave" onDismiss={() => setLeaveConfirm(false)} onConfirm={() => session.leave()} />
        : <ConfirmDialog danger title="Leave room?" text="You can rejoin with a new invite." confirm="Leave" onDismiss={() => setLeaveConfirm(false)} onConfirm={() => session.leave()} />)}
      {reportRoom && <ReportDialog type="room" id={s.meta.id} label={s.meta.title} excerpt="" onDismiss={() => setReportRoom(false)} />}
    </Backdrop>
  );
}
