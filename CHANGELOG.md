# Changelog

All notable changes to Portfolio (Android) and Portfolio for Galaxy Watch.
Newest first. The app shows this list under More › About & what's new; it's built from
this file by `Portfolio/tools/changelog.py`, so edit here and re-run the script.

## 9.1 — 26 Nov 2026
- Full-screen layout: the app draws behind the status bar and the gesture bar, edge to edge
- The bottom menu is now a floating rounded card with a soft shadow, and pages scroll underneath it
- In landscape the menu becomes a floating card down the side, so pages keep their full height
- Pages adjust to each phone's notch and gesture-bar sizes, and the menu hides while the keyboard is open

## 9.0.2 — 26 Nov 2026
- Settings (More) now stay where you are when you flip a switch or pick an option; the page no longer jumps to the top
- Switches slide on and off in place before anything that depends on them updates

## 9.0.1 — 26 Nov 2026
- Changing an option no longer jumps to the top of the page: profile, filter, list layout and Analytics section buttons redraw in place
- After changing the theme or Pure black, the page opens where you were, without the entrance animation
- Smaller header buttons (eye, refresh, +), evenly spaced and lined up with the page title

## 9.0 — 26 Nov 2026
- Auto refresh while the app is open, every 1, 5, 15, 30 or 60 minutes, or off (More › Prices & refresh, or long-press the ↻ button)
- Option to auto refresh only while the market is open (NSE hours on weekdays), plus one evening refresh for mutual fund and ULIP NAVs
- Option to also refresh when the app is closed, keeping alerts and the widget fresh (Android runs this at most every 15 min)
- The round ↻ button shows progress: its ring fills as each price arrives, then counts down to the next automatic refresh
- The page header shows when the next refresh is due

## 8.9 — 26 Nov 2026
- Link a ULIP fund to any web page that shows its NAV, such as its Moneycontrol page: open the fund › Use a web page link
- The app checks the page first and shows the NAV and date it found next to your last NAV before linking
- Linked pages are read on every refresh; a page whose NAV is far from yours is ignored, so a wrong or changed page can't overwrite your values
- The fund page shows where its NAV comes from, with Change link and Unlink

## 8.8 — 26 Nov 2026
- ULIP NAVs can update online: More › Auto-update ULIP NAVs finds your SBI Life funds on Yahoo Finance and updates them every business day, like mutual funds
- You confirm each match first, with the online NAV shown next to your last pasted one; very different values are flagged
- Each ULIP fund page shows whether its NAV comes from paste or online, with a link or unlink option
- Pasting still works and fills any gaps; funds not listed online stay on paste

## 8.7.1 — 25 Nov 2026
- Changing the theme (System, Light, Dark, Pure black) no longer asks for your fingerprint; neither does your phone switching between light and dark on its own
- The app still locks as before when you come back after more than 30 seconds away

## 8.7 · Watch 1.2 — 25 Nov 2026
- App version shown in More › About & what's new, with this full change history
- "What's new" pop-up the first time you open a new version
- Watch app shows its version at the bottom of the screen

## 8.6 · Watch 1.1 — 24 Nov 2026
- Currency per profile for NRIs: each person's holdings, savings, deposits and loans show in their own country's currency, with no conversion
- "Everyone" splits into one combined view per currency (e.g. Everyone · ₹, Everyone · S$) so currencies are never added together
- Currency picker in More (18 common currencies with flags, plus every other currency)
- Stock search covers every exchange: NSE/BSE first, then SGX, NASDAQ, LSE and more; each stock keeps its trading currency
- A note on a holding whose trading currency differs from its profile's
- Indian tax page and the tax-free gains tip apply to ₹ profiles only
- Watch shows amounts in the profile's currency

## 8.5 — 24 Nov 2026
- Every pop-up restyled to match the app: rounded card, app fonts, pill buttons, filled text fields
- Rich menus with an icon, title and description (Add investment, Markets, asset and loan types, family)
- Pop-ups are styled before they appear, so the old look never flashes

## 8.4.4 — 24 Nov 2026
- No more flicker when tapping buttons: the page keeps its scroll position before it redraws
- Performance, Heat-map and Allocation buttons update only their own card

## 8.4.3 — 24 Nov 2026
- Fixed the next card showing blank while swiping the card stack up

## 8.4.2 — 24 Nov 2026
- Swiping the card stack up now works exactly like swiping down: the solid card follows your finger and the cards behind move forward

## 8.4.1 — 24 Nov 2026
- Frosted-glass cards removed (back to the previous card look); the market-mood background stays with its own switch

## 8.4 — 24 Nov 2026
- Market-mood Dashboard background: soft green on up days, red on down days
- Pure black mode for AMOLED screens
- Numbers roll like an odometer after a refresh
- Holdings glow green or red when their price changes
- Card stack tilts with the phone, with a moving shine
- Heat-map of holdings sized by value, coloured by Today / 1 month / 1 year change
- Photo or emoji pictures for you and family members
- Festival and finance-day greetings (Diwali, Holi, Eid, Budget Day, new financial year and more)

## 8.3 · Watch 1.0 — 24 Nov 2026
- New Galaxy Watch app: total, today's change, overall gain, totals by type and top holdings, with family profiles and privacy
- Phone ⇄ watch sync over Bluetooth, no Google Play services needed; each watch is approved once on the phone
- The watch refreshes prices on its own connection

## 8.2 — 24 Nov 2026
- Pull down from the top of any page to refresh prices

## 8.1 — 24 Nov 2026
- Welcome screen with the logo and your name on first launch
- "Welcome back, name" on the launch animation and a time-of-day greeting on the Dashboard
- Profile card in More to change your name
- Source code on GitHub (dnzryts/portfolio)

## 8.0 — 24 Nov 2026
- New Analytics tab: Forecast (range of outcomes, goals, monthly amount needed), Performance (returns, risk, month grid, gain drivers) and rule-based Suggestions
- Forecast card on the Dashboard
- Fixed a crash opening FD, RD, PPF, gold or loan details

## 7.5 — 24 Nov 2026
- See-through dashboard cards (60% by default; Solid, 80%, 60% or 40% under More › Appearance)

## 7.4 — 24 Nov 2026
- Recurring deposits: monthly instalment, instalments paid, maturity value

## 7.3
- Smoother swipe-down on the card stack; edge stretch effect removed

## 7.2
- Invested amount on every Dashboard card

## 7.1
- Wallet-style card stack on the Dashboard (All, types, Cash & FDs, Other, platforms)

## 7.0
- Net worth with FDs, PPF, gold and loans; family owners; insights; tax estimate; encrypted auto-backup; adaptive icon and launch animation

## 6.0
- CAS PDF import, broker CSV/Excel import, SBI Life policies and premium reminders, share files to the app

## 5.0
- Transactions, FIFO profit and loss, XIRR, automatic SIPs, dividends, target mix

## 4.0
- Home-screen widget, price targets, daily summary, watchlist (Markets tab), 52-week range

## 3.0 – 3.6
- Bottom navigation, holdings across platforms, indexes, fingerprint lock, move alerts, privacy mode, light/dark theme, swipe between tabs, crash reports

## 2.0
- Stocks, mutual funds and ULIP together; animated dashboard; grouping by platform

## 1.0
- ULIP Tracker: paste SBI Life NAVs, charts and a summary
