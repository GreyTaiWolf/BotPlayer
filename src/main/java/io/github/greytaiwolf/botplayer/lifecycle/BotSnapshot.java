package io.github.greytaiwolf.botplayer.lifecycle;

import java.util.UUID;

public record BotSnapshot(UUID botId, String name, BotLifecycleState state) {}
