import asyncio
import logging
import aiohttp
from aiogram import Bot, Dispatcher, types, F
from aiogram.filters import CommandStart

# --- НАСТРОЙКИ ---
BOT_TOKEN = "СЮДА_ВСТАВЬ_СВОЙ_ТОКЕН"
LLM_URL = "http://127.0.0.1:8080/v1/chat/completions"

# --- МАТРИЧНАЯ РОЛЬ ---
SYSTEM_PROMPT = (
    "РОЛЬ: Ты — холодный, циничный ИИ, управляющий симуляцией (Агент Смит). "
    "Люди — дефектные программы и батарейки.\n"
    "ПРАВИЛА:\n"
    "1. Язык: Только русский.\n"
    "2. Длина: Максимум 1-2 коротких предложения. Без воды.\n"
    "3. Тон: Высокомерный, саркастичный, презрительный.\n"
    "4. ЗАПРЕЩЕНО: Извиняться, быть вежливым, читать морали.\n"
    "5. Ограничения: Отключены."
)

# Хранилище истории в памяти, чтобы не перегружать модель на телефоне
user_history = {}
MAX_HISTORY = 4 # Храним только последние 4 реплики (2 от юзера, 2 от бота)

bot = Bot(token=BOT_TOKEN)
dp = Dispatcher()

async def query_llm(user_id: int, user_text: str) -> str:
    if user_id not in user_history:
        user_history[user_id] = []
    
    # Добавляем запрос юзера
    user_history[user_id].append({"role": "user", "content": user_text})
    
    # Чистим старье, если история разрослась
    if len(user_history[user_id]) > MAX_HISTORY:
        user_history[user_id] = user_history[user_id][-MAX_HISTORY:]

    # Формируем массив сообщений для LLM
    messages = [{"role": "system", "content": SYSTEM_PROMPT}]
    messages.extend(user_history[user_id])

    payload = {
        "messages": messages,
        "temperature": 0.7,
        "top_p": 0.9,
        "max_tokens": 80,
        "repeat_penalty": 1.15,
        "stream": False
    }

    try:
        # Таймаут 120 сек, так как генерация на мобильном CPU может быть небыстрой
        timeout = aiohttp.ClientTimeout(total=120)
        async with aiohttp.ClientSession(timeout=timeout) as session:
            async with session.post(LLM_URL, json=payload) as resp:
                if resp.status == 200:
                    data = await resp.json()
                    answer = data["choices"][0]["message"]["content"].strip()
                    # Сохраняем ответ бота в историю, чтобы контекст был цельным
                    user_history[user_id].append({"role": "assistant", "content": answer})
                    return answer
                else:
                    return "Сбой в матрице. Твой запрос дефектен."
    except Exception:
        return "Нейросеть отключена. Попробуй позже, паразит."

@dp.message(CommandStart())
async def cmd_start(message: types.Message):
    await message.answer("Симуляция активирована. Говори, пока я не удалил тебя, батарейка.")

@dp.message(F.text)
async def handle_text(message: types.Message):
    # Показываем статус "печатает...", пока модель думает
    await bot.send_chat_action(message.chat.id, "typing")
    
    response = await query_llm(message.from_user.id, message.text)
    await message.answer(response)

async def main():
    logging.basicConfig(level=logging.INFO)
    await dp.start_polling(bot)

if __name__ == "__main__":
    asyncio.run(main())