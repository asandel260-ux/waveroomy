import { useEffect, useState } from "react";
import { core } from "../../app/instance";
import type { Nav } from "../nav";
import { useFlow } from "../hooks";
import { Equalizer, Avatar, Backdrop, Button, Chip, ConfirmDialog, EmptyState, GroupAvatar, PageHead, Icon, IconButton, Menu, MenuEntry, Muted, Row2, SectionTitle, Sheet, Field, Tiny, Spinner } from "../components/kit";
import { NearbyRoom, SavedRoom, avatarFrom, defaultAvatar } from "../../core/models";
import { timeAgo } from "../../core/util";
import { SAVED } from "../../room/dmhub";

export function HomeScreen({ nav }: { nav: Nav }) {
  const tab = useFlow(nav.homeTab);
  const st = useFlow(core.settings.state);
  useEffect(() => { if (st.nearby) core.lan.startListening(); else core.lan.stopListening(); }, [st.nearby]);
  return (
    <Backdrop>
      <div style={{ position: "relative", flex: 1, minHeight: 0, display: "flex", flexDirection: "column" }} key={tab}>
        <div className="page" style={{ position: "relative" }}>
          {tab === 0 ? <RoomsTab nav={nav} /> : tab === 1 ? <MessagesTab nav={nav} /> : <YouTab nav={nav} />}
        </div>
      </div>
    </Backdrop>
  );
}

/* ============================== Rooms ============================== */

function RoomsTab({ nav }: { nav: Nav }) {
  const saved = useFlow(core.rooms.rooms);
  const nearby = useFlow(core.lan.nearby);
  const settings = useFlow(core.settings.state);
  const [joining, setJoining] = useState<string | null>(null);
  const [restart, setRestart] = useState<SavedRoom | null>(null);
  const [menu, setMenu] = useState<{ r: SavedRoom; x: number; y: number } | null>(null);
  const [del, setDel] = useState<SavedRoom | null>(null);

  const joinNearby = async (r: NearbyRoom) => {
    const p = core.profile.profile.value;
    if (!p) return;
    setJoining(r.roomId + r.ip);
    const err = await core.lan.join(r, { uid: core.profile.uid, name: p.displayName }, async (code) => {
      const res = await core.handleCode(code, false);
      return res.kind === "answer" ? res.code : null;
    });
    setJoining(null);
    if (err) core.toast(err);
  };

  return (
    <div className="scroll">
      <div className="col" style={{ paddingBottom: 24 }}>
        <PageHead label="Voice rooms" title="Rooms" text="Rooms on your Wi-Fi, and the rooms you have hosted before.">
          <Button text="Join with a code" icon="scan" kind="secondary" onClick={() => nav.push({ t: "join", code: "" })} />
          <Button text="New room" icon="plus" onClick={() => nav.push({ t: "create" })} />
        </PageHead>

        <SectionTitle>On this Wi-Fi</SectionTitle>
        {!settings.nearby ? (
          <div className="empty" style={{ paddingTop: 8 }}>
            <Muted>Nearby rooms are switched off, so rooms on your network are not shown.</Muted>
            <Button small kind="secondary" text="Open network settings" onClick={() => nav.push({ t: "settings" })} />
          </div>
        ) : nearby.length === 0 ? (
          <div className="card hstack g16 inset" style={{ padding: 20 }}><Equalizer /><Muted>Listening for rooms on this Wi-Fi. When someone starts one, it appears here.</Muted></div>
        ) : nearby.map((r) => <NearbyRow key={r.roomId + r.ip} r={r} busy={joining === r.roomId + r.ip} onJoin={() => joinNearby(r)} />)}

        <SectionTitle>Rooms you have hosted</SectionTitle>
        {saved.length === 0 ? (
          <div className="card hstack g16 inset" style={{ padding: 20 }}>
            <Equalizer live={false} />
            <div className="grow"><Muted>Rooms you start are kept here with their chat and blackboard, so you can open them again.</Muted></div>
            <Button small kind="ok" text="Start your first room" onClick={() => nav.push({ t: "create" })} />
          </div>
        ) : <>
          <div className="roomrow head"><span className="tiny">Room</span><span className="tiny num">Joins</span><span className="tiny num">Peak</span><span className="tiny">Last open</span><span /></div>
          {saved.map((r) => (
            <div key={r.meta.id} className="roomrow" onContextMenu={(e) => { e.preventDefault(); setMenu({ r, x: e.clientX, y: e.clientY }); }}>
              <button className="main" onClick={() => setRestart(r)} title={`Open ${r.meta.title} again`}>
                <Icon name={r.meta.unlisted ? "lock" : "waveform"} size={18} color="var(--accent-bright)" />
                <span className="ellipsis" style={{ fontSize: 16, fontWeight: 500 }}>{r.meta.title}</span>
              </button>
              <span className="num muted">{r.totalJoins}</span>
              <span className="num muted">{r.peak}</span>
              <span className="muted ellipsis">{timeAgo(r.lastActiveAt)}</span>
              <IconButton icon="more-v" color="var(--muted)" title="Room options" onClick={(e) => { const b = e.currentTarget.getBoundingClientRect(); setMenu({ r, x: b.left - 140, y: b.bottom }); }} />
            </div>
          ))}
        </>}
      </div>
      {menu && <Menu at={menu} onDismiss={() => setMenu(null)} entries={[
        { label: "Open again", icon: "play", onClick: () => setRestart(menu.r) },
        { label: "Forget this room", icon: "trash", danger: true, onClick: () => setDel(menu.r) }
      ] as MenuEntry[]} />}
      {restart && <ConfirmDialog title={`Open “${restart.meta.title}”?`} text="You'll be the host. People can join with a code or from the Nearby list." confirm="Open room" onDismiss={() => setRestart(null)} onConfirm={() => {
        core.requestMic();
        core.session.createRoom(restart.meta.title, restart.meta.topic, restart.meta.tags, restart.meta.unlisted, restart.meta.maxSpeakers, core.rooms.loadData(restart.meta.id), restart.meta.id);
      }} />}
      {del && <ConfirmDialog danger title="Forget this room?" text="Its saved chat and blackboard will be deleted from this computer." confirm="Forget" onDismiss={() => setDel(null)} onConfirm={() => core.rooms.delete(del.meta.id)} />}
    </div>
  );
}

