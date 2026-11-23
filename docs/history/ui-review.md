# UI review and fixes

## Implemented

- Made the app shell follow the dynamic viewport height and corrected flex sizing and overflow so pages remain usable on small screens.
- Simplified the header, added the current section and a compact account identity, and replaced the account dropdown with keyboard-accessible Radix navigation.
- Replaced the mobile sidebar overlay with a modal drawer supporting focus containment, Escape dismissal, and focus restoration. Added a skip-to-content link.
- Kept every settings category visible below desktop widths with a wrapped grid instead of a scrolling strip, retained import/export on phones, and allowed save actions to wrap.
- Made page headers and actions wrap across characters, roleplay, parties, profile, and map views. Made map tabs follow the selected theme.
- Increased muted-text contrast, darkened light-theme primary colors, corrected markdown link hover color, and repaired the small-text theme token.
- Reduced decorative background intensity and adjusted authentication card padding on small screens.
- Rebuilt shared dialogs using Radix for keyboard focus, nested overlays, scroll locking, unique accessible titles, and bounded mobile-height content. Restored focus to the opening control.
- Linked shared inputs, selects, textareas, profile fields, and settings controls to accessible labels. Made field validation IDs unique and preserved caller-provided descriptions.
- Named password visibility, clear-input, search, and conversation deletion controls. Made conversation selection and deletion separate native buttons, with deletion available on touch and keyboard focus.
- Preserved loading button labels, exposed busy state, and made shared buttons default to non-submitting actions.
- Connected welcome suggestions to the composer, prevented Enter from submitting during IME composition or paused conversations, and constrained the composer reading width.
- Improved composer typography and mobile wrapping; added a reduced-motion override.
- Fixed empty-state icon sizing and action wrapping.
- Moved community lazy imports outside rendering to avoid remounting tab content.
- Gave setting labels a minimum width so a wide select or slider moves to its own line instead of squeezing the label. Notification, schedule, data-format, and theme options now use two columns on phones.
- Wrapped the character editor tabs and added a sticky save bar below 1280 pixels. Card actions moved to the footer, where they are reachable on touch.
- Allowed the group chat header and its action row to wrap, and bounded the conversation settings panel to the viewport height.
- Replaced remaining horizontal filter strips with wrapping rows so every category stays visible. This covers characters, the character selector, alignment, achievements, templates, community search, and admin tabs.
- Made story and template search filters stack below 640 pixels.

## Original accessibility review verification

- TypeScript type checking and production build pass.
- At the time of the original accessibility review, all 133 frontend tests passed. The suite includes regression coverage for field associations, settings control names, loading buttons, dialog focus containment and restoration, Escape behavior, and composition/paused-message handling.
- ESLint reports no errors. Remaining warnings are typing (`any`), hook dependency, and fast-refresh warnings.
- Headless Chromium checks covered chat, characters, roleplay, parties, community, achievements, profile, map, data, settings, and admin at 320, 390, 768, 1024, and 1440 pixels. Page and main-content widths fit their viewports at every size.
- Checked the roleplay and group creators, character editor tabs, roster picker, conversation settings, participants panel, roleplay tools, map editor, relationships and alignment panels, and admin tabs at phone widths.

## Verification notes

The checks ran against the local backend with a live roleplay conversation, a 4 MB world map, 13 characters, and one roster. The admin view was checked with a temporary admin account, removed afterwards.

- Browsers: headless Chromium only. Narrow layouts were checked at 320 and 390 pixels, not on a physical device.
- The text-to-speech server was not running, so voice selection showed its connection error state.
- Password-reset routes still keep their existing placeholder functionality.
- The production build still reports large vendor chunks.

## Header unification

### Implemented

- Replaced the per-page header markup with one shared page header: a parchment
  bar with one hairline, a 40px icon well, a serif page title, a muted
  description, and trailing actions that wrap on narrow screens.
- Moved page tabs into the header bar, under a second hairline, with a 2px
  sienna underline on the active tab.
- Gave the header the same measure and side gutter as the page content, so the
  icon well and the first card share a left edge at every width.
