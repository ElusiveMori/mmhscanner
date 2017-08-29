package net.lmoriarty.scanner

import sx.blah.discord.api.internal.json.objects.EmbedObject
import sx.blah.discord.handle.obj.IChannel
import sx.blah.discord.handle.obj.IMessage
import sx.blah.discord.util.EmbedBuilder
import java.awt.Color
import java.util.*

private val fetchedMessageCount = 256

/**
 * We run the updates of each target in a single-threaded pool because
 * we don't want multiple updates to interfere with one another, but we
 * want targets to be independent from each other.
 */
class NotificationTarget(val channel: IChannel,
                         val bot: ChatBot,
                         types: Set<GameType> = HashSet()) {
    val types: MutableSet<GameType> = HashSet(types)

    // use a treemap for strict ordering
    private val watchedGames = TreeMap<String, GameInfo>()

    private val infoMessageHolder = InfoMessageHolder(channel)
    private val notificationMessagesHolder = NotificationMessagesHolder(channel)

    init {
        log.info("NotificationTarget created for channel ${channel.name} in ${channel.guild.name}")
    }

    private fun buildHeaderStringEmbed(): String {
        return "Currently active game types: ${types.map { it.toString() }.reduce { acc, s -> acc + ", " + s }}"
    }

    private fun buildHeaderStringFallback(): String {
        return "```${buildHeaderStringEmbed()}```"
    }

    private fun getGameInfoString(info: GameInfo, padding: Int): String {
        return "${info.botName + " ".repeat(padding - info.botName.length)} --- (${info.playerCount}) ${info.name}"
    }

    private fun buildGameListStringEmbed(): String {
        if (watchedGames.isEmpty()) {
            return "No games hosted right now."
        } else {
            var message = ""

            val longestBotName = watchedGames.maxBy { it.key.length }?.key?.length ?: 0
            for ((bot, info) in watchedGames) {
                message += "`${getGameInfoString(info, longestBotName)}`\n"
            }

            return message
        }
    }

    private fun buildGameListStringFallback(): String {
        if (watchedGames.isEmpty()) {
            return "```No games hosted right now.```"
        } else {
            var message = "```"

            val longestBotName = watchedGames.maxBy { it.key.length }?.key?.length ?: 0
            for ((bot, info) in watchedGames) {
                message += "| ${getGameInfoString(info, longestBotName)}\n"
            }

            return message + "```"
        }
    }

    private fun buildInfoEmbed(): EmbedObject {
        val builder = EmbedBuilder()
        builder.withTitle("MMH Scanner")
        if (watchedGames.size > 0) {
            builder.withColor(Color(0x45FA8B))
        } else {
            builder.withColor(Color(0xFFD1B2))
        }
        builder.withDescription(buildHeaderStringEmbed())
        val owner = bot.owner

        if (owner != null) {
            builder.withFooterText("Made by ${owner.name}#${owner.discriminator}")
            builder.withFooterIcon(owner.avatarURL)
        }

        builder.appendField("Game List:", buildGameListStringEmbed(), false)

        return builder.build()
    }

    private fun buildInfoString(): String {
        return buildHeaderStringFallback() + buildGameListStringFallback()
    }

    private fun sendInfoMessage() {

        if (bot.canEmbedInChannel(channel)) {
            infoMessageHolder.update(null, buildInfoEmbed())
        } else {
            infoMessageHolder.update(buildInfoString(), null)
        }
    }

    /**
     * Runs in the same thread as the caller.
     * Clears up all messages that are "stale", i.e. leftovers from older sessions or command responses.
     */
    fun clearUntrackedMessages() {
        if (!bot.canDeleteInChannel(channel) || !bot.canUseChannel(channel)) {
            log.warn("Missing permissions to clear untracked messages in ${channel.name} from ${channel.guild.name}")
            return
        }

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
        if (bot.canUseChannel(channel)) {
            notificationMessagesHolder.sendNotification(info)
        } else {
            log.warn("Missing permissions to post notifications in ${channel.name} from ${channel.guild.name}")
        }

        sendInfoMessage()
        watchedGames[info.botName] = info
    }

    private fun removeGame(info: GameInfo) {
        if (bot.canDeleteInChannel(channel)) {
            notificationMessagesHolder.removeNotification(info)
        } else {
            log.warn("Missing permissions to remove notifications in ${channel.name} from ${channel.guild.name}")
        }

        sendInfoMessage()
        watchedGames.remove(info.botName)
    }

    /**
     * Processes a game update event as received from the ChatBot.
     * Queues the actions into the primary thread for this NotificationTarget.
     */
    fun processGameUpdate(info: GameInfo) {
        synchronized(this) {
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
        synchronized(this) {
            if (info.gameType in types) {
                removeGame(info)
            }
        }
    }

    /**
     * Renews the info message.
     */
    fun renewInfoMessage() {
        synchronized(this) {
            if (!bot.canUseChannel(channel)) {
                log.warn("Missing permissions to renew info message in ${channel.name} from ${channel.guild.name}")
                return
            }

            infoMessageHolder.renew()
        }
    }

    /**
     * Updates the info message..
     */
    fun updateInfoMessage() {
        synchronized(this) {
            if (!bot.canUseChannel(channel)) {
                log.warn("Missing permissions to update info message in ${channel.name} from ${channel.guild.name}")
                return
            }

            sendInfoMessage()
        }
    }

    /**
     * Adds additional game types to be watched by this NotificationTarget.
     * Instantly tries to grab new games from the Watcher in the target's thread.
     */
    fun addGameTypes(toAdd: Set<GameType>) {
        synchronized(this) {
            types.addAll(toAdd)
        }
    }

    /**
     * Removes watched game types from this NotificationTarget.
     * Instantly purges old games in the target's thread.
     */
    fun removeGameTypes(toRemove: Set<GameType>) {
        synchronized(this) {
            types.removeAll(toRemove)
        }
    }

    fun isEmpty(): Boolean {
        synchronized(this) {
            return types.isEmpty()
        }
    }

    fun isTrackedMessage(message: IMessage): Boolean {
        synchronized(this) {
            return infoMessageHolder.isMessageTracked(message)
                    || notificationMessagesHolder.isMessageTracked(message)
        }
    }

    fun shutdown() {
        synchronized(this) {
            infoMessageHolder.shutdown()
            notificationMessagesHolder.shutdown()
        }
    }
}