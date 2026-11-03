import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { core } from "../../app/instance";
import type { CodeResult } from "../../app/types";
import { unpack } from "../../net/sdp";
import { bool, int, obj, str, type J } from "../../core/util";
import { useFlow } from "../hooks";
import { Backdrop, Button, Field, HeroCard, Icon, Muted, ScreenHeader, Spinner, SwitchRow, Tiny } from "../components/kit";
import { QrDisplay, ScannerOverlay } from "../components/Qr";
import type { ScreenProps } from "./registry";

async function readClipboard(): Promise<string> {
  try { return await navigator.clipboard.readText(); } catch { return ""; }
}

function Step({ n, text }: { n: number; text: string }) {
  return (
    <div className="hstack g10">
      <div style={{ width: 26, height: 26, borderRadius: 6, display: "grid", placeItems: "center", background: "var(--accent)", boxShadow: "0 0 0 1px var(--border-strong)", color: "#fff", fontSize: 12, fontWeight: 700 }}>{n}</div>
      <span style={{ fontSize: 15, fontWeight: 500 }}>{text}</span>
    </div>
  );
}

function Column({ children }: { children: ReactNode }) {
  return <div className="scroll"><div style={{ maxWidth: 560, margin: "0 0 0 max(0px, calc((100% - 880px) / 2))", padding: "24px 32px", display: "flex", flexDirection: "column", alignItems: "center" }}>{children}</div></div>;
}

/** Plain-language view of what the last connection attempt found. Helps when two devices can't reach each other. */
export function ConnectionReport() {
  const r = useFlow(core.mesh.report);
  const [open, setOpen] = useState(false);
  if (!r.trim()) return null;
  return (
    <div style={{ width: "100%" }}>
      <button style={{ color: "var(--accent-bright)", fontSize: 12, padding: "6px 0" }} onClick={() => setOpen(!open)}>{open ? "Hide connection details" : "Connection details"}</button>
      {open && <div className="mono" style={{ fontSize: 12, color: "var(--muted)", background: "var(--surface)", borderRadius: 6, padding: 10, whiteSpace: "pre-wrap" }}>{r}</div>}
    </div>
  );
}

/* ============================== Create room ============================== */

export function CreateRoomScreen({ back }: ScreenProps) {
  const [title, setTitle] = useState("");
  const [topic, setTopic] = useState("");
  const [tags, setTags] = useState("");
  const [unlisted, setUnlisted] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const start = () => {
    const t = title.trim();
    if (t.length < 3) { setError("Give the room a title (3+ characters)."); return; }
    core.requestMic();
    const tg = [...new Set(tags.split(",").map((x) => x.trim().toLowerCase().slice(0, 20)).filter(Boolean))].slice(0, 6);
    if (!core.session.createRoom(t, topic, tg, unlisted, 24)) setError("Could not start the room.");
  };
  return (
    <Backdrop style={4}>
      <ScreenHeader title="New room" onBack={back} />
      <div className="scroll"><div style={{ maxWidth: 560, margin: "0 0 0 max(0px, calc((100% - 880px) / 2))", padding: "24px 32px" }}>
        <HeroCard>
          <div className="hstack" style={{ padding: 18, gap: 14 }}>
            <div style={{ width: 52, height: 52, borderRadius: 6, display: "grid", placeItems: "center", background: "var(--accent)", boxShadow: "0 0 0 1px var(--border-strong)" }}><Icon name="mic" size={24} color="#fff" /></div>
            <div><div className="display" style={{ fontSize: 20, color: "var(--text)" }}>Start talking</div><div style={{ color: "var(--muted)", fontSize: 13 }}>Name it, and invite people with a QR code.</div></div>
          </div>
        </HeroCard>
        <div style={{ height: 18 }} />
        <Field label="Title" placeholder="Room name" value={title} onChange={(v) => { setTitle(v); setError(null); }} maxLength={60} onEnter={start} autoFocus />
        <div style={{ height: 14 }} />
        <Field label="Topic (optional)" placeholder="What is this about?" value={topic} onChange={setTopic} multiline rows={3} maxLength={140} />
        <div style={{ height: 14 }} />
        <Field label="Tags (comma separated)" placeholder="Tags, separated by commas" value={tags} onChange={setTags} />
        <div style={{ height: 20 }} />
        <SwitchRow title="Unlisted" subtitle="Don't announce this room on the local network. People need your QR code." checked={unlisted} onChange={setUnlisted} />
        <Muted size={12}>You are the host. If you leave, hosting passes to the person who has been here longest. The room keeps going.</Muted>
        {error && <div style={{ color: "var(--danger)", fontSize: 13, marginTop: 10 }}>{error}</div>}
        <div style={{ height: 20 }} />
        <Button block icon="mic" text="Start room" onClick={start} />
      </div></div>
    </Backdrop>
  );
}

/* ============================== Invite (show my code) ============================== */

