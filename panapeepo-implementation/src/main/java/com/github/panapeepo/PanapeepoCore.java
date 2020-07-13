package com.github.panapeepo;

import com.github.derrop.documents.Documents;
import com.github.derrop.simplecommand.map.CommandMap;
import com.github.derrop.simplecommand.map.DefaultCommandMap;
import com.github.panapeepo.api.Panapeepo;
import com.github.panapeepo.api.config.ActivityConfig;
import com.github.panapeepo.api.config.ConfigPresence;
import com.github.panapeepo.api.config.PanapeepoConfig;
import com.github.panapeepo.api.event.EventManager;
import com.github.panapeepo.api.plugin.PluginManager;
import com.github.panapeepo.command.CommandListener;
import com.github.panapeepo.config.DefaultPanapeepoConfig;
import com.github.panapeepo.event.DefaultEventManager;
import com.github.panapeepo.plugin.DefaultPluginManager;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.sharding.DefaultShardManagerBuilder;
import net.dv8tion.jda.api.sharding.ShardManager;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nonnull;
import javax.security.auth.login.LoginException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class PanapeepoCore implements Panapeepo {

    private final ScheduledExecutorService timer = Executors.newScheduledThreadPool(4);
    private final Random random = new Random();
    private final ShardManager shardManager;

    private final EventManager eventManager = new DefaultEventManager();
    private final PluginManager pluginManager = new DefaultPluginManager(this);

    private final CommandMap consoleCommandMap = new DefaultCommandMap();
    private final CommandMap discordCommandMap = new DefaultCommandMap();

    private final PanapeepoConfig config;

    public static void main(String[] args) throws IOException {
        Path configPath = Paths.get("config.yml");
        if (!Files.exists(configPath)) {
            try (InputStream inputStream = PanapeepoCore.class.getClassLoader().getResourceAsStream("config.default.yml");
                 OutputStream outputStream = Files.newOutputStream(configPath)) {
                Objects.requireNonNull(inputStream).transferTo(outputStream);
            }

            System.exit(-1);
        }

        PanapeepoConfig config = Documents.yamlStorage().read(configPath).toInstanceOf(DefaultPanapeepoConfig.class);
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

    PanapeepoCore(@Nonnull PanapeepoConfig config, @Nonnull String[] args) throws LoginException {
        this.config = config;

        this.consoleCommandMap.registerDefaultHelpCommand();

        DefaultShardManagerBuilder builder = DefaultShardManagerBuilder.create(config.getToken(), Arrays.asList(GatewayIntent.values()));
        builder.setAutoReconnect(true);

        builder.addEventListeners(new CommandListener(this));

        this.shardManager = builder.build();

        for (int i = 0; i < config.getMaxShards(); i++) {
            this.shardManager.start(i);
        }

        this.startRPCTimer(config.getActivities());

        this.pluginManager.loadPlugins(Paths.get("plugins"));
        this.pluginManager.enablePlugins();
    }

    private void startRPCTimer(ActivityConfig config) {
        List<ConfigPresence> activities = config.getActivities();

        this.timer.scheduleAtFixedRate(() -> {
            ConfigPresence activity = activities.get(this.random.nextInt(activities.size()));
            if (activity.getText() == null || activity.getType() == null) {
                return;
            }

            for (JDA shard : shardManager.getShards()) {
                if (shard != null) {
                    shard.getPresence().setActivity(Activity.of(activity.getType(), activity.getText() + " | #" + shard.getShardInfo().getShardId()));
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
    public void shutdown() {
        this.pluginManager.disablePlugins();
        System.exit(0);
    }
}