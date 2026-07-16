package me.perch.shulkers.listener;

import me.perch.shulkers.OpenShulker;
import me.perch.shulkers.session.OpenShulkerSession;
import me.perch.shulkers.util.ShulkerActions;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public final class ShulkerOpenCloseListener implements Listener {
    private final OpenShulker plugin;
    private final ShulkerActions actions;

    public ShulkerOpenCloseListener(OpenShulker plugin) {
        this.plugin = plugin;
        this.actions = plugin.GetShulkerActions();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHandOpen(PlayerInteractEvent event) {
        if (!this.plugin._allowHandOpen) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR) return;
        if (!event.getPlayer().isSneaking()) return;

        if (this.actions.AttemptToOpenShulkerBox(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInventoryOpenAttempt(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!player.hasPermission("openshulker.use")) return;
        if (event.getClickedInventory() == null) return;
        if (!event.isRightClick() || !event.isShiftClick()) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;
        if (!clicked.getType().name().endsWith("SHULKER_BOX")) return;

        Inventory clickedInventory = event.getClickedInventory();
        OpenShulkerSession currentSession = this.actions.getSession(player);
        if (currentSession != null) {
            if (currentSession.isOpened() && clickedInventory == player.getInventory()) {
                this.actions.scheduleSessionSwitch(
                        player,
                        clickedInventory,
                        event.getSlot(),
                        OpenShulkerSession.SourceType.PLAYER_INVENTORY
                );
            }
            event.setCancelled(true);
            return;
        }

        OpenShulkerSession.SourceType sourceType = this.actions.classifySource(player, clickedInventory);
        if (sourceType == null) return;

        if (sourceType == OpenShulkerSession.SourceType.PLAYER_INVENTORY && !this.plugin._allowInventoryOpen) return;
        if (sourceType == OpenShulkerSession.SourceType.ENDER_CHEST && !this.plugin._allowEnderChestOpen) return;
        if (sourceType == OpenShulkerSession.SourceType.CONTAINER && !this.plugin._allowContainerOpen) return;

        event.setCancelled(true);
        this.actions.AttemptToOpenShulkerBox(player, clickedInventory, event.getSlot(), sourceType);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Inventory top = event.getInventory();
        OpenShulkerSession.SourceType sourceType = this.actions.classifySource(player, top);
        if (sourceType == OpenShulkerSession.SourceType.ENDER_CHEST) {
            this.actions.restoreOrphanPlaceholders(player.getEnderChest(), player);
        } else if (sourceType == OpenShulkerSession.SourceType.CONTAINER) {
            this.actions.restoreOrphanPlaceholders(top, player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVirtualShulkerClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!this.actions.isPluginVirtualShulker(event.getView().getTopInventory())) return;
        this.actions.scheduleSessionSync(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVirtualShulkerDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!this.actions.isPluginVirtualShulker(event.getView().getTopInventory())) return;
        this.actions.scheduleSessionSync(player);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onVirtualShulkerClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        this.actions.finishFromClose(player, event.getInventory());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        ItemStack result = event.getResult();
        if (result == null || !result.getType().name().endsWith("SHULKER_BOX")) return;
        this.actions.unmarkShulkerAsOpen(result);
        event.setResult(result);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        this.actions.finishPlayerSession(player, true, false);
        this.actions.recoverPlayerInventories(player);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        this.actions.finishPlayerSession(player, true, false);
        this.actions.batchUnmarkShulkers(player);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerKick(PlayerKickEvent event) {
        Player player = event.getPlayer();
        this.actions.finishPlayerSession(player, true, false);
        this.actions.batchUnmarkShulkers(player);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        OpenShulkerSession session = this.actions.getSession(player);

        this.actions.finishPlayerSession(player, true, false);
        this.actions.synchronizeDeathDrops(session, event.getDrops(), event.getKeepInventory());
        this.actions.batchUnmarkShulkers(player);
    }
}
