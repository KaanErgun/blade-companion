# Project rules

- This is an unofficial Blade companion by Kaan Ergün. Do not imply Vuzix endorsement or a shipped release.
- Keep the core package independent of Android. All user-facing strings belong in Turkish string resources.
- The USB diagnostic command is fixed and read-only. USB descriptors and permission do not prove a responding Blade; display only measured state.
- Do not log USB payloads or identifiers. Close claimed interfaces and connections on every transport exit.
- README.md is the only living status document. Run ./scripts/check.sh locally; no hosted CI.

- Product wire format is BLD1 v1, paired with the OS `docs/companion-protocol.md` and `userspace/companion/protocol.{h,c}`. The exact shared START header vector is asserted in `ProtocolTest`; update both consumers together. Never use the diagnostic shell as the product transport.
- Certificate pins and AUTH tokens come only from an owner-imported pairing record. Keep certificate validation before AUTH, keep no-auto-START behavior, and close control sockets on lifecycle loss so unknown START replies cannot leave a camera session owned by a dead Activity.

- Management framing follows OS `docs/market-protocol.md`; `MarketTest` pins AUTH_MANAGEMENT header bytes and SDK grant bits (hud=4, network=8, storage=16). HUD is bounded local text; it opens only from the glasses' application menu. Keep its token/Keystore alias separate from camera authority. Never expose trust provisioning, root flags, client paths, or shell arguments. Preserve revision preconditions and uncertain-operation STATUS recovery; no automatic mutation retry.
- `app/src/test/resources/bld1-v1.properties` is a byte-identical copy of SDK `docs/bld1-v1.properties`; `GoldenTest` compares actual constants and serializers. The OS compiled C parity gate checks both copies. Update all consumers together, including future Swift tests.

- Android TLS cancellation fields must retain the underlying plain socket. `SSLSocket.close()` may send `close_notify` and raise `NetworkOnMainThreadException`; close raw transport promptly on lifecycle loss and perform TLS teardown on the existing worker. Do not suppress StrictMode or replace cancellation with an unbounded cleanup thread.

- Stream report `displayed` counts unique frames submitted to the visible preview View, not panel scanout. Keep decode/preview FPS on their own monotonic phase spans and never export image bytes, session/frame IDs, endpoints or credentials. Publish the bounded latest report and revision together before asynchronous private persistence so Activity recreation cannot export the previous session.
