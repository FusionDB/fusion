/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.airlift.node.NodeInfo;
import io.trino.client.CoordinatorInfo;
import io.trino.client.NodeVersion;
import io.trino.client.ServerInfo;
import io.trino.metadata.NodeState;
import io.trino.server.security.ResourceSecurity;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.base.Preconditions.checkState;
import static io.airlift.units.Duration.nanosSince;
import static io.trino.metadata.NodeState.ACTIVE;
import static io.trino.metadata.NodeState.SHUTTING_DOWN;
import static io.trino.server.security.ResourceSecurity.AccessType.*;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.MediaType.TEXT_PLAIN;
import static javax.ws.rs.core.Response.Status.BAD_REQUEST;

@Path("/v1/info")
public class ServerInfoResource
{
    private final NodeVersion version;
    private final String environment;
    private final boolean coordinator;
    private final GracefulShutdownHandler shutdownHandler;
    private final long startTime = System.nanoTime();
    private final AtomicBoolean startupComplete = new AtomicBoolean();
    private static CoordinatorInfo coordinatorInfo;

    @Inject
    public ServerInfoResource(NodeVersion nodeVersion, NodeInfo nodeInfo, ServerConfig serverConfig, GracefulShutdownHandler shutdownHandler)
    {
        this.version = requireNonNull(nodeVersion, "nodeVersion is null");
        this.environment = requireNonNull(nodeInfo, "nodeInfo is null").getEnvironment();
        this.coordinator = requireNonNull(serverConfig, "serverConfig is null").isCoordinator();
        this.shutdownHandler = requireNonNull(shutdownHandler, "shutdownHandler is null");
        this.coordinatorInfo = new CoordinatorInfo(environment, System.currentTimeMillis());
    }

    @ResourceSecurity(PUBLIC)
    @GET
    @Produces(APPLICATION_JSON)
    public ServerInfo getInfo()
    {
        boolean starting = !startupComplete.get();
        return new ServerInfo(version, environment, coordinator, starting, Optional.of(nanosSince(startTime)));
    }

    @ResourceSecurity(PUBLIC)
    @GET
    @Path("coordinator")
    @Produces(APPLICATION_JSON)
    public CoordinatorInfo getCoordinator()
    {
        return coordinatorInfo;
    }

    @POST
    @Path("coordinator")
    @Produces(APPLICATION_JSON)
    @Consumes(APPLICATION_JSON)
    @ResourceSecurity(AUTHENTICATED_USER)
    public Boolean updateCoordinator(String internalNodes)
    {
        requireNonNull(internalNodes, "internalNodes is null");
        ObjectMapper mapper = new ObjectMapper();
        TypeReference<Set<Object>> typeRef = new TypeReference<Set<Object>>() {};
        try {
            Set<Object> objects = mapper.readValue(internalNodes, typeRef);
            coordinatorInfo.setInternalNodes(objects);
        }
        catch (IOException e) {
            e.printStackTrace();
        }
        coordinatorInfo.setEnvironment(environment);
        coordinatorInfo.setUpdateTime(System.currentTimeMillis());
        return true;
    }

    @ResourceSecurity(MANAGEMENT_WRITE)
    @PUT
    @Path("state")
    @Consumes(APPLICATION_JSON)
    @Produces(TEXT_PLAIN)
    public Response updateState(NodeState state)
            throws WebApplicationException
    {
        requireNonNull(state, "state is null");
        switch (state) {
            case SHUTTING_DOWN:
                shutdownHandler.requestShutdown();
                return Response.ok().build();
            case ACTIVE:
            case INACTIVE:
                throw new WebApplicationException(Response
                        .status(BAD_REQUEST)
                        .type(MediaType.TEXT_PLAIN)
                        .entity(format("Invalid state transition to %s", state))
                        .build());
            default:
                return Response.status(BAD_REQUEST)
                        .type(TEXT_PLAIN)
                        .entity(format("Invalid state %s", state))
                        .build();
        }
    }

    @ResourceSecurity(PUBLIC)
    @GET
    @Path("state")
    @Produces(APPLICATION_JSON)
    public NodeState getServerState()
    {
        if (shutdownHandler.isShutdownRequested()) {
            return SHUTTING_DOWN;
        }
        else {
            return ACTIVE;
        }
    }

    @ResourceSecurity(PUBLIC)
    @GET
    @Path("coordinator")
    @Produces(TEXT_PLAIN)
    public Response getServerCoordinator()
    {
        if (coordinator) {
            return Response.ok().build();
        }
        // return 404 to allow load balancers to only send traffic to the coordinator
        return Response.status(Response.Status.NOT_FOUND).build();
    }

    public void startupComplete()
    {
        checkState(startupComplete.compareAndSet(false, true), "Server startup already marked as complete");
    }
}
