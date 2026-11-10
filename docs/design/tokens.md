# Design tokens

Extracted from the UI mockups in `screens.html`. Compose code in `app/src/main/kotlin/app/kinjar/ui/theme` mirrors these values; change them here and there together.

## Fonts

- Text: Geist 400, 500, 600 (`res/font/geist_*.ttf`).
- Numbers and amounts: Geist Mono 500, 600 (`res/font/geist_mono_*.ttf`).
- Both are SIL Open Font License, from Google Fonts.

## Colors

| Token | Light | AMOLED | Use |
|---|---|---|---|
| bg | #FFFFFF | #000000 | screen background |
| text | #0E1014 | #F3F4F6 | primary text |
| muted | #5B616B | #9AA0AA | secondary text, labels |
| surface2 | #F3F4F6 | #0E0F12 | filled cards, keypad keys, chips |
| border | #E3E5E9 | #1F2227 | outlined cards, nav top border |
| track | #E5E7EB | #1C1E23 | progress bar background |
| primary | #3D4BD6 | #8C96FF | accent, Save button, active tab, Scan button |
| onPrimary | #FFFFFF | #05060F | text on primary |
| primarySoft | #ECEEFE | rgba(140,150,255,0.18) | selected category background |
| green | #1B7A57 | #4CC79A | income, synced dot |
| orange | #A8520E | #F0A05A | over budget |
| red | #B3261E | #FF8A80 | expense amounts, Spent, negative Difference, transaction conflict outline and label |

Category colors (same in both themes): Groceries #3D4BD6, Bills #D06A1E, Transport #1A8A7B, Eating out #7B55C7, Household #6E747D, Kids #B8467F, Fun #A88412, Health #2A7FC0, Other #6E747D. Existing category colors stay unchanged. The category editor offers 20 distinct swatches and selectable icons; transaction tiles use the income or expense treatment below.

## Layout

- Screen padding: 24 top, 20 sides, 120 bottom on Home (for the nav bar). Add screen: 20 top and sides, 24 bottom.
- Section gap: 20 on Home, 14 on Add.
- Body text 15. Screen title 26 / 600 / letter spacing -0.02em.

## Components

- Safe to spend card: radius 24, padding 22 x 20, background surface2, inner gap 14. Label 13 / 500 / uppercase / 0.06em muted. Amount mono 40 / 600 / -0.03em with currency 16 / 500 muted on the baseline. Hint line 14 muted. Progress track 10 high radius 5, pace marker 2 x 18.
- Month card: one card, radius 18, 1px border, padding 16. Income (green) and Spent (red) in two columns, gap 12, stacked when font scale is above 1.3 or the card is narrower than 280. Divider (border color, 14 above and below), then full-width Difference: green when positive, red when negative, text color when zero. Labels 13 muted, values mono 20 / 600 with the currency code 13 in the same text, so it wraps to a new line instead of truncating digits. The partial-rate warning stays below the card.
- Savings card: radius 18, border, padding 18 x 16, gap 12. Total mono 24 / 600 with RSD 14 muted. Per-currency chips: padding 6 x 10, radius 10, surface2, mono 13.
- Section header: 17 / 600. Link text 14 primary.
- Recent row (Home and Activity): min height 56, gap 12. Icon tile 40 x 40 radius 12 with a 22 decorative category icon: income green on the green tint, expense muted on surface2. Transfers (swap icon) and loan receipts (handshake icon) use the muted on surface2 style. Title 15 / 500, subtitle 13 muted. Amount mono 15 / 500 right aligned: expense red, income green, transfers and loan receipts text color. A cross-currency transfer adds the received amount (mono 13 muted, prefixed with an arrow) under the sent amount. Date 12 muted beneath. A held conflict adds a 2px red outline and a 13 / 600 red "Needs review" line.
- Bottom nav: height 84, padding 0 12 12, 5 equal columns, top border, labels 11 / 500, active item primary. Scan item is a 56 circle filled primary, lifted 24 up.
- Round icon button (close, add): 44 circle, surface2.
- Segmented control (Expense / Income / Transfer): width 270, surface2, radius 14, padding 4; items height 36, radius 10, selected one bg (white in light, surface2 lifted in dark) 14 / 500, others muted.
- Amount entry: mono 48 / 600 / -0.03em, currency 18 / 500 muted. Currency pills: min width 64, height 34, radius 17, border; selected is text color filled with bg-colored text, mono 13 / 500.
- Category tiles: 4 columns, gap 8, height 62, radius 14, border, dot 10 in category color above a 12 / 500 label. Selected: primary border, primarySoft background.
- Field boxes (Account, Date): 2 columns, gap 8, min height 52, padding 6 x 14, radius 14, border. Label 12 muted, value 14 / 500.
- Keypad: 3 columns, gap 8, keys height 52, radius 14, surface2, mono 22 / 500. Pushed to the bottom.
- Primary button (Save): height 54, radius 16, primary, 16 / 600.
- Pace bar: track height 10 on summary cards, 8 on budget rows, 6 in the Home budgets section; fully rounded; fill in the category color, orange when low or over. Marker 2 wide, 4 taller than the track on both ends, text color on summary cards and muted on rows.
- Budgets rows: 10 x 10 square with radius 3 in the category color, name 15 / 500, amounts mono 13 muted (orange when warning), hint 12 muted below the bar; non-RSD budgets show their currency after the amounts. Rows are tappable and open the edit form. "+ New" pill: height 40, radius 20, border, 14 / 500.
- Budget form: category tiles as on Add (already-budgeted ones dimmed to 40%, locked when editing), amount box mono 22 with currency suffix, currency pills, "Delete budget" text link in orange when editing, primary Save.
