package net.lmoriarty.scanner

import sx.blah.discord.api.ClientBuilder
import sx.blah.discord.api.IDiscordClient
import sx.blah.discord.api.events.IListener
import sx.blah.discord.handle.impl.events.ReadyEvent
import sx.blah.discord.handle.impl.events.guild.channel.message.MessageReceivedEvent
import sx.blah.discord.handle.obj.*
import sx.blah.discord.util.RateLimitException
import java.util.*
import java.util.concurrent.*

/**
 * Handles RateLimitException and retries if encounters. Blocks the thread.
 */
fun <T> makeRequest(action: () -> T): T {
    while (true) {
        try {
            return action()
        } catch (e: RateLimitException) {
            Thread.sleep(e.retryDelay)
        }
    }
}



class ChatBot {
    val watcher: Watcher
    val client: IDiscordClient

    private val notificationTargets = ConcurrentHashMap<IChannel, NotificationTarget>()
    private var owner: IUser? = null

    init {
        val internalThreadFactory = Executors.defaultThreadFactory()
        val listenerThreadFactory = ThreadFactory {
            val thread = internalThreadFactory.newThread(it)
            thread.name = "Handler Thread"
            thread.isDaemon = false
            return@ThreadFactory thread
        }

        val requestThreadFactory = ThreadFactory {
            val thread = internalThreadFactory.newThread(it)
            thread.name = "Request Thread"
            thread.isDaemon = false
            return@ThreadFactory thread
        }

        // we're going to be blocking in our handlers, so to avoid thread starving
        // we use an unbounded thread pool
        val executor = ThreadPoolExecutor(8, Int.MAX_VALUE, 60, TimeUnit.SECONDS, LinkedBlockingQueue(), listenerThreadFactory)

        val clientBuilder = ClientBuilder()
        clientBuilder.withToken(Settings.token)
        client = clientBuilder.login()

        client.dispatcher.registerListener(executor, IListener<ReadyEvent> {
            handleBotLoad(it)
        })
        client.dispatcher.registerListener(executor, IListener<MessageReceivedEvent> {
            handleMessage(it)
        })

        watcher = Watcher(this)
    }

    private fun commitSettings() {
        Settings.channels.clear()
        for ((channel, target) in notificationTargets) {
            val channelSettings = Settings.ChannelSettings()
            channelSettings.types = HashSet(target.types)
            Settings.channels[channel.longID] = channelSettings
        }

        val owner = this.owner
        if (owner != null) {
            Settings.owner = owner.longID
        }

        Settings.writeSettings()
    }

    private fun retrieveSettings() {
        Settings.readSettings()
        notificationTargets.clear()
        owner = null

        for ((id, notificationChannel) in Settings.channels) {
            val channel = client.getChannelByID(id)
            val target = NotificationTarget(channel, this, HashSet(notificationChannel.types))
            notificationTargets[channel] = target
         }

        if (Settings.owner != 0L) {
            val user = client.getUserByID(Settings.owner)
            owner = user
            log.info("Retrieved owner (${user.name}).")
        }
    }

    private fun isNotifiableChannel(channel: IChannel): Boolean {
        return notificationTargets.containsKey(channel)
    }

    private fun canUserManage(user: IUser, guild: IGuild): Boolean {
        if (user == owner) {
            return true
        }

        val permissions = user.getPermissionsForGuild(guild)
        return permissions.contains(Permissions.ADMINISTRATOR) || permissions.contains(Permissions.MANAGE_SERVER)
    }

    private fun commandListGameTypes(message: IMessage, arg: String) {
        val channel = message.channel
        val user = message.author
        val guild = message.guild

        if (canUserManage(user, guild) && isNotifiableChannel(channel)) {
            var response = "Here's the supported game types, Dave:\n```"

            for (gameType in GameType.values()) {
                response += "${gameType} -> ${gameType.regex}\n"
            }

            response += "```"

            makeRequest { message.channel.sendMessage(response) }
        }
    }

