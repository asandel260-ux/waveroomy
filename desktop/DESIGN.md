# HoMit for Windows: design direction

Direction given by the owner: keep the violet and green atmosphere of HoMit. Layout, ordering, theming and the previous look are free to change. The app is built against the 40 rules of antislop (R-01 to R-40); every major decision below has a one-line reason (R-31).

**Reading this as:** a desktop voice-room and messaging app for friends who talk while gaming, streaming and working, in a warm, human, low-chrome style, dial **ENERGY 2 / RHYTHM 2 / MOTION 1**.

## Owner override (R-37, R-01)

R-01 names purple-and-black and neon palettes as slop *defaults*. Here they are the established identity of the HoMit phone app and the owner asked to keep them, so they stay. They are used with restraint: violet is structure, green is one signal.

## Palette (R-29: 2 core colours + 1 accent, plus neutrals)

| Role | Dark | Light | Reason |
|---|---|---|---|
| Ink (neutral base) | `#0B0713` bg, `#120D1D` / `#191225` / `#231A34` surfaces | `#FAF9FD` bg, `#FFFFFF` / `#F3F0FA` / `#EAE5F6` | Tinted neutrals carry the violet atmosphere without any gradient or glow. |
| Core 1: violet | `#7C3AED` fills, `#B690FF` text and links | `#6D28D9` fills and text | Violet means "yours to act on": primary buttons, selection, links, focus. |
| Core 2: soft lilac | `#2C2142` borders, `#44346B` strong borders | `#DDD6EE`, `#BFB3DC` | Separates surfaces so shadows are not needed. |
| Accent: live green | `#52FF1F` | `#1F9D0A` | Green appears only where audio is flowing: speaking ring, live mic level, connected dot, the Live tab. Everywhere else it is absent, so it reads as a signal. |
| Danger | `#FF6B6B` | `#C62828` | Leave, mute-all, delete. |

## Theme (R-21, R-34)

Dark is the default because rooms are used next to games, streams and shared screens in dim rooms. A Light and a System option exist in Settings and both are verified (contrast and layout).

## Type (R-06)

- Display: **Caladea** (bundled). The phone app already uses it; its warm serif voice suits conversation, which is the product. Used for page titles, room names and the wordmark only.
- Text and UI: **Segoe UI Variable** (system font on Windows). Native rendering, tuned for small sizes, no download. Not the Inter/Geist default roster.
- No monospace headings. No uppercase tracked labels; labels are sentence case.

## Shape and depth

- Radii (R-11): 6px controls and inputs, 10px panels and dialogs, 14px bottom sheets, 4px badges, circle only for people. Never pill-wide.
- Shadow (R-12): only on things that float above the page (menus, dialogs, toasts) to mark elevation. All other surfaces are flat and separated by borders.
- Glow (R-13): max two elements: the live mic button and the speaking person's ring. Nothing else glows.
- Glass (R-10): none.
- Gradients (R-01): none. Hierarchy comes from size, weight and colour.
- Dot grid (R-07): only on the blackboard canvas, where it is a spatial reference for drawing, not decoration.

## Icons (R-04)

The same icon set as the phone app, so both apps read alike. Every glyph is the literal object (mic, headphones, monitor, message, lock). No sparkle, star, bolt or orb icons. Emoji appear only as users' own avatar and reactions, never as UI decoration.

## Layout (R-05, R-14, C-3)

- Each screen starts from the job it supports. Rooms: get into a conversation (nearby rooms and rooms you can reopen are the page). Messages: continue a conversation. You: profile and settings.
- No hero card, no feature grid, no stat row, no three-step explainer. Lists are rows with the deciding facts in columns.
- A 4px base unit; section gaps are 24 / 40 / 64 so related things sit close and unrelated things sit far.
- One focal point per screen: the primary action or the live conversation.

## Motion (R-19, R-39)

MOTION 1: state changes only. 120 to 180ms ease-out transitions on hover, focus, tab changes and dialogs; the speaking ring fades in and out with voice; reactions float up once when sent; spinners only while loading. No looping decoration. `prefers-reduced-motion` and the in-app Animations switch both stop non-essential motion.

## Copy (R-02, R-15, R-16)

No em dashes. Buttons name the action ("Create my identity", "New room", "Join with a code"). No buzzwords. No invented names, numbers or people: empty fields carry honest placeholders ("Your name").

## States (R-27)

Every list has an empty state that says why it is empty and what to do, a loading state that names what is loading, and an error state that names what failed and what to try.
