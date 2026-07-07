package com.tinyurl.service;

import com.tinyurl.exception.UrlUnreachableException;
import java.net.ConnectException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import javax.net.ssl.SSLException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class UrlReachabilityCheckerImpl implements UrlReachabilityChecker {

    private static final Logger log = LoggerFactory.getLogger(UrlReachabilityCheckerImpl.class);
    private static final int MAX_REDIRECTS = 5;

    private final HttpClient httpClient;

    public UrlReachabilityCheckerImpl() {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    // Package-private for testing
    UrlReachabilityCheckerImpl(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public void check(String url) {
        try {
            int status = sendFollowingRedirects(url, "HEAD");
            if (status == 405) {
                status = sendFollowingRedirects(url, "GET");
            }
            if (!isAcceptableStatus(status)) {
                throw new UrlUnreachableException("URL_UNREACHABLE");
            }
        } catch (UrlUnreachableException e) {
            throw e;
        } catch (UnknownHostException | ConnectException | HttpTimeoutException | SSLException e) {
            throw new UrlUnreachableException("URL_UNREACHABLE");
        } catch (Exception e) {
            log.warn("Reachability check failed for host {} — failing open: {}", URI.create(url).getHost(), e.getMessage());
        }
    }

    private int sendFollowingRedirects(String url, String method) throws Exception {
        for (int i = 0; i <= MAX_REDIRECTS; i++) {
            guardSsrf(url);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5));
            if ("HEAD".equals(method)) {
                builder.method("HEAD", HttpRequest.BodyPublishers.noBody());
            } else {
                builder.GET();
            }
            HttpResponse<Void> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                String location = response.headers().firstValue("location").orElse(null);
                if (location != null) {
                    url = URI.create(url).resolve(location).toString();
                    continue;
                }
            }
            return status;
        }
        throw new UrlUnreachableException("URL_UNREACHABLE");
    }

    private boolean isAcceptableStatus(int status) {
        return status >= 200 && status < 300;
    }

    private void guardSsrf(String url) throws Exception {
        String host = URI.create(url).getHost();
        if (host == null || host.isBlank()) {
            throw new UrlUnreachableException("URL_UNREACHABLE");
        }
        InetAddress address = InetAddress.getByName(host);
        // Reject raw IP address literals (IPv4 like 1.2.3.4, IPv6 like ::1)
        if (host.equals(address.getHostAddress())) {
            throw new UrlUnreachableException("URL_UNREACHABLE");
        }
        if (address.isLoopbackAddress()
                || address.isSiteLocalAddress()
                || address.isLinkLocalAddress()
                || address.isAnyLocalAddress()
                || isIpv6UniqueLocal(address)) {
            throw new UrlUnreachableException("URL_UNREACHABLE");
        }
    }

    private boolean isIpv6UniqueLocal(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return false;
        }
        // FC00::/7 — unique local addresses (covers FC00:: and FD00:: ranges)
        return (address.getAddress()[0] & 0xFE) == 0xFC;
    }
}
