import { GoogleGenAI } from "@google/genai";
import { FACT_CHECK_SYSTEM_PROMPT, buildUserPrompt } from "../prompts/factCheckPrompt.js";
import { CheckResponseSchema, type CheckResponse, type SourceItem } from "../schemas/response.js";
import type { CheckRequest } from "../schemas/request.js";
import { withTimeout } from "../utils/timeout.js";
import { budgetService } from "./budget.js";

function cleanBase64(data: string): string {
  // Strip potential data URL prefix e.g. "data:image/jpeg;base64,"
  const commaIdx = data.indexOf(",");
  return commaIdx !== -1 ? data.slice(commaIdx + 1) : data;
}

function extractJsonText(rawText: string): string {
  let cleaned = rawText.trim();
  // Remove markdown code fences if model wrapped response in ```json ... ```
  if (cleaned.startsWith("```json")) {
    cleaned = cleaned.replace(/^```json\s*/i, "").replace(/\s*```$/, "");
  } else if (cleaned.startsWith("```")) {
    cleaned = cleaned.replace(/^```\s*/, "").replace(/\s*```$/, "");
  }
  return cleaned.trim();
}

export class GeminiFactCheckService {
  private client: GoogleGenAI | null = null;
  private apiKey: string | null = null;

  private searchGroundingAvailable: boolean = process.env.GEMINI_SEARCH_GROUNDING !== "false";

  constructor() {
    this.apiKey = process.env.GEMINI_API_KEY || null;
    if (this.apiKey) {
      this.client = new GoogleGenAI({ apiKey: this.apiKey });
    }
  }

  isConfigured(): boolean {
    return !!this.apiKey;
  }

  async checkReel(request: CheckRequest): Promise<CheckResponse> {
    const isMock = process.env.REELCHECK_MOCK_GEMINI === "true" || !this.client;

    if (isMock) {
      const mockResult = this.generateMockResponse(request);
      budgetService.recordModelCall();
      return mockResult;
    }

    try {
      const parts: Array<any> = [
        {
          text: buildUserPrompt(
            request.username,
            request.caption,
            request.onScreenText,
            !!(request.audioWavBase64 && request.audioWavBase64.trim())
          ),
        },
      ];

      // Attach screenshot frame (in-memory base64)
      if (request.screenshotJpegBase64 && request.screenshotJpegBase64.trim()) {
        parts.push({
          inlineData: {
            mimeType: "image/jpeg",
            data: cleanBase64(request.screenshotJpegBase64),
          },
        });
      }

      // Attach audio buffer if captured (in-memory base64 WAV)
      if (request.audioWavBase64 && request.audioWavBase64.trim()) {
        parts.push({
          inlineData: {
            mimeType: "audio/wav",
            data: cleanBase64(request.audioWavBase64),
          },
        });
      }

      const modelName = process.env.GEMINI_MODEL || "gemini-3.1-flash-lite";
      let response: any;
      const baseConfig: any = {
        systemInstruction: FACT_CHECK_SYSTEM_PROMPT,
        thinkingConfig: { thinkingBudget: 0 },
        responseMimeType: "application/json",
      };

      if (this.searchGroundingAvailable) {
        try {
          response = await withTimeout(
            this.client!.models.generateContent({
              model: modelName,
              contents: [{ role: "user", parts }],
              config: {
                systemInstruction: FACT_CHECK_SYSTEM_PROMPT,
                thinkingConfig: { thinkingBudget: 0 },
                tools: [{ googleSearch: {} }],
              },
            }),
            15000,
            "Gemini fact check timed out after 15 seconds"
          );
        } catch (err: any) {
          if (
            err?.message?.includes("429") ||
            err?.message?.includes("quota") ||
            err?.message?.includes("RESOURCE_EXHAUSTED") ||
            err?.message?.includes("503")
          ) {
            console.warn("[GeminiService] Grounding search unavailable on current quota; falling back to direct model knowledge:", err.message);
            this.searchGroundingAvailable = false;
            response = await withTimeout(
              this.client!.models.generateContent({
                model: modelName,
                contents: [{ role: "user", parts }],
                config: baseConfig,
              }),
              15000,
              "Gemini fact check timed out after 15 seconds"
            );
          } else {
            throw err;
          }
        }
      } else {
        response = await withTimeout(
          this.client!.models.generateContent({
            model: modelName,
            contents: [{ role: "user", parts }],
            config: baseConfig,
          }),
          15000,
          "Gemini fact check timed out after 15 seconds"
        );
      }

      const rawText = response.text || "";
      const jsonText = extractJsonText(rawText);

      // Collect grounding sources from Search tool metadata
      const candidate = response.candidates?.[0];
      const groundingChunks = candidate?.groundingMetadata?.groundingChunks || [];
      const searchSources: SourceItem[] = [];

      for (const chunk of groundingChunks) {
        const uri = chunk.web?.uri;
        const title = chunk.web?.title || uri || "Web Source";
        if (uri && (uri.startsWith("http://") || uri.startsWith("https://"))) {
          searchSources.push({ title, url: uri });
        }
      }

      let parsed: any;
      try {
        parsed = JSON.parse(jsonText);
      } catch (parseError) {
        throw new Error(`Failed to parse model JSON response: ${(parseError as Error).message}\nRaw: ${rawText}`);
      }

      // Ensure sources are attached from grounding if model omitted URLs
      if (Array.isArray(parsed.claims)) {
        for (const claim of parsed.claims) {
          if (!Array.isArray(claim.sources) || claim.sources.length === 0) {
            claim.sources = searchSources.slice(0, 2);
          }
        }
      }

      // Validate strictly against schema from PLAN.md
      const validated = CheckResponseSchema.parse(parsed);
      budgetService.recordModelCall();
      return validated;
    } catch (err) {
      console.error("[GeminiFactCheckService] Error calling Gemini API:", err);
      throw err;
    }
  }

