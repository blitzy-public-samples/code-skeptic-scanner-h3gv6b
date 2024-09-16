import React, { useState, useEffect } from 'react';
import { useParams } from 'react-router-dom';
import TweetAnalysis from '../components/TweetAnalysis';
import { getTweet } from '../services/api';

interface Tweet {
  id: string;
  content: string;
  // Add other tweet properties as needed
}

const TweetView: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const [tweet, setTweet] = useState<Tweet | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const fetchTweet = async () => {
      try {
        const tweetData = await getTweet(id);
        setTweet(tweetData);
      } catch (err) {
        setError('Failed to fetch tweet data');
      } finally {
        setLoading(false);
      }
    };

    fetchTweet();
  }, [id]);

  if (loading) {
    return <div>Loading...</div>;
  }

  if (error) {
    return <div>Error: {error}</div>;
  }

  if (!tweet) {
    return <div>Tweet not found</div>;
  }

  return (
    <div className="tweet-view">
      <h1>Tweet Analysis</h1>
      <TweetAnalysis tweet={tweet} />
    </div>
  );
};

export default TweetView;