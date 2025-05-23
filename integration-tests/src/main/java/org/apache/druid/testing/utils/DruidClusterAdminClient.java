/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.testing.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.github.dockerjava.netty.NettyDockerCmdExecFactory;
import com.google.inject.Inject;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.Pair;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.java.util.http.client.HttpClient;
import org.apache.druid.java.util.http.client.Request;
import org.apache.druid.java.util.http.client.response.StatusResponseHandler;
import org.apache.druid.java.util.http.client.response.StatusResponseHolder;
import org.apache.druid.server.coordinator.CoordinatorDynamicConfig;
import org.apache.druid.testing.IntegrationTestingConfig;
import org.apache.druid.testing.guice.TestClient;
import org.jboss.netty.channel.ChannelException;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;

import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class DruidClusterAdminClient
{
  public static final String COORDINATOR_DOCKER_CONTAINER_NAME = "/druid-coordinator";
  public static final String COORDINATOR_TWO_DOCKER_CONTAINER_NAME = "/druid-coordinator-two";
  public static final String HISTORICAL_DOCKER_CONTAINER_NAME = "/druid-historical";
  public static final String OVERLORD_DOCKER_CONTAINER_NAME = "/druid-overlord";
  public static final String OVERLORD_TWO_DOCKER_CONTAINER_NAME = "/druid-overlord-two";
  public static final String BROKER_DOCKER_CONTAINER_NAME = "/druid-broker";
  public static final String ROUTER_DOCKER_CONTAINER_NAME = "/druid-router";
  public static final String MIDDLEMANAGER_DOCKER_CONTAINER_NAME = "/druid-middlemanager";

  private static final Logger LOG = new Logger(DruidClusterAdminClient.class);

  private final ObjectMapper jsonMapper;
  private final HttpClient httpClient;
  private IntegrationTestingConfig config;

  @Inject
  DruidClusterAdminClient(
      ObjectMapper jsonMapper,
      @TestClient HttpClient httpClient,
      IntegrationTestingConfig config
  )
  {
    this.jsonMapper = jsonMapper;
    this.httpClient = httpClient;
    this.config = config;
  }

  public void restartCoordinatorContainer()
  {
    restartDockerContainer(COORDINATOR_DOCKER_CONTAINER_NAME);
  }

  public void restartCoordinatorTwoContainer()
  {
    restartDockerContainer(COORDINATOR_TWO_DOCKER_CONTAINER_NAME);
  }

  public void restartHistoricalContainer()
  {
    restartDockerContainer(HISTORICAL_DOCKER_CONTAINER_NAME);
  }

  public void restartOverlordContainer()
  {
    restartDockerContainer(OVERLORD_DOCKER_CONTAINER_NAME);
  }

  public void restartOverlordTwoContainer()
  {
    restartDockerContainer(OVERLORD_TWO_DOCKER_CONTAINER_NAME);
  }

  public void restartBrokerContainer()
  {
    restartDockerContainer(BROKER_DOCKER_CONTAINER_NAME);
  }

  public void restartRouterContainer()
  {
    restartDockerContainer(ROUTER_DOCKER_CONTAINER_NAME);
  }

  public void restartMiddleManagerContainer()
  {
    restartDockerContainer(MIDDLEMANAGER_DOCKER_CONTAINER_NAME);
  }

  public void waitUntilCoordinatorReady()
  {
    waitUntilInstanceReady(config.getCoordinatorUrl());
    postDynamicConfig(CoordinatorDynamicConfig.builder()
                                              .withMarkSegmentAsUnusedDelayMillis(1)
                                              .build());
  }

  public void waitUntilCoordinatorTwoReady()
  {
    waitUntilInstanceReady(config.getCoordinatorTwoUrl());
    postDynamicConfig(CoordinatorDynamicConfig.builder()
                                              .withMarkSegmentAsUnusedDelayMillis(1)
                                              .build());
  }

  public void waitUntilOverlordTwoReady()
  {
    waitUntilInstanceReady(config.getOverlordTwoUrl());
  }

  public void waitUntilHistoricalReady()
  {
    waitUntilInstanceReady(config.getHistoricalUrl());
  }

  public void waitUntilIndexerReady()
  {
    waitUntilInstanceReady(config.getOverlordUrl());
  }

  public void waitUntilBrokerReady()
  {
    waitUntilInstanceReady(config.getBrokerUrl());
  }

  public void waitUntilRouterReady()
  {
    waitUntilInstanceReady(config.getRouterUrl());
  }

  public Pair<String, String> runCommandInCoordinatorContainer(String... cmd) throws Exception
  {
    return runCommandInDockerContainer(COORDINATOR_DOCKER_CONTAINER_NAME, cmd);
  }

  public Pair<String, String> runCommandInCoordinatorTwoContainer(String... cmd) throws Exception
  {
    return runCommandInDockerContainer(COORDINATOR_TWO_DOCKER_CONTAINER_NAME, cmd);
  }

  public Pair<String, String> runCommandInHistoricalContainer(String... cmd) throws Exception
  {
    return runCommandInDockerContainer(HISTORICAL_DOCKER_CONTAINER_NAME, cmd);
  }

  public Pair<String, String> runCommandInOverlordContainer(String... cmd) throws Exception
  {
    return runCommandInDockerContainer(OVERLORD_DOCKER_CONTAINER_NAME, cmd);
  }

  public Pair<String, String> runCommandInOverlordTwoContainer(String... cmd) throws Exception
  {
    return runCommandInDockerContainer(OVERLORD_TWO_DOCKER_CONTAINER_NAME, cmd);
  }

  public Pair<String, String> runCommandInBrokerContainer(String... cmd) throws Exception
  {
    return runCommandInDockerContainer(BROKER_DOCKER_CONTAINER_NAME, cmd);
  }

  public Pair<String, String> runCommandInRouterContainer(String... cmd) throws Exception
  {
    return runCommandInDockerContainer(ROUTER_DOCKER_CONTAINER_NAME, cmd);
  }

  public Pair<String, String> runCommandInMiddleManagerContainer(String... cmd) throws Exception
  {
    return runCommandInDockerContainer(MIDDLEMANAGER_DOCKER_CONTAINER_NAME, cmd);
  }

  public Pair<String, String> runCommandInDockerContainer(String serviceName, String... cmd) throws Exception
  {
    DockerClient dockerClient = newClient();
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    ExecCreateCmdResponse execCreateCmdResponse = dockerClient.execCreateCmd(findDockerContainer(dockerClient, serviceName))
                                                              .withAttachStderr(true)
                                                              .withAttachStdout(true)
                                                              .withCmd(cmd)
                                                              .exec();
    dockerClient.execStartCmd(execCreateCmdResponse.getId())
                .exec(new ExecStartResultCallback(stdout, stderr))
                .awaitCompletion();

    return new Pair<>(stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
  }

  public void restartDockerContainer(String serviceName)
  {
    DockerClient dockerClient = newClient();
    dockerClient.restartContainerCmd(findDockerContainer(dockerClient, serviceName)).exec();
  }

  public void killAndRestartDockerContainer(String serviceName)
  {
    final DockerClient dockerClient = newClient();
    final String containerId = findDockerContainer(dockerClient, serviceName);

    dockerClient.killContainerCmd(containerId).withSignal("SIGKILL").exec();
    dockerClient.startContainerCmd(containerId).exec();
  }

  private static DockerClient newClient()
  {
    return DockerClientBuilder
        .getInstance()
        .withDockerCmdExecFactory((new NettyDockerCmdExecFactory()).withConnectTimeout(10 * 1000))
        .build();
  }

  private String findDockerContainer(DockerClient dockerClient, String serviceName)
  {

    List<Container> containers = dockerClient.listContainersCmd().exec();
    Optional<String> containerName = containers
        .stream()
        .filter(container -> Arrays.asList(container.getNames()).contains(serviceName))
        .findFirst()
        .map(Container::getId);

    if (!containerName.isPresent()) {
      LOG.error("Cannot find docker container for " + serviceName);
      throw new ISE("Cannot find docker container for " + serviceName);
    }
    return containerName.get();
  }

  private void waitUntilInstanceReady(final String host)
  {
    ITRetryUtil.retryUntilEquals(
        () -> {
          try {
            StatusResponseHolder response = httpClient.go(
                new Request(HttpMethod.GET, new URL(StringUtils.format("%s/status/health", host))),
                StatusResponseHandler.getInstance()
            ).get();

            LOG.info("%s %s", response.getStatus(), response.getContent());
            return response.getStatus().equals(HttpResponseStatus.OK) ? "READY" : "";
          }
          catch (Throwable e) {
            //
            // suppress stack trace logging for some specific exceptions
            // to reduce excessive stack trace messages when waiting druid nodes to start up
            //
            if (e.getCause() instanceof ChannelException) {
              Throwable channelException = e.getCause();

              if (channelException.getCause() instanceof ClosedChannelException) {
                LOG.error("Channel Closed");
              } else if ("Channel disconnected".equals(channelException.getMessage())) {
                // log message only
                LOG.error("Channel disconnected");
              } else {
                // log stack trace for unknown exception
                LOG.error(e, "Error while waiting for [%s] to be ready", host);
              }
            } else {
              // log stack trace for unknown exception
              LOG.error(e, "Error while waiting for [%s] to be ready", host);
            }

            return "";
          }
        },
        "READY",
        StringUtils.format("Instance[%s]", host)
    );
  }

  private void postDynamicConfig(CoordinatorDynamicConfig coordinatorDynamicConfig)
  {
    ITRetryUtil.retryUntilTrue(
        () -> {
          try {
            String url = StringUtils.format("%s/druid/coordinator/v1/config", config.getCoordinatorUrl());
            StatusResponseHolder response = httpClient.go(
                new Request(HttpMethod.POST, new URL(url)).setContent(
                    "application/json",
                    jsonMapper.writeValueAsBytes(coordinatorDynamicConfig)
                ), StatusResponseHandler.getInstance()
            ).get();

            LOG.info("%s %s", response.getStatus(), response.getContent());
            // if coordinator is not leader then it will return 307 instead of 200
            return response.getStatus().equals(HttpResponseStatus.OK) || response.getStatus().equals(HttpResponseStatus.TEMPORARY_REDIRECT);
          }
          catch (Throwable e) {
            LOG.error(e, "Error while posting dynamic config");
            return false;
          }
        },
        "Posting dynamic config after startup"
    );
  }
}
