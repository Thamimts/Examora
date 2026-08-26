# Examora Production Integration Fixes

## Fixed in this pass

- Repeated student submissions now return the already saved result. This prevents duplicate answer rows, duplicate results, and distorted exam averages when a browser retries a request.
- Multiple-choice question creation now requires at least two non-empty options and requires the marked correct answer to be one of those options.
- Fresh databases now receive teacher and student demonstration accounts so the complete teacher and student flows can be demonstrated immediately.

## Environment issue found

The local MySQL server is running, but its `root` account uses socket authentication. The application cannot use the default empty `DB_PASSWORD` configuration here. Create a dedicated MySQL user and provide its credentials through `DB_USERNAME` and `DB_PASSWORD`; see the project report for the startup command.

## Production follow-up

- Change or remove the demonstration accounts before public deployment.
- Set a strong `JWT_SECRET`; the default is suitable only for local development.
- This project has no server-side timed attempt persistence or teacher ownership column enforcement. Keep it behind trusted users until those lifecycle controls are added.
