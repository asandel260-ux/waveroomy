import { useState } from "react";
import { core } from "../../app/instance";
import { AVATAR_EMOJIS, defaultAvatar } from "../../core/models";
import { newProfile } from "../../core/identity";
import { timeAgo } from "../../core/util";
import { native } from "../../core/native";
import { useFlow } from "../hooks";
import { hsl } from "../theme";
import { squareJpeg } from "../image";
import { Avatar, Backdrop, Button, Chip, ConfirmDialog, EmptyState, Field, HeroCard, Muted, NeonWave, ScreenHeader, Segmented, Tiny } from "../components/kit";
import { HUES, cleanHandle } from "./Onboarding";
import type { ScreenProps } from "./registry";

export function ProfileScreen({ nav, route, back }: ScreenProps) {
  const uid = route.t === "profile" ? route.uid : "";
  const contacts = useFlow(core.social.contacts);
  const following = useFlow(core.social.following);
  const followers = useFlow(core.social.followers);
  const blocked = useFlow(core.social.blocked);
  const connected = useFlow(core.connected);
  const me = useFlow(core.profile.profile);
  const mine = uid === core.profile.uid;
  const c = contacts[uid];
  const [confirmBlock, setConfirmBlock] = useState(false);
  const av = mine ? me?.avatar : c?.avatar;
  const name = (mine ? me?.displayName : c?.displayName) ?? "Unknown";
  const user = (mine ? me?.username : c?.username) ?? "";
  const pron = mine ? me?.pronouns : c?.pronouns;
  const bio = mine ? me?.bio : c?.bio;
  const isF = following.includes(uid);
  return (
    <Backdrop style={3}>
      <ScreenHeader title="Profile" onBack={back} />
      <div className="scroll"><div style={{ maxWidth: 520, margin: "0 0 0 max(0px, calc((100% - 880px) / 2))", padding: 20, display: "flex", flexDirection: "column", alignItems: "center" }}>
        <div style={{ width: "100%" }}>
          <HeroCard>
            <div style={{ paddingTop: 24, display: "flex", flexDirection: "column", alignItems: "center" }}>
              <Avatar avatar={av ?? defaultAvatar()} size={96} uid={uid} mine={mine} />
              <div className="display" style={{ fontSize: 26, marginTop: 12 }}>{name}</div>
              <div style={{ color: "var(--muted)", fontSize: 13, fontWeight: 500 }}>@{user}</div>
              <div style={{ width: "100%" }}><NeonWave height={40} amplitude={10} stroke={3} /></div>
            </div>
          </HeroCard>
        </div>
        <div style={{ height: 10 }} />
        {pron && <Muted size={12}>{pron}</Muted>}
        <div className="hstack g8" style={{ marginTop: 10 }}>
          {!mine && followers.includes(uid) && <Chip text="Follows you" selected />}
          {!mine && connected.has(uid) && <Chip text="Connected" icon="wifi" />}
        </div>
        {bio && <div style={{ marginTop: 14, fontSize: 15, textAlign: "center" }}>{bio}</div>}
        {!mine && c && c.lastSeen > 0 && <div style={{ marginTop: 10 }}><Tiny>Last connected {timeAgo(c.lastSeen)}</Tiny></div>}
        <div style={{ height: 24 }} />
        {mine ? <Button block icon="pencil" text="Edit profile" onClick={() => nav.push({ t: "editProfile" })} /> : (
          <div style={{ width: "100%" }}>
            <div className="hstack g10">
              <div className="grow"><Button block icon="heart" kind={isF ? "secondary" : "primary"} text={isF ? "Following" : "Follow"} onClick={() => core.follow(uid, !isF)} /></div>
              <div className="grow"><Button block icon="message" kind="secondary" text="Message" onClick={() => nav.push({ t: "chat", uid })} /></div>
            </div>
            <div style={{ height: 10 }} />
            <Button block icon="ban" kind="danger" text={blocked.includes(uid) ? "Unblock" : "Block"} onClick={() => { if (blocked.includes(uid)) core.block(uid, false); else setConfirmBlock(true); }} />
          </div>
        )}
      </div></div>
      {confirmBlock && <ConfirmDialog danger title="Block this person?" text="They can't connect to you and you won't see their messages." confirm="Block" onDismiss={() => setConfirmBlock(false)} onConfirm={() => core.block(uid, true)} />}
    </Backdrop>
  );
}

