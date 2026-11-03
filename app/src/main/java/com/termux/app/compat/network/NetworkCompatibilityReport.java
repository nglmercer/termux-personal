package com.termux.app.compat.network;

/** The result of a single, explicitly requested probe; no response body or URL parameters. */
public final class NetworkCompatibilityReport {
    public enum Status {
        NO_NETWORK, DNS_FAILURE, CONNECT_TIMEOUT, CONNECTION_REFUSED,
        TLS_CERTIFICATE_FAILURE, TLS_HANDSHAKE_FAILURE, PROXY_FAILURE,
        HTTP_RESPONSE, NETWORK_OK, UNKNOWN, TIMEOUT, UNAVAILABLE
    }
    public enum DnsStatus { NOT_TESTED, RESOLVED, NO_RECORDS, DNS_FAILURE, UNKNOWN }
    public enum FamilyStatus { NOT_TESTED, NO_DNS_RECORD, TCP_CONNECTED, CONNECT_TIMEOUT, CONNECT_FAILED,
        SECURITY_RESTRICTED, SKIPPED_PROXY, SKIPPED_VPN, NETWORK_CHANGED, POLICY_UNKNOWN }
    public final String dns;
    public final DnsStatus dnsStatus;
    public final Status status;
    public final int httpStatus;
    public final FamilyStatus ipv4Tcp, ipv6Tcp;

    public NetworkCompatibilityReport(String dns, Status status, int httpStatus) {
        this(dns, DnsStatus.NOT_TESTED, status, httpStatus);
    }
    public NetworkCompatibilityReport(String dns, DnsStatus dnsStatus, Status status, int httpStatus) {
        this(dns, dnsStatus, status, httpStatus, FamilyStatus.NOT_TESTED, FamilyStatus.NOT_TESTED);
    }
    public NetworkCompatibilityReport(String dns, DnsStatus dnsStatus, Status status, int httpStatus,
                                      FamilyStatus ipv4Tcp, FamilyStatus ipv6Tcp) {
        if (dns == null || dnsStatus == null || status == null || ipv4Tcp == null || ipv6Tcp == null)
            throw new IllegalArgumentException("Missing report field");
        this.dns = dns;
        this.dnsStatus = dnsStatus;
        this.status = status;
        this.httpStatus = httpStatus;
        this.ipv4Tcp = ipv4Tcp;
        this.ipv6Tcp = ipv6Tcp;
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
        return result.append("\nIPv4 TCP sample: ").append(ipv4Tcp)
            .append("\nIPv6 TCP sample: ").append(ipv6Tcp)
            .append("\nEach sample uses at most one resolved address and TCP port 443; it does not test TLS or other addresses.")
            .append("\nCLI DNS, CLI TLS and localhost callback: not tested.")
            .append("\nAn HTTP response, including 401 or 403, confirms a response, not service authorization.")
            .append("\nThe active network can change during or after this test.")
            .toString();
    }
}