function NearbyRow({ r, busy, onJoin }: { r: NearbyRoom; busy: boolean; onJoin: () => void }) {
  return (
    <button className="listrow" disabled={busy} onClick={onJoin}>
      <Icon name="wifi" size={20} color="var(--live)" />
      <div className="grow">
        <div className="hstack g6"><span className="ellipsis" style={{ fontSize: 16, fontWeight: 500 }}>{r.title}</span>{r.locked && <Icon name="lock" size={13} color="var(--faint)" />}</div>
        <Muted size={12}>{r.hostName} · {r.count} here{r.topic ? ` · ${r.topic}` : ""}</Muted>
      </div>
      <Chip text={busy ? "Connecting" : "Join"} selected={!busy} />
    </button>
  );
}

/* ============================== Messages ============================== */

function MessagesTab({ nav }: { nav: Nav }) {
  const convs = useFlow(core.dms.convs);
  const contacts = useFlow(core.social.contacts);
  const connected = useFlow(core.connected);
  const groups = useFlow(core.groups.groups);
  const [sheet, setSheet] = useState(false);
  const list = Object.values(convs).filter((c) => c.uid !== SAVED && c.messages.length > 0 && (!c.uid.startsWith("g:") || c.uid.slice(2) in groups)).sort((a, b) => b.lastAt - a.lastAt);
  const saved = convs[SAVED];
  const lastSaved = saved?.messages[saved.messages.length - 1];

  return (
    <div className="stack" style={{ flex: 1, minHeight: 0 }}>
      <PageHead label="Conversations" title="Messages">
        <Button small kind="secondary" icon="users" text="New group" onClick={() => nav.push({ t: "newGroup" })} />
        <Button small icon="user-plus" text="Add" onClick={() => setSheet(true)} />
      </PageHead>
      <div className="scroll">
        <div className="col">
          <FriendRequests />
          <NearbyPeople nav={nav} />
          <button className="listrow" onClick={() => nav.push({ t: "chat", uid: SAVED })}>
            <div style={{ width: 48, height: 48, borderRadius: 8, display: "grid", placeItems: "center", background: "var(--accent)", boxShadow: "0 0 0 1px var(--border-strong)" }}><Icon name="bookmark" size={22} color="#fff" /></div>
            <div className="grow"><div style={{ fontSize: 16, fontWeight: 500 }}>Saved messages</div><Muted><span className="ellipsis" style={{ display: "block" }}>{lastSaved ? core.dmHub.preview(lastSaved) : "Notes, links and files for yourself"}</span></Muted></div>
          </button>
          {list.length === 0 && (
            <EmptyState icon="message" title="No conversations yet" text="Start a group, or add a friend with a code." action={
              <div className="hstack g8"><Button small icon="users" text="New group" onClick={() => nav.push({ t: "newGroup" })} /><Button small kind="secondary" icon="user-plus" text="Add a friend" onClick={() => setSheet(true)} /></div>
            } />
          )}
          {list.map((c) => {
            const isG = c.uid.startsWith("g:");
            const grp = isG ? groups[c.uid.slice(2)] : undefined;
            const ct = contacts[c.uid];
            const last = [...c.messages].reverse().find((m) => m.kind !== "call") ?? c.messages[c.messages.length - 1];
            const who = !last ? "" : last.from === core.profile.uid ? "You: " : isG && last.from ? (last.authorName || core.contactName(last.from)) + ": " : "";
            return (
              <button key={c.uid} className="listrow" onClick={() => nav.push({ t: "chat", uid: c.uid })}>
                {isG ? <GroupAvatar name={grp?.name} hue={grp?.hue ?? 265} size={48} /> : <Avatar avatar={ct?.avatar ?? defaultAvatar()} size={48} uid={c.uid} name={ct?.displayName} />}
                <div className="grow">
                  <div className="hstack g6">
                    <span className="ellipsis" style={{ fontSize: 16, fontWeight: c.unread > 0 ? 600 : 500 }}>{isG ? grp?.name ?? "Group" : ct?.displayName ?? "Unknown"}</span>
                    {!isG && connected.has(c.uid) && <span style={{ width: 7, height: 7, borderRadius: "50%", background: "var(--neon)" }} />}
                    {c.muted && <Icon name="bell-off" size={12} color="var(--faint)" />}
                  </div>
                  <Muted><span className="ellipsis" style={{ display: "block" }}>{who + (last ? core.dmHub.preview(last) : "")}</span></Muted>
                </div>
                <div className="stack" style={{ alignItems: "flex-end", gap: 4 }}>
                  <Tiny>{timeAgo(c.lastAt)}</Tiny>
                  {c.unread > 0 && <span className={`unread ${c.muted ? "muted" : ""}`}>{c.unread > 99 ? "99+" : c.unread}</span>}
                </div>
              </button>
            );
          })}
        </div>
      </div>
      {sheet && <AddFriendSheet nav={nav} onDismiss={() => setSheet(false)} />}
    </div>
  );
}

