"""Declarative trigger engine — plugins WITHOUT any Python code.

A trigger: {"type": command|contains|startswith|regex,
            "pattern": "...", "response_text": "..."}.
Triggers are matched against incoming message text and answered with a
static template. Templates support a tiny safe substitution:
`{text}` (incoming text), `{user}` (first name). No code execution.
"""
from __future__ import annotations

import re
from dataclasses import dataclass


@dataclass
class Trigger:
    type: str
    pattern: str
    response_text: str
    description: str = ""

    def matches(self, text: str) -> bool:
        t = text or ""
        if self.type == "command":
            cmd = self.pattern if self.pattern.startswith("/") else f"/{self.pattern}"
            first = t.split()[0] if t.split() else ""
            return first.split("@")[0] == cmd
        if self.type == "contains":
            return self.pattern.lower() in t.lower()
        if self.type == "startswith":
            return t.lower().startswith(self.pattern.lower())
        if self.type == "regex":
            try:
                return re.search(self.pattern, t) is not None
            except re.error:
                return False
        return False

    def render(self, text: str, user: str) -> str:
        # Minimal safe substitution (no format-string attribute access).
        return (
            self.response_text.replace("{text}", text or "").replace("{user}", user or "")
        )


def match_triggers(triggers: list[Trigger], text: str) -> Trigger | None:
    for trig in triggers:
        try:
            if trig.matches(text):
                return trig
        except Exception:  # noqa: BLE001 — a broken trigger must not crash dispatch
            continue
    return None
