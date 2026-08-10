# Service writing instructions

- Specify `/graphql` as the base path unless the user query states one explicitly.
- A service must declare at least one query (a `resource function get`). A service containing only
  mutations or only subscriptions is rejected by the GraphQL compiler plugin.
- `anydata` is not a legal GraphQL type, for a parameter **or** for a return. The shapes below use it
  because that is what the metadata declares; replace it in both positions with a specific type —
  records and basic types for inputs, and a record, a basic type or an array of either for returns.
