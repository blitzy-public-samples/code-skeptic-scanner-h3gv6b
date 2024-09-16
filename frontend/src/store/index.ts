import { configureStore } from '@reduxjs/toolkit';
import { tweetReducer } from './tweetSlice';
import { responseReducer } from './responseSlice';

const configureAppStore = () => {
  return configureStore({
    reducer: {
      tweet: tweetReducer,
      response: responseReducer,
    },
  });
};

export const store = configureAppStore();

export type RootState = ReturnType<typeof store.getState>;
export type AppDispatch = typeof store.dispatch;