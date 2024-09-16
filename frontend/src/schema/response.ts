import { z } from 'zod';

export const ResponseSchema = z.object({
  id: z.string(),
  content: z.string(),
  generatedAt: z.date(),
  isApproved: z.boolean(),
  tweetId: z.string()
});

export type Response = z.infer<typeof ResponseSchema>;