import { createRoot } from "react-dom/client";
import { core } from "./app/instance";
import { native } from "./core/native";
import { AppRoot, nav } from "./ui/AppRoot";

native.onOpenUrl((url) => { void openLink(url); });
async function openLink(text: string) {
  const card = await core.discovery.cardFromLink(text);
  if (card) core.pendingAdd.value = card; else core.pendingCode.value = text.trim();
}
window.addEventListener("dragover", (e) => e.preventDefault());
window.addEventListener("drop", (e) => {
  e.preventDefault();
  const t = e.dataTransfer?.getData("text/plain");
  if (t) void openLink(t);
});
createRoot(document.getElementById("root")!).render(<AppRoot />);
void core.init();
void nav;
