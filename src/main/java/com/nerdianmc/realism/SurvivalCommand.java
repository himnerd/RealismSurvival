package com.nerdianmc.realism;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SurvivalCommand implements TabExecutor {
    private final JavaPlugin plugin;
    private final SurvivalManager manager;
    private final SurvivalItems items;

    public SurvivalCommand(JavaPlugin plugin, SurvivalManager manager, SurvivalItems items) {
        this.plugin = plugin;
        this.manager = manager;
        this.items = items;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String subcommand = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        if (subcommand.equals("reload")) {
            if (!admin(sender)) return true;
            plugin.reloadConfig();
            manager.reloadHud();
            sender.sendMessage(SurvivalManager.color("&aNerdianRealism configuration reloaded."));
            return true;
        }
        if (subcommand.equals("give")) {
            if (!admin(sender)) return true;
            if (args.length < 2) {
                sender.sendMessage(SurvivalManager.color("&eUsage: /" + label + " give <purified|cocoa|bag> [player] [amount]"));
                return true;
            }
            Player target;
            if (args.length >= 3) target = Bukkit.getPlayerExact(args[2]);
            else target = sender instanceof Player player ? player : null;
            if (target == null) {
                sender.sendMessage(SurvivalManager.color("&cSpecify an online player."));
                return true;
            }
            ItemStack item = switch (args[1].toLowerCase(Locale.ROOT)) {
                case "purified" -> items.purifiedWater();
                case "cocoa" -> items.cocoa();
                case "bag" -> items.sleepingBag();
                default -> null;
            };
            if (item == null) {
                sender.sendMessage(SurvivalManager.color("&cChoose purified, cocoa, or bag."));
                return true;
            }
            int amount = 1;
            if (args.length >= 4) {
                try {
                    amount = Integer.parseInt(args[3]);
                } catch (NumberFormatException exception) {
                    sender.sendMessage(SurvivalManager.color("&cAmount must be a number between 1 and 64."));
                    return true;
                }
                if (amount < 1 || amount > 64) {
                    sender.sendMessage(SurvivalManager.color("&cAmount must be between 1 and 64."));
                    return true;
                }
            }
            for (int i = 0; i < amount; i++) {
                Map<Integer, ItemStack> overflow = target.getInventory().addItem(item.clone());
                overflow.values().forEach(leftover -> target.getWorld().dropItemNaturally(target.getLocation(), leftover));
            }
            sender.sendMessage(SurvivalManager.color("&aGave " + amount + " " + args[1] + " to " + target.getName() + "."));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use status or drink. Console: /survival give <item> <player> [amount]");
            return true;
        }
        if (subcommand.equals("drink")) {
            if (!manager.isSurviving(player)) return true;
            Block water = player.getTargetBlockExact(5, FluidCollisionMode.ALWAYS);
            if (water == null || !water.getType().name().equals("WATER")) {
                player.sendMessage(SurvivalManager.color("&cLook at a water source within five blocks to drink."));
                return true;
            }
            if (water.getBlockData() instanceof org.bukkit.block.data.Levelled level && level.getLevel() == 0) {
                manager.drinkRaw(player);
            } else {
                player.sendMessage(SurvivalManager.color("&cYou need a stationary water source."));
            }
            return true;
        }
        if (subcommand.equals("status")) {
            SurvivalData data = manager.get(player);
            player.sendMessage(SurvivalManager.color(String.format(Locale.ROOT,
                    "&bWater: &f%.0f%% &8| &eFatigue: &f%.0f%% &8| &bBody: &f%.1f°C &8| &6Weight: &f%.1f",
                    data.getHydration(), data.getFatigue(), data.getTemperature(), manager.weight(player))));
            return true;
        }
        player.sendMessage(SurvivalManager.color("&e/" + label + " status &7- Show needs"));
        player.sendMessage(SurvivalManager.color("&e/" + label + " drink &7- Drink the water source you're looking at"));
        if (sender.hasPermission("nerdianrealism.admin")) {
            player.sendMessage(SurvivalManager.color("&e/" + label + " give <purified|cocoa|bag> [player] [amount]"));
            player.sendMessage(SurvivalManager.color("&e/" + label + " reload"));
        }
        return true;
    }

    private boolean admin(CommandSender sender) {
        if (sender.hasPermission("nerdianrealism.admin")) return true;
        sender.sendMessage(SurvivalManager.color("&cYou do not have permission."));
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.add("status");
            options.add("drink");
            options.add("help");
            if (sender.hasPermission("nerdianrealism.admin")) {
                options.add("give");
                options.add("reload");
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give") && sender.hasPermission("nerdianrealism.admin")) {
            options.addAll(List.of("purified", "cocoa", "bag"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give") && sender.hasPermission("nerdianrealism.admin")) {
            for (Player player : Bukkit.getOnlinePlayers()) options.add(player.getName());
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(option -> !option.toLowerCase(Locale.ROOT).startsWith(typed));
        return options;
    }
}