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
import kotlin.concurrent.timer

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

val messageHeader = """
|```Type "-mmh list" to see which game types are available!```
""".trimMargin()

class ChatBot {
    val watcher: Watcher
    val client: IDiscordClient

    private val notificationTargets = ConcurrentHashMap<Long, NotificationTarget>()
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

        // we're going to be blocking in our handlers, so to avoid thread starving
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

        watcher = Watcher(this)
    }

    private fun commitSettings() {
        Settings.channels.clear()
        for ((id, target) in notificationTargets) {
            val channel = Settings.NotificationChannel()
            channel.types = HashSet(target.types)
            Settings.channels[id] = channel
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
            notificationTargets[id] = target
         }

        if (Settings.owner != 0L) {
            val user = client.getUserByID(Settings.owner)
            owner = user
            log.info("Retrieved owner (${user.name}).")
        }
    }

    private fun isNotifiableChannel(channel: IChannel): Boolean {
        return notificationTargets.containsKey(channel.longID)
    }

    private fun canUserManage(user: IUser, guild: IGuild): Boolean {
        if (user == owner) {
            return true
        }

        val permissions = user.getPermissionsForGuild(guild)
        return permissions.contains(Permissions.ADMINISTRATOR) || permissions.contains(Permissions.MANAGE_SERVER)
    }

    private fun clearMessagesInChannel(channel: IChannel) {
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
    }

    private fun commandListGameTypes(message: IMessage) {
        val channel = message.channel

        if (isNotifiableChannel(channel)) {
            var response = "Here's the supported game types, Dave:\n```"

            for (gameType in GameType.values()) {
                response += "${gameType} -> ${gameType.regex}\n"
            }

            response += "```"

            makeRequest { message.channel.sendMessage(response) }
        }
    }

    private fun commandRegisterChannel(message: IMessage) {
        val user = message.author
        val guild = message.guild
        val channel = message.channel

        if (canUserManage(user, guild)) {
            if (!isNotifiableChannel(channel)) {
                notificationTargets[channel.longID] = NotificationTarget(channel, this, HashSet())
                commitSettings()

                makeRequest { channel.sendMessage("Channel registered for notifications, Dave.") }
            } else {
                makeRequest { channel.sendMessage("I can't do that, Dave.") }
            }
        }
    }

    private fun commandUnregisterChannel(message: IMessage) {
        val user = message.author
        val guild = message.guild
        val channel = message.channel

        if (canUserManage(user, guild)) {
            if (isNotifiableChannel(channel)) {
                notificationTargets.remove(channel.longID)?.kill()
                commitSettings()

                makeRequest { channel.sendMessage("Channel unregistered for notifications, Dave.") }
            } else {
                makeRequest { channel.sendMessage("I can't do that, Dave.") }
            }
        }
    }

    private fun commandClearMessages(message: IMessage) {
        val user = message.author
        val guild = message.guild
        val channel = message.channel

        if (canUserManage(user, guild)) {
            if (isNotifiableChannel(channel)) {
                clearMessagesInChannel(channel)

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
                "register" -> commandRegisterChannel(message)
                "unregister" -> commandUnregisterChannel(message)
                "clear" -> commandClearMessages(message)
                "list" -> commandListGameTypes(message)
            }
        }
    }

    fun onGameHosted(gameInfo: GameInfo) {
        log.info("A game has been hosted: ${gameInfo.name}")
        for ((_, target) in notificationTargets) {
            target.processGameCreate(gameInfo)
        }
    }

    fun onGameUpdated(gameInfo: GameInfo) {
        log.info("A game has been updated: ${gameInfo.oldName} -> ${gameInfo.name}")
        for ((_, target) in notificationTargets) {
            target.processGameUpdate(gameInfo)
        }
    }

    fun onGameRemoved(gameInfo: GameInfo) {
        log.info("A game has been removed: ${gameInfo.name}")
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

    /**
     * We run the updates of each target in a single-threaded pool because
     * we don't want multiple updates to interfere with one another, but we
     * want targets to be independent from each other.
     */
    class NotificationTarget(val channel: IChannel,
                             val bot: ChatBot,
                             val types: MutableSet<GameType> = HashSet()) {
        private var lastMessage: IMessage? = null
        private val executor: ThreadPoolExecutor
        // use a treemap for strict ordering
        private val watchedGames = TreeMap<String, GameInfo>()
        private val updateTimer: Timer

        init {
            val threadFactory = ThreadFactory {
                val thread = Executors.defaultThreadFactory().newThread(it)
                thread.name = "MMH Scanner Channel Update Thread (${channel.name})"
                thread.isDaemon = false
                return@ThreadFactory thread
            }

            executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(), threadFactory)

            executor.submit {
                bot.clearMessagesInChannel(channel)
            }

            for ((_, info) in bot.watcher.getAll()) {
                processGameCreate(info)
            }

            updateTimer = timer(initialDelay = 0, period = 1000, action = {
                executor.submit{ updateInfoMessage() }
            })

            log.info("NotificationTarget created for channel ${channel.name} in ${channel.guild.name}")
        }

        private fun sendInfoMessage(string: String) {
            val lastMessage = lastMessage

            if (lastMessage == null) {
                makeRequest { this.lastMessage = channel.sendMessage(string) }
            } else {
                val history = makeRequest { channel.getMessageHistory(32) }
                if (history.latestMessage != lastMessage) {
                    makeRequest { lastMessage.delete() }
                    this.lastMessage = makeRequest { channel.sendMessage(string) }
                } else {
                    makeRequest { lastMessage.edit(string) }
                }
            }
        }

        private fun buildInfoMessage(): String {
            if (watchedGames.size > 0) {
                var message = "```Currently hosted games:\n|\n"
                var longestBotName = 0

                for ((bot, _) in watchedGames) {
                    longestBotName = if (bot.length > longestBotName) bot.length else longestBotName
                }

                for ((bot, info) in watchedGames) {
                    message += "| ${bot + " ".repeat(longestBotName - bot.length)}  ---  ${info.name}\n"
                }

                message += "```"
                return messageHeader + message
            } else {
                return messageHeader + "```There are currently no hosted games.```"
            }
        }

        private fun updateInfoMessage() {
            sendInfoMessage(buildInfoMessage())
        }

        fun processGameUpdate(info: GameInfo) {
            executor.submit {
                watchedGames[info.botName] = info
                updateInfoMessage()
            }
        }

        fun processGameCreate(info: GameInfo) {
            executor.submit {
                watchedGames[info.botName] = info
                makeRequest { channel.sendMessage("@everyone A game has been hosted! `${info.name}`") }
                updateInfoMessage()
            }
        }

        fun processGameRemove(info: GameInfo) {
            executor.submit {
                watchedGames.remove(info.botName)
                updateInfoMessage()
            }
        }

        fun kill() {
            updateTimer.cancel()
        }
    }
}