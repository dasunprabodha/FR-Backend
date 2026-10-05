package lk.cf.fr.monolith.config;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.metrics.CoreMetric;
import software.amazon.awssdk.http.HttpMetric;
import software.amazon.awssdk.metrics.MetricCollection;
import software.amazon.awssdk.metrics.MetricPublisher;
import software.amazon.awssdk.metrics.SdkMetric;

import java.time.Duration;
import java.util.Optional;

/**
 * Logs a one-line breakdown of every AWS call, so a slow or failing Rekognition request can be
 * diagnosed from the log instead of guessed at from the gaps between timestamps.
 *
 * <p>Exists because a registration that stalls gives almost nothing to work with: the SDK retries
 * internally and silently, so a single {@code compareFaces} that appears to take 70 seconds could
 * equally be one slow upload, three attempts each timing out, or a long backoff between fast
 * failures. Those have completely different fixes - shrink the payload, fix the link, or fix
 * credentials - and the SDK already measures the difference. This just prints it.
 *
 * <p>Read the line as: total time, how much of it was on the wire, how many retries, and - when
 * it failed - what the SDK called the error. A large {@code apiCall} with a small
 * {@code wire} sum means time went to backoff; a single attempt consuming the whole budget means
 * the payload is too big for the link.
 */
@Slf4j
public class AwsCallMetricsPublisher implements MetricPublisher {

    /** Below this, a call is healthy and logging every one of them would only add noise. */
    private final long slowCallMs;

    public AwsCallMetricsPublisher(long slowCallMs) {
        this.slowCallMs = slowCallMs;
    }

    @Override
    public void publish(MetricCollection metrics) {
        try {
            String operation = value(metrics, CoreMetric.OPERATION_NAME).orElse("?");
            Duration apiCall = value(metrics, CoreMetric.API_CALL_DURATION).orElse(Duration.ZERO);
            boolean successful = value(metrics, CoreMetric.API_CALL_SUCCESSFUL).orElse(Boolean.TRUE);
            int retries = value(metrics, CoreMetric.RETRY_COUNT).orElse(0);

            // Nothing to say about a fast, successful call.
            if (successful && apiCall.toMillis() < slowCallMs) {
                return;
            }

            // Each child is one attempt; this is where a retry storm becomes visible as several
            // attempts rather than one long one.
            //
            // TIME_TO_FIRST_BYTE is the load-bearing number here. It is the wait until the service
            // started answering, which on a request carrying a megabyte of image is dominated by
            // the time spent pushing that image up the link. So TTFB ~= the whole attempt means
            // "the upload is the bottleneck", whereas a small TTFB inside a long attempt points at
            // the service or the response instead.
            StringBuilder attempts = new StringBuilder();
            long uploadWaitMs = 0;
            int index = 0;
            Integer lastStatus = null;
            String lastError = null;
            for (MetricCollection attempt : metrics.children()) {
                long serviceCall = millis(attempt, CoreMetric.SERVICE_CALL_DURATION);
                long ttfb = millis(attempt, CoreMetric.TIME_TO_FIRST_BYTE);
                long backoff = millis(attempt, CoreMetric.BACKOFF_DELAY_DURATION);
                long credsFetch = millis(attempt, CoreMetric.CREDENTIALS_FETCH_DURATION);
                long signing = millis(attempt, CoreMetric.SIGNING_DURATION);
                String error = value(attempt, CoreMetric.ERROR_TYPE).orElse(null);
                Integer status = value(attempt, HttpMetric.HTTP_STATUS_CODE).orElse(null);

                if (ttfb > 0) {
                    uploadWaitMs += ttfb;
                }
                if (status != null) lastStatus = status;
                if (error != null) lastError = error;
                attempts.append(String.format(" [#%d call=%s ttfb=%s sign=%s creds=%s backoff=%dms%s%s]",
                        ++index, ms(serviceCall), ms(ttfb), ms(signing), ms(credsFetch), Math.max(backoff, 0),
                        status != null ? " http=" + status : "",
                        error != null ? " error=" + error : ""));
            }

            String verdict = diagnose(successful, apiCall.toMillis(), uploadWaitMs, retries, index,
                    lastStatus, lastError);

            if (successful) {
                log.warn("[AWS-Metrics] {} SLOW: total={}ms awaitingService={}ms retries={} attempts={}{} -> {}",
                        operation, apiCall.toMillis(), uploadWaitMs, retries, index, attempts, verdict);
            } else {
                log.error("[AWS-Metrics] {} FAILED: total={}ms awaitingService={}ms retries={} attempts={}{} -> {}",
                        operation, apiCall.toMillis(), uploadWaitMs, retries, index, attempts, verdict);
            }
        } catch (Exception e) {
            // Diagnostics must never be the thing that breaks a request.
            log.debug("[AWS-Metrics] Could not publish metrics: {}", e.toString());
        }
    }

    /**
     * Turns the numbers into the sentence a reader would otherwise have to derive.
     *
     * <p>Order matters here. A rejected request and a starved link both show "almost all the time
     * spent waiting for the service", because time-to-first-byte covers the upload either way -
     * so the HTTP status has to be ruled on first. Getting that order wrong makes this line
     * confidently blame the network for what is actually a malformed request, which is worse than
     * printing nothing at all.
     */
    private String diagnose(boolean successful, long apiCallMs, long ttfbTotalMs, int retries,
                            int attempts, Integer status, String error) {
        // 1. The service answered and said no. Nothing to do with bandwidth, however long it took.
        if (!successful && status != null && status >= 400 && status < 500 && status != 429) {
            return "the service REJECTED the request (HTTP " + status + (error != null ? "/" + error : "")
                    + ") - a request or image-content problem, not the network. "
                    + "InvalidParameterException here usually means no detectable face in one of the images";
        }
        if (status != null && status == 429) {
            return "throttled by AWS (HTTP 429) - reduce concurrent calls or retry more slowly";
        }
        if (!successful && status == null && attempts >= 1 && ttfbTotalMs <= 0) {
            return "no response was ever received - DNS, connectivity or TLS to the AWS endpoint";
        }

        // 2. Only now is it meaningful to talk about how long things took.
        boolean actuallySlow = apiCallMs >= slowCallMs;
        if (actuallySlow && ttfbTotalMs > apiCallMs / 2) {
            return "most of the time was spent waiting for the service to start replying, which on these "
                    + "image payloads means the upload is the bottleneck - compare the byte counts on the "
                    + "matching [CompareFaces] line against this duration for the effective upload speed";
        }
        if (retries > 0 && ttfbTotalMs < apiCallMs / 4) {
            return "little time on the wire but several attempts - see the per-attempt error/http codes "
                    + "above; this pattern is throttling or credentials, not bandwidth";
        }
        if (!successful && attempts <= 1) {
            return "a single attempt consumed the whole timeout budget - raise aws.timeouts.* or send smaller images";
        }
        if (!successful) {
            return "budget exhausted across " + attempts + " attempts - see the per-attempt breakdown above";
        }
        return "slow but successful - see the per-attempt breakdown above";
    }

    /** Renders a duration the SDK did not record as "n/a" instead of a misleading -1. */
    private static String ms(long value) {
        return value < 0 ? "n/a" : value + "ms";
    }

    private static long millis(MetricCollection collection, SdkMetric<Duration> metric) {
        return value(collection, metric).map(Duration::toMillis).orElse(-1L);
    }

    private static <T> Optional<T> value(MetricCollection collection, SdkMetric<T> metric) {
        return collection.metricValues(metric).stream().findFirst();
    }

    @Override
    public void close() {
        // Nothing to release: this publisher only logs.
    }
}
