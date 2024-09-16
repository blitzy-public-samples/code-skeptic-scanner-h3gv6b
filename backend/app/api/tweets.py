from flask import Blueprint, request, jsonify
from flask_jwt_extended import jwt_required
from app.services.twitter_service import TwitterService
from app.services.sentiment_analysis import SentimentAnalysis
from app.schema.tweet import Tweet

tweets_bp = Blueprint('tweets', __name__)

@tweets_bp.route('/tweets', methods=['GET'])
@jwt_required
def get_tweets():
    page = request.args.get('page', 1, type=int)
    per_page = request.args.get('per_page', 10, type=int)
    
    twitter_service = TwitterService()
    tweets, pagination = twitter_service.get_paginated_tweets(page, per_page)
    
    return jsonify({
        'tweets': [tweet.to_dict() for tweet in tweets],
        'pagination': pagination
    })

@tweets_bp.route('/tweets/<tweet_id>', methods=['GET'])
@jwt_required
def get_tweet(tweet_id):
    twitter_service = TwitterService()
    tweet = twitter_service.get_tweet(tweet_id)
    
    if tweet:
        return jsonify(tweet.to_dict())
    else:
        return jsonify({'error': 'Tweet not found'}), 404

# HUMAN ASSISTANCE NEEDED
# The following function has a confidence level below 0.8 and may need review
@tweets_bp.route('/tweets/<tweet_id>/analyze', methods=['POST'])
@jwt_required
def analyze_tweet(tweet_id):
    twitter_service = TwitterService()
    tweet = twitter_service.get_tweet(tweet_id)
    
    if not tweet:
        return jsonify({'error': 'Tweet not found'}), 404
    
    sentiment_analysis = SentimentAnalysis()
    analysis_result = sentiment_analysis.analyze(tweet.content)
    
    # Update tweet with analysis results
    # Note: This step might need to be implemented in the TwitterService
    twitter_service.update_tweet_analysis(tweet_id, analysis_result)
    
    return jsonify({
        'tweet_id': tweet_id,
        'analysis_result': analysis_result
    })