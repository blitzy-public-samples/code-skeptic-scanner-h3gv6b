from openai import Completion
from app.core.config import get_settings
from app.schema.tweet import Tweet as TweetSchema
from app.schema.response import Response as ResponseSchema

class LLMService:
    def __init__(self):
        self.settings = get_settings()
        Completion.api_key = self.settings.openai_api_key

    # HUMAN ASSISTANCE NEEDED
    # The following function has a low confidence score (0.6) and may need refinement for production use.
    # Consider reviewing and improving the prompt preparation, API call, and response processing.
    def generate_response(self, tweet: TweetSchema) -> ResponseSchema:
        # Prepare prompt
        prompt = f"Generate a response to the following tweet: '{tweet.content}'\n\nContext: {tweet.context}\n\nResponse:"

        # Call OpenAI API
        response = Completion.create(
            engine="text-davinci-002",
            prompt=prompt,
            max_tokens=150,
            n=1,
            stop=None,
            temperature=0.7,
        )

        # Process API response
        generated_text = response.choices[0].text.strip()

        # Create and return ResponseSchema object
        return ResponseSchema(content=generated_text, tweet_id=tweet.id)

# HUMAN ASSISTANCE NEEDED
# The overall class has a confidence score of 0.7, which is below the threshold of 0.8.
# Consider reviewing the entire class implementation, especially error handling,
# API rate limiting, and any additional methods that might be necessary for a production environment.