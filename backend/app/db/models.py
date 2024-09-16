from sqlalchemy import Column, Integer, String, DateTime, Float, Boolean, ForeignKey
from sqlalchemy.orm import relationship
from sqlalchemy.ext.declarative import declarative_base

Base = declarative_base()

class Tweet(Base):
    __tablename__ = 'tweets'

    id = Column(Integer, primary_key=True)
    content = Column(String)
    like_count = Column(Integer)
    created_at = Column(DateTime)
    doubt_rating = Column(Float)
    media = Column(String)
    quoted_tweet_id = Column(String)
    user_id = Column(String)
    ai_tools_mentioned = Column(String)

class Response(Base):
    __tablename__ = 'responses'

    id = Column(Integer, primary_key=True)
    content = Column(String)
    generated_at = Column(DateTime)
    is_approved = Column(Boolean)
    tweet_id = Column(Integer, ForeignKey('tweets.id'))
    tweet = relationship("Tweet", back_populates="responses")

Tweet.responses = relationship("Response", order_by=Response.id, back_populates="tweet")

class AITool(Base):
    __tablename__ = 'ai_tools'

    id = Column(Integer, primary_key=True)
    name = Column(String)
    description = Column(String)

class Setting(Base):
    __tablename__ = 'settings'

    key = Column(String, primary_key=True)
    value = Column(String)
    description = Column(String)