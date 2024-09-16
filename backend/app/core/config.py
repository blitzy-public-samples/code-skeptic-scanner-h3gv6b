from os import getenv
from pydantic import BaseSettings

class Settings(BaseSettings):
    TWITTER_API_KEY: str
    TWITTER_API_SECRET: str
    NOTION_API_KEY: str
    OPENAI_API_KEY: str
    DATABASE_URL: str
    TWEET_POPULARITY_THRESHOLD: int = 100  # Default value
    RESPONSE_GENERATION_DELAY: int = 60  # Default value in seconds

    class Config:
        env_file = ".env"
        env_file_encoding = "utf-8"

def get_settings() -> Settings:
    return Settings()