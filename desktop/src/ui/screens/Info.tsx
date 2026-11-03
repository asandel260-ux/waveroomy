import type { ReactNode } from "react";
import { Backdrop, ScreenHeader } from "../components/kit";
import type { ScreenProps } from "./registry";

function Page({ title, back, children }: { title: string; back: () => void; children: ReactNode }) {
  return (
    <Backdrop style={4}>
      <ScreenHeader title={title} onBack={back} />
      <div className="scroll"><div style={{ maxWidth: 720, margin: "0 0 0 max(0px, calc((100% - 880px) / 2))", padding: "24px 32px" }}>{children}</div></div>
    </Backdrop>
  );
}
const H = ({ t }: { t: string }) => <div className="display" style={{ fontSize: 17, fontWeight: 700, margin: "18px 0 6px" }}>{t}</div>;
const P = ({ t }: { t: string }) => <p style={{ color: "var(--muted)", fontSize: 14, lineHeight: "21px", margin: "0 0 6px" }}>{t}</p>;

export function HelpScreen({ back }: ScreenProps) {
  return (
    <Page title="Help" back={back}>
      <H t="How does it work without a server?" />
      <P t="HoMit connects devices directly to each other (peer to peer). There is no account system and no server relaying your voice, chat or screen. A room is simply the group of devices that are connected to each other." />
      <H t="Starting and joining" />
      <P t="Start a room, then use the invite button to add someone. Friends in your list can join straight away; anyone else can scan the QR code or paste a code (on different networks they may also show you a reply code). The two devices link up directly. After that, anyone already in the room can invite more people. New people connect to everyone through the people already there." />
      <P t="On the same Wi-Fi, rooms appear under “Nearby” and you can join with a single click. No codes." />
      <H t="Phone and computer in one account" />
      <P t="Export an identity backup on one device and restore it on the other to be the same person on both. Then use Settings → Your devices to link them so chats, contacts and rooms stay in step." />
      <H t="The host" />
      <P t="The creator is the host. If the host leaves or loses connection, the person who has been in the room longest takes over automatically and the room carries on. Hosts can move people to and from the stage, mute, remove and ban people, lock the room and end it." />
      <H t="Different networks" />
      <P t="Two devices on different networks usually connect through public STUN servers (enabled by default in Settings → Network). Some strict networks block direct connections; in that case a TURN relay you run yourself can be added in Settings." />
      <H t="Size of a room" />
      <P t="Everyone connects to everyone, so each extra person adds load. Voice works well up to about 8–10 people; screen sharing is best with a few viewers. Up to 24 people can join." />
      <H t="Finding and adding people" />
      <P t="Use the add-person button in Messages. Search a friend's @username, or paste their link, and send a request. When they accept, your devices are friends and reconnect on their own whenever you are both online. On the same Wi-Fi or across the internet. With no codes. You can limit who can find you in Settings → Privacy." />
      <P t="Reconnecting across networks uses a public message board (ntfy.sh by default) for tiny encrypted notes that only you and your friend can read. You can turn this off or point it at another board in Settings → Network." />
      <H t="Messages" />
      <P t="Chats are saved on your computer. When your friend is online the message is delivered at once; otherwise it waits and is sent as soon as you are both online. Groups catch up whenever two members connect." />
      <H t="Shared browser and Watch Together" />
      <P t="Everyone in the room loads the same page or YouTube video on their own device; only the page address and playback position are shared. Your logins and cookies never leave your computer." />
      <H t="Screen sharing" />
      <P t="Pick a screen or a window and it is sent live to everyone in the room. On Windows you can also share that system's sound." />
      <H t="Backups" />
      <P t="Your identity is a key that exists only on your devices. Export a backup in Settings → Identity & data. Anyone with the file can act as you, so keep it private." />
    </Page>
  );
}

export function LegalScreen({ back }: ScreenProps) {
  return (
    <Page title="Privacy & terms" back={back}>
      <H t="Privacy" />
      <P t="HoMit has no servers and collects nothing. Your profile, contacts, messages and room history are stored only on your devices. When you connect to someone, they receive your display name, username, avatar, bio and photo. That is how they see you." />
      <P t="Voice, chat, screen and board content flows directly between participants and is encrypted in transit by WebRTC. People in a room you join can record or screenshot what they receive; only share what you are comfortable sharing with them." />
      <P t="If STUN is enabled, your computer contacts public STUN servers (Google and Cloudflare by default) to discover its public network address. They learn that address and nothing else. Disable it in Settings → Network to stay on the local network only." />
      <P t="The shared browser and Watch Together load websites and YouTube directly on your computer, subject to those sites' own terms and privacy policies." />
      <H t="Use" />
      <P t="Be kind. Don't use HoMit to harass people, share illegal content, or violate others' privacy. Room hosts moderate their own rooms: they can mute, remove and ban people. There is no central authority that can see or remove content." />
      <P t="HoMit is provided as is, without warranty. You are responsible for what you share." />
    </Page>
  );
}

export function AboutScreen({ back }: ScreenProps) {
  return (
    <Page title="About" back={back}>
      <H t="HoMit for Windows" />
      <P t="Server-free voice rooms: stage and audience, hand-raising, room chat and reactions, direct messages, screen sharing, Watch Together, a shared browser, a shared infinite blackboard, chess and voice effects. All peer to peer, and compatible with HoMit on Android." />
      <H t="Built with" />
      <P t="Electron, React and the browser's WebRTC, with qrcode and jsQR for codes, and the Lucide icon set." />
      <H t="Design" />
      <P t="Violet and neon green, in a dark or a light theme. Plain type, clear buttons." />
    </Page>
  );
}
