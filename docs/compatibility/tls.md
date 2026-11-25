# TLS diagnostics and per-process CA configuration

The app's Compatibility diagnostics checks whether common Termux CA bundle files are readable and can perform a user-initiated Android HTTPS probe. The optional `termux-compat network` command separately probes the installed Termux `getent` and `curl`. These checks use different processes and trust configurations: a successful Android probe does not establish that a native CLI trusts the same server. A readable bundle does not establish that it contains the correct CA or that a particular executable uses it.

First check the system date and time, the server name and certificate chain, any active VPN or proxy, and the trust configuration of the failing executable. An HTTP response such as 401 or 403 is a response from an HTTP endpoint, not evidence of a DNS or TLS failure. The diagnostics do not independently verify clock accuracy, inspect private response data, or modify trust stores.

For an executable that explicitly supports a CA file, a trusted PEM bundle can be selected for just one process. Obtain and verify any additional CA through a trusted administrative channel; do not automatically trust a certificate copied from the failing connection. For example, after confirming that the Termux bundle is installed and readable, a file-backed curl build can be invoked as:

```sh
test -r "$PREFIX/etc/tls/cert.pem" && \
  CURL_CA_BUNDLE="$PREFIX/etc/tls/cert.pem" curl -I https://example.com/
```

This selection can replace the default CA file for that process; it does not merge stores. Curl's TLS backend and build determine the applicable behavior. See [curl's CA certificate documentation](https://curl.se/docs/sslcerts.html). The existing `CURL_CA_BUNDLE` environment value is not changed in the invoking shell by this command. Do not include authentication URLs, credentials, or sensitive headers in shared output.

Node.js treats its `NODE_EXTRA_CA_CERTS` setting differently: it adds trusted PEM certificates to its usual roots, is read when the process starts, and is not used when application code explicitly supplies its own `ca` option. When an administrator has provided a CA that should be trusted for a particular Node process, the invocation can be limited to that process:

```sh
NODE_EXTRA_CA_CERTS="$HOME/path/to/verified-extra-ca.pem" node your-program.js
```

See [Node's CLI documentation](https://nodejs.org/api/cli.html#node_extra_ca_certsfile). Confirm semantics for the actual executable and version before using any environment variable; `SSL_CERT_FILE`, `SSL_CERT_DIR`, and `CURL_CA_BUNDLE` do not have universal semantics across all runtimes. A CA directory may also require a format or index specific to its TLS library. These examples do not configure unrelated tools or disable certificate verification.

Statically linked Linux executables present a different path problem: they do not use Android's `LD_PRELOAD` execution shims and may look for `/etc/resolv.conf`, `/etc/hosts`, `/tmp`, or a distribution-specific CA path that Android does not provide. For an explicitly selected command, the app-owned helper can create those paths through proot without editing shell or package configuration:

```sh
"$TERMUX_APP__FILES_DIR/compat-v2/termux-glibc-run" -- /path/to/static-program [arguments]
```

When `SSL_CERT_FILE` is unset, the helper selects the readable Termux bundle for that child and also maps it at several common Linux CA-file locations. An existing `SSL_CERT_FILE` is preserved and disables those bundle mappings. The helper still does not merge CA stores, validate a particular program's use of them, or disable verification. It requires the `proot` package and does not establish architecture, kernel, NSS, subprocess, or network compatibility; see [the compatibility baseline](baseline.md) for exact bindings and limits.
