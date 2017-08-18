package net.lmoriarty.scanner

import sx.blah.discord.api.ClientBuilder
import sx.blah.discord.api.IDiscordClient
import sx.blah.discord.api.events.EventSubscriber
import sx.blah.discord.handle.impl.events.ReadyEvent
import sx.blah.discord.handle.impl.events.guild.channel.message.MessageReceivedEvent
import sx.blah.discord.handle.obj.*

class ChatBot {
    private var client: IDiscordClient? = null
    private val notificationChannels: MutableList<IChannel> = ArrayList()

    init {
        val builder = ClientBuilder()
        builder.withToken(Settings.token)
        builder.registerListener(this)
        client = builder.login()
    }

    private fun canUserManage(user: IUser, guild: IGuild): Boolean {
        val permissions = user.getPermissionsForGuild(guild)
        return permissions.contains(Permissions.ADMINISTRATOR) || permissions.contains(Permissions.MANAGE_SERVER)
    }

    private fun registerChannel(message: IMessage) {
        val user = message.author
        val guild = message.guild

        if (canUserManage(user, guild)) {
            if (!Settings.channels.contains(message.channel.longID)) {
                Settings.channels.add(message.channel.longID)
                Settings.writeSettings()
                message.channel.sendMessage("Channel registered for notifications.")
                notificationChannels.add(message.channel)
            } else {
                message.channel.sendMessage("I can't do that, Dave.")
            }
        }
    }

    private fun unregisterChannel(message: IMessage) {
        val user = message.author
        val guild = message.guild

        if (canUserManage(user, guild)) {
            if (Settings.channels.contains(message.channel.longID)) {
                Settings.channels.remove(message.channel.longID)
                Settings.writeSettings()
                message.channel.sendMessage("Channel unregistered for notifications.")
                notificationChannels.remove(message.channel)
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

    private fun postInNotificationChannels(text: String) {
        for (channel in notificationChannels) {
            channel.sendMessage(text)
        }
    }

    fun onGameHosted(gameInfo: GameInfo) {
        val text = """
            |```Game hosted: ${gameInfo.name}```
            """.trimMargin()

        postInNotificationChannels(text)
    }

    fun onGameUpdated(gameInfo: GameInfo) {
        val text = """
            |```Game renamed: ${gameInfo.oldName} -> ${gameInfo.name}```
            """.trimMargin()

        postInNotificationChannels(text)
    }

    fun onGameUnhosted(gameInfo: GameInfo) {
        val text = """
            |```Game started/unhosted: ${gameInfo.name}```
            """.trimMargin()

        postInNotificationChannels(text)
    }

    @EventSubscriber
    fun handleBotLoad(event: ReadyEvent) {
        for (id in Settings.channels) {
            val channel = client?.getChannelByID(id)

            if (channel != null) {
                notificationChannels.add(channel)
            }
        }

        Watcher(this)
        log.info("Watcher started")
    }

    @EventSubscriber
    fun handleMessage(event: MessageReceivedEvent) {
        dispatchCommand(event.message)
    }
}