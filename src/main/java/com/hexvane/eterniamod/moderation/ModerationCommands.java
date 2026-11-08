package com.hexvane.eterniamod.moderation;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.command.EterniaCommandSuggest;
import com.hexvane.eterniamod.domain.DomainException;
import com.hexvane.eterniamod.setup.SetupAccess;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.ParseResult;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.SingleArgumentType;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.command.system.suggestion.SuggestionResult;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/**
 * Staff commands under {@code /eternia admin}: {@code ban}, {@code unban}, {@code baninfo} and {@code bans}.
 * They inherit the admin collection's WorldEditor group and check {@link SetupAccess} again when they run.
 */
public final class ModerationCommands {
    static final String KEY = "eternia_moderation.";
    static final String BRASS = "#d8b66b", IVORY = "#f0e8d5", SAGE = "#b5c3b8", TEAL = "#85c5b3", CORAL = "#e0907a";
    static final int HISTORY_LINES = 8, LIST_LINES = 15;
    static final List<String> TIME_SUGGESTIONS = List.of("30m", "1h", "6h", "1d", "3d", "7d", "30d", "perm");

    private ModerationCommands() {}

    /** The single shared edit: one call from {@code EterniaAdminCommand}. */
    public static void addTo(AbstractCommand admin) {
        admin.addSubCommand(new Ban());
        admin.addSubCommand(new Unban());
        admin.addSubCommand(new Info());
        admin.addSubCommand(new ListBans());
    }

    // ---- arguments ------------------------------------------------------------------------------------------------

    static final SingleArgumentType<String> PLAYER = new SingleArgumentType<>(KEY + "args.player.name", KEY + "args.player.usage", "PlayerName") {
        @Override public String parse(@Nonnull String input, @Nonnull ParseResult parseResult) { return input; }
        @Override public void suggest(@Nonnull CommandSender sender, @Nonnull String typed, int count, @Nonnull SuggestionResult result) {
            List<String> names = new ArrayList<>();
            for (PlayerRef player : Universe.get().getPlayers()) names.add(player.getUsername());
            var moderation = ModerationBootstrap.get();
            if (moderation != null) {
                try { names.addAll(moderation.service().activeNames()); } catch (RuntimeException ignored) { /* suggestions only */ }
            }
            EterniaCommandSuggest.suggestPrefix(result, typed, names.stream().distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList());
        }
    };

    static final SingleArgumentType<String> TIME = new SingleArgumentType<>(KEY + "args.time.name", KEY + "args.time.usage", "7d") {
        @Override public String parse(@Nonnull String input, @Nonnull ParseResult parseResult) { return input; }
        @Override public void suggest(@Nonnull CommandSender sender, @Nonnull String typed, int count, @Nonnull SuggestionResult result) {
            EterniaCommandSuggest.suggestPrefix(result, typed, TIME_SUGGESTIONS);
        }
    };

    // ---- commands -------------------------------------------------------------------------------------------------

    /** {@code ban <player> [time] [reason...]}. A first word that is not a time starts the reason. */
    static final class Ban extends AbstractAsyncCommand {
        private final RequiredArg<String> player = withRequiredArg("player", "Player name or ID", PLAYER);
        private final RequiredArg<String> time = withRequiredArg("time", "30m, 2h, 7d or perm. Leave it out to use the usual length", TIME);
        Ban() {
            super("ban", "Ban a player. With no time, repeat offences get longer bans");
            setAllowsExtraArguments(true);
            addUsageVariant(new BanWithLadder());
        }
        @Override protected CompletableFuture<Void> executeAsync(CommandContext context) {
            String timeInput = time.get(context);
            String rest = trailing(context, "ban", 2);
            Optional<BanDuration> parsed = BanDuration.parse(timeInput);
            if (parsed.isEmpty() && isLadderWord(timeInput)) return ban(context, player.get(context), null, rest);
            if (parsed.isEmpty() && BanDuration.looksLikeTime(timeInput)) {
                context.sendMessage(line("ban.badTime", CORAL).param("input", timeInput));
                return CompletableFuture.completedFuture(null);
            }
            // "ban Name griefing at spawn": no time given, the whole tail is the reason.
            if (parsed.isEmpty()) return ban(context, player.get(context), null, join(timeInput, rest));
            return ban(context, player.get(context), parsed.get(), rest);
        }
    }

