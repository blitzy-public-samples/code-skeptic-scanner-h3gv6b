import React, { useState, useEffect } from 'react';
import { analyzeTweet } from '../services/api';
import { TweetSchema } from '../schema/tweet';

// HUMAN ASSISTANCE NEEDED
// This component might need additional styling and error handling for production readiness.
// Consider adding loading states and error messages for better user experience.

const TweetAnalysis: React.FC<{ tweet: TweetSchema }> = ({ tweet }) => {
  const [analysis, setAnalysis] = useState<any>(null);

  useEffect(() => {
    const fetchAnalysis = async () => {
      try {
        const result = await analyzeTweet(tweet);
        setAnalysis(result);
      } catch (error) {
        console.error('Error analyzing tweet:', error);
        // TODO: Handle error state
      }
    };

    fetchAnalysis();
  }, [tweet]);

  if (!analysis) {
    return <div>Loading analysis...</div>;
  }

  return (
    <div className="tweet-analysis">
      <h3>Tweet Analysis</h3>
      <p>Content: {tweet.content}</p>
      
      <div className="sentiment-analysis">
        <h4>Sentiment Analysis</h4>
        <p>Sentiment: {analysis.sentiment}</p>
        {/* TODO: Add more detailed sentiment information if available */}
      </div>

      <div className="doubt-rating">
        <h4>Doubt Rating</h4>
        <p>Doubt Level: {analysis.doubtRating}/10</p>
        {/* TODO: Consider adding a visual representation of the doubt rating */}
      </div>

      <div className="ai-tools">
        <h4>AI Tools Mentioned</h4>
        {analysis.aiTools && analysis.aiTools.length > 0 ? (
          <ul>
            {analysis.aiTools.map((tool: string, index: number) => (
              <li key={index}>{tool}</li>
            ))}
          </ul>
        ) : (
          <p>No AI tools mentioned</p>
        )}
      </div>
    </div>
  );
};

export default TweetAnalysis;