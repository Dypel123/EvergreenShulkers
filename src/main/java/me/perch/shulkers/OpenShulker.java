package me.perch.shulkers;

import me.perch.shulkers.commands.CleanShulkersCommand;
import me.perch.shulkers.commands.OpenShulkerCommand;
import me.perch.shulkers.listener.ShulkerDupeListener;
import me.perch.shulkers.listener.ShulkerOpenCloseListener;
import me.perch.shulkers.listener.ShulkerReadOnlyListener;
import me.perch.shulkers.util.ShulkerActions;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class OpenShulker extends JavaPlugin {
    public boolean _allowInventoryOpen = true;
    public boolean _allowContainerOpen = true;
    public boolean _allowEnderChestOpen = true;
    public boolean _allowHandOpen = true;

    private ShulkerActions shulkerActions;

    @Override
    public void onEnable() {
        this.initializeConfig();
        this.shulkerActions = new ShulkerActions(this);

        Bukkit.getPluginManager().registerEvents(new ShulkerOpenCloseListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ShulkerDupeListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ShulkerReadOnlyListener(this), this);

        OpenShulkerCommand openShulkerCommand = new OpenShulkerCommand(this);
        PluginCommand openCommand = this.getCommand("openshulker");
        if (openCommand != null) {
            openCommand.setExecutor(openShulkerCommand);
            openCommand.setTabCompleter(openShulkerCommand);
        }

        PluginCommand cleanCommand = this.getCommand("cleanshulkers");
        if (cleanCommand != null) {
            cleanCommand.setExecutor(new CleanShulkersCommand(this));
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            this.shulkerActions.recoverPlayerInventories(player);
        }
    }

    @Override
    public void onDisable() {
        if (this.shulkerActions != null) {
            this.shulkerActions.finishAllSessions();
        }
    }

    public void InitializeConfig() {
        this.initializeConfig();
    }

    private void initializeConfig() {
        this.saveDefaultConfig();
        this.reloadConfig();

        validateSound("OpenSound", Sound.BLOCK_SHULKER_BOX_OPEN);
        validateSound("CloseSound", Sound.BLOCK_SHULKER_BOX_CLOSE);

        this._allowInventoryOpen = this.getConfig().getBoolean("OpenMethods.AllowInventoryOpen", true);
        this._allowContainerOpen = this.getConfig().getBoolean("OpenMethods.AllowContainerOpen", true);
        this._allowEnderChestOpen = this.getConfig().getBoolean("OpenMethods.AllowEnderChestOpen", true);
        this._allowHandOpen = this.getConfig().getBoolean("OpenMethods.AllowHandOpen", true);
    }

    private void validateSound(String path, Sound fallback) {
        String configured = this.getConfig().getString(path);
        if (configured == null) {
            this.getLogger().warning(path + " was not set; using " + fallback.name());
            this.getConfig().set(path, fallback.name());
            this.saveConfig();
            return;
        }

        try {
            Sound.valueOf(configured);
        } catch (IllegalArgumentException exception) {
            this.getLogger().warning("Unknown sound '" + configured + "' at " + path + "; using " + fallback.name());
        }
    }

    public ShulkerActions GetShulkerActions() {
        return this.shulkerActions;
    }
}
