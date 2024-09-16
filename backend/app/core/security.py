from jwt import encode, decode
from datetime import datetime, timedelta
from passlib.hash import bcrypt
from app.core.config import get_settings

def create_access_token(data: dict, expires_delta: timedelta) -> str:
    settings = get_settings()
    to_encode = data.copy()
    expire = datetime.utcnow() + expires_delta
    to_encode.update({"exp": expire})
    encoded_jwt = encode(to_encode, settings.SECRET_KEY, algorithm=settings.ALGORITHM)
    return encoded_jwt

def verify_password(plain_password: str, hashed_password: str) -> bool:
    return bcrypt.verify(plain_password, hashed_password)

def get_password_hash(password: str) -> str:
    return bcrypt.hash(password)