import { z } from 'zod';

export const AIToolSchema = z.object({
  id: z.string(),
  name: z.string(),
  description: z.string(),
});

export type AITool = z.infer<typeof AIToolSchema>;