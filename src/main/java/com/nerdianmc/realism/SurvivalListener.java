package com.nerdianmc.realism;

import com.cryptomorin.xseries.XMaterial;
import org.bukkit.FluidCollisionMode;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class SurvivalListener implements Listener {
    private final SurvivalManager manager;
    private final SurvivalItems items;
    private final Map<UUID, Long> bedEnterTimes = new HashMap<>();
    private final Map<UUID, Long> jumpTimes = new HashMap<>();

    public SurvivalListener(SurvivalManager manager, SurvivalItems items) {
        this.manager = manager;
        this.items = items;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        manager.load(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        bedEnterTimes.remove(player.getUniqueId());
        jumpTimes.remove(player.getUniqueId());
        manager.quit(player);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!manager.isSurviving(player) || event.getTo() == null
                || event.getFrom().getWorld() != event.getTo().getWorld()) return;
        double squared = event.getFrom().distanceSquared(event.getTo());
        if (squared <= 0 || squared > 64) return;
        double horizontal = Math.hypot(event.getTo().getX() - event.getFrom().getX(),
                event.getTo().getZ() - event.getFrom().getZ());
        if (horizontal > 0) {
            double rate = player.isSprinting() ? 0.025 : 0.004;
            manager.drain(player, horizontal * rate);
        }
        if (!player.isFlying() && !player.isGliding() && !player.isSwimming()
                && event.getTo().getY() > event.getFrom().getY()
                && player.getVelocity().getY() > 0.35) {
            long now = System.currentTimeMillis();
            long last = jumpTimes.getOrDefault(player.getUniqueId(), 0L);
            if (now - last > 650) {
                jumpTimes.put(player.getUniqueId(), now);
                manager.drain(player, manager.maximallyBurdened(player) ? 0.8 : 0.15);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSprint(PlayerToggleSprintEvent event) {
        if (event.isSprinting() && manager.isSurviving(event.getPlayer()) && manager.overburdened(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && event.getFinalDamage() > 0) {
            manager.drain(player, 0.8 + event.getFinalDamage() * 0.15);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        if (!manager.isSurviving(player)) return;
        ItemStack stack = event.getItem();
        if (items.isPurified(stack)) {
            manager.hydrate(player, 100);
            player.sendMessage(SurvivalManager.color("&bYour hydration is fully restored."));
        } else if (items.isCocoa(stack)) {
            manager.stimulate(player);
            manager.hydrate(player, 8);
        } else if (items.isWaterBottle(stack)) {
            manager.drinkRaw(player);
        } else {
            String type = stack.getType().name();
            if (type.equals("MELON_SLICE")) manager.hydrate(player, 6);
            else if (type.equals("APPLE") || type.equals("GOLDEN_APPLE")) manager.hydrate(player, 8);
            else if (type.endsWith("STEW") || type.endsWith("SOUP")) manager.hydrate(player, 14);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !manager.isSurviving(event.getPlayer())) return;
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (items.isSleepingBag(hand) && (event.getAction() == Action.RIGHT_CLICK_BLOCK || event.getAction() == Action.RIGHT_CLICK_AIR)) {
            event.setCancelled(true);
            if (manager.shortRest(player)) {
                hand.setAmount(hand.getAmount() - 1);
            }
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        Block block = event.getClickedBlock();
        if (block.getType().name().equals("WATER_CAULDRON") && hand.getType().name().equals("GLASS_BOTTLE")
                && isHeated(block) && block.getBlockData() instanceof Levelled level && level.getLevel() > 0) {
            event.setCancelled(true);
            if (level.getLevel() == 1) {
                XMaterial.matchXMaterial("CAULDRON").map(XMaterial::parseMaterial).ifPresent(block::setType);
            } else {
                level.setLevel(level.getLevel() - 1);
                block.setBlockData(level);
            }
            hand.setAmount(hand.getAmount() - 1);
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(items.purifiedWater());
            overflow.values().forEach(stack -> block.getWorld().dropItemNaturally(player.getLocation(), stack));
            player.sendMessage(SurvivalManager.color("&bYou collect boiled, purified water."));
        } else if (block.getType().name().equals("WATER") && hand.getType().isAir()
                && block.getBlockData() instanceof Levelled level && level.getLevel() == 0) {
            event.setCancelled(true);
            manager.drinkRaw(player);
        }
    }

    private boolean isHeated(Block cauldron) {
        String below = cauldron.getRelative(0, -1, 0).getType().name();
        return below.equals("FIRE") || below.equals("LAVA") || below.endsWith("CAMPFIRE");
    }

    @EventHandler(ignoreCancelled = true)
    public void onBoil(FurnaceSmeltEvent event) {
        if (items.isPurified(event.getResult()) && !items.isWaterBottle(event.getSource())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onCraft(PrepareItemCraftEvent event) {
        Recipe recipe = event.getRecipe();
        if (recipe == null || !items.isCocoa(recipe.getResult())) return;
        for (ItemStack ingredient : event.getInventory().getMatrix()) {
            if (ingredient != null && ingredient.getType().name().equals("POTION") && !items.isWaterBottle(ingredient)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (event.getBedEnterResult() == PlayerBedEnterEvent.BedEnterResult.OK) {
            bedEnterTimes.put(event.getPlayer().getUniqueId(), event.getPlayer().getWorld().getFullTime());
        }
    }

    @EventHandler
    public void onBedLeave(PlayerBedLeaveEvent event) {
        Player player = event.getPlayer();
        Long started = bedEnterTimes.remove(player.getUniqueId());
        if (started != null && player.getWorld().getFullTime() - started >= 6000) {
            manager.fullRest(player);
        }
    }
}