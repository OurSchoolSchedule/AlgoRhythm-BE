"""Convert a Secrets Manager JSON object on stdin to a Docker env-file, without logging values."""
import json
import re
import sys

REQUIRED = {
    "SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD",
    "REDISHOST", "REDISPORT", "REDISPASSWORD", "JWT_SECRET", "RESEND_API_KEY", "RESEND_FROM_EMAIL",
}
KAKAO = {"KAKAO_CLIENT_ID", "KAKAO_REDIRECT_URI"}
RESET = {"DEMO_RESET_DATABASE", "DEMO_RESET_OPERATOR", "DEMO_RESET_PIN_HASH"}
OPTIONAL = KAKAO | RESET | {
    "KAKAO_CLIENT_SECRET", "OPENAI_API_KEY", "SCHOOL42_ADMIN_HASH", "SCHOOL42_TEACHER_HASH",
    "SPRING_DATA_REDIS_USERNAME",
}


def unique(items):
    if len(items) != len(dict(items)):
        raise ValueError("Duplicate runtime key")
    return dict(items)


def validate(config):
    if not isinstance(config, dict) or config.keys() - REQUIRED - OPTIONAL:
        raise ValueError("Expected a JSON object containing only documented runtime keys")
    for key, value in config.items():
        if not isinstance(value, str) or not value.strip() or any(ord(c) < 32 for c in value):
            raise ValueError(f"Expected a nonempty, single-line string for {key}")
    missing = REQUIRED - config.keys()
    if missing:
        raise ValueError("Missing: " + ", ".join(sorted(missing)))
    for group in (KAKAO, RESET):
        if config.keys() & group and not group <= config.keys():
            raise ValueError("Configure the entire group: " + ", ".join(sorted(group)))
    if "KAKAO_CLIENT_SECRET" in config and not KAKAO <= config.keys():
        raise ValueError("Kakao secret requires client ID and callback")
    if "KAKAO_REDIRECT_URI" in config and config["KAKAO_REDIRECT_URI"] != "https://edu.rssolplan.com/api/auth/kakao/callback":
        raise ValueError("Unexpected Kakao callback")
    db = re.fullmatch(r"jdbc:mysql://[a-zA-Z0-9.-]+(?::3306)?/([a-zA-Z0-9_]+)", config["SPRING_DATASOURCE_URL"])
    if not db:
        raise ValueError("Expected a MySQL endpoint and schema without JDBC overrides")
    if config.get("DEMO_RESET_DATABASE", db[1]) != db[1]:
        raise ValueError("Demo reset schema differs from JDBC schema")
    if "DEMO_RESET_PIN_HASH" in config and not re.fullmatch(r"\$2[aby]\$\d{2}\$[./A-Za-z0-9]{53}", config["DEMO_RESET_PIN_HASH"]):
        raise ValueError("Expected a BCrypt demo reset hash")
    for key in ("SCHOOL42_ADMIN_HASH", "SCHOOL42_TEACHER_HASH"):
        if key in config and not re.fullmatch(r"[0-9a-f]{64}", config[key]):
            raise ValueError(f"Expected a lowercase SHA-256 digest for {key}")
    if not re.fullmatch(r"[A-Za-z0-9.-]+", config["REDISHOST"]) or not re.fullmatch(r"[0-9]{1,5}", config["REDISPORT"]) or not 1 <= int(config["REDISPORT"]) <= 65535:
        raise ValueError("Invalid Redis endpoint or port")
    if len(config["JWT_SECRET"].encode()) < 32:
        raise ValueError("HS256 requires at least 32 UTF-8 bytes")


if __name__ == "__main__":
    try:
        config = json.load(sys.stdin, object_pairs_hook=unique)
        validate(config)
    except (ValueError, UnicodeError) as error:
        sys.exit(str(error))
    for key, value in config.items():
        print(f"{key}={value}")
