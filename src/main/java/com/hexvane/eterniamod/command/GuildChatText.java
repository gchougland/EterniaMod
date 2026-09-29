package com.hexvane.eterniamod.command;

/** Pure text validation, independent of native command and server initialization. */
final class GuildChatText {
    private GuildChatText() {}
    static String normalize(String text) {
        if(text==null)throw new IllegalArgumentException("Enter a guild message.");
        String result=text.strip();
        if(result.isEmpty()||result.length()>500)throw new IllegalArgumentException("Guild messages must contain 1–500 characters.");
        if(result.chars().anyMatch(c->Character.isISOControl(c)||c==0x2028||c==0x2029))throw new IllegalArgumentException("Guild chat messages must be one line without control characters.");
        return result;
    }
}
