package me.perch.shulkers.session;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class OpenShulkerSession {
    public enum SourceType {
        PLAYER_INVENTORY,
        ENDER_CHEST,
        CONTAINER
    }

    private final UUID sessionId;
    private final UUID playerId;
    private final SourceType sourceType;
    private final Inventory directSourceInventory;
    private final int sourceSlot;
    private int placeholderSlot;
    private final Location sourceLocation;
    private final VirtualShulkerHolder holder;
    private final Inventory virtualInventory;
    private final long createdAtMillis;
    private final List<Chunk> ticketedChunks = new ArrayList<>();

    private ItemStack heldShulker;
    private BukkitTask lockTask;
    private BukkitTask openTask;
    private BukkitTask openVerificationTask;
    private boolean sourceLocked;
    private boolean opened;
    private boolean finishing;
    private boolean finished;
    private boolean syncScheduled;
    private boolean switchScheduled;
    private ItemStack finalSourceItem;

    public OpenShulkerSession(
            UUID sessionId,
            UUID playerId,
            SourceType sourceType,
            Inventory directSourceInventory,
            int sourceSlot,
            Location sourceLocation,
            ItemStack heldShulker,
            VirtualShulkerHolder holder,
            Inventory virtualInventory
    ) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.sourceType = Objects.requireNonNull(sourceType, "sourceType");
        this.directSourceInventory = directSourceInventory;
        this.sourceSlot = sourceSlot;
        this.placeholderSlot = sourceSlot;
        this.sourceLocation = sourceLocation == null ? null : sourceLocation.clone();
        this.heldShulker = Objects.requireNonNull(heldShulker, "heldShulker").clone();
        this.holder = Objects.requireNonNull(holder, "holder");
        this.virtualInventory = Objects.requireNonNull(virtualInventory, "virtualInventory");
        this.createdAtMillis = System.currentTimeMillis();
    }

    public UUID getSessionId() {
        return this.sessionId;
    }

    public UUID getPlayerId() {
        return this.playerId;
    }

    public SourceType getSourceType() {
        return this.sourceType;
    }

    public Inventory getDirectSourceInventory() {
        return this.directSourceInventory;
    }

    public int getSourceSlot() {
        return this.sourceSlot;
    }

    public int getPlaceholderSlot() {
        return this.placeholderSlot;
    }

    public void setPlaceholderSlot(int placeholderSlot) {
        this.placeholderSlot = placeholderSlot;
    }

    public Location getSourceLocation() {
        return this.sourceLocation == null ? null : this.sourceLocation.clone();
    }

    public ItemStack getHeldShulker() {
        return this.heldShulker.clone();
    }

    public void setHeldShulker(ItemStack heldShulker) {
        this.heldShulker = Objects.requireNonNull(heldShulker, "heldShulker").clone();
    }

    public VirtualShulkerHolder getHolder() {
        return this.holder;
    }

    public Inventory getVirtualInventory() {
        return this.virtualInventory;
    }

    public long getCreatedAtMillis() {
        return this.createdAtMillis;
    }

    public BukkitTask getLockTask() {
        return this.lockTask;
    }

    public void setLockTask(BukkitTask lockTask) {
        this.lockTask = lockTask;
    }

    public BukkitTask getOpenTask() {
        return this.openTask;
    }

    public void setOpenTask(BukkitTask openTask) {
        this.openTask = openTask;
    }

    public BukkitTask getOpenVerificationTask() {
        return this.openVerificationTask;
    }

    public void setOpenVerificationTask(BukkitTask openVerificationTask) {
        this.openVerificationTask = openVerificationTask;
    }

    public boolean isSourceLocked() {
        return this.sourceLocked;
    }

    public void setSourceLocked(boolean sourceLocked) {
        this.sourceLocked = sourceLocked;
    }

    public boolean isOpened() {
        return this.opened;
    }

    public void setOpened(boolean opened) {
        this.opened = opened;
    }

    public boolean isFinishing() {
        return this.finishing;
    }

    public void setFinishing(boolean finishing) {
        this.finishing = finishing;
    }

    public boolean isFinished() {
        return this.finished;
    }

    public void setFinished(boolean finished) {
        this.finished = finished;
    }

    public boolean isSyncScheduled() {
        return this.syncScheduled;
    }

    public void setSyncScheduled(boolean syncScheduled) {
        this.syncScheduled = syncScheduled;
    }

    public boolean isSwitchScheduled() {
        return this.switchScheduled;
    }

    public void setSwitchScheduled(boolean switchScheduled) {
        this.switchScheduled = switchScheduled;
    }

    public ItemStack getFinalSourceItem() {
        return this.finalSourceItem == null ? null : this.finalSourceItem.clone();
    }

    public void setFinalSourceItem(ItemStack finalSourceItem) {
        this.finalSourceItem = finalSourceItem == null ? null : finalSourceItem.clone();
    }

    public void addTicketedChunk(Chunk chunk) {
        if (chunk != null && !this.ticketedChunks.contains(chunk)) {
            this.ticketedChunks.add(chunk);
        }
    }

    public List<Chunk> getTicketedChunks() {
        return Collections.unmodifiableList(this.ticketedChunks);
    }

    public void clearTicketedChunks() {
        this.ticketedChunks.clear();
    }
}
