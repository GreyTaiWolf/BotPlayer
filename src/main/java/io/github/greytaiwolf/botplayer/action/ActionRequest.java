package io.github.greytaiwolf.botplayer.action;

import java.util.Set;

public sealed interface ActionRequest permits JumpAction, LookAtAction, MoveInputAction, StopAction, WaitAction, WorldInteractionAction {
   ActionKind kind();

   Set<ActionChannel> channels();
}
