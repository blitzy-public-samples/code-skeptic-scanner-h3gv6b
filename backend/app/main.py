from flask import Flask
from flask_cors import CORS
from flask_jwt_extended import JWTManager
from app.api.tweets import tweets_bp
from app.api.responses import responses_bp
from app.api.settings import settings_bp
from app.api.analytics import analytics_bp
from app.core.config import get_settings
from app.db.database import get_db_connection
from app.tasks.tweet_monitoring import start_tweet_stream
from app.tasks.response_generation import schedule_response_generation

app = Flask(__name__)

def create_app():
    settings = get_settings()
    
    app.config.from_object(settings)
    
    CORS(app)
    
    jwt = JWTManager(app)
    
    app.db = get_db_connection()
    
    app.register_blueprint(tweets_bp)
    app.register_blueprint(responses_bp)
    app.register_blueprint(settings_bp)
    app.register_blueprint(analytics_bp)
    
    @app.errorhandler(404)
    def not_found(error):
        return {"error": "Not found"}, 404
    
    @app.errorhandler(500)
    def internal_error(error):
        return {"error": "Internal server error"}, 500
    
    return app

# HUMAN ASSISTANCE NEEDED
# The following function has a confidence level below 0.8 and may need adjustments for production readiness
def initialize_background_tasks():
    # Start tweet monitoring stream in a separate thread
    start_tweet_stream()
    
    # Schedule response generation task
    schedule_response_generation()

if __name__ == "__main__":
    app = create_app()
    initialize_background_tasks()
    app.run(debug=True)