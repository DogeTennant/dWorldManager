package com.dogetennant.dworldmanager.server;

import org.bukkit.ExplosionResult;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Creeper;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayList;
import java.util.List;

import static com.dogetennant.dworldmanager.server.TestServer.messages;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Freezing on a running server: real blocks in a real world, the scans spread over server ticks,
 * the SQLite database, and players, pistons and explosions going through the server's events.
 */
class FreezeServerTest {

    private static final String DENIED = "This block has been grandfathered and can no longer be broken.";

    private TestServer mc;
    private PlayerMock admin;

    @BeforeEach
    void setUp() {
        mc = TestServer.start();
        admin = mc.admin();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Whether the player's attempt to break the block went through. */
    private static boolean breaks(PlayerMock player, Block block) {
        return !player.simulateBlockBreak(block).isCancelled();
    }

    @Test
    void aRegionFreezeGrandfathersTheRestrictedBlocksInsideIt() {
        Block diamond = mc.block(1, 10, 1, Material.DIAMOND_BLOCK);
        Block gold = mc.block(2, 11, 2, Material.GOLD_BLOCK);
        Block stone = mc.block(1, 10, 2, Material.STONE);
        Block outside = mc.block(5, 10, 5, Material.DIAMOND_BLOCK);

        mc.run(admin, "dwm freeze region build 0 10 0 2 11 2");

        assertThat(messages(admin)).containsExactly("Freeze region complete: 2 block(s) grandfathered in 'build'.");
        PlayerMock steve = mc.player("Steve");
        assertThat(breaks(steve, diamond)).isFalse();
        assertThat(breaks(steve, gold)).isFalse();
        assertThat(diamond.getType()).isEqualTo(Material.DIAMOND_BLOCK);
        assertThat(messages(steve)).containsExactly(DENIED, DENIED);

        assertThat(breaks(steve, stone)).isTrue();
        assertThat(breaks(steve, outside)).isTrue();
        assertThat(outside.getType()).isEqualTo(Material.AIR);
    }

    @Test
    void aWorldFreezeScansEveryGeneratedChunkInsideTheBorder() {
        // the 32-block border covers the chunks -1..1 on both axes
        mc.block(1, 10, 1, Material.DIAMOND_BLOCK);       // chunk 0,0
        mc.block(-5, 2, -5, Material.EMERALD_BLOCK);      // chunk -1,-1
        mc.block(20, 31, 20, Material.BEACON);            // chunk 1,1, top of the world
        Block beyond = mc.block(40, 10, 40, Material.DIAMOND_BLOCK); // chunk 2,2 - outside the border
        mc.world.notGenerated(1, -1);
        mc.world.getChunkAt(0, 0); // in use (players nearby): loaded before the scan
        assertThat(mc.world.isChunkLoaded(1, 1)).isFalse();

        mc.run(admin, "dwm freeze build");

        assertThat(messages(admin)).containsExactly(
                "Scanning 'build' for 5 material(s) to freeze - this may take a while, progress will be reported periodically...",
                "Freeze scan complete: 3 block(s) grandfathered in 'build'.");
        assertThat(mc.plugin.getBlockFreezeService().isFrozen(beyond)).isFalse();
        // a chunk that was loaded only for the scan is unloaded again; one in use stays loaded;
        // one that was never generated is not touched (loading it would generate it)
        assertThat(mc.world.isChunkLoaded(1, 1)).isFalse();
        assertThat(mc.world.isChunkLoaded(0, 0)).isTrue();
        assertThat(mc.world.wasEverLoaded(1, -1)).isFalse();
    }

    @Test
    void aFrozenBlockIsStillFrozenAfterARestart() {
        Block diamond = mc.block(1, 10, 1, Material.DIAMOND_BLOCK);
        mc.run(admin, "dwm freeze region build 1 10 1 1 10 1");

        mc.restart();

        assertThat(breaks(mc.player("Steve"), diamond)).isFalse();
        assertThat(diamond.getType()).isEqualTo(Material.DIAMOND_BLOCK);
    }

    @Test
    void placedOnlyFreezesWhatPlayersPlacedAndLeavesNaturalBlocks() {
        PlayerMock steve = mc.player("Steve");
        steve.simulateBlockPlace(Material.DIAMOND_BLOCK, new Location(mc.world, 1, 10, 1));
        Block natural = mc.block(2, 10, 1, Material.DIAMOND_BLOCK);
        mc.settle();

        mc.run(admin, "dwm freeze region build 0 10 0 3 10 3 --placed-only");

        assertThat(messages(admin)).containsExactly(
                "Freeze region complete: 1 block(s) grandfathered in 'build' (player-placed only).");
        assertThat(breaks(steve, mc.world.getBlockAt(1, 10, 1))).isFalse();
        assertThat(breaks(steve, natural)).isTrue();
    }

    @Test
    void aBlockPlacedJustBeforeTheServerStopsIsStillKnownAsPlaced() {
        mc.player("Steve").simulateBlockPlace(Material.DIAMOND_BLOCK, new Location(mc.world, 1, 10, 1));

        mc.restart(); // the write is still queued when the plugin stops: it is written first

        mc.run(admin, "dwm freeze region build 1 10 1 1 10 1 --placed-only");
        assertThat(messages(admin)).containsExactly(
                "Freeze region complete: 1 block(s) grandfathered in 'build' (player-placed only).");
    }

    @Test
    void aPlacedBlockThatWasBrokenIsForgotten() {
        PlayerMock steve = mc.player("Steve");
        steve.simulateBlockPlace(Material.DIAMOND_BLOCK, new Location(mc.world, 1, 10, 1));
        mc.settle();
        steve.simulateBlockBreak(mc.world.getBlockAt(1, 10, 1));
        mc.block(1, 10, 1, Material.DIAMOND_BLOCK); // whatever ends up there later is not "placed"
        mc.settle();

        mc.run(admin, "dwm freeze region build 1 10 1 1 10 1 --placed-only");

        assertThat(messages(admin)).containsExactly(
                "Freeze region complete: 0 block(s) grandfathered in 'build' (player-placed only).");
    }

    /** docs/problems/dworldmanager-placed-mark-lost-on-piston-move.md */
    @Test
    void placedBlocksPushedByAPistonAreStillPlacedWhereTheyLand() {
        PlayerMock steve = mc.player("Steve");
        steve.simulateBlockPlace(Material.DIAMOND_BLOCK, new Location(mc.world, 1, 10, 1));
        steve.simulateBlockPlace(Material.DIAMOND_BLOCK, new Location(mc.world, 2, 10, 1));
        Block natural = mc.block(3, 10, 1, Material.DIAMOND_BLOCK);
        mc.settle();
        Block piston = mc.block(0, 10, 1, Material.PISTON);
        BlockPistonExtendEvent push = mc.fire(new BlockPistonExtendEvent(piston,
                List.of(mc.world.getBlockAt(1, 10, 1), mc.world.getBlockAt(2, 10, 1), natural), BlockFace.EAST));
        assertThat(push.isCancelled()).isFalse();
        // the server moves the row one to the east
        mc.block(1, 10, 1, Material.PISTON_HEAD);
        mc.block(4, 10, 1, Material.DIAMOND_BLOCK);

        mc.run(admin, "dwm freeze region build 0 10 0 5 10 3 --placed-only");

        assertThat(messages(admin)).containsExactly(
                "Freeze region complete: 2 block(s) grandfathered in 'build' (player-placed only).");
        assertThat(frozenAt(2, 10, 1)).isTrue();
        assertThat(frozenAt(3, 10, 1)).isTrue();
        assertThat(frozenAt(4, 10, 1)).isFalse(); // the natural one stays natural
    }

    /** docs/problems/dworldmanager-placed-mark-lost-on-piston-move.md */
    @Test
    void aPlacedBlockPulledByAStickyPistonIsStillPlacedWhereItLands() {
        mc.player("Steve").simulateBlockPlace(Material.DIAMOND_BLOCK, new Location(mc.world, 2, 10, 1));
        mc.settle();
        // a sticky piston at x 0 facing east pulls the block from x 2 to x 1; the server gives the
        // direction the block moves in (west), not the one the piston faces
        Block piston = mc.block(0, 10, 1, Material.STICKY_PISTON);
        mc.fire(new BlockPistonRetractEvent(piston, List.of(mc.world.getBlockAt(2, 10, 1)), BlockFace.WEST));
        mc.block(2, 10, 1, Material.AIR);
        mc.block(1, 10, 1, Material.DIAMOND_BLOCK);

        mc.run(admin, "dwm freeze region build 0 10 0 3 10 3 --placed-only");

        assertThat(messages(admin)).containsExactly(
                "Freeze region complete: 1 block(s) grandfathered in 'build' (player-placed only).");
        assertThat(frozenAt(1, 10, 1)).isTrue();
    }

    private boolean frozenAt(int x, int y, int z) {
        return mc.plugin.getBlockFreezeService().isFrozen(mc.world.getBlockAt(x, y, z));
    }

    @Test
    void pistonsCannotMoveAFrozenBlockAndExplosionsLeaveItStanding() {
        Block frozen = mc.block(1, 10, 1, Material.DIAMOND_BLOCK);
        Block stone = mc.block(1, 11, 1, Material.STONE);
        mc.run(admin, "dwm freeze region build 1 10 1 1 10 1");
        Block piston = mc.block(0, 10, 1, Material.STICKY_PISTON);

        assertThat(mc.fire(new BlockPistonExtendEvent(piston, List.of(frozen), BlockFace.EAST)).isCancelled()).isTrue();
        assertThat(mc.fire(new BlockPistonRetractEvent(piston, List.of(frozen), BlockFace.WEST)).isCancelled()).isTrue();
        assertThat(mc.fire(new BlockPistonExtendEvent(piston, List.of(stone), BlockFace.EAST)).isCancelled()).isFalse();

        Creeper creeper = mc.world.spawn(new Location(mc.world, 2, 10, 2), Creeper.class);
        EntityExplodeEvent explosion = mc.fire(new EntityExplodeEvent(creeper, creeper.getLocation(),
                new ArrayList<>(List.of(frozen, stone)), 1f, ExplosionResult.DESTROY));
        assertThat(explosion.blockList()).containsExactly(stone);
    }

    @Test
    void freezeAndUnfreezeTheBlockAPlayerLooksAt() {
        TestServer.LookingPlayer staff = mc.lookingPlayer("Staff");
        staff.setOp(true);
        staff.lookingAt = mc.block(1, 10, 1, Material.CRYING_OBSIDIAN); // any block, restricted or not

        mc.run(staff, "dwm freeze block");
        mc.run(staff, "dwm freeze block");
        assertThat(breaks(mc.player("Steve"), staff.lookingAt)).isFalse();
        mc.run(staff, "dwm unfreeze block");
        mc.run(staff, "dwm unfreeze block");
        staff.lookingAt = null;
        mc.run(staff, "dwm freeze block");

        assertThat(messages(staff)).containsExactly(
                "CRYING_OBSIDIAN at 1,10,1 is now frozen.",
                "That block (CRYING_OBSIDIAN at 1,10,1) is already frozen.",
                "CRYING_OBSIDIAN at 1,10,1 unfrozen.",
                "That block isn't frozen.",
                "You're not looking at a block.");
    }

    @Test
    void unfreezingARegionFreesItsBlocksAndIsLogged() {
        Block diamond = mc.block(1, 10, 1, Material.DIAMOND_BLOCK);
        mc.block(2, 10, 2, Material.GOLD_BLOCK);
        mc.run(admin, "dwm freeze region build 0 10 0 2 10 2");
        messages(admin);

        mc.run(admin, "dwm unfreeze region build 0 10 0 2 10 2");
        mc.run(admin, "dwm auditlog");

        List<String> said = messages(admin);
        assertThat(said).hasSize(3);
        assertThat(said.get(0)).isEqualTo("2 block(s) unfrozen in the selected region in 'build'.");
        assertThat(said.get(1)).isEqualTo("Last 1 unfreeze action(s):");
        assertThat(said.get(2)).endsWith("] Admin unfroze 2x all materials in region 0,10,0 - 2,10,2 of 'build'");
        assertThat(breaks(mc.player("Steve"), diamond)).isTrue();
    }

    @Test
    void frozenListsTheCountsAndUnfreezingOneMaterialKeepsTheOthers() {
        mc.block(1, 10, 1, Material.DIAMOND_BLOCK);
        mc.block(2, 10, 1, Material.DIAMOND_BLOCK);
        Block gold = mc.block(1, 10, 2, Material.GOLD_BLOCK);
        mc.run(admin, "dwm freeze region build 0 10 0 2 10 2");
        messages(admin);

        mc.run(admin, "dwm frozen build");
        mc.run(admin, "dwm unfreeze build GOLD_BLOCK");
        mc.run(admin, "dwm frozen build diamond_block");

        assertThat(messages(admin)).containsExactly(
                "3 frozen block(s) in 'build':",
                "  DIAMOND_BLOCK: 2",
                "  GOLD_BLOCK: 1",
                "1 block(s) of GOLD_BLOCK unfrozen in 'build'.",
                "2 frozen DIAMOND_BLOCK in 'build':",
                "  1,10,1",
                "  2,10,1");
        assertThat(breaks(mc.player("Steve"), gold)).isTrue();
    }
}