export function InviteScreen({ nav, route, back }: ScreenProps) {
  const purpose = route.t === "invite" ? route.purpose : "dm";
  const [code, setCode] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [scanning, setScanning] = useState(false);
  const [reply, setReply] = useState("");
  const [applying, setApplying] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const connected = useFlow(core.connected);
  const before = useRef(core.connected.value);
  const inviteId = useRef("");
  const roomTitle = core.session.room?.meta.title;
  const isGroup = purpose.startsWith("group:");

  useEffect(() => {
    let dead = false;
    core.createInviteCode(purpose).then(({ id, code: c }) => {
      inviteId.current = id;
      if (dead) { if (id) core.mesh.cancelInvite(id); return; }
      if (c == null) setFailed(true); else setCode(c);
    });
    return () => { dead = true; if (inviteId.current) core.mesh.cancelInvite(inviteId.current); };
  }, [purpose]);

  useEffect(() => {
    const added = [...connected].filter((u) => !before.current.has(u));
    if (added.length) {
      core.toast(`Connected to ${core.contactName(added[0])}`);
      if (purpose === "dm") { nav.pop(); nav.push({ t: "chat", uid: added[0] }); } else back();
    }
  }, [connected]);

  useEffect(() => {
    if (!applying) return;
    const t = setTimeout(() => { setApplying(false); setError("The connection didn't come up.\n\n" + core.mesh.report.value); }, 40_000);
    return () => clearTimeout(t);
  }, [applying]);

  const applyReply = async (text: string) => {
    setError(null);
    const res: CodeResult = await core.handleCode(text);
    if (res.kind === "applied") setApplying(true);
    else if (res.kind === "error") setError(res.msg);
    else setError("That is an invite, not a reply. Ask them to show their reply code.");
  };
  const gname = isGroup ? core.groups.get(purpose.slice(6))?.name ?? "" : "";

  return (
    <Backdrop style={4}>
      <ScreenHeader title={purpose === "room" ? "Invite to room" : isGroup ? "Add to group" : purpose === "sync" ? "Link another device" : "Add a friend"} onBack={back} subtitle={purpose === "room" ? roomTitle : isGroup ? gname : undefined} />
      <Column>
        {failed ? <Muted>Could not create an invite. Check that the microphone and network are available and try again.</Muted>
          : code == null ? <><div style={{ height: 80 }} /><Spinner size={28} /><div style={{ height: 12 }} /><Muted>Preparing a direct connection…</Muted></>
          : <>
            <Step n={1} text="They scan this code" />
            <div style={{ height: 4 }} />
            <Muted size={12} style={{ textAlign: "center" }}>On the same Wi-Fi that is all it takes. The link comes up by itself.</Muted>
            <div style={{ height: 10 }} />
            <QrDisplay code={code} />
            <div style={{ height: 12 }} />
            <div className="hstack g8">
              <Button small kind="secondary" icon="copy" text="Copy" onClick={() => { core.copy(code); core.toast("Code copied"); }} />
              <Button small kind="secondary" icon="share" text="Copy as message" onClick={() => { core.copy(isGroup ? `Join my HoMit group “${gname}”: ${code}` : `Join me on HoMit: ${code}`); core.toast("Message copied"); }} />
            </div>
            <div style={{ height: 24 }} />
            <Step n={2} text="Different networks? Scan the reply on their screen" />
            <div style={{ height: 10 }} />
            {applying ? <div className="hstack g10"><Spinner size={18} /><Muted>Connecting directly…</Muted></div> : <>
              <Button block icon="scan" text="Scan their reply" onClick={() => setScanning(true)} />
              <div style={{ height: 10 }} />
              <div className="hstack g8" style={{ width: "100%" }}>
                <div className="grow"><Field mono placeholder="…or paste their reply code" value={reply} onChange={setReply} /></div>
                <Button small kind="secondary" text="Go" onClick={async () => applyReply(reply.trim() || (await readClipboard()))} />
              </div>
            </>}
            {error && <div style={{ color: "var(--danger)", fontSize: 13, marginTop: 10, whiteSpace: "pre-wrap" }}>{error}</div>}
            <div style={{ height: 18 }} />
            <Muted size={12} style={{ textAlign: "center" }}>This code works once and expires in 10 minutes. It carries only connection details. Nothing about you beyond your display name.</Muted>
          </>}
      </Column>
      {scanning && <ScannerOverlay title="Scan their reply" hint="Point at the reply code shown on their screen" onCode={(c) => { setScanning(false); void applyReply(c); }} onClose={() => setScanning(false)} />}
    </Backdrop>
  );
}

/* ============================== Join (scan / paste their code) ============================== */

