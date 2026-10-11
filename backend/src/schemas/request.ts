import { z } from "zod";

export const CheckRequestSchema = z.object({
  username: z.string(),
  caption: z.string(),
  onScreenText: z.string().default(""),
  screenshotJpegBase64: z.string().min(1, "screenshotJpegBase64 is required"),
  audioWavBase64: z.string().nullable().default(null),
});

export type CheckRequest = z.infer<typeof CheckRequestSchema>;
