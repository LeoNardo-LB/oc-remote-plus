# Maestro E2E Test Flows

## Prerequisites
- Android emulator running (API 26+)
- Maestro CLI installed: `curl -Ls "https://get.maestro.mobile.dev" | bash`
- App installed: `.\gradlew :app:installDevDebug`

## Running Flows

### Single flow
```bash
maestro test maestro/l1-app-launch.yaml
```

### All Layer 1 flows
```bash
maestro test maestro/l1-app-launch.yaml maestro/l1-connection-error.yaml maestro/l1-session-list.yaml
```

### With screenshots
```bash
maestro test --format junit --output maestro-results/ maestro/l1-app-launch.yaml
```

### Screenshot location
Screenshots are saved to `maestro-screenshots/` by default.

## Flow Descriptions

| Flow | What it tests | Manual? |
|------|---------------|---------|
| `l1-app-launch.yaml` | App launches without crash | No |
| `l1-connection-error.yaml` | Connection error UI renders | No |
| `l1-session-list.yaml` | Session list screen loads | No |
| `l1-crash-recovery.yaml` | App recovers after force stop | No |
| `l1-network-reconnect.yaml` | Network reconnect flow | Yes (toggle airplane) |
