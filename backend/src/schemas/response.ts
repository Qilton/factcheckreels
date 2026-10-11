import { z } from "zod";

export const SourceItemSchema = z.object({
  title: z.string(),
  url: z.string().url(),
});

export const ClaimItemSchema = z.object({
  claim: z.string(),
  verdict: z.enum(["supported", "disputed", "unverifiable"]),
  explanation: z.string(),
  sources: z.array(SourceItemSchema),
});

export const CheckResponseSchema = z.object({
  claims: z.array(ClaimItemSchema),
  notCheckable: z.string().nullable().default(null),
});

export type SourceItem = z.infer<typeof SourceItemSchema>;
export type ClaimItem = z.infer<typeof ClaimItemSchema>;
export type FactCheckClaim = ClaimItem;
export type CheckResponse = z.infer<typeof CheckResponseSchema>;

