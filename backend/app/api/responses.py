from flask import Blueprint, request, jsonify
from flask_jwt_extended import jwt_required
from app.services.response_service import ResponseService
from app.schema.response import Response

responses_bp = Blueprint('responses', __name__)

@responses_bp.route('/responses', methods=['GET'])
@jwt_required
def get_responses():
    page = request.args.get('page', 1, type=int)
    per_page = request.args.get('per_page', 10, type=int)
    
    response_service = ResponseService()
    responses, pagination_info = response_service.get_paginated_responses(page, per_page)
    
    return jsonify({
        'responses': [response.to_dict() for response in responses],
        'pagination': pagination_info
    })

@responses_bp.route('/responses/<response_id>', methods=['GET'])
@jwt_required
def get_response(response_id):
    response_service = ResponseService()
    response = response_service.get_response_by_id(response_id)
    
    if response:
        return jsonify(response.to_dict())
    else:
        return jsonify({'error': 'Response not found'}), 404

@responses_bp.route('/responses', methods=['POST'])
@jwt_required
def generate_response():
    # HUMAN ASSISTANCE NEEDED
    # This function has a lower confidence level (0.7) and may need review
    tweet_id = request.json.get('tweet_id')
    
    if not tweet_id:
        return jsonify({'error': 'Tweet ID is required'}), 400
    
    response_service = ResponseService()
    generated_response = response_service.generate_response(tweet_id)
    
    if generated_response:
        return jsonify(generated_response.to_dict()), 201
    else:
        return jsonify({'error': 'Failed to generate response'}), 500

@responses_bp.route('/responses/<response_id>', methods=['PUT'])
@jwt_required
def update_response(response_id):
    update_data = request.json
    
    if not update_data:
        return jsonify({'error': 'Update data is required'}), 400
    
    response_service = ResponseService()
    updated_response = response_service.update_response(response_id, update_data)
    
    if updated_response:
        return jsonify(updated_response.to_dict())
    else:
        return jsonify({'error': 'Response not found or update failed'}), 404