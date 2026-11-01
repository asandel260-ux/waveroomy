import { useEffect, useRef, useState } from "react";
import { core } from "../../app/instance";
import { CHESS_MARK } from "../../chess/game";
import { ChatMsg, Participant, REACTIONS, canSpeak } from "../../core/models";
import { fmtClock } from "../../core/util";
import { useFlow, useTick } from "../hooks";
import { Avatar, Button, Chip, ConfirmDialog, Dialog, EmptyState, Field, Icon, IconButton, Muted, RichText, Row2, Sheet, SwitchRow, Tiny } from "../components/kit";
import type { Nav } from "../nav";
import { ChessChallengeCard } from "../panels/Chess";

/* ============================== Chat (docked) ============================== */

export function ChatPanel({ nav, onOpenChess, onClose }: { nav: Nav; onOpenChess: () => void; onClose: () => void }) {
  const session = core.session;
  useTick(session.tick);
  const blocked = useFlow(core.social.blocked);
  const msgs = session.room ? [...session.room.chat] : [];
  const [text, setText] = useState("");
  const [actionFor, setActionFor] = useState<ChatMsg | null>(null);
  const [reportMsg, setReportMsg] = useState<ChatMsg | null>(null);
  const end = useRef<HTMLDivElement>(null);
  useEffect(() => { end.current?.scrollIntoView({ block: "end" }); session.unreadChat.value = 0; }, [msgs[msgs.length - 1]?.id, msgs.length]);
  const send = () => { const t = text.trim(); if (!t) return; session.sendChat(t); setText(""); };
  return (
    <div className="chatpanel">
      <div className="hstack" style={{ padding: "8px 6px 8px 16px" }}>
        <div className="display grow" style={{ fontSize: 18 }}>Room chat</div>
        <IconButton icon="x" size={36} iconSize={18} onClick={onClose} title="Close" />
      </div>
      <div className="scroll" style={{ padding: "4px 16px" }}>
        {msgs.length === 0 ? <EmptyState icon="message" title="No messages yet" text="Say hi 👋" /> : (
          <div className="stack g12">
            {msgs.map((m) => {
              const mine = m.uid === session.myUid;
              const hidden = blocked.includes(m.uid) && !mine;
              const chess = m.text.startsWith(CHESS_MARK);
              return (
                <div key={m.id} className="hstack" style={{ alignItems: "flex-start", gap: 10, cursor: !m.deleted && !hidden && !chess ? "pointer" : "default" }} onClick={() => { if (!m.deleted && !hidden && !chess) setActionFor(m); }}>
                  <Avatar avatar={m.avatar} size={34} uid={m.uid} mine={mine} />
                  <div className="grow" style={{ minWidth: 0 }}>
                    <div className="hstack g8"><span style={{ fontSize: 13, fontWeight: 600, color: mine ? "var(--accent-bright)" : "var(--text)" }}>{mine ? "You" : m.displayName}</span><Tiny>{fmtClock(m.at)}</Tiny></div>
                    {m.deleted ? <div style={{ color: "var(--faint)", fontSize: 13, fontStyle: "italic" }}>message removed</div>
                      : hidden ? <div style={{ color: "var(--faint)", fontSize: 13, fontStyle: "italic" }}>message from a blocked person</div>
                      : chess ? (() => { const rest = m.text.slice(CHESS_MARK.length); const i = rest.indexOf("\u001F"); return <ChessChallengeCard gameId={i < 0 ? rest : rest.slice(0, i)} text={i < 0 ? "" : rest.slice(i + 1)} onOpen={onOpenChess} />; })()
                      : <RichText text={m.text} color="var(--text)" linkColor="var(--accent-bright)" />}
                    {!m.deleted && Object.keys(m.reactions).length > 0 && (
                      <div className="hstack" style={{ flexWrap: "wrap", gap: 6, marginTop: 4 }}>
                        {Object.entries(m.reactions).map(([e, who]) => <Chip key={e} text={`${e} ${who.length}`} selected={who.includes(session.myUid)} onClick={() => session.reactChat(m.id, e)} />)}
                      </div>
                    )}
                  </div>
                </div>
              );
            })}
            <div ref={end} />
          </div>
        )}
      </div>
      <div className="hstack g8" style={{ padding: 12 }}>
        <div className="grow"><Field placeholder="Message the room" value={text} onChange={setText} maxLength={500} onEnter={send} /></div>
        <IconButton icon="send" bg="var(--accent)" color="#fff" disabled={!text.trim()} onClick={send} title="Send" />
      </div>

      {actionFor && (
        <Sheet center onDismiss={() => setActionFor(null)}>
          <div className="hstack" style={{ flexWrap: "wrap", gap: 8, padding: "0 16px 8px" }}>
            {REACTIONS.map((e) => <button key={e} style={{ width: 46, height: 46, borderRadius: "50%", background: "var(--surface2)", fontSize: 22 }} onClick={() => { session.reactChat(actionFor.id, e); setActionFor(null); }}>{e}</button>)}
          </div>
          {actionFor.uid !== session.myUid && <>
            <Row2 title="Message privately" icon="message" onClick={() => { const a = actionFor; setActionFor(null); nav.push({ t: "chat", uid: a.uid }); }} />
            <Row2 title="Report message" icon="flag" onClick={() => { setReportMsg(actionFor); setActionFor(null); }} />
          </>}
          {(actionFor.uid === session.myUid || session.isHost) && <Row2 title="Delete message" icon="trash" danger onClick={() => { session.deleteChat(actionFor.id); setActionFor(null); }} />}
        </Sheet>
      )}
      {reportMsg && <ReportDialog type="message" id={reportMsg.id} label={"@" + reportMsg.username} excerpt={reportMsg.text} onDismiss={() => setReportMsg(null)} />}
    </div>
  );
}

