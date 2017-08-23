package net.lmoriarty.scanner

import sx.blah.discord.handle.impl.obj.Channel
import sx.blah.discord.handle.obj.IChannel
import sx.blah.discord.handle.obj.IMessage
import sx.blah.discord.util.DiscordException
import java.util.*
import java.util.concurrent.*
import kotlin.concurrent.timer

private val fetchedMessageCount = 256

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
    private val notificationMessages = ConcurrentHashMap<String, IMessage>()
    // use a treemap for strict ordering
    private val watchedGames = TreeMap<String, GameInfo>()
    private val updateTimer: Timer

    init {
        val threadFactory = ThreadFactory {
            val thread = Executors.defaultThreadFactory().newThread(it)
            thread.name = "Channel Update Thread (${channel.name})"
            thread.isDaemon = false
            return@ThreadFactory thread
        }

        executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(), threadFactory)
        executor.allowCoreThreadTimeOut(false)

        executor.submit { clearUntrackedMessages() }

        for ((_, info) in bot.watcher.getAll()) {
            processGameUpdate(info)
        }

        updateTimer = timer(initialDelay = 1000, period = 5000, action = {
            try {
                executor.submit { updateInfoMessage() }.get() // wait until we're finished to not spam the queue
            } catch (e: DiscordException) {
                log.warn("Discord Error: ", e)
            } catch (e: Exception) {
                log.error("Generic Error: ", e)
            }
        })

        log.info("NotificationTarget created for channel ${channel.name} in ${channel.guild.name}")
    }

    private fun sendInfoMessage(string: String) {
        val lastMessage = lastMessage

        if (lastMessage == null) {
            makeRequest { this.lastMessage = channel.sendMessage(string) }
        } else {
            (channel as Channel).messages.clear() // we want to re-fetch history, so clear the cache
            val history = makeRequest { channel.getMessageHistory(8) }
            if (history.latestMessage != lastMessage) {
                makeRequest { lastMessage.delete() }
                this.lastMessage = makeRequest { channel.sendMessage(string) }
            } else {
                makeRequest { lastMessage.edit(string) }
            }
        }
    }

    private fun buildHeaderString(): String {
        var message = """```Type "-mmh list" to see which game types are available!
        |Currently active game types: """.trimMargin()
        message += types.map { it.toString() }.reduce {acc, s -> acc + ", " + s}
        return message + "```"
    }

    private fun buildGameListString(): String {
        var message = "```Currently hosted games:\n|\n"
        var longestBotName = 0

        for ((bot, _) in watchedGames) {
            longestBotName = if (bot.length > longestBotName) bot.length else longestBotName
        }

        for ((bot, info) in watchedGames) {
            message += "| ${bot + " ".repeat(longestBotName - bot.length)}  --- (${info.playerCount}) ${info.name}\n"
        }

        return message + "```"
    }

    private fun buildInfoMessage(): String {
        if (watchedGames.size > 0) {
            return buildHeaderString() + buildGameListString()
        } else {
            return buildHeaderString() + "```There are currently no hosted games.```"
        }
    }

    private fun updateInfoMessage() {
        sendInfoMessage(buildInfoMessage())
    }

    /**
     * Runs in the same thread as the caller.
     * Clears up all messages that are "stale", i.e. leftovers from older sessions or command responses.
     */
    fun clearUntrackedMessages() {
        log.info("Fetching message history, up to $fetchedMessageCount messages.")

        val toDelete = ArrayList<IMessage>()
        val history = makeRequest { channel.getMessageHistory(fetchedMessageCount) }

        log.info("Fetched ${history.size} messages in preparation for deletion.")

        for (historyMessage in history) {
            if (historyMessage.author == bot.client.ourUser && !isTrackedMessage(historyMessage)) {
                toDelete.add(historyMessage)
            }
        }

        log.info("${toDelete.size} messages will be deleted.")

        for (deletableMessage in toDelete) {
            makeRequest { deletableMessage.delete() }
        }

        log.info("Deleted ${toDelete.size} messages.")
    }

    private fun updateGame(info: GameInfo) {
        if (info.botName !in watchedGames) {
            val message = makeRequest { channel.sendMessage("@here A game has been hosted! `${info.name}`") }
            notificationMessages[info.botName] = message
        }

        watchedGames[info.botName] = info
    }

    private fun removeGame(info: GameInfo) {
        watchedGames.remove(info.botName)
        makeRequest { notificationMessages[info.botName]?.delete() }
        notificationMessages.remove(info.botName)
    }

    /**
     * Processes a game update event as received from the ChatBot.
     * Queues the actions into the primary thread for this NotificationTarget.
     */
    fun processGameUpdate(info: GameInfo) {
        executor.submit {
            if (info.gameType in types) {
                updateGame(info)
            }
        }
    }

    /**
     * Processess a game remove event as received from the ChatBot.
     * Queues the actions into the primary thread for this NotificationTarget.
     */
    fun processGameRemove(info: GameInfo) {
        executor.submit {
            if (info.gameType in types) {
                removeGame(info)
            }
        }
    }

    /**
     * Adds additional game types to be watched by this NotificationTarget.
     * Instantly tries to grab new games from the Watcher in the target's thread.
     */
    fun addGameTypes(toAdd: Set<GameType>) {
        executor.submit {
            types.addAll(toAdd)

            for ((_, info) in bot.watcher.getAll()) {
                if (info.gameType in types) {
                    updateGame(info)
                }
            }
        }
    }

    /**
     * Removes watched game types from this NotificationTarget.
     * Instantly purges old games in the target's thread.
     */
    fun removeGameTypes(toRemove: Set<GameType>) {
        executor.submit {
            types.removeAll(toRemove)

            for ((_, watchedGame) in watchedGames) {
                if (watchedGame.gameType in toRemove) {
                    removeGame(watchedGame)
                }
            }
        }
    }

    // this is really weird lol I'll need to change this
    // but basically we do this so that previously queued
    // actions have run before returning the result
    // because actions before could have altered the state of the object
    fun isEmpty(): Boolean {
        return executor.submit(Callable<Boolean>{ types.isEmpty() }).get()
    }

    fun shutdown() {
        executor.submit {
            updateTimer.cancel()
            executor.shutdown()
            notificationMessages.clear()
            lastMessage = null
            clearUntrackedMessages()
        }
    }

    fun isTrackedMessage(message: IMessage): Boolean {
        if (message == lastMessage) {
            return true
        } else if (notificationMessages.containsValue(message)) {
            return true
        }

        return false
    }
}