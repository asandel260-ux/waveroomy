import { useEffect, useState, type ReactNode } from "react";
import { core } from "../app/instance";
import { native } from "../core/native";
import { Nav, type Route } from "./nav";
import { useFlow, useEmitter } from "./hooks";
import { avatarFrom } from "../core/models";
import { applyTheme } from "./theme";
import { Avatar, Button, Dialog, Icon, ScreenHeader, ToastHost, Backdrop, Muted } from "./components/kit";
import { WelcomeScreen } from "./screens/Welcome";
import { Onboarding } from "./screens/Onboarding";
import { HomeScreen } from "./screens/Home";
import { SCREENS } from "./screens/registry";

export const nav = new Nav();
let welcomed = false;
try { welcomed = localStorage.getItem("homit.welcomed") === "1"; } catch { /* ignore */ }

function TitleBar() {
  const [max, setMax] = useState(false);
  useEffect(() => { native.win.isMaximized().then(setMax); native.win.onState(setMax); }, []);
  return (
    <header className="titlebar">
      <div className="brand"><Icon name="waveform" size={16} />HoMit</div>
      <div className="grow" />
      <button className="wc" onClick={() => native.win.minimize()} title="Minimize" aria-label="Minimize"><Icon name="minus" size={16} /></button>
      <button className="wc" onClick={() => native.win.maximize()} title={max ? "Restore" : "Maximize"} aria-label={max ? "Restore" : "Maximize"}><Icon name={max ? "restore" : "square"} size={14} /></button>
      <button className="wc close" onClick={() => native.win.close()} title="Close" aria-label="Close"><Icon name="x" size={16} /></button>
    </header>
  );
}

function Sidebar({ route }: { route: Route }) {
  const tab = useFlow(nav.homeTab);
  const convs = useFlow(core.dms.convs);
  const phase = useFlow(core.session.phase);
  const reqs = useFlow(core.discovery.requests);
  const p = useFlow(core.profile.profile);
  const unread = Object.values(convs).reduce((n, c) => n + (c.muted ? 0 : c.unread), 0) + reqs.length;
  const home = route.t === "home" && nav.stack.value.length === 1;
  const item = (i: number, icon: string, label: string, badge = 0) => (
    <button className={`nav ${home && tab === i ? "on" : ""}`} onClick={() => nav.goHome(i)} title={label}>
      <Icon name={icon} size={22} />{label}
      {badge > 0 && <span className="unread badge">{badge > 99 ? "99+" : badge}</span>}
    </button>
  );
  return (
    <nav className="sidebar" aria-label="Main">
      {item(0, "waveform", "Rooms")}
      {item(1, "message", "Chats", unread)}
      {item(2, "user", "You")}
      <div className="sp" />
      {phase !== "idle" && (
        <button className={`nav ${route.t === "room" ? "on" : ""}`} onClick={() => nav.push({ t: "room" })} title="Back to room">
          <Icon name="radio" size={22} color="var(--ok)" />Live
        </button>
      )}
      {p && <button onClick={() => nav.goHome(2)} title={p.displayName} aria-label={`Your profile, ${p.displayName}`} style={{ height: 56, display: "grid", placeItems: "center" }}><Avatar avatar={p.avatar} size={32} mine /></button>}
    </nav>
  );
}

function Stub({ name, onBack }: { name: string; onBack: () => void }) {
  return <Backdrop><ScreenHeader title={name} onBack={onBack} /><div style={{ padding: 24 }}><Muted>Coming soon.</Muted></div></Backdrop>;
}

function Screen({ route }: { route: Route }): ReactNode {
  const back = () => { if (!nav.pop()) nav.resetTo({ t: "home" }); };
  if (route.t === "home") return <HomeScreen nav={nav} />;
  const C = SCREENS[route.t];
  return C ? <C nav={nav} route={route} back={back} /> : <Stub name={route.t} onBack={back} />;
}

