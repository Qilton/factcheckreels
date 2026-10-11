package com.example.reelcheck.network

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FactCheckModelsTest {

    @Test
    fun testRequestToJson_containsAllRequiredFields() {
        val request = FactCheckRequest(
            username = "nasa_astronomy",
            caption = "James Webb discovery of SO2",
            onScreenText = "JWST WASP-39b",
            screenshotJpegBase64 = "abc123jpeg",
            audioWavBase64 = "def456wav"
        )

        val json = request.toJson()
        assertEquals("nasa_astronomy", json.getString("username"))
        assertEquals("James Webb discovery of SO2", json.getString("caption"))
        assertEquals("JWST WASP-39b", json.getString("onScreenText"))
        assertEquals("abc123jpeg", json.getString("screenshotJpegBase64"))
        assertEquals("def456wav", json.getString("audioWavBase64"))
    }

    @Test
    fun testRequestToJson_handlesNullAudioGracefully() {
        val request = FactCheckRequest(
            username = "news_channel",
            caption = "Test caption",
            screenshotJpegBase64 = "abc123jpeg",
            audioWavBase64 = null
        )

        val json = request.toJson()
        assertTrue(json.isNull("audioWavBase64"))
    }

    @Test
    fun testResponseFromJson_parsesClaimsAndSources() {
        val jsonStr = """
            {
              "claims": [
                {
                  "claim": "James Webb detected sulfur dioxide on WASP-39b.",
                  "verdict": "supported",
                  "explanation": "Verified by NASA spectroscopy.",
                  "sources": [
                    {
                      "title": "NASA Official Release",
                      "url": "https://www.nasa.gov/universe/webb"
                    }
                  ]
                }
              ],
              "notCheckable": null,
              "cached": true
            }
        """.trimIndent()

        val response = FactCheckResponse.fromJson(JSONObject(jsonStr))
        assertTrue(response.cached)
        assertNull(response.notCheckable)
        assertEquals(1, response.claims.size)

        val claim = response.claims[0]
        assertEquals("James Webb detected sulfur dioxide on WASP-39b.", claim.claim)
        assertEquals(Verdict.SUPPORTED, claim.verdict)
        assertEquals("Verified by NASA spectroscopy.", claim.explanation)
        assertEquals(1, claim.sources.size)
        assertEquals("NASA Official Release", claim.sources[0].title)
        assertEquals("https://www.nasa.gov/universe/webb", claim.sources[0].url)
    }

    @Test
    fun testResponseFromJson_parsesNotCheckable() {
        val jsonStr = """
            {
              "claims": [],
              "notCheckable": "Aesthetic or opinion content with no factual claims",
              "cached": false
            }
        """.trimIndent()

        val response = FactCheckResponse.fromJson(JSONObject(jsonStr))
        assertFalse(response.cached)
        assertEquals("Aesthetic or opinion content with no factual claims", response.notCheckable)
        assertEquals(0, response.claims.size)
    }

    @Test
    fun testStreamClaimsEvent_parsesIncrementalClaims() {
        val jsonStr = """
            {
              "claims": [
                { "claim": "Exoplanet WASP-39b contains sulfur dioxide." }
              ],
              "notCheckable": null,
              "cached": false
            }
        """.trimIndent()

        val event = StreamClaimsEvent.fromJson(JSONObject(jsonStr))
        assertFalse(event.cached)
        assertNull(event.notCheckable)
        assertEquals(1, event.claims.size)
        assertEquals("Exoplanet WASP-39b contains sulfur dioxide.", event.claims[0])
    }

    @Test
    fun testStreamVerdictEvent_parsesClaimVerdict() {
        val jsonStr = """
            {
              "claimIndex": 0,
              "claim": {
                "claim": "Exoplanet WASP-39b contains sulfur dioxide.",
                "verdict": "supported",
                "explanation": "Detected by Webb NIRSpec.",
                "sources": [
                  {
                    "title": "Nature Journal",
                    "url": "https://nature.com"
                  }
                ]
              }
            }
        """.trimIndent()

        val event = StreamVerdictEvent.fromJson(JSONObject(jsonStr))
        assertEquals(0, event.claimIndex)
        assertEquals("Exoplanet WASP-39b contains sulfur dioxide.", event.claim.claim)
        assertEquals(Verdict.SUPPORTED, event.claim.verdict)
        assertEquals(1, event.claim.sources.size)
    }

    @Test
    fun testStreamCompleteEvent_parsesDuration() {
        val jsonStr = """{ "totalDurationMs": 420 }"""
        val event = StreamCompleteEvent.fromJson(JSONObject(jsonStr))
        assertEquals(420L, event.totalDurationMs)
    }

    @Test
    fun testHealthResponse_parsesBudgetStats() {
        val budget = BudgetStats(
            date = "2026-10-11",
            dailyCostCapDollars = 2.0,
            estimatedCostDollars = 0.045,
            remainingBudgetDollars = 1.955,
            modelCallCount = 30,
            cachedCount = 42
        )
        val health = HealthResponse(
            status = "ok",
            service = "reelcheck-backend",
            geminiConfigured = true,
            timestamp = 123456789L,
            budget = budget
        )
        assertEquals("ok", health.status)
        assertEquals("reelcheck-backend", health.service)
        assertTrue(health.geminiConfigured)
        assertNotNull(health.budget)
        assertEquals(2.0, health.budget!!.dailyCostCapDollars, 0.001)
        assertEquals(0.045, health.budget!!.estimatedCostDollars, 0.001)
        assertEquals(1.955, health.budget!!.remainingBudgetDollars, 0.001)
        assertEquals(30, health.budget!!.modelCallCount)
        assertEquals(42, health.budget!!.cachedCount)
    }
}

