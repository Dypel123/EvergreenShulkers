package me.perch.shulkers.listener;

import me.perch.shulkers.OpenShulker;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public final class ShulkerReadOnlyListener implements Listener {
    private final OpenShulker plugin;

    public ShulkerReadOnlyListener(OpenShulker plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onShulkerInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!this.plugin.GetShulkerActions().isPluginVirtualShulker(event.getView().getTopInventory())) return;
        if (player.hasPermission("openshulker.write")) return;

        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onShulkerInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!this.plugin.GetShulkerActions().isPluginVirtualShulker(event.getView().getTopInventory())) return;
        if (player.hasPermission("openshulker.write")) return;

        event.setCancelled(true);
    }
}
