import type { ComponentType } from "react";
import type { Nav, Route } from "../nav";

export interface ScreenProps { nav: Nav; route: Route; back: () => void }
/** Screens by route type; filled in as they are ported. */
export const SCREENS: Partial<Record<Route["t"], ComponentType<ScreenProps>>> = {};
