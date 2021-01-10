/*
 * Copyright 2020 FusionLab, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. See accompanying LICENSE file.
 */

package cn.fusiondb.client;

import cn.fusiondb.mysql.ConnectContext;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import io.airlift.log.Logger;
import io.trino.cli.*;
import io.trino.client.*;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedStringBuilder;
import sun.misc.Signal;
import sun.misc.SignalHandler;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.trino.cli.TerminalUtils.isRealTerminal;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.jline.utils.AttributedStyle.*;
import static org.jline.utils.AttributedStyle.DEFAULT;

/**
 * Created by xiliu on 2020/12/9
 */
public class Query
        implements Closeable
{
    private static final Logger logger = Logger.get(Query.class);
    private static final Signal SIGINT = new Signal("INT");

    private final AtomicBoolean ignoreUserInterrupt = new AtomicBoolean();
    private final StatementClient client;
    private final boolean debug;
    private ConnectContext context;

    public Query(StatementClient client, boolean debug)
    {
        this.client = requireNonNull(client, "client is null");
        this.debug = debug;
    }

    public Optional<String> getSetCatalog()
    {
        return client.getSetCatalog();
    }

    public Optional<String> getSetSchema()
    {
        return client.getSetSchema();
    }

    public Map<String, String> getSetSessionProperties()
    {
        return client.getSetSessionProperties();
    }

    public Set<String> getResetSessionProperties()
    {
        return client.getResetSessionProperties();
    }

    public Map<String, ClientSelectedRole> getSetRoles()
    {
        return client.getSetRoles();
    }

    public Map<String, String> getAddedPreparedStatements()
    {
        return client.getAddedPreparedStatements();
    }

    public Set<String> getDeallocatedPreparedStatements()
    {
        return client.getDeallocatedPreparedStatements();
    }

    public String getStartedTransactionId()
    {
        return client.getStartedTransactionId();
    }

    public boolean isClearTransactionId()
    {
        return client.isClearTransactionId();
    }

    public boolean renderOutput(ConnectContext context, Terminal terminal, PrintStream out, ClientConfig.OutputFormat outputFormat, boolean interactive)
    {
        this.context = context;
        Thread clientThread = Thread.currentThread();
        SignalHandler oldHandler = Signal.handle(SIGINT, signal -> {
            if (ignoreUserInterrupt.get() || client.isClientAborted()) {
                return;
            }
            client.close();
            clientThread.interrupt();
        });
        try {
            return renderQueryOutput(terminal, out, outputFormat, interactive);
        }
        finally {
            Signal.handle(SIGINT, oldHandler);
            Thread.interrupted(); // clear interrupt status
        }
    }

    private boolean renderQueryOutput(Terminal terminal, PrintStream out, ClientConfig.OutputFormat outputFormat, boolean interactive)
    {
        StatusPrinter statusPrinter = null;
        @SuppressWarnings("resource")
        PrintStream errorChannel = interactive ? out : System.err;
        WarningsPrinter warningsPrinter = new Query.PrintStreamWarningsPrinter(System.err);

        if (interactive) {
            statusPrinter = new StatusPrinter(client, out, debug);
            statusPrinter.printInitialStatusUpdates(terminal);
        }
        else {
            processInitialStatusUpdates(warningsPrinter);
        }

        // if running or finished
        if (client.isRunning() || (client.isFinished() && client.finalStatusInfo().getError() == null)) {
            QueryStatusInfo results = client.isRunning() ? client.currentStatusInfo() : client.finalStatusInfo();
            if (results.getUpdateType() != null) {
                renderUpdate(errorChannel, results);
            }
            else if (results.getColumns() == null) {
                errorChannel.printf("Query %s has no columns\n", results.getId());
                return false;
            }
            else {
                renderResults(outputFormat, interactive, results.getColumns());
            }
        }

        checkState(!client.isRunning());

        if (statusPrinter != null) {
            // Print all warnings at the end of the query
            new Query.PrintStreamWarningsPrinter(System.err).print(client.finalStatusInfo().getWarnings(), true, true);
            statusPrinter.printFinalInfo();
        }
        else {
            // Print remaining warnings separated
            warningsPrinter.print(client.finalStatusInfo().getWarnings(), true, true);
        }

        if (client.isClientAborted()) {
            errorChannel.println("Query aborted by user");
            return false;
        }
        if (client.isClientError()) {
            errorChannel.println("Query is gone (server restarted?)");
            return false;
        }

        verify(client.isFinished());
        if (client.finalStatusInfo().getError() != null) {
            renderFailure();
            return false;
        }

        return true;
    }

    private void processInitialStatusUpdates(WarningsPrinter warningsPrinter)
    {
        while (client.isRunning() && (client.currentData().getData() == null)) {
            warningsPrinter.print(client.currentStatusInfo().getWarnings(), true, false);
            try {
                client.advance();
            }
            catch (RuntimeException e) {
                logger.debug(e, "error printing status");
            }
        }
        List<Warning> warnings;
        if (client.isRunning()) {
            warnings = client.currentStatusInfo().getWarnings();
        }
        else {
            warnings = client.finalStatusInfo().getWarnings();
        }
        warningsPrinter.print(warnings, false, true);
    }

    private void renderUpdate(PrintStream out, QueryStatusInfo results)
    {
        String status = results.getUpdateType();
        if (results.getUpdateCount() != null) {
            long count = results.getUpdateCount();
            status += format(": %s row%s", count, (count != 1) ? "s" : "");
        }
        out.println(status);
        discardResults();
    }

    private void discardResults()
    {
        try (OutputHandler handler = new OutputHandler(new NullPrinter())) {
            handler.processRows(client);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void renderResults(ClientConfig.OutputFormat outputFormat, boolean interactive, List<Column> columns)
    {
        try {
            doRenderResults(context, outputFormat, interactive, columns);
        }
        catch (QueryAbortedException e) {
//            System.out.println("(query aborted by user)");
            logger.warn("(query aborted by user)");
            client.close();
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void doRenderResults(ConnectContext context, ClientConfig.OutputFormat format, boolean interactive, List<Column> columns)
            throws IOException
    {
        if (interactive) {
            pageOutput(context, format, columns);
        }
        else {
            sendOutput(context, format, columns);
        }
    }

    private void pageOutput(ConnectContext context, ClientConfig.OutputFormat format, List<Column> columns)
            throws IOException
    {
        try (Pager pager = Pager.create();
             ThreadInterruptor clientThread = new ThreadInterruptor();
             OutputHandler handler = createOutputHandler(context, format, columns)) {
            if (!pager.isNullPager()) {
                // ignore the user pressing ctrl-C while in the pager
                ignoreUserInterrupt.set(true);
                pager.getFinishFuture().thenRun(() -> {
                    ignoreUserInterrupt.set(false);
                    client.close();
                    clientThread.interrupt();
                });
            }
            handler.processRows(client);
        }
        catch (RuntimeException | IOException e) {
            if (client.isClientAborted() && !(e instanceof QueryAbortedException)) {
                throw new QueryAbortedException(e);
            }
            throw e;
        }
    }

    private void sendOutput(ConnectContext context, ClientConfig.OutputFormat format, List<Column> fieldNames)
            throws IOException
    {
        try (OutputHandler handler = createOutputHandler(context, format, fieldNames)) {
            handler.processRows(client);
        }
    }

    private static OutputHandler createOutputHandler(ConnectContext context, ClientConfig.OutputFormat format, List<Column> columns)
    {
        return new OutputHandler(createOutputPrinter(context, format, columns));
    }

    private static OutputPrinter createOutputPrinter(ConnectContext context, ClientConfig.OutputFormat format, List<Column> columns)
    {
        List<String> fieldNames = columns.stream()
                .map(Column::getName)
                .collect(toImmutableList());

        switch (format) {
            case MYSQL:
                return new MySQLPrinter(context, fieldNames);
            case NULL:
                return new NullPrinter();
        }
        throw new RuntimeException(format + " not supported");
    }

    private static Writer createWriter(OutputStream out)
    {
        return new BufferedWriter(new OutputStreamWriter(out, UTF_8), 16384);
    }

    @Override
    public void close()
    {
        client.close();
    }

    public void renderFailure()
    {
        StringBuilder stringBuilder = new StringBuilder();

        QueryStatusInfo results = client.finalStatusInfo();
        QueryError error = results.getError();
        checkState(error != null);

        String message = String.format("Query %s failed: %s%n", results.getId(), error.getMessage());
        stringBuilder.append(message);
        if (debug && (error.getFailureInfo() != null)) {
            String stacktrace = ExceptionUtils.getStackTrace(error.getFailureInfo().toException());
            stringBuilder.append(stacktrace);
        }
        if (error.getErrorLocation() != null) {
            stringBuilder.append(renderErrorLocation(client.getQuery(), error.getErrorLocation()).toString());
        }

        throw new RuntimeException(stringBuilder.toString());
    }

    private static StringBuilder renderErrorLocation(String query, ErrorLocation location)
    {
        StringBuilder stringBuilder = new StringBuilder();
        List<String> lines = ImmutableList.copyOf(Splitter.on('\n').split(query).iterator());

        String errorLine = lines.get(location.getLineNumber() - 1);
        String good = errorLine.substring(0, location.getColumnNumber() - 1);
        String bad = errorLine.substring(location.getColumnNumber() - 1);

        if ((location.getLineNumber() == lines.size()) && bad.trim().isEmpty()) {
            bad = " <EOF>";
        }

        if (isRealTerminal()) {
            AttributedStringBuilder builder = new AttributedStringBuilder();

            builder.style(DEFAULT.foreground(CYAN));
            for (int i = 1; i < location.getLineNumber(); i++) {
                builder.append(lines.get(i - 1)).append("\n");
            }
            builder.append(good);

            builder.style(DEFAULT.foreground(RED));
            builder.append(bad).append("\n");
            for (int i = location.getLineNumber(); i < lines.size(); i++) {
                builder.append(lines.get(i)).append("\n");
            }

            builder.style(DEFAULT);
            stringBuilder.append(builder.toAnsi()).append("\n");
        }
        else {
            String prefix = format("LINE %s: ", location.getLineNumber());
            String padding = Strings.repeat(" ", prefix.length() + (location.getColumnNumber() - 1));
            stringBuilder.append(prefix + errorLine).append("\n");
            stringBuilder.append(padding + "^").append("\n");
        }
        return stringBuilder;
    }

    private static class PrintStreamWarningsPrinter
            extends AbstractWarningsPrinter
    {
        private final PrintStream printStream;

        PrintStreamWarningsPrinter(PrintStream printStream)
        {
            super(OptionalInt.empty());
            this.printStream = requireNonNull(printStream, "printStream is null");
        }

        @Override
        protected void print(List<String> warnings)
        {
            warnings.stream()
                    .forEach(printStream::println);
        }

        @Override
        protected void printSeparator()
        {
            printStream.println();
        }
    }
}
