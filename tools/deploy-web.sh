#!/bin/sh
# Copies the built website (docs/) to https://coursetracker.online (Caddy on aurisse-mail serves /srv/cpl-tracker/web).
# GitHub Pages keeps a copy too (the app checks for updates there). Run after build.sh / publish-apk.sh.
set -e
cd "$(dirname "$0")/.."
tar -C docs -czf - . | ssh aurisse-mail 'cd /srv/cpl-tracker/web && find . -mindepth 1 -delete && tar -xzf - && chmod -R a+rX .'
echo "Website deployed to https://coursetracker.online"
