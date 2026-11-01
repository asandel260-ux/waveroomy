import type { ComponentType } from "react";
import type { Nav, Route } from "../nav";

export interface ScreenProps { nav: Nav; route: Route; back: () => void }
/** Screens by route type; filled in as they are ported. */
import { GroupInfoScreen, NewGroupScreen } from "./Groups";
import { EditProfileScreen, PeopleScreen, ProfileScreen } from "./Social";
import { SettingsScreen } from "./Settings";
import { ModerationScreen } from "./Moderation";
import { AboutScreen, HelpScreen, LegalScreen } from "./Info";
import { ChatScreen } from "./Chat";
import { CreateRoomScreen, InviteScreen, JoinScreen } from "./Connect";
export const SCREENS: Partial<Record<Route["t"], ComponentType<ScreenProps>>> = {
  chat: ChatScreen, moderation: ModerationScreen, help: HelpScreen, legal: LegalScreen, about: AboutScreen, settings: SettingsScreen, profile: ProfileScreen, people: PeopleScreen, editProfile: EditProfileScreen, newGroup: NewGroupScreen, groupInfo: GroupInfoScreen, create: CreateRoomScreen, invite: InviteScreen, join: JoinScreen
};
