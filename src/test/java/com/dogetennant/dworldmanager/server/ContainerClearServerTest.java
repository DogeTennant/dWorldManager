package com.dogetennant.dworldmanager.server;

import io.papermc.paper.event.entity.ItemTransportingEntityValidateTargetEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.entity.Allay;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseArmorEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Arrays;
import java.util.List;

import static com.dogetennant.dworldmanager.server.TestServer.messages;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /dwm clearcontainers} on a running server: containers, item frames, armor stands, allays,
 * minecarts and dropped items in a real world, tainted (or not) through the server's events.
 */
class ContainerClearServerTest {

    private TestServer mc;
    private PlayerMock admin;
    private PlayerMock steve;

    @BeforeEach
    void setUp() {
        mc = TestServer.start();
        admin = mc.admin();
        steve = mc.player("Steve");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Runs the clear and returns its summary line. */
    private String clear() {
        mc.run(admin, "dwm clearcontainers build");
        List<String> said = messages(admin);
        assertThat(said).hasSize(2);
        assertThat(said.get(0)).isEqualTo(
                "Scanning 'build' for tainted containers - this may take a while, progress will be reported periodically...");
        return said.get(1);
    }

    private static String summary(int containers, int frames, int stands, int allays, int items) {
        return "Container clear complete in 'build': " + containers + " container(s), " + frames + " item frame(s), "
                + stands + " armor stand(s), " + allays + " allay(s), " + items + " dropped item(s) - "
                + (containers + frames + stands + allays + items) + " total.";
    }

    /** A container block the world generated, never touched by a player. */
    private Inventory generated(int x, int y, int z, Material type) {
        mc.block(x, y, z, type);
        return inventoryAt(x, y, z);
    }

    private Inventory inventoryAt(int x, int y, int z) {
        return ((Container) mc.world.getBlockAt(x, y, z).getState()).getInventory();
    }

    /** The player opens the inventory and puts an item into its first slot with the mouse. */
    private void deposit(PlayerMock player, Inventory into, ItemStack item) {
        InventoryView view = player.openInventory(into);
        InventoryClickEvent click = mc.fire(new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0,
                ClickType.LEFT, InventoryAction.PLACE_ALL));
        assertThat(click.isCancelled()).isFalse();
        into.setItem(0, item); // the server moves the item
        player.closeInventory();
    }

