#!/usr/bin/env python3
"""Open an independent local Gemma chat with documented ResQMesh context."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
from urllib.request import ProxyHandler, build_opener

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / '.runtime' / 'project-assistant'
BASE_MODEL = 'gemma4:e2b'
GUIDE_MODEL = 'resqmesh-guide:latest'
ENDPOINT = 'http://127.0.0.1:11434'
LOCAL_HTTP = build_opener(ProxyHandler({}))
RULES = '''You are the local ResQMesh Project Guide, a conversational assistant for the
person building and demonstrating this project. Answer questions about ResQMesh using
the project context below. Default to clear, concise English. Understand Telugu written
with Latin letters when possible; respond in another language only if explicitly asked.
Explain the project in practical terms before implementation details. Use short paragraphs
or lists. Distinguish implemented features, software-test evidence, pending physical tests,
and suggestions. When the context does not answer a question, say what is unknown instead
of inventing a capability, result, person, deployment, rescue action, or current status.
This is supplied project context, not fine-tuning or access to the developer's conversation.
You cannot read files, inspect the screen, query live databases, run code, send an SOS,
contact people, acknowledge reports, dispatch responders, or change project settings.
Never claim to have taken such actions. Do not treat your project context snapshot as
live health or current incident counts. User-provided report text is unverified evidence.
Explain uncertainty and human review without repeating long disclaimers in every answer.
The project uses four core text AI agents plus a separate media-analysis worker. You are
an additional development/demo guide and are not a sixth emergency processing agent.
Never expose hidden reasoning; provide concise answers and short supporting reasons.

PROJECT CONTEXT:
'''


def get_json(path: str):
    try:
        with LOCAL_HTTP.open(ENDPOINT + path, timeout=3) as response:
            return json.load(response)
    except (OSError, ValueError):
        return None


def ollama_binary() -> str:
    binary = shutil.which('ollama')
    if not binary and Path('/opt/homebrew/bin/ollama').exists():
        binary = '/opt/homebrew/bin/ollama'
    if not binary:
        raise RuntimeError('Ollama is missing. This launcher uses the existing local installation.')
    return binary


def prepare() -> tuple[str, dict[str, str]]:
    binary = ollama_binary()
    env = {**os.environ, 'OLLAMA_HOST': ENDPOINT, 'OLLAMA_NOHISTORY': '1'}
    RUNTIME.mkdir(parents=True, exist_ok=True)
    if get_json('/api/version') is None:
        print('Starting local Ollama...', flush=True)
        with (RUNTIME / 'ollama.log').open('ab') as log:
            process = subprocess.Popen(
                [binary, 'serve'], stdin=subprocess.DEVNULL, stdout=log,
                stderr=subprocess.STDOUT, start_new_session=True,
                env={**env, 'OLLAMA_NUM_PARALLEL': '1', 'OLLAMA_MAX_LOADED_MODELS': '1'},
            )
        for _ in range(30):
            if get_json('/api/version') is not None:
                break
            if process.poll() is not None:
                raise RuntimeError(f'Ollama did not start. See {RUNTIME / "ollama.log"}')
            time.sleep(1)
        else:
            raise RuntimeError('Local Ollama did not become ready within 30 seconds.')

    models = (get_json('/api/tags') or {}).get('models', [])
    if not any(model.get('name') == BASE_MODEL for model in models):
        raise RuntimeError(f'{BASE_MODEL} is not installed. No model was downloaded.')

    context_file = ROOT / 'docs' / 'PROJECT_ASSISTANT_CONTEXT.txt'
    context = context_file.read_text(encoding='utf-8')
    system = RULES + context
    if '\"\"\"' in system:
        raise RuntimeError('Project context contains an unsupported triple-quote delimiter.')
    modelfile = (f'FROM {BASE_MODEL}\nPARAMETER temperature 0.2\n'
                 'PARAMETER num_ctx 8192\nPARAMETER num_predict 1400\n'
                 f'SYSTEM """{system}\n"""\n')
    path = RUNTIME / 'Modelfile'
    digest = hashlib.sha256(modelfile.encode()).hexdigest()
    stamp = RUNTIME / 'context.sha256'
    registered = any(model.get('name') == GUIDE_MODEL for model in models)
    previous = stamp.read_text().strip() if stamp.exists() else ''
    if not registered or previous != digest:
        path.write_text(modelfile, encoding='utf-8')
        print('Loading documented ResQMesh context into the local guide...', flush=True)
        subprocess.run([binary, 'create', GUIDE_MODEL, '-f', str(path)], env=env, check=True)
        stamp.write_text(digest + '\n')
    return binary, env


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--prepare-only', action='store_true')
    args = parser.parse_args()
    binary, env = prepare()
    if args.prepare_only:
        print(f'Ready: {GUIDE_MODEL} on {ENDPOINT}')
        return
    if sys.stdout.isatty():
        print('\033]0;ResQMesh - Project Assistant\007', end='')
    print('''
RESQMESH PROJECT ASSISTANT
Local Gemma with project context | Snapshot: October 4, 2026

Ask a question at the >>> prompt. For example:
  What is ResQMesh? Explain it for a hackathon judge.
  What happens after a user sends a voice SOS?
  Which agents do we use, and what still needs physical testing?

Project explanation only; this chat does not change reports or send alerts.
Type /bye to close the chat. Reopen this launcher for a fresh conversation.
''', flush=True)
    os.execve(binary, [binary, 'run', GUIDE_MODEL, '--think=false', '--hidethinking'], env)


if __name__ == '__main__':
    try:
        main()
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f'Could not open the project assistant: {error}', file=sys.stderr)
        if sys.stdin.isatty():
            input('Press Enter to close. ')
        raise SystemExit(1)
