import { useEffect, useState, type ReactNode } from "react";
import { core } from "../../app/instance";
import { FX_PRESETS } from "../../media/rtc";
import { native } from "../../core/native";
import { parseJson } from "../../core/util";
import { useFlow } from "../hooks";
import { Backdrop, Button, Card, ConfirmDialog, Field, Icon, Muted, Row2, ScreenHeader, SectionTitle, Segmented, Sheet, SwitchRow, Tiny } from "../components/kit";
import { ACCENT_PRESETS, BG_PRESETS, ColorPickerCard } from "../components/ColorPicker";
import type { ScreenProps } from "./registry";

/** Voice effects controls, shared by Settings and the in-room sheet. */
export function VoiceFxPanel() {
  const s = useFlow(core.settings.state);
  const available = typeof AudioWorkletNode !== "undefined";
  const upd = (f: (x: typeof s) => typeof s) => { core.settings.update(f); core.rtc.applyFx(); };
  return (
    <div>
      {!available && <div style={{ marginBottom: 8 }}><Muted size={12}>Voice effects need AudioWorklet support, which this system doesn't have, so they stay off.</Muted></div>}
      <SwitchRow title="Enable voice effects" subtitle="Processed on this computer before your voice is sent." checked={s.fxEnabled} disabled={!available} onChange={(v) => upd((x) => ({ ...x, fxEnabled: v }))} />
      <div className="hstack" style={{ flexWrap: "wrap", gap: 8 }}>
        {FX_PRESETS.map((p) => {
          const on = s.fxPreset === p.id;
          return (
            <button key={p.id} style={{ width: 150, textAlign: "left", borderRadius: 10, padding: 12, background: on ? "var(--accent-30)" : "var(--surface2)", boxShadow: `inset 0 0 0 1px ${on ? "rgb(82 255 31 / .7)" : "var(--border)"}` }} onClick={() => upd((x) => ({ ...x, fxPreset: p.id, fxEnabled: available }))}>
              <div style={{ color: on ? "var(--accent-bright)" : "var(--text)", fontSize: 14, fontWeight: 600 }}>{p.name}</div>
              <Muted size={11}>{p.desc}</Muted>
            </button>
          );
        })}
      </div>
      <div style={{ height: 10 }} />
      <Tiny>Intensity · {Math.round(s.fxIntensity * 100)}%</Tiny>
      <input type="range" min={0} max={1} step={0.01} value={s.fxIntensity} style={{ width: "100%", accentColor: "var(--accent)" }} onChange={(e) => upd((x) => ({ ...x, fxIntensity: parseFloat(e.target.value) }))} />
      <Muted size={12}>Tip: use headphones so the processed sound doesn't loop back into your microphone.</Muted>
    </div>
  );
}

function Pad({ children }: { children: ReactNode }) { return <div style={{ padding: "0 20px" }}>{children}</div>; }

function DeviceRow({ label, on, onClick }: { label: string; on: boolean; onClick: () => void }) {
  return <button className="hstack" style={{ width: "100%", padding: "14px 20px", textAlign: "left" }} onClick={onClick}><span className="grow" style={{ fontSize: 15, color: on ? "var(--accent-bright)" : "var(--text)" }}>{label}</span>{on && <Icon name="check" size={18} color="var(--accent-bright)" />}</button>;
}

async function listDevices(kind: "audioinput" | "audiooutput"): Promise<MediaDeviceInfo[]> {
  try { return (await navigator.mediaDevices.enumerateDevices()).filter((d) => d.kind === kind && d.deviceId !== "default" && d.deviceId !== "communications"); } catch { return []; }
}

export function SettingsScreen({ nav, back }: ScreenProps) {
  const s = useFlow(core.settings.state);
  const rvs = useFlow(core.rendezvous.status);
  const [sheet, setSheet] = useState<"mic" | "out" | "dm" | null>(null);
  const [devices, setDevices] = useState<MediaDeviceInfo[]>([]);
  const [wipe, setWipe] = useState(false);
  const set = (p: Partial<typeof s>) => core.settings.update((x) => ({ ...x, ...p }));
  useEffect(() => {
    if (sheet === "mic" || sheet === "out") void listDevices(sheet === "mic" ? "audioinput" : "audiooutput").then(setDevices);
  }, [sheet]);
  const [devNames, setDevNames] = useState<Record<string, string>>({});
  useEffect(() => { void Promise.all([listDevices("audioinput"), listDevices("audiooutput")]).then(([a, b]) => setDevNames(Object.fromEntries([...a, ...b].map((d) => [d.deviceId, d.label])))); }, []);
  const dmLabel = s.dmPolicy === "nobody" ? "Nobody" : s.dmPolicy === "following" ? "People I follow" : "Anyone I'm connected to";

  const exportBackup = async () => {
    const bytes = new TextEncoder().encode(JSON.stringify(core.profile.exportBackup(), null, 2));
    core.toast((await native.saveFile("homit-identity.json", bytes)) ? "Backup saved. Keep it private" : "Not saved");
  };
  const restore = async () => {
    const f = await native.pickFile("application/json,.json");
    if (!f) return;
    const j = parseJson(new TextDecoder().decode(f.bytes));
    if (j && (await core.profile.importBackup(j))) { core.toast("Identity restored"); core.afterIdentityChange(); } else core.toast("That isn't a HoMit backup");
  };

  return (
    <Backdrop style={2}>
      <ScreenHeader title="Settings" onBack={back} />
      <div className="scroll"><div style={{ maxWidth: 720, margin: "0 0 0 max(0px, calc((100% - 880px) / 2))", paddingBottom: 32 }}>
        <SectionTitle>Appearance</SectionTitle>
        <Pad><div className="stack g12">
          <div><Tiny>Theme</Tiny><div style={{ height: 8 }} /><Segmented options={[["dark", "Dark"], ["light", "Light"], ["system", "Match Windows"]]} selected={s.theme} onSelect={(v) => set({ theme: v, bgColor: 0 })} /></div>
          <SwitchRow title="Animations" subtitle="Fades and slides between screens. Turn off for a calmer app." checked={s.animations} onChange={(v) => set({ animations: v })} />
          <ColorPickerCard title="Buttons & accents" subtitle="Buttons, highlights, switches and sliders." value={s.accentColor} def={0xFF7C3AED | 0} presets={ACCENT_PRESETS} onChange={(c) => set({ accentColor: c })} />
          <ColorPickerCard title="Background" subtitle="Overrides the theme base colour. Panels and borders follow it." value={s.bgColor} def={0xFF0B0713 | 0} presets={BG_PRESETS} onChange={(c) => set({ bgColor: c })} />
        </div></Pad>

        <SectionTitle>Voice</SectionTitle>
        <div style={{ padding: "0 16px" }}><Card><div style={{ padding: "4px 16px" }}>
          <SwitchRow title="Push to talk" subtitle="Hold the key (or the mic button) to speak instead of toggling it." checked={s.ptt} onChange={(v) => set({ ptt: v })} />
          {s.ptt && <div style={{ padding: "4px 0 10px" }}><div onKeyDown={(e: React.KeyboardEvent) => { e.preventDefault(); set({ pttKey: e.code }); }}><Field label="Push-to-talk key (click, then press a key)" value={s.pttKey} mono readOnly /></div></div>}
          <SwitchRow title="Interface sounds" subtitle="Small tones when people join, leave or message." checked={s.sounds} onChange={(v) => set({ sounds: v })} />
        </div></Card></div>

        <SectionTitle>Voice effects</SectionTitle>
        <Pad><VoiceFxPanel /></Pad>

        <SectionTitle>Audio devices</SectionTitle>
        <Row2 title="Microphone" subtitle={devNames[s.micDeviceId] || "Automatic"} icon="mic" onClick={() => setSheet("mic")} />
        <Row2 title="Output" subtitle={devNames[s.outputDeviceId] || "Automatic (system default)"} icon="volume" onClick={() => setSheet("out")} />

        <SectionTitle>Privacy</SectionTitle>
        <Row2 title="Who can message me" subtitle={dmLabel} icon="message" onClick={() => setSheet("dm")} />
        <Pad>
          <div style={{ padding: "6px 0" }}>
            <div style={{ fontSize: 15 }}>Who can find me</div>
            <Muted size={12}>Everyone: people can search your @username. Link only: they need your link. Nobody: no new requests.</Muted>
            <div style={{ height: 8 }} />
            <Segmented options={[["everyone", "Everyone"], ["link", "Link only"], ["nobody", "Nobody"]]} selected={s.findMe} onSelect={(v) => { set({ findMe: v }); core.rendezvous.kick(); core.discovery.profileChanged(); }} />
          </div>
        </Pad>
        <Row2 title="Copy diagnostics" subtitle="Copy a technical report if something misbehaves" icon="info" onClick={() => { core.copy(core.mesh.report.value || "No connection attempts yet."); core.toast("Report copied"); }} />

        <SectionTitle>Windows</SectionTitle>
        <div style={{ padding: "0 16px" }}><Card><div style={{ padding: "4px 16px" }}>
          <SwitchRow title="Keep running in the tray" subtitle="Closing the window hides HoMit so calls and messages keep arriving." checked={s.closeToTray} onChange={(v) => set({ closeToTray: v })} />
          <SwitchRow title="Start minimized" subtitle="Open to the tray when you sign in to Windows." checked={s.startMinimized} onChange={(v) => set({ startMinimized: v })} />
          <SwitchRow title="Desktop notifications" subtitle="Show messages and calls while the window is in the background." checked={s.notifications} onChange={(v) => set({ notifications: v })} />
        </div></Card></div>

        <SectionTitle>Network</SectionTitle>
        <div style={{ padding: "0 16px" }}><Card><div style={{ padding: "8px 16px" }}>
          <SwitchRow title="Show rooms on this Wi-Fi" subtitle="Listen for rooms hosted on the same network and list them under Rooms." checked={s.nearby} onChange={(v) => set({ nearby: v })} />
          <SwitchRow title="Use public STUN servers" subtitle="Helps two devices on different networks find a direct path. It only reveals your IP address to the STUN server. No audio or content. Turn off to stay strictly on the local network." checked={s.useStun} onChange={(v) => set({ useStun: v })} />
          <SwitchRow title="Relay helper (recommended)" subtitle="Lets devices on different networks (mobile data, strict Wi-Fi) connect when a direct path is impossible. A public relay carries only encrypted packets and is used as a last resort. Turn off to never touch any outside server." checked={s.useRelay} onChange={(v) => set({ useRelay: v })} />
          <SwitchRow title="Reconnect helper (recommended)" subtitle="Lets saved friends find each other again over the internet without scanning anything. Only tiny encrypted notes, readable only by the two of you, pass through a public message board (ntfy.sh by default). Turn off to reconnect on the same Wi-Fi only." checked={s.reconnect} onChange={(v) => set({ reconnect: v })} />
          {s.reconnect && <>
            <Muted size={12}>Status: {rvs}</Muted><div style={{ height: 6 }} />
            <Field label="Message board address (optional)" placeholder="https://ntfy.sh" mono value={s.rvUrl} onChange={(v) => set({ rvUrl: v })} />
          </>}
          <div style={{ height: 8 }} />
          <Field label="Extra STUN servers (comma separated)" placeholder="stun.example.com:3478" mono value={s.customStun} onChange={(v) => set({ customStun: v })} />
          <div style={{ height: 14 }} /><Tiny>Optional relay (TURN) for strict networks</Tiny><div style={{ height: 6 }} />
          <Muted size={12}>If two people can't connect (symmetric NAT, corporate Wi-Fi), a TURN relay you control makes it work. HoMit never ships with one.</Muted>
          <div style={{ height: 8 }} /><Field mono placeholder="turn:turn.example.com:3478" value={s.turnUrl} onChange={(v) => set({ turnUrl: v })} />
          <div style={{ height: 8 }} /><Field placeholder="Username" value={s.turnUser} onChange={(v) => set({ turnUser: v })} />
          <div style={{ height: 8 }} /><Field password placeholder="Password" value={s.turnPass} onChange={(v) => set({ turnPass: v })} />
          <div style={{ height: 6 }} /><Muted size={12}>Network changes apply to the next connection you make.</Muted>
        </div></Card></div>

        <SectionTitle>Your devices</SectionTitle>
        <Row2 title="Link another device" subtitle="Keep your messages, contacts and rooms in step with your phone" icon="qr" onClick={() => nav.push({ t: "invite", purpose: "sync" })} />
        <Row2 title="Paste a device code" icon="scan" onClick={() => nav.push({ t: "join", code: "" })} />

        <SectionTitle>Identity & data</SectionTitle>
        <Pad><div className="stack g10">
          <Muted size={12}>Your identity is a key stored only on this computer. Export a backup to use the same name, handle and followers on another device. Import it there to be the same account.</Muted>
          <Button block kind="secondary" icon="download" text="Export identity backup" onClick={exportBackup} />
          <Button block kind="secondary" icon="copy" text="Restore from backup" onClick={restore} />
          <Button block kind="danger" icon="trash" text="Delete everything on this computer" onClick={() => setWipe(true)} />
        </div></Pad>
      </div></div>

      {(sheet === "mic" || sheet === "out") && (
        <Sheet title={sheet === "mic" ? "Microphone" : "Output"} center onDismiss={() => setSheet(null)}>
          <DeviceRow label="Automatic" on={(sheet === "mic" ? s.micDeviceId : s.outputDeviceId) === ""} onClick={() => { set(sheet === "mic" ? { micDeviceId: "" } : { outputDeviceId: "" }); after(sheet); setSheet(null); }} />
          {devices.map((d) => <DeviceRow key={d.deviceId} label={d.label || d.deviceId.slice(0, 8)} on={(sheet === "mic" ? s.micDeviceId : s.outputDeviceId) === d.deviceId} onClick={() => { set(sheet === "mic" ? { micDeviceId: d.deviceId } : { outputDeviceId: d.deviceId }); after(sheet); setSheet(null); }} />)}
        </Sheet>
      )}
      {sheet === "dm" && (
        <Sheet title="Who can message me" center onDismiss={() => setSheet(null)}>
          {([["everyone", "Anyone I'm connected to"], ["following", "Only people I follow"], ["nobody", "Nobody"]] as const).map(([k, l]) => <DeviceRow key={k} label={l} on={s.dmPolicy === k} onClick={() => { set({ dmPolicy: k }); setSheet(null); }} />)}
        </Sheet>
      )}
      {wipe && <ConfirmDialog danger title="Delete everything?" text="Your identity, followers, messages, saved rooms and settings are erased from this computer. This can't be undone unless you have a backup." confirm="Delete" onDismiss={() => setWipe(false)} onConfirm={() => { void core.wipeAll(); nav.resetTo({ t: "home" }); }} />}
    </Backdrop>
  );
}

function after(kind: "mic" | "out") {
  if (kind === "mic") void core.rtc.reopenMic(); else { core.audio.refresh(); core.sfx.setSink(core.settings.state.value.outputDeviceId); }
}