- Removed the duplicated admin page frame and the shell heading that repeated a
  page title. Each route now owns one visible heading, except the chat
  transcript, which takes a screen-reader-only section heading from the shell.
- Replaced the translucent, blurred page header surfaces with parchment and one
  hairline, and dropped synthetic bold from page titles.

### Verification

- TypeScript type checking, the production build, and all 160 frontend tests
  pass.
- Headless Chromium screenshots at 320, 390, and 1440 pixels in the light
  theme, and at 1440 in the dark theme, covered achievements, community,
  characters (list and editor), party, profile, map, test map, data, settings,
  admin, story reader, and both legal pages.
- The automated design detector reports no findings on the changed files.

## Design review pass

### Implemented

- Closed the sunset band across the full shell width, under the conversation
  rail and the main region, instead of starting it at the rail edge.
- Moved transient toasts to the bottom centre so an error can no longer cover a
  page header or its primary action.
- Set the authentication card title in Instrument Serif, the product's
  page-scale voice, and tightened the legal line's wrapping.
- Removed duplicate page-level primary actions from the party and roleplay
  landings; each page now owns one set of entry actions.
- Gave the page-level empty states one construction: a 40px sienna-tinted icon
  well, a 14px semibold title, a 14px muted line held to 448px, and 44px
  actions. The shared `EmptyState` and the settings empty state follow the same
  rules.
- Reworked achievement tiles to rest as white leaves with a sand hairline, with
  the amber tint reserved for unlocked achievements, and restored readable
  14px titles and 12px descriptions.
- Replaced the profile's filled read-only bands with a hairline-separated
  definition list, and dropped resting shadows from its cards.
- Flattened character and roster cards to one hairline at rest, aligned the
  character toolbar to the shared 32px button size, and bounded the roster
  search measure. Raised the shared card radius to 16px so every gallery card
  follows the card step.
- Replaced five copies of a rainbow `GENRE_COLORS` map with one `GenreTag`: the
  same butter label for every genre, with display names such as "Sci-Fi" and
  "Slice of Life".
- Replaced the category palette's blue, green, purple, cyan, and pink with warm
  inks, and removed the coloured tab from the character card that carried them.
- Let the conversation state keep its full label on phones by hiding the turn
  mode below 640px.

### Verification

- TypeScript type checking, the production build, and all 162 frontend tests
  pass.
- Headless Chromium checks covered login and registration, chat, roleplay
  (quick start and templates), characters, party, achievements, profile, map,
  settings, and a live roleplay transcript at 1440 and 390 pixels, in the light
  theme and at 1440 in the dark theme.
- The automated design detector reports no findings on the changed files.

### Open observations

- The settings store and the database still default a new account to the dark
  theme, so the cream palette is not the first surface a new player meets.
- The raymarched 3D world map draws hard-edged pale-blue polygons across the sky
  at region scale; the cause is in `rayMarchShaders.ts`, not the token layer.
- Saving settings produced an "LLMSettingsInput has no field 'stream'" error
  while the page still showed a success banner; the two messages contradict
  each other.
- The roleplay landing offers creation but no list of existing adventures, even
  though the product describes the page as managing them.

## Current interaction verification (2026-11-22)

- The complete frontend suite passes 251 tests, including roleplay narrator gates,
  voice routing, conversation readout, inventory permissions, combat, map scopes
  and direct return to the associated conversation. Typecheck and build pass.
- The map defaults to a 2D local layout, with a separate global 2D/3D scope.
  Narrator mode is shared with chat; disabling it removes open editors and hides
  generation, deletion, manual scene changes, AI internals and history rewriting.
  Public dice and normal player actions remain available.
- Rendered local/global map controls were checked in headless Chromium at 390
  and 1440 pixels with mocked query data, including both narrator states; neither
  layout overflowed horizontally. This does not replace a live multiplayer
  session or physical-device testing.
- Ming is now the voice backend. Speech planning and playback are covered by
  automated tests; the older note above about a disconnected speech server
  describes only the original accessibility review.
