package io.github.angelsl.tgmcauth;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.Chat;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.request.ParseMode;
import com.pengrad.telegrambot.request.GetMe;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.GetMeResponse;
import fr.xephi.authme.api.v3.AuthMeApi;
import fr.xephi.authme.events.AuthMeAsyncPreLoginEvent;
import fr.xephi.authme.events.AuthMeAsyncPreRegisterEvent;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.node.types.MetaNode;
import net.luckperms.api.platform.PlayerAdapter;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URLEncoder;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public class TelegramMinecraftAuth extends JavaPlugin implements Listener, UpdatesListener {
    private static final String META_TELEGRAM_ID = "telegram-id";

    private String botUsername;
    private TelegramBot bot;

    private LuckPerms luckPerms;
    private PlayerAdapter<Player> lpAdapter;
    private FileConfiguration config;

    private String generateDeepLink(String playerName) {
        try {
            return String.format("https://t.me/%s?start=%s", botUsername, URLEncoder.encode(playerName, "UTF-8"));
        } catch (Throwable t) {
            // should not happen...
            throw new RuntimeException(t);
        }
    }

    private void nagPlayer(Player player, boolean isRegistered) {
        player.sendMessage(String.format(
                ChatColor.RED + "Welcome! Before %s, please verify you are human by linking your Telegram account.",
                isRegistered ? "logging in" : "registering"));

        {
            String deepLinkUrl = generateDeepLink(player.getName());
            TextComponent deepLink = new TextComponent(deepLinkUrl + " (click to open)");
            deepLink.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, deepLinkUrl));
            deepLink.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("Click to open")));
            player.spigot().sendMessage(
                    new ComponentBuilder().append("Open ").color(net.md_5.bungee.api.ChatColor.RED).append(deepLink)
                            .underlined(true).color(net.md_5.bungee.api.ChatColor.WHITE)
                            .append(", and then click Start in the Telegram bot chat window.").reset().underlined(false)
                            .color(net.md_5.bungee.api.ChatColor.RED).create());
        }

        {
            TextComponent verifyCommand =
                    new TextComponent(String.format("\"/verify %s\" (click to copy)", player.getName()));
            verifyCommand.setClickEvent(
                    new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, String.format("/verify %s", player.getName())));
            verifyCommand.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("Click to copy")));
            player.spigot().sendMessage(
                    new ComponentBuilder().append("Alternatively, send ").color(net.md_5.bungee.api.ChatColor.RED)
                            .append(verifyCommand).underlined(true).color(net.md_5.bungee.api.ChatColor.WHITE)
                            .append(" to ").reset().color(net.md_5.bungee.api.ChatColor.RED).underlined(false)
                            .append("@").color(net.md_5.bungee.api.ChatColor.WHITE).append(botUsername)
                            .color(net.md_5.bungee.api.ChatColor.WHITE).append(" on Telegram.")
                            .color(net.md_5.bungee.api.ChatColor.RED).create());
        }
    }

    @Override
    public void onEnable() {
        reloadConfig();
        config = getConfig();
        config.addDefault("bot_api_key", "please put your Telegram bot API key here.");
        config.options().copyDefaults(true);
        saveConfig();

        RegisteredServiceProvider<LuckPerms> provider = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        if (provider != null) {
            luckPerms = provider.getProvider();
            lpAdapter = luckPerms.getPlayerAdapter(Player.class);
        } else {
            getLogger().log(Level.SEVERE, "Could not access LuckPerms");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        String botToken = config.getString("bot_api_key");
        boolean ok = true;
        try {
            bot = new TelegramBot(botToken);
            GetMeResponse resp = bot.execute(new GetMe());
            if (!resp.isOk()) {
                getLogger().log(Level.SEVERE, "Error while testing bot API key; is it correct?", resp);
                ok = false;
            } else {
                botUsername = resp.user().username();
            }
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error while testing bot API key; is it correct?", e);
            ok = false;
        }

        if (!ok) {
            bot = null;
            botUsername = null;
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        bot.setUpdatesListener(this);

        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        getLogger().info("Disabling...");
        if (bot != null) {
            bot.removeGetUpdatesListener();
            bot = null;
        }
        getLogger().info("Disabled.");
    }

    @Override
    public int process(List<Update> updates) {
        for (Update update : updates) {
            Message m = update.message();
            if (m == null || m.text() == null || m.text().isEmpty() || m.chat().type() != Chat.Type.Private) {
                continue;
            }

            Integer tgId = m.from().id();
            String[] args = m.text().trim().split("\\s+");
            if (args.length != 2 || (!args[0].equals("/verify") && !args[0].equals("/start"))) {
                bot.execute(new SendMessage(tgId, "Syntax: /verify <username>"));
                continue;
            }

            final String tgToMc = "tg_to_mc";
            ConfigurationSection section = config.getConfigurationSection(tgToMc);
            if (section == null) {
                section = config.createSection(tgToMc, Collections.emptyMap());
            }
            final String tgIdStr = tgId.toString();
            final String oldMcUuid = section.getString(tgIdStr);
            if (oldMcUuid != null) {
                OfflinePlayer player = Bukkit.getOfflinePlayer(UUID.fromString(oldMcUuid));
                final String message = player.getName() == null ?
                        "This Telegram account is already associated with a Minecraft user." :
                        String.format("This Telegram account is already associated with Minecraft user %s.",
                                player.getName());
                bot.execute(new SendMessage(tgId, message));
                continue;
            }

            Player onlinePlayer = Bukkit.getPlayerExact(args[1]);
            if (onlinePlayer == null) {
                bot.execute(new SendMessage(tgId,
                        "Minecraft user not found. Note: you need to remain online to verify your Minecraft user."));
                continue;
            }

            section.set(tgIdStr, onlinePlayer.getUniqueId().toString());
            saveConfig();
            luckPerms.getUserManager().modifyUser(onlinePlayer.getUniqueId(), user -> {
                user.data().add(MetaNode.builder(META_TELEGRAM_ID, tgIdStr).build());
            });

            boolean isRegistered = AuthMeApi.getInstance().isRegistered(onlinePlayer.getName());

            bot.execute(new SendMessage(tgId, "Successful. Thank you! " +
                    (isRegistered ? "You may now log in in-game." :
                            "You may now register <strong>in-game</strong> using <code>/register [password] [repeat password]</code>."))
                    .parseMode(ParseMode.HTML));
            onlinePlayer.sendMessage(ChatColor.YELLOW + "Telegram verification successful.");
        }
        return CONFIRMED_UPDATES_ALL;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    private void onJoin(PlayerJoinEvent event) {
        if (!verified(event.getPlayer())) {
            boolean isRegistered = AuthMeApi.getInstance().isRegistered(event.getPlayer().getName());
            nagPlayer(event.getPlayer(), isRegistered);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onPreLogin(AuthMeAsyncPreLoginEvent event) {
        if (!verified(event.getPlayer())) {
            event.setCanLogin(false);
            nagPlayer(event.getPlayer(), true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onPreRegister(AuthMeAsyncPreRegisterEvent event) {
        if (!verified(event.getPlayer())) {
            event.setCanRegister(false);
            nagPlayer(event.getPlayer(), false);
        }
    }

    private boolean verified(Player p) {
        return getPlayerTelegramId(p) != null;
    }

    private String getPlayerTelegramId(Player p) {
        return lpAdapter.getUser(p).getCachedData().getMetaData().getMetaValue(META_TELEGRAM_ID);
    }
}
