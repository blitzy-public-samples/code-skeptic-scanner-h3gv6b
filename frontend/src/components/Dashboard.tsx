import React, { useState, useEffect } from 'react';
import { useQuery } from 'react-query';
import { getTweets } from '../services/api';
import TweetList from './TweetList';
import PerformanceMetrics from './PerformanceMetrics';

const Dashboard: React.FC = () => {
  const { data: tweets, isLoading, error } = useQuery('tweets', getTweets, {
    refetchInterval: 5000, // Refetch every 5 seconds for real-time updates
  });

  if (isLoading) return <div>Loading...</div>;
  if (error) return <div>Error fetching tweets</div>;

  return (
    <div className="dashboard">
      <h1>Dashboard</h1>
      <div className="dashboard-content">
        <div className="tweet-feed">
          <h2>Real-time Tweet Feed</h2>
          <TweetList tweets={tweets || []} />
        </div>
        <div className="performance-metrics">
          <h2>Performance Metrics</h2>
          <PerformanceMetrics />
        </div>
      </div>
    </div>
  );
};

export default Dashboard;