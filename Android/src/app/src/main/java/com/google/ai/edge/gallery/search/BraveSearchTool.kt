package com.google.ai.edge.gallery.search

import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking  // ADD THIS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val TAG = "BraveSearchTool"

class BraveSearchTool(
    private val apiKey: String,
    private val scope: CoroutineScope,
    val onSearchResults: (String, List<SearchResult>) -> Unit
) : ToolSet {

    data class SearchResult(
        val title: String,
        val url: String,
        val description: String
    )

    @Tool(description = "Searches the web for accurate, up-to-date information...")
    fun webSearch(
        @ToolParam(description = "Specific search query...")
        query: String
    ): Map<String, Any> = runBlocking {  // Use runBlocking to wait for results

        try {
            val results = withContext(Dispatchers.IO) {
                fetchBraveSearchResults(query)
            }

            // Update UI on main thread
            scope.launch(Dispatchers.Main) {
                onSearchResults(query, results)
            }

            // Return actual results to model immediately
            mapOf(
                "result" to "success",
                "query" to query,
                "results" to formatResultsForModel(results),
                "count" to results.size
            )

        } catch (e: Exception) {
            Log.e(TAG, "Search failed", e)
            mapOf(
                "result" to "error",
                "error" to (e.message ?: "Unknown error"),
                "query" to query
            )
        }
    }

    private fun fetchBraveSearchResults(query: String): List<SearchResult> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        // Use text snippets for faster response
        val url = URL("https://api.search.brave.com/res/v1/web/search?q=$encodedQuery&count=3&search_lang=en&text_decorations=false")

        val connection = url.openConnection() as HttpURLConnection
        connection.apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Subscription-Token", apiKey)
            connectTimeout = 8000   // Reduce timeout
            readTimeout = 8000      // Reduce timeout
        }

        return try {
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                parseSearchResults(response)
            } else {
                emptyList()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseSearchResults(jsonResponse: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val json = JSONObject(jsonResponse)
        val webResults = json.optJSONObject("web")?.optJSONArray("results") ?: return results

        for (i in 0 until minOf(webResults.length(), 3)) {  // Limit to 3 results for speed
            val item = webResults.getJSONObject(i)
            results.add(
                SearchResult(
                    title = item.optString("title", "No title"),
                    url = item.optString("url", ""),
                    description = item.optString("description", "")
                )
            )
        }
        return results
    }

    private fun formatResultsForModel(results: List<SearchResult>): String {
        return results.mapIndexed { index, result ->
            "[${index + 1}] ${result.title}: ${result.description}"
        }.joinToString("\n")
    }
}
