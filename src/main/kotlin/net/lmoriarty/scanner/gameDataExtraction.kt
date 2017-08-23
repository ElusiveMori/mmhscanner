package net.lmoriarty.scanner

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException

enum class Realm {
    USA,
    EUROPE,
    UNKNOWN
}

data class GameRow(val botName: String,
                   val realm: Realm = Realm.USA,
                   val currentGame: String = "",
                   val playerCount: String = "")

private val mmhGameListUrl: String = "http://makemehost.com/refresh/divGames-table-mmh.php"
private val entGameListUrl: String = "http://makemehost.com/refresh/divGames-table-ent.php"

private fun fetchDocument(url: String): Document = Jsoup.connect(url).get()

private fun getRowColumns(element: Element): List<String> {
    val columns = ArrayList<String>()

    for (child in element.getElementsByTag("td")) {
        columns.add(child.text())
    }

    return columns
}

private fun extractMmhRow(element: Element): GameRow {
    try {
        val columns = getRowColumns(element)

        if (columns.size < 4) {
            throw DataExtractException("Not enough columns in table!")
        }

        return GameRow(
                botName = columns[0],
                realm = Realm.valueOf(columns[1].toUpperCase()),
                currentGame = columns[3],
                playerCount = columns[4])
    } catch (e: Exception) {
        if (e is DataExtractException) throw e
        throw DataExtractException("Unexpected parsing error", e)
    }
}

private fun extractEntRow(element: Element): GameRow {
    try {
        val columns = getRowColumns(element)

        if (columns.size < 3) {
            throw DataExtractException("Not enough columns in table!")
        }

        return GameRow(
                botName = columns[0],
                currentGame = columns[1],
                playerCount = columns[2],
                realm = Realm.UNKNOWN)
    } catch (e: Exception) {
        if (e is DataExtractException) throw e
        throw DataExtractException("Unexpected parsing error", e)
    }
}

fun extractRows(): List<GameRow> {
    try {
        val htmlMmhRows = fetchDocument(mmhGameListUrl).getElementsByTag("tr")
        val htmlEntRows = fetchDocument(entGameListUrl).getElementsByTag("tr")

        val gameRows = ArrayList<GameRow>()

        // remove first row because that is the header
        htmlMmhRows.removeAt(0)
        for (row in htmlMmhRows) {
            gameRows.add(extractMmhRow(row))
        }

        htmlEntRows.removeAt(0)
        for (row in htmlEntRows) {
            gameRows.add(extractEntRow(row))
        }

        return gameRows
    } catch (e: IOException) {
        throw MakeMeHostConnectException("Failed to fetch data from MMH.", e)
    }
}
