package me.perch.shulkers.session;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.Objects;
import java.util.UUID;

public final class VirtualShulkerHolder implements InventoryHolder {
    private final UUID sessionId;
    private Inventory inventory;

    public VirtualShulkerHolder(UUID sessionId) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
    }

    public UUID getSessionId() {
        return this.sessionId;
    }

    public void bindInventory(Inventory inventory) {
        if (this.inventory != null) {
            throw new IllegalStateException("Inventory is already bound");
        }
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    @Override
    public Inventory getInventory() {
        return Objects.requireNonNull(this.inventory, "Inventory has not been bound yet");
    }
}
