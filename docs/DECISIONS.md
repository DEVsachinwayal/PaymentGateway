# Decisions
## D1 – Spring Boot 3.5.16, Java release 21
Alternatives: Boot 4.x (new modularisation, less mature ecosystem), Java 25 target. Reason: spec says "Java 21/25+ compatible"; 3.5.x is stable and runs on JDK 25. Consequence: revisit Boot 4 upgrade later.
## D2 – Multi-module Maven monorepo with `libs/apg-common`
Shared lib holds only conventions (response envelope, error codes, correlation ID, Money helpers) – no business logic, so service boundaries stay intact.
## D3 – Spec stored in repo as text
`docs/MASTER_SPEC_EXTRACT.txt` lets any new chat resume without the .docx.
## D4 – Kafka image: apache/kafka-native:4.1.2
Alternative: apache/kafka:3.9.0 (could not be pulled due to network issues). Native image is cached locally, KRaft mode, ~fast start. Consequence: no CLI scripts in container; healthcheck is a TCP check.
