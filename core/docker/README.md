# Fusion Docker Image

## About the Container
This Docker image is designed to provide the following
* An out-of-the-box single node cluster with the JMX, memory, TPC-DS, and TPC-H
 catalogs
* An image that can be deployed as a full cluster by mounting in configuration
* An image to be used as the basis for the Kubernetes Fusion operator

## Quickstart

### Run the Fusion server

You can launch a single node Fusion cluster for testing purposes.
The Fusion node will function both as a coordinator and a worker.
To launch it, execute the following:

```bash
docker run -p 8080:8080 --name fusion fusiondb/fusion
```

Wait for the following message log line:
```
INFO	main	io.fusion.server.Server	======== SERVER STARTED ========
```

The Fusion server is now running on `localhost:8080` (the default port).

### Run the Fsuion CLI

Run the [Fusion CLI](https://fusion.io/docs/current/installation/cli.html),
which connects to `localhost:8080` by default:

```bash
docker exec -it fusion fusion
```

You can pass additional arguments to the Fusion CLI:

```bash
docker exec -it fusion fusion --catalog tpch --schema sf1
```

## Configuration

Configuration is expected to be mounted `/etc/fusion`. If it is not mounted
then the default single node configuration will be used.

### Specific Config Options

#### `node.id`

The container supplied `run-fusion` command will set the config property
`node.id` to the hostname of the container if it is not specified in the
`node.properties` file. This allows for `node.properties` to be a static file
across all worker nodes if desired. Additionally this has the added benefit of
`node.id` being consistent, predictable, and stable through restarts.

#### `node.data-dir`

The default configuration uses `/data/fusion` as the default for
`node.data-dir`. Thus if using the default configuration and a mounted volume
is desired for the data directory it should be mounted to `/data/fusion`.

## Building a custom Docker image

To build an image for a locally modified version of Fusion, run the Maven
build as normal for the `fusion-server` and `fusion-cli` modules, then
build the image:

```bash
./build-local.sh
```

The Docker build process will print the ID of the image, which will also
be tagged with `fusion:xxx-SNAPSHOT`, where `xxx-SNAPSHOT` is the version
number of the Fusion Maven build.

## Getting Help

Join the FusionDB community [Slack](https://fusiondb.cn/slack.html).
