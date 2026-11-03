package com.termux.app.compat.network;

/** The result of a single, explicitly requested probe; no response body or URL parameters. */
public final class NetworkCompatibilityReport {
    public enum Status {
        NO_NETWORK, DNS_FAILURE, CONNECT_TIMEOUT, CONNECTION_REFUSED,
        TLS_CERTIFICATE_FAILURE, TLS_HANDSHAKE_FAILURE, PROXY_FAILURE,
        HTTP_RESPONSE, NETWORK_OK, UNKNOWN, TIMEOUT, UNAVAILABLE
    }
    public final String dns;
    public final Status status;
    public final int httpStatus;

    public NetworkCompatibilityReport(String dns, Status status, int httpStatus) {
        this.dns = dns;
        this.status = status;
        this.httpStatus = httpStatus;
    }

    public String describe() {
        StringBuilder result = new StringBuilder("Android DNS (example.com): ").append(dns)
            .append("\nAndroid HTTPS: ").append(status);
        if (status == Status.HTTP_RESPONSE) result.append(" (HTTP ").append(httpStatus).append(")");
        switch (status) {
            case NO_NETWORK: result.append("\nNo default network was visible; it may be disconnected or blocked."); break;
            case DNS_FAILURE: result.append("\nCheck the active network, VPN, Private DNS and proxy; a proxy hostname can also fail DNS."); break;
            case TLS_CERTIFICATE_FAILURE: result.append("\nCheck the device clock, server certificate validity and applicable trusted CAs; do not disable verification."); break;
            case TLS_HANDSHAKE_FAILURE: result.append("\nCheck TLS configuration and network intermediaries; certificate trust may require separate diagnosis."); break;
            case TIMEOUT: result.append("\nThe probe timed out; this alone does not identify DNS, connection, TLS or response as the cause."); break;
            case UNAVAILABLE: result.append("\nThe probe is unavailable or a previous resolver call is still running."); break;
            case HTTP_RESPONSE:
                if (httpStatus == 407) result.append("\nHTTP 407 indicates proxy authentication is required.");
                break;
            default: break;
        }
        return result.append("\nIPv4/IPv6 reachability, CLI DNS, CLI TLS and localhost callback: not tested.")
            .append("\nAn HTTP response, including 401 or 403, confirms a response, not service authorization.")
            .toString();
    }
}
