/*
 * Dangerous Caves 2 | Make your caves scary
 * Copyright (C) 2020  imDaniX
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package me.imdanix.caves.caverns;

import me.imdanix.caves.compatibility.Compatibility;
import me.imdanix.caves.configuration.Configurable;
import me.imdanix.caves.configuration.Configuration;
import me.imdanix.caves.placeholders.Placeholder;
import me.imdanix.caves.regions.CheckType;
import me.imdanix.caves.regions.Regions;
import me.imdanix.caves.ticks.TickLevel;
import me.imdanix.caves.ticks.Tickable;
import me.imdanix.caves.util.FormulasEvaluator;
import me.imdanix.caves.util.Locations;
import me.imdanix.caves.util.Utils;
import me.imdanix.caves.util.random.Rnd;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

public class DepthHypoxia implements Tickable, Configurable {
    private static final PotionEffect SLOW = new PotionEffect(PotionEffectType.SLOW, 120, 1);
    private static final PotionEffect SLOW_DIGGING = new PotionEffect(PotionEffectType.SLOW_DIGGING, 55, 1);

    private final Plugin plugin;
    private final Configuration config;

    private final Set<String> worlds;
    private final HypoxiaChancePlaceholder placeholder;

    private boolean disabled;

    private List<String> messages;
    private boolean actionbar;
    private double chance;
    private double maxChance;
    private double minChance;
    private int yMax;

    private FormulasEvaluator formula;
    private Predicate<Player> condition;

    public DepthHypoxia(Plugin plugin, Configuration config) {
        this.plugin = plugin;
        this.config = config;
        worlds = new HashSet<>();
        messages = new ArrayList<>();
        placeholder = new HypoxiaChancePlaceholder();
    }

    @Override
    public void reload(ConfigurationSection cfg) {
        chance = cfg.getDouble("try-chance", 60) / 100;
        maxChance = cfg.getDouble("chance-max", 90) / 100;
        minChance = cfg.getDouble("chance-min", 10) / 100;
        yMax = cfg.getInt("y-max", 42);
        actionbar = cfg.getBoolean("actionbar", true);
        messages.clear();
        messages.addAll(Utils.clr(cfg.getStringList("messages")));
        worlds.clear();
        Utils.fillWorlds(cfg.getStringList("worlds"), worlds);
        try {
            formula = new FormulasEvaluator(cfg.getString("chance-formula", "depth*inventory"));
            formula.setVariable("depth", 1d);
            formula.setVariable("inventory", 1d);
            formula.eval();
        } catch (Exception e) {
            formula = new FormulasEvaluator("depth*inventory");
            Bukkit.getPluginManager().getPlugin("DangerousCaves").getLogger().warning("Depth Hypoxia formula " +
                    "is invalid! Please fix the issue. \"depth*inventory\" formula is used instead.");
        }

        config.reload(placeholder);
        condition = placeholder.isEnabled() ? this::checkConditionsPH : this::checkConditions;

        disabled = !(cfg.getBoolean("enabled", true) && yMax > 0 && chance > 0 && minChance > 0 &&
                !worlds.isEmpty());
    }

    @Override
    public void tick() {
        if (disabled) return;

        for (World world : Bukkit.getWorlds()) {
            if (!worlds.contains(world.getName())) continue;
            for (Player player : world.getPlayers()) {
                if (!condition.test(player)) continue;
                player.addPotionEffect(SLOW);
                player.addPotionEffect(SLOW_DIGGING);
                if (messages.isEmpty()) continue;
                String text = Rnd.randomElement(messages).replace("%player", player.getName());
                if (actionbar) {
                    Compatibility.sendActionBar(player, text);
                } else {
                    player.sendMessage(text);
                }
            }
        }
    }

    private boolean checkConditions(Player player) {
        Location loc = player.getLocation();
        return Locations.isCave(loc) && loc.getY() <= yMax &&
                Rnd.chance(chance) && Rnd.chance(getChance(player)) &&
                Regions.INSTANCE.check(CheckType.EFFECT, loc);
    }

    private boolean checkConditionsPH(Player player) {
        Location loc = player.getLocation();
        if (!Locations.isCave(loc) || loc.getY() > yMax) {
            placeholder.removePlayer(player);
            return false;
        }
        if (Rnd.chance(chance)) {
            double hypoxiaChance;
            boolean check = Rnd.chance(hypoxiaChance = getChance(player));
            placeholder.cachePlayer(hypoxiaChance, player);
            return check && Regions.INSTANCE.check(CheckType.EFFECT, loc);
        } else {
            placeholder.cachePlayer(getChance(player), player);
            return false;
        }
    }

    private double getChance(Player player) {
        double depthChance = (yMax - player.getLocation().getY()) / yMax;
        double weightChance = 0;
        ItemStack[] contents = player.getInventory().getContents();
        for (ItemStack item : contents) {
            if (item == null) continue;
            weightChance += item.getAmount() / item.getMaxStackSize();
        }
        weightChance /= contents.length;
        formula.setVariable("depth", depthChance);
        formula.setVariable("inventory", weightChance);
        return Math.max(minChance, Math.min(maxChance, formula.eval()));
    }

    @Override
    public TickLevel getTickLevel() {
        return TickLevel.PLAYER;
    }

    @Override
    public String getConfigPath() {
        return "caverns.hypoxia";
    }

    public Placeholder getPlaceholder() {
        return placeholder;
    }

    private class HypoxiaChancePlaceholder implements Placeholder, Configurable {
        private final Map<Player, String> chances;
        private boolean enabled;
        private BukkitTask task;
        private boolean tryChance;

        public HypoxiaChancePlaceholder() {
            chances = new WeakHashMap<>();
        }

        @Override
        public void reload(ConfigurationSection cfg) {
            enabled = cfg.getBoolean("enabled", false);
            if (task != null) {
                task.cancel();
                task = null;
            }
            if (enabled) {
                tryChance = cfg.getBoolean("respect-try-chance", true);
                int schedule = cfg.getInt("schedule", 200);
                if (schedule <= 0) {
                    task = Bukkit.getScheduler().runTaskTimer(plugin, () ->
                            Bukkit.getOnlinePlayers().forEach(DepthHypoxia.this::checkConditions),
                            schedule, schedule);
                }
            }
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void cachePlayer(double hypoxiaChance, Player player) {
            chances.put(player, Double.toString(Math.floor(hypoxiaChance * (tryChance ? 10000 * chance : 10000)) / 100));
        }

        public void removePlayer(Player player) {
            chances.remove(player);
        }

        @Override
        public String getConfigPath() {
            return "integration.placeholders.hypoxia-chance";
        }

        @Override
        public String getName() {
            return "hypoxia_chance";
        }

        @Override
        public String getValue(Player player) {
            return chances.getOrDefault(player, "0.0");
        }
    }
}