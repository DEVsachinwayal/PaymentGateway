# APG – Instructions for any AI session (read first)

Project: Autonomous Payment Gateway. Spec = `docs/MASTER_SPEC_EXTRACT.txt` (extracted from the .docx; authoritative).
User communicates in Marathi/English. User asked: build the whole project per spec, keep notes so a new chat can continue, commit after each task.

## Resume protocol (new chat)
1. Read `docs/WORK_LOG.md` (last entries), `docs/TODO.md`, `docs/DECISIONS.md`.
2. Run `git log --oneline | head` and `git status`.
3. Continue from the "Next step" in the last WORK_LOG entry. One milestone (M0..M16) at a time.
4. After each task: update WORK_LOG, CHANGELOG, TODO (+DECISIONS), then `git commit`.

## Environment facts
- Windows 11. Real JDK: `C:\Program Files\Java\jdk-25.0.4.1` (system JAVA_HOME is wrong -> set `JAVA_HOME` per shell before mvn).
- Maven: `D:\Tools\apache-maven-3.9.16`. Node 22. Docker CLI present; daemon may need to be started (Docker Desktop).
- Stack decision: Spring Boot 3.5.16, Java release 21 (runs on 25), multi-module Maven. See DECISIONS.md.
- Never commit secrets; `.env` is git-ignored.
