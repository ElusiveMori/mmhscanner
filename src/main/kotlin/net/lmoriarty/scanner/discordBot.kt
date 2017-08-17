package net.lmoriarty.scanner

import sx.blah.discord.api.ClientBuilder
import sx.blah.discord.api.IDiscordClient
import sx.blah.discord.api.events.EventSubscriber
import sx.blah.discord.handle.impl.events.guild.channel.message.MessageReceivedEvent
import sx.blah.discord.handle.obj.IGuild
import sx.blah.discord.handle.obj.IMessage
import sx.blah.discord.handle.obj.IUser
import sx.blah.discord.handle.obj.Permissions

class ChatBot {
    private var client: IDiscordClient? = null

    init {
        val builder = ClientBuilder()
        builder.withToken(Settings.token)
        builder.registerListener(this)
        client = builder.login()
    }

    private fun canUserManage(user: IUser, guild: IGuild): Boolean {
        return user.getPermissionsForGuild(guild).contains(Permissions.ADMINISTRATOR)
    }

    private fun registerChannel(message: IMessage) {
        val user = message.author
        val guild = message.guild

        if (canUserManage(user, guild)) {
            if (!Settings.channels.contains(message.channel.stringID)) {
                Settings.channels.add(message.channel.stringID)
                Settings.writeSettings()
                message.channel.sendMessage("Channel registered for notifications.")
            } else {
                message.channel.sendMessage("I can't do that, Dave.")
            }
        }
    }

    private fun unregisterChannel(message: IMessage) {
        val user = message.author
        val guild = message.guild

        if (canUserManage(user, guild)) {
            if (Settings.channels.contains(message.channel.stringID)) {
                Settings.channels.remove(message.channel.stringID)
                Settings.writeSettings()
                message.channel.sendMessage("Channel unregistered for notifications.")
            } else {
                message.channel.sendMessage("I can't do that, Dave.")
            }
        }
    }

    private fun dispatchCommand(message: IMessage) {
        val input = message.content
        val split = input.split(" ")

        // nothing to see here
        if (split.size < 2) return

        if (split[0] == "-mmh") {
            val command = split[1]
            //val arg = if (split.size > 2) split.subList(2, split.size).reduce { acc, s -> acc + " " + s } else ""

            when (command) {
                "register" -> registerChannel(message)
                "unregister" -> unregisterChannel(message)
            }
        }
    }

    @EventSubscriber
    private fun handleMessage(event: MessageReceivedEvent) {
        dispatchCommand(event.message)
    }
}