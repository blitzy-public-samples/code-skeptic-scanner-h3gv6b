from tweepy import API, OAuthHandler
from app.core.config import get_settings
from app.db.models import Tweet
from app.schema.tweet import Tweet as TweetSchema

class TwitterService:
    api: API
    settings: dict

    def __init__(self):
        self.settings = get_settings()
        auth = OAuthHandler(self.settings.TWITTER_API_KEY, self.settings.TWITTER_API_SECRET_KEY)
        auth.set_access_token(self.settings.TWITTER_ACCESS_TOKEN, self.settings.TWITTER_ACCESS_TOKEN_SECRET)
        self.api = API(auth)

    # HUMAN ASSISTANCE NEEDED
    # The confidence level for this function is below 0.8. 
    # Additional implementation details or error handling might be required.
    def stream_tweets(self, keywords: List[str]):
        # Set up tweet stream with keywords
        # Process incoming tweets
        # Yield processed tweets
        pass

    def process_tweet(self, raw_tweet: dict) -> TweetSchema:
        # Extract relevant information from raw tweet
        tweet_data = {
            "id": raw_tweet["id_str"],
            "text": raw_tweet["text"],
            "user": raw_tweet["user"]["screen_name"],
            "created_at": raw_tweet["created_at"],
            "likes": raw_tweet["favorite_count"],
            "retweets": raw_tweet["retweet_count"],
        }
        
        # Create TweetSchema object
        processed_tweet = TweetSchema(**tweet_data)
        
        # Return processed tweet
        return processed_tweet

    def check_popularity_threshold(self, tweet: TweetSchema) -> bool:
        popularity_threshold = self.settings.TWEET_POPULARITY_THRESHOLD
        
        # Compare tweet likes/follower count to threshold
        if tweet.likes >= popularity_threshold:
            return True
        
        # Return comparison result
        return False