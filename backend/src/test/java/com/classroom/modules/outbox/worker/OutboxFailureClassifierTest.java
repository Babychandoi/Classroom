package com.classroom.modules.outbox.worker;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.MongoException;
import com.mongodb.MongoSocketOpenException;
import com.mongodb.MongoSocketReadTimeoutException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.ServerAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.exceptions.ClientException;
import org.neo4j.driver.exceptions.ConnectionReadTimeoutException;
import org.neo4j.driver.exceptions.ServiceUnavailableException;
import org.neo4j.driver.exceptions.SessionExpiredException;
import org.neo4j.driver.exceptions.TransientException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** R20-04b: which failures are the dependency's fault (never dead-letter) and which are the event's (count toward dead-letter). */
class OutboxFailureClassifierTest {

    private static void assertTransient(Throwable t) {
        assertEquals(FailureKind.TRANSIENT, OutboxFailureClassifier.classify(t), () -> "expected TRANSIENT for " + t);
    }

    private static void assertPermanent(Throwable t) {
        assertEquals(FailureKind.PERMANENT, OutboxFailureClassifier.classify(t), () -> "expected PERMANENT for " + t);
    }

    @Test
    @DisplayName("connectivity failures are TRANSIENT: refused / unreachable / unknown host / socket + connect + read timeouts / IO resets")
    void networkFailuresAreTransient() {
        assertTransient(new ConnectException("Connection refused"));
        assertTransient(new SocketTimeoutException("Read timed out"));
        assertTransient(new SocketException("Connection reset"));
        assertTransient(new UnknownHostException("neo4j"));
        assertTransient(new TimeoutException("attempt timed out"));
        assertTransient(new EOFException("stream ended"));
        assertTransient(new IOException("Broken pipe"));
    }

    @Test
    @DisplayName("MongoDB outages are TRANSIENT: server-selection timeout, socket open/read timeout, retryable labels, wrapped by Spring")
    void mongoOutagesAreTransient() {
        ServerAddress address = new ServerAddress("mongodb", 27017);
        assertTransient(new MongoTimeoutException("Timed out after 3000 ms while waiting for a server that matches WritableServerSelector"));
        assertTransient(new MongoSocketOpenException("Exception opening socket", address, new ConnectException("refused")));
        assertTransient(new MongoSocketReadTimeoutException("Timeout while receiving message", address, new SocketTimeoutException()));
        MongoException labelled = new MongoException("write concern failed");
        labelled.addLabel("RetryableWriteError");
        assertTransient(labelled);
        // Spring's translation of the driver exceptions
        assertTransient(new DataAccessResourceFailureException("Timed out after 3000 ms", new MongoTimeoutException("Timed out")));
        // a wrapper without a decisive type defers to its cause
        assertTransient(new RuntimeException("MongoDB projection failed", new MongoTimeoutException("Timed out after 3000 ms")));
    }

    @Test
    @DisplayName("Neo4j outages are TRANSIENT: ServiceUnavailable, SessionExpired, Transient, read timeout, pool-acquisition ClientException")
    void neo4jOutagesAreTransient() {
        assertTransient(new ServiceUnavailableException("Unable to connect to neo4j:7687, ensure the database is running"));
        assertTransient(new SessionExpiredException("Server at neo4j:7687 no longer accepts writes"));
        assertTransient(new TransientException("Neo.TransientError.Transaction.DeadlockDetected", "deadlock"));
        assertTransient(new ConnectionReadTimeoutException("Connection read timed out"));
        assertTransient(new ClientException("Unable to acquire connection from the pool within configured maximum time of 3000ms"));
        assertTransient(new TransientDataAccessResourceException("neo4j is restarting"));
        assertTransient(new QueryTimeoutException("statement timed out"));
        // the real-world shape: Neo4jSyncService wraps whatever the driver/Spring threw
        assertTransient(new RuntimeException("Neo4j projection failed: Unable to connect to neo4j:7687",
                new DataAccessResourceFailureException("Unable to connect", new ServiceUnavailableException("Unable to connect"))));
    }

