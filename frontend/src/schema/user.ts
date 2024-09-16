import { z } from 'zod';

export const UserSchema = z.object({
  id: z.string(),
  handle: z.string(),
  followerCount: z.number(),
  lastTweetDate: z.date()
});

export type User = z.infer<typeof UserSchema>;