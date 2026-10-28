# Termux control protocol v1 contract

`methods.json` lists implemented methods and their required scopes. Multiple scopes are required together; `outputService` methods also require the output service to be available. `binaryDirection` describes the additional frame after request metadata or response metadata. The Java registry is checked against this file by `ControlMethodRegistryTest`.

After `system.hello` negotiates version 1, `system.describe` returns the authorized and available methods with their scope requirements, mutation and binary metadata, and transport limits. `system.capabilities` remains available to older clients. A client talking to an older server must treat `-32601` from `system.describe` as an optional-feature absence and use `system.capabilities`. The hello response and frame format remain unchanged.

This contract currently describes existing session methods only; it does not imply that jobs, files, processes or workspaces have been implemented. Local control remains opt-in, and scopes are chosen by the server, not by client request fields.

`schemas/request.json` and `schemas/response.json` describe the JSON-RPC envelope; they do not replace the server's bounded parser or method-specific validation. `errors.json` records legacy error families and reserves names for future domain errors. A request with a binary payload is one JSON metadata frame followed by one binary frame; response metadata is followed by a binary frame only when the declared length is nonzero. The connection remains serialized and does not support batches or unsolicited messages.
