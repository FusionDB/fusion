package cn.fusiondb.client;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.HostAndPort;
import io.airlift.units.Duration;
import picocli.CommandLine;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.ZoneId;
import java.util.*;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.nullToEmpty;
import static io.trino.client.KerberosUtil.defaultCredentialCachePath;
import static java.util.Collections.emptyMap;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;

/**
 * Created by xiliu on 2020/12/9
 */
public class ClientConfig {
    private static final Splitter NAME_VALUE_SPLITTER = Splitter.on('=').limit(2);
    private static final CharMatcher PRINTABLE_ASCII = CharMatcher.inRange((char) 0x21, (char) 0x7E); // spaces are not allowed
    private static final String DEFAULT_VALUE = "(default: ${DEFAULT-VALUE})";

    @CommandLine.Option(names = "--server", paramLabel = "<server>", defaultValue = "localhost:8080", description = "Trino server location " + DEFAULT_VALUE)
    public String server;

    @CommandLine.Option(names = "--krb5-service-principal-pattern", paramLabel = "<pattern>", defaultValue = "$${SERVICE}@$${HOST}", description = "Remote kerberos service principal pattern " + DEFAULT_VALUE)
    public String krb5ServicePrincipalPattern;

    @CommandLine.Option(names = "--krb5-remote-service-name", paramLabel = "<name>", description = "Remote peer's kerberos service name")
    public String krb5RemoteServiceName;

    @CommandLine.Option(names = "--krb5-config-path", paramLabel = "<path>", defaultValue = "/etc/krb5.conf", description = "Kerberos config file path " + DEFAULT_VALUE)
    public String krb5ConfigPath;

    @CommandLine.Option(names = "--krb5-keytab-path", paramLabel = "<path>", defaultValue = "/etc/krb5.keytab", description = "Kerberos key table path " + DEFAULT_VALUE)
    public String krb5KeytabPath;

    @CommandLine.Option(names = "--krb5-credential-cache-path", paramLabel = "<path>", description = "Kerberos credential cache path")
    public String krb5CredentialCachePath = defaultCredentialCachePath().orElse(null);

    @CommandLine.Option(names = "--krb5-principal", paramLabel = "<principal>", description = "Kerberos principal to be used")
    public String krb5Principal;

    @CommandLine.Option(names = "--krb5-disable-remote-service-hostname-canonicalization", description = "Disable service hostname canonicalization using the DNS reverse lookup")
    public boolean krb5DisableRemoteServiceHostnameCanonicalization;

    @CommandLine.Option(names = "--keystore-path", paramLabel = "<path>", description = "Keystore path")
    public String keystorePath;

    @CommandLine.Option(names = "--keystore-password", paramLabel = "<password>", description = "Keystore password")
    public String keystorePassword;

    @CommandLine.Option(names = "--keystore-type", paramLabel = "<type>", description = "Keystore type")
    public String keystoreType;

    @CommandLine.Option(names = "--truststore-path", paramLabel = "<path>", description = "Truststore path")
    public String truststorePath;

    @CommandLine.Option(names = "--truststore-password", paramLabel = "<password>", description = "Truststore password")
    public String truststorePassword;

    @CommandLine.Option(names = "--truststore-type", paramLabel = "<type>", description = "Truststore type")
    public String truststoreType;

    @CommandLine.Option(names = "--insecure", description = "Skip validation of HTTP server certificates (should only be used for debugging)")
    public boolean insecure;

    @CommandLine.Option(names = "--access-token", paramLabel = "<token>", description = "Access token")
    public String accessToken;

    @CommandLine.Option(names = "--user", paramLabel = "<user>", description = "Username " + DEFAULT_VALUE)
    public String user = System.getProperty("user.name");

    @CommandLine.Option(names = "--password", paramLabel = "<password>", description = "Prompt for password")
    public boolean password;

    @CommandLine.Option(names = "--source", paramLabel = "<source>", defaultValue = "trino-cli", description = "Name of source making query " + DEFAULT_VALUE)
    public String source = "mysql-client@5.7";

