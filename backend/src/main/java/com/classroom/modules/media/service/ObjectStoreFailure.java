package com.classroom.modules.media.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.InsufficientDataException;
import io.minio.errors.InternalException;
import io.minio.errors.InvalidResponseException;
import io.minio.errors.ServerException;

import java.io.IOException;

/**
 * R20-12: tells "the object store cannot be reached / is failing" apart from "the object is not there".
 *
 * <p>Before this, every exception from the MinIO SDK was treated as "object missing" or "internal error": with MinIO down,
 * {@code /media/{id}/complete} answered 400 "file not uploaded" after ~5 s (so the Studio told the teacher the upload had failed
 * for good), the proxy {@code /download} answered a bare 500 and the presign paths a 500. A connectivity or 5xx failure is
 * transient and retryable, so it is reported as 503 {@code SERVICE_UNAVAILABLE} with {@code Retry-After} in the standard error
 * envelope; a genuine "no such key" stays a 400/404 for the caller to handle.
 */
public final class ObjectStoreFailure {

    /** Seconds suggested in {@code Retry-After}: a restarted MinIO is reachable again within a few seconds. */
    public static final long RETRY_AFTER_SECONDS = 5;
    public static final String UNAVAILABLE_MESSAGE =
            "Kho lưu trữ tệp tạm thời không khả dụng; vui lòng thử lại sau vài giây.";

    private ObjectStoreFailure() {
    }

    /**
     * True when {@code failure} (or anything in its cause chain) means the object store could not be reached or answered with a
     * server-side error: connection refused/reset, unknown host, timeouts, any other I/O error, a 5xx reply, a truncated or
     * unparseable reply. False for a definite answer such as {@code NoSuchKey}/{@code NoSuchBucket}/{@code AccessDenied}.
     */
    public static boolean isUnavailable(Throwable failure) {
        int depth = 0;
        for (Throwable t = failure; t != null && depth < 12; t = t.getCause(), depth++) {
            // Covers ConnectException, UnknownHostException, SocketTimeoutException, NoRouteToHostException, SocketException,
            // SSLException, EOFException, okhttp's connection-shutdown errors: every transport failure is an IOException.
            if (t instanceof IOException) {
                return true;
            }
            if (t instanceof ServerException || t instanceof InternalException
                    || t instanceof InsufficientDataException || t instanceof InvalidResponseException) {
                return true;
            }
            if (t instanceof ErrorResponseException response && response.response() != null
                    && response.response().code() >= 500) {
                return true;
            }
        }
        return false;
    }

    /** The exception to throw when the object store is unavailable: 503 + Retry-After in the standard envelope. */
    public static AppException unavailable() {
        return new AppException(ErrorCode.SERVICE_UNAVAILABLE, UNAVAILABLE_MESSAGE, RETRY_AFTER_SECONDS);
    }

    /** {@link #unavailable()} when {@code failure} is a connectivity/5xx problem, otherwise {@code otherwise}. */
    public static AppException classify(Throwable failure, AppException otherwise) {
        return isUnavailable(failure) ? unavailable() : otherwise;
    }
}
