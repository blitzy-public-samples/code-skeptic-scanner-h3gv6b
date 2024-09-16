from flask import Blueprint, request, jsonify
from flask_jwt_extended import jwt_required
from app.services.settings_service import SettingsService

settings_bp = Blueprint('settings', __name__)

@settings_bp.route('/settings', methods=['GET'])
@jwt_required
def get_settings():
    settings = SettingsService.get_all_settings()
    return jsonify(settings)

@settings_bp.route('/settings/<key>', methods=['PUT'])
@jwt_required
def update_setting(key):
    new_value = request.json.get('value')
    if new_value is None:
        return jsonify({'error': 'No value provided'}), 400
    
    updated_setting = SettingsService.update_setting(key, new_value)
    if updated_setting is None:
        return jsonify({'error': 'Setting not found'}), 404
    
    return jsonify(updated_setting)