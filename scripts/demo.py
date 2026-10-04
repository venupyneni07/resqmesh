#!/usr/bin/env python3
"""Start/inspect the local ResQMesh demo without cloud credentials."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
from urllib.error import URLError
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / '.runtime'


def get_json(url: str, timeout: float = 3):
    try:
        with urlopen(url, timeout=timeout) as response:
            return json.load(response)
    except (URLError, OSError, ValueError):
        return None


def spawn(name: str, command: list[str], extra_env: dict[str, str] | None = None):
    RUNTIME.mkdir(exist_ok=True)
    with (RUNTIME / f'{name}.log').open('ab') as log:
        process = subprocess.Popen(
            command, cwd=ROOT, env={**os.environ, **(extra_env or {})},
            stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
            start_new_session=True,
        )
    (RUNTIME / f'{name}.pid').write_text(str(process.pid))
    return process


def wait_for(url: str, process, seconds: int = 30):
    for _ in range(seconds):
        result = get_json(url)
        if result is not None:
            return result
        if process.poll() is not None:
            raise RuntimeError(f'Service exited. See logs in {RUNTIME}')
        time.sleep(1)
    raise RuntimeError(f'Service did not become ready: {url}. See {RUNTIME}')


def start(bind: str):
    python = ROOT / '.venv/bin/python'
    if not python.exists():
        raise RuntimeError('Missing .venv. Install backend/requirements.txt in a Python 3.12 virtual environment.')
    ollama_url = os.environ.get('OLLAMA_BASE_URL', 'http://127.0.0.1:11434').rstrip('/')
    if get_json(ollama_url + '/api/version') is None:
        if ollama_url != 'http://127.0.0.1:11434':
            raise RuntimeError('Configured remote Ollama endpoint is unavailable; will not start a replacement.')
        binary = shutil.which('ollama')
        fallback = Path('/opt/homebrew/opt/ollama/bin/ollama')
        if not binary and fallback.exists():
            binary = str(fallback)
        if not binary:
            raise RuntimeError('Ollama is not installed. See README.md.')
        process = spawn('ollama', [binary, 'serve'], {
            'OLLAMA_HOST': '127.0.0.1:11434',
            'OLLAMA_NUM_PARALLEL': '1',
            'OLLAMA_MAX_LOADED_MODELS': '1',
            'OLLAMA_CONTEXT_LENGTH': '4096',
        })
        wait_for(ollama_url + '/api/version', process)
    model = os.environ.get('OLLAMA_MODEL', 'gemma4:e2b')
    tags = get_json(ollama_url + '/api/tags') or {}
    if not any(m.get('name') == model for m in tags.get('models', [])):
        print(f'WARNING: {model} is not downloaded. Reports will remain available, but AI needs the model.')
    health = get_json('http://127.0.0.1:8000/api/health')
    if health is None:
        process = spawn('backend', [str(python), '-m', 'uvicorn', 'backend.app:app', '--host', bind, '--port', '8000'])
        health = wait_for('http://127.0.0.1:8000/api/health', process)
    print(json.dumps(health, indent=2))
    print('Responder dashboard: http://127.0.0.1:8000')
    print('Android emulator backend: http://10.0.2.2:8000')
    print('Runtime logs:', RUNTIME)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['start', 'status'], nargs='?', default='start')
    parser.add_argument('--bind', default='127.0.0.1', help='Use 0.0.0.0 explicitly for a trusted local LAN phone demo.')
    args = parser.parse_args()
    if args.action == 'status':
        print(json.dumps({
            'backend': get_json('http://127.0.0.1:8000/api/health'),
            'ollama': get_json(os.environ.get('OLLAMA_BASE_URL', 'http://127.0.0.1:11434').rstrip('/') + '/api/version'),
        }, indent=2))
    else:
        start(args.bind)


if __name__ == '__main__':
    try:
        main()
    except RuntimeError as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