    private static boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir();
    }

    private static boolean holdsNothing(Inventory inventory) {
        return Arrays.stream(inventory.getContents()).allMatch(ContainerClearServerTest::isEmpty);
    }

    @Test
    void aChestAPlayerPlacedIsEmptiedAndAGeneratedChestKeepsItsLoot() {
        steve.simulateBlockPlace(Material.CHEST, new Location(mc.world, 1, 10, 1));
        inventoryAt(1, 10, 1).addItem(new ItemStack(Material.DIAMOND, 32));
        generated(3, 10, 1, Material.CHEST).addItem(new ItemStack(Material.GOLDEN_APPLE));

        assertThat(clear()).isEqualTo(summary(1, 0, 0, 0, 0));

        assertThat(holdsNothing(inventoryAt(1, 10, 1))).isTrue();
        assertThat(mc.world.getBlockAt(1, 10, 1).getType()).isEqualTo(Material.CHEST); // the chest itself stays
        assertThat(inventoryAt(3, 10, 1).contains(Material.GOLDEN_APPLE)).isTrue();
    }

    @Test
    void aChestAPlayerPutSomethingIntoIsEmptied() {
        generated(1, 10, 1, Material.CHEST).addItem(new ItemStack(Material.GOLDEN_APPLE));

        deposit(steve, inventoryAt(1, 10, 1), new ItemStack(Material.DIAMOND, 32));

        assertThat(clear()).isEqualTo(summary(1, 0, 0, 0, 0));
        assertThat(holdsNothing(inventoryAt(1, 10, 1))).isTrue();
    }

    @Test
    void takingFromAGeneratedChestLeavesTheRestOfItsLoot() {
        Inventory loot = generated(1, 10, 1, Material.CHEST);
        loot.setItem(0, new ItemStack(Material.GOLDEN_APPLE));
        loot.setItem(1, new ItemStack(Material.IRON_INGOT, 5));
        InventoryView view = steve.openInventory(loot);
        mc.fire(new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT,
                InventoryAction.PICKUP_ALL));
        loot.setItem(0, null);
        steve.closeInventory();

        assertThat(clear()).isEqualTo(summary(0, 0, 0, 0, 0));
        assertThat(inventoryAt(1, 10, 1).contains(Material.IRON_INGOT)).isTrue();
    }

    @Test
    void aChestAHopperFillsIsEmptied() {
        Inventory loot = generated(1, 10, 1, Material.CHEST);
        Inventory hopper = generated(1, 11, 1, Material.HOPPER);
        ItemStack diamond = new ItemStack(Material.DIAMOND);

        mc.fire(new InventoryMoveItemEvent(hopper, diamond, loot, true));
        loot.addItem(diamond);

        assertThat(clear()).isEqualTo(summary(1, 0, 0, 0, 0));
        assertThat(holdsNothing(inventoryAt(1, 10, 1))).isTrue();
    }

    @Test
    void aGeneratedDecoratedPotAPlayerFilledByHandIsEmptied() {
        Block pot = mc.block(1, 10, 1, Material.DECORATED_POT);
        ItemStack diamonds = new ItemStack(Material.DIAMOND, 10);
        steve.getInventory().setItemInMainHand(diamonds);

        mc.fire(new PlayerInteractEvent(steve, Action.RIGHT_CLICK_BLOCK, diamonds, pot, BlockFace.UP, EquipmentSlot.HAND));
        inventoryAt(1, 10, 1).addItem(diamonds); // the server puts them in

        assertThat(clear()).isEqualTo(summary(1, 0, 0, 0, 0));
        assertThat(holdsNothing(inventoryAt(1, 10, 1))).isTrue();
    }

    /** docs/problems/dworldmanager-copper-golem-deliveries-not-tainted.md */
    @Test
    void aChestACopperGolemFillsIsEmptied() {
        Block chest = mc.block(1, 10, 1, Material.CHEST); // generated with the world
        CopperGolem golem = mc.world.spawn(new Location(mc.world, 2, 10, 1), CopperGolem.class);
        ItemStack diamonds = new ItemStack(Material.DIAMOND, 16);
        golem.getEquipment().setItemInMainHand(diamonds); // taken from a copper chest

        // the only event a copper golem fires: it checks the chest before it walks there
        mc.fire(new ItemTransportingEntityValidateTargetEvent(golem, chest));
        inventoryAt(1, 10, 1).addItem(diamonds);

        assertThat(clear()).isEqualTo(summary(1, 0, 0, 0, 0));
        assertThat(holdsNothing(inventoryAt(1, 10, 1))).isTrue();
    }

    @Test
    void aCopperGolemLookingForItemsLeavesAChestAlone() {
        Block chest = mc.block(1, 10, 1, Material.CHEST);
        inventoryAt(1, 10, 1).addItem(new ItemStack(Material.GOLDEN_APPLE));
        CopperGolem golem = mc.world.spawn(new Location(mc.world, 2, 10, 1), CopperGolem.class);

        mc.fire(new ItemTransportingEntityValidateTargetEvent(golem, chest)); // empty hands: fetching

        assertThat(clear()).isEqualTo(summary(0, 0, 0, 0, 0));
        assertThat(inventoryAt(1, 10, 1).contains(Material.GOLDEN_APPLE)).isTrue();
    }

    @Test
    void anItemFrameAPlayerFilledIsEmptiedButAGeneratedOneKeepsItsItem() {
        ItemFrame endShip = mc.world.spawn(new Location(mc.world, 2, 10, 2), ItemFrame.class);
        endShip.setItem(new ItemStack(Material.ELYTRA));
        ItemFrame filled = mc.world.spawn(new Location(mc.world, 4, 10, 2), ItemFrame.class);
        steve.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND));

        mc.fire(new PlayerInteractEntityEvent(steve, filled, EquipmentSlot.HAND));
        filled.setItem(new ItemStack(Material.DIAMOND)); // the server puts it in

        assertThat(clear()).isEqualTo(summary(0, 1, 0, 0, 0));
        assertThat(isEmpty(filled.getItem())).isTrue();
        assertThat(filled.isValid()).isTrue(); // the frame itself stays
        assertThat(endShip.getItem().getType()).isEqualTo(Material.ELYTRA);
    }

    @Test
    void anArmorStandAPlayerDressedIsStrippedButAnUntouchedOneKeepsItsArmor() {
        ArmorStand decoration = mc.world.spawn(new Location(mc.world, 2, 10, 4), ArmorStand.class);
        decoration.getEquipment().setHelmet(new ItemStack(Material.GOLDEN_HELMET)); // set up by staff with a command
        ArmorStand dressed = mc.world.spawn(new Location(mc.world, 4, 10, 4), ArmorStand.class);
        ItemStack chestplate = new ItemStack(Material.DIAMOND_CHESTPLATE);

        mc.fire(new PlayerArmorStandManipulateEvent(steve, dressed, chestplate, ItemStack.empty(),
                EquipmentSlot.CHEST, EquipmentSlot.HAND));
        dressed.getEquipment().setChestplate(chestplate); // the server puts it on

        assertThat(clear()).isEqualTo(summary(0, 0, 1, 0, 0));
        assertThat(isEmpty(dressed.getEquipment().getChestplate())).isTrue();
        assertThat(dressed.isValid()).isTrue();
        assertThat(decoration.getEquipment().getHelmet().getType()).isEqualTo(Material.GOLDEN_HELMET);
    }

    /** docs/problems/dworldmanager-dispensed-armour-not-tainted.md */
    @Test
    void anArmorStandDressedByADispenserIsStripped() {
        ArmorStand stand = mc.world.spawn(new Location(mc.world, 4, 10, 4), ArmorStand.class);
        Block dispenser = mc.block(5, 10, 4, Material.DISPENSER);
        ItemStack netherite = new ItemStack(Material.NETHERITE_CHESTPLATE);

        assertThat(mc.fire(new BlockDispenseArmorEvent(dispenser, netherite, stand)).isCancelled()).isFalse();
        stand.getEquipment().setChestplate(netherite); // the server puts it on

        assertThat(clear()).isEqualTo(summary(0, 0, 1, 0, 0));
        assertThat(isEmpty(stand.getEquipment().getChestplate())).isTrue();
    }

    /** docs/problems/dworldmanager-allay-keeps-held-item.md */
    @Test
    void anAllayIsEmptiedAndLosesTheItemInItsHand() {
        Allay allay = mc.world.spawn(new Location(mc.world, 2, 10, 6), Allay.class);
        // on a server the item a player hands an allay is in its main hand; what it collects goes
        // into its inventory (MockBukkit's own "current item" stands apart from both)
        allay.getEquipment().setItemInMainHand(new ItemStack(Material.SHULKER_BOX));
        allay.getInventory().addItem(new ItemStack(Material.NETHERITE_INGOT));

        assertThat(clear()).isEqualTo(summary(0, 0, 0, 1, 0));
        assertThat(holdsNothing(allay.getInventory())).isTrue();
        assertThat(isEmpty(allay.getEquipment().getItemInMainHand())).isTrue();
    }

    @Test
    void aChestMinecartAPlayerFilledIsEmptiedButAnUntouchedOneKeepsItsLoot() {
        StorageMinecart mineshaft = mc.world.spawn(new Location(mc.world, 2, 10, 8), StorageMinecart.class);
        mineshaft.getInventory().addItem(new ItemStack(Material.RAIL, 8));
        StorageMinecart filled = mc.world.spawn(new Location(mc.world, 4, 10, 8), StorageMinecart.class);

        deposit(steve, filled.getInventory(), new ItemStack(Material.DIAMOND, 32));

        assertThat(clear()).isEqualTo(summary(1, 0, 0, 0, 0));
        assertThat(holdsNothing(filled.getInventory())).isTrue();
        assertThat(mineshaft.getInventory().contains(Material.RAIL)).isTrue();
    }

    @Test
    void droppedItemsAreRemoved() {
        Item dropped = mc.world.dropItem(new Location(mc.world, 6, 4, 6), new ItemStack(Material.DIAMOND, 10));

        assertThat(clear()).isEqualTo(summary(0, 0, 0, 0, 1));
        assertThat(dropped.isValid()).isFalse();
    }

    /** docs/problems/dworldmanager-clear-empties-player-inventories.md */
    @Test
    void aPlayerWhoPutSomethingIntoTheirEnderChestKeepsTheirInventory() {
        steve.getInventory().setItem(0, new ItemStack(Material.DIAMOND_SWORD));
        steve.getInventory().setHelmet(new ItemStack(Material.DIAMOND_HELMET));

        // an ender chest belongs to its player - on a server too
        deposit(steve, steve.getEnderChest(), new ItemStack(Material.COBBLESTONE));

        assertThat(clear()).isEqualTo(summary(0, 0, 0, 0, 0));
        assertThat(steve.getInventory().getItem(0).getType()).isEqualTo(Material.DIAMOND_SWORD);
        assertThat(steve.getInventory().getHelmet().getType()).isEqualTo(Material.DIAMOND_HELMET);
    }

    /** docs/problems/dworldmanager-clear-empties-player-inventories.md */
    @Test
    void aPlayerWhoCraftedSomethingKeepsTheirInventory() {
        steve.getInventory().setItem(0, new ItemStack(Material.DIAMOND_PICKAXE));
        // on a server the crafting grid (in the player's own inventory or at a crafting table)
        // belongs to the player crafting
        Inventory craftingTable = mc.server.createInventory(steve, InventoryType.WORKBENCH);

        deposit(steve, craftingTable, new ItemStack(Material.OAK_PLANKS));

        assertThat(clear()).isEqualTo(summary(0, 0, 0, 0, 0));
        assertThat(steve.getInventory().getItem(0).getType()).isEqualTo(Material.DIAMOND_PICKAXE);
    }

    /** docs/problems/dworldmanager-clear-empties-player-inventories.md */
    @Test
    void aPlayerTaintedBy110KeepsTheirInventory() {
        steve.getInventory().setItem(0, new ItemStack(Material.DIAMOND_SWORD));
        // the flag 1.1.0 left in the player data of everyone who crafted something
        steve.getPersistentDataContainer().set(new NamespacedKey(mc.plugin, "tainted"), PersistentDataType.BYTE, (byte) 1);

        assertThat(clear()).isEqualTo(summary(0, 0, 0, 0, 0));
        assertThat(steve.getInventory().getItem(0).getType()).isEqualTo(Material.DIAMOND_SWORD);
    }
}
