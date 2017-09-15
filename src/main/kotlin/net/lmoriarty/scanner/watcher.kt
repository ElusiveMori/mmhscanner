package net.lmoriarty.scanner

import kotlin.concurrent.timer

/**
 * Holds info about a single MMH bot account, as much as is relevant to us.
 */
data class GameInfo(val id: Long,
                    var name: String,
                    var oldName: String = "",
                    var type: GameType,
                    var bot: String,
                    var playerCount: String,
                    var oldPlayerCount: String = "",
                    var updated: Boolean = false)

enum class GameType(pattern: String, val roles: List<String>) {
    ROTRP           ("""(rotrp)""", listOf("RotRP")),
    YARP            ("""(yarp)""", listOf("YARP")),
    SOTDRP          ("""(sotdrp)""", listOf("SotDRP")),
    AOC             ("""(\baoc\b|aocl|aocrp)""", listOf("AOC")),
    GCG             ("""(gcg|guilty crown)""", listOf()),
    TL              ("""(\bkot\b|titans land|titan land|titanland|\btl\b)""", listOf("TL", "Titan's Land", "Titan Land")),
    LOAD            ("""(\bload\b|life of a dragon)""", listOf("LoaD")),
    MZI             ("""(mzi|medieval zombie invasion|medieval zombie|riverlands|winterscape|cityscape)""", listOf("MZI")),
    ROTK            ("""(rotk|three kingdoms)""", listOf("Strategist")),
    SPIDER_INVASION ("""(spider invasion)""", listOf()),
    AZEROTH         ("""(azeroth rp|azzy|kacpa)""", listOf("Azeroth")),
    FANTASY_LIFE    ("""(fantasy life|fl)""", listOf("Fantasy Life")),
    EAW             ("""(eaw|europe at war)""", listOf("Strategist")),
    RP              ("""(roleplay|\brp\b)""", listOf(""));

    val regex = Regex(pattern)
}

val ignoreRegex = Regex("""(\bpl\b|\bru\b|\bfr\b|\brus\b|\bger\b)""")

/**
 * Scans the MMH roster and notifies the discord bot when it should post notifications.
 */
class Watcher(val bot: ChatBot) {
    // key is mmh bot account name
    private val registry: MutableMap<String, GameInfo> = HashMap()
    private var idCounter = 0L

    fun start() {
        timer(name = "Watcher", initialDelay = 5000, period = 5000, action = {scan()})
    }

    /**
     * Returns which regex matched the game, if any
     * or null otherwise
     */
    private fun shouldWatch(row: GameRow): GameType? {
        if (ignoreRegex.containsMatchIn(row.currentGame.toLowerCase())) {
            return null
        }

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
                    if (info.type == gameType) {
                        // update if name and/or playercount got changed
                        if (info.name != data.currentGame || info.playerCount != data.playerCount) {
                            info.oldName = info.name
                            info.oldPlayerCount = info.playerCount

                            info.name = data.currentGame
                            info.playerCount = data.playerCount
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
                    info = GameInfo(id = idCounter++,
                                    name = data.currentGame,
                                    playerCount = data.playerCount,
                                    bot = data.botName,
                                    type = gameType
                                    )
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
            // don't let the exception propagate, because it will shutdown the timer
            log.error("Generic Error:", e)
        }
    }

    fun getAll(): Map<String, GameInfo> {
        return registry
    }
}