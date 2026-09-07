# Using ballerinax/solace

## Broker objects are never created by the connector
- The connector has no API to create queues, topic endpoints or dead-message queues. Durable queues, durable topic endpoints and the DMQ must already exist on the broker (created in PubSub+ Manager or through SEMP) before the program starts.
- `queueName` in `@solace:ServiceConfig` / `solace:QueueConfiguration`, and `endpointName` when `durability` is `solace:DURABLE` in `@solace:ServiceConfig` / `solace:TopicConfiguration`, must name an existing broker object. If it does not exist, the consumer flow fails to bind when the listener or `solace:MessageConsumer` starts.
- Exception: with `durability: solace:TEMPORARY` (the default for topic subscriptions) the broker creates the endpoint for the session, so nothing is pre-created; `queueName` is then only an optional name hint. Read the broker-generated name with `MessageConsumer.destinationName()` when it must be published as a reply-to address.
- Whenever the generated code binds a durable queue or endpoint, end the response with a short prerequisites note naming each broker object that must be created first, together with the message VPN it lives in.
