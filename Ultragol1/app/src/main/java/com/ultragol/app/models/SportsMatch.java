package com.ultragol.app.models;

import java.util.ArrayList;
import java.util.List;

/**
 * A single sports match/fixture: two teams, score/status from the scores
 * feed, plus zero or more playable servers gathered by matching this
 * fixture's teams against the stream-source feeds (see SportsApi).
 */
public class SportsMatch {

    public static final int STATUS_LIVE      = 0;
    public static final int STATUS_UPCOMING  = 1;
    public static final int STATUS_FINISHED  = 2;

    public String league   = "";
    public String homeTeam = "";
    public String awayTeam = "";
    public String homeLogo = "";
    public String awayLogo = "";
    public String homeScore = "-";
    public String awayScore = "-";
    public int    status   = STATUS_UPCOMING;
    public String time     = "";   // e.g. "18:00"
    public String date     = "";   // e.g. "Sáb 16 Ago"
    public String minute   = "";   // e.g. "45" while live

    /** [0] = server label, [1] = playable url (embed or direct m3u8/mp4). */
    public final List<String[]> servers = new ArrayList<>();

    public String matchTitle() {
        return homeTeam + " vs " + awayTeam;
    }

    public boolean hasScore() {
        return !"-".equals(homeScore) && !"-".equals(awayScore);
    }

    /** True for matches that belong on the agenda (excludes already-finished ones). */
    public boolean isLiveOrUpcoming() {
        return status == STATUS_LIVE || status == STATUS_UPCOMING;
    }
}