/* ============================== Person ============================== */

export function PersonSheet({ p, nav, onDismiss }: { p: Participant; nav: Nav; onDismiss: () => void }) {
  const session = core.session;
  const me = session.me;
  const mine = p.sid === session.mySid;
  const following = useFlow(core.social.following);
  const blocked = useFlow(core.social.blocked);
  const connected = useFlow(core.connected);
  const [vol, setVol] = useState(core.audio.userVolume(p.uid));
  const [banDialog, setBanDialog] = useState(false);
  const [kickConfirm, setKickConfirm] = useState(false);
  const [report, setReport] = useState(false);
  const host = me?.role === "host";
  return (
    <>
      <Sheet center onDismiss={onDismiss}>
        <div className="hstack g14" style={{ padding: "0 20px 8px" }}>
          <Avatar avatar={p.avatar} size={56} uid={p.uid} mine={mine} />
          <div className="grow"><div style={{ fontSize: 18, fontWeight: 600 }}>{p.displayName}{mine ? " (you)" : ""}</div><Muted>@{p.username} · {p.role === "host" ? "host" : "member"}{!mine && !connected.has(p.uid) ? " · connecting…" : ""}</Muted></div>
        </div>
        {!mine && canSpeak(p.role) && (
          <div style={{ padding: "4px 20px" }}>
            <Tiny>Their volume</Tiny>
            <input type="range" min={0} max={2} step={0.02} value={vol} style={{ width: "100%", accentColor: "var(--accent-bright)" }} onChange={(e) => { const v = parseFloat(e.target.value); setVol(v); core.audio.setUserVolume(p.uid, v); }} />
          </div>
        )}
        <Row2 title="View profile" icon="user" onClick={() => { onDismiss(); nav.push({ t: "profile", uid: p.uid }); }} />
        {!mine && <>
          <Row2 title="Message" subtitle={connected.has(p.uid) ? "Delivered instantly" : "Queued until you're connected"} icon="message" onClick={() => { onDismiss(); nav.push({ t: "chat", uid: p.uid }); }} />
          <Row2 title={following.includes(p.uid) ? "Unfollow" : "Follow"} icon="heart" onClick={() => { core.follow(p.uid, !following.includes(p.uid)); onDismiss(); }} />
        </>}
        {host && !mine && <>
          {!p.muted && <Row2 title="Mute microphone" icon="mic-off" onClick={() => { session.muteUser(p.sid); onDismiss(); }} />}
          <Row2 title="Remove from room" icon="log-out" danger onClick={() => setKickConfirm(true)} />
          <Row2 title="Ban…" icon="ban" danger onClick={() => setBanDialog(true)} />
        </>}
        {!mine && <>
          <Row2 title="Report" icon="flag" onClick={() => setReport(true)} />
          <Row2 title={blocked.includes(p.uid) ? "Unblock" : "Block"} subtitle="Blocked people can't connect to you" icon="ban" danger onClick={() => { core.block(p.uid, !blocked.includes(p.uid)); onDismiss(); }} />
        </>}
      </Sheet>
      {kickConfirm && <ConfirmDialog danger title={`Remove ${p.displayName}?`} text="They can rejoin with a new invite unless you ban them." confirm="Remove" onDismiss={() => setKickConfirm(false)} onConfirm={() => { session.kick(p.sid); onDismiss(); }} />}
      {banDialog && (
        <Dialog title={`Ban ${p.displayName}`} onDismiss={() => setBanDialog(false)} actions={<Button kind="ghost" text="Cancel" onClick={() => setBanDialog(false)} />}>
          <Muted>They are removed now and can't rejoin this room for the chosen time.</Muted>
          <div className="stack g6" style={{ marginTop: 12 }}>
            <Button kind="danger" text="1 hour" onClick={() => { session.ban(p.sid, 3_600_000); setBanDialog(false); onDismiss(); }} />
            <Button kind="danger" text="24 hours" onClick={() => { session.ban(p.sid, 86_400_000); setBanDialog(false); onDismiss(); }} />
            <Button kind="danger" text="Permanently" onClick={() => { session.ban(p.sid, 0); setBanDialog(false); onDismiss(); }} />
          </div>
        </Dialog>
      )}
      {report && <ReportDialog type="user" id={p.uid} label={"@" + p.username} excerpt="" onDismiss={() => setReport(false)} />}
    </>
  );
}

