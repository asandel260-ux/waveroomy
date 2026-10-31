import type { J } from "../core/util";

export type CodeResult =
  /** We accepted an invite: show [code] to the inviter so they can finish the link. */
  | { kind: "answer"; code: string; from: string; title: string; purpose: string; auto: boolean }
  /** We applied an answer to one of our invites: the link is coming up. */
  | { kind: "applied" }
  | { kind: "error"; msg: string };

export interface Card { uid: string; pub: string; username: string; name: string; avatar: J; ts: number; signed: boolean; sig: string }
