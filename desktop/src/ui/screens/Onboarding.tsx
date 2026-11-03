import { useState } from "react";
import { core } from "../../app/instance";
import { AVATAR_EMOJIS } from "../../core/models";
import { newProfile } from "../../core/identity";
import { native } from "../../core/native";
import { parseJson } from "../../core/util";
import { Avatar, Backdrop, Button, Field, Icon, Muted, Tiny } from "../components/kit";
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
        <div style={{ maxWidth: 1040, margin: "0 auto", padding: "72px 32px 56px", display: "grid", gridTemplateColumns: "repeat(12, 1fr)", columnGap: 24, rowGap: 48 }}>
          <div style={{ gridColumn: "1 / span 5" }}>
            <h1 className="display" style={{ fontSize: 48, margin: 0 }}>Create your identity.</h1>
            <p className="muted" style={{ fontSize: 15, lineHeight: "22px", margin: "20px 0 0", maxWidth: 340 }}>Your identity is a key that lives only on this computer. There is no account, no email and no server to sign in to.</p>
            <div style={{ borderTop: "2px solid var(--text)", marginTop: 40 }}>
              {[["Back up", "Export it from Settings; the same file restores it on your phone."], ["Private", "Voice, messages and files go device to device."]].map(([t, d]) => (
                <div key={t} style={{ padding: "14px 0", borderBottom: "1px solid var(--border)" }}>
                  <div style={{ fontWeight: 600, fontSize: 14 }}>{t}</div><Muted size={13}>{d}</Muted>
                </div>
              ))}
            </div>
          </div>
          <div style={{ gridColumn: "7 / span 6" }}>
          <div className="hstack g16">
            <Avatar avatar={{ emoji, hue }} size={64} name={name} />
            <div>
              <Tiny>Avatar</Tiny>
              <div className="hstack g8" style={{ marginTop: 8 }}>
                {HUES.map((h) => <button key={h} onClick={() => setHue(h)} aria-label={`Avatar colour ${h}`} aria-pressed={h === hue} style={{ width: 22, height: 22, borderRadius: "50%", background: hsl(h, 0.55, 0.42), boxShadow: h === hue ? "0 0 0 2px var(--text)" : "none" }} />)}
              </div>
            </div>
          </div>
          <Field style={{ marginTop: 24 }} label="Display name" placeholder="Your name" value={name} onChange={(v) => { setName(v.slice(0, 30)); if (!edited) setHandle(cleanHandle(v.replace(/ /g, ""))); }} />
          <Field style={{ marginTop: 14 }} label="Username" placeholder="yourname" leadingIcon="user" value={handle} onChange={(v) => { setHandle(cleanHandle(v)); setEdited(true); }} onEnter={create} />
          {error && <div style={{ color: "var(--danger)", fontSize: 13, marginTop: 10 }}>{error}</div>}
          <div style={{ marginTop: 24 }}><Button block kind="ok" text="Create my identity" onClick={create} /></div>
          <div style={{ marginTop: 10 }}><Button block kind="secondary" text="Restore from a backup file" onClick={restore} /></div>
          </div>
        </div>
      </div>
    </Backdrop>
  );
}
