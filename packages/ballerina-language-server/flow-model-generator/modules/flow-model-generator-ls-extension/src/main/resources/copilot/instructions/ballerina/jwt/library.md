# Using ballerina/jwt

## Reading claims from jwt:Payload
- `jwt:validate` returns `jwt:Payload`, an OPEN record that declares only the registered claims (`iss`, `sub`, `aud`, `exp`, `nbf`, `iat`, `jti`). Custom claims are present in the value but are not declared fields.
- Read a custom claim with member access on the record and narrow the result. Member access returns `()` when the claim is absent:
```ballerina
jwt:Payload payload = check jwt:validate(token, validatorConfig);
anydata orderIdClaim = payload["orderId"];
if orderIdClaim !is string {
    return error("missing 'orderId' claim");
}
```
- NEVER cast the payload to a map: `<map<json>>payload` compiles but panics at run time with `{ballerina}TypeCastError` because an open record's implicit rest field type is `anydata`, not `json`. Do not use `payload.get("claim")` for a claim that may be absent either — it panics with `{ballerina/lang.map}KeyNotFound`.
- `aud` is `string|string[]`; narrow before comparing.
- `jwt:validate` already checks the signature (when `signatureConfig` is set), `iss`, `aud`, `exp` and `nbf` from `jwt:ValidatorConfig`; do not re-implement those checks. Always set `signatureConfig` — without it a forged token is accepted.
