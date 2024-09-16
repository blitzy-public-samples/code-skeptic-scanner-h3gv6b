import React from 'react';
import Dashboard from '../components/Dashboard';
import TweetAnalysis from '../components/TweetAnalysis';
import ResponseManagement from '../components/ResponseManagement';

const Home: React.FC = () => {
  return (
    <div className="home-container">
      <h1>Dashboard</h1>
      <Dashboard />
      <TweetAnalysis />
      <ResponseManagement />
    </div>
  );
};

export default Home;