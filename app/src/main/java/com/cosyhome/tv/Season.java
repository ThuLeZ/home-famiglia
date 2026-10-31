package com.cosyhome.tv;

import java.util.Calendar;

/** What the scene shows: the time of year, and the weather falling over it. */
enum Season {
    CHRISTMAS, HALLOWEEN, SPRING, SUMMER, AUTUMN, WINTER;

    enum Fx { CLEAR, RAIN, SNOW }

    /** Christmas 1 Dec to 6 Jan, Halloween 15 to 31 Oct, otherwise the season of the year. */
    static Season at(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        int m = c.get(Calendar.MONTH), d = c.get(Calendar.DAY_OF_MONTH);
        if (m == Calendar.DECEMBER || (m == Calendar.JANUARY && d <= 6)) return CHRISTMAS;
        if (m == Calendar.OCTOBER && d >= 15) return HALLOWEEN;
        if (m >= Calendar.MARCH && m <= Calendar.MAY) return SPRING;
        if (m >= Calendar.JUNE && m <= Calendar.AUGUST) return SUMMER;
        if (m >= Calendar.SEPTEMBER && m <= Calendar.NOVEMBER) return AUTUMN;
        return WINTER;
    }

    /** Snow only at or below the threshold; above it, any precipitation falls as rain. */
    static Fx fx(double celsius, int code, double snowAtOrBelow) {
        if (celsius <= snowAtOrBelow) return Fx.SNOW;
        boolean wet = (code >= 51 && code <= 67) || (code >= 71 && code <= 77)
                || (code >= 80 && code <= 86) || code >= 95;
        return wet ? Fx.RAIN : Fx.CLEAR;
    }
}
