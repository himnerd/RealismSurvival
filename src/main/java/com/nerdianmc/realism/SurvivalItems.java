package com.nerdianmc.realism;

import com.cryptomorin.xseries.XMaterial;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionType;

import java.util.List;

public final class SurvivalItems {
    private final NamespacedKey purifiedKey;
    private final NamespacedKey cocoaKey;
    private final NamespacedKey bagKey;
    private final JavaPlugin plugin;

    public SurvivalItems(JavaPlugin plugin) {
        this.plugin = plugin;
        purifiedKey = new NamespacedKey(plugin, "purified_water");
        cocoaKey = new NamespacedKey(plugin, "brewed_cocoa");
        bagKey = new NamespacedKey(plugin, "sleeping_bag");
    }

    private ItemStack item(String material) {
        return XMaterial.matchXMaterial(material).map(XMaterial::parseItem)
                .orElseThrow(() -> new IllegalStateException("Missing Minecraft item: " + material));
    }

    public ItemStack purifiedWater() {
        ItemStack result = item("POTION");
        PotionMeta meta = (PotionMeta) result.getItemMeta();
        meta.setBasePotionType(PotionType.WATER);
        meta.setDisplayName(SurvivalManager.color("&bPurified Water"));
        meta.setLore(List.of(SurvivalManager.color("&7Boiled and safe to drink."),
                SurvivalManager.color("&bRestores all hydration.")));
        meta.getPersistentDataContainer().set(purifiedKey, PersistentDataType.BYTE, (byte) 1);
        result.setItemMeta(meta);
        return result;
    }

    public ItemStack cocoa() {
        ItemStack result = item("POTION");
        PotionMeta meta = (PotionMeta) result.getItemMeta();
        meta.setBasePotionType(PotionType.WATER);
        meta.setDisplayName(SurvivalManager.color("&6Brewed Cocoa"));
        meta.setLore(List.of(SurvivalManager.color("&7Delays fatigue for half a Minecraft day."),
                SurvivalManager.color("&eBriefly boosts movement speed.")));
        meta.getPersistentDataContainer().set(cocoaKey, PersistentDataType.BYTE, (byte) 1);
        result.setItemMeta(meta);
        return result;
    }

    public ItemStack sleepingBag() {
        ItemStack result = item("WHITE_CARPET");
        ItemMeta meta = result.getItemMeta();
        meta.setDisplayName(SurvivalManager.color("&fSleeping Bag"));
        meta.setLore(List.of(SurvivalManager.color("&7Use for a short rest without skipping the night.")));
        meta.getPersistentDataContainer().set(bagKey, PersistentDataType.BYTE, (byte) 1);
        result.setItemMeta(meta);
        return result;
    }

    private boolean tagged(ItemStack stack, NamespacedKey key) {
        return stack != null && stack.hasItemMeta()
                && stack.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    public boolean isPurified(ItemStack stack) {
        return tagged(stack, purifiedKey);
    }

    public boolean isCocoa(ItemStack stack) {
        return tagged(stack, cocoaKey);
    }

    public boolean isSleepingBag(ItemStack stack) {
        return tagged(stack, bagKey);
    }

    public boolean isWaterBottle(ItemStack stack) {
        if (stack == null || !stack.getType().name().equals("POTION") || isPurified(stack) || isCocoa(stack)) return false;
        return stack.getItemMeta() instanceof PotionMeta meta && meta.getBasePotionType() == PotionType.WATER;
    }

    public void registerRecipes() {
        Bukkit.addRecipe(new FurnaceRecipe(new NamespacedKey(plugin, "boil_water"), purifiedWater(),
                item("POTION").getType(), 0.1f, 200));

        ShapedRecipe bag = new ShapedRecipe(new NamespacedKey(plugin, "sleeping_bag_recipe"), sleepingBag());
        bag.shape("WWW", " S ");
        bag.setIngredient('W', item("WHITE_WOOL").getType());
        bag.setIngredient('S', item("STRING").getType());
        Bukkit.addRecipe(bag);

        ShapelessRecipe drink = new ShapelessRecipe(new NamespacedKey(plugin, "brewed_cocoa_recipe"), cocoa());
        drink.addIngredient(item("POTION").getType());
        drink.addIngredient(item("COCOA_BEANS").getType());
        drink.addIngredient(item("SUGAR").getType());
        Bukkit.addRecipe(drink);
    }
}