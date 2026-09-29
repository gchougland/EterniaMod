package com.hexvane.eterniamod.ui;

import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;

/** Replace Anchor as a native value; nested Anchor.Height/Width are not client properties. */
public final class UiAnchors {
    private UiAnchors() {}

    public static Anchor height(int height) {
        var anchor=new Anchor();anchor.setHeight(Value.of(height));return anchor;
    }
    public static Anchor heightWithBottom(int height,int bottom) {
        var anchor=height(height);anchor.setBottom(Value.of(bottom));return anchor;
    }
    public static Anchor heightWithRight(int height,int right) {
        var anchor=height(height);anchor.setRight(Value.of(right));return anchor;
    }
    public static Anchor size(int width,int height) {
        var anchor=height(height);anchor.setWidth(Value.of(width));return anchor;
    }
    /** Preserve ServiceRow.ui's action height and spacing when its label needs more width. */
    public static Anchor serviceAction(int width) {
        var anchor=size(width,44);anchor.setLeft(Value.of(12));anchor.setTop(Value.of(16));return anchor;
    }
}
