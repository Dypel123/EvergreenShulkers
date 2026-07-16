package me.perch.shulkers.listener;

import me.perch.shulkers.OpenShulker;
import me.perch.shulkers.session.OpenShulkerSession;
import me.perch.shulkers.util.ShulkerActions;
import org.bukkit.ChatColor;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public final class ShulkerDupeListener implements Listener {
    private final OpenShulker plugin;
    private final ShulkerActions actions;

    public ShulkerDupeListener(OpenShulker plugin) {
        this.plugin = plugin;
        this.actions = plugin.GetShulkerActions();
    }

    private boolean cancelIfVirtualShulkerOpen(Cancellable event, Player player) {
        if (!this.actions.isPluginVirtualShulker(player.getOpenInventory().getTopInventory())) return false;
        if (!this.actions.HasOpenShulkerBox(player)) return false;
        event.setCancelled(true);
        return true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (this.actions.isSessionPlaceholder(event.getItemInHand())) {
            event.setCancelled(true);
            return;
        }
        cancelIfVirtualShulkerOpen(event, event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (cancelIfVirtualShulkerOpen(event, event.getPlayer())) return;
        handleContainerBreak(event, event.getPlayer(), event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        if (!isProtectedContainer(event.getBlock())) return;
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteractAtEntity(PlayerInteractAtEntityEvent event) {
        cancelIfVirtualShulkerOpen(event, event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        cancelIfVirtualShulkerOpen(event, event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onCraftItem(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (cancelIfVirtualShulkerOpen(event, player)) event.setResult(Event.Result.DENY);
    }

    private void handleContainerBreak(BlockEvent blockEvent, CommandSender sender, Cancellable cancellable) {
        if (!isProtectedContainer(blockEvent.getBlock())) return;
        cancellable.setCancelled(true);

        if (sender instanceof Player) {
            String prefix = ChatColor.translateAlternateColorCodes('&',
                    this.plugin.getConfig().getString("Messages.Prefix", "&c[OpenShulker] "));
            String message = ChatColor.translateAlternateColorCodes('&',
                    this.plugin.getConfig().getString(
                            "Messages.CannotBreakContainer",
                            "&cCannot break: this container holds an active shulker session."
                    ));
            sender.sendMessage(prefix + message);
        }
    }

    private boolean isProtectedContainer(Block block) {
        if (this.actions.hasActiveContainerAt(block)) return true;
        if (!(block.getState() instanceof Container container)) return false;

        Inventory inventory = container.getInventory();
        this.actions.restoreOrphanPlaceholders(inventory, null);
        return this.actions.hasActiveSessionForInventory(inventory)
                || this.actions.containsSessionPlaceholder(inventory);
    }

    private void removeProtectedContainers(List<Block> blocks) {
        blocks.removeIf(this::isProtectedContainer);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        removeProtectedContainers(event.blockList());
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        removeProtectedContainers(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlaceholderUse(PlayerInteractEvent event) {
        if (!this.actions.isSessionPlaceholder(event.getItem())) return;
        event.setCancelled(true);
        event.setUseItemInHand(Event.Result.DENY);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        ItemStack clicked = event.getCurrentItem();
        ItemStack cursor = event.getCursor();

        if (this.actions.isSessionPlaceholder(clicked)) {
            if (event.getClickedInventory() != null) {
                this.actions.tryRestoreOrphanPlaceholder(
                        event.getClickedInventory(),
                        event.getSlot(),
                        player
                );
            }
            event.setCancelled(true);
            return;
        }

        if (this.actions.isSessionPlaceholder(cursor)) {
            event.setCancelled(true);
            return;
        }

        OpenShulkerSession session = this.actions.getSession(player);
        if (session == null) return;

        if (event.getClickedInventory() != null
                && this.actions.isSessionSourceSlot(session, event.getClickedInventory(), event.getSlot())) {
            event.setCancelled(true);
            return;
        }

        if (session.getSourceType() == OpenShulkerSession.SourceType.PLAYER_INVENTORY) {
            if (event.getClick() == ClickType.NUMBER_KEY
                    && event.getHotbarButton() == session.getPlaceholderSlot()) {
                event.setCancelled(true);
                return;
            }

            if (event.getClick() == ClickType.SWAP_OFFHAND && session.getPlaceholderSlot() == 40) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        if (this.actions.isSessionPlaceholder(event.getOldCursor())) {
            event.setCancelled(true);
            return;
        }
        for (ItemStack item : event.getNewItems().values()) {
            if (this.actions.isSessionPlaceholder(item)) {
                event.setCancelled(true);
                return;
            }
        }

        OpenShulkerSession session = this.actions.getSession(player);
        if (session == null) return;

        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            Inventory inventory;
            int localSlot;
            if (rawSlot < topSize) {
                inventory = event.getView().getTopInventory();
                localSlot = rawSlot;
            } else {
                inventory = event.getView().getBottomInventory();
                localSlot = event.getView().convertSlot(rawSlot);
            }

            if (this.actions.isSessionSourceSlot(session, inventory, localSlot)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlaceholderDrop(PlayerDropItemEvent event) {
        if (this.actions.isSessionPlaceholder(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (this.actions.isSessionPlaceholder(event.getMainHandItem())
                || this.actions.isSessionPlaceholder(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemMove(InventoryMoveItemEvent event) {
        // freeze hopper traffic touching an active source inventory
        if (this.actions.isSessionPlaceholder(event.getItem())
                || this.actions.hasActiveSessionForInventory(event.getSource())
                || this.actions.hasActiveSessionForInventory(event.getDestination())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockDispense(BlockDispenseEvent event) {
        if (this.actions.isSessionPlaceholder(event.getItem())) {
            event.setCancelled(true);
        }
    }
}
