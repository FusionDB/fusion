package cn.fusiondb.client;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import io.airlift.json.JsonCodec;
import io.airlift.units.Duration;
import io.trino.client.*;
import okhttp3.*;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static cn.fusiondb.client.FusionHeaders.*;
import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.net.HttpHeaders.ACCEPT_ENCODING;
import static com.google.common.net.HttpHeaders.USER_AGENT;
import static io.airlift.json.JsonCodec.jsonCodec;
import static io.trino.client.ProtocolHeaders.TRINO_HEADERS;
import static java.lang.String.format;
import static java.net.HttpURLConnection.*;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Created by xiliu on 2020/12/9
 */
@ThreadSafe
class StatementClientV1
        implements StatementClient
{
    private static final MediaType MEDIA_TYPE_TEXT = MediaType.parse("text/plain; charset=utf-8");
    private static final JsonCodec<QueryResults> QUERY_RESULTS_CODEC = jsonCodec(QueryResults.class);

    private static final Splitter SESSION_HEADER_SPLITTER = Splitter.on('=').limit(2).trimResults();
    private static final String USER_AGENT_VALUE = StatementClientV1.class.getSimpleName() +
            "/" +
            firstNonNull(StatementClientV1.class.getPackage().getImplementationVersion(), "unknown");

    private final OkHttpClient httpClient;
    private final String query;
    private final AtomicReference<QueryResults> currentResults = new AtomicReference<>();
    private final AtomicReference<String> setCatalog = new AtomicReference<>();
    private final AtomicReference<String> setSchema = new AtomicReference<>();
    private final AtomicReference<String> setPath = new AtomicReference<>();
    private final Map<String, String> setSessionProperties = new ConcurrentHashMap<>();
    private final Set<String> resetSessionProperties = Sets.newConcurrentHashSet();
    private final Map<String, ClientSelectedRole> setRoles = new ConcurrentHashMap<>();
    private final Map<String, String> addedPreparedStatements = new ConcurrentHashMap<>();
    private final Set<String> deallocatedPreparedStatements = Sets.newConcurrentHashSet();
    private final AtomicReference<String> startedTransactionId = new AtomicReference<>();
    private final AtomicBoolean clearTransactionId = new AtomicBoolean();
    private final ZoneId timeZone;
    private final Duration requestTimeoutNanos;
    private final String user;
    private final String clientCapabilities;
    private final boolean compressionDisabled;

    private final AtomicReference<StatementClientV1.State> state = new AtomicReference<>(StatementClientV1.State.RUNNING);

    public StatementClientV1(OkHttpClient httpClient, ClientSession session, String query)
    {
        requireNonNull(httpClient, "httpClient is null");
        requireNonNull(session, "session is null");
        requireNonNull(query, "query is null");

        this.httpClient = httpClient;
        this.timeZone = session.getTimeZone();
        this.query = query;
        this.requestTimeoutNanos = session.getClientRequestTimeout();
        this.user = session.getUser();
        this.clientCapabilities = Joiner.on(",").join(ClientCapabilities.values());
        this.compressionDisabled = session.isCompressionDisabled();

        Request request = buildQueryRequest(session, query);

        JsonResponse<QueryResults> response = JsonResponse.execute(QUERY_RESULTS_CODEC, httpClient, request);
        if ((response.getStatusCode() != HTTP_OK) || !response.hasValue()) {
            state.compareAndSet(StatementClientV1.State.RUNNING, StatementClientV1.State.CLIENT_ERROR);
            throw requestFailedException("starting query", request, response);
        }

        processResponse(response.getHeaders(), response.getValue());
    }

    private Request buildQueryRequest(ClientSession session, String query)
    {
        HttpUrl url = HttpUrl.get(session.getServer());
        if (url == null) {
            throw new ClientException("Invalid server URL: " + session.getServer());
        }
        url = url.newBuilder().encodedPath("/v1/statement").build();

        Request.Builder builder = prepareRequest(url)
                .post(RequestBody.create(MEDIA_TYPE_TEXT, query));

        if (session.getSource() != null) {
            builder.addHeader(TRINO_HEADERS.requestSource(), session.getSource());
        }

        session.getTraceToken().ifPresent(token -> builder.addHeader(TRINO_HEADERS.requestTraceToken(), token));

        if (session.getClientTags() != null && !session.getClientTags().isEmpty()) {
            builder.addHeader(TRINO_HEADERS.requestClientTags(), Joiner.on(",").join(session.getClientTags()));
        }
        if (session.getClientInfo() != null) {
            builder.addHeader(TRINO_HEADERS.requestClientInfo(), session.getClientInfo());
        }
        if (session.getCatalog() != null) {
            builder.addHeader(TRINO_HEADERS.requestCatalog(), session.getCatalog());
        }
        if (session.getSchema() != null) {
            builder.addHeader(TRINO_HEADERS.requestSchema(), session.getSchema());
        }
        if (session.getPath() != null) {
            builder.addHeader(TRINO_HEADERS.requestPath(), session.getPath());
        }
        builder.addHeader(TRINO_HEADERS.requestTimeZone(), session.getTimeZone().getId());
        if (session.getLocale() != null) {
            builder.addHeader(TRINO_HEADERS.requestLanguage(), session.getLocale().toLanguageTag());
        }

        Map<String, String> property = session.getProperties();
        for (Map.Entry<String, String> entry : property.entrySet()) {
            builder.addHeader(TRINO_HEADERS.requestSession(), entry.getKey() + "=" + urlEncode(entry.getValue()));
        }

        Map<String, String> resourceEstimates = session.getResourceEstimates();
        for (Map.Entry<String, String> entry : resourceEstimates.entrySet()) {
            builder.addHeader(TRINO_HEADERS.requestResourceEstimate(), entry.getKey() + "=" + urlEncode(entry.getValue()));
        }

        Map<String, ClientSelectedRole> roles = session.getRoles();
        for (Map.Entry<String, ClientSelectedRole> entry : roles.entrySet()) {
            builder.addHeader(TRINO_HEADERS.requestRole(), entry.getKey() + '=' + urlEncode(entry.getValue().toString()));
        }

        Map<String, String> extraCredentials = session.getExtraCredentials();
        for (Map.Entry<String, String> entry : extraCredentials.entrySet()) {
            builder.addHeader(TRINO_HEADERS.requestExtraCredential(), entry.getKey() + "=" + urlEncode(entry.getValue()));
        }

        Map<String, String> statements = session.getPreparedStatements();
        for (Map.Entry<String, String> entry : statements.entrySet()) {
            builder.addHeader(TRINO_HEADERS.requestPreparedStatement(), urlEncode(entry.getKey()) + "=" + urlEncode(entry.getValue()));
        }

        builder.addHeader(TRINO_HEADERS.requestTransactionId(), session.getTransactionId() == null ? "NONE" : session.getTransactionId());

        builder.addHeader(TRINO_HEADERS.requestClientCapabilities(), clientCapabilities);

        return builder.build();
    }

    @Override
    public String getQuery()
    {
        return query;
    }

    @Override
    public ZoneId getTimeZone()
    {
        return timeZone;
    }

    @Override
    public boolean isRunning()
    {
        return state.get() == StatementClientV1.State.RUNNING;
    }

    @Override
    public boolean isClientAborted()
    {
        return state.get() == StatementClientV1.State.CLIENT_ABORTED;
    }

    @Override
    public boolean isClientError()
    {
        return state.get() == StatementClientV1.State.CLIENT_ERROR;
    }

    @Override
    public boolean isFinished()
    {
        return state.get() == StatementClientV1.State.FINISHED;
    }

    @Override
    public StatementStats getStats()
    {
        return currentResults.get().getStats();
    }

    @Override
    public QueryStatusInfo currentStatusInfo()
    {
        return currentResults.get();
    }

    @Override
    public QueryData currentData()
    {
        checkState(isRunning(), "current position is not valid (cursor past end)");
        return currentResults.get();
    }

    @Override
    public QueryStatusInfo finalStatusInfo()
    {
        checkState(!isRunning(), "current position is still valid");
        return currentResults.get();
    }

    @Override
    public Optional<String> getSetCatalog()
    {
        return Optional.ofNullable(setCatalog.get());
    }

    @Override
    public Optional<String> getSetSchema()
    {
        return Optional.ofNullable(setSchema.get());
    }

    @Override
    public Optional<String> getSetPath()
    {
        return Optional.ofNullable(setPath.get());
    }

    @Override
    public Map<String, String> getSetSessionProperties()
    {
        return ImmutableMap.copyOf(setSessionProperties);
    }

    @Override
    public Set<String> getResetSessionProperties()
    {
        return ImmutableSet.copyOf(resetSessionProperties);
    }

    @Override
    public Map<String, ClientSelectedRole> getSetRoles()
    {
        return ImmutableMap.copyOf(setRoles);
    }

    @Override
    public Map<String, String> getAddedPreparedStatements()
    {
        return ImmutableMap.copyOf(addedPreparedStatements);
    }

    @Override
    public Set<String> getDeallocatedPreparedStatements()
    {
        return ImmutableSet.copyOf(deallocatedPreparedStatements);
    }

    @Override
    @Nullable
    public String getStartedTransactionId()
    {
        return startedTransactionId.get();
    }

    @Override
    public boolean isClearTransactionId()
    {
        return clearTransactionId.get();
    }

    private Request.Builder prepareRequest(HttpUrl url)
    {
        Request.Builder builder = new Request.Builder()
                .addHeader(TRINO_HEADERS.requestUser(), user)
                .addHeader(USER_AGENT, USER_AGENT_VALUE)
                .url(url);
        if (compressionDisabled) {
            builder.header(ACCEPT_ENCODING, "identity");
        }
        return builder;
    }

    @Override
    public boolean advance()
    {
        if (!isRunning()) {
            return false;
        }

        URI nextUri = currentStatusInfo().getNextUri();
        if (nextUri == null) {
            state.compareAndSet(StatementClientV1.State.RUNNING, StatementClientV1.State.FINISHED);
            return false;
        }

        Request request = prepareRequest(HttpUrl.get(nextUri)).build();

        Exception cause = null;
        long start = System.nanoTime();
        long attempts = 0;

        while (true) {
            if (isClientAborted()) {
                return false;
            }

            Duration sinceStart = Duration.nanosSince(start);
            if (attempts > 0 && sinceStart.compareTo(requestTimeoutNanos) > 0) {
                state.compareAndSet(StatementClientV1.State.RUNNING, StatementClientV1.State.CLIENT_ERROR);
                throw new RuntimeException(format("Error fetching next (attempts: %s, duration: %s)", attempts, sinceStart), cause);
            }

            if (attempts > 0) {
                // back-off on retry
                try {
                    MILLISECONDS.sleep(attempts * 100);
                }
                catch (InterruptedException e) {
                    try {
                        close();
                    }
                    finally {
                        Thread.currentThread().interrupt();
                    }
                    state.compareAndSet(StatementClientV1.State.RUNNING, StatementClientV1.State.CLIENT_ERROR);
                    throw new RuntimeException("StatementClient thread was interrupted");
                }
            }
            attempts++;

            JsonResponse<QueryResults> response;
            try {
                response = JsonResponse.execute(QUERY_RESULTS_CODEC, httpClient, request);
            }
            catch (RuntimeException e) {
                cause = e;
                continue;
            }

            if ((response.getStatusCode() == HTTP_OK) && response.hasValue()) {
                processResponse(response.getHeaders(), response.getValue());
                return true;
            }

            if (response.getStatusCode() != HTTP_UNAVAILABLE) {
                state.compareAndSet(StatementClientV1.State.RUNNING, StatementClientV1.State.CLIENT_ERROR);
                throw requestFailedException("fetching next", request, response);
            }
        }
    }

    private void processResponse(Headers headers, QueryResults results)
    {
        setCatalog.set(headers.get(TRINO_HEADERS.responseSetCatalog()));
        setSchema.set(headers.get(TRINO_HEADERS.responseSetSchema()));
        setPath.set(headers.get(TRINO_HEADERS.responseSetPath()));

        for (String setSession : headers.values(TRINO_HEADERS.responseSetSession())) {
            List<String> keyValue = SESSION_HEADER_SPLITTER.splitToList(setSession);
            if (keyValue.size() != 2) {
                continue;
            }
            setSessionProperties.put(keyValue.get(0), urlDecode(keyValue.get(1)));
        }
        resetSessionProperties.addAll(headers.values(TRINO_HEADERS.responseClearSession()));

        for (String setRole : headers.values(TRINO_HEADERS.responseSetRole())) {
            List<String> keyValue = SESSION_HEADER_SPLITTER.splitToList(setRole);
            if (keyValue.size() != 2) {
                continue;
            }
            setRoles.put(keyValue.get(0), ClientSelectedRole.valueOf(urlDecode(keyValue.get(1))));
        }

        for (String entry : headers.values(TRINO_HEADERS.responseAddedPrepare())) {
            List<String> keyValue = SESSION_HEADER_SPLITTER.splitToList(entry);
            if (keyValue.size() != 2) {
                continue;
            }
            addedPreparedStatements.put(urlDecode(keyValue.get(0)), urlDecode(keyValue.get(1)));
        }
        for (String entry : headers.values(TRINO_HEADERS.responseDeallocatedPrepare())) {
            deallocatedPreparedStatements.add(urlDecode(entry));
        }

        String startedTransactionId = headers.get(TRINO_HEADERS.responseStartedTransactionId());
        if (startedTransactionId != null) {
            this.startedTransactionId.set(startedTransactionId);
        }
        if (headers.get(TRINO_HEADERS.responseClearTransactionId()) != null) {
            clearTransactionId.set(true);
        }

        currentResults.set(results);
    }

    private RuntimeException requestFailedException(String task, Request request, JsonResponse<QueryResults> response)
    {
        if (!response.hasValue()) {
            if (response.getStatusCode() == HTTP_UNAUTHORIZED) {
                return new ClientException("Authentication failed" +
                        Optional.ofNullable(response.getStatusMessage())
                                .map(message -> ": " + message)
                                .orElse(""));
            }
            return new RuntimeException(
                    format("Error %s at %s returned an invalid response: %s [Error: %s]", task, request.url(), response, response.getResponseBody()),
                    response.getException());
        }
        return new RuntimeException(format("Error %s at %s returned HTTP %s", task, request.url(), response.getStatusCode()));
    }

    @Override
    public void cancelLeafStage()
    {
        checkState(!isClientAborted(), "client is closed");

        URI uri = currentStatusInfo().getPartialCancelUri();
        if (uri != null) {
            httpDelete(uri);
        }
    }

    @Override
    public void close()
    {
        // If the query is not done, abort the query.
        if (state.compareAndSet(StatementClientV1.State.RUNNING, StatementClientV1.State.CLIENT_ABORTED)) {
            URI uri = currentResults.get().getNextUri();
            if (uri != null) {
                httpDelete(uri);
            }
        }
    }

    private void httpDelete(URI uri)
    {
        Request request = prepareRequest(HttpUrl.get(uri))
                .delete()
                .build();
        try {
            httpClient.newCall(request)
                    .execute()
                    .close();
        }
        catch (IOException ignored) {
            // callers expect this method not to throw
        }
    }

    private static String urlEncode(String value)
    {
        try {
            return URLEncoder.encode(value, "UTF-8");
        }
        catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    private static String urlDecode(String value)
    {
        try {
            return URLDecoder.decode(value, "UTF-8");
        }
        catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    private enum State
    {
        /**
         * submitted to server, not in terminal state (including planning, queued, running, etc)
         */
        RUNNING,
        CLIENT_ERROR,
        CLIENT_ABORTED,
        /**
         * finished on remote Presto server (including failed and successfully completed)
         */
        FINISHED,
    }
}

