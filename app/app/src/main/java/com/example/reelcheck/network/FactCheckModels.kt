package com.example.reelcheck.network

import org.json.JSONArray
import org.json.JSONObject

data class FactCheckRequest(
    val username: String,
    val caption: String,
    val onScreenText: String = "",
    val screenshotJpegBase64: String,
    val audioWavBase64: String? = null
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("username", username)
        json.put("caption", caption)
        json.put("onScreenText", onScreenText)
        json.put("screenshotJpegBase64", screenshotJpegBase64)
        if (audioWavBase64 != null) {
            json.put("audioWavBase64", audioWavBase64)
        } else {
            json.put("audioWavBase64", JSONObject.NULL)
        }
        return json
    }
}

data class FactCheckSource(
    val title: String,
    val url: String
) {
    companion object {
        fun fromJson(json: JSONObject): FactCheckSource {
            return FactCheckSource(
                title = json.optString("title", "Source"),
                url = json.optString("url", "")
            )
        }
    }
}

enum class Verdict(val rawValue: String) {
    SUPPORTED("supported"),
    DISPUTED("disputed"),
    UNVERIFIABLE("unverifiable");

    companion object {
        fun fromString(value: String): Verdict {
            return entries.firstOrNull { it.rawValue.equals(value, ignoreCase = true) }
                ?: UNVERIFIABLE
        }
    }
}

data class FactCheckClaim(
    val claim: String,
    val verdict: Verdict,
    val explanation: String,
    val sources: List<FactCheckSource>
) {
    companion object {
        fun fromJson(json: JSONObject): FactCheckClaim {
            val sourcesArray = json.optJSONArray("sources") ?: JSONArray()
            val sourcesList = mutableListOf<FactCheckSource>()
            for (i in 0 until sourcesArray.length()) {
                val item = sourcesArray.optJSONObject(i)
                if (item != null) {
                    sourcesList.add(FactCheckSource.fromJson(item))
                }
            }
            return FactCheckClaim(
                claim = json.optString("claim", ""),
                verdict = Verdict.fromString(json.optString("verdict", "unverifiable")),
                explanation = json.optString("explanation", ""),
                sources = sourcesList
            )
        }
    }
}

data class FactCheckResponse(
    val claims: List<FactCheckClaim>,
    val notCheckable: String? = null,
    val cached: Boolean = false
) {
    companion object {
        fun fromJson(json: JSONObject): FactCheckResponse {
            val claimsArray = json.optJSONArray("claims") ?: JSONArray()
            val claimsList = mutableListOf<FactCheckClaim>()
            for (i in 0 until claimsArray.length()) {
                val item = claimsArray.optJSONObject(i)
                if (item != null) {
                    claimsList.add(FactCheckClaim.fromJson(item))
                }
            }
            val notCheckable = if (json.isNull("notCheckable")) null else json.optString("notCheckable").takeIf { it.isNotEmpty() }
            val cached = json.optBoolean("cached", false)
            return FactCheckResponse(
                claims = claimsList,
                notCheckable = notCheckable,
                cached = cached
            )
        }
    }
}

data class BudgetStats(
    val date: String,
    val dailyCostCapDollars: Double,
    val estimatedCostDollars: Double,
    val remainingBudgetDollars: Double,
    val modelCallCount: Int,
    val cachedCount: Int
)

data class HealthResponse(
    val status: String,
    val service: String,
    val geminiConfigured: Boolean,
    val timestamp: Long,
    val budget: BudgetStats? = null
)

data class StreamClaimsEvent(
    val claims: List<String>,
    val notCheckable: String?,
    val cached: Boolean
) {
    companion object {
        fun fromJson(json: JSONObject): StreamClaimsEvent {
            val claimsArray = json.optJSONArray("claims") ?: JSONArray()
            val list = mutableListOf<String>()
            for (i in 0 until claimsArray.length()) {
                val obj = claimsArray.optJSONObject(i)
                val claimStr = obj?.optString("claim") ?: claimsArray.optString(i, "")
                if (claimStr.isNotEmpty()) list.add(claimStr)
            }
            val notCheckable = if (json.isNull("notCheckable")) null else json.optString("notCheckable").takeIf { it.isNotEmpty() }
            val cached = json.optBoolean("cached", false)
            return StreamClaimsEvent(list, notCheckable, cached)
        }
    }
}

data class StreamVerdictEvent(
    val claimIndex: Int,
    val claim: FactCheckClaim
) {
    companion object {
        fun fromJson(json: JSONObject): StreamVerdictEvent {
            val claimIndex = json.optInt("claimIndex", 0)
            val claimObj = json.optJSONObject("claim") ?: JSONObject()
            return StreamVerdictEvent(claimIndex, FactCheckClaim.fromJson(claimObj))
        }
    }
}

data class StreamCompleteEvent(
    val totalDurationMs: Long
) {
    companion object {
        fun fromJson(json: JSONObject): StreamCompleteEvent {
            return StreamCompleteEvent(json.optLong("totalDurationMs", 0L))
        }
    }
}

