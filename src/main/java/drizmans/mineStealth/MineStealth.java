package drizmans.mineStealth;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.ScoreboardManager; // Import for ScoreboardManager

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public final class MineStealth extends JavaPlugin implements Listener, CommandExecutor {

    // A list to store the UUIDs of players currently in stealth mode.
    // This list will be loaded from and saved to the plugin's config.
    private List<UUID> stealthPlayers;

    // Store a reference to the ScoreboardManager for easier access.
    private ScoreboardManager scoreboardManager;

    @Override
    public void onEnable() {
        getLogger().info("MineStealth plugin enabled!");

        // Get the ScoreboardManager instance when the plugin enables.
        scoreboardManager = Bukkit.getScoreboardManager();

        // --------------------------------------------------------------------
        // Configuration Setup:
        // This section handles loading and saving of the stealth player list.
        // --------------------------------------------------------------------
        // Saves the default config.yml if it doesn't exist. This ensures we have a base file.
        saveDefaultConfig();
        // Load the list of stealth player UUIDs from the plugin's config.
        // The getStealthPlayers method handles conversion from String to UUID.
        stealthPlayers = loadStealthPlayers();

        // --------------------------------------------------------------------
        // Event and Command Registration:
        // This section registers the necessary components for the plugin to function.
        // --------------------------------------------------------------------
        // Register this class as an event listener for Bukkit events.
        Bukkit.getPluginManager().registerEvents(this, this);
        // Register this class as the executor for the "stealth" command.
        getCommand("stealth").setExecutor(this);

        // Apply stealth properties to any players who are already online
        // and in the stealth list when the plugin enables (e.g., after a reload).
        // Also, apply scoreboard wipe to all non-stealth players already online.
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (stealthPlayers.contains(onlinePlayer.getUniqueId())) {
                applyStealth(onlinePlayer);
            } else {
                // If the player is online and not in stealth, wipe their scoreboard.
                onlinePlayer.setScoreboard(scoreboardManager.getNewScoreboard());
            }
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("MineStealth plugin disabled!");
        // Save the current list of stealth player UUIDs to the plugin's config.
        saveStealthPlayers();

        // When the plugin disables, ensure all stealth players have their visibility properties reverted.
        // For non-stealth players, their scoreboard will remain wiped unless another plugin intervenes.
        for (UUID uuid : stealthPlayers) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                removeStealth(player); // Only revert visibility, not scoreboard for these players
            }
        }
    }

    /**
     * Loads the list of stealth player UUIDs from the plugin's config.yml.
     * Stored as a list of strings and converted to UUIDs.
     * @return A List of UUIDs of players in stealth mode.
     */
    private List<UUID> loadStealthPlayers() {
        List<String> uuidStrings = getConfig().getStringList("stealth-players");
        return uuidStrings.stream()
                .map(uuidString -> {
                    try {
                        return UUID.fromString(uuidString);
                    } catch (IllegalArgumentException e) {
                        getLogger().warning("Invalid UUID found in config: " + uuidString);
                        return null; // Return null for invalid UUIDs
                    }
                })
                .filter(java.util.Objects::nonNull) // Filter out any null UUIDs
                .collect(Collectors.toList());
    }

    /**
     * Saves the current list of stealth player UUIDs to the plugin's config.yml.
     * UUIDs are converted to strings for storage.
     */
    private void saveStealthPlayers() {
        List<String> uuidStrings = stealthPlayers.stream()
                .map(UUID::toString)
                .collect(Collectors.toList());
        getConfig().set("stealth-players", uuidStrings);
        saveConfig(); // Persist changes to the disk.
    }

    /**
     * Applies all necessary properties to a player to put them into stealth mode.
     * This includes setting gamemode, hiding from other players, and preventing targeting.
     * For stealth players, their scoreboard is NOT modified; they will see the main server scoreboard.
     * @param player The Player object to apply stealth to.
     */
    private void applyStealth(Player player) {
        player.setGameMode(GameMode.SPECTATOR); // Essential for non-interaction and invisibility
        player.setCollidable(false); // Ensure they don't collide with other entities

        // Hide player from all other currently online players
        for (Player otherPlayer : Bukkit.getOnlinePlayers()) {
            if (!otherPlayer.equals(player)) { // Don't hide player from themselves
                otherPlayer.hidePlayer(this, player);
            }
        }

        // The method Player#setPlayerListVisible(boolean) does not exist in Bukkit/Paper API.
        // To hide from tab list, a packet-based solution (e.g., ProtocolLib) or NMS is required.
        // We are also not modifying the scoreboard for stealth players, as they should see it.
        getLogger().info("Applied stealth properties to " + player.getName() + ".");
    }

    /**
     * Removes all stealth properties from a player, reverting their visibility to normal.
     * This includes restoring gamemode and showing them to other players.
     * When a player is removed from stealth, they become a regular player,
     * so their scoreboard will be wiped according to the plugin's rules for regular players.
     * @param player The Player object to remove stealth from.
     */
    private void removeStealth(Player player) {
        // Show player to all other currently online players
        for (Player otherPlayer : Bukkit.getOnlinePlayers()) {
            if (!otherPlayer.equals(player)) { // Don't try to show player to themselves
                otherPlayer.showPlayer(this, player);
                player.showPlayer(this, otherPlayer); // Ensure player is shown to others
            }
        }

        // The method Player#setPlayerListVisible(boolean) does not exist in Bukkit/Paper API.
        // To re-show in tab list, a packet-based solution (e.g., ProtocolLib) or NMS is required.

        // Revert gamemode to SURVIVAL (or their last known non-stealth gamemode, if tracked)
        // For simplicity, we'll revert to SURVIVAL here. A more advanced plugin might save their previous gamemode.
        player.setGameMode(GameMode.SURVIVAL);
        player.setCollidable(true);
        getLogger().info("Removed stealth properties from " + player.getName() + ".");
    }

    /**
     * Event handler for player join events.
     * Hides join messages and applies stealth (or scoreboard wipe) based on player status.
     * @param event The PlayerJoinEvent instance.
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Hide the default join message.

        if (stealthPlayers.contains(player.getUniqueId())) {
            // If the joining player is in stealth mode, apply stealth properties.
            // Their scoreboard is NOT modified by this plugin.
            applyStealth(player);
            player.sendMessage("§aYou are currently in stealth mode.");
            event.setJoinMessage(null);
        } else {
            // This player is NOT in stealth mode, so wipe their scoreboard.
            player.setScoreboard(scoreboardManager.getNewScoreboard());

            // For non-stealth players joining, ensure they can see all non-stealth players
            // and cannot see stealth players.
            for (Player otherPlayer : Bukkit.getOnlinePlayers()) {
                if (stealthPlayers.contains(otherPlayer.getUniqueId())) {
                    // Hide stealth players from this non-stealth joiner
                    player.hidePlayer(this, otherPlayer);
                } else if (!otherPlayer.equals(player)) {
                    // Ensure the new non-stealth joiner sees other non-stealth players
                    player.showPlayer(this, otherPlayer);
                }
            }
        }
    }

    /**
     * Event handler for player quit events.
     * Hides quit messages.
     * @param event The PlayerQuitEvent instance.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        // Hide the default quit message.
        if (stealthPlayers.contains(player.getUniqueId())) {
            event.setQuitMessage(null);
        }

        // No scoreboard changes needed here as the player is leaving the server.
    }

    /**
     * Event handler for asynchronous player chat events.
     * Cancels chat messages from players in stealth mode.
     * @param event The AsyncPlayerChatEvent instance.
     */
    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (stealthPlayers.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true); // Cancel chat message for stealth players.
            event.getPlayer().sendMessage("§cYou cannot chat while in stealth mode.");
        }
    }

    /**
     * Event handler for player gamemode change events.
     * Ensures players in stealth mode remain in spectator gamemode.
     * @param event The PlayerGameModeChangeEvent instance.
     */
    @EventHandler
    public void onPlayerGameModeChange(PlayerGameModeChangeEvent event) {
        if (stealthPlayers.contains(event.getPlayer().getUniqueId())) {
            if (event.getNewGameMode() != GameMode.SPECTATOR) {
                event.setCancelled(true); // Prevent stealth players from changing out of spectator mode.
                event.getPlayer().sendMessage("§cYou must remain in spectator mode while in stealth.");
            }
        }
    }

    /**
     * Event handler for entity targeting events.
     * Prevents living entities (mobs) from targeting players in stealth mode.
     * @param event The EntityTargetLivingEntityEvent instance.
     */
    @EventHandler
    public void onEntityTarget(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() instanceof Player) {
            Player target = (Player) event.getTarget();
            if (stealthPlayers.contains(target.getUniqueId())) {
                event.setCancelled(true); // Prevent mobs from targeting stealth players.
            }
        }
    }

    /**
     * Handles the execution of the /stealth command.
     * Allows adding, removing, and listing players in stealth mode.
     * @param sender The entity that sent the command (e.g., a Player, ConsoleSender).
     * @param command The Command object representing the executed command.
     * @param label The alias of the command used (e.g., "stealth").
     * @param args An array of strings, representing the arguments passed to the command.
     * @return true if the command was handled successfully, false otherwise.
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // --------------------------------------------------------------------
        // Permission Check:
        // Verify if the sender has the necessary permission to use this command.
        // --------------------------------------------------------------------
        if (!sender.hasPermission("minestealth.admin")) {
            sender.sendMessage("§cYou do not have permission to use this command.");
            return true;
        }

        // --------------------------------------------------------------------
        // Argument Validation:
        // Check for correct command usage (sub-command and player name if applicable).
        // --------------------------------------------------------------------
        if (args.length < 1) {
            sender.sendMessage("§cUsage: /stealth <add|remove|list> [player_name]");
            return true;
        }

        String subCommand = args[0].toLowerCase(); // Get the sub-command (add, remove, list).

        switch (subCommand) {
            case "add":
                if (args.length != 2) {
                    sender.sendMessage("§cUsage: /stealth add <player_name>");
                    return true;
                }
                handleAddCommand(sender, args[1]);
                break;
            case "remove":
                if (args.length != 2) {
                    sender.sendMessage("§cUsage: /stealth remove <player_name>");
                    return true;
                }
                handleRemoveCommand(sender, args[1]);
                break;
            case "list":
                handleListCommand(sender);
                break;
            default:
                sender.sendMessage("§cUnknown sub-command. Usage: /stealth <add|remove|list>");
                break;
        }

        return true; // Indicate that the command was successfully handled.
    }

    /**
     * Handles the "add" sub-command, adding a player to stealth mode.
     * @param sender The command sender.
     * @param targetPlayerName The name of the player to add.
     */
    private void handleAddCommand(CommandSender sender, String targetPlayerName) {
        OfflinePlayer targetOfflinePlayer = Bukkit.getOfflinePlayer(targetPlayerName);

        if (targetOfflinePlayer == null || !targetOfflinePlayer.hasPlayedBefore()) {
            sender.sendMessage("§cPlayer '" + targetPlayerName + "' has never played on this server or does not exist.");
            return;
        }

        UUID targetUUID = targetOfflinePlayer.getUniqueId();

        if (stealthPlayers.contains(targetUUID)) {
            sender.sendMessage("§cPlayer '" + targetPlayerName + "' is already in stealth mode.");
            return;
        }

        stealthPlayers.add(targetUUID);
        saveStealthPlayers(); // Save the updated list to config.

        Player targetPlayer = targetOfflinePlayer.getPlayer();
        if (targetPlayer != null && targetPlayer.isOnline()) {
            // Apply stealth properties to the player (visibility, gamemode etc.)
            applyStealth(targetPlayer);
            // Since they are now a stealth player, their scoreboard is NOT wiped.
            // If it was wiped before, it will remain wiped unless another plugin changes it.
            // To ensure they see the main scoreboard, we explicitly set it.
            targetPlayer.setScoreboard(scoreboardManager.getMainScoreboard());
            targetPlayer.sendMessage("§aYou have been put into stealth mode.");
            sender.sendMessage("§aPlayer '" + targetPlayerName + "' has been put into stealth mode.");
        } else {
            sender.sendMessage("§aPlayer '" + targetPlayerName + "' will be in stealth mode when they next join.");
            // No scoreboard change needed here as they are offline.
            // Their scoreboard will be handled by onPlayerJoin when they connect.
        }
    }

    /**
     * Handles the "remove" sub-command, removing a player from stealth mode.
     * @param sender The command sender.
     * @param targetPlayerName The name of the player to remove.
     */
    private void handleRemoveCommand(CommandSender sender, String targetPlayerName) {
        OfflinePlayer targetOfflinePlayer = Bukkit.getOfflinePlayer(targetPlayerName);

        if (targetOfflinePlayer == null || !targetOfflinePlayer.hasPlayedBefore()) {
            sender.sendMessage("§cPlayer '" + targetPlayerName + "' has never played on this server or does not exist.");
            return;
        }

        UUID targetUUID = targetOfflinePlayer.getUniqueId();

        if (!stealthPlayers.contains(targetUUID)) {
            sender.sendMessage("§cPlayer '" + targetPlayerName + "' is not currently in stealth mode.");
            return;
        }

        stealthPlayers.remove(targetUUID);
        saveStealthPlayers(); // Save the updated list to config.

        Player targetPlayer = targetOfflinePlayer.getPlayer();
        if (targetPlayer != null && targetPlayer.isOnline()) {
            // Remove stealth properties (visibility, gamemode etc.)
            removeStealth(targetPlayer);
            // Now that they are no longer stealth, apply the non-stealth player rule: wipe their scoreboard.
            targetPlayer.setScoreboard(scoreboardManager.getNewScoreboard());
            targetPlayer.sendMessage("§aYou have been removed from stealth mode.");
            sender.sendMessage("§aPlayer '" + targetPlayerName + "' has been removed from stealth mode.");
        } else {
            sender.sendMessage("§aPlayer '" + targetPlayerName + "' will no longer be in stealth mode when they next join.");
            // No scoreboard change needed here as they are offline.
            // Their scoreboard will be handled by onPlayerJoin when they connect (it will be wiped).
        }
    }

    /**
     * Handles the "list" sub-command, listing all players currently in stealth mode.
     * @param sender The command sender.
     */
    private void handleListCommand(CommandSender sender) {
        if (stealthPlayers.isEmpty()) {
            sender.sendMessage("§eNo players are currently in stealth mode.");
            return;
        }

        sender.sendMessage("§ePlayers in stealth mode:");
        for (UUID uuid : stealthPlayers) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
            String playerName = (offlinePlayer != null && offlinePlayer.getName() != null) ? offlinePlayer.getName() : uuid.toString();
            sender.sendMessage("§7- " + playerName + " (" + (offlinePlayer.isOnline() ? "Online" : "Offline") + ")");
        }
    }
}
