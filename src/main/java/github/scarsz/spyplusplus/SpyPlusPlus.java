package github.scarsz.spyplusplus;

import github.scarsz.spyplusplus.customevents.AnvilItemDisplayNameChangeEvent;
import org.apache.commons.lang.StringUtils;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

public class SpyPlusPlus extends JavaPlugin implements Listener {

    private List<UUID> subscribedPlayers = new ArrayList<>();

    public void onEnable() {
        // save default config
        saveDefaultConfig();

        // start metrics
        try {
            if (!getConfig().getBoolean("MetricsDisabled")) new Metrics(this).start();
        } catch (IOException e) {
            e.printStackTrace();
        }

        // register declared event handlers
        Bukkit.getPluginManager().registerEvents(this, this);

        final SpyPlusPlus plugin = this;
        Bukkit.getScheduler().scheduleSyncDelayedTask(this, new Runnable() {
            @Override
            public void run() {
                System.out.println("exec");

                // listen to all events
                RegisteredListener registeredListener = new RegisteredListener(new Listener() {
                    public void onEvent(Event event) {
                        if (getConfig().getBoolean("Debug")) getLogger().info("[DEBUG] Received event " + event.getClass().getSimpleName());

                        for (String eventDirective : getConfig().getStringList("Events")) {
                            if (!eventDirective.split("\\|")[0].equalsIgnoreCase(event.getClass().getSimpleName())) continue;
                            if (getConfig().getBoolean("Debug")) getLogger().info("[DEBUG] Found matching event directive: " + eventDirective);

                            List<String> eventDirectiveSplit = new LinkedList<>(Arrays.asList(eventDirective.split("\\|")));
                            eventDirectiveSplit.remove(0);
                            String directive = StringUtils.join(eventDirectiveSplit, ".");

                            String message = "";
                            String evaluationChain = "";
                            boolean chaining = false;
                            for (char c : directive.toCharArray()) {
                                if (c != '{' && c != '}') {
                                    if (!chaining) message += c;
                                    else evaluationChain += c;
                                } else {
                                    chaining = !chaining;
                                    if (!chaining) {
                                        if (getConfig().getBoolean("Debug")) getLogger().info("[DEBUG] Starting evaluation of chain: " + evaluationChain);
                                        Object current = event;
                                        for (String part : evaluationChain.split("\\.")) {
                                            try {
                                                current = current.getClass().getMethod(part, null).invoke(current, null);
                                            } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                                                e.printStackTrace();
                                            }
                                        }
                                        switch (current.getClass().getSimpleName()) {
                                            case "Class":
                                                //noinspection ConstantConditions
                                                message += ((Class) current).getSimpleName();
                                                break;
                                            case "Double":
                                            case "Long":
                                                message += Integer.valueOf(String.valueOf(current).split("\\.")[0]);
                                                break;
                                            default:
                                                if (!(current instanceof Object[])) message += current.toString();
                                                else message += Arrays.toString((Object[]) current);
                                                break;
                                        }
                                        evaluationChain = "";
                                    }
                                }
                            }

                            message = ChatColor.translateAlternateColorCodes('&', message);
                            if (getConfig().getBoolean("PrintMessagesToConsole")) getLogger().info(ChatColor.stripColor(message));
                            for (Player player : Bukkit.getOnlinePlayers()) if (subscribedPlayers.contains(player.getUniqueId())) player.sendMessage(message);
                        }
                    }
                }, new EventExecutor() {
                    @Override
                    public void execute(Listener listener, Event event) throws EventException {
                        try {
                            listener.getClass().getDeclaredMethod("onEvent", Event.class).invoke(listener, event);
                        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                            e.printStackTrace();
                        }
                    }
                }, EventPriority.MONITOR, plugin, false);
                for (HandlerList handler : HandlerList.getHandlerLists()) handler.register(registeredListener);

                AnvilItemDisplayNameChangeEvent.getHandlerList().unregister(registeredListener);
                AnvilItemDisplayNameChangeEvent.getHandlerList().register(registeredListener);
                PlayerEditBookEvent.getHandlerList().unregister(registeredListener);
                PlayerEditBookEvent.getHandlerList().register(registeredListener);
            }
        }, 100);
    }

    // spyplusplus.onjoin functionality
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (event.getPlayer().hasPermission("spyplusplus.onjoin")) {
            if (!subscribedPlayers.contains(event.getPlayer().getUniqueId())) subscribedPlayers.add(event.getPlayer().getUniqueId());
            event.getPlayer().sendMessage(ChatColor.RED + "You've been automatically subscribed to SpyPlusPlus messages from your " + ChatColor.WHITE + "spyplusplus.onjoin" + ChatColor.RED + " permission.");
        }
    }

    // remove disconnected players from the subscribed players list to save on memory
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (getConfig().getBoolean("RemoveDisconnectedPlayersFromSubscribedPlayers")) subscribedPlayers.remove(event.getPlayer().getUniqueId());
    }

    // /spyplusplus command
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equals("reload") && sender.isOp()) {
            reloadConfig();
            if (sender instanceof Player) sender.sendMessage(ChatColor.RED + "The config has been reloaded");
            else sender.sendMessage("The config has been reloaded");
            return true;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage("[SPP] This command is for players only.");
            return true;
        }
        Player senderPlayer = (Player) sender;

        if (!subscribedPlayers.contains(senderPlayer.getUniqueId())) subscribedPlayers.add(senderPlayer.getUniqueId());
        else subscribedPlayers.remove(senderPlayer.getUniqueId());

        senderPlayer.sendMessage(ChatColor.RED + "You have been " + ChatColor.WHITE + (subscribedPlayers.contains(senderPlayer.getUniqueId()) ? "" : "un") + "subscribed" + ChatColor.RED + " to SpyPlusPlus messages.");

        return true;
    }

    // Anvil custom event
    @EventHandler
    public void onInventoryClickEvent(InventoryClickEvent event) {
        if (event.getInventory().getType() == InventoryType.ANVIL &&
                event.getRawSlot() == event.getView().convertSlot(event.getRawSlot()) &&
                event.getRawSlot() == 2 &&
                ((event.getCurrentItem() != null) && (event.getCurrentItem().getType() != Material.AIR) && (event.getCurrentItem().hasItemMeta())) &&
                event.getCurrentItem().getItemMeta().hasDisplayName()) {
            AnvilItemDisplayNameChangeEvent newEvent = new AnvilItemDisplayNameChangeEvent(Bukkit.getPlayer(event.getWhoClicked().getUniqueId()), event.getCurrentItem().getItemMeta().getDisplayName(), event.getWhoClicked().getLocation(), event.getCurrentItem());
            Bukkit.getPluginManager().callEvent(newEvent);
        }
    }

}
