#!/bin/zsh
set -eu
resqmesh_project_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
exec "$resqmesh_project_dir/.venv/bin/python" "$resqmesh_project_dir/scripts/project_assistant.py"
