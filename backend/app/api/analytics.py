from flask import Blueprint, jsonify
from flask_jwt_extended import jwt_required
from app.services.analytics_service import AnalyticsService

analytics_bp = Blueprint('analytics', __name__)

@analytics_bp.route('/analytics/trends', methods=['GET'])
@jwt_required
def get_trends():
    # HUMAN ASSISTANCE NEEDED
    # This function needs review to ensure it's production-ready
    # and correctly implements the AnalyticsService
    analytics_service = AnalyticsService()
    trend_data = analytics_service.get_trends()
    return jsonify(trend_data)

@analytics_bp.route('/analytics/summary', methods=['GET'])
@jwt_required
def get_summary():
    # HUMAN ASSISTANCE NEEDED
    # This function needs review to ensure it's production-ready
    # and correctly implements the AnalyticsService
    analytics_service = AnalyticsService()
    summary_data = analytics_service.get_summary()
    return jsonify(summary_data)