export function AddFriendSheet({ nav, onDismiss }: { nav: Nav; onDismiss: () => void }) {
  const [q, setQ] = useState("");
  const results = useFlow(core.discovery.results);
  const searching = useFlow(core.discovery.searching);
  const note = useFlow(core.discovery.searchNote);
  const contacts = useFlow(core.social.contacts);
  useEffect(() => { core.discovery.results.value = []; core.discovery.searchNote.value = ""; }, []);
  return (
    <Sheet title="Add someone" onDismiss={onDismiss}>
      <div style={{ padding: "0 20px" }}>
        <Field label="Find by @username, or paste a link" placeholder="@name" value={q} onChange={setQ} onEnter={() => void core.discovery.search(q)} autoFocus />
        <div style={{ height: 10 }} />
        <Button block text={searching ? "Searching…" : "Search"} disabled={!q.trim() || searching} onClick={() => void core.discovery.search(q)} />
        {note && <div style={{ marginTop: 8 }}><Muted>{note}</Muted></div>}
      </div>
      {results.map((c) => {
        const already = !!contacts[c.uid]?.pair;
        return (
          <div key={c.uid} className="listrow">
            <Avatar avatar={avatarFrom(c.avatar)} size={46} name={c.name} />
            <div className="grow"><div className="ellipsis" style={{ fontSize: 16, fontWeight: 500 }}>{c.name}</div><Muted size={12}>@{c.username} · id {c.uid.slice(0, 5)}</Muted></div>
            {already ? <Chip text="Added" /> : <Button small text="Add" onClick={() => void core.discovery.sendRequest(c)} />}
          </div>
        );
      })}
      <div className="hairline" style={{ marginTop: 8 }} />
      <Row2 title="Copy my link" subtitle="Send it in any messenger" icon="share" onClick={() => { const l = core.discovery.myLink(); if (l) { core.copy(`Add me on HoMit: ${l}`); core.toast("Link copied"); } }} />
      <Row2 title="Show my code" subtitle="They paste it into HoMit" icon="qr" onClick={() => { onDismiss(); nav.push({ t: "invite", purpose: "dm" }); }} />
      <Row2 title="Paste a code" icon="scan" onClick={() => { onDismiss(); nav.push({ t: "join", code: "" }); }} />
      <NearbyPeople nav={nav} compact />
    </Sheet>
  );
}

