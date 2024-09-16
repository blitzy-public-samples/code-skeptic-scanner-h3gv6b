import React from 'react';
import { Settings } from '../components/Settings';

const SettingsPage: React.FC = () => {
  return (
    <div className="settings-page">
      <h1>Settings</h1>
      <Settings />
    </div>
  );
};

export default SettingsPage;