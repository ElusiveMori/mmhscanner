package net.lmoriarty.scanner

import kotlin.concurrent.timer

/**
 * Holds info about a single MMH bot account, as much as is relevant to us.
 */
data class GameInfo(var name: String,
                    var updated: Boolean,
                    var gameType: GameType,
                    var oldName: String,
                    var botName: String)

enum class GameType(pattern: String) {
    ROTRP("""(rotrp)"""),
    YARP("""(yarp)"""),
    SOTDRP("""(sotdrp)"""),
    AOC("""(\baoc\b|aocl|aocrp)"""),
    GCG("""(gcg|guilty crown)"""),
    TL("""(\bkot\b|titans land|titan land|\btl\b)"""),
    LOAD("""(\bload\b|life of a dragon)"""),
    RP("""(roleplay|\brp\b)""");

    val regex = Regex(pattern)
}

/**
 * Scans the MMH roster and notifies the discord bot when it should post notifications.
 */
class Watcher(val bot: ChatBot) {
    // key is mmh bot account name
    private val registry: MutableMap<String, GameInfo> = HashMap()

    fun start() {
        timer(initialDelay = 5000, period = 5000, action = {scan()})
    }

    /**
     * Returns which regex matched the game, if any
     * or null otherwise
     */
    private fun shouldWatch(row: GameRow): GameType? {
        for (gameType in GameType.values()) {
            if (gameType.regex.containsMatchIn(row.currentGame.toLowerCase())) {
                return gameType
            }
        }

        return null
    }

    private fun scan() {
        try {
            val rows = extractRows().map { Pair(it, shouldWatch(it)) }.filter { it.second != null }
            registry.forEach { it.value.updated = false }

            for (row in rows) {
                val data = row.first
                val gameType = row.second as GameType
                var info = registry[row.first.botName]

                // we already had a tracked game on this bot
                if (info != null) {
                    if (info.gameType == gameType) {
                        // update if name got changed
                        if (info.name != data.currentGame) {
                            info.oldName = info.name
                            info.name = data.currentGame
                            bot.onGameUpdated(info)
                        }
                    } else {
                        // it's a different game - previous one got unhosted/started (very rare case)
                        bot.onGameRemoved(info)
                        registry.remove(data.botName)
                        info = null
                    }
                }

                // a new game has been hosted on the bot
                if (info == null) {
                    info = GameInfo(data.currentGame, false, gameType, "", data.botName)
                    bot.onGameHosted(info)
                    registry[data.botName] = info
                }

                info.updated = true
            }

            val iterator = registry.iterator()
            while (iterator.hasNext()) {
                val info = iterator.next().value

                // if game wasn't updated, means it's gone
                if (!info.updated) {
                    bot.onGameRemoved(info)
                    iterator.remove()
                }
            }
        } catch (e: MakeMeHostConnectException) {
            // it's not critical if MMH goes down
            log.warn("MMH Connection Warning:", e)
        } catch (e: Exception) {
            // don't let the exception propagate, because it will kill the timer
            log.error("Generic Error:", e)
        }
    }

}