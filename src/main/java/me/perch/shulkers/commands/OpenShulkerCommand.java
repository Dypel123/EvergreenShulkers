package me.perch.shulkers.commands;

import me.perch.shulkers.OpenShulker;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class OpenShulkerCommand implements TabExecutor {
    private final OpenShulker plugin;

    public OpenShulkerCommand(OpenShulker plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (!sender.hasPermission("openshulker.admin")) return true;

        String prefix = ChatColor.translateAlternateColorCodes('&',
                this.plugin.getConfig().getString("Messages.Prefix", "&8[&2OpenShulker&8] &7"));

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            this.plugin.InitializeConfig();
            sender.sendMessage(prefix + ChatColor.translateAlternateColorCodes('&',
                    this.plugin.getConfig().getString(
                            "Messages.OpenShulkerCommand.Reloaded",
                            "The plugin was reloaded!"
                    )));
            return true;
        }

        String syntax = this.plugin.getConfig().getString(
                "Messages.OpenShulkerCommand.Syntax",
                "&cSyntax: &4/<LABEL> <Reload>"
        ).replace("<LABEL>", label);
        sender.sendMessage(prefix + ChatColor.translateAlternateColorCodes('&', syntax));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        if (args.length == 1) return Collections.singletonList("reload");
        return new ArrayList<>();
    }
}
