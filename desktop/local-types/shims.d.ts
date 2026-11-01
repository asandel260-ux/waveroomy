// Only used by tsconfig.local.json (offline typecheck without @types packages).
declare module "react" {
  const React: any;
  export default React;
  export type ComponentType<P = any> = (props: P) => any;
  export function useState<T>(init: T | (() => T)): [T, (v: T | ((p: T) => T)) => void];
  export function useState<T = undefined>(): [T | undefined, (v: T | undefined | ((p: T | undefined) => T | undefined)) => void];
  export function useRef<T>(init: null): { current: T | null };
  export function useRef<T>(init: T): { current: T };
  export function useRef<T = undefined>(): { current: T | undefined };
  export function useMemo<T>(f: () => T, deps: any[]): T;
  export function useCallback<T extends (...a: any[]) => any>(f: T, deps: any[]): T;
  export function useEffect(f: () => void | (() => void), deps?: any[]): void;
  export function useLayoutEffect(f: () => void | (() => void), deps?: any[]): void;
  export function useSyncExternalStore<T>(sub: (cb: () => void) => () => void, snap: () => T): T;
  export const useReducer: any, useContext: any, createContext: any, Fragment: any, memo: any, forwardRef: any, useId: any, useImperativeHandle: any;
  export type ReactNode = any;
  export type CSSProperties = any;
  export type MouseEvent<T = any> = any;
  export type ChangeEvent<T = any> = any;
  export type KeyboardEvent<T = any> = any;
  export type PointerEvent<T = any> = any;
  export type WheelEvent<T = any> = any;
  export type DragEvent<T = any> = any;
  export type FormEvent<T = any> = any; type ClipboardEvent<T = any> = any;
  export type Ref<T = any> = any; type ComponentType<P = any> = (props: P) => any;
  export type RefObject<T = any> = any;
}
declare namespace React {
  type CSSProperties = any; type ReactNode = any; type MouseEvent<T = any> = any; type ChangeEvent<T = any> = any;
  type KeyboardEvent<T = any> = any; type PointerEvent<T = any> = any; type WheelEvent<T = any> = any; type DragEvent<T = any> = any;
  type Ref<T = any> = any; type RefObject<T = any> = any; type FormEvent<T = any> = any; type ClipboardEvent<T = any> = any;
}
declare module "react/jsx-runtime" { export const jsx: any; export const jsxs: any; export const Fragment: any; }
declare module "react-dom" { export const createPortal: any; const x: any; export default x; }
declare module "react-dom/client" { export const createRoot: any; }
declare module "qrcode" { const x: any; export default x; }
declare module "jsqr" { const x: any; export default x; }
declare namespace JSX { interface IntrinsicElements { [k: string]: any } interface Element {} interface IntrinsicAttributes { key?: string | number | null } interface ElementChildrenAttribute { children: {} } }
