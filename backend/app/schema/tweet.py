from pydantic import BaseModel
from typing import List, Optional
from datetime import datetime

class Tweet(BaseModel):
    id: str
    content: str
    like_count: int
    created_at: datetime
    doubt_rating: float
    media: List[str]
    quoted_tweet_id: Optional[str]
    user_id: str
    ai_tools_mentioned: List[str]