import pytest
from fastapi.testclient import TestClient
from app.main import app

client = TestClient(app)

# Test cases for tweet-related endpoints
def test_create_tweet():
    response = client.post("/tweets/", json={"content": "Test tweet"})
    assert response.status_code == 201
    assert response.json()["content"] == "Test tweet"

def test_get_tweet():
    # Assuming a tweet with id 1 exists
    response = client.get("/tweets/1")
    assert response.status_code == 200
    assert "content" in response.json()

def test_delete_tweet():
    # Assuming a tweet with id 1 exists
    response = client.delete("/tweets/1")
    assert response.status_code == 204

# Test cases for response-related endpoints
def test_create_response():
    response = client.post("/responses/", json={"tweet_id": 1, "content": "Test response"})
    assert response.status_code == 201
    assert response.json()["content"] == "Test response"

def test_get_responses():
    response = client.get("/tweets/1/responses")
    assert response.status_code == 200
    assert isinstance(response.json(), list)

# Test cases for settings-related endpoints
def test_get_settings():
    response = client.get("/settings/")
    assert response.status_code == 200
    assert "auto_response" in response.json()

def test_update_settings():
    response = client.put("/settings/", json={"auto_response": True})
    assert response.status_code == 200
    assert response.json()["auto_response"] == True

# Test cases for analytics-related endpoints
def test_get_analytics():
    response = client.get("/analytics/")
    assert response.status_code == 200
    assert "total_tweets" in response.json()
    assert "total_responses" in response.json()

# HUMAN ASSISTANCE NEEDED
# The following test cases might need to be adjusted based on the actual implementation of the analytics endpoint
def test_get_analytics_by_date_range():
    response = client.get("/analytics/?start_date=2023-01-01&end_date=2023-12-31")
    assert response.status_code == 200
    assert "total_tweets" in response.json()
    assert "total_responses" in response.json()