from tweepy import Stream
from app.core.config import get_settings
from app.services.twitter_service import TwitterService
from app.services.sentiment_analysis import SentimentAnalysis
from app.db.models import Tweet
from app.schema.tweet import Tweet as TweetSchema

class TweetListener:
    def __init__(self, twitter_service: TwitterService, sentiment_analysis: SentimentAnalysis):
        self.twitter_service = twitter_service
        self.sentiment_analysis = sentiment_analysis

    # HUMAN ASSISTANCE NEEDED
    # The following function needs review for production readiness
    def on_status(self, status):
        # Check if tweet meets popularity threshold
        if self.twitter_service.meets_popularity_threshold(status):
            # Analyze sentiment of tweet
            sentiment = self.sentiment_analysis.analyze(status.text)
            
            # Create Tweet object and store in database
            tweet = Tweet(
                id=status.id,
                text=status.text,
                user=status.user.screen_name,
                created_at=status.created_at,
                sentiment=sentiment
            )
            # TODO: Add database session and commit tweet
            
            # Trigger response generation if necessary
            # TODO: Implement response generation logic
            
        return True

# HUMAN ASSISTANCE NEEDED
# The following function needs review for production readiness
def start_tweet_stream():
    settings = get_settings()
    twitter_service = TwitterService()
    sentiment_analysis = SentimentAnalysis()
    
    listener = TweetListener(twitter_service, sentiment_analysis)
    
    stream = Stream(
        consumer_key=settings.TWITTER_CONSUMER_KEY,
        consumer_secret=settings.TWITTER_CONSUMER_SECRET,
        access_token=settings.TWITTER_ACCESS_TOKEN,
        access_token_secret=settings.TWITTER_ACCESS_TOKEN_SECRET,
        listener=listener
    )
    
    # TODO: Define keywords for streaming
    keywords = []
    stream.filter(track=keywords)