    @CommandLine.Option(names = "--client-info", paramLabel = "<info>", description = "Extra information about client making query")
    public String clientInfo;

    @CommandLine.Option(names = "--client-tags", paramLabel = "<tags>", description = "Client tags")
    public String clientTags;

    @CommandLine.Option(names = "--trace-token", paramLabel = "<token>", description = "Trace token")
    public String traceToken;

    @CommandLine.Option(names = "--catalog", paramLabel = "<catalog>", description = "Default catalog")
    public String catalog;

    @CommandLine.Option(names = "--schema", paramLabel = "<schema>", description = "Default schema")
    public String schema;

    @CommandLine.Option(names = {"-f", "--file"}, paramLabel = "<file>", description = "Execute statements from file and exit")
    public String file;

    @CommandLine.Option(names = "--debug", paramLabel = "<debug>", description = "Enable debug information")
    public boolean debug;

    @CommandLine.Option(names = "--progress", paramLabel = "<progress>", description = "Show query progress in batch mode")
    public boolean progress;

    @CommandLine.Option(names = "--log-levels-file", paramLabel = "<file>", description = "Configure log levels for debugging using this file")
    public String logLevelsFile;

    @CommandLine.Option(names = "--execute", paramLabel = "<execute>", description = "Execute specified statements and exit")
    public String execute;

    @CommandLine.Option(names = "--output-format", paramLabel = "<format>", defaultValue = "MYSQL", description = "Output format for batch mode [${COMPLETION-CANDIDATES}] " + DEFAULT_VALUE)
    public ClientConfig.OutputFormat outputFormat = OutputFormat.MYSQL;

    @CommandLine.Option(names = "--resource-estimate", paramLabel = "<estimate>", description = "Resource estimate (property can be used multiple times; format is key=value)")
    public final List<ClientConfig.ClientResourceEstimate> resourceEstimates = new ArrayList<>();

    @CommandLine.Option(names = "--session", paramLabel = "<session>", description = "Session property (property can be used multiple times; format is key=value; use 'SHOW SESSION' to see available properties)")
    public final List<ClientConfig.ClientSessionProperty> sessionProperties = new ArrayList<>();

    @CommandLine.Option(names = "--extra-credential", paramLabel = "<credential>", description = "Extra credentials (property can be used multiple times; format is key=value)")
    public final List<ClientConfig.ClientExtraCredential> extraCredentials = new ArrayList<>();

    @CommandLine.Option(names = "--socks-proxy", paramLabel = "<proxy>", description = "SOCKS proxy to use for server connections")
    public HostAndPort socksProxy;

    @CommandLine.Option(names = "--http-proxy", paramLabel = "<proxy>", description = "HTTP proxy to use for server connections")
    public HostAndPort httpProxy;

    @CommandLine.Option(names = "--client-request-timeout", paramLabel = "<timeout>", defaultValue = "2m", description = "Client request timeout " + DEFAULT_VALUE)
    public Duration clientRequestTimeout;

    @CommandLine.Option(names = "--ignore-errors", description = "Continue processing in batch mode when an error occurs (default is to exit immediately)")
    public boolean ignoreErrors;

    @CommandLine.Option(names = "--timezone", paramLabel = "<timezone>", description = "Session time zone " + DEFAULT_VALUE)
    public ZoneId timeZone = ZoneId.systemDefault();

    @CommandLine.Option(names = "--disable-compression", description = "Disable compression of query results")
    public boolean disableCompression;

    public enum OutputFormat
    {
        ALIGNED,
        VERTICAL,
        TSV,
        TSV_HEADER,
        CSV,
        CSV_HEADER,
        CSV_UNQUOTED,
        CSV_HEADER_UNQUOTED,
        JSON,
        MYSQL,
        NULL
    }

