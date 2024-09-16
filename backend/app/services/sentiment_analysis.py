from google.cloud.language import LanguageServiceClient
from app.core.config import get_settings
from app.schema.tweet import Tweet as TweetSchema

class SentimentAnalysis:
    def __init__(self):
        self.settings = get_settings()
        self.client = LanguageServiceClient()

    # HUMAN ASSISTANCE NEEDED
    # The following function has a confidence level below 0.8 and may need refinement
    def analyze_sentiment(self, tweet: TweetSchema) -> float:
        # Prepare tweet content for analysis
        content = tweet.text

        # Call Google Cloud Natural Language API
        document = {"content": content, "type_": "PLAIN_TEXT", "language": "en"}
        response = self.client.analyze_sentiment(request={"document": document})

        # Process API response
        sentiment = response.document_sentiment

        # Return sentiment score
        return sentiment.score

    def calculate_doubt_rating(self, sentiment_score: float) -> float:
        # Convert sentiment score to doubt rating scale
        # Sentiment score ranges from -1 to 1, we need to convert it to 0 to 10
        doubt_rating = (1 - sentiment_score) * 5

        # Apply any additional adjustments or thresholds
        doubt_rating = max(0, min(10, doubt_rating))  # Ensure the rating is between 0 and 10

        # Return calculated doubt rating
        return doubt_rating