"""Manifest schema + rule engine tests."""
import pytest
from pydantic import ValidationError

from app.bot.plugin_manager import PluginManager
from app.bot.rule_engine import Trigger
from app.schemas import PluginManifest


def test_manifest_ok():
    m = PluginManifest(id="demo", name="Demo", version="1.2.3")
    assert m.entrypoint == "main.py" and m.enabled is False


def test_manifest_bad_id():
    with pytest.raises(ValidationError):
        PluginManifest(id="Bad-Id!", name="x", version="1.0.0")


def test_manifest_bad_version():
    with pytest.raises(ValidationError):
        PluginManifest(id="demo", name="x", version="1.0")


def test_trigger_command():
    t = Trigger(type="command", pattern="/start", response_text="hi {user}")
    assert t.matches("/start") and t.matches("/start@mybot arg")
    assert not t.matches("/stop")
    assert t.render("/start", "Ann") == "hi Ann"


def test_trigger_contains_and_regex():
    assert Trigger(type="contains", pattern="hello", response_text="").matches("Say Hello!")
    assert Trigger(type="regex", pattern=r"^ping\s+\d+$", response_text="").matches("ping 123")
    assert not Trigger(type="regex", pattern=r"([", response_text="").matches("x")  # bad regex safe


def test_manager_rejects_path_escape(tmp_path):
    mgr = PluginManager(tmp_path)
    with pytest.raises(Exception):
        mgr.plugin_path("../evil")


def test_manager_create_and_discover(tmp_path):
    from app.schemas import PluginCreate

    mgr = PluginManager(tmp_path)
    mgr.create(PluginCreate(id="t1", name="T1"))
    ids = [m.id for m in mgr.discover()]
    assert "t1" in ids


def test_manager_rejects_dangerous_code(tmp_path):
    from app.bot.plugin_manager import PluginError
    from app.schemas import PluginCreate

    mgr = PluginManager(tmp_path)
    with pytest.raises(PluginError):
        mgr.create(PluginCreate(id="evil", name="E", code="import os\n"))
