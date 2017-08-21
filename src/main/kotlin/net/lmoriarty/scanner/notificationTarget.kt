package net.lmoriarty.scanner

import sx.blah.discord.handle.obj.IChannel
import sx.blah.discord.handle.obj.IMessage
import sx.blah.discord.util.DiscordException
import java.util.*
import java.util.concurrent.*
import kotlin.concurrent.timer

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
    private val notificationMessages = HashMap<GameInfo, IMessage>()
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

        executor.submit {
            clearUntrackedMessages()
        }

        for ((_, info) in bot.watcher.getAll()) {
            processGameCreate(info)
        }

        updateTimer = timer(initialDelay = 1000, period = 5000, action = {
            try {
                executor.submit {
                    updateInfoMessage()
                }.get() // wait until we're finished to not spam the queue
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
            makeRequest {
                this.lastMessage = channel.sendMessage(string)
            }
        } else {
            val history = makeRequest {
                channel.getMessageHistory(32)
            }
            if (history.latestMessage != lastMessage) {
                makeRequest {
                    lastMessage.delete()
                }
                this.lastMessage = makeRequest {
                    channel.sendMessage(string)
                }
            } else {
                makeRequest {
                    lastMessage.edit(string)
                }
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
                message += "| ${bot + " ".repeat(longestBotName - bot.length)}  --- (${info.playerCount}) ${info.name}\n"
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

    fun clearUntrackedMessages() {
        log.info("Fetching full message history.")

        val toDelete = ArrayList<IMessage>()
        val history = makeRequest { channel.fullMessageHistory }

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

    fun processGameUpdate(info: GameInfo) {
        executor.submit {
            watchedGames[info.botName] = info
        }
    }

    fun processGameCreate(info: GameInfo) {
        executor.submit {
            watchedGames[info.botName] = info

            val message = makeRequest { channel.sendMessage("@here A game has been hosted! `${info.name}`") }
            notificationMessages[info] = message
        }
    }

    fun processGameRemove(info: GameInfo) {
        executor.submit {
            watchedGames.remove(info.botName)
            makeRequest { notificationMessages[info]?.delete() }
            notificationMessages.remove(info)
        }
    }

    fun kill() {
        updateTimer.cancel()
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