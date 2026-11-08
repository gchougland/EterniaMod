package com.hexvane.eterniamod.housing;

import com.hexvane.eterniamod.hexplots.HexClaimRules;
import com.hypixel.hytale.server.core.Message;
import java.util.Objects;

/**
 * Outcome of a hex claim or expansion commit ({@link NativeClaimCoordinator#claimCells} and
 * {@link NativeClaimCoordinator#expandPlot}).
 *
 * @param ok            true when the land is the player's now (claimed or grown) and the world shows it
 * @param playerMessage the friendly line already sent to the player, in English (logs, pages that print it)
 * @param plotId        the plot's id ({@code UUID} text); null when nothing was claimed
 * @param message       the same line as a translatable {@link Message}, for pages that show it again
 * @param reason        the rule result behind a refusal (for map pages that colour the cell), or an allowed reason
 */
public record HexCommitResult(boolean ok, String playerMessage, String plotId, Message message, HexClaimRules.Result reason) {
    public HexCommitResult {
        Objects.requireNonNull(playerMessage);
        Objects.requireNonNull(message);
        Objects.requireNonNull(reason);
    }

    static HexCommitResult success(HexPlotText.Line line, String plotId, HexClaimRules.Result reason) {
        return new HexCommitResult(true, line.english(), plotId, line.message(), reason);
    }

    static HexCommitResult refused(HexClaimRules.Result reason) {
        var line = HexPlotText.reason(reason);
        return new HexCommitResult(false, line.english(), null, line.message(), reason);
    }

    static HexCommitResult failed(HexPlotText.Line line, String plotId) {
        return new HexCommitResult(false, line.english(), plotId, line.message(), HexClaimRules.Result.of(HexClaimRules.Reason.CHANGED));
    }
}
