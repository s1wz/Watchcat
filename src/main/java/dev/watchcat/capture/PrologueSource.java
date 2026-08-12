package dev.watchcat.capture;

import java.util.List;
import java.util.UUID;

public interface PrologueSource {

    List<CapturedPacket> getPrologue(UUID playerUuid, String playerName);

    boolean hasConfigurationPrologue();

    boolean hasLoginPrologue();

    String getLoginPrologueFailure();
}
