/*
 * DiscordSRV - A Minecraft to Discord and back link plugin
 * Copyright (C) 2016-2020 Austin "Scarsz" Shapiro
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package github.scarsz.discordsrv.hooks.chat;

import br.net.fabiozumbi12.UltimateChat.Bukkit.API.SendChannelMessageEvent;
import br.net.fabiozumbi12.UltimateChat.Bukkit.UCChannel;
import br.net.fabiozumbi12.UltimateChat.Bukkit.UChat;
import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.util.LangUtil;
import github.scarsz.discordsrv.util.MessageUtil;
import github.scarsz.discordsrv.util.PlayerUtil;
import github.scarsz.discordsrv.util.PluginUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.apache.commons.lang3.StringUtils;
import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

public class UltimateChatHook implements ChatHook {

    private Constructor<?> ultimateFancyConstructor;
    private Method sendMessageMethod;

    public UltimateChatHook() {}

    @Override
    public void hook() {
        Class<?> ultimateFancyClass;
        try {
            ultimateFancyClass = Class.forName("br.net.fabiozumbi12.UltimateChat.Bukkit.UltimateFancy");
        } catch (ClassNotFoundException ignored) {
            try {
                ultimateFancyClass = Class.forName("br.net.fabiozumbi12.UltimateChat.Bukkit.util.UltimateFancy");
            } catch (ClassNotFoundException ignoreThis) {
                try {
                    ultimateFancyClass = Class.forName("br.net.fabiozumbi12.UltimateFancy.UltimateFancy");
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException("No UltimateFancy class found to use for UltimateChat hook", e);
                }
            }
        }

        try {
            if (Arrays.stream(ultimateFancyClass.getConstructors())
                    .anyMatch(constructor -> constructor.getParameterCount() == 0)) {
                ultimateFancyConstructor = ultimateFancyClass.getDeclaredConstructor();
            } else {
                ultimateFancyConstructor = ultimateFancyClass.getDeclaredConstructor(JavaPlugin.class);
            }
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Failed to find UltimateFancy constructor: " + e.getMessage(), e);
        }

        try {
            sendMessageMethod = Class.forName("br.net.fabiozumbi12.UltimateChat.Bukkit.UCChannel").getMethod("sendMessage", ConsoleCommandSender.class, ultimateFancyClass, boolean.class);
        } catch (NoSuchMethodException | ClassNotFoundException e) {
            throw new RuntimeException("Failed to get sendMessage method of UCChannel in UltimateChat hook", e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMessage(SendChannelMessageEvent event) {
        // make sure chat channel is registered with a destination
        if (event.getChannel() == null || DiscordSRV.getPlugin().getDestinationTextChannelForGameChannelName(event.getChannel().getName()) == null) return;

        // make sure message isn't just blank
        if (StringUtils.isBlank(event.getMessage())) return;

        Player sender = null;
        if (event.getSender() instanceof Player) sender = (Player) event.getSender();

        DiscordSRV.getPlugin().processChatMessage(sender, event.getMessage(), event.getChannel().getName(), false);
    }

    @Override
    public void broadcastMessageToChannel(String channel, Component message) {
        UCChannel chatChannel = getChannelByCaseInsensitiveName(channel);
        if (chatChannel == null) return; // no suitable channel found

        Component plainMessage = MessageUtil.toComponent(
                LangUtil.Message.CHAT_CHANNEL_MESSAGE.toString()
                        .replace("%channelcolor%", chatChannel.getColor())
                        .replace("%channelname%", chatChannel.getName())
                        .replace("%channelnickname%", chatChannel.getAlias())
        );
        plainMessage.replaceText(MessageUtil.MESSAGE_PLACEHOLDER, builder ->
                message.append(builder.content(builder.content().replaceFirst("%message%", ""))));

        Object ultimateFancy;
        try {
            if (ultimateFancyConstructor.getParameterCount() == 1 && ultimateFancyConstructor.getParameterTypes()[0] == String.class) {
                // older UltimateFancy version
                ultimateFancy = ultimateFancyConstructor.newInstance();
            } else {
                ultimateFancy = ultimateFancyConstructor.newInstance(DiscordSRV.getPlugin());
            }
        } catch (IllegalAccessException | InstantiationException | InvocationTargetException e) {
            DiscordSRV.debug("Failed to initialize UltimateFancy in UltimateChat hook: " + e.getMessage());
            return;
        }

        try {
            // despite the name, this is where json is added
            Method appendStringMethod = ultimateFancy.getClass().getDeclaredMethod("appendString", String.class);

            appendStringMethod.invoke(ultimateFancy, GsonComponentSerializer.gson().serialize(message));
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            DiscordSRV.debug("Failed to add JSON to UltimateChat UltimateFancy class " + e.getMessage());
            return;
        }

        try {
            sendMessageMethod.invoke(chatChannel, Bukkit.getServer().getConsoleSender(), ultimateFancy, true);
        } catch (IllegalAccessException | InvocationTargetException e) {
            DiscordSRV.debug("Failed to invoke sendMessage on UCChannel in UltimateChat hook: " + e.getMessage());
            return;
        }

        PlayerUtil.notifyPlayersOfMentions(player -> chatChannel.getMembers().contains(player.getName()), MessageUtil.toPlain(message, true));
    }

    private static UCChannel getChannelByCaseInsensitiveName(String name) {
        for (UCChannel channel : UChat.get().getAPI().getChannels())
            if (channel.getName().equalsIgnoreCase(name)) return channel;
        return null;
    }

    @Override
    public Plugin getPlugin() {
        return PluginUtil.getPlugin("UltimateChat");
    }

}
