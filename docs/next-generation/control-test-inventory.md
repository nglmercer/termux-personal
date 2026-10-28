# Control and document provider test inventory

Baseline: main `1fbb348ebec7d8d086bd92716ef4320ebad474c1`. This inventory describes coverage, not a release certification.

* `app/src/test/java/com/termux/app/control/` covers scope checks, session validation, options, output retention and resume.
* `app/src/test/java/com/termux/app/control/ipc/` covers framing, negotiation, parsing, dispatch, output, deadlines and service-thread dispatch.
* `app/src/test/java/com/termux/filepicker/DocumentPathPolicyTest.java` covers legacy document IDs, traversal, sibling prefixes, symlinks and names on the host filesystem.
* `TermuxDocumentsProviderTest` exercises provider create, list, search, delete and ancestry operations with an isolated root, including invalid IDs, links and root protection. These host tests do not exercise Android URI grants or concurrent path swaps.
* `scripts/test-control-interop.sh`, `test-control-output-interop.sh`, `test-control-resume-host.sh`, `test-control-lifetime-host.sh`, and `test-control-client-resume.sh` exercise host socket and native-client behavior. They do not test Android LocalSocket credentials.
* `LocalControlServerInstrumentedTest` exercises a same-UID socket, read-only scope and revocation with real Android LocalSocket calls. A different-UID peer, provider grants and symlink races still require Android tests. These remain release blockers.
* `app/src/test/resources/control/v1-read-only.json` is a golden request/response conversation exercised by `RpcDispatcherTest`.

Run the app unit suite with `./gradlew :app:testDebugUnitTest`. Host interop scripts require the compiled debug classes and `GSON_JAR` pointing to Gson 2.11.0. Android device tests, ABI packaging and signed upgrade paths require separate verification.

The documents provider retains absolute document IDs for existing grants. It rejects links and paths outside the root during validation, but validation followed by a pathname operation is not atomic against concurrent filesystem changes. Do not use this validation as the security foundation for new file RPC operations; descriptor-relative no-follow operations and race tests are required first.
