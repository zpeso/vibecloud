# VibeCloud

A small, single-node Minecraft cloud controller written entirely in Kotlin. It manages local server processes as
**Services**, provisioned from versioned **Group** templates. It is intentionally much smaller than a multi-node
platform: the public API, process boundary, template provider registry, and port allocator are replaceable seams for
future growth, while the default implementation remains easy to operate.

## Compatibility

- Kotlin **2.4.20** (stable)
- Gradle **9.8.0** with Kotlin DSL
- Java **25** for this build and for current Paper releases
- Example target: Minecraft Java **26.3** (stable release on September 15, 2026)
- Coroutines **1.11.0**, SnakeYAML **2.7**

Minecraft 26.1+ / Paper currently requires Java 25. Older templates may have different requirements; the controller uses
the configured Java executable for every service.

## Work in progress, currently at no use state
