package com.uliptracker;

import java.time.LocalDate;
import java.time.MonthDay;

/**
 * Festival and money-calendar greetings for the welcome line. Lunar festivals are listed by date for 2026–2028
 * (Delhi panchang; Eid depends on the moon, so it covers the likely day and the next).
 */
public final class Seasons {

    private Seasons() { }

    /** {date, greeting}. Earlier rows win when two fall on the same day. */
    private static final String[][] DATED = {
            // Diwali (Lakshmi Puja) and the day after
            {"2026-11-08", "Happy Diwali 🪔"}, {"2026-11-09", "Happy Diwali 🪔"},
            {"2027-10-29", "Happy Diwali 🪔"}, {"2027-10-30", "Happy Diwali 🪔"},
            {"2028-10-17", "Happy Diwali 🪔"}, {"2028-10-18", "Happy Diwali 🪔"},
            // Dhanteras
            {"2026-11-06", "Happy Dhanteras ✨"}, {"2027-10-27", "Happy Dhanteras ✨"},
            // Holi
            {"2026-03-04", "Happy Holi 🎨"}, {"2027-03-22", "Happy Holi 🎨"}, {"2027-03-23", "Happy Holi 🎨"}, {"2028-03-11", "Happy Holi 🎨"},
            // Eid al-Fitr
            {"2027-03-10", "Eid Mubarak 🌙"}, {"2027-03-11", "Eid Mubarak 🌙"},
            {"2028-02-26", "Eid Mubarak 🌙"}, {"2028-02-27", "Eid Mubarak 🌙"},
            // Dussehra
            {"2026-10-20", "Happy Dussehra 🏹"}, {"2027-10-09", "Happy Dussehra 🏹"}, {"2028-09-27", "Happy Dussehra 🏹"},
            // Ganesh Chaturthi
            {"2027-09-04", "Ganpati Bappa Morya 🐘"}, {"2028-08-23", "Ganpati Bappa Morya 🐘"},
            // Akshaya Tritiya
            {"2027-05-09", "Happy Akshaya Tritiya 🪙"}, {"2028-04-27", "Happy Akshaya Tritiya 🪙"},
    };

    /** {MM-DD, greeting} every year. */
    private static final String[][] YEARLY = {
            {"01-01", "Happy New Year 🎆"},
            {"01-14", "Happy Sankranti & Pongal 🪁"}, {"01-15", "Happy Sankranti & Pongal 🪁"},
            {"01-26", "Happy Republic Day 🇮🇳"},
            {"02-01", "It's Budget Day 📜"},
            {"03-31", "Last day of the financial year 🧾"},
            {"04-01", "Happy new financial year 📒"},
            {"08-15", "Happy Independence Day 🇮🇳"},
            {"12-25", "Merry Christmas 🎄"},
            {"12-31", "Happy New Year's Eve 🥂"},
    };

    /** Greeting for this date, or null on an ordinary day. */
    public static String greeting(LocalDate d) {
        String iso = d.toString();
        for (String[] r : DATED) if (r[0].equals(iso)) return r[1];
        String md = String.format(java.util.Locale.US, "%02d-%02d", d.getMonthValue(), d.getDayOfMonth());
        for (String[] r : YEARLY) if (r[0].equals(md)) return r[1];
        return null;
    }

    /** "Happy Diwali, Asha 🪔" from "Happy Diwali 🪔" and a name (the emoji stays at the end). */
    public static String withName(String g, String name) {
        if (name == null || name.isEmpty()) return g;
        int sp = g.lastIndexOf(' ');
        if (sp < 0) return g + ", " + name;
        return g.substring(0, sp) + ", " + name + g.substring(sp);
    }
}