    private fun commandRegisterChannel(message: IMessage, arg: String) {
        val user = message.author
        val guild = message.guild
        val channel = message.channel
        val typesStr = arg.split(" ")

        if (canUserManage(user, guild)) {
            val types = HashSet<GameType>()

            if (arg == "all") {
                types.addAll(GameType.values())
            } else {
                for (typeStr in typesStr) {
                    try {
                        val type = GameType.valueOf(typeStr.toUpperCase())
                        types.add(type)
                    } catch (e: IllegalArgumentException) {
                        makeRequest { channel.sendMessage("'$typeStr' is not a real game type, Dave.") }
                    }
                }
            }

            var target = notificationTargets[channel]
            if (target == null) {
                target = NotificationTarget(channel, this, HashSet())
                notificationTargets[channel] = target
            }

            target.addGameTypes(types)

            makeRequest { channel.sendMessage("Channel registered for '$types' notifications, Dave.") }
            commitSettings()
        }
    }

    private fun commandUnregisterChannel(message: IMessage, arg: String) {
        val user = message.author
        val guild = message.guild
        val channel = message.channel
        val typesStr = arg.split(" ")

        if (canUserManage(user, guild)) {
            if (isNotifiableChannel(channel)) {
                val types = HashSet<GameType>()

                if (arg == "all") {
                    types.addAll(GameType.values())
                } else {
                    for (typeStr in typesStr) {
                        try {
                            val type = GameType.valueOf(typeStr.toUpperCase())
                            types.add(type)
                        } catch (e: IllegalArgumentException) {
                            makeRequest { channel.sendMessage("'$typeStr' is not a real game type, Dave.") }
                        }
                    }
                }

                val target = notificationTargets[channel] as NotificationTarget

                target.removeGameTypes(types)

                if (target.isEmpty()) {
                    notificationTargets.remove(channel)
                    target.shutdown()
                }

                makeRequest { channel.sendMessage("Channel unregistered for '$types' notifications, Dave.") }
                commitSettings()
            }
        }
    }

    private fun commandClearMessages(message: IMessage, arg: String) {
        val user = message.author
        val guild = message.guild
        val channel = message.channel

        if (canUserManage(user, guild)) {
            val notificationTarget = notificationTargets[channel]

            if (notificationTarget != null) {
                notificationTarget.clearUntrackedMessages()

                makeRequest { channel.sendMessage("I've cleared all my messages, Dave.") }
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
            val arg = if (split.size > 2) split.subList(2, split.size).reduce { acc, s -> acc + " " + s } else ""

            when (command) {
                "register" -> commandRegisterChannel(message, arg)
                "unregister" -> commandUnregisterChannel(message, arg)
                "clear" -> commandClearMessages(message, arg)
                "list" -> commandListGameTypes(message, arg)
            }
        }
    }

    fun onGameHosted(gameInfo: GameInfo) {
        log.info("A game has been hosted: ${gameInfo.name} (${gameInfo.playerCount})")
        for ((_, target) in notificationTargets) {
            target.processGameUpdate(gameInfo)
        }
    }

    fun onGameUpdated(gameInfo: GameInfo) {
        log.info("A game has been updated: ${gameInfo.oldName} (${gameInfo.oldPlayerCount}) -> (${gameInfo.playerCount}) ${gameInfo.name}")
        for ((_, target) in notificationTargets) {
            target.processGameUpdate(gameInfo)
        }
    }

    fun onGameRemoved(gameInfo: GameInfo) {
        log.info("A game has been removed: ${gameInfo.name} (${gameInfo.playerCount})")
        for ((_, target) in notificationTargets) {
            target.processGameRemove(gameInfo)
        }
    }

    private fun handleBotLoad(event: ReadyEvent) {
        retrieveSettings()

        watcher.start()
        log.info("Watcher started.")
    }

    private fun handleMessage(event: MessageReceivedEvent) {
        dispatchCommand(event.message)
    }
}