import { createSlice, PayloadAction, createAsyncThunk } from '@reduxjs/toolkit';
import { ResponseSchema } from '../schema/response';
import { generateResponse as apiGenerateResponse } from '../services/api';

interface ResponseState {
  responses: ResponseSchema[];
  loading: boolean;
  error: string | null;
}

const initialState: ResponseState = {
  responses: [],
  loading: false,
  error: null,
};

// HUMAN ASSISTANCE NEEDED
// The confidence level for the generateResponse function is below 0.8.
// Please review and adjust the implementation as needed.
export const generateResponse = createAsyncThunk<ResponseSchema, string>(
  'response/generateResponse',
  async (tweetId: string, { rejectWithValue }) => {
    try {
      const response = await apiGenerateResponse(tweetId);
      return response;
    } catch (error) {
      return rejectWithValue((error as Error).message);
    }
  }
);

const responseSlice = createSlice({
  name: 'response',
  initialState,
  reducers: {},
  extraReducers: (builder) => {
    builder
      .addCase(generateResponse.pending, (state) => {
        state.loading = true;
        state.error = null;
      })
      .addCase(generateResponse.fulfilled, (state, action: PayloadAction<ResponseSchema>) => {
        state.loading = false;
        state.responses.push(action.payload);
      })
      .addCase(generateResponse.rejected, (state, action) => {
        state.loading = false;
        state.error = action.payload as string;
      });
  },
});

export default responseSlice.reducer;