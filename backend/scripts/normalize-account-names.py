#!/usr/bin/env python3
"""Backfill display names using the application's Java formatter.

Dry run by default. Use --apply to update, with an automatic private JSON backup.
Connection options: MYSQL_HOST, MYSQL_PORT, MYSQL_USER, MYSQL_DATABASE, MYSQL_PWD.
Requires a JDK and the mariadb client. Never changes usernames or message history.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import subprocess
import tempfile
from datetime import datetime, timezone


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    command = ['mariadb', '--batch', '--raw', '--skip-column-names', '--default-character-set=utf8mb4',
               '--host', os.environ.get('MYSQL_HOST', '127.0.0.1'),
               '--port', os.environ.get('MYSQL_PORT', '3306'),
               '--user', os.environ.get('MYSQL_USER', 'chatllm'),
               os.environ.get('MYSQL_DATABASE', 'chatllm')]

    def query(sql):
        return subprocess.run(command, input=sql, text=True, check=True, capture_output=True).stdout

    rows = [json.loads(line) for line in query(
        "SELECT JSON_OBJECT('id', userUUID, 'displayName', displayName) FROM user WHERE displayName IS NOT NULL;"
    ).splitlines()]
    if not rows:
        print(json.dumps({'checked': 0, 'changes': 0, 'applied': False}))
        return
    formatter = Path(__file__).resolve().parents[1] / 'src/main/java/com/eonmux/chatllm/utils/DisplayNameFormatter.java'
    with tempfile.TemporaryDirectory(prefix='chatllm-name-formatter-') as directory:
        bridge = Path(directory) / 'FormatNames.java'
        bridge.write_text('''
import com.eonmux.chatllm.utils.DisplayNameFormatter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
public class FormatNames {
  public static void main(String[] args) throws Exception {
    var lines = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    for (String line; (line = lines.readLine()) != null;) {
      String name = new String(Base64.getDecoder().decode(line), StandardCharsets.UTF_8);
      System.out.println(Base64.getEncoder().encodeToString(DisplayNameFormatter.normalize(name).getBytes(StandardCharsets.UTF_8)));
    }
  }
}
''', encoding='utf-8')
        subprocess.run(['javac', '-encoding', 'UTF-8', '-d', directory, str(formatter), str(bridge)], check=True, capture_output=True)
        batch = ''.join(base64.b64encode(row['displayName'].encode()).decode() + '\n' for row in rows)
        output = subprocess.run(['java', '-cp', directory, 'FormatNames'], input=batch, text=True, check=True, capture_output=True).stdout
        names = [base64.b64decode(line).decode() for line in output.splitlines()]
    if len(names) != len(rows):
        raise RuntimeError('Formatter returned an unexpected number of results')
    changes = [dict(row, normalized=name) for row, name in zip(rows, names) if row['displayName'] != name]
    summary = {'checked': len(rows), 'changes': len(changes), 'applied': False}
    if args.apply and changes:
        stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
        backup = Path(tempfile.gettempdir()) / f'chatllm-name-backfill-{stamp}.json'
        with os.fdopen(os.open(backup, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as file:
            json.dump(changes, file, ensure_ascii=False, indent=2)
        def literal(value):
            return "CONVERT(X'" + value.encode().hex() + "' USING utf8mb4)"
        statements = ['START TRANSACTION;']
        for row in changes:
            # Compare the exact old value, so a concurrent profile edit is never overwritten.
            statements.append(f"UPDATE user SET displayName = {literal(row['normalized'])} WHERE userUUID = {literal(row['id'])} AND BINARY displayName = BINARY {literal(row['displayName'])};")
            statements.append('SELECT ROW_COUNT();')
        statements.append('COMMIT;')
        updated = sum(int(line) for line in query('\n'.join(statements)).splitlines())
        summary.update(applied=True, updated=updated, skipped=len(changes)-updated, backup=str(backup))
    print(json.dumps(summary))


if __name__ == '__main__':
    main()
