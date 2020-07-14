package com.github.panapeepo.api;

import com.github.derrop.simplecommand.map.CommandMap;
import com.github.panapeepo.api.config.PanapeepoConfig;
import com.github.panapeepo.api.event.EventManager;
import com.github.panapeepo.api.plugin.PluginManager;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageChannel;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.sharding.ShardManager;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

public interface Panapeepo {

    @NotNull
    EventManager getEventManager();

    @NotNull
    PluginManager getPluginManager();

    @NotNull
    ShardManager getShardManager();

    @NotNull
    CommandMap getConsoleCommandMap();

    @NotNull
    CommandMap getDiscordCommandMap();

    @NotNull
    PanapeepoConfig getConfig();

    void shutdown();

    @NotNull
    String getCurrentVersion();

    @NotNull
    String getCurrentCommit();

    @NotNull
    EmbedBuilder createDefaultEmbed(User user);

    double getMaxMemory();

    double getUsedMemory();

    double getFreeMemory();

    long getStartupTime();

    @NotNull
    String formatMillis(long millis);

    void sendDefaultEmbed(@NotNull MessageChannel channel, @NotNull User user, @NotNull Consumer<EmbedBuilder> consumer);

}
