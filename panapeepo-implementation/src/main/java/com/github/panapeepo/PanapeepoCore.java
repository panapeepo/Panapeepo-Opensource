package com.github.panapeepo;

import com.github.derrop.documents.Documents;
import com.github.derrop.simplecommand.map.CommandMap;
import com.github.derrop.simplecommand.map.DefaultCommandMap;
import com.github.panapeepo.api.Panapeepo;
import com.github.panapeepo.api.config.ActivityConfig;
import com.github.panapeepo.api.config.PanapeepoConfig;
import com.github.panapeepo.api.database.DatabaseDriver;
import com.github.panapeepo.api.event.EventManager;
import com.github.panapeepo.api.plugin.PluginManager;
import com.github.panapeepo.api.service.ServiceRegistry;
import com.github.panapeepo.api.util.MessageUtils;
import com.github.panapeepo.command.CommandListener;
import com.github.panapeepo.command.discord.HelpCommand;
import com.github.panapeepo.command.discord.InfoCommand;
import com.github.panapeepo.command.discord.PluginsCommand;
import com.github.panapeepo.config.DefaultPanapeepoConfig;
import com.github.panapeepo.database.H2DatabaseDriver;
import com.github.panapeepo.event.DefaultEventManager;
import com.github.panapeepo.plugin.DefaultPluginManager;
import com.github.panapeepo.service.BasicServiceRegistry;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.MessageChannel;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.sharding.DefaultShardManagerBuilder;
import net.dv8tion.jda.api.sharding.ShardManager;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nonnull;
import javax.security.auth.login.LoginException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class PanapeepoCore implements Panapeepo {

    private final long startupTime;
    private final ScheduledExecutorService timer = Executors.newScheduledThreadPool(4);
    private final Random random = new Random();
    private final ShardManager shardManager;

    private final EventManager eventManager = new DefaultEventManager();
    private final PluginManager pluginManager = new DefaultPluginManager(this);

    private final CommandMap consoleCommandMap = new DefaultCommandMap();
    private final CommandMap discordCommandMap = new DefaultCommandMap();

    private final ServiceRegistry serviceRegistry = new BasicServiceRegistry();
    private final PanapeepoConfig config;

    public static void main(String[] args) throws IOException {
        var configPath = Paths.get("config.yml");
        if (!Files.exists(configPath)) {
            try (var inputStream = PanapeepoCore.class.getClassLoader().getResourceAsStream("config.default.yml");
                 var outputStream = Files.newOutputStream(configPath)) {
                Objects.requireNonNull(inputStream).transferTo(outputStream);
            }
            System.out.println("Config created. Edit the configuration file and start the bot again!");
            System.exit(-1);
        }

        var config = Documents.yamlStorage().read(configPath).toInstanceOf(DefaultPanapeepoConfig.class);
        if (config.getToken().isEmpty()) {
            System.out.println("Set the bot token in the config and start the bot again!");
            System.exit(-1);
        }

        try {
            new PanapeepoCore(config, args);
        } catch (LoginException exception) {
            exception.printStackTrace();
        }
    }

    PanapeepoCore(@Nonnull PanapeepoConfig config, @Nonnull String[] args) throws LoginException, IOException {
        this.startupTime = System.currentTimeMillis();
        System.out.println(String.format("Starting Panapeepo version %s (%s)", this.getCurrentVersion(), this.getCurrentCommit()));
        var pluginsPath = Paths.get("plugins");
        if (!Files.exists(pluginsPath)) {
            Files.createDirectory(pluginsPath);
        }

        this.config = config;
        this.serviceRegistry.setProvider(DatabaseDriver.class, new H2DatabaseDriver(), false, true);
        this.consoleCommandMap.registerDefaultHelpCommand();
        this.pluginManager.loadPlugins(pluginsPath);

        var intents = new ArrayList<>(Arrays.asList(GatewayIntent.GUILD_MESSAGES, GatewayIntent.GUILD_MESSAGE_REACTIONS));
        this.pluginManager.getPlugins().forEach(pluginContainer -> {
            intents.addAll(pluginContainer.getIntents());
        });

        DefaultShardManagerBuilder builder = DefaultShardManagerBuilder.create(config.getToken(), intents);
        builder.setAutoReconnect(true);
        builder.setStatus(OnlineStatus.ONLINE);

        this.discordCommandMap.registerSubCommands(new HelpCommand(this));
        this.discordCommandMap.registerSubCommands(new InfoCommand(this));
        this.discordCommandMap.registerSubCommands(new PluginsCommand(this));

        builder.addEventListeners(new CommandListener(this));

        this.shardManager = builder.build();

        for (int i = 0; i < config.getMaxShards(); i++) {
            this.shardManager.start(i);
        }

        this.startRPCTimer(config.getActivities());

        this.pluginManager.enablePlugins();
        this.serviceRegistry.getProviderUnchecked(DatabaseDriver.class).connect();
        System.out.println(String.format(
                "Started Panapeepo (Took %.2fs)! Enabled %s plugin(s) and started %s shard(s)",
                (System.currentTimeMillis() - this.startupTime) / 1000D,
                this.pluginManager.getPlugins().size(),
                this.shardManager.getShardsTotal()
        ));
    }

    private void startRPCTimer(ActivityConfig config) {
        var activities = config.getActivities();

        this.timer.scheduleAtFixedRate(() -> {
            var activity = activities.get(this.random.nextInt(activities.size()));
            if (activity.getText() == null || activity.getType() == null) {
                return;
            }

            for (var shard : shardManager.getShards()) {
                if (shard != null) {
                    shard.getPresence().setActivity(Activity.of(activity.getType(), activity.getText() + " • #" + shard.getShardInfo().getShardId()));
                }
            }
        }, 0, config.getUpdateInterval(), TimeUnit.SECONDS);
    }

    @Override
    public @NotNull EventManager getEventManager() {
        return this.eventManager;
    }

    @Override
    public @NotNull PluginManager getPluginManager() {
        return this.pluginManager;
    }

    @Override
    public @NotNull ShardManager getShardManager() {
        return this.shardManager;
    }

    @Override
    public @NotNull CommandMap getConsoleCommandMap() {
        return this.consoleCommandMap;
    }

    @Override
    public @NotNull CommandMap getDiscordCommandMap() {
        return this.discordCommandMap;
    }

    @Override
    public @NotNull PanapeepoConfig getConfig() {
        return this.config;
    }

    @Override
    public @NotNull DatabaseDriver getDatabase() {
        return this.serviceRegistry.getProviderUnchecked(DatabaseDriver.class);
    }

    @Override
    public @NotNull ServiceRegistry getServiceRegistry() {
        return this.serviceRegistry;
    }

    @Override
    public void shutdown() {
        this.pluginManager.disablePlugins();
        System.exit(0);
    }

    @Override
    public @NotNull String getCurrentCommit() {
        var version = PanapeepoCore.class.getPackage().getSpecificationVersion();
        if (version == null) {
            return "unknown";
        }
        return version;
    }

    @Override
    public @NotNull EmbedBuilder createDefaultEmbed(User user) {
        var embed = new EmbedBuilder();

        MessageUtils.setDefaultFooter(this, user, embed);
        embed.setTimestamp(Instant.now());

        return embed;
    }

    @Override
    public @NotNull String getCurrentVersion() {
        var version = PanapeepoCore.class.getPackage().getImplementationVersion();
        if (version == null) {
            return "unknown";
        }
        return version;
    }

    @Override
    public double getMaxMemory() {
        return (double) Runtime.getRuntime().totalMemory() / ((double) 1024 * 1024);
    }

    @Override
    public double getUsedMemory() {
        return getMaxMemory() - getFreeMemory();
    }

    @Override
    public double getFreeMemory() {
        return (double) Runtime.getRuntime().freeMemory() / ((double) 1024 * 1024);
    }

    @Override
    public long getStartupTime() {
        return this.startupTime;
    }

    @Override
    public @NotNull String formatMillis(long millis) {
        var seconds = (millis / 1000) % 60;
        var minutes = (millis / (1000 * 60)) % 60;
        var hours = (millis / (1000 * 60 * 60)) % 24;

        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    @Override
    public void sendDefaultEmbed(@NotNull MessageChannel channel, @NotNull User user, @NotNull Consumer<EmbedBuilder> consumer) {
        var embed = this.createDefaultEmbed(user);
        consumer.accept(embed);
        channel.sendMessage(embed.build()).queue(message -> message.delete().queueAfter(2, TimeUnit.MINUTES));
    }
}