export function PeopleScreen({ nav, back }: ScreenProps) {
  const contacts = useFlow(core.social.contacts);
  const following = useFlow(core.social.following);
  const followers = useFlow(core.social.followers);
  const blocked = useFlow(core.social.blocked);
  const connected = useFlow(core.connected);
  const [tab, setTab] = useState("met");
  const uids: string[] = tab === "following" ? [...following] : tab === "followers" ? [...followers] : tab === "blocked" ? [...blocked]
    : Object.values(contacts).sort((a, b) => b.lastSeen - a.lastSeen).map((c) => c.uid);
  return (
    <Backdrop style={3}>
      <ScreenHeader title="People" onBack={back} />
      <div style={{ padding: "6px 16px" }}><Segmented options={[["met", "Met"], ["following", "Following"], ["followers", "Followers"], ["blocked", "Blocked"]]} selected={tab} onSelect={setTab} /></div>
      {uids.length === 0 ? <EmptyState icon="users" title="Nobody here yet" text="People appear after you've connected with them in a room or with a QR code." /> : (
        <div className="scroll"><div style={{ maxWidth: 720, margin: "0 0 0 max(0px, calc((100% - 880px) / 2))" }}>
          {uids.map((uid) => {
            const c = contacts[uid];
            const f = following.includes(uid);
            return (
              <div key={uid} className="listrow" style={{ cursor: "pointer" }} onClick={() => nav.push({ t: "profile", uid })}>
                <Avatar avatar={c?.avatar ?? defaultAvatar()} size={44} uid={uid} />
                <div className="grow"><div style={{ fontSize: 15, fontWeight: 500 }}>{c?.displayName ?? "Unknown"}</div><Muted size={12}>@{c?.username ?? ""}{connected.has(uid) ? " · connected" : ""}</Muted></div>
                {tab === "blocked" ? <Button small kind="secondary" text="Unblock" onClick={(e) => { e.stopPropagation(); core.block(uid, false); }} />
                  : <Button small kind={f ? "secondary" : "primary"} text={f ? "Following" : "Follow"} onClick={(e) => { e.stopPropagation(); core.follow(uid, !f); }} />}
              </div>
            );
          })}
        </div></div>
      )}
    </Backdrop>
  );
}

export function EditProfileScreen({ back }: ScreenProps) {
  const me = useFlow(core.profile.profile);
  useFlow(core.profile.photoVersion);
  const p0 = me!;
  const [name, setName] = useState(p0.displayName);
  const [handle, setHandle] = useState(p0.username);
  const [bio, setBio] = useState(p0.bio);
  const [pron, setPron] = useState(p0.pronouns);
  const [emoji, setEmoji] = useState(p0.avatar.emoji);
  const [hue, setHue] = useState(p0.avatar.hue);
  const [error, setError] = useState<string | null>(null);
  const choosePhoto = async () => {
    const f = await native.pickFile("image/*");
    if (!f) return;
    const b = await squareJpeg(f.bytes);
    if (b) core.profile.setPhoto(b); else core.toast("Could not read that image");
  };
  const save = () => {
    if (name.trim().length < 2) { setError("Display name needs 2+ characters."); return; }
    if (handle.length < 3) { setError("Username needs 3+ characters."); return; }
    core.profile.save(newProfile({ username: handle, displayName: name.trim(), bio: bio.trim(), pronouns: pron.trim(), avatar: { emoji, hue }, createdAt: p0.createdAt }));
    core.toast("Profile saved");
    back();
  };
  return (
    <Backdrop style={3}>
      <ScreenHeader title="Edit profile" onBack={back} />
      <div className="scroll"><div style={{ maxWidth: 560, margin: "0 0 0 max(0px, calc((100% - 880px) / 2))", padding: "24px 32px" }}>
        <div className="hstack g16">
          <Avatar avatar={{ emoji, hue }} size={80} mine />
          <div className="stack g6">
            <Button small kind="secondary" icon="image" text="Choose photo" onClick={choosePhoto} />
            {core.profile.photo && <Button small kind="ghost" text="Remove photo" onClick={() => core.profile.setPhoto(null)} />}
          </div>
        </div>
        <div className="hstack" style={{ gap: 8, marginTop: 14, flexWrap: "wrap" }}>
          {HUES.map((h) => <button key={h} style={{ width: 24, height: 24, borderRadius: "50%", background: hsl(h, 0.55, 0.42), boxShadow: h === hue ? "0 0 0 2px var(--text)" : "none" }} onClick={() => setHue(h)} />)}
        </div>
        <div className="hstack" style={{ gap: 8, marginTop: 10, flexWrap: "wrap" }}>
          {AVATAR_EMOJIS.map((e) => <button key={e} style={{ width: 40, height: 40, borderRadius: "50%", fontSize: 19, background: e === emoji ? "var(--accent-30)" : "var(--surface2)", boxShadow: `inset 0 0 0 ${e === emoji ? 1.5 : 1}px ${e === emoji ? "var(--text)" : "var(--border)"}` }} onClick={() => setEmoji(e)}>{e}</button>)}
        </div>
        <div style={{ height: 20 }} />
        <Field label="Display name" value={name} onChange={(v) => setName(v.slice(0, 30))} />
        <div style={{ height: 12 }} />
        <Field label="Username" leadingIcon="user" value={handle} onChange={(v) => setHandle(cleanHandle(v))} />
        <div style={{ height: 12 }} />
        <Field label="Pronouns (optional)" value={pron} onChange={(v) => setPron(v.slice(0, 20))} />
        <div style={{ height: 12 }} />
        <Field label="Bio" multiline rows={3} value={bio} onChange={(v) => setBio(v.slice(0, 160))} />
        {error && <div style={{ color: "var(--danger)", fontSize: 13, marginTop: 10 }}>{error}</div>}
        <div style={{ height: 8 }} /><Muted size={12}>Others see your updated profile the next time you connect.</Muted>
        <div style={{ height: 20 }} />
        <Button block text="Save" onClick={save} />
      </div></div>
    </Backdrop>
  );
}