/** Dialogs that can appear on top of anything: incoming calls, room ended, join failed, codes opened from outside. */
function Globals() {
  const call = useFlow(core.dmHub.incomingCall);
  const ended = useFlow(core.session.endedReason);
  const joinErr = useFlow(core.session.joinError);
  const add = useFlow(core.pendingAdd);
  const code = useFlow(core.pendingCode);
  useEffect(() => { if (code) { core.pendingCode.value = null; nav.push({ t: "join", code }); } }, [code]);
  return (
    <>
      {call && (
        <Dialog title="Incoming call" onDismiss={() => core.dmHub.declineCall()} actions={<><Button kind="ghost" text="Decline" onClick={() => core.dmHub.declineCall()} /><Button text="Answer" icon="phone" onClick={() => { core.requestMic(); core.dmHub.acceptCall(); nav.push({ t: "room" }); }} /></>}>
          <div style={{ fontSize: 15 }}>{call.title} is calling…</div>
        </Dialog>
      )}
      {ended && <Dialog title="Room ended" onDismiss={() => { core.session.endedReason.value = null; }} actions={<Button text="OK" onClick={() => { core.session.endedReason.value = null; }} />}><div>{ended}</div></Dialog>}
      {joinErr && <Dialog title="Couldn't join" onDismiss={() => { core.session.joinError.value = null; }} actions={<Button text="OK" onClick={() => { core.session.joinError.value = null; }} />}><div>{joinErr}</div></Dialog>}
      {add && (
        <Dialog title={`Add ${add.name}?`} onDismiss={() => { core.pendingAdd.value = null; }} actions={<><Button kind="ghost" text="Not now" onClick={() => { core.pendingAdd.value = null; }} /><Button text="Send request" onClick={() => { void core.discovery.sendRequest(add); core.pendingAdd.value = null; }} /></>}>
          <div className="hstack g12"><Avatar avatar={avatarFrom(add.avatar)} size={46} /><div><div style={{ fontWeight: 600 }}>{add.name}</div><Muted>@{add.username}</Muted></div></div>
        </Dialog>
      )}
    </>
  );
}

export function AppRoot() {
  const ready = useFlow(core.ready);
  const profile = useFlow(core.profile.profile);
  const stack = useFlow(nav.stack);
  const settings = useFlow(core.settings.state);
  const phase = useFlow(core.session.phase);
  const [intro, setIntro] = useState(!welcomed);
  useEffect(() => { native.setPrefs({ closeToTray: settings.closeToTray, startMinimized: settings.startMinimized, notifications: settings.notifications }); }, [settings.closeToTray, settings.startMinimized, settings.notifications]);
  useEffect(() => { applyTheme(settings); }, [settings.theme, settings.accentColor, settings.bgColor, settings.animations]);
  useEffect(() => { if (settings.theme !== "system") return; const mq = window.matchMedia("(prefers-color-scheme: light)"); const f = () => applyTheme(core.settings.state.value); mq.addEventListener("change", f); return () => mq.removeEventListener("change", f); }, [settings.theme]);
  useEffect(() => { if (phase === "joining" || phase === "live") { if (nav.top.t !== "room") nav.push({ t: "room" }); } else nav.removeAll("room"); }, [phase]);
  useEmitter(core.session.toasts, (t) => core.toast(t));
  const route = stack[stack.length - 1];

  let body: ReactNode;
  if (!ready) body = <Backdrop css={{}} />;
  else if (!profile) body = <main style={{ flex: 1, minHeight: 0, display: "flex", flexDirection: "column" }}>{intro ? <WelcomeScreen onStart={() => { welcomed = true; try { localStorage.setItem("homit.welcomed", "1"); } catch { /* ignore */ } setIntro(false); }} /> : <Onboarding />}</main>;
  else body = (
    <div style={{ flex: 1, display: "flex", minHeight: 0 }}>
      {route.t !== "room" && <Sidebar route={route} />}
      <main style={{ flex: 1, minWidth: 0, display: "flex", flexDirection: "column" }} key={route.t + ("uid" in route ? route.uid : "")}>
        <Screen route={route} />
      </main>
    </div>
  );
  return (
    <div style={{ position: "fixed", inset: 0, display: "flex", flexDirection: "column", background: "var(--bg)" }}>
      <TitleBar />
      {body}
      {profile && <Globals />}
      <ToastHost />
    </div>
  );
}