    /** {@code ban <player>}: the usual length for their offence and no reason. */
    static final class BanWithLadder extends AbstractAsyncCommand {
        private final RequiredArg<String> player = withRequiredArg("player", "Player name or ID", PLAYER);
        BanWithLadder() { super("Ban a player for the usual length of their next offence"); }
        @Override protected CompletableFuture<Void> executeAsync(CommandContext context) { return ban(context, player.get(context), null, ""); }
    }

    static final class Unban extends AbstractAsyncCommand {
        private final RequiredArg<String> player = withRequiredArg("player", "Player name or ID", PLAYER);
        Unban() { super("unban", "Lift a player's ban. Anything after the name is kept as a note"); setAllowsExtraArguments(true); }
        @Override protected CompletableFuture<Void> executeAsync(CommandContext context) {
            String note = trailing(context, "unban", 1);
            String input = player.get(context);
            return run(context, moderation -> {
                Target target = resolve(moderation, input);
                var lifted = moderation.service().unban(target.id(), actor(context.sender()), note);
                if (lifted.isEmpty()) return List.of(line("unban.notBanned", SAGE).param("name", name(target.name())));
                return List.of(line("unban.done", TEAL).param("name", name(lifted.get().name())));
            });
        }
    }

    static final class Info extends AbstractAsyncCommand {
        private final RequiredArg<String> player = withRequiredArg("player", "Player name or ID", PLAYER);
        Info() { super("baninfo", "Show a player's ban and ban history"); addAliases("banhistory"); }
        @Override protected CompletableFuture<Void> executeAsync(CommandContext context) {
            String input = player.get(context);
            return run(context, moderation -> {
                Target target = resolve(moderation, input);
                var service = moderation.service();
                Instant now = Instant.now();
                var current = service.active(target.id());
                var history = service.history(target.id());
                int offences = service.offences(target.id());
                List<Message> out = new ArrayList<>();
                out.add(line("info.title", BRASS).param("name", name(target.name())));
                if (current.isPresent()) {
                    var ban = current.get();
                    out.add(ban.permanent() ? line("info.activePermanent", CORAL) : line("info.activeTimed", CORAL).param("time", TimeWords.between(now, ban.until())));
                    out.add(line("info.activeDetail", IVORY).param("by", ban.by().name()).param("date", TimeWords.date(ban.at())).param("reason", reasonText(ban.reason())));
                } else {
                    out.add(line("info.clear", TEAL));
                }
                if (history.isEmpty()) {
                    out.add(line("info.empty", SAGE).param("name", target.name()));
                    return out;
                }
                out.add(line("info.offences", IVORY).param("count", offences).param("next", service.ladder().forOffence(offences + 1).words()));
                out.add(line("info.historyTitle", BRASS));
                var newest = new ArrayList<>(history);
                java.util.Collections.reverse(newest);
                for (var event : newest.subList(0, Math.min(HISTORY_LINES, newest.size()))) out.add(event(event));
                if (newest.size() > HISTORY_LINES) out.add(line("info.historyMore", SAGE).param("count", newest.size() - HISTORY_LINES));
                return out;
            });
        }
    }

