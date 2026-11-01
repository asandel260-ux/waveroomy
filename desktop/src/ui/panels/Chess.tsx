import type { Participant } from "../../core/models";
export function ChessChallengeCard({ gameId, text, onOpen }: { gameId: string; text: string; onOpen: () => void }) { void gameId; void onOpen; return <div>{text}</div>; }
export function ChessPanel(_: { land: boolean; focus: boolean; setFocus: (b: boolean) => void; onExit: () => void; onCreate: () => void }) { return <div />; }
export function ChessCreateSheet(_: { onDismiss: () => void; onCreated: () => void }) { return null; }
