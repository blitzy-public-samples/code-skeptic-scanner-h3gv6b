import React, { useState, useEffect } from 'react';
import { Line, Bar } from 'react-chartjs-2';
import { getAnalytics } from '../services/api';

// HUMAN ASSISTANCE NEEDED
// The following component needs review and potential improvements for production readiness.
// Areas of concern:
// 1. Error handling for API calls
// 2. Loading state management
// 3. Responsive design considerations
// 4. Accessibility improvements
// 5. Performance optimization for large datasets

const Analytics: React.FC = () => {
  const [analyticsData, setAnalyticsData] = useState<any>(null);

  useEffect(() => {
    const fetchAnalytics = async () => {
      try {
        const data = await getAnalytics();
        setAnalyticsData(data);
      } catch (error) {
        console.error('Error fetching analytics:', error);
        // TODO: Implement proper error handling
      }
    };

    fetchAnalytics();
  }, []);

  if (!analyticsData) {
    return <div>Loading...</div>;
  }

  const prepareChartData = (data: any, label: string) => ({
    labels: data.labels,
    datasets: [
      {
        label,
        data: data.values,
        fill: false,
        borderColor: 'rgb(75, 192, 192)',
        tension: 0.1,
      },
    ],
  });

  return (
    <div className="analytics-dashboard">
      <h1>Analytics Dashboard</h1>

      <div className="chart-container">
        <h2>User Growth Trend</h2>
        <Line data={prepareChartData(analyticsData.userGrowth, 'User Growth')} />
      </div>

      <div className="chart-container">
        <h2>Revenue Trend</h2>
        <Bar data={prepareChartData(analyticsData.revenueTrend, 'Revenue')} />
      </div>

      <div className="summary-statistics">
        <h2>Summary Statistics</h2>
        <ul>
          <li>Total Users: {analyticsData.totalUsers}</li>
          <li>Active Users: {analyticsData.activeUsers}</li>
          <li>Total Revenue: ${analyticsData.totalRevenue}</li>
          <li>Average Order Value: ${analyticsData.averageOrderValue}</li>
        </ul>
      </div>
    </div>
  );
};

export default Analytics;