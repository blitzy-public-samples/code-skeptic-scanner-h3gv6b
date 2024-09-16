import { z } from 'zod';

export const TweetSchema = z.object({
  id: z.string(),
  content: z.string(),
  likeCount: z.number(),
  createdAt: z.date(),
  doubtRating: z.number(),
  media: z.array(z.string()),
  quotedTweetId: z.string().optional(),
  userId: z.string(),
  aiToolsMentioned: z.array(z.string())
});

export type Tweet = z.infer<typeof TweetSchema>;