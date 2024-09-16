from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from app.core.config import get_settings

def get_db_connection():
    settings = get_settings()
    engine = create_engine(settings.DATABASE_URL)
    return engine

def get_db_session():
    engine = get_db_connection()
    SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
    return SessionLocal()