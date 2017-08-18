package net.lmoriarty.scanner

import kotlin.concurrent.timer

/**
 * Holds info about a single MMH bot account, as much as is relevant to us.
 */
data class GameInfo(var name: String, var updated: Boolean, var pattern: Regex, var oldName: String)

/**
 * Scans the MMH roster and notifies the discord bot when it should post notifications.
 */
class Watcher(val bot: ChatBot) {
    // key is mmh bot account name
    private val registry: MutableMap<String, GameInfo> = HashMap()
    // TODO: add loading from config, modification with commands
    private val patterns = listOf(
            Regex("""(rotrp)"""),
            Regex("""(yarp)"""),
            Regex("""(sotdrp)"""),
            Regex("""(aoc|aocrp)"""),
            Regex("""(gcg)"""),
            Regex("""(\bkot\b|titans land|titan land|\btl\b)"""),
            Regex("""(\bload\b|life of a dragon)"""),
            Regex("""(roleplay|\brp\b)""")
    )

    init {
        timer(initialDelay = 5000, period = 5000, action = {scan()})
    }

    /**
     * Returns which regex matched the game, if any
     * or null otherwise
     */
    private fun shouldWatch(row: GameRow): Regex? {
        for (pattern in patterns) {
            if (pattern.containsMatchIn(row.currentGame.toLowerCase())) {
                return pattern
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
                val regex = row.second as Regex
                var info = registry[row.first.botName]

                if (info != null) {
                    if (info.pattern == regex) {
                        if (info.name != data.currentGame) {
                            info.oldName = info.name
                            info.name = data.currentGame
                            log.info("Game is updated: " + data.currentGame)
                            bot.onGameUpdated(info)
                        }
                    } else {
                        bot.onGameUnhosted(info)
                        registry.remove(data.botName)
                        info = null
                    }
                }

                if (info == null) {
                    info = GameInfo(data.currentGame, false, regex, "")
                    bot.onGameHosted(info)
                    registry[data.botName] = info
                }

                info.updated = true
            }

            val iterator = registry.iterator()
            while (iterator.hasNext()) {
                val info = iterator.next().value

                if (!info.updated) {
                    bot.onGameUnhosted(info)
                    iterator.remove()
                }
            }

        } catch (e: MakeMeHostConnectException) {
            log.warn(e)
        }

    }

}