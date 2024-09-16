from celery import Celery
from app.core.config import get_settings
from app.services.llm_service import LLMService
from app.services.notion_service import NotionService
from app.db.models import Tweet, Response
from app.schema.response import Response as ResponseSchema

celery_app = Celery('code_skeptic_scanner')

@celery_app.task
def generate_response(tweet_id: str) -> ResponseSchema:
    # HUMAN ASSISTANCE NEEDED
    # This function needs review and potential modifications for production readiness
    
    # Retrieve tweet from database
    tweet = Tweet.get(tweet_id)
    
    # Initialize LLMService
    llm_service = LLMService()
    
    # Generate response using LLM
    response_text = llm_service.generate_response(tweet.content)
    
    # Create Response object and store in database
    response = Response(tweet_id=tweet_id, content=response_text)
    response.save()
    
    # Update Notion database with response
    notion_service = NotionService()
    notion_service.update_tweet_response(tweet_id, response_text)
    
    # Return generated response
    return ResponseSchema(id=response.id, content=response.content)

def schedule_response_generation():
    # HUMAN ASSISTANCE NEEDED
    # This function needs review and potential modifications for production readiness
    
    settings = get_settings()
    
    while True:
        # Query database for tweets without responses
        tweets_without_responses = Tweet.query.filter(Tweet.response == None).all()
        
        # Schedule response generation tasks for each tweet
        for tweet in tweets_without_responses:
            generate_response.delay(tweet.id)
        
        # Sleep for specified interval before next check
        time.sleep(settings.response_generation_interval)