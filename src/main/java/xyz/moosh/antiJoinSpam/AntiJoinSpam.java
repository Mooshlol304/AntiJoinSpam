package xyz.moosh.antiJoinSpam;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.translation.GlobalTranslator;
import net.kyori.adventure.translation.TranslationRegistry;
import net.kyori.adventure.util.UTF8ResourceBundleControl;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class AntiJoinSpam extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final @NotNull String KEY_RELOAD  = "antijoinspam.command.reload";
    private static final @NotNull String KEY_CLEAR   = "antijoinspam.command.clear";
    private static final @NotNull String KEY_USAGE   = "antijoinspam.command.usage";
    private static final @NotNull String KEY_NO_PERM = "antijoinspam.command.noperm";

    private static final @NotNull String PERM_ADMIN = "antijoinspam.admin";

    // per-UUID login timestamps in ms; inner lists are synchronizedList
    private final @NotNull Map<UUID, List<Long>> loginTimestamps = new ConcurrentHashMap<>();
    private final @NotNull Set<UUID> suppressedPlayers = ConcurrentHashMap.newKeySet();

    private int timeWindowSeconds;
    private int maxLogins;

    // -- lifecycle --

    @Override
    public void onEnable() {
        saveDefaultConfig();
        applyConfig();
        registerTranslations();
        getServer().getPluginManager().registerEvents(this, this);
        registerCommand();
        scheduleCleanup();
        getLogger().info("Enabled | window=" + timeWindowSeconds + "s  max-logins=" + maxLogins);
    }

    @Override
    public void onDisable() {
        loginTimestamps.clear();
        suppressedPlayers.clear();
        getLogger().info("Disabled — state cleared.");
    }

    // -- config --

    private void applyConfig() {
        reloadConfig();
        timeWindowSeconds = getConfig().getInt("time-window", 60);
        maxLogins         = getConfig().getInt("max-logins", 2);
    }

    // -- translations --

    private void registerTranslations() {
        final TranslationRegistry registry = TranslationRegistry.create(
                net.kyori.adventure.key.Key.key("antijoinspam", "translations")
        );
        registry.registerAll(
                Locale.US,
                ResourceBundle.getBundle(
                        "translations.antijoinspam", Locale.US,
                        getClass().getClassLoader(), UTF8ResourceBundleControl.get()
                ),
                true
        );
        GlobalTranslator.translator().addSource(registry);
    }

    // -- commands --

    private void registerCommand() {
        final @Nullable PluginCommand cmd = getCommand("antijoinspam");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
    }

    @Override
    public boolean onCommand(
            final @NotNull CommandSender sender,
            final @NotNull Command command,
            final @NotNull String label,
            final @NotNull String[] args
    ) {
        if (!sender.hasPermission(PERM_ADMIN)) {
            sender.sendMessage(prefix().append(Component.translatable(KEY_NO_PERM, NamedTextColor.RED)));
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            applyConfig();
            sender.sendMessage(prefix().append(Component.translatable(KEY_RELOAD, NamedTextColor.GREEN)));
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("clear")) {
            final int count = suppressedPlayers.size();
            suppressedPlayers.clear();
            loginTimestamps.clear();
            sender.sendMessage(prefix().append(
                    Component.translatable(KEY_CLEAR, NamedTextColor.GREEN, Component.text(count))
            ));
            return true;
        }

        sender.sendMessage(prefix().append(Component.translatable(KEY_USAGE, NamedTextColor.YELLOW)));
        return true;
    }

    @Override
    public @NotNull List<String> onTabComplete(
            final @NotNull CommandSender sender,
            final @NotNull Command command,
            final @NotNull String label,
            final @NotNull String[] args
    ) {
        if (!sender.hasPermission(PERM_ADMIN)) return Collections.emptyList();
        if (args.length == 1) {
            final List<String> completions = new ArrayList<>(List.of("reload", "clear"));
            completions.removeIf(s -> !s.startsWith(args[0].toLowerCase(Locale.ROOT)));
            return completions;
        }
        return Collections.emptyList();
    }

    // -- events --

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAsyncPreLogin(final @NotNull AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;

        final UUID uuid   = event.getUniqueId();
        final long now    = System.currentTimeMillis();
        final long cutoff = now - ((long) timeWindowSeconds * 1_000L);

        final List<Long> stamps = loginTimestamps.computeIfAbsent(
                uuid, k -> Collections.synchronizedList(new ArrayList<>())
        );

        synchronized (stamps) {
            stamps.removeIf(t -> t < cutoff);
            stamps.add(now);

            if (stamps.size() > maxLogins) {
                suppressedPlayers.add(uuid);
            } else {
                suppressedPlayers.remove(uuid);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(final @NotNull PlayerJoinEvent event) {
        if (!suppressedPlayers.contains(event.getPlayer().getUniqueId())) return;
        event.joinMessage(null);
        getLogger().info("Suppressed join message for " + event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerQuit(final @NotNull PlayerQuitEvent event) {
        if (!suppressedPlayers.contains(event.getPlayer().getUniqueId())) return;
        event.quitMessage(null);
        getLogger().info("Suppressed quit message for " + event.getPlayer().getName());
    }

    // -- helpers --

    private @NotNull Component prefix() {
        return Component.text()
                .append(Component.text("[", NamedTextColor.DARK_GRAY))
                .append(Component.text("AntiJoinSpam", NamedTextColor.GOLD))
                .append(Component.text("] ", NamedTextColor.DARK_GRAY))
                .build();
    }

    private void scheduleCleanup() {
        if (isFolia()) {
            Bukkit.getAsyncScheduler().runAtFixedRate(
                    this, task -> pruneExpiredEntries(), 30L, 30L, TimeUnit.SECONDS
            );
        } else {
            Bukkit.getScheduler().runTaskTimerAsynchronously(
                    this, this::pruneExpiredEntries, 600L, 600L // 600t = 30s
            );
        }
    }

    private void pruneExpiredEntries() {
        final long cutoff = System.currentTimeMillis() - ((long) timeWindowSeconds * 1_000L);

        loginTimestamps.entrySet().removeIf(entry -> {
            final List<Long> stamps = entry.getValue();
            synchronized (stamps) {
                stamps.removeIf(t -> t < cutoff);

                if (stamps.isEmpty()) {
                    suppressedPlayers.remove(entry.getKey());
                    return true;
                }

                if (stamps.size() <= maxLogins) {
                    suppressedPlayers.remove(entry.getKey());
                }
                return false;
            }
        });
    }

    // runtime Folia detection — no hard compile-time dep needed
    private static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (final ClassNotFoundException ignored) {
            return false;
        }
    }
}