export function JoinScreen({ nav, route, back }: ScreenProps) {
  const initial = route.t === "join" ? route.code : "";
  const [text, setText] = useState(initial);
  const [scanning, setScanning] = useState(false);
  const [answer, setAnswer] = useState<Extract<CodeResult, { kind: "answer" }> | null>(null);
  const [waiting, setWaiting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [parsed, setParsed] = useState<J | null>(null);
  const connected = useFlow(core.connected);
  const before = useRef(core.connected.value);

  useEffect(() => {
    let live = true;
    if (!text.trim()) { setParsed(null); return; }
    unpack(text).then((p) => { if (live) setParsed(p); });
    return () => { live = false; };
  }, [text]);
  const isOffer = parsed ? str(parsed, "k") === "o" : false;
  const preview = parsed ? obj(parsed, "v") : null;

  const submit = async (raw: string) => {
    setError(null);
    core.requestMic();
    const res = await core.handleCode(raw);
    if (res.kind === "answer") { setAnswer(res); setWaiting(true); }
    else if (res.kind === "applied") setWaiting(true);
    else setError(res.msg);
  };

  useEffect(() => {
    if (!initial) return;
    unpack(initial).then((p) => { if (p && str(p, "k") === "a") void submit(initial); });
  }, []);
  useEffect(() => {
    const added = [...connected].filter((u) => !before.current.has(u));
    if (waiting && added.length) {
      core.toast(`Connected to ${core.contactName(added[0])}`);
      if (answer?.purpose === "dm") { nav.pop(); nav.push({ t: "chat", uid: added[0] }); }
      else if (answer?.purpose === "sync") back();
    }
  }, [connected]);
  useEffect(() => {
    if (!waiting) return;
    const t = setTimeout(() => {
      if (core.session.phase.value === "idle") { setWaiting(false); setAnswer(null); setError("The connection didn't come up.\n\n" + (core.mesh.report.value.trim() || "Ask for a fresh invite and try again.")); }
    }, 60_000);
    return () => clearTimeout(t);
  }, [waiting]);

  const a = answer;
  return (
    <Backdrop style={4}>
      <ScreenHeader title="Join with a code" onBack={back} />
      <Column>
        {a && a.auto ? <>
          <div style={{ height: 80 }} /><Spinner size={28} /><div style={{ height: 12 }} />
          <div style={{ fontSize: 16, fontWeight: 500 }}>Connecting directly…</div>
          <Muted style={{ textAlign: "center" }}>You're on the same Wi-Fi, so no reply code is needed.</Muted>
          <div style={{ height: 16 }} /><ConnectionReport />
        </> : a ? <>
          <Step n={2} text={a.purpose === "room" ? "Show this reply to the host" : "Show this reply to them"} />
          <div style={{ height: 6 }} />
          <Muted style={{ textAlign: "center" }}>Have them click “Scan their reply”. The link comes up by itself.</Muted>
          <div style={{ height: 14 }} />
          <QrDisplay code={a.code} />
          <div style={{ height: 12 }} />
          <div className="hstack g8"><Button small kind="secondary" icon="copy" text="Copy" onClick={() => { core.copy(a.code); core.toast("Reply copied"); }} /></div>
          <div style={{ height: 20 }} />
          <div className="hstack g10"><Spinner size={18} /><Muted>Waiting for the link…</Muted></div>
          <div style={{ height: 10 }} /><ConnectionReport /><div style={{ height: 10 }} />
          <Button small kind="ghost" text="Start over" onClick={() => { setAnswer(null); setWaiting(false); setText(""); }} />
        </> : waiting ? <><div style={{ height: 80 }} /><Spinner size={28} /><div style={{ height: 12 }} /><Muted>Connecting directly…</Muted></> : <>
          <Step n={1} text="Scan their code" />
          <div style={{ height: 10 }} />
          <Button block icon="scan" text="Scan QR code" onClick={() => setScanning(true)} />
          <div style={{ height: 16 }} />
          <Tiny>Or paste the code</Tiny>
          <div style={{ height: 8 }} />
          <div style={{ width: "100%" }}><Field mono multiline rows={4} placeholder="WR1.…" value={text} onChange={setText} /></div>
          <div style={{ height: 8 }} />
          <div className="hstack g8"><Button small kind="secondary" icon="copy" text="Paste" onClick={async () => setText(await readClipboard())} /></div>
          {isOffer && preview && (
            <div style={{ width: "100%", marginTop: 16, borderRadius: 10, background: "var(--surface)", boxShadow: "inset 0 0 0 1px var(--border)", padding: 16 }}>
              <Tiny>{str(parsed!, "p") === "dm" ? "Friend invite" : str(parsed!, "p") === "sync" ? "Your other device" : "Room"}</Tiny>
              <div style={{ fontSize: 18, fontWeight: 600, marginTop: 4 }}>{str(preview, "t") || "Someone"}</div>
              {str(parsed!, "p") !== "dm" && str(parsed!, "p") !== "sync" && <Muted>Hosted by {str(preview, "h")} · {int(preview, "n")} here{bool(preview, "l") ? " · locked" : ""}</Muted>}
            </div>
          )}
          <div style={{ height: 16 }} />
          <Button block text={isOffer && str(parsed!, "p") !== "dm" ? "Join room" : "Continue"} disabled={!text.trim()} onClick={() => void submit(text)} />
          {error && <div style={{ color: "var(--danger)", fontSize: 13, marginTop: 10, whiteSpace: "pre-wrap" }}>{error}</div>}
        </>}
      </Column>
      {scanning && <ScannerOverlay title="Scan a code" hint="Point at the QR code on their screen" onCode={(c) => { setScanning(false); setText(c); void submit(c); }} onClose={() => setScanning(false)} />}
    </Backdrop>
  );
}
