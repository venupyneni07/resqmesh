#!/usr/bin/env python3
"""Provision local responder accounts or rotate a node-bound gateway key."""
import argparse
import getpass
import os
from pathlib import Path
import secrets
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from backend.security import SecurityStore


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", required=True)
    commands = parser.add_subparsers(dest="command", required=True)
    account = commands.add_parser("user")
    account.add_argument("name")
    account.add_argument("--role", choices=["viewer", "responder", "admin"], required=True)
    account.add_argument("--password-file", type=Path)
    disable = commands.add_parser("disable")
    disable.add_argument("name")
    gateway = commands.add_parser("gateway")
    gateway.add_argument("node_id")
    gateway.add_argument("--key-file", type=Path, required=True)
    args = parser.parse_args()
    store = SecurityStore(args.database)
    if args.command == "user":
        password = args.password_file.read_text().rstrip("\r\n") if args.password_file else getpass.getpass("New password (14+ characters): ")
        store.set_user(args.name, password, args.role)
        print("Account provisioned; previous sessions revoked.")
    elif args.command == "disable":
        store.disable_user(args.name)
        print("Account disabled; sessions revoked.")
    else:
        # Never replace an existing credential file accidentally or print the secret.
        value = secrets.token_urlsafe(32)
        fd = os.open(args.key_file, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(fd, "w") as file:
            file.write(value + "\n")
        store.register_gateway(args.node_id, value)
        print("Gateway key saved in the requested private file. Configure only that device.")


if __name__ == "__main__":
    main()
