---
name: Veyloria
description: A warm paper workspace for an AI-run fantasy world that keeps its history.
colors:
  paper-cream: "#fffaeb"
  parchment: "#fff8e0"
  white-leaf: "#ffffff"
  soft-ink: "#1f1f1f"
  weathered-olive: "#605643"
  sand-rule: "#e6d5a8"
  aged-brass: "#af9664"
  field-brown: "#8b7a55"
  sienna-ink: "#962a03"
  ember-deep: "#822503"
  coffee-brown: "#654020"
  roasted-bark: "#523319"
  butter-tint: "#fff0c2"
  brick-red: "#a31f1f"
  brick-deep: "#961d1d"
  deep-pine: "#106546"
  umber-warning: "#784b08"
  signal-ember: "#fa520f"
  sunset-rust: "#cc3a05"
  tangerine: "#ff8105"
  honey-gold: "#ffa110"
  lamp-cream: "#ffd06a"
  sunshine-amber: "#ffb83e"
  pigment-blue: "#1252ba"
  pigment-violet: "#532caf"
  pigment-magenta: "#a51d4a"
  pigment-teal: "#107064"
  pigment-burnt: "#97440c"
  pigment-purple: "#77289f"
  night-surface: "#23221f"
  night-card: "#2b2a26"
  night-border: "#4c4538"
  night-muted-ink: "#c1b7a4"
  lamp-amber: "#ffb83d"
typography:
  display:
    fontFamily: "Instrument Serif, Iowan Old Style, Georgia, serif"
    fontSize: "2.25rem"
    fontWeight: 400
    lineHeight: 1
    letterSpacing: "0.015em"
  headline:
    fontFamily: "Instrument Serif, Iowan Old Style, Georgia, serif"
    fontSize: "1.5rem"
    fontWeight: 400
    lineHeight: 1.25
    letterSpacing: "0.015em"
  title:
    fontFamily: "Inter, system-ui, -apple-system, Segoe UI, Roboto, sans-serif"
    fontSize: "1rem"
    fontWeight: 600
    lineHeight: 1.5
  body:
    fontFamily: "Inter, system-ui, -apple-system, Segoe UI, Roboto, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 400
    lineHeight: 1.5
  control:
    fontFamily: "Inter, system-ui, -apple-system, Segoe UI, Roboto, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 500
    lineHeight: 1.25
  label:
    fontFamily: "Inter, system-ui, -apple-system, Segoe UI, Roboto, sans-serif"
    fontSize: "0.75rem"
    fontWeight: 500
    letterSpacing: "0.05em"
  mono:
    fontFamily: "JetBrains Mono, SF Mono, Menlo, Monaco, Consolas, monospace"
    fontSize: "0.8125rem"
    lineHeight: 1.6
rounded:
  xs: "4px"
  sm: "6px"
  md: "8px"
  lg: "12px"
  xl: "16px"
  2xl: "16px"
  pill: "9999px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "16px"
  lg: "24px"
  xl: "32px"