    public ClientSession toClientSession(String server, String user, String catalog, String schema)
    {
        return new ClientSession(
                parseServer(server),
                user,
                source,
                Optional.ofNullable(traceToken),
                parseClientTags(nullToEmpty(clientTags)),
                clientInfo,
                catalog,
                schema,
                null,
                timeZone,
                Locale.getDefault(),
                toResourceEstimates(resourceEstimates),
                toProperties(sessionProperties),
                emptyMap(),
                emptyMap(),
                toExtraCredentials(extraCredentials),
                null,
                clientRequestTimeout,
                disableCompression);
    }

    public static URI parseServer(String server)
    {
        server = server.toLowerCase(ENGLISH);
        if (server.startsWith("http://") || server.startsWith("https://")) {
            return URI.create(server);
        }

        HostAndPort host = HostAndPort.fromString(server);
        try {
            return new URI("http", null, host.getHost(), host.getPortOrDefault(80), null, null, null);
        }
        catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static Set<String> parseClientTags(String clientTagsString)
    {
        Splitter splitter = Splitter.on(',').trimResults().omitEmptyStrings();
        return ImmutableSet.copyOf(splitter.split(nullToEmpty(clientTagsString)));
    }

    public static Map<String, String> toProperties(List<ClientConfig.ClientSessionProperty> sessionProperties)
    {
        ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
        for (ClientConfig.ClientSessionProperty sessionProperty : sessionProperties) {
            String name = sessionProperty.getName();
            if (sessionProperty.getCatalog().isPresent()) {
                name = sessionProperty.getCatalog().get() + "." + name;
            }
            builder.put(name, sessionProperty.getValue());
        }
        return builder.build();
    }

    public static Map<String, String> toResourceEstimates(List<ClientConfig.ClientResourceEstimate> estimates)
    {
        ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
        for (ClientConfig.ClientResourceEstimate estimate : estimates) {
            builder.put(estimate.getResource(), estimate.getEstimate());
        }
        return builder.build();
    }

    public static Map<String, String> toExtraCredentials(List<ClientConfig.ClientExtraCredential> extraCredentials)
    {
        ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
        for (ClientConfig.ClientExtraCredential credential : extraCredentials) {
            builder.put(credential.getName(), credential.getValue());
        }
        return builder.build();
    }

    public static final class ClientResourceEstimate
    {
        private final String resource;
        private final String estimate;

        public ClientResourceEstimate(String resourceEstimate)
        {
            List<String> nameValue = NAME_VALUE_SPLITTER.splitToList(resourceEstimate);
            checkArgument(nameValue.size() == 2, "Resource estimate: %s", resourceEstimate);

            this.resource = nameValue.get(0);
            this.estimate = nameValue.get(1);
            checkArgument(!resource.isEmpty(), "Resource name is empty");
            checkArgument(!estimate.isEmpty(), "Resource estimate is empty");
            checkArgument(PRINTABLE_ASCII.matchesAllOf(resource), "Resource contains spaces or is not ASCII: %s", resource);
            checkArgument(resource.indexOf('=') < 0, "Resource must not contain '=': %s", resource);
            checkArgument(PRINTABLE_ASCII.matchesAllOf(estimate), "Resource estimate contains spaces or is not ASCII: %s", resource);
        }

        @VisibleForTesting
        public ClientResourceEstimate(String resource, String estimate)
        {
            this.resource = requireNonNull(resource, "resource is null");
            this.estimate = estimate;
        }

        public String getResource()
        {
            return resource;
        }

        public String getEstimate()
        {
            return estimate;
        }

        @Override
        public String toString()
        {
            return resource + '=' + estimate;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            ClientConfig.ClientResourceEstimate other = (ClientConfig.ClientResourceEstimate) o;
            return Objects.equals(resource, other.resource) &&
                    Objects.equals(estimate, other.estimate);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(resource, estimate);
        }
    }

    public static final class ClientSessionProperty
    {
        private static final Splitter NAME_SPLITTER = Splitter.on('.');
        private final Optional<String> catalog;
        private final String name;
        private final String value;

        public ClientSessionProperty(String property)
        {
            List<String> nameValue = NAME_VALUE_SPLITTER.splitToList(property);
            checkArgument(nameValue.size() == 2, "Session property: %s", property);

            List<String> nameParts = NAME_SPLITTER.splitToList(nameValue.get(0));
            checkArgument(nameParts.size() == 1 || nameParts.size() == 2, "Invalid session property: %s", property);
            if (nameParts.size() == 1) {
                catalog = Optional.empty();
                name = nameParts.get(0);
            }
            else {
                catalog = Optional.of(nameParts.get(0));
                name = nameParts.get(1);
            }
            value = nameValue.get(1);

            verifyProperty(catalog, name, value);
        }

        public ClientSessionProperty(Optional<String> catalog, String name, String value)
        {
            this.catalog = requireNonNull(catalog, "catalog is null");
            this.name = requireNonNull(name, "name is null");
            this.value = requireNonNull(value, "value is null");

            verifyProperty(catalog, name, value);
        }

        private static void verifyProperty(Optional<String> catalog, String name, String value)
        {
            checkArgument(!catalog.isPresent() || !catalog.get().isEmpty(), "Invalid session property: %s.%s:%s", catalog, name, value);
            checkArgument(!name.isEmpty(), "Session property name is empty");
            checkArgument(catalog.orElse("").indexOf('=') < 0, "Session property catalog must not contain '=': %s", name);
            checkArgument(PRINTABLE_ASCII.matchesAllOf(catalog.orElse("")), "Session property catalog contains spaces or is not ASCII: %s", name);
            checkArgument(name.indexOf('=') < 0, "Session property name must not contain '=': %s", name);
            checkArgument(PRINTABLE_ASCII.matchesAllOf(name), "Session property name contains spaces or is not ASCII: %s", name);
            checkArgument(PRINTABLE_ASCII.matchesAllOf(value), "Session property value contains spaces or is not ASCII: %s", value);
        }

        public Optional<String> getCatalog()
        {
            return catalog;
        }

        public String getName()
        {
            return name;
        }

        public String getValue()
        {
            return value;
        }

        @Override
        public String toString()
        {
            return (catalog.isPresent() ? catalog.get() + '.' : "") + name + '=' + value;
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(catalog, name, value);
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            ClientConfig.ClientSessionProperty other = (ClientConfig.ClientSessionProperty) obj;
            return Objects.equals(this.catalog, other.catalog) &&
                    Objects.equals(this.name, other.name) &&
                    Objects.equals(this.value, other.value);
        }
    }

    public static final class ClientExtraCredential
    {
        private final String name;
        private final String value;

        public ClientExtraCredential(String extraCredential)
        {
            List<String> nameValue = NAME_VALUE_SPLITTER.splitToList(extraCredential);
            checkArgument(nameValue.size() == 2, "Extra credential: %s", extraCredential);

            this.name = nameValue.get(0);
            this.value = nameValue.get(1);
            checkArgument(!name.isEmpty(), "Credential name is empty");
            checkArgument(!value.isEmpty(), "Credential value is empty");
            checkArgument(PRINTABLE_ASCII.matchesAllOf(name), "Credential name contains spaces or is not ASCII: %s", name);
            checkArgument(name.indexOf('=') < 0, "Credential name must not contain '=': %s", name);
            checkArgument(PRINTABLE_ASCII.matchesAllOf(value), "Credential value contains space or is not ASCII: %s", name);
        }

        public ClientExtraCredential(String name, String value)
        {
            this.name = requireNonNull(name, "name is null");
            this.value = value;
        }

        public String getName()
        {
            return name;
        }

        public String getValue()
        {
            return value;
        }

        @Override
        public String toString()
        {
            return name + '=' + value;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            ClientConfig.ClientExtraCredential other = (ClientConfig.ClientExtraCredential) o;
            return Objects.equals(name, other.name) && Objects.equals(value, other.value);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(name, value);
        }
    }
}
