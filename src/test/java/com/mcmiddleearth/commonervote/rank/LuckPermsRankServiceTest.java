package com.mcmiddleearth.commonervote.rank;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.mcmiddleearth.commonervote.Permissions;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.cacheddata.CachedDataManager;
import net.luckperms.api.cacheddata.CachedPermissionData;
import net.luckperms.api.context.ContextManager;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.util.Tristate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LuckPermsRankServiceTest {

    private final UUID bilbo = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private final UUID frodo = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    private final UUID sam = UUID.fromString("00000000-0000-0000-0000-00000000005a");

    private final LuckPerms lp = mock(LuckPerms.class);
    private final UserManager users = mock(UserManager.class);
    private final ContextManager contexts = mock(ContextManager.class);
    private final QueryOptions queryOptions = mock(QueryOptions.class);
    private LuckPermsRankService service;

    @BeforeEach
    void setUp() {
        when(lp.getUserManager()).thenReturn(users);
        when(lp.getContextManager()).thenReturn(contexts);
        when(contexts.getStaticQueryOptions()).thenReturn(queryOptions);
        service = new LuckPermsRankService(lp);
    }

    @Test
    void lookup_present() {
        when(users.lookupUniqueId("Bilbo_B")).thenReturn(CompletableFuture.completedFuture(bilbo));

        assertEquals(Optional.of(bilbo), service.lookup("Bilbo_B").join());
    }

    @Test
    void lookup_absent() {
        when(users.lookupUniqueId("nobody")).thenReturn(CompletableFuture.completedFuture(null));

        assertEquals(Optional.empty(), service.lookup("nobody").join());
    }

    @Test
    void names_skips_unknown() {
        when(users.lookupUsername(bilbo)).thenReturn(CompletableFuture.completedFuture("Bilbo_B"));
        when(users.lookupUsername(frodo)).thenReturn(CompletableFuture.completedFuture(null));
        when(users.lookupUsername(sam)).thenReturn(CompletableFuture.completedFuture("Sam"));

        Map<UUID, String> names = service.names(List.of(bilbo, frodo, sam)).join();

        assertEquals(Map.of(bilbo, "Bilbo_B", sam, "Sam"), names);
    }

    @Test
    void names_skips_failed_lookup() {
        when(users.lookupUsername(bilbo)).thenReturn(CompletableFuture.completedFuture("Bilbo_B"));
        when(users.lookupUsername(frodo)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("storage down")));

        Map<UUID, String> names = service.names(List.of(bilbo, frodo)).join();

        assertEquals(Map.of(bilbo, "Bilbo_B"), names, "one failed lookup must not fail the others");
    }

    @Test
    void names_empty_input() {
        CompletableFuture<Map<UUID, String>> names = service.names(List.of());

        assertTrue(names.isDone());
        assertEquals(Map.of(), names.join());
    }

    @Test
    void names_asks_once_per_player_and_result_is_read_only() {
        when(users.lookupUsername(bilbo)).thenReturn(CompletableFuture.completedFuture("Bilbo_B"));

        Map<UUID, String> names = service.names(List.of(bilbo, bilbo)).join();

        assertEquals(Map.of(bilbo, "Bilbo_B"), names);
        assertThrows(UnsupportedOperationException.class, () -> names.put(sam, "Sam"));
    }

    @Test
    void exempt_true() {
        stubPermission(bilbo, Tristate.TRUE);

        assertTrue(service.isExempt(bilbo).join());
    }

    @Test
    void exempt_false_when_unset_or_denied() {
        stubPermission(bilbo, Tristate.UNDEFINED);
        stubPermission(frodo, Tristate.FALSE);

        assertFalse(service.isExempt(bilbo).join());
        assertFalse(service.isExempt(frodo).join());
    }

    @Test
    void direct_groups() {
        stubGroups(bilbo, "default", "artist");

        assertEquals(Set.of("default", "artist"), service.directGroups(bilbo).join());
    }

    @Test
    void direct_groups_are_lowercase_and_read_only() {
        stubGroups(bilbo, "Default", "ARTIST");

        Set<String> groups = service.directGroups(bilbo).join();

        assertEquals(Set.of("default", "artist"), groups);
        assertThrows(UnsupportedOperationException.class, () -> groups.add("commoner"));
    }

    @Test
    void direct_groups_empty_when_no_inheritance_nodes() {
        stubGroups(bilbo);

        assertEquals(Set.of(), service.directGroups(bilbo).join());
    }

    /** The exempt check reads the permission data with the static query options, as the brief says. */
    private void stubPermission(UUID player, Tristate result) {
        User user = mock(User.class);
        CachedDataManager data = mock(CachedDataManager.class);
        CachedPermissionData permissions = mock(CachedPermissionData.class);
        when(users.loadUser(player)).thenReturn(CompletableFuture.completedFuture(user));
        when(user.getCachedData()).thenReturn(data);
        when(data.getPermissionData(queryOptions)).thenReturn(permissions);
        when(permissions.checkPermission(Permissions.EXEMPT)).thenReturn(result);
    }

    private void stubGroups(UUID player, String... groupNames) {
        User user = mock(User.class);
        List<InheritanceNode> nodes = Arrays.stream(groupNames).map(name -> {
            InheritanceNode node = mock(InheritanceNode.class);
            when(node.getGroupName()).thenReturn(name);
            return node;
        }).toList();
        when(users.loadUser(player)).thenReturn(CompletableFuture.completedFuture(user));
        when(user.getNodes(NodeType.INHERITANCE)).thenReturn(nodes);
    }
}
