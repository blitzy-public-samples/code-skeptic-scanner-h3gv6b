import { createSlice, PayloadAction, createAsyncThunk } from '@reduxjs/toolkit';
import { TweetSchema } from '../schema/tweet';
import { getTweets } from '../services/api';

interface TweetState {
  tweets: TweetSchema[];
  loading: boolean;
  error: string | null;
}

const initialState: TweetState = {
  tweets: [],
  loading: false,
  error: null,
};

// HUMAN ASSISTANCE NEEDED
// The fetchTweets function is implemented as an async thunk, but the confidence level is below 0.8.
// Please review and adjust as necessary.
export const fetchTweets = createAsyncThunk(
  'tweets/fetchTweets',
  async (params: object, { rejectWithValue }) => {
    try {
      const tweets = await getTweets(params);
      return tweets;
    } catch (error) {
      return rejectWithValue((error as Error).message);
    }
  }
);

const tweetSlice = createSlice({
  name: 'tweets',
  initialState,
  reducers: {},
  extraReducers: (builder) => {
    builder
      .addCase(fetchTweets.pending, (state) => {
        state.loading = true;
        state.error = null;
      })
      .addCase(fetchTweets.fulfilled, (state, action: PayloadAction<TweetSchema[]>) => {
        state.loading = false;
        state.tweets = action.payload;
      })
      .addCase(fetchTweets.rejected, (state, action) => {
        state.loading = false;
        state.error = action.payload as string;
      });
  },
});

export default tweetSlice.reducer;