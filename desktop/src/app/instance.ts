import { AppCore } from "./core";
import { native } from "../core/native";

/** The one application instance, shared by every screen. */
export const core = Object.assign(new AppCore(), {
  openUrl(url: string) { native.openUrl(url); },
  copy(text: string) { native.copy(text); }
});