  private generateMockResponse(request: CheckRequest): CheckResponse {
    const text = `${request.caption} ${request.onScreenText}`.toLowerCase();

    // 10. Pure lifestyle / opinion / aesthetic
    if (text.includes("sunset") || text.includes("vibes") || text.includes("pov:") || text.includes("canggu") || text.includes("coconut")) {
      return {
        claims: [],
        notCheckable: "No factual or scientific claims identified; reel is aesthetic, opinion, or humor.",
      };
    }

    // 1. WASP-39b / sulfur dioxide
    if (text.includes("wasp-39b") || text.includes("sulfur dioxide") || text.includes("james webb") || text.includes("sulfur molecules")) {
      return {
        claims: [
          {
            claim: "James Webb Space Telescope detected sulfur dioxide in the atmosphere of exoplanet WASP-39b.",
            verdict: "supported",
            explanation: "High-resolution transmission spectroscopy from JWST confirmed sulfur dioxide (SO2) photochemical production on WASP-39b.",
            sources: [
              {
                title: "NASA Webb Reveals First Full Atmospheric Profile of an Exoplanet",
                url: "https://www.nasa.gov/universe/webb-reveals-first-full-atmospheric-profile-of-an-exoplanet/",
              },
              {
                title: "Nature: Sulfur dioxide in the atmosphere of an exoplanet",
                url: "https://www.nature.com/articles/s41586-023-05902-2",
              },
            ],
          },
        ],
        notCheckable: null,
      };
    }

    // 2. Bleach cure
    if (text.includes("bleach") || text.includes("detox your bloodstream")) {
      return {
        claims: [
          {
            claim: "Drinking diluted bleach can kill any virus and detox your bloodstream instantly.",
            verdict: "disputed",
            explanation: "Ingesting bleach (sodium hypochlorite or chlorine dioxide) causes severe chemical burns and internal organ failure; health authorities strongly warn against ingestion.",
            sources: [
              {
                title: "FDA Warning: Danger of Drinking Bleach and Miracle Mineral Solution",
                url: "https://www.fda.gov/consumers/consumer-updates/danger-dont-drink-miracle-mineral-solution-or-similar-products",
              },
              {
                title: "CDC: Household Disinfectant Poisonings Guidance",
                url: "https://www.cdc.gov/mmwr/volumes/69/wr/mm6916e1.htm",
              },
            ],
          },
        ],
        notCheckable: null,
      };
    }

    // 3. Earth's outer core
    if (text.includes("outer core") || text.includes("liquid iron") || text.includes("iron and nickel")) {
      return {
        claims: [
          {
            claim: "Earth's outer core is a fluid layer composed mostly of iron and nickel, generating our magnetic field.",
            verdict: "supported",
            explanation: "Geophysical seismic data proves the outer core is liquid iron-nickel, creating Earth's geomagnetic field via geodynamo action.",
            sources: [
              {
                title: "USGS: Earth Core Geodynamics",
                url: "https://www.usgs.gov/programs/geomagnetism/science/earths-magnetic-field",
              },
              {
                title: "National Geographic: Earth Core Structure",
                url: "https://education.nationalgeographic.org/resource/core/",
              },
            ],
          },
        ],
        notCheckable: null,
      };
    }

    // 4. Dinosaurs iPhone
    if (text.includes("jurassic") || text.includes("iphone") || (text.includes("dinosaur") && text.includes("cell"))) {
      return {
        claims: [
          {
            claim: "Archaeologists dug up an iPhone from the Jurassic period proving dinosaurs had cell towers.",
            verdict: "disputed",
            explanation: "The Jurassic period ended approximately 145 million years ago; modern electronic smartphones and cellular networks were invented in the 20th and 21st centuries.",
            sources: [
              {
                title: "Smithsonian: The Age of Dinosaurs and Human Technology",
                url: "https://www.smithsonianmag.com/history/",
              },
            ],
          },
        ],
        notCheckable: null,
      };
    }

    // 5. Water freezing point
    if (text.includes("water freezes") || text.includes("0 degrees celsius") || text.includes("freezing point")) {
      return {
        claims: [
          {
            claim: "Pure water freezes into ice at 0 degrees Celsius (32 degrees Fahrenheit) at standard atmospheric pressure.",
            verdict: "supported",
            explanation: "At standard atmospheric pressure (1 atm), the freezing and melting point of pure water is precisely 0 °C (32 °F).",
            sources: [
              {
                title: "NIST: Pure Substance Thermophysical Properties",
                url: "https://webbook.nist.gov/chemistry/fluid/",
              },
            ],
          },
        ],
        notCheckable: null,
      };
    }

    // 6. 10% brain myth
    if (text.includes("10% of their brain") || text.includes("10% of your brain") || text.includes("telepathic")) {
      return {
        claims: [
          {
            claim: "Humans only use 10% of their brain capacity, unlocking the rest gives telepathic powers.",
            verdict: "disputed",
            explanation: "Neuroimaging (fMRI and PET scans) confirms that nearly all of the human brain is active across normal daily tasks, debunking the 10% myth.",
            sources: [
              {
                title: "Mayo Clinic: Neurological Myths and Realities",
                url: "https://www.mayoclinic.org/brain-health-myths",
              },
            ],
          },
        ],
        notCheckable: null,
      };
    }

    // 7. Carrots WWII
    if (text.includes("carrots") && (text.includes("radar") || text.includes("night vision"))) {
      return {
        claims: [
          {
            claim: "Carrots were credited with British radar success in WWII to hide radar tech, proving carrots give night vision.",
            verdict: "disputed",
            explanation: "While the British Ministry of Information popularized the myth to conceal airborne interception radar, eating excess carrots does not grant night vision beyond preventing vitamin A deficiency.",
            sources: [
              {
                title: "Smithsonian Magazine: A WWII Propaganda Campaign Popularized the Carrot Myth",
                url: "https://www.smithsonianmag.com/arts-culture/a-wwii-propaganda-campaign-popularized-the-myth-that-carrots-help-you-see-in-the-dark-28812484/",
              },
            ],
          },
        ],
        notCheckable: null,
      };
    }

    // 8. Great Wall of China moon
    if (text.includes("great wall") && text.includes("moon")) {
      return {
        claims: [
          {
            claim: "The Great Wall of China is visible from the Moon with the naked eye.",
            verdict: "disputed",
            explanation: "Apollo astronauts confirmed that no human-made structures, including the Great Wall of China, are visible from lunar orbit with the naked eye.",
            sources: [
              {
                title: "NASA: Great Wall from Space Verification",
                url: "https://www.nasa.gov/vision/space/workinginspace/great_wall.html",
              },
            ],
          },
        ],
        notCheckable: null,
      };
    }

    // 9. Voyager 1 interstellar
    if (text.includes("voyager 1") || text.includes("interstellar space") || text.includes("heliopause")) {
      return {
        claims: [
          {
            claim: "NASA's Voyager 1 spacecraft crossed the heliopause into interstellar space in August 2012.",
            verdict: "supported",
            explanation: "In August 2012, Voyager 1 measured a sharp jump in galactic cosmic rays and a drop in solar particles, confirming its entry into interstellar space.",
            sources: [
              {
                title: "NASA Jet Propulsion Laboratory: Voyager 1 Enters Interstellar Space",
                url: "https://www.jpl.nasa.gov/news/nasa-spacecraft-embarks-on-historic-journey-into-interstellar-space",
              },
            ],
          },
        ],
        notCheckable: null,
      };
    }

    // Default mock response
    return {
      claims: [
        {
          claim: request.caption || "Content from mock reel",
          verdict: "unverifiable",
          explanation: "Demonstration mock response. To enable live Google Search fact-checking, configure GEMINI_API_KEY in backend/.env.",
          sources: [
            {
              title: "ReelCheck Documentation",
              url: "https://github.com/factcheckreels",
            },
          ],
        },
      ],
      notCheckable: null,
    };
  }
}

export const geminiService = new GeminiFactCheckService();
