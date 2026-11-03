import { useMemo } from "react";
import { makeDoodle } from "../doodle";

/** The app-wide background: green line work on purple. Static, behind everything, and never takes a click. */
export function DoodleBackdrop() {
  const d = useMemo(() => makeDoodle(20261103, 1600, 1000, { vines: 9, spirals: 24, loops: 16, hatches: 5, leaves: 5, dots: 30 }), []);
  return (
    <svg className="doodle-bg" aria-hidden="true" viewBox="0 0 1600 1000" preserveAspectRatio="xMidYMid slice">
      <g fill="none" stroke="var(--doodle)" strokeLinecap="round" strokeLinejoin="round">
        {d.strokes.map((s, i) => <path key={i} d={s.d} strokeWidth={s.w} strokeOpacity={s.o} />)}
      </g>
      {d.dots.map((p, i) => <circle key={i} cx={p.x} cy={p.y} r={p.r} fill="var(--doodle)" className="firefly" style={{ animationDelay: `${(i % 7) * 0.6}s` }} />)}
    </svg>
  );
}

/** A doodle that draws itself, used on the intro tour. A new seed gives a new picture. */
export function DoodleDraw({ seed, w = 900, h = 700 }: { seed: number; w?: number; h?: number }) {
  const d = useMemo(() => makeDoodle(seed, w, h, { vines: 5, spirals: 7, loops: 6, hatches: 2, leaves: 3, dots: 14 }), [seed, w, h]);
  return (
    <svg className="doodle-draw" aria-hidden="true" viewBox={`0 0 ${w} ${h}`} preserveAspectRatio="xMidYMid slice" style={{ position: "absolute", inset: 0, width: "100%", height: "100%" }}>
      <g fill="none" stroke="var(--doodle)" strokeLinecap="round" strokeLinejoin="round">
        {d.strokes.map((s, i) => <path key={i} d={s.d} strokeWidth={s.w} strokeOpacity={s.o} pathLength={1} style={{ ["--delay" as any]: `${(i / d.strokes.length) * 1.6}s` }} />)}
      </g>
      {d.dots.map((p, i) => <circle key={i} cx={p.x} cy={p.y} r={p.r} fill="var(--doodle)" className="firefly" style={{ animationDelay: `${(i % 7) * 0.6}s` }} />)}
    </svg>
  );
}
