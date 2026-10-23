"""Sandboxed echo handler (validated Python, restricted builtins)."""
import re


async def handle(ctx):
    prefix = ctx["config"].get("prefix", "Echo:")
    text = re.sub(r"^/echo(@\\S+)?\\s*", "", ctx["text"]).strip()
    if not text:
        await ctx["reply"]("Usage: /echo <text>")
        return
    await ctx["reply"](f"{prefix} {text}")
