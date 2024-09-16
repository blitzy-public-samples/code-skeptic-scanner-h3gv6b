import React, { useState, useEffect } from 'react';
import { useParams } from 'react-router-dom';
import { ResponseManagement } from '../components/ResponseManagement';
import { getResponse } from '../services/api';

const ResponseView: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const [responseData, setResponseData] = useState<any>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const fetchResponseData = async () => {
      try {
        const data = await getResponse(id);
        setResponseData(data);
        setLoading(false);
      } catch (err) {
        setError('Failed to fetch response data');
        setLoading(false);
      }
    };

    fetchResponseData();
  }, [id]);

  if (loading) {
    return <div>Loading...</div>;
  }

  if (error) {
    return <div>Error: {error}</div>;
  }

  return (
    <div className="response-view">
      <h1>Response View</h1>
      {responseData && (
        <ResponseManagement responseData={responseData} />
      )}
    </div>
  );
};

export default ResponseView;