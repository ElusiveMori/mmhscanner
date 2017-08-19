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
import kotlin.collections.ArrayList

/**
 * Handles RateLimitException and retries if encounters. Blocks the thread.
 */
fun <T> makeRequest(action: () -> T): T {
    while (true) {
        try {
            return action()
        } catch (e: RateLimitException) {
            Thread.sleep(e.retryDelay + 5)
        }
    }
}

class NotificationTarget(val channel: IChannel,
                         var lastMessage: IMessage?,
                         val types: MutableSet<GameType> = HashSet()) {
}


class ChatBot {
    private var client: IDiscordClient
    private var watcher: Watcher? = null
    private val notificationTargets = HashMap<Long, NotificationTarget>()
    // used for parallel execution of requests
    private val requestExecutor: ThreadPoolExecutor
    private var owner: IUser? = null

    init {
        val internalThreadFactory = Executors.defaultThreadFactory()
        val listenerThreadFactory = ThreadFactory {
            val thread = internalThreadFactory.newThread(it)
            thread.name = "MMH Scanner Handler Thread"
            thread.isDaemon = false
            return@ThreadFactory thread
        }

        val requestThreadFactory = ThreadFactory {
            val thread = internalThreadFactory.newThread(it)
            thread.name = "MMH Scanner Request Thread"
            thread.isDaemon = false
            return@ThreadFactory thread
        }

        // we're going to be blocking in our requests, so to avoid thread starving
        // we use an unbounded thread pool
        val executor = ThreadPoolExecutor(8, Int.MAX_VALUE, 60, TimeUnit.SECONDS, LinkedBlockingQueue(), listenerThreadFactory)
        requestExecutor = ThreadPoolExecutor(16, 16, 0, TimeUnit.SECONDS, LinkedBlockingQueue(), requestThreadFactory)

        val clientBuilder = ClientBuilder()
        clientBuilder.withToken(Settings.token)
        client = clientBuilder.login()

        client.dispatcher.registerListener(executor, IListener<ReadyEvent> {
            handleBotLoad(it)
        })
        client.dispatcher.registerListener(executor, IListener<MessageReceivedEvent> {
            handleMessage(it)
        })
    }

    private fun commitSettings() {
        for ((id, target) in notificationTargets) {
            var channel = Settings.channels[id]

            if (channel == null) {
                channel = Settings.NotificationChannel()
                Settings.channels[id] = channel
            }

            channel.types = HashSet(target.types)
        }
    }

    private fun retrieveSettings() {
        notificationTargets.clear()
        owner = null

        for ((id, notificationChannel) in Settings.channels) {
            val channel = client.getChannelByID(id)

            val target = NotificationTarget(channel, null, HashSet(notificationChannel.types))
            notificationTargets[id] = target
            log.info("Retrieved notification target (${channel.name}, ${target.types}).")
        }

        if (Settings.owner != 0L) {
            val user = client.getUserByID(Settings.owner)
            owner = user
            log.info("Retrieved owner (${user.name}).")
        }
    }

    private fun isNotifiableChannel(channel: IChannel): Boolean {
        return channel.longID in notificationTargets
    }

    private fun canUserManage(user: IUser, guild: IGuild): Boolean {
        if (user == owner) {
            return true
        }

        val permissions = user.getPermissionsForGuild(guild)
        return permissions.contains(Permissions.ADMINISTRATOR) || permissions.contains(Permissions.MANAGE_SERVER)
    }

    private fun listGameTypes(message: IMessage) {
        val user = message.author
        val guild = message.guild
        val channel = message.channel

        if (canUserManage(user, guild)) {
            if (isNotifiableChannel(channel)) {
                var response = "Here's the supported game types, Dave:\n```"

                for (gameType in GameType.values()) {
                    response += "${gameType} -> ${gameType.regex}\n"
                }

                response += "```"

                makeRequest { message.channel.sendMessage(response) }
            }
        }
    }

    private fun registerChannel(message: IMessage) {
        val user = message.author
        val guild = message.guild
        val channel = message.channel

        if (canUserManage(user, guild)) {
            if (!isNotifiableChannel(channel)) {
                notificationTargets[channel.longID] = NotificationTarget(channel, null, HashSet())
                commitSettings()

                makeRequest { channel.sendMessage("Channel registered for notifications, Dave.") }
            } else {
                makeRequest { channel.sendMessage("I can't do that, Dave.") }
            }
        }
    }

    private fun unregisterChannel(message: IMessage) {
        val user = message.author
        val guild = message.guild
        val channel = message.channel

        if (canUserManage(user, guild)) {
            if (isNotifiableChannel(channel)) {
                notificationTargets.remove(channel.longID)
                commitSettings()

                makeRequest { channel.sendMessage("Channel unregistered for notifications, Dave.") }
            } else {
                makeRequest { channel.sendMessage("I can't do that, Dave.") }
            }
        }
    }

    private fun clearMessages(message: IMessage) {
        val user = message.author
        val guild = message.guild
        val channel = message.channel

        if (canUserManage(user, guild)) {
            if (channel.longID in notificationTargets) {
                val toDelete = ArrayList<IMessage>()
                val history = makeRequest { channel.fullMessageHistory }

                log.info("Fetched ${history.size} messages in preparation for deletion.")

                for (historyMessage in history) {
                    if (historyMessage.author == client.ourUser) {
                        toDelete.add(historyMessage)
                    }
                }

                log.info("${toDelete.size} messages will be deleted.")

                // preallocate size just in case
                val futureList = ArrayList<Future<*>>(toDelete.size)
                for (deletableMessage in toDelete) {
                    futureList.add(requestExecutor.submit { makeRequest { deletableMessage.delete() } })
                }

                // wait for all tasks to complete
                for (future in futureList) {
                    future.get()
                }

                log.info("Deleted ${toDelete.size} messages.")

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
            //val arg = if (split.size > 2) split.subList(2, split.size).reduce { acc, s -> acc + " " + s } else ""

            when (command) {
                "register" -> registerChannel(message)
                "unregister" -> unregisterChannel(message)
                "clear" -> clearMessages(message)
                "list" -> listGameTypes(message)
            }
        }
    }

    private fun postInNotificationChannels(text: String) {
        for ((id, target) in notificationTargets) {
            makeRequest { target.channel.sendMessage(text) }
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

    fun onGameRemoved(gameInfo: GameInfo) {
        val text = """
            |```Game started/unhosted: ${gameInfo.name}```
            """.trimMargin()

        postInNotificationChannels(text)
    }

    private fun handleBotLoad(event: ReadyEvent) {
        retrieveSettings()

        watcher = Watcher(this)
        log.info("Watcher started.")
    }

    private fun handleMessage(event: MessageReceivedEvent) {
        dispatchCommand(event.message)
    }
}