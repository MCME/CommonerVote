package com.mcmiddleearth.commonervote.domain;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Decides whether a player may be promoted by a vote, from the LuckPerms groups they sit in directly.
 * <p>
 * Only a plain guest may be promoted: someone who already holds another group (a badge, a role) would lose it when
 * {@code parent set} replaces their groups. A group the admins listed as promotable is the exception. A pure
 * function on group names, with no LuckPerms code.
 */
public final class PromotionGuard {

    /** LuckPerms puts everyone in this group, so it never counts as something to lose. */
    private static final String DEFAULT_GROUP = "default";

    private PromotionGuard() {
    }

    /**
     * @param blockingGroup the group that stops the promotion: the first in sorted order when there are several,
     *                      lowercase; empty when {@code allowed}
     */
    public record Result(boolean allowed, Optional<String> blockingGroup) {
    }

    /**
     * @param directGroups     the groups the player is a direct member of, in any case
     * @param promotableGroups the groups that do not stop a promotion, in any case
     */
    public static Result check(Set<String> directGroups, Set<String> promotableGroups) {
        Set<String> blocking = new TreeSet<>();
        for (String group : directGroups) {
            blocking.add(group.toLowerCase(Locale.ROOT));
        }
        blocking.remove(DEFAULT_GROUP);
        for (String group : promotableGroups) {
            blocking.remove(group.toLowerCase(Locale.ROOT));
        }
        if (blocking.isEmpty()) {
            return new Result(true, Optional.empty());
        }
        return new Result(false, Optional.of(blocking.iterator().next()));
    }
}
