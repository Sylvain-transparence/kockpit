#!/bin/sh

# Replace KOCKPIT_BACKEND placeholder in nginx config
if [ ! -z "$KOCKPIT_BACKEND" ]; then
  echo "Replacing __KOCKPIT_BACKEND__ with $KOCKPIT_BACKEND in nginx config"
  sed -i "s|__KOCKPIT_BACKEND__|$KOCKPIT_BACKEND|g" /etc/nginx/conf.d/default.conf
else
  echo "Warning: KOCKPIT_BACKEND environment variable not set, using default"
  sed -i "s|__KOCKPIT_BACKEND__|http://localhost:8080|g" /etc/nginx/conf.d/default.conf
fi

# Replace VITE_API_BASE placeholder in config.js if it exists
if [ ! -z "$VITE_API_BASE" ] && [ -f "/usr/share/nginx/html/config.js" ]; then
  echo "Replacing VITE_API_BASE placeholder in config.js"
  sed -i "s|\${VITE_API_BASE}|$VITE_API_BASE|g" /usr/share/nginx/html/config.js
fi

if [ ! -z "$FEATURE_FLIPPING_REFRESH_MS" ] && [ -f "/usr/share/nginx/html/config.js" ]; then
  echo "Feature flipping refresh: $FEATURE_FLIPPING_REFRESH_MS ms"
  sed -i "s|\${FEATURE_FLIPPING_REFRESH_MS}|$FEATURE_FLIPPING_REFRESH_MS|g" /usr/share/nginx/html/config.js
fi

# Start the passed command (nginx)
exec "$@"
