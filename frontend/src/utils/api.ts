import axios, { AxiosInstance, AxiosRequestConfig, Method } from 'axios';
import { getAuthToken } from '../services/authService';

// HUMAN ASSISTANCE NEEDED
// The confidence level is below 0.8, so this function may need review and potential improvements.
// Additionally, error handling and response type could be more specific.

export async function fetchWithAuth(url: string, method: Method, data?: any): Promise<any> {
  try {
    const token = await getAuthToken();
    
    const axiosInstance: AxiosInstance = axios.create({
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
    });

    const config: AxiosRequestConfig = {
      method,
      url,
      data,
    };

    const response = await axiosInstance(config);
    return response.data;
  } catch (error) {
    if (axios.isAxiosError(error)) {
      // Handle Axios specific errors
      console.error('Axios error:', error.response?.data || error.message);
      throw new Error(error.response?.data?.message || 'An error occurred while fetching data');
    } else {
      // Handle other errors
      console.error('Error:', error);
      throw new Error('An unexpected error occurred');
    }
  }
}