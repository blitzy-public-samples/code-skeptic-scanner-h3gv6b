import React, { useState, useEffect } from 'react';
import { getSettings, updateSetting } from '../services/api';

// HUMAN ASSISTANCE NEEDED
// The following component might need additional refinement for production readiness.
// Please review and adjust as necessary, especially error handling and UI/UX improvements.

const Settings: React.FC = () => {
  const [settings, setSettings] = useState<Record<string, any>>({});
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    fetchSettings();
  }, []);

  const fetchSettings = async () => {
    try {
      const fetchedSettings = await getSettings();
      setSettings(fetchedSettings);
    } catch (error) {
      setMessage('Error fetching settings. Please try again.');
    }
  };

  const handleSettingChange = async (key: string, value: any) => {
    try {
      await updateSetting(key, value);
      setSettings(prevSettings => ({ ...prevSettings, [key]: value }));
      setMessage('Setting updated successfully');
    } catch (error) {
      setMessage('Error updating setting. Please try again.');
    }
  };

  return (
    <div className="settings-container">
      <h2>Application Settings</h2>
      {message && <div className="message">{message}</div>}
      <form>
        {Object.entries(settings).map(([key, value]) => (
          <div key={key} className="setting-item">
            <label htmlFor={key}>{key}</label>
            <input
              id={key}
              type={typeof value === 'boolean' ? 'checkbox' : 'text'}
              checked={typeof value === 'boolean' ? value : undefined}
              value={typeof value !== 'boolean' ? value : undefined}
              onChange={(e) => handleSettingChange(key, e.target.type === 'checkbox' ? e.target.checked : e.target.value)}
            />
          </div>
        ))}
      </form>
    </div>
  );
};

export default Settings;