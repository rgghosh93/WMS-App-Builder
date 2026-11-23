-- People signed in before last_sign_in_at existed.
UPDATE app_users SET last_sign_in_at = (SELECT MAX(s.created_at) FROM device_sessions s WHERE s.user_id = app_users.id)
WHERE last_sign_in_at IS NULL;
