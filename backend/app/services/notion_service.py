from notion_client import Client
from app.core.config import get_settings
from app.schema.tweet import Tweet as TweetSchema

class NotionService:
    def __init__(self):
        self.settings = get_settings()
        self.client = Client(auth=self.settings.NOTION_API_KEY)

    # HUMAN ASSISTANCE NEEDED
    # The following function has a confidence level of 0.7 and may need adjustments for production readiness
    def store_tweet(self, tweet: TweetSchema) -> dict:
        # Convert TweetSchema to Notion page properties
        properties = {
            "Content": {"title": [{"text": {"content": tweet.content}}]},
            "Author": {"rich_text": [{"text": {"content": tweet.author}}]},
            "Timestamp": {"date": {"start": tweet.timestamp.isoformat()}},
            "Sentiment": {"select": {"name": tweet.sentiment}},
            "Engagement": {"number": tweet.engagement}
        }

        # Create new page in Notion database
        response = self.client.pages.create(
            parent={"database_id": self.settings.NOTION_DATABASE_ID},
            properties=properties
        )

        return response

    # HUMAN ASSISTANCE NEEDED
    # The following function has a confidence level of 0.7 and may need adjustments for production readiness
    def get_tweets(self, limit: int = 10, start_cursor: str = None) -> List[TweetSchema]:
        # Query Notion database with pagination
        response = self.client.databases.query(
            database_id=self.settings.NOTION_DATABASE_ID,
            start_cursor=start_cursor,
            page_size=limit
        )

        # Convert Notion pages to TweetSchema objects
        tweets = []
        for page in response["results"]:
            properties = page["properties"]
            tweet = TweetSchema(
                content=properties["Content"]["title"][0]["text"]["content"],
                author=properties["Author"]["rich_text"][0]["text"]["content"],
                timestamp=properties["Timestamp"]["date"]["start"],
                sentiment=properties["Sentiment"]["select"]["name"],
                engagement=properties["Engagement"]["number"]
            )
            tweets.append(tweet)

        return tweets