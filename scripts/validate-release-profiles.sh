#!/usr/bin/env sh
set -eu
python tools/profile_pipeline.py validate --lock OniBridge/bds.lock.json "$@"
