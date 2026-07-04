# Quiz Bot

A Telegram bot for running multiplayer quiz games in group chats, built with Spring Boot and the [pengrad/telegrambot](https://github.com/pengrad/java-telegram-bot-api) library.

Players join a game, mark themselves ready, then answer multiple-choice questions using inline buttons. Each round is scored and shown to the group, with a final leaderboard at the end.

## Requirements

- Java 25
- A Telegram bot token from [@BotFather](https://t.me/BotFather)

## Configuration

The bot token is read from the `TELEGRAM_BOT_TOKEN` environment variable (see `src/main/resources/application.properties`). It is **not** stored in the repository — set it before running:

```bash
export TELEGRAM_BOT_TOKEN=123456789:your-token-here
```

On Windows PowerShell:

```powershell
$env:TELEGRAM_BOT_TOKEN = "123456789:your-token-here"
```

## Running

```bash
./mvnw spring-boot:run
```

Or build and run the jar:

```bash
./mvnw clean package
java -jar target/quizBot-0.0.1-SNAPSHOT.jar
```

## How to play

Add the bot to a Telegram group chat and use the following commands:

| Command | Description |
|---|---|
| `/newGame <category\|all> <count> [timeout]` | Create a new game with `count` questions from `<category>` (or `all`); `timeout` is seconds per question (default 60) |
| `/categories` | List available question categories |
| `/join` | Join the current game |
| `/ready` | Mark yourself as ready (does **not** start the game) |
| `/startGame` | Start the game — only works once every joined player has `/ready`'d |
| `/pauseGame` | Pause after the current question finishes — the round in progress is never interrupted |
| `/resumeGame` | Resume a paused game, continuing with the next question |
| `/endGame` | End the current game early (before or during play) and show the leaderboard so far |
| `/list` | List joined players and who's ready |
| `/help` | Show command help |

### Game flow

1. Someone runs `/newGame <category> <count> [timeout]` to create a game in the chat.
2. Players run `/join` to enter, then `/ready` once they're prepared to play.
3. Once everyone who joined is ready, anyone runs `/startGame` to begin.
4. Each round, the bot posts a question with inline buttons (`A`/`B`/`C`/`D`) — answers are submitted by tapping a button, not by typing.
5. A round ends as soon as every player has answered, or after the timeout (default 60s, configurable via `/newGame`'s third argument), whichever comes first. Round results (correct/wrong/no answer per player) are posted, then the next question follows automatically — unless `/pauseGame` was used, in which case the bot waits for `/resumeGame` before continuing.
6. After the last question (or after `/endGame`), a final leaderboard is posted sorted by score, along with a "🔁 Play Again" button that recreates a new game with the same category, question count, and timeout.

A new `/newGame` can be run any time the current game hasn't started yet (`WAITING`) or has finished — it fully replaces the existing game's settings and players. It's rejected only while a game is actually `IN_GAME`; use `/endGame` first if you need to bail out of one early.

## Question categories

Questions are loaded at startup from `src/main/resources/questions/`, one file per category (e.g. `general`, `history`, `science-technology`, `sports`, `bg`, ...). Use `/categories` in a chat to see the current list, or `all` to draw from every category at once.
