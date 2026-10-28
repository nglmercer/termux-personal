# Control and document provider test inventory

Baseline: main `1fbb348ebec7d8d086bd92716ef4320ebad474c1`. This inventory describes coverage, not a release certification.

* `app/src/test/java/com/termux/app/control/` covers scope checks, session validation, options, output retention and resume.
* `app/src/test/java/com/termux/app/control/ipc/` covers framing, negotiation, parsing, dispatch, output, deadlines and service-thread dispatch.
* `app/src/test/java/com/termux/filepicker/DocumentPathPolicyTest.java` covers legacy document IDs, traversal, sibling prefixes, symlinks and names on the host filesystem.
* `scripts/test-control-interop.sh`, `test-control-output-interop.sh`, `test-control-resume-host.sh`, `test-control-lifetime-host.sh`, and `test-control-client-resume.sh` exercise host socket and native-client behavior. They do not test Android LocalSocket credentials.
* Android instrumentation currently has no provider grant, local socket credential, consent revocation, or symlink race tests. These remain release blockers.

Run the app unit suite with `./gradlew :app:testDebugUnitTest`. Host interop scripts require the compiled debug classes and `GSON_JAR` pointing to Gson 2.11.0. Android device tests, ABI packaging and signed upgrade paths require separate verification.

The documents provider retains absolute document IDs for existing grants. It rejects links and paths outside the root during validation, but validation followed by a pathname operation is not atomic against concurrent filesystem changes. Do not use this validation as the security foundation for new file RPC operations; descriptor-relative no-follow operations and race tests are required first.
