import { useEffect, useRef, useSyncExternalStore } from "react";
import type { Emitter, Flow } from "../core/util";

/** Subscribe a component to a Flow. */
export function useFlow<T>(f: Flow<T>): T {
  return useSyncExternalStore(f.subscribe, f.snap);
}

export function useEmitter<T>(e: Emitter<T>, cb: (v: T) => void) {
  const ref = useRef(cb);
  ref.current = cb;
  useEffect(() => e.on((v) => ref.current(v)), [e]);
}

/** Re-render whenever the room mirror changes. */
export function useTick(f: Flow<number>): number {
  return useSyncExternalStore(f.subscribe, f.snap);
}
