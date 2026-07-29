package io.github.greytaiwolf.botplayer.navigation.path;

import io.github.greytaiwolf.botplayer.navigation.GridPoint;
import io.github.greytaiwolf.botplayer.navigation.NavigationGoal;
import io.github.greytaiwolf.botplayer.navigation.NavigationPolicy;
import java.util.function.BooleanSupplier;

public interface RoutePlanner {
    RoutePlan plan(
            NavigationSnapshot snapshot,
            GridPoint start,
            NavigationGoal goal,
            NavigationPolicy policy,
            BooleanSupplier cancelled);
}
