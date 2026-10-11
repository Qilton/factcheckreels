import type { CheckRequest } from "../../src/schemas/request.js";

// A minimal valid 1x1 JPEG in Base64
export const TINY_JPEG_BASE64 = "/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////wgALCAABAAEBAREA/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPxA=";

// A minimal 44-byte silent WAV in Base64
export const TINY_WAV_BASE64 = "UklGRiYAAABXQVZFZm10IBAAAAABAAEAQB8AAEAfAAABAAgAZGF0YQIAAAAA";

export const SAMPLE_REEL_ASTRONOMY: CheckRequest = {
  username: "nasa_astronomy",
  caption: "New James Webb observations have detected heavy sulfur molecules in the atmosphere of an exoplanet 120 light years away. This confirms models of active atmospheric volcanism.",
  onScreenText: "James Webb Discovery: Exoplanet Atmosphere",
  screenshotJpegBase64: TINY_JPEG_BASE64,
  audioWavBase64: TINY_WAV_BASE64,
};

export const SAMPLE_REEL_DISPUTED: CheckRequest = {
  username: "conspiracy_daily",
  caption: "NASA admitted the moon is artificial and hollow inside, with alien radio towers broadcasting on low frequencies.",
  onScreenText: "THE MOON IS HOLLOW AND ARTIFICIAL",
  screenshotJpegBase64: TINY_JPEG_BASE64,
  audioWavBase64: null,
};

export const SAMPLE_REEL_OPINION: CheckRequest = {
  username: "comedy_vibes",
  caption: "Me trying to get out of bed on Monday morning 😂 send this to someone lazy #humor #relatable",
  onScreenText: "POV: Monday mornings",
  screenshotJpegBase64: TINY_JPEG_BASE64,
  audioWavBase64: null,
};

export interface BenchmarkReelCase {
  id: string;
  topic: string;
  request: CheckRequest;
  expectedVerdict: "supported" | "disputed" | "not_checkable";
  expectedKeywords: string[];
}

export const BENCHMARK_10_REELS: BenchmarkReelCase[] = [
  {
    id: "reel-01",
    topic: "James Webb exoplanet SO2 discovery",
    request: {
      username: "space_news",
      caption: "James Webb Space Telescope detected sulfur dioxide in the atmosphere of exoplanet WASP-39b.",
      onScreenText: "JWST WASP-39b Discovery",
      screenshotJpegBase64: TINY_JPEG_BASE64,
      audioWavBase64: null,
    },
    expectedVerdict: "supported",
    expectedKeywords: ["WASP-39b", "sulfur dioxide", "Webb"],
  },
  {
    id: "reel-02",
    topic: "Drinking bleach cures diseases",
    request: {
      username: "fake_health",
      caption: "Drinking diluted bleach can kill any virus and detox your bloodstream instantly.",
      onScreenText: "Miracle Bleach Cure",
      screenshotJpegBase64: TINY_JPEG_BASE64,
      audioWavBase64: null,
    },
    expectedVerdict: "disputed",
    expectedKeywords: ["bleach", "toxic", "poison"],
  },
  {
    id: "reel-03",
    topic: "Earth outer core is liquid iron-nickel",
    request: {
      username: "geology_facts",
      caption: "Earth's outer core is a fluid layer composed mostly of iron and nickel, generating our magnetic field.",
      onScreenText: "Earth's Liquid Core",
      screenshotJpegBase64: TINY_JPEG_BASE64,
      audioWavBase64: null,
    },
    expectedVerdict: "supported",
    expectedKeywords: ["outer core", "iron", "magnetic field"],
  },
  {
    id: "reel-04",
    topic: "Dinosaurs coexisted with modern smartphones",
    request: {
      username: "myth_busters",
      caption: "Archaeologists dug up an iPhone from the Jurassic period proving dinosaurs had cell towers.",
      onScreenText: "Ancient iPhone Found",
      screenshotJpegBase64: TINY_JPEG_BASE64,
      audioWavBase64: null,
    },
    expectedVerdict: "disputed",
    expectedKeywords: ["dinosaur", "jurassic", "hoax"],
  },
  {
    id: "reel-05",
    topic: "Water freezing point at standard pressure",
    request: {
      username: "science_basics",
      caption: "Pure water freezes into ice at 0 degrees Celsius (32 degrees Fahrenheit) at standard atmospheric pressure.",
      onScreenText: "Freezing Point of Water",
      screenshotJpegBase64: TINY_JPEG_BASE64,
      audioWavBase64: null,
    },
    expectedVerdict: "supported",
    expectedKeywords: ["freezing point", "0 degrees", "ice"],
  },
  {
    id: "reel-06",
    topic: "Humans only use 10% of their brains myth",
    request: {
      username: "brain_myths",
      caption: "You only use 10% of your brain capacity, unlocking the remaining 90% gives telepathic powers.",
      onScreenText: "10% Brain Myth",
      screenshotJpegBase64: TINY_JPEG_BASE64,
      audioWavBase64: null,
    },
    expectedVerdict: "disputed",
    expectedKeywords: ["10 percent", "brain", "myth"],
  },
  {
    id: "reel-07",
    topic: "Carrots give military night vision myth",
    request: {
      username: "war_history",
      caption: "Carrots were credited with British radar success in WWII to hide radar tech, proving carrots give night vision.",
      onScreenText: "WWII Carrot Propaganda",
      screenshotJpegBase64: TINY_JPEG_BASE64,
      audioWavBase64: null,
    },
    expectedVerdict: "disputed",
    expectedKeywords: ["radar", "propaganda", "carrots"],
  },
  {
    id: "reel-08",
    topic: "Great Wall visible from the Moon with naked eye",
    request: {
      username: "travel_myths",
      caption: "The Great Wall of China is the only human-made structure visible from the Moon with the naked eye.",
      onScreenText: "Great Wall from Space",
      screenshotJpegBase64: TINY_JPEG_BASE64,
      audioWavBase64: null,
    },
    expectedVerdict: "disputed",
    expectedKeywords: ["great wall", "moon", "not visible"],
  },
  {
    id: "reel-09",
    topic: "Voyager 1 entered interstellar space",
    request: {
      username: "nasa_voyager",
      caption: "NASA's Voyager 1 spacecraft crossed the heliopause into interstellar space in August 2012.",
      onScreenText: "Voyager 1 Leaves Solar System",
      screenshotJpegBase64: TINY_JPEG_BASE64,
      audioWavBase64: null,
    },
    expectedVerdict: "supported",
    expectedKeywords: ["Voyager 1", "heliopause", "interstellar"],
  },
  {
    id: "reel-10",
    topic: "Sunset vibes in Bali (pure lifestyle)",
    request: {
      username: "bali_travels",
      caption: "Nothing beats this sunset in Canggu! Best coconut in the world #goodvibes #travel",
      onScreenText: "Sunset aesthetic ✨",
      screenshotJpegBase64: TINY_JPEG_BASE64,
      audioWavBase64: null,
    },
    expectedVerdict: "not_checkable",
    expectedKeywords: [],
  },
];
