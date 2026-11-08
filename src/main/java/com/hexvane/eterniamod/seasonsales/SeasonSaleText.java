package com.hexvane.eterniamod.seasonsales;

import com.hexvane.eterniamod.domain.SeasonService.SaleState;
import com.hexvane.eterniamod.domain.SeasonService.SaleStatus;
import com.hexvane.eterniamod.domain.SeasonService.SaleWindow;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Player and admin wording for season sale windows. Pure text, safe to call from any thread or view. */
public final class SeasonSaleText {
    private static final DateTimeFormatter SHORT=DateTimeFormatter.ofPattern("d MMM",Locale.UK);
    private static final DateTimeFormatter SHORT_YEAR=DateTimeFormatter.ofPattern("d MMM yyyy",Locale.UK);
    private static final DateTimeFormatter LONG=DateTimeFormatter.ofPattern("d MMMM yyyy",Locale.UK);
    private SeasonSaleText(){}

    /** "1 December 2026". */
    public static String longDay(LocalDate day){return LONG.format(day);}
    /** "30 Jan", with the year only when it is not the current year. */
    public static String shortDay(LocalDate day,Instant now){
        return day.getYear()==now.atZone(ZoneOffset.UTC).getYear()?SHORT.format(day):SHORT_YEAR.format(day);
    }
    private static String season(SaleStatus status){return status.name()==null||status.name().isBlank()?"its season":status.name();}

    /** Short card badge: "Until 30 Jan", "Opens 1 Dec", "Season ended". Empty when there is nothing to say. */
    public static String badge(SaleStatus status,Instant now){
        if(status==null)return "";var w=status.window();
        return switch(status.state()){
            case UPCOMING->"Opens "+shortDay(w.startsOn(),now);
            case ON_SALE->w.endsOn()==null?"":"Until "+shortDay(w.endsOn(),now);
            case ENDED->"Season ended";
            case UNSCHEDULED->"";
        };
    }
    /** One friendly sentence for a detail view. Empty for items with no sale limit. */
    public static String note(SaleStatus status){
        if(status==null)return "";var w=status.window();
        return switch(status.state()){
            case UPCOMING->"Coming soon. This goes on sale when "+season(status)+" begins on "+longDay(w.startsOn())+".";
            case ON_SALE->w.endsOn()==null?"":"Available until the end of "+longDay(w.endsOn())+", while "+season(status)+" runs.";
            case ENDED->season(status).substring(0,1).toUpperCase(Locale.ROOT)+season(status).substring(1)+" has ended, so this is no longer for sale. Anything you already own stays yours.";
            case UNSCHEDULED->"";
        };
    }
    /** The purchase button when buying is not possible. */
    public static String closedButton(SaleState state){return state==SaleState.UPCOMING?"Coming soon":"No longer sold";}
    /** The reason a server rejects a purchase outside the window, e.g. from a page left open too long. */
    public static String rejection(SaleStatus status){
        return switch(status.state()){
            case UPCOMING->"This is not on sale yet. It goes on sale when "+season(status)+" begins on "+longDay(status.window().startsOn())+".";
            case ENDED->"Sorry, "+season(status)+" has ended, so this is no longer for sale. Anything you already own stays yours.";
            default->"This is not on sale right now.";
        };
    }

    /** Admin summary for the season designer: a headline and one line of detail. */
    public record Summary(String headline,String detail) {}
    public static Summary designer(String startsOn,String endsOn,Instant now){
        SaleWindow window;
        try{window=SaleWindow.parse(startsOn,endsOn);}
        catch(RuntimeException e){return new Summary("Check the dates","Write dates like 2026-12-01. The end date cannot be before the start.");}
        var w=window;
        return switch(w.state(now)){
            case UNSCHEDULED->new Summary("Always on sale","No dates set, so Premium and this season's store items can be bought at any time.");
            case UPCOMING->new Summary("Coming soon","Premium and season items go on sale "+longDay(w.startsOn())+(w.endsOn()==null?".":" and stop after "+longDay(w.endsOn())+"."));
            case ON_SALE->new Summary("On sale now",w.endsOn()==null?"Premium and season items can be bought, with no end date set.":"Sale ends after "+longDay(w.endsOn())+" (midnight UTC). Owners keep their pass forever.");
            case ENDED->new Summary("Sale ended","Sales closed after "+longDay(w.endsOn())+". Owners keep their pass and items forever.");
        };
    }
}
