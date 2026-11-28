import json
import subprocess
import sys
import unittest
from pathlib import Path

from runtime_env import unique, validate


class RuntimeEnvTest(unittest.TestCase):
    def setUp(self):
        self.config = {
            "SPRING_DATASOURCE_URL": "jdbc:mysql://test-db:3306/algorhythm",
            "SPRING_DATASOURCE_USERNAME": "test",
            "SPRING_DATASOURCE_PASSWORD": "test",
            "REDISHOST": "redis.test", "REDISPORT": "6379", "REDISPASSWORD": "test",
            "JWT_SECRET": "x" * 32, "RESEND_API_KEY": "test", "RESEND_FROM_EMAIL": "ci@example.invalid",
        }

    def test_required_and_conditional_keys(self):
        validate(self.config)
        for key in self.config:
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate({k: v for k, v in self.config.items() if k != key})
        for change in ({"KAKAO_CLIENT_ID": "ci"}, {"DEMO_RESET_OPERATOR": "ci"},
                       {"SCHOOL42_ADMIN_HASH": "not-a-digest"},
                       {"SCHOOL42_ADMIN_HASH": "a" * 64, "SCHOOL42_TEACHER_HASH": "a" * 64}, {"JWT_SECRET": "short"},
                       {"JAVA_TOOL_OPTIONS": "unused"}, {"REDISPORT": "65536"},
                       {"REDISHOST": "host\nOTHER=bad"},
                       {"SPRING_DATASOURCE_URL": self.config["SPRING_DATASOURCE_URL"] + "?sslMode=DISABLED"}):
            with self.subTest(key=next(iter(change))), self.assertRaises(ValueError):
                validate(self.config | change)
        with self.assertRaises(ValueError):
            json.loads('{"k":"a","k":"b"}', object_pairs_hook=unique)

    def test_docker_values_are_literal_and_errors_hide_values(self):
        password = "ci test $dollar='quoted'\\path#hash"
        self.config["SPRING_DATASOURCE_PASSWORD"] = password
        args = [sys.executable, str(Path(__file__).with_name("runtime_env.py"))]
        result = subprocess.run(args, input=json.dumps(self.config), capture_output=True, text=True, check=True)
        self.assertIn("SPRING_DATASOURCE_PASSWORD=" + password, result.stdout.splitlines())
        self.config["REDISPORT"] = password
        result = subprocess.run(args, input=json.dumps(self.config), capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")
        self.assertNotIn(password, result.stderr)
