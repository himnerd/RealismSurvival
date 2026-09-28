package com.nerdianmc.realism;

import com.cryptomorin.xseries.XAttribute;
import com.cryptomorin.xseries.XPotion;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class SurvivalManager {
    private final JavaPlugin plugin;
    private final File playerDirectory;
    private final NamespacedKey fatigueHealthKey;
    private final Map<UUID, SurvivalData> players = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Map<UUID, Integer> warnedTiers = new HashMap<>();
    private BukkitTask needsTask;
    private BukkitTask hudTask;

    public SurvivalManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.playerDirectory = new File(plugin.getDataFolder(), "players");
        this.fatigueHealthKey = new NamespacedKey(plugin, "fatigue_health");
    }

    public void start() {
        if (!playerDirectory.exists() && !playerDirectory.mkdirs()) {
            plugin.getLogger().severe("Could not create player data directory: " + playerDirectory);
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            load(player);
        }
        needsTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 200L, 200L);
        hudTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateHud, 40L,
                Math.max(20L, plugin.getConfig().getLong("hud.update-ticks", 40L)));
    }

    public void shutdown() {
        if (needsTask != null) needsTask.cancel();
        if (hudTask != null) hudTask.cancel();
        for (Player player : Bukkit.getOnlinePlayers()) {
            removeHealthPenalty(player);
            save(player);
        }
        for (BossBar bar : bars.values()) bar.removeAll();
        bars.clear();
        players.clear();
    }

    public void load(Player player) {
        SurvivalData data = new SurvivalData();
        data.setTemperature(plugin.getConfig().getDouble("temperature.default", 37.0));
        File file = new File(playerDirectory, player.getUniqueId() + ".yml");
        if (file.isFile()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            data.setHydration(clamp(yaml.getDouble("hydration", 100.0), 0.0, 100.0));
            data.setFatigue(clamp(yaml.getDouble("fatigue", 0.0), 0.0, 100.0));
            data.setTemperature(clamp(yaml.getDouble("temperature", 37.0), 25.0, 45.0));
            data.setStimulantUntil(yaml.getLong("stimulant-until"));
            data.setShortRestUntil(yaml.getLong("short-rest-until"));
        }
        data.setLastWorldTick(player.getWorld().getFullTime());
        players.put(player.getUniqueId(), data);
        applyHealthPenalty(player, data.getFatigue());
    }

    public void quit(Player player) {
        save(player);
        players.remove(player.getUniqueId());
        warnedTiers.remove(player.getUniqueId());
        BossBar bar = bars.remove(player.getUniqueId());
        if (bar != null) bar.removeAll();
    }

    private void save(Player player) {
        SurvivalData data = players.get(player.getUniqueId());
        if (data == null) return;
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("hydration", data.getHydration());
        yaml.set("fatigue", data.getFatigue());
        yaml.set("temperature", data.getTemperature());
        yaml.set("stimulant-until", data.getStimulantUntil());
        yaml.set("short-rest-until", data.getShortRestUntil());
        try {
            yaml.save(new File(playerDirectory, player.getUniqueId() + ".yml"));
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save survival data for " + player.getName() + ": " + exception.getMessage());
        }
    }

    public SurvivalData get(Player player) {
        SurvivalData data = players.get(player.getUniqueId());
        if (data == null) {
            load(player);
            data = players.get(player.getUniqueId());
        }
        return data;
    }

    public boolean isSurviving(Player player) {
        String mode = player.getGameMode().name();
        return !mode.equals("CREATIVE") && !mode.equals("SPECTATOR");
    }

    public void drain(Player player, double amount) {
        if (!isSurviving(player) || amount <= 0) return;
        double multiplier = isHot(player) ? plugin.getConfig().getDouble("hydration.hot-biome-multiplier", 1.8) : 1.0;
        if (get(player).getTemperature() > plugin.getConfig().getDouble("temperature.heatstroke-threshold", 40.0)) {
            multiplier *= plugin.getConfig().getDouble("hydration.heatstroke-multiplier", 3.0);
        }
        SurvivalData data = get(player);
        data.setHydration(clamp(data.getHydration() - amount * multiplier, 0.0, 100.0));
    }

    public void hydrate(Player player, double amount) {
        SurvivalData data = get(player);
        data.setHydration(clamp(data.getHydration() + amount, 0.0, 100.0));
    }

    public void drinkRaw(Player player) {
        hydrate(player, plugin.getConfig().getDouble("hydration.raw-water-restore", 22.0));
        if (ThreadLocalRandom.current().nextDouble() < plugin.getConfig().getDouble("hydration.raw-water-illness-chance", 0.22)) {
            applyPotion(player, ThreadLocalRandom.current().nextBoolean() ? "NAUSEA" : "HUNGER", 300, 0);
            player.sendMessage(color("&cThe untreated water made you sick."));
        } else {
            player.sendMessage(color("&bYou drink untreated water."));
        }
    }

    public void stimulate(Player player) {
        SurvivalData data = get(player);
        long now = System.currentTimeMillis();
        data.setStimulantUntil(Math.max(now, data.getStimulantUntil())
                + plugin.getConfig().getLong("fatigue.stimulant-delay-seconds", 300L) * 1000L);
        applyPotion(player, "SPEED", (int) (plugin.getConfig().getLong("fatigue.stimulant-speed-seconds", 60L) * 20L), 0);
        player.sendMessage(color("&6The warm cocoa keeps you alert for a while."));
    }

    public boolean shortRest(Player player) {
        SurvivalData data = get(player);
        long now = System.currentTimeMillis();
        if (now < data.getShortRestUntil()) {
            player.sendMessage(color("&7You need more time before another short rest."));
            return false;
        }
        data.setFatigue(clamp(data.getFatigue() - plugin.getConfig().getDouble("fatigue.short-rest-recovery", 30.0), 0.0, 100.0));
        data.setShortRestUntil(now + plugin.getConfig().getLong("fatigue.short-rest-cooldown-seconds", 600L) * 1000L);
        data.setFireRestChecks(0);
        applyHealthPenalty(player, data.getFatigue());
        player.sendMessage(color("&aYou feel rested. Fatigue: " + (int) data.getFatigue() + "%"));
        return true;
    }

    public void fullRest(Player player) {
        SurvivalData data = get(player);
        data.setFatigue(0);
        data.setFireRestChecks(0);
        data.setLastWorldTick(player.getWorld().getFullTime());
        applyHealthPenalty(player, 0);
        player.sendMessage(color("&aA full night's sleep restored your energy."));
    }

    public double weight(Player player) {
        double total = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack == null || stack.getType().isAir()) continue;
            String type = stack.getType().name();
            double unit;
            if (type.contains("ANVIL")) unit = 28;
            else if (type.contains("NETHERITE_BLOCK")) unit = 35;
            else if (type.contains("NETHERITE")) unit = 5;
            else if (type.contains("FEATHER") || type.contains("SEEDS")) unit = 0.02;
            else if (type.contains("BUCKET")) unit = 8;
            else if (type.contains("IRON_BLOCK") || type.contains("GOLD_BLOCK")) unit = 12;
            else if (type.contains("IRON") || type.contains("GOLD")) unit = 2.5;
            else if (type.contains("STONE") || type.contains("COBBLE") || type.contains("DEEPSLATE")) unit = 0.55;
            else if (type.endsWith("_BLOCK") || type.endsWith("_LOG") || type.endsWith("_PLANKS")) unit = 0.4;
            else if (type.endsWith("_SWORD") || type.endsWith("_AXE") || type.endsWith("_PICKAXE")) unit = 1.5;
            else unit = 0.12;
            total += unit * stack.getAmount();
        }
        return total;
    }

    public boolean overburdened(Player player) {
        return weight(player) > plugin.getConfig().getDouble("encumbrance.sprint-limit", 180.0);
    }

    public boolean maximallyBurdened(Player player) {
        return weight(player) >= plugin.getConfig().getDouble("encumbrance.maximum-limit", 300.0);
    }

    public boolean isHot(Player player) {
        return player.getWorld().getEnvironment().name().equals("NETHER") || biome(player).contains("DESERT")
                || biome(player).contains("BADLANDS") || biome(player).contains("SAVANNA");
    }

    private String biome(Player player) {
        return player.getWorld().getBiome(player.getLocation()).getKey().getKey().toUpperCase(Locale.ROOT);
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            SurvivalData data = get(player);
            long worldTime = player.getWorld().getFullTime();
            long elapsed = data.getLastWorldTick() < 0 ? 0 : Math.max(0, Math.min(200, worldTime - data.getLastWorldTick()));
            data.setLastWorldTick(worldTime);
            if (!isSurviving(player) || player.isDead()) continue;

            updateTemperature(player, data);
            drain(player, plugin.getConfig().getDouble("hydration.passive-per-minute", 0.6) / 6.0);
            if (!player.isSleeping() && System.currentTimeMillis() >= data.getStimulantUntil()) {
                double days = Math.max(0.1, plugin.getConfig().getDouble("fatigue.full-exhaustion-days", 2.5));
                data.setFatigue(clamp(data.getFatigue() + elapsed * 100.0 / (24000.0 * days), 0, 100));
            }
            applyEffects(player, data);
            checkCampfireRest(player, data);
        }
    }

    private void updateTemperature(Player player, SurvivalData data) {
        Location loc = player.getLocation();
        World world = player.getWorld();
        String biome = biome(player);
        boolean hot = isHot(player);
        boolean cold = biome.contains("SNOW") || biome.contains("FROZEN") || biome.contains("ICE")
                || biome.contains("GROVE") || biome.contains("PEAKS") || biome.contains("TAIGA");
        double target = plugin.getConfig().getDouble("temperature.default", 37.0);
        if (world.getEnvironment().name().equals("NETHER")) target += 10;
        else if (hot) target += 6;
        if (cold) target -= 9;
        if (loc.getBlockY() > 130) target -= 2;
        long time = world.getTime();
        if (world.getEnvironment().name().equals("NORMAL")) {
            if (time >= 13000 && time <= 23000) target -= 2;
            else if (hot && world.getHighestBlockYAt(loc) <= loc.getBlockY()) target += 2;
        }
        if (player.isInWater() || player.getEyeLocation().getBlock().getType().name().equals("WATER")) target -= 7;

        boolean nearFire = false;
        for (int x = -2; x <= 2; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -2; z <= 2; z++) {
                    String type = world.getBlockAt(loc.getBlockX() + x, loc.getBlockY() + y, loc.getBlockZ() + z).getType().name();
                    if (type.equals("LAVA")) target += 0.18;
                    if (type.equals("FIRE") || type.equals("SOUL_FIRE") || type.endsWith("CAMPFIRE")) nearFire = true;
                }
            }
        }
        if (nearFire) target += 4;
        for (ItemStack piece : player.getInventory().getArmorContents()) {
            if (piece == null || piece.getType().isAir()) continue;
            String type = piece.getType().name();
            if (type.startsWith("LEATHER_")) target += hot ? 1.2 : 1.0;
            else if (type.startsWith("IRON_") || type.startsWith("CHAINMAIL_")) target += hot ? 1.5 : cold ? -1.0 : 0;
        }
        double rate = clamp(plugin.getConfig().getDouble("temperature.approach-per-check", 0.22), 0.0, 1.0);
        data.setTemperature(clamp(data.getTemperature() + (target - data.getTemperature()) * rate, 25, 45));
    }

    private void applyEffects(Player player, SurvivalData data) {
        double fatigue = data.getFatigue();
        double hydration = data.getHydration();
        double temp = data.getTemperature();
        boolean night = player.getWorld().getTime() >= 13000 && player.getWorld().getTime() <= 23000;
        if (fatigue >= 50 && night) applyPotion(player, "SLOWNESS", 220, 0);
        if (fatigue >= 75) {
            applyPotion(player, "MINING_FATIGUE", 220, 0);
            if (ThreadLocalRandom.current().nextDouble() < plugin.getConfig().getDouble("fatigue.blindness-chance-per-check", 0.08)) {
                applyPotion(player, "BLINDNESS", 40, 0);
            }
        }
        if (fatigue >= 90) {
            applyPotion(player, "DARKNESS", 220, 0);
            if (player.getStatistic(Statistic.TIME_SINCE_REST) < 72000) {
                player.setStatistic(Statistic.TIME_SINCE_REST, 72000);
            }
        }
        applyHealthPenalty(player, fatigue);
        if (hydration < 25) applyPotion(player, "SLOWNESS", 220, 0);
        if (hydration <= plugin.getConfig().getDouble("hydration.critical-threshold", 10.0)) {
            applyPotion(player, "NAUSEA", 220, 0);
            player.damage(1.0);
        }
        if (temp < plugin.getConfig().getDouble("temperature.hypothermia-threshold", 32.0)) {
            player.setFreezeTicks(Math.min(player.getMaxFreezeTicks(), player.getFreezeTicks() + 80));
            applyPotion(player, "SLOWNESS", 220, 0);
            player.damage(1.0);
        }
        if (temp > plugin.getConfig().getDouble("temperature.heatstroke-threshold", 40.0)) {
            applyPotion(player, "NAUSEA", 220, 0);
            player.damage(1.0);
        }
        if (maximallyBurdened(player)) applyPotion(player, "SLOWNESS", 220, 1);
        if (overburdened(player) && player.isSprinting()) player.setSprinting(false);

        int tier = fatigue >= 90 ? 3 : fatigue >= 75 ? 2 : fatigue >= 50 ? 1 : 0;
        int oldTier = warnedTiers.getOrDefault(player.getUniqueId(), 0);
        if (tier > oldTier) player.sendMessage(color("&eFatigue is at " + (int) fatigue + "% — find a bed or rest near a campfire."));
        warnedTiers.put(player.getUniqueId(), tier);
    }

    private void applyHealthPenalty(Player player, double fatigue) {
        XAttribute.of("max_health").ifPresent(attribute -> {
            AttributeInstance instance = player.getAttribute(attribute.get());
            if (instance == null) return;
            double penalty = fatigue >= 95 ? -8.0 : fatigue >= 90 ? -4.0 : 0.0;
            AttributeModifier existing = instance.getModifier(fatigueHealthKey);
            if (existing != null && existing.getAmount() == penalty) return;
            if (existing != null) instance.removeModifier(existing);
            if (penalty != 0) {
                instance.addModifier(new AttributeModifier(fatigueHealthKey, penalty, AttributeModifier.Operation.ADD_NUMBER));
                if (player.getHealth() > instance.getValue()) player.setHealth(Math.max(1.0, instance.getValue()));
            }
        });
    }

    private void removeHealthPenalty(Player player) {
        XAttribute.of("max_health").ifPresent(attribute -> {
            AttributeInstance instance = player.getAttribute(attribute.get());
            if (instance != null) {
                AttributeModifier modifier = instance.getModifier(fatigueHealthKey);
                if (modifier != null) instance.removeModifier(modifier);
            }
        });
    }

    private void checkCampfireRest(Player player, SurvivalData data) {
        if (!player.isSneaking() || player.getVelocity().lengthSquared() > 0.02 || player.isInWater()) {
            data.setFireRestChecks(0);
            return;
        }
        Location loc = player.getLocation();
        boolean nearby = false;
        for (int x = -3; x <= 3 && !nearby; x++) {
            for (int y = -2; y <= 2 && !nearby; y++) {
                for (int z = -3; z <= 3; z++) {
                    if (loc.getWorld().getBlockAt(loc.getBlockX() + x, loc.getBlockY() + y, loc.getBlockZ() + z)
                            .getType().name().endsWith("CAMPFIRE")) {
                        nearby = true;
                        break;
                    }
                }
            }
        }
        if (!nearby) {
            data.setFireRestChecks(0);
            return;
        }
        data.setFireRestChecks(data.getFireRestChecks() + 1);
        if (data.getFireRestChecks() >= Math.max(1, plugin.getConfig().getInt("fatigue.campfire-rest-seconds", 30) / 10)) {
            shortRest(player);
            data.setFireRestChecks(0);
        }
    }

    private void updateHud() {
        boolean bossMode = plugin.getConfig().getString("hud.mode", "actionbar").equalsIgnoreCase("bossbar");
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isSurviving(player)) continue;
            SurvivalData data = get(player);
            double weight = weight(player);
            String extra = String.format(Locale.ROOT, "&eFatigue &f%d%% &8| &bTemp &f%.1f°C &8| &6Load &f%.0f",
                    (int) data.getFatigue(), data.getTemperature(), weight);
            String thirst = String.format(Locale.ROOT, "&bWater &f%d%%", (int) data.getHydration());
            if (bossMode) {
                BossBar bar = bars.computeIfAbsent(player.getUniqueId(), id -> {
                    BossBar created = Bukkit.createBossBar("", BarColor.BLUE, BarStyle.SEGMENTED_10);
                    created.addPlayer(player);
                    return created;
                });
                bar.setTitle(color(thirst));
                bar.setColor(data.getHydration() < 25 ? BarColor.RED : BarColor.BLUE);
                bar.setProgress(clamp(data.getHydration() / 100.0, 0, 1));
                player.sendActionBar(LegacyComponentSerializer.legacyAmpersand().deserialize(extra));
            } else {
                BossBar old = bars.remove(player.getUniqueId());
                if (old != null) old.removeAll();
                player.sendActionBar(LegacyComponentSerializer.legacyAmpersand().deserialize(thirst + " &8| " + extra));
            }
        }
    }

    public void reloadHud() {
        for (BossBar bar : bars.values()) bar.removeAll();
        bars.clear();
        if (hudTask != null) hudTask.cancel();
        hudTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateHud, 1L,
                Math.max(20L, plugin.getConfig().getLong("hud.update-ticks", 40L)));
    }

    public static void applyPotion(Player player, String name, int ticks, int amplifier) {
        XPotion.matchXPotion(name).map(effect -> effect.buildPotionEffect(ticks, amplifier)).ifPresent(player::addPotionEffect);
    }

    public static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}