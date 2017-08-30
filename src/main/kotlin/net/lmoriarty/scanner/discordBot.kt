package net.lmoriarty.scanner

import sx.blah.discord.api.ClientBuilder
import sx.blah.discord.api.IDiscordClient
import sx.blah.discord.api.events.IListener
import sx.blah.discord.handle.impl.events.ReadyEvent
import sx.blah.discord.handle.impl.events.guild.channel.message.MessageReceivedEvent
import sx.blah.discord.handle.obj.*
import sx.blah.discord.util.PermissionUtils
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

    private val notificationTargets = HashMap<IChannel, NotificationTarget>()
    private val targetExecutor: ExecutorService
    var owner: IUser? = null
        private set

    init {
        val internalThreadFactory = Executors.defaultThreadFactory()
        var handlerCounter = 0
        val listenerThreadFactory = ThreadFactory {
            val thread = internalThreadFactory.newThread(it)
            thread.name = "Handler Thread - ${handlerCounter++}"
            thread.isDaemon = false
            return@ThreadFactory thread
        }

        var requestCounter = 0
        val requestThreadFactory = ThreadFactory {
            val thread = internalThreadFactory.newThread(it)
            thread.name = "Request Thread - ${requestCounter++}"
            thread.isDaemon = false
            return@ThreadFactory thread
        }

        // we're going to be blocking in our handlers, so to avoid thread starving
        // we use an unbounded thread pool
        val executor = ThreadPoolExecutor(8, Int.MAX_VALUE, 60, TimeUnit.SECONDS, LinkedBlockingQueue(), listenerThreadFactory)
        targetExecutor = ThreadPoolExecutor(8, Int.MAX_VALUE, 60, TimeUnit.SECONDS, LinkedBlockingQueue(), requestThreadFactory)

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

    private fun createNotificationTarget(channel: IChannel, types: Set<GameType>): NotificationTarget {
        if (types.isEmpty()) throw IllegalArgumentException("Cannot create notification target with empty set.")

        val target = NotificationTarget(channel, this, types)

        targetExecutor.submit {
            target.clearUntrackedMessages()

            for ((_, info) in watcher.getAll()) {
                target.processGameUpdate(info)
            }

            target.updateInfoMessage()
        }

        notificationTargets[channel] = target
        return target
    }

    private fun addGameTypesToChannel(channel: IChannel, types: Set<GameType>) {
        if (types.isEmpty()) return

        val target = notificationTargets[channel]

        if (target == null) {
            createNotificationTarget(channel, types)
        } else {
            target.addGameTypes(types)

            for ((_, info) in watcher.getAll()) {
                target.processGameUpdate(info)
            }
        }
    }

    private fun removeGameTypesFromChannel(channel: IChannel, types: Set<GameType>) {
        if (types.isEmpty()) return

        val target = notificationTargets[channel]

        if (target != null) {
            for ((_, info) in watcher.getAll()) {
                if (info.type in types) {
                    target.processGameRemove(info)
                }
            }

            target.removeGameTypes(types)

            if (target.isEmpty()) {
                target.shutdown()
                notificationTargets.remove(channel)
            }
        }
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

        if (Settings.owner != 0L) {
            val user = client.getUserByID(Settings.owner)
            owner = user
            log.info("Retrieved owner (${user.name}).")
        }

        for ((id, notificationChannel) in Settings.channels) {
            val channel = client.getChannelByID(id)
            createNotificationTarget(channel, notificationChannel.types)
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

            addGameTypesToChannel(channel, types)

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

                removeGameTypesFromChannel(channel, types)

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

    private fun hasPermissionInChannel(channel: IChannel, permission: Permissions): Boolean {
        return PermissionUtils.hasPermissions(channel, client.ourUser, permission)
    }

    fun canEmbedInChannel(channel: IChannel): Boolean {
        return hasPermissionInChannel(channel, Permissions.EMBED_LINKS)
    }

    fun canUseChannel(channel: IChannel): Boolean {
        return hasPermissionInChannel(channel, Permissions.SEND_MESSAGES) &&
                hasPermissionInChannel(channel, Permissions.READ_MESSAGES)
    }

    fun canDeleteInChannel(channel: IChannel): Boolean {
        return hasPermissionInChannel(channel, Permissions.MANAGE_MESSAGES)
    }

    fun onGameHosted(gameInfo: GameInfo) {
        log.info("A game has been hosted: ${gameInfo.name} (${gameInfo.playerCount})")
        for ((channel, target) in notificationTargets) {
            targetExecutor.submit {
                target.processGameUpdate(gameInfo)
            }
        }
    }

    fun onGameUpdated(gameInfo: GameInfo) {
        log.info("A game has been updated: ${gameInfo.oldName} (${gameInfo.oldPlayerCount}) -> (${gameInfo.playerCount}) ${gameInfo.name}")
        for ((channel, target) in notificationTargets) {
            targetExecutor.submit {
                target.processGameUpdate(gameInfo)
            }
        }
    }

    fun onGameRemoved(gameInfo: GameInfo) {
        log.info("A game has been removed: ${gameInfo.name} (${gameInfo.playerCount})")
        for ((channel, target) in notificationTargets) {
            targetExecutor.submit {
                target.processGameRemove(gameInfo)
            }
        }
    }

    private fun handleBotLoad(event: ReadyEvent) {
        retrieveSettings()

        client.changeUsername("Scanner-chan")
        watcher.start()
        log.info("Watcher started.")
    }

    private fun handleMessage(event: MessageReceivedEvent) {
        dispatchCommand(event.message)

        val notificationTarget = notificationTargets[event.channel]

        if (notificationTarget != null) {
            notificationTarget.renewInfoMessage()
        }
    }
}