import { useEffect, useRef, useState } from "react";
import { Button } from "../components/kit";
import { DoodleDraw } from "../components/Doodle";

/* The first thing a new person sees: a short tour on the left, and on the right a field of dots that moves the way the app does (voices as ripples, people as nodes). */

interface Slide { title: string; text: string; mode: number }
const SLIDES: Slide[] = [
  { title: "Welcome to HoMit", text: "Voice rooms that run straight between your devices. A short look around, then you make your identity.", mode: 0 },
  { title: "Talk in a room", text: "Start a room and share its code or QR. People on the same Wi-Fi see it appear without any code at all.", mode: 1 },
  { title: "Do things together", text: "Watch a video in sync, draw on a shared board, browse the same page or play chess while you talk.", mode: 2 },
  { title: "No servers in the middle", text: "Voice, chat and files go directly from one device to the next. There is no account to sign up for.", mode: 3 },
  { title: "One identity, two devices", text: "Use the same identity on your phone and on this computer. Your contacts and profile stay in step.", mode: 4 },
];

export function WelcomeScreen({ onStart }: { onStart: () => void }) {
  const [i, setI] = useState(0);
  const last = i === SLIDES.length - 1;
  const s = SLIDES[i];
  const go = (n: number) => setI(Math.max(0, Math.min(SLIDES.length - 1, n)));
  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLElement && /INPUT|TEXTAREA/.test(e.target.tagName)) return;
      if (e.key === "ArrowRight") setI((n) => Math.min(SLIDES.length - 1, n + 1));
      if (e.key === "ArrowLeft") setI((n) => Math.max(0, n - 1));
    };
    window.addEventListener("keydown", key); return () => window.removeEventListener("keydown", key);
  }, []);
  return (
    <div className="intro" role="region" aria-roledescription="carousel" aria-label="Introduction to HoMit">
      <div className="intro-top"><Button small kind="ghost" text="Skip" onClick={onStart} /></div>
      <div className="intro-card">
        <div className="intro-text" key={i} aria-live="polite">
          <div className="tiny">{i + 1} of {SLIDES.length}</div>
          <h1 className="display">{s.title}</h1>
          <p>{s.text}</p>
          <div className="hstack g8" style={{ marginTop: 28 }}>
            {i > 0 && <Button kind="secondary" icon="arrow-left" text="Back" onClick={() => go(i - 1)} />}
            {last ? <Button kind="ok" text="Create my identity" onClick={onStart} /> : <Button text={i === 0 ? "Take a quick look" : "Next"} onClick={() => go(i + 1)} />}
          </div>
        </div>
        <div className="intro-art">
          <DoodleDraw key={i} seed={7700 + i * 31} />
          <img src="./icon.png" alt="HoMit app icon" width={164} height={164} draggable={false} />
        </div>
      </div>
      <div className="intro-dots" role="group" aria-label="Choose a step">
        {SLIDES.map((x, n) => <button key={n} onClick={() => go(n)} aria-label={`Step ${n + 1}: ${x.title}`} aria-current={n === i} className={n === i ? "on" : ""}><span /></button>)}
      </div>
    </div>
  );
}