components:
  button-primary:
    backgroundColor: "{colors.sienna-ink}"
    textColor: "{colors.white-leaf}"
    typography: "{typography.control}"
    rounded: "{rounded.lg}"
    padding: "0 16px"
    height: "44px"
  button-primary-hover:
    backgroundColor: "{colors.ember-deep}"
    textColor: "{colors.white-leaf}"
    rounded: "{rounded.lg}"
  button-outline:
    backgroundColor: "{colors.paper-cream}"
    textColor: "{colors.soft-ink}"
    typography: "{typography.control}"
    rounded: "{rounded.lg}"
    padding: "0 16px"
    height: "44px"
  button-ghost:
    backgroundColor: "{colors.paper-cream}"
    textColor: "{colors.weathered-olive}"
    rounded: "{rounded.lg}"
    size: "40px"
  input-text:
    backgroundColor: "{colors.paper-cream}"
    textColor: "{colors.soft-ink}"
    typography: "{typography.body}"
    rounded: "{rounded.xl}"
    padding: "0 12px"
    height: "44px"
  card-default:
    backgroundColor: "{colors.white-leaf}"
    textColor: "{colors.soft-ink}"
    rounded: "{rounded.xl}"
    padding: "16px"
  message-user:
    backgroundColor: "{colors.sienna-ink}"
    textColor: "{colors.white-leaf}"
    typography: "{typography.body}"
    rounded: "{rounded.2xl}"
    padding: "12px 16px"
  message-character:
    backgroundColor: "{colors.white-leaf}"
    textColor: "{colors.soft-ink}"
    typography: "{typography.body}"
    rounded: "{rounded.2xl}"
    padding: "12px 16px"
  narration-block:
    backgroundColor: "{colors.paper-cream}"
    textColor: "{colors.soft-ink}"
    typography: "{typography.body}"
    rounded: "{rounded.lg}"
    padding: "12px 16px"
  nav-link-active:
    backgroundColor: "{colors.butter-tint}"
    textColor: "{colors.soft-ink}"
    typography: "{typography.control}"
    rounded: "{rounded.lg}"
    padding: "10px 12px"
    height: "44px"
---

# Design System: Veyloria

## Overview

**Creative North Star: "The Sunset Manuscript"**

The product reads as one manuscript kept on a warm desk. Paper sits under every
screen: a cream page, white leaves for cards, and hairline rules in sand that
separate columns the way a ruled margin separates notes. A six-stop sunset band
closes the frame at the bottom edge and marks the wordmark, and it does that work
as a fixed piece of chrome rather than as paint on any interactive surface.

The interface is a workspace before it is a conversation. Density, alignment, and
scanability carry the day, and expression lives in pigment, in the serif that
names a page, and in the two semantic tints that separate speech from action. The
palette stays warm across every surface: hue 16 through 48 for paper, ink, and
rules, with saturated cool hues reserved for the eight participant pigments that
mark identity in group scenes.

Material behavior is flat. A card rests on the page as a white leaf with one sand
hairline, and a shadow appears only to mark a raised overlay or a state change.
Type carries two voices: Instrument Serif speaks at page scale in a single roman
weight, and Inter carries every control, label, and line of body copy.

The light palette is the reference. The settings store still ships `dark` as its
default, so a new account meets the inverted palette first; the cream page is what
the system is drawn against.

**Key Characteristics:**
- Cream paper ground with white card leaves and 1px sand rules.
- Deep sienna as the only ink for actions, links, and focus.
- A six-stop sunset band at the shell edge and a five-bar brand mark as the only
  ornamental geometry.
- Flat surfaces that lift on hover and float only as overlays.
- Instrument Serif at page scale, Inter everywhere inside the work.
- A fixed frame: header, conversation rail, transcript, and an optional tool
  panel.

## Colors

One warm family runs from paper through ink, and one deep sienna carries every
action; the saturated sunset pigments stay in decoration and identity.

