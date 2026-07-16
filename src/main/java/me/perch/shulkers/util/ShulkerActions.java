package me.perch.shulkers.util;

import me.perch.shulkers.OpenShulker;
import me.perch.shulkers.session.OpenShulkerSession;
import me.perch.shulkers.session.VirtualShulkerHolder;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ShulkerActions {
    private final NamespacedKey openShulkerKey;
    private final NamespacedKey openShulkerLocationKey;
    private final NamespacedKey legacyTimestampKey;

    private final NamespacedKey placeholderKey;
    private final NamespacedKey recoveryItemKey;
    private final NamespacedKey sourceTypeKey;
    private final NamespacedKey sourceSlotKey;
    private final NamespacedKey sourceOwnerKey;
    private final NamespacedKey sourceWorldKey;
    private final NamespacedKey sourceXKey;
    private final NamespacedKey sourceYKey;
    private final NamespacedKey sourceZKey;

    private final OpenShulker plugin;
    private final Map<UUID, OpenShulkerSession> sessionsByPlayer = new HashMap<>();
    private final Map<UUID, OpenShulkerSession> sessionsById = new HashMap<>();
    private final Set<UUID> warnedQuarantinedPlaceholders = new HashSet<>();
    private final BukkitTask watchdogTask;

    public ShulkerActions(OpenShulker plugin) {
        this.plugin = plugin;
        this.openShulkerKey = new NamespacedKey(plugin, "openshulker");
        this.openShulkerLocationKey = new NamespacedKey(plugin, "openshulkerlocation");
        this.legacyTimestampKey = new NamespacedKey(plugin, "openshulker_ts");

        this.placeholderKey = new NamespacedKey(plugin, "session_placeholder");
        this.recoveryItemKey = new NamespacedKey(plugin, "session_recovery_item");
        this.sourceTypeKey = new NamespacedKey(plugin, "session_source_type");
        this.sourceSlotKey = new NamespacedKey(plugin, "session_source_slot");
        this.sourceOwnerKey = new NamespacedKey(plugin, "session_source_owner");
        this.sourceWorldKey = new NamespacedKey(plugin, "session_source_world");
        this.sourceXKey = new NamespacedKey(plugin, "session_source_x");
        this.sourceYKey = new NamespacedKey(plugin, "session_source_y");
        this.sourceZKey = new NamespacedKey(plugin, "session_source_z");

        this.watchdogTask = Bukkit.getScheduler().runTaskTimer(plugin, this::runSessionWatchdog, 20L, 20L);
    }

    public boolean AttemptToOpenShulkerBox(Player player) {
        PlayerInventory inventory = player.getInventory();
        return this.AttemptToOpenShulkerBox(
                player,
                inventory,
                inventory.getHeldItemSlot(),
                OpenShulkerSession.SourceType.PLAYER_INVENTORY
        );
    }

    public boolean AttemptToOpenShulkerBox(
            Player player,
            Inventory sourceInventory,
            int sourceSlot,
            OpenShulkerSession.SourceType requestedSourceType
    ) {
        this.requirePrimaryThread();
        if (!player.hasPermission("openshulker.use")) return false;
        if (this.sessionsByPlayer.containsKey(player.getUniqueId())) return false;
        if (sourceInventory == null || sourceSlot < 0 || sourceSlot >= sourceInventory.getSize()) return false;

        OpenShulkerSession.SourceType actualSourceType = this.classifySource(player, sourceInventory);
        if (actualSourceType == null || actualSourceType != requestedSourceType) return false;

        sourceInventory = this.canonicalizeSourceInventory(player, sourceInventory, actualSourceType);
        if (sourceInventory == null || sourceSlot >= sourceInventory.getSize()) return false;

        ItemStack sourceItem = sourceInventory.getItem(sourceSlot);
        if (!isValidShulker(sourceItem)) return false;

        UUID legacyMarker = this.readLegacySessionId(sourceItem);
        if (legacyMarker != null && this.sessionsById.containsKey(legacyMarker)) return false;
        if (legacyMarker != null) {
            this.clearLegacyItemFlag(sourceItem);
            sourceInventory.setItem(sourceSlot, sourceItem);
        }

        ItemStack heldShulker = sourceItem.clone();
        ShulkerBox sourceState = getShulkerState(heldShulker);
        if (sourceState == null) return false;

        UUID sessionId = UUID.randomUUID();
        VirtualShulkerHolder holder = new VirtualShulkerHolder(sessionId);
        Inventory virtualInventory = Bukkit.createInventory(holder, InventoryType.SHULKER_BOX);
        holder.bindInventory(virtualInventory);
        virtualInventory.setContents(cloneContents(sourceState.getInventory().getContents()));

        Location sourceLocation = actualSourceType == OpenShulkerSession.SourceType.CONTAINER
                ? this.getContainerAnchorLocation(sourceInventory)
                : null;
        Inventory directInventory = actualSourceType == OpenShulkerSession.SourceType.CONTAINER
                ? null
                : sourceInventory;

        OpenShulkerSession session = new OpenShulkerSession(
                sessionId,
                player.getUniqueId(),
                actualSourceType,
                directInventory,
                sourceSlot,
                sourceLocation,
                heldShulker,
                holder,
                virtualInventory
        );

        // register the transaction before doing anything
        this.sessionsByPlayer.put(player.getUniqueId(), session);
        this.sessionsById.put(sessionId, session);
        this.clearLegacyPlayerState(player);

        BukkitTask lockTask = Bukkit.getScheduler().runTask(
                this.plugin,
                () -> this.lockSourceAndScheduleOpen(player, sessionId)
        );
        session.setLockTask(lockTask);
        return true;
    }

    private void lockSourceAndScheduleOpen(Player player, UUID sessionId) {
        OpenShulkerSession session = this.sessionsById.get(sessionId);
        if (!this.isCurrentSession(player, session) || session.isFinished()) return;

        if (!player.isOnline()) {
            this.finishSession(session, player, false, false, false);
            return;
        }

        Inventory sourceInventory = this.resolveSourceInventory(session, player);
        if (sourceInventory == null || session.getSourceSlot() >= sourceInventory.getSize()) {
            this.cancelUnstartedSession(session, player, "source inventory is no longer available");
            return;
        }

        ItemStack current = sourceInventory.getItem(session.getSourceSlot());
        if (!this.matchesHeldShulker(current, session.getHeldShulker())) {
            this.cancelUnstartedSession(session, player, "source shulker moved before it could be locked");
            return;
        }

        ItemStack placeholder = this.createPlaceholder(session);
        if (placeholder == null) {
            this.cancelUnstartedSession(session, player, "could not serialize the source shulker");
            return;
        }

        sourceInventory.setItem(session.getSourceSlot(), placeholder);
        session.setPlaceholderSlot(session.getSourceSlot());
        session.setSourceLocked(true);
        this.addContainerChunkTickets(session);

        long seconds = Math.max(0L, this.plugin.getConfig().getLong("WaitSecondsBeforeOpen", 0L));
        long delayTicks = seconds * 20L;
        if (delayTicks == 0L) {
            this.openScheduledSession(player, sessionId);
            return;
        }

        BukkitTask openTask = Bukkit.getScheduler().runTaskLater(
                this.plugin,
                () -> this.openScheduledSession(player, sessionId),
                delayTicks
        );
        session.setOpenTask(openTask);
    }

    private void cancelUnstartedSession(OpenShulkerSession session, Player player, String reason) {
        this.plugin.getLogger().warning(
                "Cancelled shulker session " + session.getSessionId() + " for " + player.getName() + ": " + reason + "."
        );
        this.finishSession(session, player, false, false, false);
    }

    private void openScheduledSession(Player player, UUID sessionId) {
        OpenShulkerSession session = this.sessionsById.get(sessionId);
        if (!this.isCurrentSession(player, session) || session.isFinished()) return;

        if (!player.isOnline()) {
            this.finishSession(session, player, false, false, false);
            return;
        }

        PlaceholderResolution placeholder = this.resolvePlaceholder(session, player);
        if (placeholder == null) {
            this.plugin.getLogger().severe(
                    "The placeholder for shulker session " + sessionId + " disappeared before the GUI opened."
            );
            this.finishSession(session, player, false, false, false);
            return;
        }
        session.setPlaceholderSlot(placeholder.slot());

        InventoryView openedView = player.openInventory(session.getVirtualInventory());
        if (openedView == null || openedView.getTopInventory() != session.getVirtualInventory()) {
            this.finishSession(session, player, false, false, false);
            return;
        }

        session.setOpened(true);
        this.playConfiguredSound(player, "OpenSound", Sound.BLOCK_SHULKER_BOX_OPEN);

        BukkitTask verificationTask = Bukkit.getScheduler().runTask(this.plugin, () -> {
            OpenShulkerSession current = this.sessionsById.get(sessionId);
            if (!this.isCurrentSession(player, current) || current.isFinished()) return;
            if (player.getOpenInventory().getTopInventory() == current.getVirtualInventory()) return;

            this.plugin.getLogger().warning(
                    "Virtual shulker GUI did not remain open for session " + sessionId + "; restoring the source item."
            );
            this.finishSession(current, player, false, false, false);
        });
        session.setOpenVerificationTask(verificationTask);
    }

    public void scheduleSessionSync(Player player) {
        OpenShulkerSession session = this.sessionsByPlayer.get(player.getUniqueId());
        if (session == null || session.isFinished() || session.isFinishing()
                || !session.isOpened() || session.isSyncScheduled()) return;

        session.setSyncScheduled(true);
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            session.setSyncScheduled(false);
            if (!this.isCurrentSession(player, session) || session.isFinished() || session.isFinishing()) return;

            ItemStack updated = session.getHeldShulker();
            if (!this.writeContentsToShulker(updated, session.getVirtualInventory().getContents())) {
                this.plugin.getLogger().severe("Could not update held shulker for session " + session.getSessionId() + ".");
                this.finishSession(session, player, false, false, true);
                return;
            }
            session.setHeldShulker(updated);

            if (!this.refreshPlaceholderRecovery(session, player)) {
                this.plugin.getLogger().severe(
                        "The source placeholder disappeared during session " + session.getSessionId()
                                + "; returning the authoritative shulker and closing the GUI."
                );
                this.finishSession(session, player, true, false, true);
            }
        });
    }

    public boolean scheduleSessionSwitch(
            Player player,
            Inventory nextSourceInventory,
            int nextSourceSlot,
            OpenShulkerSession.SourceType nextSourceType
    ) {
        if (!player.hasPermission("openshulker.use")) return false;

        OpenShulkerSession current = this.sessionsByPlayer.get(player.getUniqueId());
        if (current == null || current.isFinished() || current.isFinishing() || !current.isOpened()) return false;
        if (current.isSwitchScheduled()) return false;
        if (nextSourceInventory == null || nextSourceSlot < 0 || nextSourceSlot >= nextSourceInventory.getSize()) return false;
        if (this.isSessionSourceSlot(current, nextSourceInventory, nextSourceSlot)) return false;
        if (this.classifySource(player, nextSourceInventory) != nextSourceType) return false;

        ItemStack nextSourceItem = nextSourceInventory.getItem(nextSourceSlot);
        if (!isValidShulker(nextSourceItem)) return false;
        if (this.readLegacySessionId(nextSourceItem) != null) return false;

        current.setSwitchScheduled(true);
        UUID currentSessionId = current.getSessionId();
        ItemStack expectedNextItem = nextSourceItem.clone();

        Bukkit.getScheduler().runTask(this.plugin, () -> this.performSessionSwitch(
                player,
                currentSessionId,
                nextSourceInventory,
                nextSourceSlot,
                nextSourceType,
                expectedNextItem
        ));
        return true;
    }

    private void performSessionSwitch(
            Player player,
            UUID currentSessionId,
            Inventory nextSourceInventory,
            int nextSourceSlot,
            OpenShulkerSession.SourceType nextSourceType,
            ItemStack expectedNextItem
    ) {
        OpenShulkerSession current = this.sessionsById.get(currentSessionId);
        if (!this.isCurrentSession(player, current) || current.isFinished() || !current.isSwitchScheduled()) return;

        if (!player.isOnline()) {
            current.setSwitchScheduled(false);
            this.finishSession(current, player, true, false, false);
            return;
        }

        ItemStack actualNext = nextSourceInventory.getItem(nextSourceSlot);
        if (!this.matchesHeldShulker(actualNext, expectedNextItem)
                || this.isSessionSourceSlot(current, nextSourceInventory, nextSourceSlot)
                || this.classifySource(player, nextSourceInventory) != nextSourceType) {
            current.setSwitchScheduled(false);
            return;
        }

        current.setSwitchScheduled(false);
        if (!this.finishSession(current, player, true, false, true)) return;
        if (!player.isOnline()) return;

        this.AttemptToOpenShulkerBox(player, nextSourceInventory, nextSourceSlot, nextSourceType);
    }

    public boolean finishFromClose(Player player, Inventory closedInventory) {
        UUID sessionId = this.getVirtualSessionId(closedInventory);
        if (sessionId == null) return false;

        OpenShulkerSession session = this.sessionsById.get(sessionId);
        if (!this.isCurrentSession(player, session)) return false;
        if (closedInventory != session.getVirtualInventory()) return false;

        return this.finishSession(session, player, true, true, false);
    }

    public boolean finishPlayerSession(Player player, boolean saveContents, boolean reopenSource) {
        OpenShulkerSession session = this.sessionsByPlayer.get(player.getUniqueId());
        if (session == null) return false;
        return this.finishSession(session, player, saveContents, reopenSource, true);
    }

    private boolean finishSession(
            OpenShulkerSession session,
            Player player,
            boolean saveContents,
            boolean reopenSource,
            boolean closeVirtualView
    ) {
        if (session == null || session.isFinished() || session.isFinishing()) return false;
        session.setFinishing(true);
        session.setSwitchScheduled(false);
        this.cancelSessionTasks(session);

        boolean restored = false;
        try {
            if (saveContents && session.isOpened()) {
                ItemStack updated = session.getHeldShulker();
                if (this.writeContentsToShulker(updated, session.getVirtualInventory().getContents())) {
                    session.setHeldShulker(updated);
                } else {
                    this.plugin.getLogger().severe(
                            "Could not apply final virtual contents for shulker session " + session.getSessionId() + "."
                    );
                }
            }

            if (session.isSourceLocked()) {
                ItemStack finalItem = session.getHeldShulker();
                this.clearLegacyItemFlag(finalItem);
                restored = this.restoreHeldShulker(session, player, finalItem);
                if (restored) session.setFinalSourceItem(finalItem);
            }

            if (session.isOpened() && player != null) {
                this.playConfiguredSound(player, "CloseSound", Sound.BLOCK_SHULKER_BOX_CLOSE);
            }
        } catch (Throwable throwable) {
            this.plugin.getLogger().severe(
                    "Error while finishing shulker session " + session.getSessionId() + ": " + throwable.getMessage()
            );
            throwable.printStackTrace();

            if (session.isSourceLocked() && !restored) {
                restored = this.emergencyReturnHeldShulker(session, player, session.getHeldShulker());
                if (restored) session.setFinalSourceItem(session.getHeldShulker());
            }
        } finally {
            this.removeContainerChunkTickets(session);
            this.sessionsById.remove(session.getSessionId(), session);
            this.sessionsByPlayer.remove(session.getPlayerId(), session);
            if (player != null) this.clearLegacyPlayerState(player);

            session.setFinished(true);
            session.setFinishing(false);
            session.getVirtualInventory().clear();

            if (closeVirtualView && player != null && player.isOnline()
                    && player.getOpenInventory().getTopInventory() == session.getVirtualInventory()) {
                player.closeInventory();
            }

            if (reopenSource && player != null && player.isOnline()) {
                this.scheduleSourceReopen(session, player);
            }
        }
        return true;
    }

    private boolean restoreHeldShulker(OpenShulkerSession session, Player player, ItemStack finalItem) {
        Inventory sourceInventory = this.resolveSourceInventory(session, player);
        if (sourceInventory != null) {
            List<Integer> placeholders = this.findPlaceholderSlots(sourceInventory, session.getSessionId());
            if (!placeholders.isEmpty()) {
                int restoreSlot = placeholders.contains(session.getPlaceholderSlot())
                        ? session.getPlaceholderSlot()
                        : placeholders.get(0);

                sourceInventory.setItem(restoreSlot, finalItem.clone());
                for (int slot : placeholders) {
                    if (slot != restoreSlot) sourceInventory.setItem(slot, null);
                }
                return true;
            }

            int preferredSlot = session.getSourceSlot();
            if (preferredSlot >= 0 && preferredSlot < sourceInventory.getSize()
                    && isEmpty(sourceInventory.getItem(preferredSlot))) {
                sourceInventory.setItem(preferredSlot, finalItem.clone());
                return true;
            }

            Map<Integer, ItemStack> leftovers = sourceInventory.addItem(finalItem.clone());
            if (leftovers.isEmpty()) return true;
            finalItem = leftovers.values().iterator().next();
        }

        return this.returnToPlayerOrDrop(session, player, finalItem);
    }

    private boolean emergencyReturnHeldShulker(OpenShulkerSession session, Player player, ItemStack heldItem) {
        try {
            Inventory sourceInventory = this.resolveSourceInventory(session, player);
            if (sourceInventory != null) {
                for (int slot : this.findPlaceholderSlots(sourceInventory, session.getSessionId())) {
                    sourceInventory.setItem(slot, null);
                }
            }
        } catch (Throwable ignored) {
        }
        return this.returnToPlayerOrDrop(session, player, heldItem);
    }

    private boolean returnToPlayerOrDrop(OpenShulkerSession session, Player player, ItemStack item) {
        ItemStack remaining = item.clone();
        if (player != null) {
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(remaining);
            if (leftovers.isEmpty()) return true;
            remaining = leftovers.values().iterator().next();
            player.getWorld().dropItemNaturally(player.getLocation(), remaining);
            return true;
        }

        Location sourceLocation = session.getSourceLocation();
        if (sourceLocation != null && sourceLocation.getWorld() != null) {
            sourceLocation.getWorld().dropItemNaturally(sourceLocation, remaining);
            return true;
        }

        this.plugin.getLogger().severe(
                "Could not return held shulker for session " + session.getSessionId()
                        + ": no player or source world was available."
        );
        return false;
    }

    private void scheduleSourceReopen(OpenShulkerSession session, Player player) {
        if (session.getSourceType() == OpenShulkerSession.SourceType.PLAYER_INVENTORY) return;

        Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (!player.isOnline() || this.sessionsByPlayer.containsKey(player.getUniqueId())) return;
            if (player.getOpenInventory().getTopInventory().getType() != InventoryType.CRAFTING) return;

            Inventory source = this.resolveSourceInventory(session, player);
            if (source == null) return;

            if (session.getSourceType() == OpenShulkerSession.SourceType.CONTAINER) {
                Location location = session.getSourceLocation();
                if (location == null || location.getWorld() != player.getWorld()) return;
                if (location.distanceSquared(player.getLocation()) > 36.0D) return;
            }
            player.openInventory(source);
        });
    }

    public void finishAllSessions() {
        List<OpenShulkerSession> sessions = new ArrayList<>(this.sessionsById.values());
        for (OpenShulkerSession session : sessions) {
            Player player = Bukkit.getPlayer(session.getPlayerId());
            this.finishSession(session, player, true, false, true);
        }
        if (this.watchdogTask != null) this.watchdogTask.cancel();
    }

    public void synchronizeDeathDrops(OpenShulkerSession session, List<ItemStack> drops, boolean keepInventory) {
        if (session == null || session.getSourceType() != OpenShulkerSession.SourceType.PLAYER_INVENTORY) return;
        if (keepInventory) return;

        ItemStack finalItem = session.getFinalSourceItem();
        if (finalItem == null) return;

        for (int i = 0; i < drops.size(); i++) {
            ItemStack drop = drops.get(i);
            if (!this.isPlaceholderForSession(drop, session.getSessionId())) continue;
            drops.set(i, finalItem.clone());
            return;
        }

        for (ItemStack drop : drops) {
            if (drop != null && drop.isSimilar(finalItem)) return;
        }
        drops.add(finalItem.clone());
    }

    private void runSessionWatchdog() {
        List<OpenShulkerSession> sessions = new ArrayList<>(this.sessionsById.values());
        for (OpenShulkerSession session : sessions) {
            if (session.isFinished() || session.isFinishing()) continue;
            Player player = Bukkit.getPlayer(session.getPlayerId());
            if (player == null || !player.isOnline()) continue;

            if (session.isSourceLocked() && this.resolvePlaceholder(session, player) == null) {
                this.plugin.getLogger().severe(
                        "Source placeholder disappeared for shulker session " + session.getSessionId() + "."
                );
                this.finishSession(session, player, true, false, true);
                continue;
            }

            if (session.isOpened()
                    && player.getOpenInventory().getTopInventory() != session.getVirtualInventory()) {
                this.finishSession(session, player, true, false, false);
                continue;
            }

            if (session.getSourceType() == OpenShulkerSession.SourceType.CONTAINER) {
                Location location = session.getSourceLocation();
                if (location == null || location.getWorld() != player.getWorld()
                        || location.distanceSquared(player.getLocation()) > 64.0D
                        || this.resolveSourceInventory(session, player) == null) {
                    this.finishSession(session, player, true, false, true);
                }
            }
        }
    }

    public OpenShulkerSession getSession(Player player) {
        return this.sessionsByPlayer.get(player.getUniqueId());
    }

    public OpenShulkerSession getSession(UUID sessionId) {
        return this.sessionsById.get(sessionId);
    }

    public boolean HasOpenShulkerBox(Player player) {
        return this.sessionsByPlayer.containsKey(player.getUniqueId());
    }

    public boolean isPluginVirtualShulker(Inventory inventory) {
        if (inventory == null || !(inventory.getHolder() instanceof VirtualShulkerHolder holder)) return false;
        OpenShulkerSession session = this.sessionsById.get(holder.getSessionId());
        return session != null && session.getVirtualInventory() == inventory;
    }

    public UUID getVirtualSessionId(Inventory inventory) {
        if (inventory == null || !(inventory.getHolder() instanceof VirtualShulkerHolder holder)) return null;
        return holder.getSessionId();
    }

    public OpenShulkerSession.SourceType classifySource(Player player, Inventory inventory) {
        if (player == null || inventory == null) return null;
        if (inventory == player.getInventory()) return OpenShulkerSession.SourceType.PLAYER_INVENTORY;

        if (inventory == player.getEnderChest() || inventory.getType() == InventoryType.ENDER_CHEST) {
            return OpenShulkerSession.SourceType.ENDER_CHEST;
        }

        if (this.isAllowedContainerInventory(inventory)) return OpenShulkerSession.SourceType.CONTAINER;
        return null;
    }

    private Inventory canonicalizeSourceInventory(
            Player player,
            Inventory inventory,
            OpenShulkerSession.SourceType sourceType
    ) {
        if (player == null || inventory == null || sourceType == null) return null;
        return switch (sourceType) {
            case PLAYER_INVENTORY -> player.getInventory();
            case ENDER_CHEST -> player.getEnderChest();
            case CONTAINER -> inventory;
        };
    }

    public boolean isAllowedContainerInventory(Inventory inventory) {
        if (inventory == null) return false;
        Location location = this.getContainerAnchorLocation(inventory);
        if (location == null || location.getWorld() == null) return false;

        Material material = location.getBlock().getType();
        if (material != Material.CHEST && material != Material.TRAPPED_CHEST && material != Material.BARREL) {
            return false;
        }

        BlockState state = location.getBlock().getState();
        if (!(state instanceof Container)) return false;
        return inventory.getType() == InventoryType.CHEST || inventory.getType() == InventoryType.BARREL;
    }

    private Location getContainerAnchorLocation(Inventory inventory) {
        if (inventory == null) return null;
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof Container container) return container.getLocation();
        if (holder instanceof DoubleChest doubleChest) {
            InventoryHolder left = doubleChest.getLeftSide();
            if (left instanceof Container container) return container.getLocation();
            InventoryHolder right = doubleChest.getRightSide();
            if (right instanceof Container container) return container.getLocation();
        }
        return null;
    }

    public boolean isSessionSourceSlot(OpenShulkerSession session, Inventory inventory, int slot) {
        if (session == null || inventory == null) return false;
        Player player = Bukkit.getPlayer(session.getPlayerId());
        Inventory resolved = this.resolveSourceInventory(session, player);
        return resolved == inventory && session.getPlaceholderSlot() == slot;
    }

    public boolean hasActiveSessionForInventory(Inventory inventory) {
        if (inventory == null) return false;
        for (OpenShulkerSession session : this.sessionsById.values()) {
            Player player = Bukkit.getPlayer(session.getPlayerId());
            if (this.resolveSourceInventory(session, player) == inventory) return true;
        }
        return false;
    }

    public boolean hasActiveContainerAt(Block block) {
        if (block == null) return false;
        for (OpenShulkerSession session : this.sessionsById.values()) {
            if (session.getSourceType() != OpenShulkerSession.SourceType.CONTAINER) continue;
            Location location = session.getSourceLocation();
            if (location == null || location.getWorld() != block.getWorld()) continue;
            if (location.getBlockX() == block.getX()
                    && location.getBlockY() == block.getY()
                    && location.getBlockZ() == block.getZ()) return true;
        }
        return false;
    }

    public boolean containsSessionPlaceholder(Inventory inventory) {
        if (inventory == null) return false;
        for (ItemStack item : inventory.getContents()) {
            if (this.isSessionPlaceholder(item)) return true;
        }
        return false;
    }

    public boolean isSessionPlaceholder(ItemStack item) {
        if (item == null || item.getType() != Material.BARRIER || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(this.placeholderKey, PersistentDataType.BYTE);
    }

    public boolean isPlaceholderForSession(ItemStack item, UUID sessionId) {
        return sessionId != null && sessionId.equals(this.readPlaceholderSessionId(item));
    }

    public boolean tryRestoreOrphanPlaceholder(Inventory inventory, int slot, Player viewer) {
        if (inventory == null || slot < 0 || slot >= inventory.getSize()) return false;
        ItemStack item = inventory.getItem(slot);
        UUID sessionId = this.readPlaceholderSessionId(item);
        if (sessionId == null || this.sessionsById.containsKey(sessionId)) return false;
        if (!this.placeholderMatchesOrigin(item, inventory, slot, viewer)) {
            this.warnQuarantinedPlaceholder(sessionId, inventory, slot);
            return false;
        }

        ItemStack recovered = this.readRecoveryItem(item);
        if (!isValidShulker(recovered)) {
            this.plugin.getLogger().severe("Could not decode orphan shulker placeholder " + sessionId + ".");
            return false;
        }
        this.clearLegacyItemFlag(recovered);
        inventory.setItem(slot, recovered);
        return true;
    }

    public void restoreOrphanPlaceholders(Inventory inventory, Player viewer) {
        if (inventory == null) return;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            this.tryRestoreOrphanPlaceholder(inventory, slot, viewer);
        }
    }

    public void recoverPlayerInventories(Player player) {
        this.restoreOrphanPlaceholders(player.getInventory(), player);
        this.restoreOrphanPlaceholders(player.getEnderChest(), player);
        this.batchUnmarkLegacyShulkers(player);
    }

    public void batchUnmarkShulkers(Player player) {
        this.restoreOrphanPlaceholders(player.getInventory(), player);
        this.restoreOrphanPlaceholders(player.getEnderChest(), player);
        this.batchUnmarkLegacyShulkers(player);
    }

    private void batchUnmarkLegacyShulkers(Player player) {
        PlayerInventory inventory = player.getInventory();
        for (ItemStack item : inventory.getStorageContents()) this.unmarkShulkerAsOpen(item);
        for (ItemStack item : inventory.getArmorContents()) this.unmarkShulkerAsOpen(item);
        this.unmarkShulkerAsOpen(inventory.getItemInOffHand());
        for (ItemStack item : player.getEnderChest().getContents()) this.unmarkShulkerAsOpen(item);
        this.clearLegacyPlayerState(player);
    }

    public void unmarkShulkerAsOpen(ItemStack shulker) {
        if (!isValidShulker(shulker)) return;
        this.clearLegacyItemFlag(shulker);
    }

    public boolean IsOpenShulker(ItemStack itemStack, Player player) {
        UUID marker = this.readLegacySessionId(itemStack);
        if (marker == null) return false;
        OpenShulkerSession session = this.sessionsById.get(marker);
        return session != null && (player == null || session.getPlayerId().equals(player.getUniqueId()));
    }

    public boolean IsOpenShulker(ItemStack itemStack) {
        return this.IsOpenShulker(itemStack, null);
    }

    public ItemStack SearchShulkerBox(Player player) {
        OpenShulkerSession session = this.getSession(player);
        return session == null ? null : session.getHeldShulker();
    }

    public ItemStack SearchShulkerBox(Inventory inventory) {
        if (inventory == null) return null;
        for (ItemStack item : inventory.getContents()) {
            ItemStack recovered = this.readRecoveryItem(item);
            if (isValidShulker(recovered)) return recovered;
        }
        return null;
    }

    public void clearLegacyPlayerState(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        pdc.remove(this.openShulkerKey);
        pdc.remove(this.openShulkerLocationKey);
    }

    private Inventory resolveSourceInventory(OpenShulkerSession session, Player player) {
        if (session.getSourceType() == OpenShulkerSession.SourceType.PLAYER_INVENTORY) {
            if (player == null || !session.getPlayerId().equals(player.getUniqueId())) return null;
            return player.getInventory();
        }
        if (session.getSourceType() == OpenShulkerSession.SourceType.ENDER_CHEST) {
            if (player == null || !session.getPlayerId().equals(player.getUniqueId())) return null;
            return player.getEnderChest();
        }

        Location location = session.getSourceLocation();
        if (location == null || location.getWorld() == null) return null;
        if (!location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return null;
        if (!this.isAllowedContainerBlock(location.getBlock())) return null;
        return ((Container) location.getBlock().getState()).getInventory();
    }

    private boolean isAllowedContainerBlock(Block block) {
        Material material = block.getType();
        return (material == Material.CHEST || material == Material.TRAPPED_CHEST || material == Material.BARREL)
                && block.getState() instanceof Container;
    }

    private PlaceholderResolution resolvePlaceholder(OpenShulkerSession session, Player player) {
        if (!session.isSourceLocked()) return null;
        Inventory source = this.resolveSourceInventory(session, player);
        if (source == null) return null;

        int preferred = session.getPlaceholderSlot();
        if (preferred >= 0 && preferred < source.getSize()
                && this.isPlaceholderForSession(source.getItem(preferred), session.getSessionId())) {
            return new PlaceholderResolution(source, preferred);
        }

        for (int slot = 0; slot < source.getSize(); slot++) {
            if (this.isPlaceholderForSession(source.getItem(slot), session.getSessionId())) {
                session.setPlaceholderSlot(slot);
                return new PlaceholderResolution(source, slot);
            }
        }
        return null;
    }

    private List<Integer> findPlaceholderSlots(Inventory inventory, UUID sessionId) {
        List<Integer> result = new ArrayList<>();
        if (inventory == null) return result;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (this.isPlaceholderForSession(inventory.getItem(slot), sessionId)) result.add(slot);
        }
        return result;
    }

    private boolean refreshPlaceholderRecovery(OpenShulkerSession session, Player player) {
        PlaceholderResolution resolution = this.resolvePlaceholder(session, player);
        if (resolution == null) return false;
        ItemStack replacement = this.createPlaceholder(session);
        if (replacement == null) return false;
        resolution.inventory().setItem(resolution.slot(), replacement);
        return true;
    }

    private ItemStack createPlaceholder(OpenShulkerSession session) {
        byte[] serialized = this.serializeItem(session.getHeldShulker());
        if (serialized == null) return null;

        ItemStack placeholder = new ItemStack(Material.BARRIER);
        ItemMeta meta = placeholder.getItemMeta();
        if (meta == null) return null;

        meta.setDisplayName(ChatColor.DARK_GRAY + "Shulker currently open");
        meta.setLore(List.of(
                ChatColor.GRAY + "This slot is locked while the shulker GUI is open.",
                ChatColor.GRAY + "The item will return when the GUI closes."
        ));

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(this.placeholderKey, PersistentDataType.BYTE, (byte) 1);
        pdc.set(this.openShulkerKey, PersistentDataType.STRING, session.getSessionId().toString());
        pdc.set(this.recoveryItemKey, PersistentDataType.BYTE_ARRAY, serialized);
        pdc.set(this.sourceTypeKey, PersistentDataType.STRING, session.getSourceType().name());
        pdc.set(this.sourceSlotKey, PersistentDataType.INTEGER, session.getSourceSlot());
        pdc.set(this.sourceOwnerKey, PersistentDataType.STRING, session.getPlayerId().toString());

        Location location = session.getSourceLocation();
        if (location != null && location.getWorld() != null) {
            pdc.set(this.sourceWorldKey, PersistentDataType.STRING, location.getWorld().getUID().toString());
            pdc.set(this.sourceXKey, PersistentDataType.INTEGER, location.getBlockX());
            pdc.set(this.sourceYKey, PersistentDataType.INTEGER, location.getBlockY());
            pdc.set(this.sourceZKey, PersistentDataType.INTEGER, location.getBlockZ());
        }

        placeholder.setItemMeta(meta);
        return placeholder;
    }

    private UUID readPlaceholderSessionId(ItemStack item) {
        if (!this.isSessionPlaceholder(item)) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        String raw = meta.getPersistentDataContainer().get(this.openShulkerKey, PersistentDataType.STRING);
        return parseUuid(raw);
    }

    private ItemStack readRecoveryItem(ItemStack placeholder) {
        if (!this.isSessionPlaceholder(placeholder)) return null;
        ItemMeta meta = placeholder.getItemMeta();
        if (meta == null) return null;
        byte[] bytes = meta.getPersistentDataContainer().get(this.recoveryItemKey, PersistentDataType.BYTE_ARRAY);
        return bytes == null ? null : this.deserializeItem(bytes);
    }

    private boolean placeholderMatchesOrigin(ItemStack placeholder, Inventory inventory, int slot, Player viewer) {
        if (!this.isSessionPlaceholder(placeholder)) return false;
        ItemMeta meta = placeholder.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        String typeName = pdc.get(this.sourceTypeKey, PersistentDataType.STRING);
        Integer originalSlot = pdc.get(this.sourceSlotKey, PersistentDataType.INTEGER);
        String ownerRaw = pdc.get(this.sourceOwnerKey, PersistentDataType.STRING);
        if (typeName == null || originalSlot == null || originalSlot != slot) return false;

        OpenShulkerSession.SourceType sourceType;
        try {
            sourceType = OpenShulkerSession.SourceType.valueOf(typeName);
        } catch (IllegalArgumentException ignored) {
            return false;
        }

        UUID ownerId = parseUuid(ownerRaw);
        if (sourceType == OpenShulkerSession.SourceType.PLAYER_INVENTORY) {
            return viewer != null && ownerId != null && ownerId.equals(viewer.getUniqueId())
                    && inventory == viewer.getInventory();
        }
        if (sourceType == OpenShulkerSession.SourceType.ENDER_CHEST) {
            return viewer != null && ownerId != null && ownerId.equals(viewer.getUniqueId())
                    && inventory == viewer.getEnderChest();
        }

        Location inventoryLocation = this.getContainerAnchorLocation(inventory);
        String worldRaw = pdc.get(this.sourceWorldKey, PersistentDataType.STRING);
        Integer x = pdc.get(this.sourceXKey, PersistentDataType.INTEGER);
        Integer y = pdc.get(this.sourceYKey, PersistentDataType.INTEGER);
        Integer z = pdc.get(this.sourceZKey, PersistentDataType.INTEGER);
        UUID worldId = parseUuid(worldRaw);
        return inventoryLocation != null && inventoryLocation.getWorld() != null
                && worldId != null && worldId.equals(inventoryLocation.getWorld().getUID())
                && x != null && y != null && z != null
                && x == inventoryLocation.getBlockX()
                && y == inventoryLocation.getBlockY()
                && z == inventoryLocation.getBlockZ()
                && this.isAllowedContainerInventory(inventory);
    }

    private void warnQuarantinedPlaceholder(UUID sessionId, Inventory inventory, int slot) {
        if (!this.warnedQuarantinedPlaceholders.add(sessionId)) return;
        this.plugin.getLogger().severe(
                "Found orphan shulker placeholder " + sessionId + " outside its recorded source slot "
                        + slot + ". It was quarantined as a barrier instead of being restored to prevent duplication."
        );
    }

    private byte[] serializeItem(ItemStack item) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             BukkitObjectOutputStream output = new BukkitObjectOutputStream(bytes)) {
            output.writeObject(item);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            this.plugin.getLogger().severe("Could not serialize shulker recovery item: " + exception.getMessage());
            return null;
        }
    }

    private ItemStack deserializeItem(byte[] bytes) {
        try (ByteArrayInputStream inputBytes = new ByteArrayInputStream(bytes);
             BukkitObjectInputStream input = new BukkitObjectInputStream(inputBytes)) {
            Object object = input.readObject();
            return object instanceof ItemStack item ? item : null;
        } catch (IOException | ClassNotFoundException exception) {
            this.plugin.getLogger().severe("Could not deserialize shulker recovery item: " + exception.getMessage());
            return null;
        }
    }

    private void addContainerChunkTickets(OpenShulkerSession session) {
        if (session.getSourceType() != OpenShulkerSession.SourceType.CONTAINER) return;
        Location location = session.getSourceLocation();
        if (location == null || location.getWorld() == null) return;

        World world = location.getWorld();
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        this.addChunkTicket(session, world.getChunkAt(chunkX, chunkZ));

        int localX = location.getBlockX() & 15;
        int localZ = location.getBlockZ() & 15;
        if (localX == 0) this.addChunkTicket(session, world.getChunkAt(chunkX - 1, chunkZ));
        if (localX == 15) this.addChunkTicket(session, world.getChunkAt(chunkX + 1, chunkZ));
        if (localZ == 0) this.addChunkTicket(session, world.getChunkAt(chunkX, chunkZ - 1));
        if (localZ == 15) this.addChunkTicket(session, world.getChunkAt(chunkX, chunkZ + 1));
    }

    private void addChunkTicket(OpenShulkerSession session, Chunk chunk) {
        try {
            if (chunk.addPluginChunkTicket(this.plugin)) session.addTicketedChunk(chunk);
        } catch (Throwable throwable) {
            this.plugin.getLogger().warning("Could not add chunk ticket for shulker session " + session.getSessionId() + ".");
        }
    }

    private void removeContainerChunkTickets(OpenShulkerSession session) {
        for (Chunk chunk : new ArrayList<>(session.getTicketedChunks())) {
            try {
                chunk.removePluginChunkTicket(this.plugin);
            } catch (Throwable ignored) {
            }
        }
        session.clearTicketedChunks();
    }

    private void cancelSessionTasks(OpenShulkerSession session) {
        if (session.getLockTask() != null) session.getLockTask().cancel();
        if (session.getOpenTask() != null) session.getOpenTask().cancel();
        if (session.getOpenVerificationTask() != null) session.getOpenVerificationTask().cancel();
    }

    private boolean isCurrentSession(Player player, OpenShulkerSession session) {
        return player != null && session != null
                && session.getPlayerId().equals(player.getUniqueId())
                && this.sessionsByPlayer.get(player.getUniqueId()) == session
                && this.sessionsById.get(session.getSessionId()) == session;
    }

    private boolean matchesHeldShulker(ItemStack current, ItemStack expected) {
        if (!isValidShulker(current) || !isValidShulker(expected)) return false;
        ItemStack cleanCurrent = current.clone();
        ItemStack cleanExpected = expected.clone();
        this.clearLegacyItemFlag(cleanCurrent);
        this.clearLegacyItemFlag(cleanExpected);
        return cleanCurrent.getAmount() == cleanExpected.getAmount() && cleanCurrent.isSimilar(cleanExpected);
    }

    private boolean writeContentsToShulker(ItemStack shulkerItem, ItemStack[] contents) {
        ShulkerBox shulkerState = getShulkerState(shulkerItem);
        if (shulkerState == null) return false;

        BlockStateMeta meta = (BlockStateMeta) shulkerItem.getItemMeta();
        shulkerState.getInventory().setContents(cloneContents(contents));
        meta.setBlockState(shulkerState);
        shulkerItem.setItemMeta(meta);
        return true;
    }

    private static boolean isValidShulker(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || item.getAmount() != 1) return false;
        if (!item.getType().name().endsWith("SHULKER_BOX")) return false;
        if (!(item.getItemMeta() instanceof BlockStateMeta meta)) return false;
        return meta.getBlockState() instanceof ShulkerBox;
    }

    private static ShulkerBox getShulkerState(ItemStack item) {
        if (!isValidShulker(item)) return null;
        BlockStateMeta meta = (BlockStateMeta) item.getItemMeta();
        return (ShulkerBox) meta.getBlockState();
    }

    private UUID readLegacySessionId(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        return parseUuid(meta.getPersistentDataContainer().get(this.openShulkerKey, PersistentDataType.STRING));
    }

    private void clearLegacyItemFlag(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.remove(this.openShulkerKey);
        pdc.remove(this.legacyTimestampKey);
        item.setItemMeta(meta);
    }

    private static UUID parseUuid(String raw) {
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean isEmpty(ItemStack item) {
        return item == null || item.getType() == Material.AIR;
    }

    private static ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            copy[i] = contents[i] == null ? null : contents[i].clone();
        }
        return copy;
    }

    private void playConfiguredSound(Player player, String configPath, Sound fallback) {
        String configured = this.plugin.getConfig().getString(configPath, fallback.name());
        try {
            player.playSound(player.getLocation(), Sound.valueOf(configured), 0.08F, 1.0F);
        } catch (IllegalArgumentException ignored) {
            player.playSound(player.getLocation(), fallback, 0.08F, 1.0F);
        }
    }

    private void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("PerchShulkers inventory sessions must run on the server thread");
        }
    }

    private record PlaceholderResolution(Inventory inventory, int slot) {
    }
}
