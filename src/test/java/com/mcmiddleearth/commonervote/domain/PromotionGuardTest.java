package com.mcmiddleearth.commonervote.domain;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromotionGuardTest {

    @Test
    void default_only_is_allowed() {
        PromotionGuard.Result r = PromotionGuard.check(Set.of("default"), Set.of());
        assertTrue(r.allowed());
        assertEquals(Optional.empty(), r.blockingGroup());
    }

    @Test
    void no_groups_at_all_is_allowed() {
        // LuckPerms always implies the default group
        PromotionGuard.Result r = PromotionGuard.check(Set.of(), Set.of());
        assertTrue(r.allowed());
        assertEquals(Optional.empty(), r.blockingGroup());
    }

    @Test
    void another_group_blocks_and_is_reported_lowercased() {
        PromotionGuard.Result r = PromotionGuard.check(Set.of("default", "Badge_Mod"), Set.of());
        assertFalse(r.allowed());
        assertEquals(Optional.of("badge_mod"), r.blockingGroup());
    }

    @Test
    void a_promotable_group_does_not_block() {
        PromotionGuard.Result r = PromotionGuard.check(Set.of("default", "Badge_Mod"), Set.of("badge_mod"));
        assertTrue(r.allowed());
        assertEquals(Optional.empty(), r.blockingGroup());
    }

    @Test
    void the_first_blocking_group_in_sorted_order_is_reported() {
        PromotionGuard.Result r = PromotionGuard.check(Set.of("artist", "default", "zeta"), Set.of());
        assertFalse(r.allowed());
        assertEquals(Optional.of("artist"), r.blockingGroup());
    }

    @Test
    void sorting_is_on_the_lowercased_names() {
        // "Zeta" sorts before "alpha" in plain string order, but not once both are lowercased
        PromotionGuard.Result r = PromotionGuard.check(Set.of("Zeta", "alpha"), Set.of());
        assertEquals(Optional.of("alpha"), r.blockingGroup());
    }

    @Test
    void only_the_unlisted_groups_block() {
        PromotionGuard.Result r = PromotionGuard.check(Set.of("default", "artist", "zeta"), Set.of("artist"));
        assertFalse(r.allowed());
        assertEquals(Optional.of("zeta"), r.blockingGroup());
    }

    @Test
    void the_default_group_is_recognised_in_any_case() {
        assertTrue(PromotionGuard.check(Set.of("DEFAULT"), Set.of()).allowed());
    }

    @Test
    void the_promotable_set_is_compared_lowercased_too() {
        assertTrue(PromotionGuard.check(Set.of("badge_mod"), Set.of("Badge_Mod")).allowed());
    }
}
