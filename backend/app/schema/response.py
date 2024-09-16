from pydantic import BaseModel
from datetime import datetime

class Response(BaseModel):
    id: str
    content: str
    generated_at: datetime
    is_approved: bool
    tweet_id: str