package com.classroom.modules.outbox.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.mongodb.MongoException;
import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import org.neo4j.driver.exceptions.ClientException;
import org.neo4j.driver.exceptions.ConnectionReadTimeoutException;
import org.neo4j.driver.exceptions.Neo4jException;
import org.neo4j.driver.exceptions.ServiceUnavailableException;
import org.neo4j.driver.exceptions.SessionExpiredException;
import org.neo4j.driver.exceptions.TransientException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.NonTransientDataAccessException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.channels.ClosedChannelException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Decides whether a failed projection is a problem of the DEPENDENCY (transient: retry later, never dead-letter) or of the EVENT
 * (permanent: counts toward the dead-letter limit) - R20-04b.
 *
 * <p>The cause chain is walked from the outermost exception inwards and the first exception that is decisive wins. Wrappers such as
 * {@code RuntimeException("Neo4j projection failed: ...")} are not decisive; they simply defer to their cause. Types are matched
 * before message text; text is only a last resort for opaque wrappers (and for events dead-lettered by older versions that only kept
 * the message, see {@link #classifyMessage(String)}).
 *
 * <p>Anything that is not recognised is {@link FailureKind#PERMANENT}: an unknown error must still reach the dead-letter queue after
 * the retry limit instead of being retried forever, and the automatic re-drive job gives such events another chance later.
 */
public final class OutboxFailureClassifier {

    private static final int MAX_CAUSE_DEPTH = 12;

    /** Text fragments that only appear when a peer could not be reached / answered too slowly (matched on the lower-cased message). */
    private static final Pattern TRANSIENT_TEXT = Pattern.compile(String.join("|",
            "connection refused", "connect timed out", "connection timed out", "read timed out", "timed out", "timeout",
            "unable to connect", "unable to acquire connection", "unable to establish connection", "could not connect",
            "failed to connect", "connection reset", "connection closed", "connection is closed", "broken pipe",
            "no route to host", "unknownhost", "unknown host", "nodename nor servname", "temporary failure in name resolution",
            "ensure the database is running", "service unavailable", "serviceunavailable", "server selection",
            "no available connection", "connection pool", "connection was closed", "channel closed", "socket",
            "not primary", "notwritableprimary", "node is recovering", "transienterror", "database unavailable",
            "databaseunavailable", "leader switch", "too many connections"));

    private OutboxFailureClassifier() {
    }

    /** Classifies a thrown exception. A {@code null} throwable is {@link FailureKind#PERMANENT}. */
    public static FailureKind classify(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        StringBuilder messages = new StringBuilder();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH && seen.add(current); depth++) {
            FailureKind decisive = decisive(current);
            if (decisive != null) {
                return decisive;
            }
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append(' ');
            }
            current = current.getCause();
        }
        return messages.length() > 0 ? classifyMessage(messages.toString()) : FailureKind.PERMANENT;
    }

    /**
     * Classification from message text alone. Used for wrapped errors nobody recognised and for {@code DEAD_LETTER} rows written before
     * failure kinds were recorded (their {@code error_message} is all that is left).
     */
    public static FailureKind classifyMessage(String message) {
        if (message == null || message.isBlank()) {
            return FailureKind.PERMANENT;
        }
        return TRANSIENT_TEXT.matcher(message.toLowerCase(Locale.ROOT)).find() ? FailureKind.TRANSIENT : FailureKind.PERMANENT;
    }

    /** {@code TRANSIENT}/{@code PERMANENT} when this single exception is decisive, otherwise {@code null} (look at its cause). */
    private static FailureKind decisive(Throwable t) {
        // ---- the event is bad: deterministic, retrying cannot help --------------------------------------------------------
        if (t instanceof JsonProcessingException                       // is-a IOException, so it must be tested BEFORE the I/O rule
                || t instanceof IllegalArgumentException              // includes NumberFormatException
                || t instanceof ClassCastException
                || t instanceof NullPointerException
                || t instanceof UnsupportedOperationException
                || t instanceof IndexOutOfBoundsException) {
            return FailureKind.PERMANENT;
        }

        // ---- network / dependency availability ---------------------------------------------------------------------------
        if (t instanceof ConnectException || t instanceof NoRouteToHostException || t instanceof UnknownHostException
                || t instanceof SocketTimeoutException || t instanceof SocketException || t instanceof TimeoutException
                || t instanceof InterruptedIOException || t instanceof ClosedChannelException
                || t instanceof InterruptedException) {
            return FailureKind.TRANSIENT;
        }

        // ---- Spring's data-access hierarchy (Mongo / Neo4j / JDBC exceptions are translated into it) ----------------------
        if (t instanceof DataAccessResourceFailureException            // is-a NonTransientDataAccessException: keep this first
                || t instanceof TransientDataAccessException
                || t instanceof RecoverableDataAccessException) {
            return FailureKind.TRANSIENT;
        }
        if (t instanceof NonTransientDataAccessException) {            // constraint violations, bad API usage, mapping errors ...
            return FailureKind.PERMANENT;
        }

        // ---- MongoDB driver ----------------------------------------------------------------------------------------------
        if (t instanceof MongoException mongo) {
            return classifyMongo(mongo);
        }

        // ---- Neo4j driver ------------------------------------------------------------------------------------------------
        if (t instanceof Neo4jException neo) {
            return classifyNeo4j(neo);
        }

        // ---- other I/O failures (connection reset, EOF, ...) - after the Json / Socket subclasses were handled above ------
        if (t instanceof IOException) {
            return FailureKind.TRANSIENT;
        }
        return null;
    }

    private static FailureKind classifyMongo(MongoException e) {
        if (e.hasErrorLabel("TransientTransactionError") || e.hasErrorLabel("RetryableWriteError")) {
            return FailureKind.TRANSIENT;
        }
        if (e instanceof MongoTimeoutException || e instanceof MongoSocketException) {
            return FailureKind.TRANSIENT;
        }
        return switch (e.getClass().getSimpleName()) {
            case "MongoNotPrimaryException", "MongoNodeIsRecoveringException", "MongoInterruptedException",
                 "MongoConnectionPoolClearedException", "MongoServerUnavailableException", "MongoExecutionTimeoutException" ->
                    FailureKind.TRANSIENT;
            // MongoCommandException / MongoWriteException / configuration errors: not decisive here - the cause chain and the message
            // are consulted, and an unrecognised failure is PERMANENT.
            default -> null;
        };
    }

    private static FailureKind classifyNeo4j(Neo4jException e) {
        if (e instanceof ServiceUnavailableException || e instanceof SessionExpiredException
                || e instanceof TransientException || e instanceof ConnectionReadTimeoutException) {
            return FailureKind.TRANSIENT;
        }
        String code = e.code();
        if (code != null && code.startsWith("Neo.TransientError.")) {
            return FailureKind.TRANSIENT;
        }
        if (e instanceof ClientException) {
            // "Unable to acquire connection from the pool within configured maximum time of 3000ms" is a ClientException carrying the
            // placeholder code N/A - a saturated or dead pool, not a bad statement. Real Neo.ClientError.* codes are the event's fault.
            if (code == null || "N/A".equalsIgnoreCase(code)) {
                return classifyMessage(e.getMessage());
            }
            return FailureKind.PERMANENT;
        }
        return null;
    }
}