### Primary
- **Burnt Sienna Ink** (#962a03): The action color. It fills primary buttons,
  colors links, sets the focus ring, and draws the 3px inset bar on the active
  navigation link. It measures 7.65:1 on paper cream, 7.99:1 on white, and 7.03:1
  on the butter tint, so it carries small text and state marks anywhere.
- **Ember Deep** (#822503): The pressed and hovered state of that ink.
- **Signal Ember** (#fa520f): The brand orange. It appears in the wordmark
  underscore, in the five-bar brand mark, and as the second stop of the sunset
  band. It measures 3.21:1 on paper cream, so it never carries text.

### Secondary
- **Coffee Brown** (#654020): Secondary buttons, and the gradient tile behind a
  character avatar in group chat.
- **Roasted Bark** (#523319): The hovered state of coffee brown.

### Tertiary
- **Sunset Rust** (#cc3a05), **Signal Ember** (#fa520f), **Tangerine** (#ff8105),
  **Honey Gold** (#ffa110), **Lamp Cream** (#ffd06a), **Butter Tint** (#fff0c2):
  The six stops of the sunset band, in order. The band runs as a 4px strip at the
  bottom of the app shell and an 8px strip at the foot of the navigation drawer.
  Tangerine also paints the two inner bars of the five-bar brand mark.
- **Sunshine Amber** (#ffb83e): The two outer bars of the five-bar brand mark, and
  the near match of the dark-theme action ink, Lamp Amber.

### Neutral
- **Paper Cream** (#fffaeb): The page ground, the composer fill, and every input
  fill.
- **Parchment** (#fff8e0): The header, the navigation rail, and the conversation
  rail; a half step deeper than the page.
- **White Leaf** (#ffffff): Cards, popovers, dialogs, and character message
  bubbles.
- **Soft Ink** (#1f1f1f): Body text, headings, and the dark-theme background.
- **Weathered Olive** (#605643): Secondary text, timestamps, meta counts, and
  placeholder ink. It measures 6.92:1 on paper cream.
- **Sand Rule** (#e6d5a8): The default 1px border on every card, input, panel, and
  divider.
- **Aged Brass** (#af9664): The hovered border, and the footer rule of a card.
- **Field Brown** (#8b7a55): The resting border of an input and the composer.

### Status
- **Brick Red** (#a31f1f) and **Brick Deep** (#961d1d): Destructive actions and
  failed messages. Hover darkens.
- **Deep Pine** (#106546): Success, and the online dot on an avatar.
- **Umber Warning** (#784b08): Warning text, the interjection badge, and the
  generating dot.

### Participant Pigments
Eight hues mark speakers in a group scene, paired with a 96% tint of the same hue
for the bubble fill. Blue (#1252ba), violet (#532caf), magenta (#a51d4a), teal
(#107064), burnt (#97440c), and purple (#77289f) are unique to the set; the third
and fourth slots reuse deep pine and umber warning. A participant keeps the same
pigment for their avatar tile, name, and bubble for the whole conversation.

### Dark Theme
The dark theme inverts paper and ink and keeps the same roles. Background #1f1f1f,
parchment #23221f, card #2b2a26, border #4c4538, muted ink #c1b7a4. The action ink
becomes **Lamp Amber** (#ffb83d), which lightens on hover, and the ink on it is
soft ink. The pigment set lifts to 64–84% lightness so it stays legible on
charcoal.

### Named Rules
**The Pigment and Ink Rule.** Saturated sunset hues are pigment; deep sienna is
ink. Text and icons take sienna. A pigment may paint a bar or a band, but it never
carries a word: signal ember fails AA against paper cream.

**The Sunset Edge Rule.** The six-stop band appears only at the shell edge, in the
navigation drawer's foot, and inside the five-bar mark. It never fills a button, a
card, an input, or a heading.

**The One Warm Axis Rule.** Surface, border, and muted ink stay within hue 16–48.
Cool grays enter only through the participant pigments. No surface uses a fixed
`zinc`, `slate`, or `gray` scale; the panel set reads the same tokens as the shell
in both themes.

## Typography

**Display Font:** Instrument Serif (with Iowan Old Style, Georgia)
**Body Font:** Inter (with system-ui, Segoe UI, Roboto)
**Mono Font:** JetBrains Mono (with SF Mono, Menlo, Consolas)

**Character:** One old-style serif in a single roman weight answers one neutral
interface sans. The serif sets the scale of a page and then steps aside; the sans
does the work. The pairing stays quiet because the palette already carries the
warmth. Instrument Serif loads from Google Fonts at 400 roman and italic only.
JetBrains Mono leads the mono stack but does not load, so most machines resolve to
the platform mono.

### Hierarchy
- **Display** (400, 2.25rem, line-height 1, tracking 0.015em): The empty-state
  line above a new conversation, and 3rem from 640px up.
- **Headline** (400, 1.5rem, line-height 1.25): Page titles inside the main
  region, applied by the page header component rather than by each page.
- **Title** (600, 1rem, line-height 1.5): Card headers and panel section names.
  Dialog titles step up to 1.125rem.
- **Body** (400, 0.875rem, line-height 1.5): Message text, form copy, and
  descriptions. Rendered markdown rises to 1.7 inside a bubble. Hold prose to
  65–75 characters.
- **Control** (500, 0.875rem): Button labels, input values, and nav links. The
  workspace rail steps down to 13px.
- **Label** (500, 0.75rem, tracking 0.05em, uppercase where it names a group):
  Badges and field labels. Rail group headings drop to 0.625rem and widen to
  0.16em.
- **Mono** (400, 0.8125rem): Token counts, keystrokes, and code blocks.

### Named Rules
**The One Weight Rule.** Instrument Serif ships one weight. Set display type at
400 and never ask the face for more; the browser fakes the difference.

**The Two Voice Rule.** The serif speaks at page scale only. Inside a panel, a
card, or a bubble, every word is Inter. The login card is the one page-scale
title outside the workspace shell, so it takes the serif at 1.875rem; the brand
wordmark above it stays Inter bold.

## Layout

The app is a fixed frame. The shell fills the dynamic viewport height, hides
overflow, and never scrolls as a page; each region scrolls inside itself.

Three regions hold the frame:
1. A 56px header spans the top with the brand mark, three quick links, an Explore
   menu, an account menu, and a slot for per-conversation tools. Below 768px the
   header menu opens a 288px navigation drawer over a backdrop.
2. The conversation rail at 240px, pinned from 1024px up and collapsible to zero
   with a tab handle on its edge.
3. The roleplay tool panel at 320px on the right, with a 40px icon strip of nine
   vertical tabs and a sticky header for the active tab.

A 4px sunset strip closes the shell at the bottom edge, running under the
conversation rail and the main region.

Every route inside the shell except chat opens with one page header: a parchment
bar closed by a hairline, a 40px icon well, a serif h1 at the headline step, an
optional 14px muted description, and trailing actions. The header carries the same
measure as the page content, so the icon well and the first card share a left
edge, and the title sits one well and one 12px gap inside it. A route that needs
tabs puts them inside the same bar under a second hairline, with a 2px sienna
underline on the active tab. Full-bleed routes keep the bar without a measure.

Page content centers at 1280px with 16px side padding and 32px vertical padding.
Measured regions are narrower on purpose: the community grid at 1152px, the
roleplay landing and the transcript at 768px, and the story reader at 768px. The
composer shares the transcript measure so a turn and the line the player writes
next sit on one axis.

Spacing follows a 4px rhythm. Card padding is 16px, dialog side padding 24px, the
gap between stacked cards 12px, and the gap between sections 24px.

### Named Rules
**The Fixed Frame Rule.** The document does not scroll. If a new surface needs
more room, it scrolls inside its own region and keeps the frame still.

**The Three Measures Rule.** Transcript 768px, composer 768px, reading column
672px. A character bubble caps at 672px inside the transcript so a long turn keeps a
comfortable reading measure; the action that expands a turn lifts the cap. A single
column holds the work inside the rails, and a second column only appears as a tool
panel.

**The 44 Rule.** Primary controls hold 44px of height: buttons at medium size,
inputs, navigation links, and composer action buttons. A 32px control appears only
beside a 44px one.

**The One Page Title Rule.** A route carries one visible h1, inside its page
header. The shell header names no heading, except that the chat transcript, which
has no page header, takes its section name as a screen-reader-only h1. Header
actions sit on the trailing edge and wrap below the title rather than shrinking
it.

## Elevation & Depth

The system is flat by default. Depth comes from paper on paper, one hairline rule,
and a lift on state. A white card sits on cream and reads as a separate leaf with
no shadow at all. Hover raises a card 2px and adds a large shadow. Overlays float
on a 60% black backdrop with a blur and a large shadow.

### Shadow Vocabulary
- **Rest** (`0 1px 2px 0 rgb(0 0 0 / 0.05)`): Primary and destructive buttons, the
  composer, and the active send button.
- **Hover** (`0 4px 6px -1px rgb(0 0 0 / 0.1), 0 2px 4px -2px rgb(0 0 0 / 0.1)`):
  A hovered button, a focused composer.
- **Floating** (`0 10px 15px -3px rgb(0 0 0 / 0.1), 0 4px 6px -4px rgb(0 0 0 / 0.1)`):
  The message action tray, menu content, and a hovered card.
- **Overlay** (`0 25px 50px -12px rgb(0 0 0 / 0.25)`): Dialogs, over a 60%
  blurred backdrop.
- **Menu** (`0 20px 25px -5px rgb(0 0 0 / 0.1), 0 8px 10px -6px rgb(0 0 0 / 0.1)`):
  The account menu, popovers, and selectors. The dark theme doubles the alpha to
  0.5.
- **Colored lift** (`0 20px 25px -5px hsl(var(--primary) / 0.3)`): Gradient avatar
  tiles, which cast a shadow in their own hue.
- **Focus** (`0 0 0 3px hsl(var(--primary) / 0.15)`): The composer's focus halo,
  layered over a 1px sienna border.

One surface breaks the flat rule on purpose. The login and registration pages set
two 24rem blurred pigment circles behind the form, a corner gradient, and a large
shadow under the form card. The five-bar mark and the wordmark sit above the card
at 1.25 scale with no colored shadow. They are the only atmospheric surfaces;
everything behind the login stays paper.

The stylesheet defines no glow, glass, gradient-text, or gradient-border utility.
Those effects left the system when no surface rendered them.

### Named Rules
**The Flat Paper Rule.** Surfaces rest flat. A shadow marks a raised overlay or a
state change, and never decorates a resting panel.

**The One Divider Rule.** A container separates itself with a hairline or a
shadow. A card that carries both a rule and a shadow must earn the shadow with
state.

## Shapes

Corners run one family from 4px to 16px, and the size follows the object: 12px for
controls, tiles, and icon buttons (the most used radius in the codebase), 16px for
cards, inputs, list items, dialogs, message bubbles, and the large empty-state icon
well, 8px for the small corners inside a layout, 6px for inline code, and 4px for a
keystroke chip. Counters, scrollbars, and circular marks use a full pill.

The stylesheet overrides the radius scale from `sm` through `xl`, so Tailwind's
`2xl` step resolves to the same 16px as `xl`. No surface uses a 24px radius.

Bubbles tighten one corner. The author's side takes an 8px top corner against the
other 16px, so a stack of turns points back at the avatar column.

Two blocks carry a 4px accent bar on their left edge, rounded on the outer side
only: narration in amber and action in teal. The brand mark is five 4px bars of
14, 22, 28, 22, and 14px height, bottom-aligned with a 2px gap.

Borders are 1px. A container takes sand rule at full strength; a nested surface
takes the same rule at 50%, which keeps three levels of nesting readable without a
second color.

### Named Rules
**The Tail Corner Rule.** A message corner tightens to 8px on the author's side.
Nothing else tightens.

**The Outer Edge Rule.** A left accent bar rounds on its outer corners and stays
square against the block it marks.

## Components

### Buttons
- **Shape:** Gently curved (12px radius). Sizes: small 32px tall with 12px side
  padding, medium 44px with 16px, large 48px with 24px, icon 44px square.
- **Primary:** Sienna fill with white ink, 14px medium label, and a rest shadow.
- **Hover / Focus:** Fill deepens to ember deep and the shadow rises. Press
  scales to 0.98. Focus draws a 2px ring at 2px offset.
- **Secondary:** Coffee brown fill, white ink, same shape and motion.
- **Ghost:** Transparent with full-strength ink; hover paints the butter tint.
- **Outline:** Transparent with a 1px sand rule; hover paints the tint and darkens
  the border to aged brass.
- **Danger / Success:** Brick red and deep pine fills. Success lightens on hover
  instead of darkening.
- **Disabled:** 50% opacity with pointer events removed. A loading button keeps
  its label and shows a spinner, and it sets `aria-busy`.

### Chips and Badges
- **Style:** 8px radius, 10px medium label, tinted fill at 20% of the hue, and no
  border. A model tag uses the butter tint with muted ink.
- **State:** The interjection badge uses umber warning, the out-of-character badge
  uses the muted pair, and a category tag on a stat uses the quiet pair.

### Cards
- **Corner Style:** 16px.
- **Background:** White leaf on paper cream.
- **Shadow Strategy:** None at rest; a hoverable card lifts 2px and takes the
  floating shadow.
- **Border:** 1px sand rule, darkening to aged brass on hover.
- **Internal Padding:** 12, 16, or 24px. The header holds a 16px semibold title
  over a 14px muted description, and the footer separates with a top rule.

### Empty States
- **Construction:** A 40px sienna-tinted icon well with a 16px radius and a 20px
  sienna icon, a 14px semibold title, a 14px muted line held to 448px, and up to
  two 44px actions.
- **Placement:** Centered in the region that would hold the list, with 48–64px of
  vertical padding. The empty state keeps the page's own header and tabs.
- **Action:** When the region has no other content, the empty state owns the
  primary action; the page header then keeps its trailing edge clear.

### Inputs and Fields
- **Style:** 16px radius, 44px tall, 1px field-brown border on paper cream, 14px
  text, and 12px side padding.
- **Focus:** Border turns sienna and a 2px ring at 20% sienna sits outside it.
- **Hover:** Border deepens to muted ink.
- **Error:** Border and ring turn brick red, and a 12px alert line with an icon
  sits below the field. The field reports `aria-invalid` and points at the message
  by id.
- **Labels:** 14px medium above the field, with a quiet "(required)" suffix.
  Helper text sits at 12px muted below.

### Navigation
- **Workspace navigation:** Below 768px the header menu opens a 288px left drawer
  over a 45% backdrop, with focus containment and focus restored to the toggle.
  Group headings are 10px semibold, uppercase, 0.16em tracking, in muted ink.
  Links are 13px medium, 44px tall, 12px radius, with a 19px icon at 1.6 stroke.
  The active link paints the butter tint and a 3px inset sienna bar on its left
  edge.
- **Header:** Three quick links at 12px medium in 8px radius pills, an "Explore"
  menu for the rest, and a 32px account tile with the initial in semibold. From
  768px up these links are the only workspace navigation.

### Page Header
- **Surface:** Parchment with a 1px sand base rule. No transparency and no blur.
- **Identity:** A 40px icon well with a 12px radius and a 10% sienna tint, holding
  a 20px sienna icon; then the serif h1 at 1.5rem with 1.25 line height, and an
  optional 14px muted description. The title wraps rather than truncates.
- **Lead:** A 44px ghost icon control that returns to the parent route, or a 40px
  initial tile on the profile page.
- **Actions:** 44px buttons and 32px segmented view controls on the trailing edge.
  They wrap below the identity on narrow screens instead of compressing the
  title.
- **Tabs:** Inside the bar under a second hairline. Tabs are 44px tall with 14px
  medium labels and a 16px icon. The active tab takes sienna ink and a 2px sienna
  underline that sits on the base rule.
- **Measure:** The bar shares the page content measure, from `max-w-3xl` for the
  story reader to `max-w-7xl` for characters and admin. Map, test map, and
  settings stay full-bleed.
- **Standalone:** The legal pages reuse the bar with a 44px back link, a sticky
  position, and a 672px reading measure.

### The Message Family
Three block types carry a roleplay turn, and each has a stable color code:
- **Speech** sits in a bubble. The player's turn uses a sienna-to-ember gradient
  with white ink, capped at 85% width, aligned right. A character's turn uses a
  white leaf with a sand rule at 50%, capped at 672px, aligned left. A participant's
  turn takes their pigment tint and a pigment border.
- **Action** renders as an italic line between two teal asterisks, inside a teal
  5% fill with a teal 20% rule and a 4px teal edge bar. The character name and
  avatar head the block at 12px semibold.
- **Narration** renders the same construction in amber, with an optional
  book-icon scene label in 12px uppercase above the italic text.

Both tinted blocks keep their fixed amber and teal values in light and dark
themes. They mark a kind of writing, not a theme surface.

### The Composer
A 12px rounded card in the workspace shell, which tightens the shared wrapper's
own 16px radius. It holds a self-sizing textarea (44px minimum, 200px maximum), a
paperclip action, and a send button. At rest it carries the field-brown border and
a rest shadow. Focus turns the border sienna and adds a 3px halo at 15%. While the
model streams, the border pulses between 30% and 50% sienna and the send button
becomes a brick red stop button with a pulsing shadow. The footer carries keystroke
chips on the left and a character counter that shifts to umber warning near the
limit and brick red at it.

## Do's and Don'ts

### Do:
- **Do** take surface, border, and ink from the token layer (`bg-card`,
  `text-foreground`, `border-border`, `text-muted-foreground`) so both themes
  follow.
- **Do** open every route with the shared page header: one icon well, one serif
  h1, one description, and trailing actions.
- **Do** hold the 4px rhythm: 4, 8, 12, 16, 24, 32.
- **Do** set primary controls to 44px of height and give every focusable element a
  2px sienna ring at 2px offset.
- **Do** theme the browser surfaces: `accent-color` on checkboxes, radios, ranges,
  and progress bars, and a sienna caret in fields. Text selection is sienna at 30%.
- **Do** keep body copy at 14px and labels at 12px. A character bubble caps at
  42rem, which keeps a turn within a readable measure.
- **Do** reach for Instrument Serif at page scale only, at weight 400.
- **Do** hold AA contrast. The palette was darkened for it: sienna ink reaches
  7.65:1 on paper cream and muted ink reaches 6.92:1.
- **Do** keep the sunset band to the shell edge, the drawer foot, and the brand
  mark.
- **Do** state motion in two speeds: 150ms for a state change and 300ms with
  `cubic-bezier(0.16, 1, 0.3, 1)` for a panel.

### Don't:
- **Don't** set text or icons in signal ember (#fa520f). It measures 3.21:1 on
  paper cream and fails AA below 18px.
- **Don't** apply `font-bold` or `font-semibold` to Instrument Serif. Page titles
  that pass `font-bold` today render synthetic bold from a 400-only face; the
  shell rule already sets display type to 400.
- **Don't** add surfaces in `zinc`, `slate`, or `gray`. The token layer is the
  system, and a fixed cool gray renders a dark tile inside the cream theme.
- **Don't** fill a button, card, input, or heading with the sunset gradient or
  clip it to text. The band is chrome.
- **Don't** add glassmorphism. A translucent panel over a blurred backdrop reads
  as a different product, and the stylesheet no longer carries the utility.
- **Don't** grow a bubble past 85% of its column, or drop the tightened 8px corner
  that points at the author.
- **Don't** recolor the amber narration block or the teal action block per theme.
  Their fixed values are the semantic code.
- **Don't** give a page header a translucent or blurred surface. It is parchment
  with one hairline, the same as the shell header.
- **Don't** stack a border and a shadow on a resting card. Pick one.