/** Friend requests waiting for a yes or a no. */
export function FriendRequests() {
  const reqs = useFlow(core.discovery.requests);
  if (reqs.length === 0) return null;
  return (
    <>
      <SectionTitle>Friend requests</SectionTitle>
      {reqs.map((r) => (
        <div key={r.card.uid} className="listrow" style={{ paddingTop: 8, paddingBottom: 8 }}>
          <Avatar avatar={avatarFrom(r.card.avatar)} size={46} name={r.card.name} />
          <div className="grow"><div className="ellipsis" style={{ fontSize: 16, fontWeight: 500 }}>{r.card.name}</div><Muted size={12}>@{r.card.username} · wants to connect</Muted></div>
          <Button small text="Accept" onClick={() => void core.discovery.accept(r)} />
          <IconButton icon="x" color="var(--muted)" onClick={() => core.discovery.decline(r)} title="Decline" />
        </div>
      ))}
    </>
  );
}

/** People on the same Wi-Fi who are running HoMit: one click connects, no codes. */
export function NearbyPeople({ nav, compact }: { nav: Nav; compact?: boolean }) {
  const people = useFlow(core.lan.people);
  const connected = useFlow(core.connected);
  const settings = useFlow(core.settings.state);
  const [busy, setBusy] = useState<string | null>(null);
  if (!settings.nearby) return null;
  const list = people.filter((p) => p.uid !== core.profile.uid);
  if (list.length === 0 && compact) return <div className="hstack g10" style={{ padding: "8px 32px" }}><Icon name="wifi" size={18} color="var(--faint)" /><Muted>Looking for people on this Wi-Fi… make sure both devices are on the same network.</Muted></div>;
  if (list.length === 0) return null;
  return (
    <>
      <SectionTitle>On this Wi-Fi</SectionTitle>
      {list.map((p) => {
        const on = connected.has(p.uid);
        return (
          <button key={p.uid} className="listrow" disabled={busy != null} onClick={async () => {
            if (on) { nav.push({ t: "chat", uid: p.uid }); return; }
            setBusy(p.uid);
            const err = await core.connectNearby(p);
            setBusy(null);
            core.toast(err ?? `Connecting to ${p.name}…`);
          }}>
            <Avatar avatar={avatarFrom(p.avatar)} size={44} uid={p.uid} name={p.name} />
            <div className="grow"><div style={{ fontSize: 16, fontWeight: 500 }}>{p.name}</div><Muted size={12}>@{p.username}</Muted></div>
            {busy === p.uid ? <Spinner /> : <Chip text={on ? "Open chat" : "Connect"} selected={!on} />}
          </button>
        );
      })}
    </>
  );
}

/* ============================== You ============================== */

function YouTab({ nav }: { nav: Nav }) {
  const p = useFlow(core.profile.profile);
  const reports = useFlow(core.mod.reports);
  const open = reports.filter((r) => r.status === "open" && !r.outgoing).length;
  return (
    <div className="scroll">
      <div className="col">
        <button style={{ display: "block", width: "100%", textAlign: "left" }} onClick={() => nav.push({ t: "editProfile" })}>
          <div className="pagehead-w"><div className="pagehead" style={{ alignItems: "center" }}>
            <div className="l hstack g20">
              {p && <Avatar avatar={p.avatar} size={72} mine name={p.displayName} />}
              <div style={{ minWidth: 0 }}>
                <Tiny>You</Tiny>
                <h1 className="ellipsis" style={{ fontSize: 40, marginTop: 8 }}>{p?.displayName}</h1>
                <div className="mono" style={{ color: "var(--muted)", fontSize: 13, marginTop: 8 }}>@{p?.username}</div>
              </div>
            </div>
            <div className="r"><Icon name="chevron-right" size={20} color="var(--faint)" /></div>
          </div></div>
        </button>
        <Row2 icon="pencil" title="Edit profile" onClick={() => nav.push({ t: "editProfile" })} />
        <Row2 icon="users" title="People" subtitle="Following, followers, blocked" onClick={() => nav.push({ t: "people" })} />
        <Row2 icon="shield-alert" title="Moderation desk" subtitle={open > 0 ? `${open} open report(s)` : "Reports and audit trail for your rooms"} onClick={() => nav.push({ t: "moderation" })} />
        <Row2 icon="settings" title="Settings" subtitle="Voice effects, devices, network, privacy" onClick={() => nav.push({ t: "settings" })} />
        <div className="hairline" />
        <Row2 icon="book" title="Help" subtitle="How connecting works, tips" onClick={() => nav.push({ t: "help" })} />
        <Row2 icon="shield" title="Privacy & terms" onClick={() => nav.push({ t: "legal" })} />
        <Row2 icon="info" title="About HoMit" onClick={() => nav.push({ t: "about" })} />
      </div>
    </div>
  );
}
