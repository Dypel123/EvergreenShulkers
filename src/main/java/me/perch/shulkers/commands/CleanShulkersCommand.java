package me.perch.shulkers.commands;

import me.perch.shulkers.OpenShulker;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class CleanShulkersCommand implements CommandExecutor {
    private final OpenShulker plugin;

    public CleanShulkersCommand(OpenShulker plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Only players can use this command without arguments.");
                return true;
            }
            if (!player.hasPermission("openshulker.cleanshulkers")) {
                player.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
                return true;
            }

            cleanPlayer(player);
            player.sendMessage(ChatColor.GREEN
                    + "All shulker boxes in your inventory, armor, offhand, and ender chest have been cleaned.");
            return true;
        }

        if (!sender.hasPermission("openshulker.cleanshulkers.others")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to clean shulkers for other players.");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player not found or not online.");
            return true;
        }

        cleanPlayer(target);
        sender.sendMessage(ChatColor.GREEN + "All shulker boxes for " + target.getName() + " have been cleaned.");
        target.sendMessage(ChatColor.YELLOW
                + "All shulker boxes in your inventory, armor, offhand, and ender chest have been cleaned by "
                + sender.getName() + ".");
        return true;
    }

    private void cleanPlayer(Player player) {
        this.plugin.GetShulkerActions().finishPlayerSession(player, true, false);
        this.plugin.GetShulkerActions().batchUnmarkShulkers(player);
    }
}
