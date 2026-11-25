package com.uliptracker;

/** Release history shown in More › About & what's new. Generated from CHANGELOG.md by tools/changelog.py — don't edit by hand. */
public final class Changelog {

    private Changelog() { }

    public static final class Entry {
        public final String version, date;
        public final String[] items;
        Entry(String version, String date, String[] items) { this.version = version; this.date = date; this.items = items; }
    }

    /** Newest first. */
    public static final Entry[] ENTRIES = {
            new Entry("8.7 · Watch 1.2", "25 Nov 2026", new String[]{"App version shown in More › About & what's new, with this full change history", "\"What's new\" pop-up the first time you open a new version", "Watch app shows its version at the bottom of the screen"}),
            new Entry("8.6 · Watch 1.1", "24 Nov 2026", new String[]{"Currency per profile for NRIs: each person's holdings, savings, deposits and loans show in their own country's currency, with no conversion", "\"Everyone\" splits into one combined view per currency (e.g. Everyone · ₹, Everyone · S$) so currencies are never added together", "Currency picker in More (18 common currencies with flags, plus every other currency)", "Stock search covers every exchange: NSE/BSE first, then SGX, NASDAQ, LSE and more; each stock keeps its trading currency", "A note on a holding whose trading currency differs from its profile's", "Indian tax page and the tax-free gains tip apply to ₹ profiles only", "Watch shows amounts in the profile's currency"}),
            new Entry("8.5", "24 Nov 2026", new String[]{"Every pop-up restyled to match the app: rounded card, app fonts, pill buttons, filled text fields", "Rich menus with an icon, title and description (Add investment, Markets, asset and loan types, family)", "Pop-ups are styled before they appear, so the old look never flashes"}),
            new Entry("8.4.4", "24 Nov 2026", new String[]{"No more flicker when tapping buttons: the page keeps its scroll position before it redraws", "Performance, Heat-map and Allocation buttons update only their own card"}),
            new Entry("8.4.3", "24 Nov 2026", new String[]{"Fixed the next card showing blank while swiping the card stack up"}),
            new Entry("8.4.2", "24 Nov 2026", new String[]{"Swiping the card stack up now works exactly like swiping down: the solid card follows your finger and the cards behind move forward"}),
            new Entry("8.4.1", "24 Nov 2026", new String[]{"Frosted-glass cards removed (back to the previous card look); the market-mood background stays with its own switch"}),
            new Entry("8.4", "24 Nov 2026", new String[]{"Market-mood Dashboard background: soft green on up days, red on down days", "Pure black mode for AMOLED screens", "Numbers roll like an odometer after a refresh", "Holdings glow green or red when their price changes", "Card stack tilts with the phone, with a moving shine", "Heat-map of holdings sized by value, coloured by Today / 1 month / 1 year change", "Photo or emoji pictures for you and family members", "Festival and finance-day greetings (Diwali, Holi, Eid, Budget Day, new financial year and more)"}),
            new Entry("8.3 · Watch 1.0", "24 Nov 2026", new String[]{"New Galaxy Watch app: total, today's change, overall gain, totals by type and top holdings, with family profiles and privacy", "Phone ⇄ watch sync over Bluetooth, no Google Play services needed; each watch is approved once on the phone", "The watch refreshes prices on its own connection"}),
            new Entry("8.2", "24 Nov 2026", new String[]{"Pull down from the top of any page to refresh prices"}),
            new Entry("8.1", "24 Nov 2026", new String[]{"Welcome screen with the logo and your name on first launch", "\"Welcome back, name\" on the launch animation and a time-of-day greeting on the Dashboard", "Profile card in More to change your name", "Source code on GitHub (dnzryts/portfolio)"}),
            new Entry("8.0", "24 Nov 2026", new String[]{"New Analytics tab: Forecast (range of outcomes, goals, monthly amount needed), Performance (returns, risk, month grid, gain drivers) and rule-based Suggestions", "Forecast card on the Dashboard", "Fixed a crash opening FD, RD, PPF, gold or loan details"}),
            new Entry("7.5", "24 Nov 2026", new String[]{"See-through dashboard cards (60% by default; Solid, 80%, 60% or 40% under More › Appearance)"}),
            new Entry("7.4", "24 Nov 2026", new String[]{"Recurring deposits: monthly instalment, instalments paid, maturity value"}),
            new Entry("7.3", "", new String[]{"Smoother swipe-down on the card stack; edge stretch effect removed"}),
            new Entry("7.2", "", new String[]{"Invested amount on every Dashboard card"}),
            new Entry("7.1", "", new String[]{"Wallet-style card stack on the Dashboard (All, types, Cash & FDs, Other, platforms)"}),
            new Entry("7.0", "", new String[]{"Net worth with FDs, PPF, gold and loans; family owners; insights; tax estimate; encrypted auto-backup; adaptive icon and launch animation"}),
            new Entry("6.0", "", new String[]{"CAS PDF import, broker CSV/Excel import, SBI Life policies and premium reminders, share files to the app"}),
            new Entry("5.0", "", new String[]{"Transactions, FIFO profit and loss, XIRR, automatic SIPs, dividends, target mix"}),
            new Entry("4.0", "", new String[]{"Home-screen widget, price targets, daily summary, watchlist (Markets tab), 52-week range"}),
            new Entry("3.0 – 3.6", "", new String[]{"Bottom navigation, holdings across platforms, indexes, fingerprint lock, move alerts, privacy mode, light/dark theme, swipe between tabs, crash reports"}),
            new Entry("2.0", "", new String[]{"Stocks, mutual funds and ULIP together; animated dashboard; grouping by platform"}),
            new Entry("1.0", "", new String[]{"ULIP Tracker: paste SBI Life NAVs, charts and a summary"}),
    };
}
