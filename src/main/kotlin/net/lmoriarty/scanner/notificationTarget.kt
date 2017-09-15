package net.lmoriarty.scanner

import sx.blah.discord.api.internal.json.objects.EmbedObject
import sx.blah.discord.handle.obj.IChannel
import sx.blah.discord.handle.obj.IMessage
import sx.blah.discord.handle.obj.IRole
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

    private val watchedGames = TreeMap<Long, GameInfo>()
    private val infoMessageHolder = InfoMessageHolder(channel)
    private val notificationMessagesHolder = NotificationMessagesHolder(channel)
    private val typeRoles = HashMap<GameType, IRole>()

    init {
        log.info("NotificationTarget created for channel ${channel.name} in ${channel.guild.name}")
        updateRoles()
    }

    private fun updateRoles() {
        typeRoles.clear()

        for (type in types) {
            roleLoop@ for (roleName in type.roles) {
                for (role in channel.guild.roles) {
                    if (role.name.contains(roleName, true)) {
                        typeRoles[type] = role
                        log.info("${channel.name}/${channel.guild.name} : ${type} -> ${role.name}")
                        break@roleLoop
                    }
                }
            }
        }
    }

    private fun buildHeaderStringEmbed(): String {
        return "Currently active game types: ${types.map { it.toString() }.reduce { acc, s -> acc + ", " + s }}"
    }

    private fun buildHeaderStringFallback(): String {
        return "```${buildHeaderStringEmbed()}```"
    }

    private fun getGameInfoString(info: GameInfo, padding: Int): String {
        return "${info.bot + " ".repeat(padding - info.bot.length)} --- (${info.playerCount}) ${info.name}"
    }

    private fun getLongestBotName(): Int {
        return watchedGames.maxBy { it.value.bot.length }?.value?.bot?.length ?: 0
    }

    private fun buildGameListStringEmbed(): String {
        if (watchedGames.isEmpty()) {
            return "No games hosted right now."
        } else {
            var message = ""

            val longestBotName = getLongestBotName()
            for ((_, info) in watchedGames) {
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

            val longestBotName = getLongestBotName()
            for ((_, info) in watchedGames) {
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

    private fun updateMessage() {
        if (bot.canUseChannel(channel)) {
            if (bot.canEmbedInChannel(channel)) {
                infoMessageHolder.update(null, buildInfoEmbed())
            } else {
                infoMessageHolder.update(buildInfoString(), null)
            }
        } else {
            log.warn("Missing permissions to update info message in ${channel.name} from ${channel.guild.name}")
        }
    }

    private fun renewMessage() {
        if (bot.canUseChannel(channel) && bot.canDeleteInChannel(channel)) {
            infoMessageHolder.renew()
        } else {
            log.warn("Missing permissions to renew info message in ${channel.name} from ${channel.guild.name}")
        }
    }

    private fun getMentionForInfo(info: GameInfo): String {
        return typeRoles[info.type]?.mention() ?: "@here"
    }

    private fun sendNotification(info: GameInfo) {
        if (bot.canUseChannel(channel)) {
            if (notificationMessagesHolder.sendNotification(info, getMentionForInfo(info))) {
                renewInfoMessage()
            }
        } else {
            log.warn("Missing permissions to post notifications in ${channel.name} from ${channel.guild.name}")
        }
    }

    private fun removeNotification(info: GameInfo) {
        if (bot.canDeleteInChannel(channel)) {
            notificationMessagesHolder.removeNotification(info)
        } else {
            log.warn("Missing permissions to remove notifications in ${channel.name} from ${channel.guild.name}")
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
        watchedGames[info.id] = info
        sendNotification(info)
        updateMessage()
    }

    private fun removeGame(info: GameInfo) {
        watchedGames.remove(info.id)
        removeNotification(info)
        updateMessage()
    }

    /**
     * Processes a game update event as received from the ChatBot.
     * Queues the actions into the primary thread for this NotificationTarget.
     */
    fun processGameUpdate(info: GameInfo) {
        synchronized(this) {
            if (info.type in types) {
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
            if (info.type in types) {
                removeGame(info)
            }
        }
    }

    /**
     * Renews the info message.
     */
    fun renewInfoMessage() {
        synchronized(this) {
            renewMessage()
        }
    }

    /**
     * Updates the info message.
     */
    fun updateInfoMessage() {
        synchronized(this) {
            updateMessage()
        }
    }

    /**
     * Adds additional game types to be watched by this NotificationTarget.
     * Instantly tries to grab new games from the Watcher in the target's thread.
     */
    fun addGameTypes(toAdd: Set<GameType>) {
        synchronized(this) {
            types.addAll(toAdd)
            updateRoles()
        }
    }

    /**
     * Removes watched game types from this NotificationTarget.
     * Instantly purges old games in the target's thread.
     */
    fun removeGameTypes(toRemove: Set<GameType>) {
        synchronized(this) {
            types.removeAll(toRemove)
            updateRoles()
        }
    }

    /**
     * Checks if the target isn't registered for any notifications.
     */
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