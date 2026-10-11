package com.example.reelcheck.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

object FactCheckApiClient {
    private const val TAG = "ReelCheck-ApiClient"

    // 10.0.2.2 is the special Android Emulator alias to 127.0.0.1 on the host computer
    @Volatile
    var baseUrl: String = "http://10.0.2.2:3000"

    suspend fun checkHealth(): Result<HealthResponse> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("$baseUrl/health")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("Accept", "application/json")
            }

            val statusCode = connection.responseCode
            if (statusCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8))
                val responseText = reader.use { it.readText() }
                val json = JSONObject(responseText)

                var budgetStats: BudgetStats? = null
                val budgetObj = json.optJSONObject("budget")
                if (budgetObj != null) {
                    budgetStats = BudgetStats(
                        date = budgetObj.optString("date", ""),
                        dailyCostCapDollars = budgetObj.optDouble("dailyCostCapDollars", 2.0),
                        estimatedCostDollars = budgetObj.optDouble("estimatedCostDollars", 0.0),
                        remainingBudgetDollars = budgetObj.optDouble("remainingBudgetDollars", 2.0),
                        modelCallCount = budgetObj.optInt("modelCallCount", 0),
                        cachedCount = budgetObj.optInt("cachedCount", 0)
                    )
                }

                val health = HealthResponse(
                    status = json.optString("status", "unknown"),
                    service = json.optString("service", "unknown"),
                    geminiConfigured = json.optBoolean("geminiConfigured", false),
                    timestamp = json.optLong("timestamp", 0L),
                    budget = budgetStats
                )
                Result.success(health)
            } else {
                Result.failure(Exception("Health check returned HTTP $statusCode"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Health check failed: ${e.message}", e)
            Result.failure(Exception(formatNetworkException(e), e))
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseServerError(statusCode: Int, errorText: String): String {
        if (errorText.isBlank()) {
            return when (statusCode) {
                429 -> "Daily budget cap or rate limit reached. Please wait before checking again."
                504 -> "Fact-check timed out after 15s. The Gemini model took too long to verify."
                else -> "Server returned HTTP $statusCode"
            }
        }
        return try {
            val json = JSONObject(errorText)
            val msg = json.optString("message", "")
            val err = json.optString("error", "")
            when {
                msg.isNotBlank() && err.isNotBlank() -> "$err: $msg"
                msg.isNotBlank() -> msg
                err.isNotBlank() -> err
                else -> errorText
            }
        } catch (_: Exception) {
            errorText
        }
    }

    private fun formatNetworkException(e: Exception): String {
        return when (e) {
            is java.net.SocketTimeoutException ->
                "Request timed out. Server or network took too long to respond."
            is java.net.ConnectException ->
                "Cannot connect to ReelCheck backend at $baseUrl. Verify the server is running."
            else ->
                e.message ?: "Network communication failed"
        }
    }

    suspend fun checkReel(request: FactCheckRequest): Result<FactCheckResponse> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        val startTime = System.currentTimeMillis()
        try {
            val url = URL("$baseUrl/check")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 35000 // Allow sufficient time for Gemini search grounding
                doOutput = true
                doInput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
            }

            // Write JSON payload directly in memory (zero disk I/O per Rule 4)
            val jsonPayload = request.toJson().toString()
            connection.outputStream.use { os ->
                OutputStreamWriter(os, StandardCharsets.UTF_8).use { writer ->
                    writer.write(jsonPayload)
                    writer.flush()
                }
            }

            val statusCode = connection.responseCode
            val elapsedMs = System.currentTimeMillis() - startTime
            Log.i(TAG, "Backend responded with HTTP $statusCode in ${elapsedMs}ms")

            if (statusCode in 200..299) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8))
                val responseText = reader.use { it.readText() }
                val json = JSONObject(responseText)
                val response = FactCheckResponse.fromJson(json)
                Result.success(response)
            } else {
                val errorStream = connection.errorStream ?: connection.inputStream
                val errorText = errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
                Log.e(TAG, "Backend error ($statusCode): $errorText")
                val parsedMsg = parseServerError(statusCode, errorText)
                Result.failure(Exception(parsedMsg))
            }
        } catch (e: Exception) {
            val elapsedMs = System.currentTimeMillis() - startTime
            Log.e(TAG, "Fact check request failed after ${elapsedMs}ms: ${e.message}", e)
            Result.failure(Exception(formatNetworkException(e), e))
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun checkReelStream(
        request: FactCheckRequest,
        onClaims: (StreamClaimsEvent) -> Unit,
        onVerdict: (StreamVerdictEvent) -> Unit,
        onComplete: (StreamCompleteEvent) -> Unit,
        onError: (Throwable) -> Unit
    ) = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        val startTime = System.currentTimeMillis()
        try {
            val url = URL("$baseUrl/check/stream")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 40000 // Allow sufficient time for progressive Gemini Search grounding
                doOutput = true
                doInput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "text/event-stream")
            }

            // Write JSON payload directly in memory (zero disk I/O per Rule 4)
            val jsonPayload = request.toJson().toString()
            connection.outputStream.use { os ->
                OutputStreamWriter(os, StandardCharsets.UTF_8).use { writer ->
                    writer.write(jsonPayload)
                    writer.flush()
                }
            }

            val statusCode = connection.responseCode
            val elapsedMs = System.currentTimeMillis() - startTime
            Log.i(TAG, "Backend stream responded with HTTP $statusCode in ${elapsedMs}ms")

            if (statusCode !in 200..299) {
                val errorStream = connection.errorStream ?: connection.inputStream
                val errorText = errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
                Log.e(TAG, "Backend stream error ($statusCode): $errorText")
                val parsedMsg = parseServerError(statusCode, errorText)
                withContext(Dispatchers.Main) {
                    onError(Exception(parsedMsg))
                }
                return@withContext
            }

            // Stream line by line over SSE connection
            val reader = BufferedReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8))
            var currentEvent = ""
            var line: String? = reader.readLine()
            while (line != null) {
                if (line.startsWith("event: ")) {
                    currentEvent = line.substring(7).trim()
                } else if (line.startsWith("data: ")) {
                    val dataJson = line.substring(6).trim()
                    if (dataJson.isNotEmpty()) {
                        val json = JSONObject(dataJson)
                        when (currentEvent) {
                            "claims" -> {
                                val event = StreamClaimsEvent.fromJson(json)
                                withContext(Dispatchers.Main) { onClaims(event) }
                            }
                            "verdict" -> {
                                val event = StreamVerdictEvent.fromJson(json)
                                withContext(Dispatchers.Main) { onVerdict(event) }
                            }
                            "complete" -> {
                                val event = StreamCompleteEvent.fromJson(json)
                                withContext(Dispatchers.Main) { onComplete(event) }
                            }
                            "error" -> {
                                val errMsg = json.optString("message", "")
                                val errTitle = json.optString("error", "Stream check failed")
                                val combined = if (errMsg.isNotBlank() && errTitle != errMsg) "$errTitle: $errMsg" else errTitle
                                withContext(Dispatchers.Main) { onError(Exception(combined)) }
                            }
                        }
                    }
                }
                line = reader.readLine()
            }
        } catch (e: Exception) {
            val elapsedMs = System.currentTimeMillis() - startTime
            Log.e(TAG, "Stream fact check failed after ${elapsedMs}ms: ${e.message}", e)
            withContext(Dispatchers.Main) {
                onError(Exception(formatNetworkException(e), e))
            }
        } finally {
            connection?.disconnect()
        }
    }
}

