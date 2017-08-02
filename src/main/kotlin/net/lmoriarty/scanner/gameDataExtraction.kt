package net.lmoriarty.scanner

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException

enum class Realm {
    USA,
    EUROPE
}

data class GameRow(val botName: String,
                   val realm: Realm = Realm.USA,
                   val currentGame: String = "",
                   val ingameCounter: String = "")

class MakeMeHostExtractor {
    val gameListUrl: String = "http://makemehost.com/refresh/divGames-table-mmh.php"

    private fun fetchDocument(): Document? = Jsoup.connect(gameListUrl).get()

    private fun getRowColumns(element: Element): List<String> {
        val columns = ArrayList<String>()

        for (child in element.getElementsByTag("td")) {
            columns.add(child.text())
        }

        return columns
    }

    private fun extractRow(element: Element): GameRow {
        try {
            val columns = getRowColumns(element)

            if (columns.size < 4) {
                throw DataExtractException("Not enough columns in table!")
            }

            return GameRow(
                    botName = columns[0],
                    realm = Realm.valueOf(columns[1].toUpperCase()),
                    currentGame = columns[3],
                    ingameCounter = columns[4])
        } catch (e: Exception) {
            if (e is DataExtractException) throw e
            throw DataExtractException("Unexpected parsing error", e)
        }
    }

    fun extractRows(): List<GameRow>? {
        try {
            val htmlRows = fetchDocument()?.getElementsByTag("tr") ?: return null
            val gameRows = ArrayList<GameRow>()

            // remove first row because that is the header
            htmlRows.removeAt(0)
            for (row in htmlRows) {
                gameRows.add(extractRow(row))
            }

            return gameRows
        } catch (e: IOException) {
            throw MakeMeHostConnectException("Failed to fetch data from MMH.", e)
        }
    }
}