#!/usr/bin/env python3
"""Build and smoke-test the root Compose application with disposable state."""
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]


def main():
    evidence = ROOT / ".runtime" / "review"
    evidence.mkdir(parents=True, exist_ok=True)
    output = Path(tempfile.mkdtemp(prefix="core-compose-", dir=evidence))
    project = "veyloria-core-review-" + secrets.token_hex(5)
    # Never load the operator's root .env or inherit their app/provider settings.
    env = {
        key: value for key, value in os.environ.items()
        if key in {"PATH", "HOME", "LANG", "XDG_RUNTIME_DIR", "SSH_AUTH_SOCK"}
        or key.startswith("DOCKER_")
    }
    env.update(
        ENVIRONMENT="production", DEVELOPMENT_MODE="false", AUTH_BYPASS_ENABLED="false",
        SHOW_GRAPHQL_ERROR_DETAILS="false", LLM_ENABLED="true", LLM_MOCK_MODE="true",
        LLM_EXTERNAL_SERVER="true", COMFYUI_ENABLED="false",
        JWT_SECRET=secrets.token_urlsafe(48),
        MYSQL_ROOT_PASSWORD=secrets.token_hex(20),
        MYSQL_PASSWORD=secrets.token_hex(20),
        REDIS_PASSWORD=secrets.token_hex(20),
    )
    config = json.loads(subprocess.check_output(
        ["docker", "compose", "--env-file", ".env.example", "config", "--format", "json"],
        cwd=ROOT, env=env,
    ))
    config.pop("name", None)
    # Rendered Compose has explicit resource names: remove them to isolate this run.
    for section in ("volumes", "networks"):
        for resource in config.get(section, {}).values():
            resource.pop("name", None)
    allowed_binds = {
        (ROOT / "scripts/init.sql").resolve(),
        (ROOT / "backend/src/main/resources/db/schema.sql").resolve(),
    }
    for name in ("mysql", "redis", "backend", "frontend"):
        for mount in config["services"][name].get("volumes", []):
            if mount.get("type") == "bind" and (
                Path(mount["source"]).resolve() not in allowed_binds or not mount.get("read_only")
            ):
                raise RuntimeError(f"Refusing an unexpected host bind mount in {name}")
    images = []
    for name, service in config["services"].items():
        service.pop("container_name", None)
        service.pop("ports", None)
        if name in {"backend", "frontend"}:
            service["image"] = project + "-" + name + ":local"
            images.append(service["image"])
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    config["services"]["frontend"]["ports"] = [{
        "target": 80, "published": str(port), "host_ip": "127.0.0.1", "protocol": "tcp",
    }]
    config_file = output / "compose.json"
    with open(config_file, "x", opener=lambda path, flags: os.open(path, flags, 0o600)) as stream:
        json.dump(config, stream)
    base = ["docker", "compose", "--project-name", project, "-f", str(config_file)]
    log_file = output / "compose.log"
    print(f"Building and testing disposable core services. Evidence: {output}", flush=True)
    try:
        with log_file.open("w") as log:
            try:
                subprocess.run(base + [
                    "up", "-d", "--build", "--wait", "--wait-timeout", "180",
                    "mysql", "redis", "backend", "frontend",
                ], cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
                origin = f"http://127.0.0.1:{port}"
                for route in ("/health", "/"):
                    with urlopen(origin + route, timeout=20) as response:
                        if response.status != 200:
                            raise RuntimeError(f"Unexpected status for {route}: {response.status}")
                request = Request(origin + "/graphql", data=b'{"query":"{ __typename }"}',
                                  headers={"Content-Type": "application/json", "Origin": "http://localhost"})
                with urlopen(request, timeout=20) as response:
                    if response.headers.get("Access-Control-Allow-Origin") != "http://localhost":
                        raise RuntimeError("Default frontend origin was not accepted by CORS")
                    result = json.load(response)
                if result.get("data", {}).get("__typename") != "Query":
                    raise RuntimeError("GraphQL proxy check did not return the expected query type")
                (output / "result.json").write_text(json.dumps({
                    "passed": True,
                    "checks": ["fresh images", "fresh schema and grants", "backend startup",
                               "frontend", "GraphQL proxy", "default-origin CORS"],
                }, indent=2) + "\n")
            except Exception:
                subprocess.run(base + ["logs", "--no-color", "--tail", "100"], cwd=ROOT,
                               env=env, stdout=log, stderr=subprocess.STDOUT, check=False)
                raise
            finally:
                subprocess.run(base + ["down", "--volumes", "--remove-orphans"], cwd=ROOT,
                               env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
                subprocess.run(["docker", "image", "rm", "--no-prune", *images], env=env,
                               stdout=log, stderr=subprocess.STDOUT, check=False)
    finally:
        config_file.unlink(missing_ok=True)
    print("PASS: clean core Compose startup, schema/grants, frontend and GraphQL proxy")


if __name__ == "__main__":
    main()
