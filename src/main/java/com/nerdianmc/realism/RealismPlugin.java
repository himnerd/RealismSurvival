package com.nerdianmc.realism;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class RealismPlugin extends JavaPlugin {
    private SurvivalManager manager;
    private SurvivalItems items;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new SurvivalManager(this);
        items = new SurvivalItems(this);
        items.registerRecipes();
        Bukkit.getPluginManager().registerEvents(new SurvivalListener(manager, items), this);
        SurvivalCommand command = new SurvivalCommand(this, manager, items);
        getCommand("survival").setExecutor(command);
        getCommand("survival").setTabCompleter(command);
        manager.start();
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.shutdown();
        }
    }
}