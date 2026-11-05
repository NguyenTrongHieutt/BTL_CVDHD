package vn.nhom17.banhang.platform.internal.web;

import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/** Dựng RFC 9457 Problem Details có {@code requestId} (AD-19). */
public final class Problems {

    private Problems() {
    }

    public static ProblemDetail of(HttpStatusCode status, String type, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(type));
        pd.setTitle(title);
        withRequestId(pd);
        return pd;
    }

    public static ProblemDetail withRequestId(ProblemDetail pd) {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null) {
            pd.setProperty("requestId", requestId);
        }
        return pd;
    }
}
