import { useState } from "react";
import { core } from "../../app/instance";
import { AVATAR_EMOJIS } from "../../core/models";
import { newProfile } from "../../core/identity";
import { native } from "../../core/native";
import { parseJson } from "../../core/util";
import { Avatar, Backdrop, Button, Field, HeroCard, Icon, Muted, NeonWave, Tiny } from "../components/kit";
import { hsl } from "../theme";

export const cleanHandle = (s: string) => s.toLowerCase().replace(/[^a-z0-9_]/g, "").slice(0, 20);
export const HUES = [265, 200, 160, 120, 40, 10, 330, 290];

export function Onboarding() {
  const [name, setName] = useState("");
  const [handle, setHandle] = useState("");
  const [edited, setEdited] = useState(false);
  const [emoji, setEmoji] = useState(AVATAR_EMOJIS[0]);
  const [hue, setHue] = useState(HUES[0]);
  const [error, setError] = useState<string | null>(null);

  const create = () => {
    const n = name.trim();
    if (n.length < 2) { setError("Pick a display name (2+ characters)."); return; }
    if (handle.length < 3) { setError("Usernames need 3+ letters, numbers or underscores."); return; }
    core.profile.save(newProfile({ username: handle, displayName: n, avatar: { emoji, hue } }));
  };
  const restore = async () => {
    const f = await native.pickFile(".json");
    if (!f) return;
    try {
      const j = parseJson(new TextDecoder().decode(f.bytes));
      if (!j || !(await core.profile.importBackup(j))) setError("That file is not a HoMit identity backup.");
      else await core.afterIdentityChange();
    } catch { setError("Could not read that file."); }
  };

  return (
    <Backdrop>
      <div className="scroll" style={{ position: "relative" }}>
        <div style={{ maxWidth: 520, margin: "0 auto", padding: "32px 24px 40px" }}>
          <HeroCard>
            <div style={{ padding: 22 }}>
              <div className="hstack g10">
                <div style={{ width: 38, height: 38, borderRadius: "50%", display: "grid", placeItems: "center", background: "linear-gradient(180deg, var(--grad-top), var(--grad-bottom))", boxShadow: "0 0 0 1px rgb(182 144 255 / .6)" }}><Icon name="waveform" size={20} color="#fff" /></div>
                <span className="display" style={{ fontSize: 22 }}>HoMit</span>
              </div>
              <div className="display" style={{ fontSize: 34, lineHeight: "38px", marginTop: 22 }}>Voice rooms<br />with no server.</div>
              <NeonWave height={40} amplitude={12} stroke={3} />
              <Muted size={14}>Talk, chat, share your screen, watch together and draw — device to device. Your identity is a key that lives only on this computer.</Muted>
            </div>
          </HeroCard>

          <div className="hstack g16" style={{ marginTop: 32 }}>
            <Avatar avatar={{ emoji, hue }} size={64} />
            <div>
              <Tiny>Avatar</Tiny>
              <div className="hstack g8" style={{ marginTop: 8 }}>
                {HUES.map((h) => <button key={h} onClick={() => setHue(h)} style={{ width: 22, height: 22, borderRadius: "50%", background: hsl(h, 0.55, 0.42), boxShadow: h === hue ? "0 0 0 2px var(--neon)" : "none" }} />)}
              </div>
            </div>
          </div>
          <div className="hstack wrap g8" style={{ marginTop: 14 }}>
            {AVATAR_EMOJIS.map((e) => (
              <button key={e} onClick={() => setEmoji(e)} style={{ width: 42, height: 42, borderRadius: "50%", fontSize: 20, background: e === emoji ? "var(--accent-soft)" : "var(--surface2)", boxShadow: `inset 0 0 0 ${e === emoji ? 1.5 : 1}px ${e === emoji ? "var(--neon)" : "var(--border)"}` }}>{e}</button>
            ))}
          </div>
          <Field style={{ marginTop: 24 }} label="Display name" placeholder="Ada Lovelace" value={name} onChange={(v) => { setName(v.slice(0, 30)); if (!edited) setHandle(cleanHandle(v.replace(/ /g, ""))); }} />
          <Field style={{ marginTop: 14 }} label="Username" placeholder="ada" leadingIcon="user" value={handle} onChange={(v) => { setHandle(cleanHandle(v)); setEdited(true); }} onEnter={create} />
          {error && <div style={{ color: "var(--danger)", fontSize: 13, marginTop: 10 }}>{error}</div>}
          <div style={{ marginTop: 24 }}><Button block text="Create my identity" onClick={create} /></div>
          <div style={{ marginTop: 10 }}><Button block kind="secondary" text="Restore from a backup file" onClick={restore} /></div>
          <div style={{ marginTop: 20 }}><Muted size={12}>No account, no email, no server. Back up your identity from Settings to keep it if you change computers — the same backup file also works in the HoMit phone app.</Muted></div>
        </div>
      </div>
    </Backdrop>
  );
}
