# Server templates and downloaded builds

`group create` can choose an exact upstream build and downloads it into this directory before saving the group. Build
folders are pinned and include a `.server-build.properties` manifest with the upstream URL, build/channel metadata, and
local SHA-256. PaperMC Fill v3 checksums are verified before a JAR is installed.

Examples of generated folders:

```text
templates/
├── paper/paper-26.2-129/server.jar
├── velocity/velocity-4.0.0-6/server.jar
├── bungeecord/bungeecord-2100/server.jar
└── bungeecord/waterfall-1.21-615/server.jar
```

Paper and Velocity versions/builds and Waterfall builds are listed from PaperMC's Fill v3 API. BungeeCord itself is
listed from the official SpigotMC Jenkins metadata endpoint because it is not a PaperMC Fill project. Waterfall is a
Bungee-compatible PaperMC fork and is end-of-life; prefer Velocity for new proxy networks. The controller does not
automatically select Waterfall when you choose BungeeCord—the catalog labels the distribution explicitly.

Spigot does not publish a ready-to-run server JAR through Fill. Build it using the official Spigot BuildTools and place
it at `spigot/<your-version>/server.jar` below this directory. Existing local templates remain selectable in the group
wizard for every type.

For Velocity, the downloader creates a basic `velocity.toml` if the artifact has none. For BungeeCord/Waterfall, it
creates a starter `config.yml`. Review proxy forwarding, backend addresses, authentication, secrets, and firewall rules
before public use. The defaults are examples, not a secure production network configuration.

The cloud copies a template into each service directory. It assigns a unique listen port in Paper/Spigot
`server.properties`, Velocity `bind`, and the first Bungee-compatible `host` listener. Paper/Spigot templates may omit
`server.properties`; one is created. Existing worlds and service files stay with the service, not the shared template.