    @Test
    @DisplayName("the event's own faults are PERMANENT: bad JSON, illegal argument, constraint violations, Neo.ClientError.*, unknown errors")
    void eventFaultsArePermanent() throws Exception {
        assertPermanent(new IllegalArgumentException("classId must not be null"));
        assertPermanent(new NumberFormatException("For input string: x"));
        assertPermanent(new NullPointerException("payload"));
        assertPermanent(new ClassCastException("cannot cast"));
        assertPermanent(new DataIntegrityViolationException("Duplicate entry"));
        assertPermanent(new DuplicateKeyException("E11000 duplicate key"));
        assertPermanent(new InvalidDataAccessApiUsageException("bad query"));
        assertPermanent(new ClientException("Neo.ClientError.Statement.SyntaxError", "Invalid input 'X'"));
        assertPermanent(new ClientException("Neo.ClientError.Schema.ConstraintValidationFailed", "already exists"));
        assertPermanent(new IllegalStateException("Neo4j projection is enabled but Neo4jClient is not available"));
        assertPermanent(new RuntimeException("something nobody has seen before"));
        assertPermanent(new MongoException(11000, "E11000 duplicate key error collection"));
        try {
            new ObjectMapper().readTree("{not json");
        } catch (JsonParseException e) {
            assertPermanent(e); // is-a IOException - must NOT be mistaken for a network failure
            assertPermanent(new RuntimeException("Cannot serialize outbox event payload", e));
        }
    }

    @Test
    @DisplayName("an unrecognised wrapper is classified by the text of its message chain; null and blank are PERMANENT")
    void messageFallback() {
        assertTransient(new RuntimeException("Neo4j projection failed: Unable to connect to neo4j:7687, ensure the database is running"));
        assertTransient(new RuntimeException("outer", new RuntimeException("Connection refused (Connection refused)")));
        assertTransient(new RuntimeException("MongoDB connection timeout"));
        assertPermanent(new RuntimeException("MongoDB persistent rejection"));
        assertPermanent(new RuntimeException());
        assertPermanent(null);
        assertEquals(FailureKind.PERMANENT, OutboxFailureClassifier.classifyMessage(null));
        assertEquals(FailureKind.PERMANENT, OutboxFailureClassifier.classifyMessage("  "));
    }

    @Test
    @DisplayName("classifyMessage recognises the error texts of events dead-lettered by older versions (re-drive input)")
    void legacyDeadLetterMessages() {
        // Real messages taken from the R20 reviewer's drill (before failure_kind existed)
        assertEquals(FailureKind.TRANSIENT, OutboxFailureClassifier.classifyMessage(
                "Neo4j: Neo4j projection failed: Unable to connect to neo4j:7687, ensure the database is running and that there is a working network connection to it."));
        assertEquals(FailureKind.TRANSIENT, OutboxFailureClassifier.classifyMessage(
                "MongoDB: Timed out after 30000 ms while waiting for a server that matches ReadPreferenceServerSelector"));
        assertEquals(FailureKind.PERMANENT, OutboxFailureClassifier.classifyMessage("Reclaimed from stale PROCESSING after exceeding max retries"));
        assertEquals(FailureKind.PERMANENT, OutboxFailureClassifier.classifyMessage("MongoDB repository unavailable (projection incomplete)"));
    }

    @Test
    @DisplayName("a cause cycle or a very deep chain cannot hang the classifier")
    void cyclesAndDepthAreBounded() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);
        assertPermanent(a);

        Throwable deep = new RuntimeException("bottom");
        for (int i = 0; i < 100; i++) {
            deep = new RuntimeException("level " + i, deep);
        }
        assertPermanent(deep);
        assertTransient(List.of(new ConnectException("x")).get(0));
    }
}