    static final class ListBans extends AbstractAsyncCommand {
        ListBans() { super("bans", "List everyone who is banned right now"); }
        @Override protected CompletableFuture<Void> executeAsync(CommandContext context) {
            return run(context, moderation -> {
                var bans = moderation.service().activeBans();
                if (bans.isEmpty()) return List.of(line("list.empty", TEAL));
                Instant now = Instant.now();
                List<Message> out = new ArrayList<>();
                out.add(line("list.title", BRASS).param("count", bans.size()));
                for (var ban : bans.subList(0, Math.min(LIST_LINES, bans.size()))) {
                    out.add(line("list.row", IVORY).param("name", name(ban.name())).param("ends", ends(ban, now))
                        .param("by", ban.by().name()).param("reason", reasonText(ban.reason())));
                }
                if (bans.size() > LIST_LINES) out.add(line("list.more", SAGE).param("count", bans.size() - LIST_LINES));
                return out;
            });
        }
    }

    // ---- shared work ----------------------------------------------------------------------------------------------

    private static CompletableFuture<Void> ban(CommandContext context, String input, BanDuration length, String reason) {
        return run(context, moderation -> {
            Target target = resolve(moderation, input);
            if (context.sender() instanceof PlayerRef self && self.getUuid().equals(target.id())) return List.of(line("ban.self", CORAL));
            var result = moderation.service().ban(target.id(), target.name(), actor(context.sender()), length, reason);
            Instant now = Instant.now();
            if (result instanceof ModerationService.AlreadyBanned already) {
                return List.of(line("ban.already", CORAL).param("name", name(already.ban().name())).param("ends", ends(already.ban(), now)));
            }
            var banned = (ModerationService.Banned) result;
            var ban = banned.ban();
            List<Message> out = new ArrayList<>();
            out.add(ban.permanent() ? line("ban.donePermanent", TEAL).param("name", name(ban.name()))
                : line("ban.done", TEAL).param("name", name(ban.name())).param("time", banned.length().words()));
            out.add(line("ban.reason", IVORY).param("reason", reasonText(ban.reason())));
            if (banned.fromLadder()) out.add(line("ban.ladder", SAGE).param("offence", ban.offence()));
            if (Universe.get().getPlayer(ban.player()) != null) {
                moderation.disconnect(ban);
                out.add(line("ban.removed", SAGE));
            }
            EterniaModPlugin plugin = EterniaModPlugin.get();
            if (plugin != null) plugin.getLogger().atInfo().log("Ban: %s (%s) by %s for %s, offence %d. Reason: %s",
                ban.name(), ban.player(), ban.by().name(), banned.length().compact(), ban.offence(), ban.reason());
            return out;
        });
    }

