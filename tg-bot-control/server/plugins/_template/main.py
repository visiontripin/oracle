"""Template sandboxed handler.

ctx keys: plugin_id, text, user, user_id, chat_id, config, reply().
Allowed: safe builtins + re/math/json. No files/network/OS/aiogram.
"""


async def handle(ctx):
    greeting = ctx["config"].get("greeting", "Hello")
    await ctx["reply"](f"{greeting}, {ctx['user']}! Echo: {ctx['text']}")
