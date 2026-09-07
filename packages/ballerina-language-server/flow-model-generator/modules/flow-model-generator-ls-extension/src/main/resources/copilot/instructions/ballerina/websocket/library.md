# Using ballerina/websocket

## Sharing an http:Listener requires HTTP/1.1
- WebSocket runs over HTTP/1.1 only, but `http:Listener` defaults to HTTP/2. A `websocket:Listener` created from an existing `http:Listener` that was declared with default configuration fails EVERY upgrade with HTTP 500 and logs nothing.
- When the WebSocket service must share a port with HTTP services, declare the shared listener with HTTP/1.1 and build the WebSocket listener from it:
```ballerina
listener http:Listener sharedListener = new (servicePort, httpVersion = http:HTTP_1_1);
listener websocket:Listener liveListener = new (sharedListener);
```
- `websocket:ListenerConfiguration` has no `httpVersion` field; the version can only be set on the `http:Listener`. A `websocket:Listener` created directly from a port (`new websocket:Listener(9090)`) already uses HTTP/1.1.

## Upgrade responses
- Cancel an upgrade by returning `websocket:UpgradeError` from the upgrade `get` resource. It is always answered with HTTP 400; `websocket:UpgradeError` carries no status code.
- For 401/403 use the listener's auth support (`@websocket:ServiceConfig {auth: [...]}` with `jwtValidatorConfig`, `oauth2IntrospectionConfig` or a basic-auth provider), which answers 401/403 itself; do not hand-roll status codes in the upgrade resource.