    /** Checks staff access, runs the work on the moderation thread and sends what it returns, or a friendly failure. */
    private static CompletableFuture<Void> run(CommandContext context, java.util.function.Function<ModerationBootstrap, List<Message>> work) {
        if (context.sender() instanceof PlayerRef player && !SetupAccess.allowed(player.getUuid())) {
            context.sendMessage(line("staffOnly", CORAL));
            return CompletableFuture.completedFuture(null);
        }
        var moderation = ModerationBootstrap.get();
        if (moderation == null) {
            context.sendMessage(line("failed", CORAL));
            return CompletableFuture.completedFuture(null);
        }
        Supplier<List<Message>> task = () -> work.apply(moderation);
        return moderation.supply(task).handle((messages, failure) -> {
            if (failure == null) { messages.forEach(context::sendMessage); return null; }
            Throwable cause = failure;
            while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof Feedback feedback) context.sendMessage(feedback.message);
            else if (cause instanceof IllegalArgumentException && cause.getMessage() != null && cause.getMessage().contains("themselves")) context.sendMessage(line("ban.self", CORAL));
            else {
                EterniaModPlugin plugin = EterniaModPlugin.get();
                if (plugin != null) plugin.getLogger().atWarning().withCause(cause).log("Moderation command failed");
                context.sendMessage(line("failed", CORAL));
            }
            return null;
        });
    }

    record Target(UUID id, String name) {}

    /** Online players first, then a player ID, then anyone known to the account store, then old ban records. */
    static Target resolve(ModerationBootstrap moderation, String input) {
        String text = input.trim();
        for (PlayerRef online : Universe.get().getPlayers()) {
            if (online.getUsername().equalsIgnoreCase(text)) return new Target(online.getUuid(), online.getUsername());
        }
        var plugin = EterniaModPlugin.get();
        var accounts = plugin == null ? null : plugin.getServices().accounts();
        UUID id = null;
        try { id = UUID.fromString(text); } catch (IllegalArgumentException notAnId) { /* a name */ }
        if (id != null) {
            UUID found = id;
            String known = accounts == null ? null : accounts.find(found).map(a -> a.displayName()).orElse(null);
            if (known == null) known = moderation.service().history(found).stream().reduce((a, b) -> b).map(ModerationService.Event::name).orElse(null);
            if (known == null) throw new Feedback(line("unknown", CORAL).param("name", name(text)));
            return new Target(found, known);
        }
        if (accounts != null) {
            try {
                var account = accounts.findByName(text);
                if (account.isPresent()) return new Target(account.get().id(), account.get().displayName());
            } catch (DomainException failure) {
                if (failure.code() == DomainException.Code.CONFLICT) throw new Feedback(line("ambiguous", CORAL).param("name", name(text)));
                if (failure.code() != DomainException.Code.INVALID_INPUT) throw failure;
            }
        }
        var banned = moderation.service().findBannedByName(text);
        if (banned.isPresent()) {
            String known = moderation.service().history(banned.get()).stream().reduce((a, b) -> b).map(ModerationService.Event::name).orElse(text);
            return new Target(banned.get(), known);
        }
        throw new Feedback(line("unknown", CORAL).param("name", name(text)));
    }

    static ModerationService.Actor actor(CommandSender sender) {
        return new ModerationService.Actor(sender instanceof PlayerRef player ? player.getUuid() : null,
            sender instanceof PlayerRef player ? player.getUsername() : "Console");
    }

    /** Everything typed after the command name and the first {@code skip} arguments. */
    static String trailing(CommandContext context, String commandName, int skip) {
        return trailing(context.getInputString(), commandName, skip);
    }

    static String trailing(String input, String commandName, int skip) {
        if (input == null) return "";
        String[] tokens = input.trim().split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i].startsWith("/") ? tokens[i].substring(1) : tokens[i];
            if (token.equalsIgnoreCase(commandName)) {
                int from = i + 1 + skip;
                return from >= tokens.length ? "" : String.join(" ", Arrays.copyOfRange(tokens, from, tokens.length));
            }
        }
        return "";
    }

    static boolean isLadderWord(String input) {
        String text = input.trim().toLowerCase(Locale.ROOT);
        return text.equals("auto") || text.equals("next") || text.equals("default") || text.equals("usual");
    }

    private static String join(String first, String rest) { return rest.isBlank() ? first : first + " " + rest; }

    static Message line(String key, String color) { return Message.translation(KEY + key).color(color); }

    static Message name(String text) { return Message.raw(text).bold(true); }

    static Message reasonText(String reason) {
        return reason == null || reason.isBlank() ? Message.translation(KEY + "noReason").italic(true) : Message.raw(reason);
    }

    static Message ends(ModerationService.Ban ban, Instant now) {
        return ban.permanent() ? Message.translation(KEY + "ends.never") : Message.translation(KEY + "ends.in").param("time", TimeWords.between(now, ban.until()));
    }

    private static Message event(ModerationService.Event event) {
        String date = TimeWords.date(event.at());
        return switch (event.kind()) {
            case BAN -> (event.length() == null || event.length().permanent()
                ? line("info.banPermanent", IVORY)
                : line("info.ban", IVORY).param("length", event.length().words()))
                .param("date", date).param("by", event.by().name()).param("reason", reasonText(event.reason()));
            case UNBAN -> line("info.unban", IVORY).param("date", date).param("by", event.by().name()).param("reason", event.reason());
            case EXPIRE -> line("info.expire", SAGE).param("date", date);
        };
    }

    /** A message for staff instead of an error. */
    static final class Feedback extends RuntimeException {
        final transient Message message;
        Feedback(Message message) { super(null, null, false, false); this.message = message; }
    }
}
