import { useEffect, useState } from "react";
import { core } from "../../app/instance";
import { canAdd, isAdmin } from "../../core/models";
import { useFlow } from "../hooks";
import { hsl } from "../theme";
import { Avatar, Backdrop, Button, ConfirmDialog, Field, GroupAvatar, HeroCard, Icon, Muted, Row2, ScreenHeader, SectionTitle, Sheet, SwitchRow, Tiny } from "../components/kit";
import { HUES } from "./Onboarding";
import type { ScreenProps } from "./registry";

const EMOJIS = ["💬", "🎧", "🔥", "🌙", "🎮", "📚", "🍕", "✈️", "🌿", "🎨"];

export function NewGroupScreen({ nav, back }: ScreenProps) {
  const contacts = useFlow(core.social.contacts);
  const [name, setName] = useState("");
  const [about, setAbout] = useState("");
  const [emoji, setEmoji] = useState("💬");
  const [hue, setHue] = useState(HUES[0]);
  const [picked, setPicked] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const people = Object.values(contacts).filter((c) => c.uid !== core.profile.uid).sort((a, b) => a.displayName.toLowerCase().localeCompare(b.displayName.toLowerCase()));
  const toggle = (u: string) => setPicked((p) => p.includes(u) ? p.filter((x) => x !== u) : [...p, u]);
  const create = () => {
    if (creating) return;
    if (name.trim().length < 2) { setError("Give the group a name first."); return; }
    setCreating(true);
    const id = core.dmHub.createGroup(name, about, picked, emoji, hue);
    nav.pop(); nav.push({ t: "chat", uid: id });
  };
  return (
    <Backdrop style={4}>
      <ScreenHeader title="New group" onBack={back} />
      <div className="scroll"><div style={{ maxWidth: 560, margin: "0 0 0 max(0px, calc((100% - 880px) / 2))", padding: "24px 32px" }}>
        <div className="hstack g14"><GroupAvatar emoji={emoji} hue={hue} size={64} /><div className="grow"><Field label="Group name" placeholder="Weekend plans" value={name} onChange={(v) => { setName(v); setError(null); }} maxLength={60} autoFocus onEnter={create} /></div></div>
        <div style={{ height: 12 }} />
        <Field label="About (optional)" placeholder="What is this group for?" value={about} onChange={setAbout} maxLength={200} />
        <div style={{ height: 14 }} /><Tiny>Icon</Tiny><div style={{ height: 8 }} />
        <div className="hstack" style={{ flexWrap: "wrap", gap: 8 }}>
          {EMOJIS.map((e) => <button key={e} style={{ width: 44, height: 44, borderRadius: "50%", fontSize: 20, background: e === emoji ? "var(--accent-30)" : "var(--surface2)", boxShadow: `inset 0 0 0 ${e === emoji ? 1.5 : 1}px ${e === emoji ? "var(--text)" : "var(--border)"}` }} onClick={() => setEmoji(e)}>{e}</button>)}
        </div>
        <div className="hstack" style={{ flexWrap: "wrap", gap: 8, marginTop: 10 }}>
          {HUES.map((h) => <button key={h} style={{ width: 44, height: 44, display: "grid", placeItems: "center" }} onClick={() => setHue(h)}><span style={{ width: 30, height: 30, borderRadius: "50%", background: hsl(h, 0.5, 0.4), boxShadow: h === hue ? "0 0 0 2px var(--text)" : "none" }} /></button>)}
        </div>
        <div style={{ height: 18 }} /><Tiny>Add people · {picked.length} selected</Tiny><div style={{ height: 6 }} />
        {people.length === 0 && <Muted>People you've connected with appear here. You can also create the group now and add people later with a link or QR code.</Muted>}
        {people.map((c) => {
          const on = picked.includes(c.uid);
          return (
            <button key={c.uid} className="hstack g12" style={{ width: "100%", padding: "8px 0", textAlign: "left" }} onClick={() => toggle(c.uid)}>
              <Avatar avatar={c.avatar} size={40} uid={c.uid} />
              <div className="grow"><div style={{ fontSize: 15 }}>{c.displayName}</div><Muted size={12}>@{c.username}</Muted></div>
              <span style={{ width: 24, height: 24, borderRadius: "50%", display: "grid", placeItems: "center", background: on ? "var(--text)" : "transparent", boxShadow: `inset 0 0 0 1.5px ${on ? "var(--text)" : "var(--border-strong)"}` }}>{on && <Icon name="check" size={14} color="#052200" />}</span>
            </button>
          );
        })}
        <div style={{ height: 16 }} />
        {error && <div style={{ color: "var(--danger)", fontSize: 13, marginBottom: 8 }}>{error}</div>}
        <Button block icon="users" text={picked.length === 0 ? "Create group" : `Create with ${picked.length} ${picked.length === 1 ? "person" : "people"}`} onClick={create} />
        <div style={{ height: 20 }} />
      </div></div>
    </Backdrop>
  );
}

export function GroupInfoScreen({ nav, route, back }: ScreenProps) {
  const id = route.t === "groupInfo" ? route.gid : "";
  const groups = useFlow(core.groups.groups);
  const convs = useFlow(core.dms.convs);
  const connected = useFlow(core.connected);
  const contacts = useFlow(core.social.contacts);
  const g = groups[id];
  const me = core.profile.uid;
  const [edit, setEdit] = useState(false);
  const [add, setAdd] = useState(false);
  const [leave, setLeave] = useState(false);
  const [memberMenu, setMemberMenu] = useState<string | null>(null);
  useEffect(() => { if (!g) back(); }, [g == null]);
  if (!g) return <Backdrop />;
  const admin = isAdmin(g, me);
  const chat = "g:" + g.id;
  const muted = convs[chat]?.muted === true;
  const order = (r: string) => (r === "owner" ? 0 : r === "admin" ? 1 : 2);
  const members = Object.values(g.members).sort((a, b) => order(a.role) - order(b.role) || a.name.toLowerCase().localeCompare(b.name.toLowerCase()));
  const mm = memberMenu ? g.members[memberMenu] : null;
  return (
    <Backdrop>
      <ScreenHeader title="Group info" onBack={back} />
      <div className="scroll"><div style={{ maxWidth: 640, margin: "0 0 0 max(0px, calc((100% - 880px) / 2))", paddingBottom: 30 }}>
        <div style={{ padding: 16 }}>
          <HeroCard>
            <div style={{ padding: 20, display: "flex", flexDirection: "column", alignItems: "center", textAlign: "center" }}>
              <GroupAvatar emoji={g.emoji} hue={g.hue} size={88} />
              <div className="display" style={{ fontSize: 26, marginTop: 12 }}>{g.name}</div>
              {g.about && <div style={{ marginTop: 4 }}><Muted>{g.about}</Muted></div>}
              <div style={{ marginTop: 6 }}><Muted size={12}>{members.length === 1 ? "1 member" : `${members.length} members`}</Muted></div>
              <div className="hstack g10" style={{ marginTop: 14 }}>
                <Button small kind="secondary" icon="message" text="Message" onClick={() => nav.replaceTop({ t: "chat", uid: chat })} />
                <Button small kind="secondary" icon="phone" text="Group call" disabled={g.left} onClick={() => core.dmHub.startCall(chat)} />
              </div>
            </div>
          </HeroCard>
        </div>
        {!g.left && <>
          {canAdd(g, me) && <>
            <Row2 title="Add people" subtitle="From people you've met" icon="user-plus" onClick={() => setAdd(true)} />
            <Row2 title="Invite with link or QR" subtitle="Anyone who scans joins the group" icon="qr" onClick={() => nav.push({ t: "invite", purpose: "group:" + g.id })} />
          </>}
          {admin && <Row2 title="Edit group" icon="pencil" onClick={() => setEdit(true)} />}
          <Row2 title={muted ? "Unmute notifications" : "Mute notifications"} icon={muted ? "bell" : "bell-off"} onClick={() => core.dms.setMuted(chat, !muted)} />
        </>}
        <SectionTitle>Members</SectionTitle>
        {members.map((m) => (
          <button key={m.uid} className="listrow" onClick={() => { if (m.uid !== me) setMemberMenu(m.uid); }}>
            <Avatar avatar={m.avatar} size={42} uid={m.uid} mine={m.uid === me} />
            <div className="grow"><div style={{ fontSize: 15, fontWeight: 500 }}>{m.uid === me ? `${m.name} (you)` : m.name}</div><Muted size={12}>@{m.username}{m.uid !== me && connected.has(m.uid) ? " · connected" : ""}</Muted></div>
            {m.role !== "member" && <span style={{ color: "var(--accent-bright)", fontSize: 12, fontWeight: 600 }}>{m.role}</span>}
          </button>
        ))}
        <div style={{ height: 10 }} />
        {!g.left && <Row2 title="Leave group" icon="log-out" danger onClick={() => setLeave(true)} />}
      </div></div>

      {edit && <EditGroup g={g} onDismiss={() => setEdit(false)} />}
      {add && (
        <Sheet title="Add people" center onDismiss={() => setAdd(false)}>
          <div style={{ maxHeight: 460, overflowY: "auto" }}>
            {Object.values(contacts).filter((c) => !g.members[c.uid]).length === 0 && <div style={{ padding: 20 }}><Muted>Everyone you know is already here. Use “Invite with link or QR” for new people.</Muted></div>}
            {Object.values(contacts).filter((c) => !g.members[c.uid]).map((c) => (
              <button key={c.uid} className="listrow" onClick={() => { core.dmHub.addMember(g.id, c.uid); setAdd(false); }}>
                <Avatar avatar={c.avatar} size={40} uid={c.uid} />
                <div className="grow"><div style={{ fontSize: 15 }}>{c.displayName}</div><Muted size={12}>{connected.has(c.uid) ? "connected. Added instantly" : "added when you next connect"}</Muted></div>
              </button>
            ))}
          </div>
        </Sheet>
      )}
      {mm && (
        <Sheet title={mm.name} center onDismiss={() => setMemberMenu(null)}>
          <Row2 title="Message" icon="message" onClick={() => { setMemberMenu(null); nav.push({ t: "chat", uid: mm.uid }); }} />
          <Row2 title="View profile" icon="user" onClick={() => { setMemberMenu(null); nav.push({ t: "profile", uid: mm.uid }); }} />
          {g.ownerUid === me && mm.role !== "owner" && <Row2 title={mm.role === "admin" ? "Remove admin" : "Make admin"} icon="shield" onClick={() => { core.dmHub.setAdmin(g.id, mm.uid, mm.role !== "admin"); setMemberMenu(null); }} />}
          {admin && mm.role !== "owner" && <Row2 title="Remove from group" icon="log-out" danger onClick={() => { core.dmHub.removeMember(g.id, mm.uid); setMemberMenu(null); }} />}
        </Sheet>
      )}
      {leave && <ConfirmDialog danger title="Leave group?" text="You will stop receiving messages. The history stays on this computer until you delete the chat." confirm="Leave" onDismiss={() => setLeave(false)} onConfirm={() => { setLeave(false); core.dmHub.leaveGroup(g.id); }} />}
    </Backdrop>
  );
}

function EditGroup({ g, onDismiss }: { g: { id: string; name: string; about: string; membersCanAdd: boolean; emoji: string; hue: number }; onDismiss: () => void }) {
  const [n, setN] = useState(g.name);
  const [a, setA] = useState(g.about);
  const [mca, setMca] = useState(g.membersCanAdd);
  const [e, setE] = useState(g.emoji);
  return (
    <Sheet title="Edit group" center onDismiss={onDismiss}>
      <div style={{ padding: "0 20px 16px" }}>
        <Field label="Name" value={n} onChange={setN} maxLength={60} />
        <div style={{ height: 10 }} />
        <Field label="About" value={a} onChange={setA} maxLength={200} />
        <div style={{ height: 10 }} />
        <div className="hstack" style={{ flexWrap: "wrap", gap: 8 }}>
          {EMOJIS.map((x) => <button key={x} style={{ width: 40, height: 40, borderRadius: "50%", fontSize: 20, background: x === e ? "var(--accent-30)" : "var(--surface2)" }} onClick={() => setE(x)}>{x}</button>)}
        </div>
        <SwitchRow title="Members can add people" subtitle="Otherwise only admins can." checked={mca} onChange={setMca} />
        <div style={{ height: 10 }} />
        <Button block text="Save" onClick={() => { core.dmHub.updateGroup(g.id, n, a, mca, e, g.hue); onDismiss(); }} />
      </div>
    </Sheet>
  );
}
