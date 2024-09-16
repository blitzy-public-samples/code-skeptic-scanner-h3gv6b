import { getTweets, analyzeTweet } from 'app/services/api';

interface TweetData {
  // Define the structure of a tweet
  id: string;
  content: string;
  popularity: number;
  // Add other relevant properties
}

interface AnalysisResult {
  // Define the structure of the analysis result
  sentiment: string;
  keywords: string[];
  // Add other relevant properties
}

class TwitterService {
  private popularityThreshold: number;

  constructor(initialThreshold: number) {
    this.popularityThreshold = initialThreshold;
  }

  async fetchTweets(page: number, perPage: number): Promise<TweetData[]> {
    const tweets = await getTweets(page, perPage);
    return tweets.filter(tweet => tweet.popularity >= this.popularityThreshold);
  }

  setPopularityThreshold(newThreshold: number): void {
    this.popularityThreshold = newThreshold;
  }

  async triggerAnalysis(tweetId: string): Promise<AnalysisResult> {
    return await analyzeTweet(tweetId);
  }
}

export default TwitterService;