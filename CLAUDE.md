# TremorWatch Router

TremorWatch is a multi-module Kotlin project:
- Android phone app (`phone/`) using Jetpack Compose, Room, event-driven WorkManager uploads, Wear Data Layer
- WearOS watch app (`app/`) using foreground service + SensorManager + FFT-based tremor detection
- Shared Kotlin module (`shared/`) for cross-device models/constants

Read order for any AI agent:
1. `agents.md` - operating rules, battery constraints, build/test commands.
2. `features.md` - current feature status and known gaps from latest audit.

Do not assume feature completeness from README claims; use `features.md` as the current state baseline.

For install/deploy commands and safety rules (no uninstall, target correct device, phone regular profile/user 0), follow `AGENTS.md`.