/* ============================== Report ============================== */

const REPORT_CATEGORIES: [string, string][] = [["spam", "Spam"], ["harassment", "Harassment"], ["hate", "Hate"], ["sexual", "Sexual content"], ["violence", "Violence"], ["other", "Other"]];

export function ReportDialog({ type, id, label, excerpt, onDismiss }: { type: string; id: string; label: string; excerpt: string; onDismiss: () => void }) {
  const [cat, setCat] = useState("spam");
  const [details, setDetails] = useState("");
  return (
    <Dialog title={`Report ${label}`} onDismiss={onDismiss} actions={<>
      <Button kind="ghost" text="Cancel" onClick={onDismiss} />
      <Button text="Send report" onClick={() => { core.session.fileReport(type, id, label, cat, details, excerpt, (ok) => core.toast(ok ? "Report sent to the host" : "Could not send the report")); onDismiss(); }} />
    </>}>
      <Muted size={12}>Goes to the room's host. There is no central moderator — the host decides.</Muted>
      <div className="hstack" style={{ flexWrap: "wrap", gap: 6, margin: "10px 0" }}>{REPORT_CATEGORIES.map(([k, v]) => <Chip key={k} text={v} selected={cat === k} onClick={() => setCat(k)} />)}</div>
      <Field multiline rows={4} placeholder="Details (optional)" value={details} onChange={setDetails} maxLength={1000} />
    </Dialog>
  );
}

/* ============================== Room settings (host) ============================== */

export function RoomSettingsSheet({ onDismiss }: { onDismiss: () => void }) {
  const session = core.session;
  useTick(session.tick);
  const contacts = useFlow(core.social.contacts);
  const s = session.room;
  const [title, setTitle] = useState(s?.meta.title ?? "");
  const [topic, setTopic] = useState(s?.meta.topic ?? "");
  const [tags, setTags] = useState((s?.meta.tags ?? []).join(", "));
  const [error, setError] = useState<string | null>(null);
  const [endConfirm, setEndConfirm] = useState(false);
  if (!s) return null;
  const bans = Object.entries(s.meta.bans);
  return (
    <>
      <Sheet title="Room settings" center onDismiss={onDismiss}>
        <div style={{ padding: "0 20px", maxHeight: "70vh", overflowY: "auto" }}>
          <Field label="Title" value={title} onChange={setTitle} maxLength={60} />
          <div style={{ height: 12 }} />
          <Field label="Topic" multiline rows={3} value={topic} onChange={setTopic} maxLength={140} />
          <div style={{ height: 12 }} />
          <Field label="Tags" value={tags} onChange={setTags} />
          {error && <div style={{ color: "var(--danger)", fontSize: 13, marginTop: 8 }}>{error}</div>}
          <div style={{ height: 12 }} />
          <Button block small kind="secondary" text="Save changes" onClick={() => session.editRoom(title, topic, tags.split(",").map((t) => t.trim()).filter(Boolean), (r) => { if (r.ok) { setError(null); core.toast("Saved"); } else setError(String(r.error ?? "")); })} />
          <div style={{ height: 10 }} />
          <SwitchRow title="Lock room" subtitle="Nobody new can join while locked." checked={s.meta.locked} onChange={(v) => session.setLocked(v)} />
          {bans.length > 0 && <>
            <div style={{ height: 10 }} /><Tiny>Banned</Tiny>
            {bans.map(([uid, until]) => (
              <div key={uid} className="hstack" style={{ padding: "6px 0" }}>
                <div className="grow"><div style={{ fontSize: 14 }}>{contacts[uid]?.displayName ?? uid.slice(0, 10)}</div><Muted size={12}>{until === 0 ? "permanent" : "until " + new Date(until).toLocaleString(undefined, { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" })}</Muted></div>
                <Button small kind="secondary" text="Unban" onClick={() => session.unban(uid)} />
              </div>
            ))}
          </>}
          <div style={{ height: 18 }} />
          <Button block kind="secondary" icon="eraser" text="Clear blackboard" onClick={() => session.boardClear()} />
          <div style={{ height: 10 }} />
          <Button block kind="danger" icon="log-out" text="End room for everyone" onClick={() => setEndConfirm(true)} />
          <div style={{ height: 20 }} />
        </div>
      </Sheet>
      {endConfirm && <ConfirmDialog danger title="End this room?" text="Everyone is disconnected. The chat and blackboard stay saved on this computer." confirm="End room" onDismiss={() => setEndConfirm(false)} onConfirm={() => { onDismiss(); session.endRoom(); }} />}
    </>
  );
}
