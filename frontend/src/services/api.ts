import axios from 'axios';
import { fetchWithAuth } from 'app/utils/api';

interface TweetData {
  // Define the structure of a tweet
  id: string;
  content: string;
  // Add other relevant fields
}

interface AnalysisResult {
  // Define the structure of an analysis result
  sentiment: string;
  keywords: string[];
  // Add other relevant fields
}

interface ResponseData {
  // Define the structure of a generated response
  content: string;
  // Add other relevant fields
}

export const getTweets = async (page: number, perPage: number): Promise<TweetData[]> => {
  const url = `/api/tweets?page=${page}&perPage=${perPage}`;
  const response = await fetchWithAuth(url);
  return response.data;
};

// HUMAN ASSISTANCE NEEDED
// The confidence level for this function is below 0.8. Please review and refine as necessary.
export const analyzeTweet = async (tweetId: string): Promise<AnalysisResult> => {
  const url = `/api/tweets/${tweetId}/analyze`;
  const response = await fetchWithAuth(url, {
    method: 'POST',
  });
  return response.data;
};

// HUMAN ASSISTANCE NEEDED
// The confidence level for this function is below 0.8. Please review and refine as necessary.
export const generateResponse = async (tweetId: string): Promise<ResponseData> => {
  const url = `/api/tweets/${tweetId}/generate-response`;
  const response = await fetchWithAuth(url, {
    method: 'POST',
  });
  return response.data;
};