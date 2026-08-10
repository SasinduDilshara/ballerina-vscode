# Service writing instructions

- Always declare the listener at module level as a variable and attach the service to that variable.
  (eg: `listener http:Listener ep = check new http:Listener(8080);` then `service /v1 on ep { ... }`)
  The declaration shown below writes the listener inline only to state its signature; do not copy
  that form.
- Path parameters are written as typed segments in the resource path.
  (eg: `resource function get v1/user/[int userId]/profile()`)
- Prefer a concrete return type (a record, `string`, `json`) over `http:Response`.

The following two rules are enforced by the HTTP compiler plugin and override what the handler shape
below states about optionality. The shape lists every slot that is *available*; it is not a legal
combination of them.

- `@http:Payload` is **required** on the body parameter whenever the handler takes more than one
  parameter. It may be omitted only when the handler takes exactly one parameter and that parameter's
  type is a record.
- A handler that takes an `http:Caller` must return `error?` — it may not also return a value.
  Use `http:Caller` to respond directly, or return a value; never both.

```
import ballerina/http;

listener http:Listener ep  = check new http:Listener(8080);

type Person record {
    string name;
    int age;
};

service /v1 on ep {

    // Prefer types as return type. can be anydata such as string, json, record, etc.
    resource function get foo() returns Person|error {
        return { name: "John", age: 30};
    }

    // Query parameters
    resource function get bar(@http:Query string id) returns Person|error {
        return { name: "John", age: 30};
    }

    // Path parameters
    resource function get customers/[int id]/accounts() returns Person|error {
        return { name: "John", age: 30};
    }

    // Body with data binding and header parameters
    resource function post customers/[int id]/accounts(@http:Payload Person account, @http:Header string customHeader) returns Person|error {
        return account;
    }
}

```


# Client writing instructions

- Always declare clients in module level as final variables.
- Use direct data binding to bind the response to a type whenever possible.
- Only use `http:Response` type as the return type when you need to access headers or status code of the response.

```
import ballerina/http;

listener http:Listener ep  = check new http:Listener(8080);

// Always declare clients in module level as final variables.
final http:Client cl = check new("http://localhost:9090");

type Person record {
    string name;
    int age;
};

service /v1 on ep {
    resource function get user() returns Person|error {
        // If only the body of the response is needed use direct data binding.
        Person p = check cl->get("/foo/bar");

        // If the full response is needed use http:Response
        http:Response res = check cl->get("/foo/bar");
        json payload = check res.getJsonPayload();
        Person p1 = check payload.cloneWithType();


        // get a specific header
        string contentTypeHeader = check res.getHeader("Content-Type");

        // get status code
        int statusCode = res.statusCode;

        // send a http request with query params and headers. Note both these are optional.
        Person p3 = check cl->get("/foo/bar?queryParam1=value&queryParam2=val2", headers = {
            "x-Custom-Header": "custom-value"
        });

        return p1;
    }
}
```
