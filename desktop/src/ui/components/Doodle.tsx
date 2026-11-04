import { useMemo } from "react";
import { makeScene, type Mix, type Piece } from "../doodle";

export type SceneId = "rooms" | "chats" | "you" | "social" | "create" | "room" | "quiet" | "intro";

/** Every screen floats a different set of drawings. */
const SCENES: Record<SceneId, { seed: number; count: number; mix: Mix; calm?: number }> = {
  rooms: { seed: 11, count: 30, mix: { vine: 3, tube: 3, curls: 2, swirl: 2, leaf: 1, hatch: 1 } },
  chats: { seed: 23, count: 32, mix: { chain: 3, curls: 3, cells: 2, swirl: 2, vine: 1, hatch: 1 } },
  you: { seed: 37, count: 28, mix: { leaf: 3, vine: 2, hatch: 2, swirl: 2, tube: 1, curls: 1 } },
  social: { seed: 41, count: 26, mix: { cells: 3, chain: 2, curls: 2, tube: 1, leaf: 1 } },
  create: { seed: 53, count: 28, mix: { swirl: 3, curls: 3, vine: 2, cells: 1, chain: 1 } },
  room: { seed: 67, count: 22, mix: { vine: 2, tube: 2, swirl: 1, curls: 1, leaf: 1 }, calm: 0.7 },
  quiet: { seed: 71, count: 18, mix: { vine: 3, leaf: 1, swirl: 1, chain: 1 }, calm: 0.7 },
  intro: { seed: 83, count: 24, mix: { vine: 2, tube: 2, curls: 2, swirl: 2, leaf: 1, chain: 1 } },
};
const INK = { green: "var(--doodle)", pale: "var(--doodle-pale)", bright: "var(--doodle-bright)" } as const;

function PieceView({ p }: { p: Piece }) {
  const [x, y, w, h] = p.vb;
  return (
    <div className="piece" style={{ left: `${p.x}%`, top: `${p.y}%`, width: w * p.scale, height: h * p.scale, opacity: 0.35 + p.z * 0.6, animationDuration: `${p.dur}s`, animationDelay: `${p.delay}s`,
      ["--r0" as any]: `${p.r0}deg`, ["--r1" as any]: `${p.r1}deg`, ["--dx" as any]: `${p.dx}vw`, ["--dy" as any]: `${p.dy}vh` }}>
      <svg viewBox={`${x} ${y} ${w} ${h}`} width="100%" height="100%" style={{ animationDuration: `${p.sdur}s`, overflow: "visible" }}>
        <g fill="none" strokeLinecap="round" strokeLinejoin="round">
          {p.strokes.map((s, i) => <path key={i} d={s.d} stroke={INK[s.ink]} strokeWidth={s.w / Math.max(0.7, p.scale * 0.8)} strokeOpacity={s.o} />)}
        </g>
        {p.light && <path d={p.strokes[0].d} className="tracer" pathLength={1000} fill="none" style={{ animationDuration: `${p.light.dur}s`, animationDelay: `${p.light.delay}s` }} />}
      </svg>
    </div>
  );
}

const cache = new Map<string, Piece[]>();
function pieces(id: SceneId, seed = 0, count?: number): Piece[] {
  const key = `${id}:${seed}:${count ?? ""}`;
  let s = cache.get(key);
  if (!s) { const sc = SCENES[id]; s = makeScene(sc.seed + seed, count ?? sc.count, sc.mix, { calm: sc.calm }); cache.set(key, s); }
  return s;
}

/** The app background: drawings drifting about on their own. */
export function DoodleBackdrop({ scene }: { scene: SceneId }) {
  const ps = useMemo(() => pieces(scene), [scene]);
  return <div className="doodle-bg" aria-hidden="true">{ps.map((p, i) => <PieceView key={scene + i} p={p} />)}</div>;
}

/** The drawings for one intro slide, floating inside the panel. */
export function DoodleDraw({ seed }: { seed: number }) {
  const ps = useMemo(() => pieces("intro", seed, 14), [seed]);
  return <div className="doodle-art">{ps.map((p, i) => <PieceView key={seed + ":" + i} p={p} />)}</div>;
}
