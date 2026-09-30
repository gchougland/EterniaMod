package com.hexvane.eterniamod.ui;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.text.NumberFormat;
import java.util.Locale;

/** Shared currency labels. Icons always have a written currency name beside them. */
public final class CurrencyUi {
    private CurrencyUi() {}
    public static String amount(long value){return NumberFormat.getIntegerInstance(Locale.US).format(value);}
    public static String balance(long value){
        if(value<1_000_000)return amount(value);
        var format=NumberFormat.getCompactNumberInstance(Locale.US,NumberFormat.Style.SHORT);
        format.setMaximumFractionDigits(2);return format.format(value);
    }
    public static void wallet(UICommandBuilder c,Long coins,Long crowns){
        c.set("#WalletCoins.Text",coins==null?"Coins: loading":balance(coins)+" Coins");
        c.set("#WalletCrowns.Text",crowns==null?"Crowns: loading":balance(crowns)+" Crowns");
    }
    public static void price(UICommandBuilder c,String row,long coins,String suffix){
        c.set(row+" #RowPrice.Visible",coins>=0);
        if(coins>=0)c.set(row+" #CoinPrice.Text",amount(coins)+" Coins"+suffix);
    }
}
